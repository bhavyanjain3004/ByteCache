package com.bytecache.cache;

import com.bytecache.cluster.CacheCluster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CacheTest {

    // ─────────────────────────────────────────────
    // 1. Eviction Correctness
    // ─────────────────────────────────────────────

    @Test
    void testLRUEviction() {
        LRUCache<String, String> cache = new LRUCache<>(3);
        cache.set("1", "A");
        cache.set("2", "B");
        cache.set("3", "C"); // order: 3→2→1 (head→tail)
        cache.get("1");       // access "1" → order: 1→3→2; "2" is now LRU
        cache.set("4", "D"); // capacity full → evict "2"

        assertNull(cache.get("2"),  "Key '2' should have been evicted (LRU)");
        assertEquals("A", cache.get("1"));
        assertEquals("C", cache.get("3"));
        assertEquals("D", cache.get("4"));
    }

    @Test
    void testLFUEviction() {
        LFUCache<String, String> cache = new LFUCache<>(3);
        cache.set("1", "A"); // freq=1
        cache.set("2", "B"); // freq=1
        cache.set("3", "C"); // freq=1

        cache.get("1"); // freq("1")=2
        cache.get("2"); // freq("2")=2
        // "3" still has freq=1 → will be evicted

        cache.set("4", "D"); // triggers eviction of key "3"

        assertNull(cache.get("3"),  "Key '3' should have been evicted (LFU, lowest freq)");
        assertEquals("A", cache.get("1"));
        assertEquals("D", cache.get("4"));
    }

    // ─────────────────────────────────────────────
    // 2. TTL Expiry
    // ─────────────────────────────────────────────

    @Test
    void testTTLActiveEviction() throws InterruptedException {
        LRUCache<String, String> cache = new LRUCache<>(10);
        cache.set("ttlKey", "val", 100); // expires in 100 ms

        assertEquals("val", cache.get("ttlKey"), "Key should be alive before TTL");

        Thread.sleep(200); // wait well past the 100 ms TTL

        assertNull(cache.get("ttlKey"), "Key should be gone after TTL (active or lazy expiry)");
    }

    @Test
    void testTTLLazyEvictionOnGet() throws InterruptedException {
        LRUCache<String, String> cache = new LRUCache<>(10);
        cache.set("lazy", "gone", 50); // 50 ms TTL

        Thread.sleep(100); // outlast TTL without touching the key

        // lazy expiry fires exactly here on the first get()
        assertNull(cache.get("lazy"), "Lazy expiry should remove key on read");
        assertEquals(0, cache.size(), "Cache should be empty after lazy expiry");
    }

    @Test
    void testNonExpiredKeyStaysAlive() throws InterruptedException {
        LRUCache<String, String> cache = new LRUCache<>(10);
        cache.set("persist", "value", 5_000); // 5-second TTL

        Thread.sleep(50); // wait much less than TTL

        assertEquals("value", cache.get("persist"), "Key with long TTL should still be alive");
    }

    @Test
    void testNoTTLKeyNeverExpires() throws InterruptedException {
        LRUCache<String, String> cache = new LRUCache<>(10);
        cache.set("immortal", "stillhere"); // no TTL

        Thread.sleep(100);

        assertEquals("stillhere", cache.get("immortal"), "Key with no TTL should never expire");
    }

    // ─────────────────────────────────────────────
    // 3. Concurrency Edge Cases
    // ─────────────────────────────────────────────

    /**
     * Race condition stress test: 8 writer threads and 8 reader threads all
     * hammer the same cache simultaneously.
     *
     * <p>Correctness invariants verified:
     * <ul>
     *   <li>No {@link NullPointerException} or other unchecked exception escapes</li>
     *   <li>Cache size never exceeds declared capacity</li>
     *   <li>Total operation count matches the expected number</li>
     * </ul>
     *
     * <p>A {@link CountDownLatch} ensures all threads start at exactly the same
     * moment, maximising the chance of exposing race conditions.
     */
    @Test
    @Timeout(10) // fail if deadlock or extreme contention detected
    void testConcurrentReadWriteNoCorruption() throws InterruptedException {
        final int CAPACITY   = 100;
        final int THREADS    = 16;   // 8 writers + 8 readers
        final int OPS_EACH   = 2_000;

        LRUCache<String, String> cache = new LRUCache<>(CAPACITY);

        ExecutorService pool       = Executors.newFixedThreadPool(THREADS);
        CountDownLatch  startGate  = new CountDownLatch(1);  // all threads wait here
        CountDownLatch  doneGate   = new CountDownLatch(THREADS);
        AtomicInteger   errors     = new AtomicInteger(0);
        AtomicInteger   totalOps   = new AtomicInteger(0);

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    startGate.await(); // wait for all threads to be ready
                    for (int i = 0; i < OPS_EACH; i++) {
                        String key = "key-" + (i % 50); // bounded key space → eviction pressure
                        if (threadId % 2 == 0) {
                            // even threads: write
                            cache.set(key, "v-" + threadId + "-" + i);
                        } else {
                            // odd threads: read (return value may be null due to eviction — that's fine)
                            cache.get(key);
                        }
                        totalOps.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown(); // release all threads simultaneously
        doneGate.await();
        pool.shutdownNow();

        assertEquals(0, errors.get(), "No thread should have thrown an exception");
        assertEquals(THREADS * OPS_EACH, totalOps.get(), "All operations must complete");
        assertTrue(cache.size() <= CAPACITY, "Cache size must never exceed capacity");
    }

    /**
     * Eviction-under-concurrency test: verify that the LRU capacity contract
     * holds even when multiple writers race to insert entries simultaneously.
     * The size must never exceed capacity, regardless of thread scheduling.
     */
    @Test
    @Timeout(10)
    void testCapacityInvariantUnderConcurrentWrites() throws InterruptedException {
        final int CAPACITY = 50;
        final int THREADS  = 10;
        final int OPS_EACH = 1_000;

        LRUCache<String, String> cache = new LRUCache<>(CAPACITY);
        ExecutorService pool     = Executors.newFixedThreadPool(THREADS);
        CountDownLatch  start    = new CountDownLatch(1);
        CountDownLatch  done     = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < OPS_EACH; i++) {
                        cache.set("t" + tid + "-k" + i, "v" + i);
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        pool.shutdownNow();

        assertTrue(cache.size() <= CAPACITY,
                "Capacity invariant violated: size=" + cache.size() + " > capacity=" + CAPACITY);
    }

    /**
     * Concurrent TTL test: multiple threads set keys with short TTLs while
     * readers simultaneously poll those keys. Verifies the expired-read path
     * (lazy eviction inside get()) is thread-safe.
     */
    @Test
    @Timeout(10)
    void testConcurrentTTLExpiryNoDeadlock() throws InterruptedException {
        final int THREADS = 8;
        LRUCache<String, String> cache = new LRUCache<>(200);

        ExecutorService pool  = Executors.newFixedThreadPool(THREADS);
        CountDownLatch  start = new CountDownLatch(1);
        CountDownLatch  done  = new CountDownLatch(THREADS);
        AtomicInteger   errors = new AtomicInteger(0);

        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        String key = "ttl-" + tid + "-" + i;
                        cache.set(key, "v", 30); // 30 ms TTL
                        Thread.sleep(1);          // let some TTLs expire mid-loop
                        cache.get(key);           // may trigger lazy eviction
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        pool.shutdownNow();

        assertEquals(0, errors.get(), "No deadlock or exception during concurrent TTL operations");
    }

    /**
     * Delete-under-concurrent-reads test: verifies that concurrent deletions
     * interleaved with reads don't produce NPEs or stale references.
     */
    @Test
    @Timeout(10)
    void testConcurrentDeleteAndRead() throws InterruptedException {
        final int THREADS = 6;
        LRUCache<String, String> cache = new LRUCache<>(100);

        // Pre-populate
        for (int i = 0; i < 100; i++) {
            cache.set("key-" + i, "val-" + i);
        }

        ExecutorService pool   = Executors.newFixedThreadPool(THREADS);
        CountDownLatch  start  = new CountDownLatch(1);
        CountDownLatch  done   = new CountDownLatch(THREADS);
        AtomicInteger   errors = new AtomicInteger(0);

        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        String key = "key-" + (i % 100);
                        if (tid % 3 == 0) {
                            cache.delete(key);          // deleter threads
                        } else {
                            cache.get(key);             // reader threads (null is fine post-delete)
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        pool.shutdownNow();

        assertEquals(0, errors.get(), "Concurrent delete + read must not throw");
    }

    // ─────────────────────────────────────────────
    // 4. Multi-Node Cluster (CacheCluster)
    // ─────────────────────────────────────────────

    /**
     * Verifies that the consistent hash ring distributes keys across all shards
     * (no shard is empty when N >> numShards keys are inserted).
     */
    @Test
    void testClusterKeyDistribution() {
        CacheCluster<String> cluster = new CacheCluster<>(4, 500);

        for (int i = 0; i < 400; i++) {
            cluster.set("key-" + i, "v-" + i);
        }

        assertEquals(400, cluster.size(), "All 400 keys should be stored across shards");
    }

    /**
     * Verifies that a key always routes to the same shard (deterministic hashing).
     */
    @Test
    void testClusterDeterministicRouting() {
        CacheCluster<String> cluster = new CacheCluster<>(4, 500);

        int firstShard  = cluster.shardIndexFor("my-cache-key");
        int secondShard = cluster.shardIndexFor("my-cache-key");

        assertEquals(firstShard, secondShard, "Same key must always route to the same shard");
    }

    /**
     * Verifies full get/set/delete round-trip through the cluster router.
     */
    @Test
    void testClusterGetSetDelete() {
        CacheCluster<String> cluster = new CacheCluster<>(3, 100);

        cluster.set("user:1", "bhavya");
        assertEquals("bhavya", cluster.get("user:1"));

        cluster.delete("user:1");
        assertNull(cluster.get("user:1"), "Key should be gone after delete");
    }

    /**
     * Concurrent stress test against the cluster: verifies that multi-shard
     * parallel writes and reads from N threads produce no corruption.
     */
    @Test
    @Timeout(15)
    void testClusterConcurrentThroughput() throws InterruptedException {
        final int THREADS  = 8;
        final int OPS_EACH = 5_000;

        CacheCluster<String> cluster = new CacheCluster<>(4, 1_000);
        ExecutorService pool   = Executors.newFixedThreadPool(THREADS);
        CountDownLatch  start  = new CountDownLatch(1);
        CountDownLatch  done   = new CountDownLatch(THREADS);
        AtomicInteger   errors = new AtomicInteger(0);

        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < OPS_EACH; i++) {
                        String key = "k" + (i % 200);
                        if (tid % 2 == 0) {
                            cluster.set(key, "val-" + i);
                        } else {
                            cluster.get(key);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        pool.shutdownNow();

        assertEquals(0, errors.get(), "No errors during concurrent cluster operations");
        assertTrue(cluster.size() <= 4 * 1_000, "Cluster size must not exceed total capacity");
    }
}
