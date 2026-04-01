package com.bytecache.cache;

public interface Cache<K, V> {
    void set(K key, V value);
    void set(K key, V value, long ttlMillis);
    V get(K key);
    void delete(K key);
    int size();
    void clear();
    void shutdown();
}
