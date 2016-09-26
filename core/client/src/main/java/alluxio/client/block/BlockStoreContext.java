/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.client.block;

import alluxio.Configuration;
import alluxio.Constants;
import alluxio.PropertyKey;
import alluxio.client.ClientContext;
import alluxio.exception.ExceptionMessage;
import alluxio.metrics.MetricsSystem;
import alluxio.network.connection.NettyChannelPool;
import alluxio.resource.CloseableResource;
import alluxio.thrift.BlockWorkerClientService;
import alluxio.util.IdUtils;
import alluxio.util.network.NetworkAddressUtils;
import alluxio.wire.WorkerInfo;
import alluxio.wire.WorkerNetAddress;

import com.codahale.metrics.Gauge;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.util.internal.chmv8.ConcurrentHashMapV8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A shared context for each master for common block master client functionality such as a pool
 * of master clients and a pool of local worker clients. Any remote clients will be created and
 * destroyed on a per use basis.
 * <p/>
 * NOTE: The context maintains a pool of block master clients and a pool of block worker clients
 * that are already thread-safe. Synchronizing {@link BlockStoreContext} methods could lead to
 * deadlock: thread A attempts to acquire a client when there are no clients left in the pool and
 * blocks holding a lock on the {@link BlockStoreContext}, when thread B attempts to release a
 * client it owns, it is unable to do so, because thread A holds the lock on
 * {@link BlockStoreContext}.
 */
@ThreadSafe
public final class BlockStoreContext {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);
  private BlockMasterClientPool mBlockMasterClientPool;

  private static final ConcurrentHashMapV8<InetSocketAddress, BlockWorkerThriftClientPool>
      BLOCK_WORKER_THRIFT_CLIENT_POOL = new ConcurrentHashMapV8<>();

  private static final ConcurrentHashMapV8<InetSocketAddress, NettyChannelPool>
      NETTY_CHANNEL_POOL_MAP = new ConcurrentHashMapV8<>();

  /**
   * Only one context will be kept for each master address.
   */
  private static final Map<InetSocketAddress, BlockStoreContext> CACHED_CONTEXTS =
      new ConcurrentHashMap<>();

  /**
   * Indicates whether there is Alluxio workers running in the local machine. This is initialized
   * lazily.
   */
  @GuardedBy("this")
  private Boolean mHasLocalWorker;

  static {
    Metrics.initializeGauges();
  }

  /**
   * Creates a new block store context.
   */
  private BlockStoreContext(InetSocketAddress masterAddress) {
    mBlockMasterClientPool = new BlockMasterClientPool(masterAddress);
  }

  /**
   * Gets a context with the specified master address from the cache if it's created before.
   * Otherwise creates a new one and puts it in the cache.
   *
   * @param masterAddress the master's address
   * @return the context created or cached before
   */
  public static synchronized BlockStoreContext get(InetSocketAddress masterAddress) {
    BlockStoreContext context = CACHED_CONTEXTS.get(masterAddress);
    if (context == null) {
      context = new BlockStoreContext(masterAddress);
      CACHED_CONTEXTS.put(masterAddress, context);
    }
    return context;
  }

  /**
   * Gets a context using the master address got from config.
   *
   * @return the context created or cached before
   */
  public static synchronized BlockStoreContext get() {
    return get(ClientContext.getMasterAddress());
  }

  /**
   * Gets the worker addresses with the given hostname by querying the master. Returns all the
   * addresses, if the hostname is an empty string.
   *
   * @param hostname hostname of the worker to query, empty string denotes any worker
   * @return a list of {@link WorkerNetAddress} with the given hostname
   */
  private List<WorkerNetAddress> getWorkerAddresses(String hostname) {
    List<WorkerNetAddress> addresses = new ArrayList<>();
    try (CloseableResource<BlockMasterClient> masterClient = acquireMasterClientResource()) {
      List<WorkerInfo> workers = masterClient.get().getWorkerInfoList();
      for (WorkerInfo worker : workers) {
        if (hostname.isEmpty() || worker.getAddress().getHost().equals(hostname)) {
          addresses.add(worker.getAddress());
        }
      }
    } catch (Exception e) {
      Throwables.propagate(e);
    }
    return addresses;
  }

  /**
   * Acquires a block master client resource from the block master client pool. The resource is
   * {@code Closeable}.
   *
   * @return the acquired block master client resource
   */
  public CloseableResource<BlockMasterClient> acquireMasterClientResource() {
    return new CloseableResource<BlockMasterClient>(mBlockMasterClientPool.acquire()) {
      @Override
      public void close() {
        mBlockMasterClientPool.release(get());
      }
    };
  }

  /**
   * Obtains a client for a worker with the given address.
   *
   * @param address the address of the worker to get a client to
   * @return a {@link BlockWorkerClient} connected to the worker with the given hostname
   * @throws IOException if it fails to create a client for a given hostname (e.g. no Alluxio
   *        worker is available for the given hostname)
   */
  public BlockWorkerClient acquireWorkerClient(WorkerNetAddress address) throws IOException {
    Preconditions.checkArgument(address != null, ExceptionMessage.NO_WORKER_AVAILABLE.getMessage());
    long clientId = IdUtils.getRandomNonNegativeLong();
    return new RetryHandlingBlockWorkerClient(address,
        ClientContext.getBlockClientExecutorService(), clientId);
  }

  /**
   * Releases the {@link BlockWorkerClient} back to the client pool, or destroys it if it was a
   * remote client.
   *
   * @param blockWorkerClient the worker client to release, the client should not be accessed after
   *        this method is called
   */
  public void releaseWorkerClient(BlockWorkerClient blockWorkerClient) {
    blockWorkerClient.close();
  }

  /**
   * Acquires a netty channel from the channel pools.
   *
   * @param address the network address of the channel
   * @param bootstrapBuilder the bootstrap builder that creates a new bootstrap upon called
   * @return the acquired netty channel
   * @throws IOException if it fails to acquire the netty channel from the pools
   */
  public static Channel acquireNettyChannel(final InetSocketAddress address,
      final Callable<Bootstrap> bootstrapBuilder) throws IOException {
    if (!NETTY_CHANNEL_POOL_MAP.containsKey(address)) {
      Callable<Bootstrap> bootstrapBuilderClone = new Callable<Bootstrap>() {
        @Override
        public Bootstrap call() throws Exception {
          Bootstrap bs = bootstrapBuilder.call();
          bs.remoteAddress(address);
          return bs;
        }
      };
      NettyChannelPool pool = new NettyChannelPool(
          bootstrapBuilderClone,
          Configuration.getInt(PropertyKey.USER_NETWORK_NETTY_CHANNEL_POOL_SIZE_MAX),
          Configuration.getInt(PropertyKey.USER_NETWORK_NETTY_CHANNEL_POOL_GC_THRESHOLD_SECS));
      if (NETTY_CHANNEL_POOL_MAP.putIfAbsent(address, pool) != null) {
        pool.close();
      }
    }
    try {
      return NETTY_CHANNEL_POOL_MAP.get(address).acquire();
    } catch (IOException e) {
      LOG.error(String.format("Failed to acquire netty channel %s.", address), e);
      throw e;
    }
  }

  /**
   * Releases a netty channel to the channel pools.
   *
   * @param address the network address of the channel
   * @param channel the channel to release
   */
  public static void releaseNettyChannel(InetSocketAddress address, Channel channel) {
    Preconditions.checkArgument(NETTY_CHANNEL_POOL_MAP.containsKey(address));
    NETTY_CHANNEL_POOL_MAP.get(address).release(channel);
  }

  /**
   * Acquires a block worker thrift client from the block worker thrift client pools.
   *
   * @param address the address of the block worker
   * @return the block worker thrift client
   * @throws IOException if it fails to connect to remote worker
   */
  public static BlockWorkerClientService.Client acquireBlockWorkerThriftClient(
      final InetSocketAddress address) throws IOException {
    if (!BLOCK_WORKER_THRIFT_CLIENT_POOL.containsKey(address)) {
      BlockWorkerThriftClientPool pool = new BlockWorkerThriftClientPool(address,
          Configuration.getInt(PropertyKey.USER_BLOCK_WORKER_CLIENT_POOL_SIZE_MAX),
          Configuration.getInt(PropertyKey.USER_BLOCK_WORKER_CLIENT_POOL_GC_THRESHOLD_SECS));
      if (BLOCK_WORKER_THRIFT_CLIENT_POOL.putIfAbsent(address, pool) != null) {
        pool.close();
      }
    }
    try {
      return BLOCK_WORKER_THRIFT_CLIENT_POOL.get(address).acquire();
    } catch (IOException e) {
      LOG.error(String.format("Failed to connect to block worker %s.", address), e);
      throw e;
    }
  }

  /**
   * Releases the block worker thrift client to the pool.
   *
   * @param address the network address of the block worker thrift client
   * @param client the block worker thrift client
   */
  public static void releaseBlockWorkerThriftClient(InetSocketAddress address,
      BlockWorkerClientService.Client client) {
    Preconditions.checkArgument(BLOCK_WORKER_THRIFT_CLIENT_POOL.containsKey(address));
    BLOCK_WORKER_THRIFT_CLIENT_POOL.get(address).release(client);
  }

  /**
   * @return if there is a local worker running the same machine
   */
  public synchronized boolean hasLocalWorker() {
    if (mHasLocalWorker == null) {
      mHasLocalWorker = !getWorkerAddresses(NetworkAddressUtils.getLocalHostName()).isEmpty();
    }
    return mHasLocalWorker;
  }

  /**
   * Class that contains metrics about BlockStoreContext.
   */
  @ThreadSafe
  private static final class Metrics {
    private static void initializeGauges() {
      MetricsSystem.registerGaugeIfAbsent(MetricsSystem.getClientMetricName("NettyConnectionsOpen"),
          new Gauge<Long>() {
            @Override
            public Long getValue() {
              long ret = 0;
              for (NettyChannelPool pool : NETTY_CHANNEL_POOL_MAP.values()) {
                ret += pool.size();
              }
              return ret;
            }
          });
    }

    private Metrics() {} // prevent instantiation
  }
}

