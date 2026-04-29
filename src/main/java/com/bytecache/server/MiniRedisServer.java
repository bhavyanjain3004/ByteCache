package com.bytecache.server;

import com.bytecache.cache.Cache;
import com.bytecache.cache.EvictionPolicy;
import com.bytecache.cache.LFUCache;
import com.bytecache.cache.LRUCache;
import com.bytecache.cluster.CacheCluster;
import com.bytecache.pubsub.PubSubEngine;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiniRedisServer {
    private final Cache<String, String> cache;
    private final PubSubEngine pubSubEngine;
    private final int port;
    private final ExecutorService clientPool;

    public MiniRedisServer(int port, EvictionPolicy policy, int capacity) {
        this(port, policy, capacity, 1);
    }

    /**
     * Multi-node constructor. When {@code numNodes} > 1, all cache operations
     * are routed through a {@link CacheCluster} with consistent hashing.
     * Each shard gets {@code capacity / numNodes} entries.
     *
     * @param numNodes number of independent cache shards (1 = single-node mode)
     */
    public MiniRedisServer(int port, EvictionPolicy policy, int capacity, int numNodes) {
        this.port = port;
        if (numNodes > 1) {
            int capacityPerShard = Math.max(1, capacity / numNodes);
            this.cache = new CacheCluster<>(numNodes, capacityPerShard);
            System.out.println("[Cluster] " + numNodes + " nodes, " + capacityPerShard + " capacity/shard");
        } else if (policy == EvictionPolicy.LFU) {
            this.cache = new LFUCache<>(capacity);
        } else {
            this.cache = new LRUCache<>(capacity);
        }
        this.pubSubEngine = new PubSubEngine();
        this.clientPool = Executors.newCachedThreadPool();
    }

    public void start() throws IOException {
        System.out.println("Starting MiniRedisServer on port " + port + " with " + cache.getClass().getSimpleName());
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (!Thread.currentThread().isInterrupted()) {
                Socket clientSocket = serverSocket.accept();
                clientPool.submit(new ClientHandler(clientSocket, cache, pubSubEngine));
            }
        } finally {
            cache.shutdown();
            pubSubEngine.shutdown();
        }
    }

    public static void main(String[] args) throws IOException {
        int port = 6379;
        EvictionPolicy policy = EvictionPolicy.LRU;
        int capacity = 1000;
        int nodes = 1; // default: single-node mode

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--policy") && i + 1 < args.length) {
                policy = EvictionPolicy.valueOf(args[++i].toUpperCase());
            } else if (args[i].equals("--capacity") && i + 1 < args.length) {
                capacity = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--nodes") && i + 1 < args.length) {
                nodes = Integer.parseInt(args[++i]);
            }
        }

        MiniRedisServer server = new MiniRedisServer(port, policy, capacity, nodes);
        server.start();
    }
}
