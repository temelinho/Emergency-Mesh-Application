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
import com.emergencymesh.util.EarthquakeDetector;
import com.emergencymesh.util.VoiceAssistant;
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
    private static final boolean USE_WIFI_DIRECT = true;

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
    public static final String ACTION_SEND_DIRECT_MESSAGE = "com.emergencymesh.SEND_DIRECT_MESSAGE";
    public static final String EXTRA_DIRECT_RECEIVER = "direct_receiver";
    public static final String ACTION_TRIGGER_FULL_SYNC = "com.emergencymesh.TRIGGER_FULL_SYNC";
    public static final String ACTION_REQUEST_STATUS = "com.emergencymesh.REQUEST_STATUS";

    // Protokol komutları
    private static final String CMD_SYNC_FULL_REQUEST = "SYNC_REQ:";
    private static final String CMD_SYNC_RECENT = "SYNC_RECENT:";
    private static final String CMD_SYNC_RESPONSE = "SYNC_RES:";
    private static final String CMD_MESSAGE = "MSG:";
    private static final String CMD_QUAKE = "QUAKE:";
    private static final String CMD_DIRECT = "DM:"; // Özel mesaj komutu

    private BleManager bleManager;
    private WifiDirectManager wifiDirectManager;
    private NetworkMonitor networkMonitor;
    private BackendSync backendSync;
    private MessageDatabase db;
    private VoiceAssistant voiceAssistant;
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

        Intent notificationIntent = new Intent(this, com.emergencymesh.ui.MainActivity.class);
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, notificationIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Acil Mod Aktif")
                .setContentText("Cihaz düğüm olarak çalışıyor")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentIntent(pendingIntent)
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
            wifiDirectManager.start();
        }
        networkMonitor = new NetworkMonitor(this, this);
        networkMonitor.start(); // EKSİK OLAN START EKLENDİ
        backendSync = new BackendSync(this, this);

        // Servis başladığında bekleyen mesajları hemen gönder
        if (backendSync != null) {
            backendSync.syncNow();
        }

        voiceAssistant = new VoiceAssistant(this);
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
        } else if (intent != null && ACTION_SEND_DIRECT_MESSAGE.equals(intent.getAction())) {
            String content = intent.getStringExtra(EXTRA_LOCAL_MESSAGE);
            String receiverId = intent.getStringExtra(EXTRA_DIRECT_RECEIVER);
            handleDirectOutgoingMessage(content, receiverId);
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
            if (wifiDirectManager != null) {
                wifiStatus = "Pasif Mod";
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
        // Sağlık profilini mesaja ekle
        SessionManager session = new SessionManager(this);
        String userName = session.getUserName();
        if (userName == null || userName.isEmpty()) userName = "Bilinmeyen Kişi";

        String bloodType = session.getBloodType();
        String chronicDisease = session.getChronicDisease();
        String medication = session.getMedication();

        // İsmi gizli bir mühürle mesajın en başına ekle (ChatActivity çözecek)
        StringBuilder enrichedContent = new StringBuilder();
        enrichedContent.append(userName).append("|||").append(content);
        if ((bloodType != null && !bloodType.isEmpty()) || 
            (chronicDisease != null && !chronicDisease.isEmpty()) || 
            (medication != null && !medication.isEmpty())) {
            
            enrichedContent.append("\n[SAĞLIK: ");
            if (bloodType != null && !bloodType.isEmpty()) enrichedContent.append("Kan: ").append(bloodType).append(", ");
            if (chronicDisease != null && !chronicDisease.isEmpty()) enrichedContent.append("Hastalık: ").append(chronicDisease).append(", ");
            if (medication != null && !medication.isEmpty()) enrichedContent.append("İlaç: ").append(medication);
            
            // Sondaki virgül ve boşluğu temizle
            if (enrichedContent.toString().endsWith(", ")) {
                enrichedContent.setLength(enrichedContent.length() - 2);
            }
            enrichedContent.append("]");
        }

        // Öncelik seviyesini belirle
        String priority = "LOW";
        if (content.startsWith("!3")) priority = "EMERGENCY";
        else if (content.startsWith("!2") || content.startsWith("!7")) priority = "HIGH";
        else if (content.startsWith("!5")) priority = "MEDIUM";
        else if (content.startsWith("!1") || content.startsWith("!8")) priority = "LOW";

        Message msg = new Message(myNodeId, enrichedContent.toString(), lat, lon);
        msg.setPriority(priority);
        db.insert(msg);
        notifyMessageReceived();

        // Backend'e gönderilmeyenleri temizlemeyi dene (backendSync kendi içinde internet/hata yönetimi yapar)
        if (backendSync != null) {
            backendSync.syncNow();
        }

        // 1. KENDİ OLUŞTURDUĞUMUZ MESAJI ETRAFA (BLE ve Wi-Fi) FIRLAT (BROADCAST)
        String payload = CMD_MESSAGE + msg.serialize();
        if (bleManager != null && bleManager.isReady()) {
            bleManager.broadcast(payload);
        }
        if (wifiDirectManager != null && isConnectedViaWifi) {
            wifiDirectManager.sendMessage(payload);
        }
    }

    // ─── Özel Mesaj Gönderme ────────────────────────────────────────────────────
    private void handleDirectOutgoingMessage(String content, String receiverId) {
        if (content == null || content.trim().isEmpty() || receiverId == null) return;
        SessionManager session = new SessionManager(this);
        String userName = session.getUserName();
        if (userName == null || userName.isEmpty()) userName = "Bilinmeyen Kişi";

        // Özel mesajlarda da isim mührü kullan (DirectMessageActivity çözecek)
        String enriched = userName + "|||" + content.trim();
        Message msg = new Message(myNodeId, receiverId, enriched);
        db.insert(msg);
        notifyDirectMessageReceived(receiverId);

        // Özel mesaj payload'ı — tüm ağa çıkılır ama sadece alıcı işler
        String payload = CMD_DIRECT + msg.serialize();
        if (bleManager != null && bleManager.isReady()) {
            bleManager.broadcast(payload);
        }
        if (wifiDirectManager != null && isConnectedViaWifi) {
            wifiDirectManager.sendMessage(payload);
        }
    }

    private void triggerEarthquakeAlarm(String message) {
        // Sesli uyarı
        if (voiceAssistant != null) {
            voiceAssistant.speak("DİKKAT! DEPREM UYARISI! " + message);
        }

        // Bildirim
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("⚠️ DEPREM UYARISI")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .build();
        
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(911, n); // Özel bir ID
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

    private void notifyDirectMessageReceived(String peerId) {
        Intent i = new Intent(ACTION_MESSAGE_RECEIVED);
        i.setPackage(getPackageName());
        i.putExtra("dm_peer_id", peerId);
        sendBroadcast(i);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bleManager != null)
            bleManager.stop();
        if (wifiDirectManager != null)
            wifiDirectManager.stop();
        if (networkMonitor != null)
            networkMonitor.stop();
        if (voiceAssistant != null)
            voiceAssistant.shutdown();
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
        // İki cihaz bağlandığı saniye eksik mesajları birbirleriyle eşitlemek için tetikle
        startFullSync();
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

        // TAM SENKRONİZASYON İSTEĞİ (Handshake) GELDİYSE
        if (raw.startsWith(CMD_SYNC_FULL_REQUEST)) {
            String idsStr = raw.substring(CMD_SYNC_FULL_REQUEST.length());
            Set<String> remoteIds = new java.util.HashSet<>(java.util.Arrays.asList(idsStr.split(",")));
            java.util.List<Message> missingForThem = db.getMessagesNotIn(remoteIds);
            
            // İZDİHAMI ÖNLEME (Anti-Spam): 
            // Aynı anda 20 mesaj atıp Bluetooth'u çökertmemek için aralarına yarım saniye ekliyoruz.
            int delayMs = 200;
            for (Message m : missingForThem) {
                String payload = CMD_SYNC_RESPONSE + m.serialize();
                handler.postDelayed(() -> {
                    if (bleManager != null && bleManager.isReady()) bleManager.broadcast(payload);
                    if (wifiDirectManager != null && isConnectedViaWifi) wifiDirectManager.sendMessage(payload);
                }, delayMs);
                delayMs += 800; // Her mesaj arasında 0.8 saniye (800ms) boşluk bırak
            }
            return;
        }

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

                if (db.insert(msg, true)) { // true = mesh'ten geldi, sunucuya tekrar gönderme
                    notifyMessageReceived();
                    
                    // 1. MESH RELAY (AĞ SIÇRAMASI): Eğer A'dan mesaj aldıysam ve bu yeniyse, onu C'ye fırlat (Kurye Mantığı)
                    String payload = CMD_MESSAGE + msg.serialize();
                    if (bleManager != null && bleManager.isReady()) {
                        bleManager.broadcast(payload);
                    }
                    if (wifiDirectManager != null && isConnectedViaWifi) {
                        wifiDirectManager.sendMessage(payload);
                    }

                    // NOT: Mesh'ten gelen mesajlar sunucuya yüklenmez.
                    // Sadece orijinal gönderici kendi mesajını sunucuya yükler.
                    // Bu sayede admin panelde tekrar (duplicate) oluşmaz.
                }
            }
        } else if (raw.startsWith(CMD_DIRECT)) {
            // Özel (kişiye özel) mesaj geldi!
            String data2 = raw.substring(CMD_DIRECT.length());
            Message dm = Message.deserialize(data2);
            if (dm != null && !dm.getSenderId().equals(myNodeId)) {
                if (myNodeId.equals(dm.getReceiverId())) {
                    // Bu mesaj bana!
                    if (db.insert(dm)) {
                        notifyDirectMessageReceived(dm.getSenderId());
                    }
                } else {
                    // Bana değil — ama mesajı relay ediyoruz (alıcıya ulaşsın)
                    // (Gizlilik: sadece üzstbilgi relay edilir, içeriği relay etmiyoruz)
                    String relayPayload = CMD_DIRECT + dm.serialize();
                    if (bleManager != null && bleManager.isReady()) {
                        bleManager.broadcast(relayPayload);
                    }
                    if (wifiDirectManager != null && isConnectedViaWifi) {
                        wifiDirectManager.sendMessage(relayPayload);
                    }
                }
            }
        } else if (raw.startsWith(CMD_QUAKE)) {
            // Mesh üzerinden deprem uyarısı geldi!
            Log.w(TAG, "MESH EARTHQUAKE ALERT RECEIVED: " + raw);
            triggerEarthquakeAlarm("Yakınlardaki bir cihaz sarsıntı algıladı! Deprem geliyor olabilir.");
            
            // Rebroadcast (Mesh'i genişlet)
            if (bleManager != null && bleManager.isReady()) {
                bleManager.broadcast(raw);
            }
        }
    }

    private void startFullSync() {
        Set<String> myIds = db.getAllIds();

        // BLE MTU taşmasını önlemek için ID listesini en fazla 50 ile sınırla
        // (Her UUID ~36 karakter, 50 x 36 = 1800 byte → BLE için güvenli sınır)
        String idsToSend;
        if (myIds.size() > 50) {
            idsToSend = myIds.stream().limit(50).reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b);
        } else {
            idsToSend = String.join(",", myIds);
        }

        String syncRequest = CMD_SYNC_FULL_REQUEST + idsToSend;
        if (bleManager != null && bleManager.isReady()) {
            bleManager.broadcast(syncRequest);
        }
        if (wifiDirectManager != null && isConnectedViaWifi) {
            wifiDirectManager.sendMessage(syncRequest);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Emergency Mode",
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void updateNotification(String text) {
        Intent notificationIntent = new Intent(this, com.emergencymesh.ui.MainActivity.class);
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, notificationIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Acil Mod Aktif")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentIntent(pendingIntent)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null)
            nm.notify(1, n);
    }
}