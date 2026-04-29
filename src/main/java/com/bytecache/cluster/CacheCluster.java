package com.bytecache.cluster;

import com.bytecache.cache.Cache;
import com.bytecache.cache.LRUCache;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * CacheCluster implements a multi-node, consistent-hashing-based sharding layer
 * over independent LRUCache instances.
 *
 * <p>Each "node" is an isolated {@link LRUCache} shard. Keys are mapped to shards
 * via a consistent hash ring so that adding or removing nodes only remaps a
 * proportional fraction of keys (not all keys, as with naive modular hashing).
 *
 * <p>Architecture:
 * <pre>
 *   Client
 *     │
 *     ▼
 *   CacheCluster  ← consistent hash ring (TreeMap of virtual nodes)
 *     │
 *     ├── LRUCache[0]  (shard 0)
 *     ├── LRUCache[1]  (shard 1)
 *     └── LRUCache[N]  (shard N)
 * </pre>
 *
 * <p>Each shard maintains its own {@code ReentrantReadWriteLock}, so concurrent
 * operations on different shards proceed fully in parallel — this is the key
 * scalability advantage over a single-lock design.
 */
public class CacheCluster<V> implements Cache<String, V> {

    /** Number of virtual nodes per physical shard on the hash ring. */
    private static final int VIRTUAL_NODES_PER_SHARD = 150;

    /** Sorted ring mapping hash-positions → shard index. */
    private final SortedMap<Integer, Integer> ring = new TreeMap<>();

    /** Physical cache shards. */
    private final List<LRUCache<String, V>> shards;

    private final int numShards;

    /**
     * Creates a cluster of {@code numShards} independent LRU cache nodes,
     * each with {@code capacityPerShard} entries.
     *
     * @param numShards        number of independent cache nodes (shards)
     * @param capacityPerShard maximum entries per shard
     */
    public CacheCluster(int numShards, int capacityPerShard) {
        if (numShards < 1) throw new IllegalArgumentException("numShards must be >= 1");
        this.numShards = numShards;
        this.shards = new ArrayList<>(numShards);

        // Instantiate each shard
        for (int i = 0; i < numShards; i++) {
            shards.add(new LRUCache<>(capacityPerShard));
        }

        // Populate consistent hash ring with virtual nodes
        for (int shardIndex = 0; shardIndex < numShards; shardIndex++) {
            for (int v = 0; v < VIRTUAL_NODES_PER_SHARD; v++) {
                String virtualKey = "shard-" + shardIndex + "-vnode-" + v;
                int hash = consistentHash(virtualKey);
                ring.put(hash, shardIndex);
            }
        }
    }

    /**
     * Returns the shard responsible for the given key by walking the ring
     * clockwise to the first virtual node at or after {@code hash(key)}.
     */
    private LRUCache<String, V> shardFor(String key) {
        int hash = consistentHash(key);
        SortedMap<Integer, Integer> tail = ring.tailMap(hash);
        int shardIndex = tail.isEmpty()
                ? ring.get(ring.firstKey())   // wrap around the ring
                : tail.get(tail.firstKey());
        return shards.get(shardIndex);
    }

    /**
     * FNV-1a inspired hash — produces a well-distributed positive int.
     * Using a simple but effective non-cryptographic hash is standard for
     * consistent hashing rings (e.g., Ketama uses MD5; we use FNV for speed).
     */
    private int consistentHash(String key) {
        int hash = 0x811c9dc5;
        for (char c : key.toCharArray()) {
            hash ^= c;
            hash *= 0x01000193;
        }
        // Ensure positive value for TreeMap ordering
        return hash & Integer.MAX_VALUE;
    }

    // ─────────────────────── Cache<String,V> impl ───────────────────────

    @Override
    public void set(String key, V value) {
        shardFor(key).set(key, value);
    }

    @Override
    public void set(String key, V value, long ttlMillis) {
        shardFor(key).set(key, value, ttlMillis);
    }

    @Override
    public V get(String key) {
        return shardFor(key).get(key);
    }

    @Override
    public void delete(String key) {
        shardFor(key).delete(key);
    }

    @Override
    public int size() {
        int total = 0;
        for (LRUCache<String, V> shard : shards) {
            total += shard.size();
        }
        return total;
    }

    @Override
    public void clear() {
        for (LRUCache<String, V> shard : shards) {
            shard.clear();
        }
    }

    @Override
    public void shutdown() {
        for (LRUCache<String, V> shard : shards) {
            shard.shutdown();
        }
    }

    /**
     * Returns the shard index that owns a given key — useful for debugging
     * and verifying key distribution.
     */
    public int shardIndexFor(String key) {
        int hash = consistentHash(key);
        SortedMap<Integer, Integer> tail = ring.tailMap(hash);
        return tail.isEmpty()
                ? ring.get(ring.firstKey())
                : tail.get(tail.firstKey());
    }

    /** Returns the number of physical shards in this cluster. */
    public int getNumShards() {
        return numShards;
    }
}
