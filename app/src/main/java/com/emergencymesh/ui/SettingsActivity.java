package com.emergencymesh.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.emergencymesh.R;
import com.emergencymesh.util.VoiceAssistant;

/**
 * Uygulama Ayarları Ekranı.
 * Artık BottomSheet yerine tam ekran Activity olarak çalışır.
 */
public class SettingsActivity extends BaseActivity {

    private VoiceAssistant voiceAssistant;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        voiceAssistant = new VoiceAssistant(this);

        initSettingsUI();
    }

    private void initSettingsUI() {
        // Geri Butonu
        findViewById(R.id.btnSettingsBack).setOnClickListener(v -> finish());

        // Kullanıcı bilgilerini doldur
        String userName = sessionManager.getUserName();
        String userPhone = sessionManager.getPhone();

        TextView tvInitial = findViewById(R.id.tvAvatarInitial);
        TextView tvName    = findViewById(R.id.tvSettingsUserName);
        TextView tvPhone   = findViewById(R.id.tvSettingsUserPhone);

        if (tvName != null) {
            tvName.setText(userName != null && !userName.isEmpty() ? userName : "Kullanıcı");
        }
        if (tvPhone != null) {
            tvPhone.setText(userPhone != null && !userPhone.isEmpty() ? userPhone : "Telefon bilgisi yok");
        }
        if (tvInitial != null && userName != null && !userName.isEmpty()) {
            tvInitial.setText(String.valueOf(userName.charAt(0)).toUpperCase());
        }

        // Profili Düzenle
        findViewById(R.id.btnEditProfile).setOnClickListener(v -> {
            startActivity(new Intent(this, ProfileActivity.class));
        });

        // Sağlık Bilgileri
        findViewById(R.id.btnHealthProfile).setOnClickListener(v -> {
            startActivity(new Intent(this, HealthProfileActivity.class));
        });

        // Erişilebilirlik (Yaşlı) Modu Toggle
        androidx.appcompat.widget.SwitchCompat switchAcc = findViewById(R.id.switchAccessibility);
        if (switchAcc != null) {
            switchAcc.setChecked(sessionManager.isAccessibilityModeEnabled());
            switchAcc.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked != sessionManager.isAccessibilityModeEnabled()) {
                    sessionManager.setAccessibilityMode(isChecked);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        recreate();
                    }, 200);
                }
            });

            findViewById(R.id.btnElderlyMode).setOnClickListener(v -> {
                switchAcc.toggle();
            });
        }

        // Sesli Rehber Toggle
        androidx.appcompat.widget.SwitchCompat switchVoice = findViewById(R.id.switchVoiceAssistance);
        if (switchVoice != null) {
            switchVoice.setChecked(sessionManager.isVoiceAssistanceEnabled());
            switchVoice.setOnCheckedChangeListener((btn, isChecked) -> {
                sessionManager.setVoiceAssistanceEnabled(isChecked);
                if (isChecked) {
                    voiceAssistant.speak("Sesli rehber aktif");
                }
            });
        }

        // Bildirimler
        findViewById(R.id.btnNotifications).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Bildirim Ayarları")
                    .setMessage("Şu anda tüm acil uyarı bildirimleri ve mesh ağı mesaj bildirimleri aktiftir. Kapatılamaz.")
                    .setPositiveButton("Tamam", null)
                    .show();
        });

        // Hakkında
        findViewById(R.id.btnAbout).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Uygulama Hakkında")
                    .setMessage("EmergencyMesh\n\nSürüm: 1.0.0\nAfet ve acil durumlarda internet olmadan iletişim kurmanızı sağlayan mesh ağı projesi.")
                    .setPositiveButton("Tamam", null)
                    .show();
        });

        // Güvenli Çıkış
        findViewById(R.id.btnLogout).setOnClickListener(v -> {
            sessionManager.logout();
            Toast.makeText(this, "Başarıyla çıkış yapıldı.", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }
}
