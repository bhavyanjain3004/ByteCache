package com.bytecache.pubsub;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PubSubEngine {
    private final Map<String, List<Subscriber>> channels = new ConcurrentHashMap<>();
    private final ExecutorService asyncExecutor;

    public PubSubEngine() {
        this.asyncExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "PubSub-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void subscribe(String channel, Subscriber subscriber) {
        channels.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(subscriber);
    }

    public void unsubscribe(String channel, Subscriber subscriber) {
        List<Subscriber> subs = channels.get(channel);
        if (subs != null) {
            subs.remove(subscriber);
            if (subs.isEmpty()) {
                channels.remove(channel);
            }
        }
    }

    public int publish(String channel, String message) {
        List<Subscriber> subs = channels.get(channel);
        if (subs != null && !subs.isEmpty()) {
            for (Subscriber sub : subs) {
                asyncExecutor.submit(() -> sub.onMessage(channel, message));
            }
            return subs.size();
        }
        return 0;
    }

    public void shutdown() {
        asyncExecutor.shutdownNow();
    }
}
