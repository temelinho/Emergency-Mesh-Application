package com.emergencymesh.ui;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.emergencymesh.R;
import com.emergencymesh.util.SessionManager;

/**
 * Tüm Activity'lerin türediği ana sınıf. 
 * Tema değişimini (Yaşlı Modu) merkezi olarak yönetir.
 */
public abstract class BaseActivity extends AppCompatActivity {
    
    protected SessionManager sessionManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        sessionManager = new SessionManager(this);
        
        // Tema Ayarı: setContentView'dan ÖNCE çağrılmalı
        if (sessionManager.isAccessibilityModeEnabled()) {
            setTheme(R.style.Theme_EmergencyMeshApp_Accessibility);
        } else {
            setTheme(R.style.Theme_EmergencyMeshApp);
        }
        
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Eğer kullanıcı ayarları değiştirip geri dönerse ve mod değişmişse sayfayı tazele
        // (Bu kısım isteğe bağlı ama akış için iyi olur)
    }
}
