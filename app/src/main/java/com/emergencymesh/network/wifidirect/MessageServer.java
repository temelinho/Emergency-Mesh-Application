package com.emergencymesh.network.wifidirect;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class MessageServer {
    public interface Listener { void onMessageReceived(String message); }
    private final Listener listener;
    private ServerSocket serverSocket;
    private PrintWriter out;
    private boolean running = false;

    public MessageServer(Listener listener) { this.listener = listener; }

    public void start() {
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(8888);
                    while (running) {
                        Socket client = serverSocket.accept();
                        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        out = new PrintWriter(client.getOutputStream(), true);
                        String line;
                        while ((line = in.readLine()) != null) {
                            if (listener != null) listener.onMessageReceived(line);
                        }
                    }
                } catch (Exception e) { Log.e("MessageServer", "Error: " + e.getMessage()); }
            }
        }).start();
    }

    public void sendMessage(final String msg) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (out != null) {
                    out.println(msg);
                }
            }
        }).start();
    }

    public void stop() {
        running = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try { if (serverSocket != null) serverSocket.close(); } catch (Exception e) {}
            }
        }).start();
    }
}