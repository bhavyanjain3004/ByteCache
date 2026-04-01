package com.bytecache.pubsub;

public interface Subscriber {
    void onMessage(String channel, String message);
}
