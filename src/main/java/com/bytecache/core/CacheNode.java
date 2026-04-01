package com.bytecache.core;

public class CacheNode<K, V> {
    public K key;
    public V value;
    public long expiryTime;
    public int frequency;
    public CacheNode<K, V> prev;
    public CacheNode<K, V> next;

    public CacheNode(K key, V value, long expiryTime) {
        this.key = key;
        this.value = value;
        this.expiryTime = expiryTime;
        this.frequency = 1; // Important: A node starts with frequency 1 when accessed for the first time
    }

    public boolean isExpired(long currentTimeMillis) {
        return expiryTime > 0 && currentTimeMillis > expiryTime;
    }
}
