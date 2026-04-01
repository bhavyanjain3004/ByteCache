package com.bytecache.server;

import com.bytecache.cache.Cache;
import com.bytecache.pubsub.PubSubEngine;
import com.bytecache.pubsub.Subscriber;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;

public class ClientHandler implements Runnable, Subscriber {
    private final Socket socket;
    private final Cache<String, String> cache;
    private final PubSubEngine pubSub;
    private PrintWriter out;
    private String subscribedChannel = null;

    public ClientHandler(Socket socket, Cache<String, String> cache, PubSubEngine pubSub) {
        this.socket = socket;
        this.cache = cache;
        this.pubSub = pubSub;
    }

    @Override
    public void run() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            // Auto-flush enabled so we don't need to manually flush print calls
            out = new PrintWriter(socket.getOutputStream(), true);

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                String command = parts[0].toUpperCase();

                try {
                    switch (command) {
                        case "SET":
                            if (parts.length < 3) {
                                out.println("-ERR wrong number of arguments for 'SET' command");
                            } else {
                                cache.set(parts[1], parts[2]);
                                out.println("+OK");
                            }
                            break;
                        case "EXPIRE": 
                            // Syntax: EXPIRE key seconds.
                            // However, we only have set(). If key exists, we read it and overwrite it with TTL.
                            if (parts.length < 3) {
                                out.println("-ERR wrong number of arguments for 'EXPIRE' command");
                            } else {
                                String key = parts[1];
                                long seconds = Long.parseLong(parts[2]);
                                String existingVal = cache.get(key);
                                if (existingVal != null) {
                                    cache.set(key, existingVal, seconds * 1000);
                                    out.println(":1");
                                } else {
                                    out.println(":0");
                                }
                            }
                            break;
                        case "SETEX": // Additional helper instruction from some commands 
                            if (parts.length < 4) {
                                out.println("-ERR wrong number of arguments for 'SETEX' command");
                            } else {
                                long seconds = Long.parseLong(parts[3]);
                                cache.set(parts[1], parts[2], seconds * 1000);
                                out.println("+OK");
                            }
                            break;
                        case "GET":
                            if (parts.length < 2) {
                                out.println("-ERR wrong number of arguments for 'GET' command");
                            } else {
                                String val = cache.get(parts[1]);
                                if (val == null) {
                                    out.println("$-1");
                                } else {
                                    out.println("$" + val.length() + "\r\n" + val);
                                }
                            }
                            break;
                        case "DEL":
                            if (parts.length < 2) {
                                out.println("-ERR wrong number of arguments for 'DEL' command");
                            } else {
                                cache.delete(parts[1]);
                                out.println(":1");
                            }
                            break;
                        case "SUBSCRIBE":
                            if (parts.length < 2) {
                                out.println("-ERR wrong number of arguments for 'SUBSCRIBE' command");
                            } else {
                                this.subscribedChannel = parts[1];
                                pubSub.subscribe(subscribedChannel, this);
                                out.println("*3\r\n$9\r\nsubscribe\r\n$" + subscribedChannel.length() + "\r\n" + subscribedChannel + "\r\n:1");
                            }
                            break;
                        case "PUBLISH":
                            if (parts.length < 3) {
                                out.println("-ERR wrong number of arguments for 'PUBLISH' command");
                            } else {
                                int receivers = pubSub.publish(parts[1], parts[2]);
                                out.println(":" + receivers);
                            }
                            break;
                        default:
                            out.println("-ERR unknown command '" + command + "'");
                    }
                } catch (Exception e) {
                    out.println("-ERR " + e.getMessage());
                }
            }
        } catch (SocketException e) {
            // connection dropped
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (subscribedChannel != null) {
                pubSub.unsubscribe(subscribedChannel, this);
            }
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    @Override
    public void onMessage(String channel, String message) {
        if (out != null) {
            out.println("*3\r\n$7\r\nmessage\r\n$" + channel.length() + "\r\n" + channel + "\r\n$" + message.length() + "\r\n" + message);
        }
    }
}
