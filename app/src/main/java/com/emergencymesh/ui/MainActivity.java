package com.emergencymesh.ui;

import android.view.View;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.animation.ObjectAnimator;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.emergencymesh.R;
import com.emergencymesh.data.Message;
import com.emergencymesh.data.MessageDatabase;
import com.emergencymesh.service.EmergencyService;
import com.emergencymesh.util.NodeId;
import com.emergencymesh.util.SessionManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import com.emergencymesh.util.VoiceAssistant;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends BaseActivity {

    private static final int REQUEST_PERMISSIONS = 100;
    private static final int REQUEST_ENABLE_LOCATION = 101;
    private boolean emergencyModeActive = false;
    private boolean permissionRequestedOnce = false;
    private String myNodeId;
    private final Map<String, Integer> nearbyNodes = new LinkedHashMap<>();

    // Sesli yönlendirme motoru
    private VoiceAssistant voiceAssistant;
    // Durum izleme için
    private final Handler statusPollHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService pollExecutor = Executors.newSingleThreadExecutor();
    private String lastKnownEmergencyStatus = null;
    private static final long POLL_INTERVAL_MS = 45_000; // 45 saniye
    private static final String BACKEND_BASE = "http://37.247.101.86";

    private TextView txtNodeCount, txtInternetStatus, txtBleStatus, txtWifiStatus, txtStatusDetail;

    private final BroadcastReceiver nodeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null)
                return;

            switch (action) {
                case EmergencyService.ACTION_NODE_FOUND:
                    String nodeId = intent.getStringExtra(EmergencyService.EXTRA_NODE_ID);
                    int rssi = intent.getIntExtra(EmergencyService.EXTRA_RSSI, 0);
                    if (nodeId != null && !nodeId.isEmpty()) {
                        nearbyNodes.put(nodeId, rssi);
                        updateNodeCount();
                    }
                    break;
                case EmergencyService.ACTION_MESSAGE_RECEIVED:
                    Toast.makeText(context, "Yeni bir mesaj alındı!", Toast.LENGTH_SHORT).show();
                    break;
                case EmergencyService.ACTION_INTERNET_STATUS:
                    boolean hasInternet = intent.getBooleanExtra(EmergencyService.EXTRA_INTERNET_AVAILABLE, false);
                    updateInternetStatus(hasInternet);
                    break;
                case EmergencyService.ACTION_STATUS_UPDATE:
                    updateStatusDisplay(
                            intent.getStringExtra(EmergencyService.EXTRA_BLE_STATUS),
                            intent.getStringExtra(EmergencyService.EXTRA_WIFI_STATUS),
                            intent.getStringExtra(EmergencyService.EXTRA_STATUS_MESSAGE));
                    break;
            }
        }
    };

    private ObjectAnimator pulseAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!sessionManager.isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        // Sesli yönlendirme motorunu başlat
        voiceAssistant = new VoiceAssistant(this);

        initViews();

        myNodeId = NodeId.get(this);
        ((TextView) findViewById(R.id.txtNodeId))
                .setText("Kullanıcı: " + sessionManager.getUserName() + " (" + myNodeId + ")");

        showEnvironmentHints();

        // Üst butonlar
        findViewById(R.id.btnEmergency).setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); // Titreşim
            if (!isEmergencyServiceRunning()) {
                if (hasAllPermissions()) {
                    startEmergencyMode();
                } else {
                    requestAllPermissions();
                }
            } else {
                stopEmergencyMode();
            }
        });

        findViewById(R.id.btnSyncAll).setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            triggerFullSync();
        });

        // Hızlı Durum Kartları (Optimize Edilmiş 2-Byte Payload Tagleri)
        findViewById(R.id.cardSafe).setOnClickListener(v -> sendQuickStatus(v, "!1"));
        findViewById(R.id.cardDanger).setOnClickListener(v -> sendQuickStatus(v, "!7"));
        findViewById(R.id.cardHelp).setOnClickListener(v -> sendQuickStatus(v, "!2"));
        findViewById(R.id.cardDebris).setOnClickListener(v -> sendQuickStatus(v, "!3"));
        findViewById(R.id.cardSupply).setOnClickListener(v -> sendQuickStatus(v, "!5"));
        findViewById(R.id.cardBattery).setOnClickListener(v -> sendQuickStatus(v, "!8"));

        // Siren Butonu (Tek Tıkla Toggle)
        Button btnSiren = findViewById(R.id.btnSiren);
        if (btnSiren != null) {
            btnSiren.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                toggleSiren();
            });
        }

        // Mesaj kutusu Butonu
        findViewById(R.id.btnChat).setOnClickListener(v -> {
            startActivity(new Intent(this, ChatActivity.class));
        });

        // Kişiye Özel Mesaj Butonu
        findViewById(R.id.btnDirectMessage).setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            startActivity(new Intent(this, DirectMessageActivity.class));
        });

        // Ayarlar Butonu
        findViewById(R.id.btnSettings).setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            startActivity(new Intent(this, SettingsActivity.class));
        });

        // Uygulama açılır açılmaz izinleri kontrol et ve iste
        if (!hasAllPermissions()) {
            requestAllPermissions();
        }
        // Konum servisleri kontrolü
        checkLocationServicesAndPrompt();
    }

    private void initViews() {
        txtNodeCount = findViewById(R.id.txtNodeCount);
        txtInternetStatus = findViewById(R.id.txtInternetStatus);
        txtBleStatus = findViewById(R.id.txtBleStatus);
        txtWifiStatus = findViewById(R.id.txtWifiStatus);
        txtStatusDetail = findViewById(R.id.txtStatusDetail);

        View btnNodeBadge = findViewById(R.id.btnNodeBadge);
        if (btnNodeBadge != null) {
            btnNodeBadge.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                showConnectedNodesDialog();
            });
        }
    }

    private void showConnectedNodesDialog() {
        if (nearbyNodes.isEmpty()) {
            Toast.makeText(this, "Ağda şu an bağlı düğüm bulunmuyor.", Toast.LENGTH_SHORT).show();
            return;
        }

        com.google.android.material.bottomsheet.BottomSheetDialog bottomSheet = new com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.Theme_EmergencyMeshApp);
        View view = getLayoutInflater().inflate(R.layout.dialog_connected_nodes, null);
        bottomSheet.setContentView(view);

        TextView tvSubtitle = view.findViewById(R.id.tvNodesSubtitle);
        tvSubtitle.setText("Şu an çevrenizde " + nearbyNodes.size() + " cihaz var.\nÖzel mesaj göndermek için birine dokunun.");

        ListView listView = view.findViewById(R.id.listConnectedNodes);
        
        List<String> nodeIds = new ArrayList<>(nearbyNodes.keySet());
        
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(this, 0, nodeIds) {
            @androidx.annotation.NonNull
            @Override
            public View getView(int position, @androidx.annotation.Nullable View convertView, @androidx.annotation.NonNull android.view.ViewGroup parent) {
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(R.layout.item_connected_node, parent, false);
                }

                String id = getItem(position);
                int rssi = nearbyNodes.get(id);

                TextView tvName = convertView.findViewById(R.id.tvNodeName);
                TextView tvMac = convertView.findViewById(R.id.tvNodeMac);
                TextView tvInitial = convertView.findViewById(R.id.tvNodeInitial);
                TextView tvSignal = convertView.findViewById(R.id.tvNodeSignal);

                String resolvedName = resolveNodeName(id);
                tvName.setText(resolvedName);
                
                String initial = resolvedName.substring(0, 1).toUpperCase();
                tvInitial.setText(initial);
                
                tvMac.setText("ID: " + (id.length() > 16 ? id.substring(0, 16) + "..." : id));
                tvSignal.setText(rssi + " dBm");

                // Sinyal rengi
                int color = android.graphics.Color.parseColor("#4CAF50"); // Yeşil
                if (rssi < -85) color = android.graphics.Color.parseColor("#F44336"); // Kırmızı
                else if (rssi < -70) color = android.graphics.Color.parseColor("#FF9800"); // Turuncu
                tvSignal.setTextColor(color);

                return convertView;
            }
        };

        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, v, position, id) -> {
            String selectedNodeId = nodeIds.get(position);
            Intent intent = new Intent(MainActivity.this, DirectMessageActivity.class);
            intent.putExtra(DirectMessageActivity.EXTRA_PEER_ID, selectedNodeId);
            intent.putExtra(DirectMessageActivity.EXTRA_PEER_NAME, resolveNodeName(selectedNodeId));
            startActivity(intent);
            bottomSheet.dismiss();
        });

        bottomSheet.show();
    }

    private String resolveNodeName(String nodeId) {
        com.emergencymesh.data.MessageDatabase db = com.emergencymesh.data.MessageDatabase.getInstance(this);
        java.util.List<com.emergencymesh.data.Message> all = db.getAll();
        for (int i = all.size() - 1; i >= 0; i--) {
            com.emergencymesh.data.Message m = all.get(i);
            if (nodeId.equals(m.getSenderId())) {
                String content = m.getContent();
                if (content != null && content.contains("|||")) {
                    String[] parts = content.split("\\|\\|\\|", 2);
                    if (parts.length == 2 && !parts[0].trim().isEmpty()) {
                        return parts[0].trim();
                    }
                }
            }
        }
        
        if (nodeId.startsWith("BLE:")) return "Yakındaki Cihaz (BLE)";
        if (nodeId.startsWith("WIFI:")) return "Yakındaki Cihaz (WiFi)";
        return "Cihaz " + (nodeId.length() > 4 ? nodeId.substring(nodeId.length() - 4) : nodeId);
    }

    private void sendQuickStatus(android.view.View v, String statusText) {
        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        if (!isEmergencyServiceRunning()) {
            if (hasAllPermissions()) {
                startEmergencyMode();
            } else {
                requestAllPermissions();
                return;
            }
        }

        // Sesli bildirim — butona basılır basılmaz söyle (Eğer açıksa)
        if (voiceAssistant != null && sessionManager.isVoiceAssistanceEnabled()) {
            voiceAssistant.announceButtonAction(statusText);
        }

        // Servis üzerinden gönder
        Intent i = new Intent(this, EmergencyService.class);
        i.setAction(EmergencyService.ACTION_SEND_LOCAL_MESSAGE);
        i.putExtra(EmergencyService.EXTRA_LOCAL_MESSAGE, statusText);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }

        // Backend durum takibini başlat (ekip atanınca sesli bildirim)
        startEmergencyStatusPolling();

        Toast.makeText(this, "Durum Yayınlanıyor: " + statusText, Toast.LENGTH_SHORT).show();
    }

    // ─── BACKEND DURUM TAKİBİ ──────────────────────────────────────────
    private final Runnable statusPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollExecutor.execute(() -> checkEmergencyStatusFromBackend());
            statusPollHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private void startEmergencyStatusPolling() {
        // Mevcut polling varsa yeniden başlatma
        statusPollHandler.removeCallbacks(statusPollRunnable);
        statusPollHandler.postDelayed(statusPollRunnable, POLL_INTERVAL_MS);
    }

    private void stopEmergencyStatusPolling() {
        statusPollHandler.removeCallbacks(statusPollRunnable);
    }

    private void checkEmergencyStatusFromBackend() {
        String userId = sessionManager.getUserId();
        if (userId == null || userId.isEmpty()) return;

        try {
            URL url = new URL(BACKEND_BASE + "/api/emergency?userId=" + userId + "&status=IN_PROGRESS&size=1");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "EmergencyMeshApp/1.0");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                String json = sb.toString();
                // Basit JSON parse: IN_PROGRESS vakası var mı?
                boolean hasInProgress = json.contains("IN_PROGRESS");
                boolean hasResolved   = json.contains("RESOLVED") && !hasInProgress;

                runOnUiThread(() -> {
                    if (hasInProgress && !"IN_PROGRESS".equals(lastKnownEmergencyStatus)) {
                        lastKnownEmergencyStatus = "IN_PROGRESS";
                        if (voiceAssistant != null && sessionManager.isVoiceAssistanceEnabled()) {
                            voiceAssistant.speak(VoiceAssistant.MSG_ASSIGNED);
                        }
                        Toast.makeText(this, "🚑 Ekip atandı! Yardım yolda.", Toast.LENGTH_LONG).show();
                    } else if (hasResolved && "IN_PROGRESS".equals(lastKnownEmergencyStatus)) {
                        lastKnownEmergencyStatus = "RESOLVED";
                        stopEmergencyStatusPolling();
                        if (voiceAssistant != null && sessionManager.isVoiceAssistanceEnabled()) {
                            voiceAssistant.speak(VoiceAssistant.MSG_RESOLVED);
                        }
                    }
                });
            }
            conn.disconnect();
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "Durum sorgusu başarısız: " + e.getMessage());
        }
    }

    private boolean hasAllPermissions() {
        List<String> permissions = getRequiredPermissions();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private List<String> getRequiredPermissions() {
        List<String> p = new ArrayList<>();

        // Temel Konum İzinleri (Hem BLE Scan hem Wi-Fi Direct için zorunlu)
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        // Android 12 ve üzeri (API 31+) Bluetooth ve özel izinler
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }

        // Android 13 ve üzeri (API 33+) Wi-Fi Aware ve Bildirimler
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            p.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        return p;
    }

    private void requestAllPermissions() {
        List<String> p = getRequiredPermissions();
        List<String> toRequest = new ArrayList<>();
        for (String perm : p) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(perm);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    private void showEnvironmentHints() {
        showMissingPermissionsHint();
        showLocationServicesHintIfNeeded();
    }

    private void showMissingPermissionsHint() {
        List<String> required = getRequiredPermissions();
        List<String> missingLabels = new ArrayList<>();
        for (String perm : required) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                if (Manifest.permission.BLUETOOTH_SCAN.equals(perm))
                    missingLabels.add("Bluetooth tarama");
                else if (Manifest.permission.BLUETOOTH_ADVERTISE.equals(perm))
                    missingLabels.add("Bluetooth yayın");
                else if (Manifest.permission.BLUETOOTH_CONNECT.equals(perm))
                    missingLabels.add("Bluetooth bağlantı");
                else if (Manifest.permission.NEARBY_WIFI_DEVICES.equals(perm))
                    missingLabels.add("Yakındaki cihazlar (Wi-Fi)");
                else if (Manifest.permission.POST_NOTIFICATIONS.equals(perm))
                    missingLabels.add("Bildirimler");
                else if (Manifest.permission.ACCESS_FINE_LOCATION.equals(perm))
                    missingLabels.add("Hassas Konum");
                else if (Manifest.permission.ACCESS_COARSE_LOCATION.equals(perm))
                    missingLabels.add("Yaklaşık Konum");
                else
                    missingLabels.add(perm);
            }
        }
        if (!missingLabels.isEmpty()) {
            txtStatusDetail.setText("Eksik izinler: " + TextUtils.join(", ", missingLabels));
        } else {
            // Tüm izinler verildi — eski "Eksik izinler" metnini temizle
            String current = txtStatusDetail.getText() != null ? txtStatusDetail.getText().toString() : "";
            if (current.startsWith("Eksik izinler")) {
                txtStatusDetail.setText("Tüm izinler verildi \u2713");
            }
        }
    }

    private void showLocationServicesHintIfNeeded() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null)
                return;
            boolean enabled;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                enabled = lm.isLocationEnabled();
            } else {
                enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            }
            if (!enabled) {
                String msg = "Konum Servisleri kapalı. BLE/Wi‑Fi Direct keşif için Konum'u açın.";
                String current = txtStatusDetail.getText() != null ? txtStatusDetail.getText().toString() : "";
                if (TextUtils.isEmpty(current))
                    txtStatusDetail.setText(msg);
                else if (!current.contains("Konum Servisleri"))
                    txtStatusDetail.setText(current + "\n" + msg);
            }
        } catch (Exception ignored) {
        }
    }

    private void checkLocationServicesAndPrompt() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null)
                return;
            boolean enabled;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                enabled = lm.isLocationEnabled();
            } else {
                enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            }
            if (!enabled) {
                new AlertDialog.Builder(this)
                        .setTitle("Konum Servisleri Kapalı")
                        .setMessage(
                                "BLE tarama ve Wi-Fi Direct keşfi için Konum Servislerini açmanız gerekiyor.\n\nAyarlar'a gitmek ister misiniz?")
                        .setPositiveButton("Ayarlar'a Git", (d, w) -> {
                            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                        })
                        .setNegativeButton("Daha Sonra", null)
                        .show();
            }
        } catch (Exception ignored) {
        }
    }

    private void openAppSettings() {
        new AlertDialog.Builder(this)
                .setTitle("İzinler Gerekli")
                .setMessage(
                        "Bluetooth, Konum ve diğer izinler bu uygulamanın çalışması için zorunludur.\n\nLütfen Uygulama Ayarları'ndan izinleri açın.")
                .setPositiveButton("Ayarlar'a Git", (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && grantResults.length > 0) {
            if (hasAllPermissions()) {
                showEnvironmentHints();
                Toast.makeText(this, "Tüm izinler verildi! Acil Modu başlatabilirsiniz.", Toast.LENGTH_SHORT).show();
            } else {
                boolean anyPermanentlyDenied = false;
                for (String perm : permissions) {
                    if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                        if (!ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                            anyPermanentlyDenied = true;
                            break;
                        }
                    }
                }
                if (anyPermanentlyDenied) {
                    openAppSettings();
                } else {
                    Toast.makeText(this, "Tüm izinler onaylanmalıdır!", Toast.LENGTH_LONG).show();
                }
                showEnvironmentHints();
            }
        }
    }

    private void startEmergencyMode() {
        Intent i = new Intent(this, EmergencyService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        emergencyModeActive = true;
        ((Button) findViewById(R.id.btnEmergency)).setText("AÇIK\n(KAPAT)");
        startPulseAnimation();
        Toast.makeText(this, "Acil Mod Başlatıldı", Toast.LENGTH_SHORT).show();
    }

    private void stopEmergencyMode() {
        stopService(new Intent(this, EmergencyService.class));
        emergencyModeActive = false;
        ((Button) findViewById(R.id.btnEmergency)).setText("ACİL\nMOD");
        stopPulseAnimation();
        clearNodeList();
        Toast.makeText(this, "Acil Mod Durduruldu", Toast.LENGTH_SHORT).show();
    }

    private void startPulseAnimation() {
        android.view.View pulseView = findViewById(R.id.pulseBackground);
        pulseView.setAlpha(1f);
        android.animation.PropertyValuesHolder scaleX = android.animation.PropertyValuesHolder
                .ofFloat(android.view.View.SCALE_X, 1f, 1.4f);
        android.animation.PropertyValuesHolder scaleY = android.animation.PropertyValuesHolder
                .ofFloat(android.view.View.SCALE_Y, 1f, 1.4f);
        android.animation.PropertyValuesHolder alpha = android.animation.PropertyValuesHolder
                .ofFloat(android.view.View.ALPHA, 0.8f, 0f);

        pulseAnimator = android.animation.ObjectAnimator.ofPropertyValuesHolder(pulseView, scaleX, scaleY, alpha);
        pulseAnimator.setDuration(1200); // 1.2 saniye
        pulseAnimator.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
        pulseAnimator.setRepeatMode(android.animation.ObjectAnimator.RESTART);
        pulseAnimator.start();
    }

    private void stopPulseAnimation() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
        android.view.View pulseView = findViewById(R.id.pulseBackground);
        pulseView.setAlpha(0f);
        pulseView.setScaleX(1f);
        pulseView.setScaleY(1f);
    }

    private void triggerFullSync() {
        if (!isEmergencyServiceRunning()) {
            Toast.makeText(this, "Önce Acil Modu başlatın!", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, EmergencyService.class);
        i.setAction(EmergencyService.ACTION_TRIGGER_FULL_SYNC);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        Toast.makeText(this, "Tam senkronizasyon isteği gönderildi.", Toast.LENGTH_SHORT).show();
    }

    private void updateNodeCount() {
        txtNodeCount.setText(nearbyNodes.size() + " düğüm");
    }

    private void clearNodeList() {
        nearbyNodes.clear();
        updateNodeCount();
    }

    private void updateInternetStatus(boolean hasInternet) {
        txtInternetStatus.setText(hasInternet ? "NET: OK" : "NET: -");
        txtInternetStatus.setTextColor(hasInternet ? 0xFF4CAF50 : 0xFF9E9E9E);
    }

    private void updateStatusDisplay(String ble, String wifi, String msg) {
        if (ble != null)
            txtBleStatus.setText("BLE: " + ble);
        if (wifi != null)
            txtWifiStatus.setText("WiFi-D: " + wifi);
        txtStatusDetail.setText(msg != null ? msg : "");
    }

    @Override
    protected void onResume() {
        super.onResume();
        showEnvironmentHints();
        // Geri dönüşte filtreleri tekrar kaydet
        IntentFilter filter = new IntentFilter();
        filter.addAction(EmergencyService.ACTION_NODE_FOUND);
        filter.addAction(EmergencyService.ACTION_MESSAGE_RECEIVED);
        filter.addAction(EmergencyService.ACTION_INTERNET_STATUS);
        filter.addAction(EmergencyService.ACTION_STATUS_UPDATE);
        registerReceiver(nodeReceiver, filter,
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ? Context.RECEIVER_NOT_EXPORTED : 0);

        if (permissionRequestedOnce && hasAllPermissions()) {
            Toast.makeText(this, "İzinler verildi! Acil Modu başlatabilirsiniz.", Toast.LENGTH_SHORT).show();
        }

        emergencyModeActive = isEmergencyServiceRunning();
        if (emergencyModeActive) {
            ((Button) findViewById(R.id.btnEmergency)).setText("AÇIK\n(KAPAT)");
            startPulseAnimation();
            try {
                Intent statusReq = new Intent(this, EmergencyService.class);
                statusReq.setAction(EmergencyService.ACTION_REQUEST_STATUS);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(statusReq);
                } else {
                    startService(statusReq);
                }
            } catch (Exception ignored) {
            }
        } else {
            ((Button) findViewById(R.id.btnEmergency)).setText("ACİL\nMOD");
            stopPulseAnimation();
        }
    }

    private boolean isEmergencyServiceRunning() {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (EmergencyService.class.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(nodeReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopEmergencyStatusPolling();
        pollExecutor.shutdownNow();
        if (voiceAssistant != null) {
            voiceAssistant.shutdown();
        }
    }

    private android.media.ToneGenerator sirenGen;
    private boolean isSirenPlaying = false;

    private void toggleSiren() {
        if (isSirenPlaying) {
            stopSiren();
            isSirenPlaying = false;
            Toast.makeText(this, "Siren Durduruldu", Toast.LENGTH_SHORT).show();
        } else {
            playSiren();
            isSirenPlaying = true;
            Toast.makeText(this, "🚨 SİREN AKTİF!", Toast.LENGTH_SHORT).show();
        }
    }

    private void playSiren() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM);
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, maxVol, 0);
            }

            if (sirenGen == null) {
                sirenGen = new android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100);
            }
            sirenGen.startTone(android.media.ToneGenerator.TONE_SUP_ERROR, 10000); // 10 saniye çal
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopSiren() {
        if (sirenGen != null) {
            sirenGen.stopTone();
        }
    }
}