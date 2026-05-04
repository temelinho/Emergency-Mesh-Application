package com.emergencymesh.ui;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.emergencymesh.R;
import com.emergencymesh.util.SessionManager;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import android.widget.ArrayAdapter;

public class HealthProfileActivity extends BaseActivity {

    private MaterialAutoCompleteTextView etBloodType;
    private TextInputEditText etChronicDisease, etMedication;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_health_profile);

        etBloodType = findViewById(R.id.etBloodType);
        etChronicDisease = findViewById(R.id.etChronicDisease);
        etMedication = findViewById(R.id.etMedication);

        // Kan grubu seçeneklerini ayarla
        String[] bloodTypes = new String[]{"A+", "A-", "B+", "B-", "AB+", "AB-", "0+", "0-"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, bloodTypes);
        etBloodType.setAdapter(adapter);

        // Mevcut verileri yükle
        etBloodType.setText(sessionManager.getBloodType(), false); // 'false' ile filtreleme yapmadan metni set ederiz
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

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }
}
