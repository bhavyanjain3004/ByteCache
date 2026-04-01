package com.bytecache.benchmark;

import com.bytecache.cache.Cache;
import com.bytecache.cache.LFUCache;
import com.bytecache.cache.LRUCache;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadBenchmark {
    public static void main(String[] args) throws InterruptedException {
        int threads = Runtime.getRuntime().availableProcessors();
        int operations = 1_000_000;
        String type = "LRU";

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--threads")) threads = Integer.parseInt(args[++i]);
            if (args[i].equals("--ops")) operations = Integer.parseInt(args[++i]);
            if (args[i].equals("--type")) type = args[++i].toUpperCase();
        }

        Cache<String, String> cache = type.equals("LFU") ? new LFUCache<>(5000) : new LRUCache<>(5000);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger opsDone = new AtomicInteger(0);

        final int finalThreads = threads;
        final int finalOperations = operations;

        System.out.println("Starting Benchmark for " + type + " Cache");
        
        long start = System.currentTimeMillis();

        for (int i = 0; i < finalThreads; i++) {
            executor.submit(() -> {
                for (int j = 0; j < finalOperations / finalThreads; j++) {
                    int keyId = (int) (Math.random() * 10000);
                    // 90% read workload to simulate typical cache usage
                    if (Math.random() < 0.9) {
                        cache.get(String.valueOf(keyId));
                    } else {
                        cache.set(String.valueOf(keyId), "value" + keyId);
                    }
                    opsDone.incrementAndGet();
                }
                latch.countDown();
            });
        }

        latch.await();
        long end = System.currentTimeMillis();
        long duration = Math.max(end - start, 1);

        System.out.println("----- Benchmark complete -----");
        System.out.println("Cache Type: " + type);
        System.out.println("Threads: " + threads);
        System.out.println("Total Ops: " + opsDone.get());
        System.out.println("Duration: " + duration + "ms");
        System.out.println("Throughput: " + (opsDone.get() * 1000L / duration) + " ops/sec");

        executor.shutdownNow();
        cache.shutdown();
    }
}
