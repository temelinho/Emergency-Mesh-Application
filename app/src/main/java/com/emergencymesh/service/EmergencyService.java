package com.emergencymesh.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.net.wifi.p2p.WifiP2pDevice;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.emergencymesh.data.Message;
import com.emergencymesh.data.MessageDatabase;
import com.emergencymesh.network.BackendSync;
import com.emergencymesh.network.NetworkMonitor;
import com.emergencymesh.network.ble.BleManager;
import com.emergencymesh.network.wifidirect.WifiDirectManager;
import com.emergencymesh.util.NodeId;
import com.emergencymesh.util.SessionManager;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class EmergencyService extends Service implements
        BleManager.Listener,
        WifiDirectManager.Listener,
        NetworkMonitor.Listener,
        BackendSync.Listener {

    private static final String TAG = "EmergencyService";
    private static final String CHANNEL_ID = "emergency_channel";
    private static final boolean USE_WIFI_DIRECT = false;

    // Broadcast actions
    public static final String ACTION_NODE_FOUND = "com.emergencymesh.NODE_FOUND";
    public static final String ACTION_MESSAGE_RECEIVED = "com.emergencymesh.MESSAGE_RECEIVED";
    public static final String ACTION_WIFI_PEER_FOUND = "com.emergencymesh.WIFI_PEER_FOUND";
    public static final String ACTION_WIFI_CONNECTED = "com.emergencymesh.WIFI_CONNECTED";
    public static final String ACTION_INTERNET_STATUS = "com.emergencymesh.INTERNET_STATUS";
    public static final String ACTION_BACKEND_SYNC = "com.emergencymesh.BACKEND_SYNC";
    public static final String ACTION_STATUS_UPDATE = "com.emergencymesh.STATUS_UPDATE";
    public static final String EXTRA_NODE_ID = "node_id";
    public static final String EXTRA_RSSI = "rssi";
    public static final String EXTRA_PEER_COUNT = "peer_count";
    public static final String EXTRA_INTERNET_AVAILABLE = "internet_available";
    public static final String EXTRA_SYNC_COUNT = "sync_count";
    public static final String EXTRA_BLE_STATUS = "ble_status";
    public static final String EXTRA_WIFI_STATUS = "wifi_status";
    public static final String EXTRA_STATUS_MESSAGE = "status_message";

    public static final String ACTION_SEND_LOCAL_MESSAGE = "com.emergencymesh.SEND_LOCAL_MESSAGE";
    public static final String EXTRA_LOCAL_MESSAGE = "local_message";
    public static final String ACTION_TRIGGER_FULL_SYNC = "com.emergencymesh.TRIGGER_FULL_SYNC";
    public static final String ACTION_REQUEST_STATUS = "com.emergencymesh.REQUEST_STATUS";

    // Protokol komutları
    private static final String CMD_SYNC_FULL_REQUEST = "SYNC_REQ:";
    private static final String CMD_SYNC_RECENT = "SYNC_RECENT:";
    private static final String CMD_SYNC_RESPONSE = "SYNC_RES:";
    private static final String CMD_MESSAGE = "MSG:";

    private BleManager bleManager;
    private WifiDirectManager wifiDirectManager;
    private NetworkMonitor networkMonitor;
    private BackendSync backendSync;
    private MessageDatabase db;
    private String myNodeId;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isConnectedViaWifi = false;
    private boolean hasInternet = false;

    private FusedLocationProviderClient fusedLocationClient;

    private volatile String lastBleStatus = "Kapalı";
    private volatile String lastWifiStatus = "Kapalı";
    private volatile String lastStatusMessage = "";

    private static final long STATUS_BROADCAST_INTERVAL_MS = 10_000;
    private final Runnable statusBroadcastRunnable = new Runnable() {
        @Override
        public void run() {
            sendStatusUpdate(lastBleStatus, lastWifiStatus, lastStatusMessage);
            Intent netBroadcast = new Intent(ACTION_INTERNET_STATUS);
            netBroadcast.setPackage(getPackageName());
            netBroadcast.putExtra(EXTRA_INTERNET_AVAILABLE, hasInternet);
            sendBroadcast(netBroadcast);
            handler.postDelayed(this, STATUS_BROADCAST_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Acil Mod Aktif")
                .setContentText("Cihaz düğüm olarak çalışıyor")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .build();

        // Android 14+ Uyumluluğu için Foreground Type Belirtimi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, notification);
        }

        db = MessageDatabase.getInstance(this);
        SessionManager session = new SessionManager(this);
        String sessionUserId = session.getUserId();
        if (sessionUserId != null && !sessionUserId.isEmpty() && sessionUserId.length() > 8) {
            myNodeId = sessionUserId;
            NodeId.set(this, sessionUserId);
        } else {
            myNodeId = NodeId.get(this);
        }

        bleManager = new BleManager(this, this);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        if (USE_WIFI_DIRECT) {
            wifiDirectManager = new WifiDirectManager(this, this);
        }
        networkMonitor = new NetworkMonitor(this, this);
        networkMonitor.start(); // EKSİK OLAN START EKLENDİ
        backendSync = new BackendSync(this, this);

        // Servis başladığında bekleyen mesajları hemen gönder
        if (backendSync != null) {
            backendSync.syncNow();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Her start command geldiğinde nodeId'yi tazele (Login/Register sonrası için
        // kritik)
        SessionManager session = new SessionManager(this);
        String sessionUserId = session.getUserId();
        if (sessionUserId != null && !sessionUserId.isEmpty() && sessionUserId.length() > 8) {
            myNodeId = sessionUserId;
            NodeId.set(this, sessionUserId);
        }

        if (intent != null && ACTION_SEND_LOCAL_MESSAGE.equals(intent.getAction())) {
            String content = intent.getStringExtra(EXTRA_LOCAL_MESSAGE);
            handleLocalOutgoingMessage(content);
            return START_STICKY;
        } else if (intent != null && ACTION_TRIGGER_FULL_SYNC.equals(intent.getAction())) {
            startFullSync();
            return START_STICKY;
        } else if (intent != null && ACTION_REQUEST_STATUS.equals(intent.getAction())) {
            sendStatusUpdate(lastBleStatus, lastWifiStatus, lastStatusMessage);
            return START_STICKY;
        }

        String bleStatus = "Kapalı";
        String wifiStatus = "Kapalı";
        String statusMessage = "";

        try {
            if (bleManager != null && bleManager.isReady()) {
                bleManager.start(myNodeId);
                bleStatus = "Aktif";
            } else {
                bleStatus = "Hazır Değil";
            }
        } catch (Exception e) {
            bleStatus = "Hata";
        }

        updateAndBroadcastStatus(bleStatus, wifiStatus, statusMessage);
        handler.removeCallbacks(statusBroadcastRunnable);
        handler.postDelayed(statusBroadcastRunnable, STATUS_BROADCAST_INTERVAL_MS);

        return START_STICKY;
    }

    // --- Konum ve Mesaj İşleme ---
    private void handleLocalOutgoingMessage(String content) {
        if (content == null || content.trim().isEmpty())
            return;
        final String finalContent = content.trim();

        boolean hasFineLocation = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarseLocation = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (hasFineLocation || hasCoarseLocation) {
            fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null) {
                    processLocalMessageWithLocation(finalContent, location.getLatitude(), location.getLongitude());
                } else {
                    processLocalMessageWithLocation(finalContent, null, null);
                }
            }).addOnFailureListener(e -> {
                processLocalMessageWithLocation(finalContent, null, null);
            });
        } else {
            processLocalMessageWithLocation(finalContent, null, null);
        }
    }

    private void processLocalMessageWithLocation(String content, Double lat, Double lon) {
        Message msg = new Message(myNodeId, content, lat, lon);
        db.insert(msg);
        notifyMessageReceived();

        // Backend'e gönderilmeyenleri temizlemeyi dene (backendSync kendi içinde
        // internet/hata yönetimi yapar)
        if (backendSync != null) {
            backendSync.syncNow();
        }

        if (bleManager != null && bleManager.isReady()) {
            bleManager.broadcast(CMD_MESSAGE + msg.serialize());
        }
    }

    // --- Diğer Gerekli Metotlar (Override) ---
    private void updateAndBroadcastStatus(String ble, String wifi, String msg) {
        if (ble != null)
            lastBleStatus = ble;
        if (wifi != null)
            lastWifiStatus = wifi;
        if (msg != null)
            lastStatusMessage = msg;
        sendStatusUpdate(lastBleStatus, lastWifiStatus, lastStatusMessage);
    }

    private void sendStatusUpdate(String ble, String wifi, String msg) {
        Intent i = new Intent(ACTION_STATUS_UPDATE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_BLE_STATUS, ble);
        i.putExtra(EXTRA_WIFI_STATUS, wifi);
        i.putExtra(EXTRA_STATUS_MESSAGE, msg);
        sendBroadcast(i);
    }

    private void notifyMessageReceived() {
        Intent i = new Intent(ACTION_MESSAGE_RECEIVED);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bleManager != null)
            bleManager.stop();
        if (networkMonitor != null)
            networkMonitor.stop();
        handler.removeCallbacksAndMessages(null);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Listener İmplementasyonları (Boş veya Log eklenmiş halleri)
    @Override
    public void onNodeSeen(String nodeId, int rssi) {
        Intent b = new Intent(ACTION_NODE_FOUND);
        b.setPackage(getPackageName());
        b.putExtra(EXTRA_NODE_ID, nodeId);
        b.putExtra(EXTRA_RSSI, rssi);
        sendBroadcast(b);
    }

    @Override
    public void onBleStatus(String status, String detail) {
        updateAndBroadcastStatus(status, null, detail);
    }

    @Override
    public void onBleRawMessage(String raw) {
        handleIncomingMessage(raw);
    }

    @Override
    public void onWifiStatus(String status, String detail) {
        updateAndBroadcastStatus(null, status, detail);
    }

    @Override
    public void onPeersAvailable(List<WifiP2pDevice> peers) {
    }

    @Override
    public void onConnectionEstablished(boolean isGroupOwner, InetAddress groupOwnerAddress) {
        isConnectedViaWifi = true;
    }

    @Override
    public void onConnectionLost() {
        isConnectedViaWifi = false;
    }

    @Override
    public void onMessageReceived(String message) {
        handleIncomingMessage(message);
    }

    @Override
    public void onInternetAvailable() {
        hasInternet = true;
        if (backendSync != null)
            backendSync.syncNow();
    }

    @Override
    public void onInternetLost() {
        hasInternet = false;
    }

    @Override
    public void onSyncComplete(int count) {
        updateNotification("Sync: " + count + " mesaj");
    }

    @Override
    public void onSyncFailed(String error) {
        Log.e(TAG, error);
    }

    // --- Sync Mantığı (Basitleştirilmiş) ---
    private void handleIncomingMessage(String raw) {
        if (raw == null)
            return;
        String data = "";
        if (raw.startsWith(CMD_MESSAGE))
            data = raw.substring(CMD_MESSAGE.length());
        else if (raw.startsWith(CMD_SYNC_RESPONSE))
            data = raw.substring(CMD_SYNC_RESPONSE.length());

        if (!data.isEmpty()) {
            Message msg = Message.deserialize(data);
            if (msg != null) {
                // Kendi gönderdiğimiz mesaj ağdan sekerse (yankı/echo), tamamen yoksay
                if (msg.getSenderId() != null && msg.getSenderId().equals(myNodeId)) {
                    return;
                }

                if (db.insert(msg)) {
                    notifyMessageReceived();
                    // Mesh üzerinden gelen yeni mesajları da internet varsa hemen gönder
                    if (backendSync != null) {
                        backendSync.syncNow();
                    }
                }
            }
        }
    }

    private void startFullSync() {
        Set<String> myIds = db.getAllIds();
        String syncRequest = CMD_SYNC_FULL_REQUEST + String.join(",", myIds);
        if (bleManager != null && bleManager.isReady())
            bleManager.broadcast(syncRequest);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Emergency Mode",
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void updateNotification(String text) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Acil Mod Aktif").setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_more).build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null)
            nm.notify(1, n);
    }
}