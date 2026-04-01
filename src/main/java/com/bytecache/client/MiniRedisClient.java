package com.bytecache.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class MiniRedisClient {
    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 6379;
        
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--host") && i + 1 < args.length) {
                host = args[++i];
            } else if (args[i].equals("--port") && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            }
        }

        try (Socket socket = new Socket(host, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             Scanner scanner = new Scanner(System.in)) {
            
            System.out.println("Connected to MiniRedis at " + host + ":" + port);
            
            Thread listener = new Thread(() -> {
                try {
                    String response;
                    while ((response = in.readLine()) != null) {
                        System.out.println(response);
                    }
                } catch (Exception e) {
                    System.out.println("Connection closed by server.");
                    System.exit(0);
                }
            });
            listener.setDaemon(true);
            listener.start();
            
            while (true) {
                String input = scanner.nextLine();
                if (input.equalsIgnoreCase("QUIT") || input.equalsIgnoreCase("EXIT")) {
                    break;
                }
                out.println(input);
            }
        } catch (Exception e) {
            System.err.println("Failed to connect to " + host + ":" + port);
            e.printStackTrace();
        }
    }
}
