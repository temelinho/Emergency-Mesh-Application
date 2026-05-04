package com.emergencymesh.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.emergencymesh.R;
import com.emergencymesh.network.CityDto;
import com.emergencymesh.network.RetrofitClient;
import com.emergencymesh.network.UserDto;
import com.emergencymesh.util.NodeId;
import com.emergencymesh.util.SessionManager;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class RegisterActivity extends BaseActivity {

    private EditText editUsername, editFirstName, editLastName, editPhone, editPassword;
    private AutoCompleteTextView editCity, editDistrict;
    private List<CityDto.Info> citiesList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        editUsername = findViewById(R.id.editUsername);
        editFirstName = findViewById(R.id.editFirstName);
        editLastName = findViewById(R.id.editLastName);
        editPhone = findViewById(R.id.editPhone);
        editPassword = findViewById(R.id.editPassword);
        editCity = findViewById(R.id.editCity);
        editDistrict = findViewById(R.id.editDistrict);
        Button btnRegister = findViewById(R.id.btnRegister);

        btnRegister.setOnClickListener(v -> performRegister());

        findViewById(R.id.btnGoToLogin).setOnClickListener(v -> finish());

        loadCities();

        editCity.setOnItemClickListener((parent, view, position, id) -> {
            CityDto.Info selectedCity = citiesList.get(position);
            loadDistricts(selectedCity.getId());
        });
    }

    private void loadCities() {
        RetrofitClient.getUserApi().getCities().enqueue(new Callback<List<CityDto.Info>>() {
            @Override
            public void onResponse(Call<List<CityDto.Info>> call,
                    Response<List<CityDto.Info>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    citiesList = response.body();
                    List<String> cityNames = new ArrayList<>();
                    for (CityDto.Info city : citiesList) {
                        cityNames.add(city.getName());
                    }
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(RegisterActivity.this,
                            android.R.layout.simple_dropdown_item_1line, cityNames);
                    editCity.setAdapter(adapter);
                }
            }

            @Override
            public void onFailure(Call<List<com.emergencymesh.network.CityDto.Info>> call, Throwable t) {
                Toast.makeText(RegisterActivity.this, "Şehirler yüklenemedi", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadDistricts(String cityId) {
        RetrofitClient.getUserApi().getDistricts(cityId).enqueue(new Callback<List<String>>() {
            @Override
            public void onResponse(Call<List<String>> call, Response<List<String>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    List<String> districts = response.body();
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(RegisterActivity.this,
                            android.R.layout.simple_dropdown_item_1line, districts);
                    editDistrict.setAdapter(adapter);
                    editDistrict.setText(""); // Clear previous district
                }
            }

            @Override
            public void onFailure(Call<List<String>> call, Throwable t) {
                Toast.makeText(RegisterActivity.this, "İlçeler yüklenemedi", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void performRegister() {
        String username = editUsername.getText().toString().trim();
        String firstName = editFirstName.getText().toString().trim();
        String lastName = editLastName.getText().toString().trim();
        String phone = editPhone.getText().toString().trim();
        String password = editPassword.getText().toString().trim();
        String city = editCity.getText().toString().trim();
        String district = editDistrict.getText().toString().trim();

        if (username.length() < 3) {
            Toast.makeText(this, "Kullanıcı adı en az 3 karakter olmalıdır", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!phone.matches("^(05|5)\\d{9}$|^\\+905\\d{9}$")) {
            Toast.makeText(this, "Geçersiz telefon formatı (Örn: 05XXXXXXXXX)", Toast.LENGTH_SHORT).show();
            return;
        }

        if (password.length() < 8 || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            Toast.makeText(this, "Şifre en az 8 karakter olmalı, harf ve rakam içermelidir", Toast.LENGTH_LONG).show();
            return;
        }

        if (username.isEmpty() || phone.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Lütfen gerekli alanları doldurun", Toast.LENGTH_SHORT).show();
            return;
        }

        UserDto.Request request = new UserDto.Request(username, phone, password, firstName, lastName, city, district);

        RetrofitClient.getUserApi().register(request).enqueue(new Callback<UserDto.Response>() {
            @Override
            public void onResponse(Call<UserDto.Response> call, Response<UserDto.Response> response) {
                if (response.isSuccessful() && response.body() != null) {
                    UserDto.Response user = response.body();
                    sessionManager.saveUser(user.getId(), user.getUsername(), user.getPhoneNumber());
                    NodeId.set(RegisterActivity.this, user.getId());
                    Toast.makeText(RegisterActivity.this, "✅ Kayıt başarılı! Hoş geldin " + user.getUsername(), Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(RegisterActivity.this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                } else {
                    // Sunucudan gelen hata mesajını düzgün parse et
                    String serverMsg = "";
                    try {
                        if (response.errorBody() != null) {
                            String raw = response.errorBody().string();
                            // JSON formatında {"message":"..."} veya düz metin olabilir
                            if (raw.contains("\"message\"")) {
                                int start = raw.indexOf("\"message\"") + 11;
                                int end = raw.indexOf("\"", start);
                                if (end > start) serverMsg = raw.substring(start, end);
                            } else {
                                serverMsg = raw;
                            }
                        }
                    } catch (Exception e) { /* ignore */ }

                    if (response.code() == 400 || response.code() == 409) {
                        String displayMsg = serverMsg.isEmpty()
                                ? "Bu kullanıcı adı zaten alınmış. Lütfen başka bir isim seçin."
                                : serverMsg;
                        Toast.makeText(RegisterActivity.this, "⚠️ " + displayMsg, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(RegisterActivity.this,
                                "Kayıt başarısız (" + response.code() + "): " + serverMsg,
                                Toast.LENGTH_LONG).show();
                    }
                }
            }

            @Override
            public void onFailure(Call<UserDto.Response> call, Throwable t) {
                Toast.makeText(RegisterActivity.this, "Bağlantı hatası: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }
}
