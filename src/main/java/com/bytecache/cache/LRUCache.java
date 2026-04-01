package com.bytecache.cache;

import com.bytecache.core.CacheNode;
import com.bytecache.core.DoublyLinkedList;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class LRUCache<K, V> implements Cache<K, V> {
    private final int capacity;
    private final Map<K, CacheNode<K, V>> map;
    private final DoublyLinkedList<K, V> list;
    
    private final ReentrantReadWriteLock rwLock;
    private final Lock readLock;
    private final Lock writeLock;
    
    private final ScheduledExecutorService ttlExecutor;

    public LRUCache(int capacity) {
        this.capacity = capacity;
        this.map = new HashMap<>();
        this.list = new DoublyLinkedList<>();
        this.rwLock = new ReentrantReadWriteLock();
        this.readLock = rwLock.readLock();
        this.writeLock = rwLock.writeLock();
        this.ttlExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TTL-Cleanup-Thread");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void set(K key, V value) {
        set(key, value, 0); // 0 means no expiry
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
                list.moveToFirst(node);
            } else {
                if (map.size() >= capacity) {
                    CacheNode<K, V> lru = list.removeLast();
                    if (lru != null) {
                        map.remove(lru.key);
                    }
                }
                CacheNode<K, V> newNode = new CacheNode<>(key, value, expiryTime);
                list.addFirst(newNode);
                map.put(key, newNode);
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
            // Lazy expiry check
            if (node.isExpired(System.currentTimeMillis())) {
                // Must release read lock before acquiring write lock
                readLock.unlock();
                try {
                    delete(key);
                } finally {
                    readLock.lock(); // Re-acquire to maintain lock balance in the finally block below
                }
                return null;
            }
        } finally {
            readLock.unlock();
        }

        // We only move to first on access. Moving requires write lock!
        // To maintain O(1) and thread safety, moving to head makes it a write operation.
        writeLock.lock();
        try {
            if (map.containsKey(key)) { // Double-check in case it was deleted
                list.moveToFirst(node);
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
                list.remove(node);
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
            while (!list.isEmpty()) {
                list.removeLast();
            }
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
