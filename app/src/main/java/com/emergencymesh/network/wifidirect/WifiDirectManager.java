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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class WifiDirectManager {

    public interface Listener {
        void onPeersAvailable(List<WifiP2pDevice> peers);
        void onConnectionEstablished(boolean isGroupOwner, InetAddress groupOwnerAddress);
        void onConnectionLost();
        void onMessageReceived(String message);
        default void onWifiStatus(String status, String detail) {}
    }

    private static final String TAG = "WifiDirectManager";
    private static final int DISCOVERY_INTERVAL_MS = 12000;
    private static final int AUTO_CONNECT_DELAY_MS = 5000;

    private final Context ctx;
    private final Listener listener;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    private boolean isDiscovering = false;
    private boolean isConnected = false;
    private boolean isConnecting = false;
    private String connectedDeviceAddress = null;
    private final List<WifiP2pDevice> currentPeers = new ArrayList<>();

    private MessageServer messageServer;
    private MessageClient messageClient;

    public WifiDirectManager(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        manager = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) return;

        channel = manager.initialize(ctx, Looper.getMainLooper(), null);
        if (channel == null) return;

        setupIntentFilter();
        setupReceiver();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, intentFilter);
        }

        resetWifiDirect();
        handler.postDelayed(this::startDiscovery, 2000);
    }

    private void resetWifiDirect() {
        if (manager == null || channel == null) return;
        manager.removeGroup(channel, null);
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
                if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        startDiscovery();
                    } else {
                        stopMessaging();
                    }
                } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    requestPeers();
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    handleConnectionStatus(intent);
                }
            }
        };
    }

    private void requestPeers() {
        if (manager == null || channel == null || !hasPermission()) return;
        manager.requestPeers(channel, peers -> {
            currentPeers.clear();
            currentPeers.addAll(peers.getDeviceList());
            Collections.sort(currentPeers, (d1, d2) -> d1.status - d2.status);
            if (listener != null) listener.onPeersAvailable(new ArrayList<>(currentPeers));
            if (!isConnected) evaluateAutoConnect();
        });
    }

    private void evaluateAutoConnect() {
        if (currentPeers.isEmpty()) return;
        
        // Akıllı Ağ Seçimi (Smart Switch): Eğer mevcut bağlı değilsek, cihazları gruplandır ve seç.
        // Android doğrudan RSSI vermediği için, en güncel ve en uygun (AVAILABLE) olanı seçiyoruz.
        WifiP2pDevice bestDevice = null;

        for (WifiP2pDevice device : currentPeers) {
            if (device.status == WifiP2pDevice.AVAILABLE) {
                bestDevice = device;
                // Owner olan bir cihaz bulursak (zaten bir grubun sahibiyse) doğrudan ona atla ki güçlü bir ağa dahil olalım
                if (device.isGroupOwner()) {
                    break;
                }
            }
        }

        if (bestDevice != null && !isConnected && !isConnecting) {
            isConnecting = true;
            // Çarpışmayı önlemek için rastgele gecikme (Anti-Collision)
            int randomDelay = new java.util.Random().nextInt(2000) + 500; // 500ms - 2500ms
            final WifiP2pDevice target = bestDevice;
            
            handler.postDelayed(() -> {
                if (!isConnected) connectToPeer(target);
                else isConnecting = false;
            }, randomDelay);
        }
    }

    private void handleConnectionStatus(Intent intent) {
        isConnecting = false;
        NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
        if (networkInfo != null && networkInfo.isConnected()) {
            manager.requestConnectionInfo(channel, info -> {
                if (info.groupFormed) {
                    isConnected = true;
                    connectedDeviceAddress = (info.isGroupOwner) ? null : info.groupOwnerAddress.getHostAddress();
                    if (info.isGroupOwner) startServer();
                    else startClient(info.groupOwnerAddress);
                    if (listener != null) {
                        listener.onConnectionEstablished(info.isGroupOwner, info.groupOwnerAddress);
                    }
                }
            });
        } else {
            isConnected = false;
            isConnecting = false;
            connectedDeviceAddress = null;
            stopMessaging();
            if (listener != null) listener.onConnectionLost();
        }
    }

    public void startDiscovery() {
        if (manager == null || channel == null || !hasPermission()) return;
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                isDiscovering = true;
                scheduleNextDiscovery();
            }
            @Override
            public void onFailure(int reason) {
                if (reason == WifiP2pManager.BUSY) {
                    handler.postDelayed(() -> startDiscovery(), 5000);
                }
            }
        });
    }

    private final Runnable discoveryRunnable = new Runnable() {
        @Override
        public void run() {
            if (isDiscovering) startDiscovery();
        }
    };

    private void scheduleNextDiscovery() {
        handler.removeCallbacks(discoveryRunnable);
        handler.postDelayed(discoveryRunnable, DISCOVERY_INTERVAL_MS);
    }

    public void connectToPeer(final WifiP2pDevice device) {
        if (manager == null || channel == null || device == null || isConnected) {
            isConnecting = false;
            return;
        }
        if (!hasPermission()) {
            isConnecting = false;
            return;
        }
        
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        
        // Kararı tamamen Android sisteminin müzakeresine bırakıyoruz (Çarpışmayı engeller)
        config.groupOwnerIntent = 0; 

        if (listener != null) listener.onWifiStatus("Bağlanıyor...", "Cihaz: " + device.deviceName);

        manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        // Bağlantı başarılı, status broadcast receiver tarafından yakalanacak
                    }

                    @Override
                    public void onFailure(int reason) {
                        if (listener != null) listener.onWifiStatus("Hata", "Bağlantı koptu, yeni ağ aranıyor.");
                        isConnected = false;
                        isConnecting = false;
                        startDiscovery(); // Başarısız olursa hemen yeni ağ aramaya başla (Smart Switch)
                    }
                });
            }
            @Override
            public void onFailure(int reason) {
                // Discovery durdurulamazsa bile bağlanmayı dene
                manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {}
                    @Override
                    public void onFailure(int reason) {
                        isConnecting = false;
                    }
                });
            }
        });
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

    public void sendMessage(String message) {
        if (messageServer != null) messageServer.sendMessage(message);
        if (messageClient != null) messageClient.sendMessage(message);
    }

    public void stop() {
        isDiscovering = false;
        isConnected = false;
        handler.removeCallbacksAndMessages(null);
        stopMessaging();
        try {
            ctx.unregisterReceiver(receiver);
            manager.removeGroup(channel, null);
        } catch (Exception e) { /* ignored */ }
    }

    private boolean hasPermission() {
        return ActivityCompat.checkSelfPermission(ctx, getRequiredPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private String getRequiredPermission() {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ?
                Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
    }
}