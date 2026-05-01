package com.emergencymesh.ui;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.emergencymesh.R;
import com.emergencymesh.util.SessionManager;
import com.google.android.material.textfield.TextInputEditText;

public class HealthProfileActivity extends AppCompatActivity {

    private TextInputEditText etBloodType, etChronicDisease, etMedication;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_health_profile);

        sessionManager = new SessionManager(this);

        etBloodType = findViewById(R.id.etBloodType);
        etChronicDisease = findViewById(R.id.etChronicDisease);
        etMedication = findViewById(R.id.etMedication);

        // Mevcut verileri yükle
        etBloodType.setText(sessionManager.getBloodType());
        etChronicDisease.setText(sessionManager.getChronicDisease());
        etMedication.setText(sessionManager.getMedication());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        findViewById(R.id.btnSaveHealth).setOnClickListener(v -> {
            String blood = etBloodType.getText() != null ? etBloodType.getText().toString().trim() : "";
            String disease = etChronicDisease.getText() != null ? etChronicDisease.getText().toString().trim() : "";
            String meds = etMedication.getText() != null ? etMedication.getText().toString().trim() : "";

            sessionManager.saveHealthProfile(blood, disease, meds);
            Toast.makeText(this, "Sağlık bilgileri başarıyla kaydedildi.", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
