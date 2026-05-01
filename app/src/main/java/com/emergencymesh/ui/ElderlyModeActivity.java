package com.emergencymesh.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.MotionEvent;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.emergencymesh.R;
import com.emergencymesh.service.EmergencyService;

import java.util.Locale;

public class ElderlyModeActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private TextToSpeech tts;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnableSafe;
    private Runnable longPressRunnableEmergency;
    private Runnable periodicReminder;

    private static final long LONG_PRESS_DURATION = 2000; // 2 seconds

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_elderly_mode);

        tts = new TextToSpeech(this, this);

        findViewById(R.id.btnBackToNormal).setOnClickListener(v -> finish());

        setupSafeButton();
        setupEmergencyButton();

        // Her 10 dakikada bir sesli hatırlatma
        periodicReminder = new Runnable() {
            @Override
            public void run() {
                speak("Uygulamanız çalışıyor. Konumunuz paylaşılıyor. Lütfen telefonunuzu kapatmayın.");
                handler.postDelayed(this, 10 * 60 * 1000);
            }
        };
        handler.postDelayed(periodicReminder, 10 * 60 * 1000);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(new Locale("tr", "TR"));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback to default if Turkish is not available
                tts.setLanguage(Locale.getDefault());
            }
            speak("Merhaba. Bu acil yardım uygulaması. İyiyseniz yeşil butona basın. Yardıma ihtiyacınız varsa kırmızı butona basın.");
        }
    }

    private void speak(String text) {
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupSafeButton() {
        android.view.View btnSafe = findViewById(R.id.btnElderlySafe);
        
        longPressRunnableSafe = () -> {
            speak("Güvendeyim mesajınız iletildi. Yetkililer bilgilendirildi.");
            sendLocalMessage("Güvendeyim");
            Toast.makeText(this, "Mesaj Gönderildi: Güvendeyim", Toast.LENGTH_LONG).show();
            btnSafe.setPressed(false);
        };

        btnSafe.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    speak("Güvendeyim butonu. Onaylamak için 2 saniye basılı tutun.");
                    btnSafe.setPressed(true);
                    handler.postDelayed(longPressRunnableSafe, LONG_PRESS_DURATION);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPressRunnableSafe);
                    btnSafe.setPressed(false);
                    return true;
            }
            return false;
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupEmergencyButton() {
        android.view.View btnEmergency = findViewById(R.id.btnElderlyEmergency);
        
        longPressRunnableEmergency = () -> {
            speak("Yardım çağrınız iletildi. Kurtarma ekibi geliyor. Sakin olun, telefonunuzu kapatmayın.");
            sendLocalMessage("ACİL: YARDIM LAZIM!");
            Toast.makeText(this, "Mesaj Gönderildi: YARDIM LAZIM", Toast.LENGTH_LONG).show();
            btnEmergency.setPressed(false);
        };

        btnEmergency.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    speak("Yardım lazım butonu. Onaylamak için 2 saniye basılı tutun.");
                    btnEmergency.setPressed(true);
                    handler.postDelayed(longPressRunnableEmergency, LONG_PRESS_DURATION);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPressRunnableEmergency);
                    btnEmergency.setPressed(false);
                    return true;
            }
            return false;
        });
    }

    private void sendLocalMessage(String messageText) {
        Intent intent = new Intent(this, EmergencyService.class);
        intent.setAction(EmergencyService.ACTION_SEND_LOCAL_MESSAGE);
        intent.putExtra(EmergencyService.EXTRA_LOCAL_MESSAGE, messageText);
        startService(intent);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
