package com.emergencymesh.network.wifidirect;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;

public class MessageClient {
    public interface Listener { void onMessageReceived(String message); }
    private final Listener listener;
    private final InetAddress host;
    private Socket socket;
    private PrintWriter out;
    private boolean running = false;

    public MessageClient(InetAddress host, Listener listener) {
        this.host = host;
        this.listener = listener;
    }

    public void start() {
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    socket = new Socket(host, 8888);
                    out = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String line;
                    while (running && (line = in.readLine()) != null) {
                        if (listener != null) listener.onMessageReceived(line);
                    }
                } catch (Exception e) { Log.e("MessageClient", "Error: " + e.getMessage()); }
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
                try { if (socket != null) socket.close(); } catch (Exception e) {}
            }
        }).start();
    }
}