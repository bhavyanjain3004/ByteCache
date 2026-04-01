package com.bytecache.benchmark;

import com.bytecache.cache.LFUCache;
import com.bytecache.cache.LRUCache;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, time = 2, timeUnit = TimeUnit.SECONDS)
@Threads(4)
public class CacheBenchmark {

    private LRUCache<String, String> lruCache;
    private LFUCache<String, String> lfuCache;

    @Setup
    public void setup() {
        lruCache = new LRUCache<>(10000);
        lfuCache = new LFUCache<>(10000);
    }

    @TearDown
    public void tearDown() {
        lruCache.shutdown();
        lfuCache.shutdown();
    }

    @Benchmark
    public void testLRU() {
        int keyId = (int)(Math.random() * 20000);
        if (Math.random() < 0.9) {
            lruCache.get(String.valueOf(keyId));
        } else {
            lruCache.set(String.valueOf(keyId), "value");
        }
    }

    @Benchmark
    public void testLFU() {
        int keyId = (int)(Math.random() * 20000);
        if (Math.random() < 0.9) {
            lfuCache.get(String.valueOf(keyId));
        } else {
            lfuCache.set(String.valueOf(keyId), "value");
        }
    }
}
