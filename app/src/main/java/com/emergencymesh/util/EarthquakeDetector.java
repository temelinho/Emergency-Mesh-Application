package com.emergencymesh.util;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

/**
 * EarthquakeDetector: Yatay duruş (uyku) ve araç içi titreşim ayrımı yapabilen
 * gelişmiş sismik algılayıcı.
 */
public class EarthquakeDetector implements SensorEventListener {
    private static final String TAG = "EarthquakeDetector";

    public interface Listener {
        void onEarthquakeDetected(double intensity);
    }

    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Listener listener;

    // --- Parametreler ---
    private static final float STA_WINDOW = 0.5f;
    private static final float LTA_WINDOW = 15.0f;
    
    // Hassasiyet Eşikleri (Daha hassas hale getirildi)
    private static final float THRESHOLD_FLAT_SURFACE = 1.6f; // Masada çok hassas
    private static final float THRESHOLD_MOVING = 5.0f;      // Hareketli/Araç içi
    private static final float THRESHOLD_HANDHELD = 2.0f;    // Elde tutarken (3.0 idi, düşürüldü)
    
    private static final long MIN_DURATION_MS = 600; // 0.6 saniye yeterli (1.2 saniye idi)

    private double sta = 0;
    private double lta = 0;
    private long lastUpdate = 0;
    
    private long triggerStartTime = 0;
    private boolean isPotentiallyEarthquake = false;
    private boolean isHorizontal = false;

    public EarthquakeDetector(Context context, Listener listener) {
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        this.listener = listener;
    }

    public void start() {
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            Log.d(TAG, "EarthquakeDetector BAŞLATILDI");
        }
    }

    public void stop() {
        sensorManager.unregisterListener(this);
        resetState();
        Log.d(TAG, "EarthquakeDetector DURDURULDU");
    }

    private void resetState() {
        sta = 0; lta = 0; triggerStartTime = 0; isPotentiallyEarthquake = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        long currentTime = System.currentTimeMillis();
        float dt = (lastUpdate == 0) ? 0 : (currentTime - lastUpdate) / 1000.0f;
        lastUpdate = currentTime;
        if (dt <= 0 || dt > 1.0) return; // Hatalı dt'leri engelle

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        // 1. Yatay Durum Kontrolü
        isHorizontal = Math.abs(z) > 9.3 && Math.abs(x) < 1.5 && Math.abs(y) < 1.5;

        // 2. İvme Hesaplama (Linear Acceleration approximation)
        double magnitude = Math.sqrt(x*x + y*y + z*z) - 9.81;
        magnitude = Math.max(0, Math.abs(magnitude));

        // 3. STA/LTA Güncelleme
        sta = (1 - (dt / STA_WINDOW)) * sta + (dt / STA_WINDOW) * magnitude;
        lta = (1 - (dt / LTA_WINDOW)) * lta + (dt / LTA_WINDOW) * magnitude;

        // 4. Araba Filtresi
        boolean isInVehicle = lta > 0.9; 

        // 5. Dinamik Eşik Belirle
        double threshold = THRESHOLD_MOVING;
        if (isHorizontal && !isInVehicle) {
            threshold = THRESHOLD_FLAT_SURFACE;
        } else if (!isInVehicle) {
            threshold = THRESHOLD_HANDHELD;
        }

        // 6. Algılama ve Loglama
        double ratio = (lta > 0.02) ? (sta / lta) : 0;
        
        // Debug Log: Sadece belirli bir güç üzerindeyse yazdır ki Logcat dolmasın
        if (ratio > 1.2) {
            Log.d(TAG, String.format("Sarsıntı Analizi -> Oran: %.2f, Eşik: %.2f, STA: %.2f, LTA: %.2f", ratio, threshold, sta, lta));
        }

        if (lta > 0.02 && ratio > threshold && sta > 0.25) {
            if (!isPotentiallyEarthquake) {
                isPotentiallyEarthquake = true;
                triggerStartTime = currentTime;
                Log.w(TAG, "Sarsıntı algılandı, doğrulanıyor... (Süre hedefi: " + MIN_DURATION_MS + "ms)");
            } else {
                long duration = currentTime - triggerStartTime;
                if (duration >= MIN_DURATION_MS) {
                    Log.e(TAG, "!!! DEPREM ONAYLANDI !!! Oran: " + String.format("%.2f", ratio));
                    if (listener != null) {
                        listener.onEarthquakeDetected(sta);
                    }
                    resetState(); // Tetiklendikten sonra sıfırla (Soğuma süresi)
                }
            }
        } else {
            // Eğer sarsıntı aniden kesilirse (veya kısa sürerse) 300ms içinde iptal et
            if (isPotentiallyEarthquake && (currentTime - triggerStartTime) > 300) {
                isPotentiallyEarthquake = false;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
