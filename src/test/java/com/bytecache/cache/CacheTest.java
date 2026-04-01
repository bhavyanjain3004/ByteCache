package com.bytecache.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CacheTest {

    @Test
    void testLRUEviction() {
        LRUCache<String, String> cache = new LRUCache<>(3);
        cache.set("1", "A");
        cache.set("2", "B");
        cache.set("3", "C"); // 1, 2, 3
        cache.get("1"); // 1 is now most recent, 2 is least recent
        cache.set("4", "D"); // 2 is evicted

        assertNull(cache.get("2"));
        assertEquals("A", cache.get("1"));
        assertEquals("C", cache.get("3"));
        assertEquals("D", cache.get("4"));
    }

    @Test
    void testLFUEviction() {
        LFUCache<String, String> cache = new LFUCache<>(3);
        cache.set("1", "A");
        cache.set("2", "B");
        cache.set("3", "C"); 
        
        cache.get("1"); // freq=2
        cache.get("2"); // freq=2
        
        cache.set("4", "D"); // 3 has freq=1, will be evicted

        assertNull(cache.get("3"));
        assertEquals("A", cache.get("1"));
        assertEquals("D", cache.get("4"));
    }

    @Test
    void testTTLActiveEviction() throws InterruptedException {
        LRUCache<String, String> cache = new LRUCache<>(10);
        cache.set("ttlKey", "val", 100);
        
        assertEquals("val", cache.get("ttlKey"));
        
        Thread.sleep(150); // wait for TTL to expire
        
        assertNull(cache.get("ttlKey")); // either lazy or active should have caught it
    }
}
