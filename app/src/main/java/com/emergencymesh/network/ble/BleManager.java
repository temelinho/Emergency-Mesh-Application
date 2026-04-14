package com.emergencymesh.network.ble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BleManager {

    // Samsung periyodik tarama restart (arka plan tarama limiti aşımı için)
    private static final long SCAN_RESTART_INTERVAL_MS = 5 * 60 * 1000; // 5 dakika

    public interface Listener {
        void onNodeSeen(String nodeId, int rssi);

        /**
         * BLE tarafındaki durumları UI'ya yansıtmak için opsiyonel callback.
         */
        default void onBleStatus(String status, String detail) {
        }

        /**
         * BLE üzerinden gelen ham mesajları uygulamaya iletmek için opsiyonel callback.
         */
        default void onBleRawMessage(String raw) {
        }
    }

    private static final String TAG = "BleManager";

    // Uygulamanın sabit servis UUID’si
    private static final UUID SERVICE_UUID = UUID.fromString("7d2b2f4a-8c8e-4b5f-9c64-8a6b8b5b9e31");

    // Mesaj characteristic UUID'si
    private static final UUID MESSAGE_CHAR_UUID = UUID.fromString("f3c8e6b2-9a4d-4c3a-9e58-0b7e5a2c1d11");

    private final Context ctx;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private BluetoothGattServer gattServer;

    private AdvertiseCallback advertiseCallback;
    private ScanCallback scanCallback;

    // Tarama sırasında görülen cihazlar
    private final List<BluetoothDevice> knownDevices = new ArrayList<>();

    // Gelen parçalı mesajları birleştirmek için buffer: MacAddress ->
    // ByteArrayOutputStream
    private final Map<String, ByteArrayOutputStream> reassemblyBuffers = new ConcurrentHashMap<>();

    // Periyodik tarama restart için
    private final Handler scanRestartHandler = new Handler(Looper.getMainLooper());
    private final Runnable scanRestartRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "Periyodik tarama restart (Samsung arka plan limiti workaround)");
            try {
                stopScan();
            } catch (Exception e) {
                Log.w(TAG, "Tarama durdurma hatası: " + e.getMessage());
            }
            try {
                Thread.sleep(500); // Kısa bekleme
            } catch (InterruptedException ignored) {
            }
            try {
                startScan();
            } catch (Exception e) {
                Log.w(TAG, "Tarama yeniden başlatma hatası: " + e.getMessage());
            }
            scanRestartHandler.postDelayed(this, SCAN_RESTART_INTERVAL_MS);
        }
    };

    public BleManager(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    private BluetoothAdapter getAdapter() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    public boolean isReady() {
        BluetoothAdapter a = getAdapter();
        if (a == null)
            return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(ctx,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_CONNECT izni verilmemiş.");
                return false;
            }
        }
        return a.isEnabled();
    }

    public void start(String nodeId) {
        try {
            startGattServer();
            startAdvertise(nodeId);
        } catch (Exception e) {
            Log.e(TAG, "Advertise başlatma hatası: " + e.getMessage());
            if (listener != null)
                listener.onBleStatus("Hata", "Advertise: " + e.getMessage());
        }
        try {
            startScan();
            // Samsung arka plan tarama limiti için periyodik restart başlat
            scanRestartHandler.removeCallbacks(scanRestartRunnable);
            scanRestartHandler.postDelayed(scanRestartRunnable, SCAN_RESTART_INTERVAL_MS);
        } catch (Exception e) {
            Log.e(TAG, "Scan başlatma hatası: " + e.getMessage());
            if (listener != null)
                listener.onBleStatus("Hata", "Scan: " + e.getMessage());
        }
    }

    public void stop() {
        // Periyodik tarama restart'ı durdur
        scanRestartHandler.removeCallbacks(scanRestartRunnable);
        try {
            stopScan();
        } catch (Exception e) {
            Log.e(TAG, "Scan durdurma hatası: " + e.getMessage());
        }
        try {
            stopAdvertise();
        } catch (Exception e) {
            Log.e(TAG, "Advertise durdurma hatası: " + e.getMessage());
        }
        try {
            stopGattServer();
        } catch (Exception e) {
            Log.e(TAG, "GattServer durdurma hatası: " + e.getMessage());
        }
    }

    public void broadcast(String payload) {
        if (payload == null || payload.isEmpty())
            return;
        List<BluetoothDevice> snapshot;
        synchronized (knownDevices) {
            snapshot = new ArrayList<>(knownDevices);
        }
        for (BluetoothDevice device : snapshot) {
            if (device != null) {
                sendToDevice(device, payload);
            }
        }
    }

    private void startAdvertise(String nodeId) {
        BluetoothAdapter a = getAdapter();
        if (a == null)
            return;
        advertiser = a.getBluetoothLeAdvertiser();
        if (advertiser == null)
            return;

        if (SecurityUtils.missingPermission(ctx, Manifest.permission.BLUETOOTH_ADVERTISE))
            return;

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .setIncludeDeviceName(false)
                .build();

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build();

        advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                Log.i(TAG, "Reklam (Advertise) başarıyla başladı.");
                if (listener != null)
                    listener.onBleStatus("Aktif", "Advertise OK");
            }

            @Override
            public void onStartFailure(int errorCode) {
                Log.e(TAG, "Reklam (Advertise) başlatılamadı: " + errorCode);
                if (listener != null)
                    listener.onBleStatus("Hata", "Advertise fail: " + errorCode);
            }
        };
        advertiser.startAdvertising(settings, data, advertiseCallback);
    }

    private void stopAdvertise() {
        if (advertiser != null && advertiseCallback != null) {
            if (SecurityUtils.missingPermission(ctx, Manifest.permission.BLUETOOTH_ADVERTISE))
                return;
            advertiser.stopAdvertising(advertiseCallback);
        }
        advertiser = null;
        advertiseCallback = null;
    }

    private void startScan() {
        BluetoothAdapter a = getAdapter();
        if (a == null)
            return;
        scanner = a.getBluetoothLeScanner();
        if (scanner == null)
            return;

        if (SecurityUtils.missingScanPermission(ctx))
            return;

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build());

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                handle(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult r : results)
                    handle(r);
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "Tarama başarısız: " + errorCode);
            }

            private void handle(ScanResult result) {
                BluetoothDevice device = result.getDevice();
                if (device != null) {
                    synchronized (knownDevices) {
                        boolean exists = false;
                        for (BluetoothDevice d : knownDevices) {
                            if (d.getAddress().equals(device.getAddress())) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists)
                            knownDevices.add(device);
                    }
                }

                // Node ID parsing logic...
                String nodeId = "BLE:" + device.getAddress();
                if (listener != null)
                    listener.onNodeSeen(nodeId, result.getRssi());
            }
        };

        scanner.startScan(filters, settings, scanCallback);
        Log.i(TAG, "Tarama başladı.");
        if (listener != null)
            listener.onBleStatus("Aktif", "Scan başladı");
    }

    private void stopScan() {
        if (scanner != null && scanCallback != null) {
            if (SecurityUtils.missingScanPermission(ctx))
                return;
            scanner.stopScan(scanCallback);
        }
        scanner = null;
        scanCallback = null;
    }

    // ==== GATT Server (Alıcı) ====

    private void startGattServer() {
        if (gattServer != null)
            return;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null)
            return;

        if (SecurityUtils.missingPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT))
            return;

        gattServer = bm.openGattServer(ctx, new BluetoothGattServerCallback() {
            @Override
            public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
                super.onConnectionStateChange(device, status, newState);
                // Samsung debug: bağlantı durumu logla
                Log.i(TAG, "GATT Server bağlantı: device=" + device.getAddress()
                        + ", status=" + status + ", newState=" + newState);
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "📥 Cihaz GATT sunucumuza bağlandı: " + device.getAddress());
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "📤 Cihaz GATT sunucumuzdan ayrıldı: " + device.getAddress());
                    reassemblyBuffers.remove(device.getAddress());
                }
            }

            @Override
            public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                    BluetoothGattCharacteristic characteristic,
                    boolean preparedWrite, boolean responseNeeded,
                    int offset, byte[] value) {

                Log.d(TAG, "📝 Write isteği: device=" + device.getAddress()
                        + ", uuid=" + characteristic.getUuid()
                        + ", responseNeeded=" + responseNeeded
                        + ", dataLen=" + (value != null ? value.length : 0));

                // ÖNEMLİ: Samsung'da sendResponse ÖNCE çağrılmalı.
                // Bazı Samsung modelleri response beklerken timeout yapıp bağlantıyı kesiyor.
                if (responseNeeded && gattServer != null) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
                }

                if (MESSAGE_CHAR_UUID.equals(characteristic.getUuid())) {
                    if (value != null) {
                        handleIncomingChunk(device, value);
                    }
                }
            }

            @Override
            public void onServiceAdded(int status, BluetoothGattService service) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "✅ GATT servisi başarıyla eklendi: " + service.getUuid());
                } else {
                    Log.e(TAG, "❌ GATT servisi eklenemedi! status=" + status);
                }
            }
        });

        if (gattServer == null) {
            Log.e(TAG, "GATT server açılamadı!");
            return;
        }

        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic messageChar = new BluetoothGattCharacteristic(
                MESSAGE_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        service.addCharacteristic(messageChar);
        gattServer.addService(service);
        Log.i(TAG, "GATT server başlatıldı, servis ekleniyor...");
    }

    private void handleIncomingChunk(BluetoothDevice device, byte[] chunk) {
        String address = device.getAddress();
        ByteArrayOutputStream buffer = reassemblyBuffers.computeIfAbsent(address, k -> new ByteArrayOutputStream());

        try {
            buffer.write(chunk);
            byte[] currentBytes = buffer.toByteArray();

            // En az 4 byte (uzunluk başlığı) var mı?
            if (currentBytes.length >= 4) {
                ByteBuffer wrapped = ByteBuffer.wrap(currentBytes);
                int totalLength = wrapped.getInt(); // İlk 4 byte toplam uzunluk

                // Tüm mesaj geldi mi? (+4 byte header)
                if (currentBytes.length >= totalLength + 4) {
                    // Mesajı ayıkla
                    byte[] messageBytes = Arrays.copyOfRange(currentBytes, 4, 4 + totalLength);
                    String fullMessage = new String(messageBytes, StandardCharsets.UTF_8);

                    Log.i(TAG, "Mesaj başarıyla birleştirildi (" + totalLength + " bytes): " + fullMessage);

                    if (listener != null) {
                        mainHandler.post(() -> listener.onBleRawMessage(fullMessage));
                    }

                    // Buffer'ı temizle (veya varsa sonraki mesajı işle - burada basitlik için
                    // temizliyoruz)
                    reassemblyBuffers.remove(address);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Chunk işleme hatası: " + e.getMessage());
            reassemblyBuffers.remove(address);
        }
    }

    private void stopGattServer() {
        if (gattServer != null) {
            if (SecurityUtils.missingPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT))
                return;
            gattServer.close();
        }
        gattServer = null;
    }

    // ==== GATT Client (Gönderici) ====

    private void sendToDevice(BluetoothDevice device, String payload) {
        if (device == null || payload == null)
            return;
        if (SecurityUtils.missingPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT))
            return;

        Log.i(TAG, "Mesaj gönderiliyor: " + device.getAddress() + " | Len: " + payload.length());

        // Header ekle: [TotalLength (4 bytes)] + [Payload]
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        byte[] lengthHeader = ByteBuffer.allocate(4).putInt(payloadBytes.length).array();

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            os.write(lengthHeader);
            os.write(payloadBytes);
        } catch (Exception e) {
            return;
        }

        byte[] fullData = os.toByteArray();

        // Queue ve State yönetimi için bir nesne
        final ChunkSender sender = new ChunkSender(ctx, device, fullData);
        sender.start();
    }

    /**
     * Mesajı parçalara bölüp sırayla gönderen yardımcı sınıf.
     */
    private static class ChunkSender extends BluetoothGattCallback {
        private static final int MAX_RETRY = 3;
        private static final long RETRY_DELAY_MS = 1000; // 1 saniye
        private static final long SERVICE_DISCOVERY_DELAY_MS = 600; // Samsung GATT 129 fix

        private final Context context;
        private final BluetoothDevice device;
        private final byte[] data;
        private BluetoothGatt gatt;
        private int currentMtu = 23; // Default BLE MTU
        private int offset = 0;
        private int retryCount = 0;
        private final Handler retryHandler = new Handler(Looper.getMainLooper());

        ChunkSender(Context context, BluetoothDevice device, byte[] data) {
            this.context = context;
            this.device = device;
            this.data = data;
        }

        void start() {
            connectInternal();
        }

        private void connectInternal() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(context, false, this);
            }
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Samsung GATT 133 hatası ve diğer bağlantı hataları
                Log.w(TAG, "GATT bağlantı hatası: status=" + status + ", newState=" + newState
                        + ", retry=" + retryCount + "/" + MAX_RETRY);
                cleanupGatt();

                if (retryCount < MAX_RETRY) {
                    retryCount++;
                    retryHandler.postDelayed(() -> {
                        Log.i(TAG, "GATT retry #" + retryCount + " başlatılıyor...");
                        connectInternal();
                    }, RETRY_DELAY_MS * retryCount); // Giderek artan bekleme
                } else {
                    Log.e(TAG, "GATT " + MAX_RETRY + " denemede başarısız, vazgeçildi: " + device.getAddress());
                }
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT bağlandı, MTU isteniyor...");
                retryCount = 0; // Başarılı bağlantıda retry sayacını sıfırla

                // Samsung MTU Blackhole Fix: Bazı cihazlar requestMtu'ya asla cevap vermez.
                // 1.5 saniye içinde cevap gelmezse default MTU (23) ile devam et.
                Runnable mtuTimeoutTask = () -> {
                    if (gatt != null) {
                        Log.w(TAG, "⚠️ MTU cevabı gelmedi (Timeout). Default MTU ile (23) devam ediliyor...");
                        gatt.discoverServices();
                    }
                };
                // Task'ı handler'a koyalım. onMtuChanged içinde iptal edeceğiz.
                gatt.requestMtu(512);
                retryHandler.postDelayed(mtuTimeoutTask, 1500);

                // Hack: Timeout taskını iptal edebilmek için küçük bir workaround
                // Bunu sınıf değişkeni yapmadık çünkü her instance sadece bir send yapıyor.
                this.pendingMtuTimeout = mtuTimeoutTask;

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT bağlantısı kesildi.");
                cleanupGatt();
            }
        }

        // Timeout task'ı için referans
        private Runnable pendingMtuTimeout;

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(TAG, "MTU değişti: " + mtu);

            // Timeout taskını iptal et
            if (pendingMtuTimeout != null) {
                retryHandler.removeCallbacks(pendingMtuTimeout);
                pendingMtuTimeout = null;
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                this.currentMtu = mtu;
            }
            // Samsung GATT 129 fix: Servis keşfinden önce kısa gecikme
            retryHandler.postDelayed(() -> {
                if (this.gatt != null) {
                    this.gatt.discoverServices();
                }
            }, SERVICE_DISCOVERY_DELAY_MS);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                sendNextChunk();
            } else {
                Log.e(TAG, "Servis keşfi başarısız, status: " + status);
                disconnect();
            }
        }

        private void sendNextChunk() {
            if (offset >= data.length) {
                Log.i(TAG, "Tüm gönderim tamamlandı.");
                disconnect();
                return;
            }

            if (gatt == null)
                return;

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                disconnect();
                return;
            }

            BluetoothGattCharacteristic ch = service.getCharacteristic(MESSAGE_CHAR_UUID);
            if (ch == null) {
                disconnect();
                return;
            }

            // Header overhead (3 byte) düşülerek payload boyutu hesapla
            int chunkSize = Math.min(data.length - offset, currentMtu - 3);
            byte[] chunk = Arrays.copyOfRange(data, offset, offset + chunkSize);

            ch.setValue(chunk);
            ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

            if (!gatt.writeCharacteristic(ch)) {
                Log.e(TAG, "writeCharacteristic başarısız.");
                disconnect();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                int chunkSize = characteristic.getValue().length;
                offset += chunkSize;
                sendNextChunk();
            } else {
                Log.e(TAG, "Yazma başarısız: " + status);
                disconnect();
            }
        }

        private void disconnect() {
            cleanupGatt();
        }

        private void cleanupGatt() {
            if (gatt != null) {
                try {
                    gatt.disconnect();
                } catch (Exception e) {
                    /* ignore */ }
                try {
                    gatt.close();
                } catch (Exception e) {
                    /* ignore */ }
                gatt = null;
            }
        }
    }

    // İzin kontrolü için yardımcı sınıf (Internal use)
    private static class SecurityUtils {
        static boolean missingPermission(Context ctx, String perm) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return ActivityCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED;
            }
            return false;
        }

        static boolean missingScanPermission(Context ctx) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return ActivityCompat.checkSelfPermission(ctx,
                        Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED;
            } else {
                return ActivityCompat.checkSelfPermission(ctx,
                        Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
            }
        }
    }
}