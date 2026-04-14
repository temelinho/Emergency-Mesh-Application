package com.emergencymesh.network.wifidirect;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class WifiDirectManager {

    public interface Listener {
        void onPeersAvailable(List<WifiP2pDevice> peers);
        void onConnectionEstablished(boolean isGroupOwner, InetAddress groupOwnerAddress);
        void onConnectionLost();
        void onMessageReceived(String message);

        /**
         * Wi‑Fi Direct durumlarını UI'ya yansıtmak için opsiyonel callback.
         * Mevcut implementasyonları bozmamak için default.
         */
        default void onWifiStatus(String status, String detail) {}
    }

    private static final String TAG = "WifiDirectManager";
    private static final int DISCOVERY_INTERVAL_MS = 15000;

    private final Context ctx;
    private final Listener listener;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isDiscovering = false;
    private final List<WifiP2pDevice> currentPeers = new ArrayList<>();

    private MessageServer messageServer;
    private MessageClient messageClient;

    public WifiDirectManager(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void sendMessage(String message) {
        if (messageServer != null) {
            messageServer.sendMessage(message);
        }
        if (messageClient != null) {
            messageClient.sendMessage(message);
        }
    }

    public void start() {
        manager = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            if (listener != null) listener.onWifiStatus("Hata", "WifiP2pManager null");
            return;
        }

        channel = manager.initialize(ctx, Looper.getMainLooper(), null);
        if (channel == null) {
            if (listener != null) listener.onWifiStatus("Hata", "WifiP2P channel null");
            return;
        }

        setupIntentFilter();
        setupReceiver();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, intentFilter);
        }

        if (listener != null) listener.onWifiStatus("Başlıyor", "Receiver registered");
        startDiscovery();
    }

    private void setupIntentFilter() {
        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }

    private void setupReceiver() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    if (manager != null && hasPermission()) {
                        if (ActivityCompat.checkSelfPermission(ctx, getRequiredPermission()) != PackageManager.PERMISSION_GRANTED) {
                            return;
                        }
                        manager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
                            @Override
                            public void onPeersAvailable(WifiP2pDeviceList peers) {
                                currentPeers.clear();
                                currentPeers.addAll(peers.getDeviceList());
                                if (listener != null) {
                                    listener.onPeersAvailable(new ArrayList<>(currentPeers));
                                }
                            }
                        });
                    }
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    handleConnectionStatus(intent);
                }
            }
        };
    }

    private void handleConnectionStatus(Intent intent) {
        NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
        if (networkInfo != null && networkInfo.isConnected()) {
            manager.requestConnectionInfo(channel, info -> {
                if (info.groupFormed) {
                    if (info.isGroupOwner) {
                        startServer();
                    } else {
                        startClient(info.groupOwnerAddress);
                    }
                    if (listener != null) {
                        listener.onConnectionEstablished(info.isGroupOwner, info.groupOwnerAddress);
                        listener.onWifiStatus("Bağlandı", "Group formed");
                    }
                }
            });
        } else {
            stopMessaging();
            if (listener != null) {
                listener.onConnectionLost();
                listener.onWifiStatus("Kesildi", "Disconnected");
            }
        }
    }

    private void startServer() {
        stopMessaging();
        messageServer = new MessageServer(message -> {
            if (listener != null) listener.onMessageReceived(message);
        });
        messageServer.start();
    }

    private void startClient(InetAddress groupOwnerAddress) {
        stopMessaging();
        messageClient = new MessageClient(groupOwnerAddress, message -> {
            if (listener != null) listener.onMessageReceived(message);
        });
        messageClient.start();
    }

    private void stopMessaging() {
        if (messageServer != null) { messageServer.stop(); messageServer = null; }
        if (messageClient != null) { messageClient.stop(); messageClient = null; }
    }

    public void startDiscovery() {
        if (manager == null || channel == null) return;
        if (ActivityCompat.checkSelfPermission(ctx, getRequiredPermission()) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "İzin yok, keşif başlatılamıyor.");
            if (listener != null) listener.onWifiStatus("İzin yok", "discoverPeers: " + getRequiredPermission());
            return;
        }
        // Bazı cihazlarda (özellikle Android 13/14) önceki bir discovery durdurulmadan
        // discoverPeers çağrılırsa sürekli BUSY (2) dönebiliyor. Önce durdurmayı dene.
        manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "stopPeerDiscovery OK, discoverPeers çağrılıyor");
                internalDiscoverPeers();
            }

            @Override
            public void onFailure(int reason) {
                Log.w(TAG, "stopPeerDiscovery fail: " + reason + " — yine de discoverPeers denenecek");
                internalDiscoverPeers();
            }
        });
    }

    private void internalDiscoverPeers() {
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                isDiscovering = true;
                if (listener != null) listener.onWifiStatus("Aranıyor", "discoverPeers OK");
                scheduleNextDiscovery();
            }

            @Override
            public void onFailure(int reason) {
                isDiscovering = false;

                String humanReason;
                switch (reason) {
                    case WifiP2pManager.BUSY:
                        humanReason = "Meşgul (kod 2) — Wi‑Fi Direct başka bir işlem tarafından kullanılıyor olabilir (hotspot / ekran yansıtma / Yakındaki Paylaşım).";
                        // Bir süre sonra tekrar dene.
                        handler.postDelayed(() -> startDiscovery(), 5000);
                        break;
                    case WifiP2pManager.P2P_UNSUPPORTED:
                        humanReason = "Desteklenmiyor (kod 1) — Bu cihazda Wi‑Fi Direct donanımsal olarak kapalı.";
                        break;
                    case WifiP2pManager.ERROR:
                    default:
                        humanReason = "Genel hata (kod " + reason + ")";
                        break;
                }

                Log.e(TAG, "discoverPeers başarısız: " + humanReason);
                if (listener != null) listener.onWifiStatus("Hata", humanReason);
            }
        });
    }

    private void scheduleNextDiscovery() {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            if (isDiscovering) startDiscovery();
        }, DISCOVERY_INTERVAL_MS);
    }

    public void connectToPeer(final WifiP2pDevice device) {
        if (manager == null || channel == null || device == null) return;
        if (ActivityCompat.checkSelfPermission(ctx, getRequiredPermission()) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "İzin yok, bağlantı başlatılamıyor.");
            return;
        }
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.groupOwnerIntent = 7;
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Bağlantı denemesi başladı.");
            }
            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Bağlantı başarısız: " + reason);
            }
        });
    }

    public void stop() {
        isDiscovering = false;
        handler.removeCallbacksAndMessages(null);
        stopMessaging();
        try {
            ctx.unregisterReceiver(receiver);
            manager.removeGroup(channel, null);
        } catch (Exception e) { Log.e(TAG, "Durdurma hatası", e); }
        if (listener != null) listener.onWifiStatus("Kapalı", "Stopped");
    }

    private boolean hasPermission() {
        return ActivityCompat.checkSelfPermission(ctx, getRequiredPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private String getRequiredPermission() {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ?
                Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
    }
}