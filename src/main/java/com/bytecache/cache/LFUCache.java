package com.bytecache.cache;

import com.bytecache.core.CacheNode;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class LFUCache<K, V> implements Cache<K, V> {
    private final int capacity;
    private final Map<K, CacheNode<K, V>> map;
    private final PriorityQueue<CacheNode<K, V>> minHeap;

    private final ReentrantReadWriteLock rwLock;
    private final Lock readLock;
    private final Lock writeLock;

    private final ScheduledExecutorService ttlExecutor;

    public LFUCache(int capacity) {
        this.capacity = capacity;
        this.map = new HashMap<>();
        // Min-heap based on frequency. For ties, use an access time or just keep it simple.
        this.minHeap = new PriorityQueue<>(Comparator.comparingInt(node -> node.frequency));
        this.rwLock = new ReentrantReadWriteLock();
        this.readLock = rwLock.readLock();
        this.writeLock = rwLock.writeLock();
        this.ttlExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TTL-Cleanup-Thread-LFU");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void set(K key, V value) {
        set(key, value, 0);
    }

    @Override
    public void set(K key, V value, long ttlMillis) {
        long expiryTime = ttlMillis > 0 ? System.currentTimeMillis() + ttlMillis : 0;

        writeLock.lock();
        try {
            if (map.containsKey(key)) {
                CacheNode<K, V> node = map.get(key);
                node.value = value;
                node.expiryTime = expiryTime;
                node.frequency++;
                minHeap.remove(node); // O(n) remove from PriorityQueue in Java, but acceptable for this assignment bounds O(log n) inserts
                minHeap.add(node);
            } else {
                if (map.size() >= capacity) {
                    CacheNode<K, V> lfu = minHeap.poll();
                    if (lfu != null) {
                        map.remove(lfu.key);
                    }
                }
                CacheNode<K, V> newNode = new CacheNode<>(key, value, expiryTime);
                map.put(key, newNode);
                minHeap.add(newNode);
            }
        } finally {
            writeLock.unlock();
        }

        if (ttlMillis > 0) {
            scheduleEviction(key, ttlMillis);
        }
    }

    @Override
    public V get(K key) {
        readLock.lock();
        CacheNode<K, V> node;
        try {
            node = map.get(key);
            if (node == null) {
                return null;
            }
            if (node.isExpired(System.currentTimeMillis())) {
                readLock.unlock();
                try {
                    delete(key);
                } finally {
                    readLock.lock();
                }
                return null;
            }
        } finally {
            readLock.unlock();
        }

        writeLock.lock();
        try {
            if (map.containsKey(key)) {
                node.frequency++;
                minHeap.remove(node);
                minHeap.add(node);
            }
        } finally {
            writeLock.unlock();
        }

        return node.value;
    }

    @Override
    public void delete(K key) {
        writeLock.lock();
        try {
            CacheNode<K, V> node = map.remove(key);
            if (node != null) {
                minHeap.remove(node);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public int size() {
        readLock.lock();
        try {
            return map.size();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void clear() {
        writeLock.lock();
        try {
            map.clear();
            minHeap.clear();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void shutdown() {
        ttlExecutor.shutdownNow();
    }

    private void scheduleEviction(K key, long delayMillis) {
        ttlExecutor.schedule(() -> {
            boolean expired = false;
            readLock.lock();
            try {
                CacheNode<K, V> node = map.get(key);
                if (node != null && node.isExpired(System.currentTimeMillis())) {
                    expired = true;
                }
            } finally {
                readLock.unlock();
            }
            if (expired) {
                delete(key);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }
}
