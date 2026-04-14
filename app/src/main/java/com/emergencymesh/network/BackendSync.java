package com.emergencymesh.network;

import android.content.Context;
import android.util.Log;

import com.emergencymesh.data.Message;
import com.emergencymesh.data.MessageDatabase;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BackendSync {

    public interface Listener {
        void onSyncComplete(int count);

        void onSyncFailed(String error);
    }

    private static final String TAG = "BackendSync";
    private static final int MAX_SYNC_RETRY = 3;
    private static final long RETRY_DELAY_MS = 30000; // 30 saniye

    // Backend URL
    private static final String BACKEND_BATCH_URL = "https://hydrargyric-katalina-gravid.ngrok-free.dev/api/v1/messages/batch";

    private final Context ctx;
    private final Listener listener;
    private final MessageDatabase db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean isSyncing = false;
    private volatile boolean syncRequiredAfterCurrent = false;

    public BackendSync(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.db = MessageDatabase.getInstance(ctx);
    }

    public synchronized void syncNow() {
        if (isSyncing) {
            Log.i(TAG, "Sync zaten devam ediyor, sonrasında bir tane daha planlandı.");
            syncRequiredAfterCurrent = true;
            return;
        }

        isSyncing = true;
        executor.execute(this::performSync);
    }

    private void performSync() {
        try {
            List<Message> unsynced = db.getUnsynced();
            if (unsynced.isEmpty()) {
                notifyComplete(0);
                return;
            }

            Log.i(TAG, "Batch sync başlatılıyor. Toplam bekleyen: " + unsynced.size());

            // Mesajları 50'şerli gruplara bölerek gönder (Payload boyutu güvenliği için)
            int totalProcessed = 0;
            int batchSize = 50;

            for (int i = 0; i < unsynced.size(); i += batchSize) {
                int end = Math.min(i + batchSize, unsynced.size());
                List<Message> batch = unsynced.subList(i, end);

                Log.d(TAG, "Batch gönderiliyor: " + i + " - " + end);
                boolean success = sendBatchToBackend(batch);

                if (success) {
                    for (Message msg : batch) {
                        db.markSynced(msg.getId());
                    }
                    totalProcessed += batch.size();
                } else {
                    Log.e(TAG, "Batch gönderimi sırasında hata oluştu. Kalanlar sonra denenecek.");
                    notifyFailed("Kısmi gönderim hatası.");
                    break; // Hata durumunda döngüden çık, sonraki syncNow beklesin
                }
            }

            notifyComplete(totalProcessed);

        } catch (Exception e) {
            Log.e(TAG, "Sync hatası: " + e.getMessage());
            notifyFailed(e.getMessage());
        } finally {
            synchronized (this) {
                isSyncing = false;
                if (syncRequiredAfterCurrent) {
                    syncRequiredAfterCurrent = false;
                    syncNow();
                }
            }
        }
    }

    private boolean sendBatchToBackend(List<Message> messages) {
        HttpURLConnection connection = null;

        try {
            URL url = java.net.URI.create(BACKEND_BATCH_URL).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("ngrok-skip-browser-warning", "true");
            connection.setRequestProperty("User-Agent", "EmergencyMeshApp/1.0");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);

            // JSON Array formatında mesajlar
            StringBuilder jsonBuilder = new StringBuilder();
            jsonBuilder.append("[");
            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                jsonBuilder.append("{");
                jsonBuilder.append("\"id\":\"").append(escapeJson(msg.getId())).append("\",");
                jsonBuilder.append("\"originalCreatedAt\":").append(msg.getTimestamp()).append(",");
                jsonBuilder.append("\"senderId\":\"").append(escapeJson(msg.getSenderId())).append("\",");
                jsonBuilder.append("\"content\":\"").append(escapeJson(msg.getContent())).append("\",");
                jsonBuilder.append("\"priority\":\"")
                        .append(escapeJson(msg.getPriority() != null ? msg.getPriority() : "LOW")).append("\"");

                if (msg.getLatitude() != null && msg.getLongitude() != null) {
                    jsonBuilder.append(",\"latitude\":").append(msg.getLatitude()).append(",");
                    jsonBuilder.append("\"longitude\":").append(msg.getLongitude());
                }
                jsonBuilder.append("}");
                if (i < messages.size() - 1) {
                    jsonBuilder.append(",");
                }
            }
            jsonBuilder.append("]");

            String json = jsonBuilder.toString();
            Log.d(TAG, "Batch JSON (" + messages.size() + " msg): " + json);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                return true;
            } else {
                Log.e(TAG, "Batch HATA [" + responseCode + "]");
                return false;
            }

        } catch (IOException e) {
            Log.e(TAG, "Batch HTTP hatası: " + e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String escapeJson(String str) {
        if (str == null)
            return "";
        return str
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void notifyComplete(int count) {
        if (listener != null) {
            listener.onSyncComplete(count);
        }
    }

    private void notifyFailed(String error) {
        if (listener != null) {
            listener.onSyncFailed(error);
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
