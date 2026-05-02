package com.emergencymesh.network.wifidirect;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class MessageServer {
    public interface Listener { void onMessageReceived(String message); }
    private final Listener listener;
    private ServerSocket serverSocket;
    private boolean running = false;

    // Bağlı tüm istemcilerin yazıcılarını saklıyoruz (Broadcast için)
    private final List<PrintWriter> clients = new ArrayList<>();

    public MessageServer(Listener listener) { this.listener = listener; }

    public void start() {
        running = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(8888);
                while (running) {
                    Socket client = serverSocket.accept();
                    // Her bağlantı için ayrı thread aç (Çoklu istemci desteği)
                    new Thread(() -> handleClient(client)).start();
                }
            } catch (Exception e) {
                if (running) Log.e("MessageServer", "Server hatası: " + e.getMessage());
            }
        }).start();
    }

    private void handleClient(Socket client) {
        PrintWriter out = null;
        try {
            out = new PrintWriter(client.getOutputStream(), true);
            synchronized (clients) {
                clients.add(out);
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String line;
            while (running && (line = in.readLine()) != null) {
                if (listener != null) listener.onMessageReceived(line);
            }
        } catch (Exception e) {
            Log.w("MessageServer", "İstemci bağlantısı kesildi: " + e.getMessage());
        } finally {
            if (out != null) {
                synchronized (clients) {
                    clients.remove(out);
                }
            }
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    public void sendMessage(final String msg) {
        new Thread(() -> {
            synchronized (clients) {
                for (PrintWriter out : clients) {
                    out.println(msg);
                }
            }
        }).start();
    }

    public void stop() {
        running = false;
        synchronized (clients) {
            clients.clear();
        }
        new Thread(() -> {
            try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        }).start();
    }
}