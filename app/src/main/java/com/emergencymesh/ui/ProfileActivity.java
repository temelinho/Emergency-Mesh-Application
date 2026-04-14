package com.emergencymesh.ui;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.ImageButton;
import androidx.appcompat.app.AppCompatActivity;

import com.emergencymesh.R;
import com.emergencymesh.network.CityDto;
import com.emergencymesh.network.RetrofitClient;
import com.emergencymesh.network.UserDto;
import com.emergencymesh.util.SessionManager;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ProfileActivity extends AppCompatActivity {

    private EditText editUsername, editFirstName, editLastName, editPhone;
    private AutoCompleteTextView editCity, editDistrict;
    private MaterialButton btnSave;
    private ImageButton btnBack;
    private SessionManager sessionManager;
    private List<CityDto.Info> citiesList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        sessionManager = new SessionManager(this);

        initViews();
        loadUserData();

        btnBack.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> saveProfile());

        loadCities();

        editCity.setOnItemClickListener((parent, view, position, id) -> {
            CityDto.Info selectedCity = citiesList.get(position);
            loadDistricts(selectedCity.getId());
        });
    }

    private void initViews() {
        editUsername = findViewById(R.id.editUsername);
        editFirstName = findViewById(R.id.editFirstName);
        editLastName = findViewById(R.id.editLastName);
        editPhone = findViewById(R.id.editPhone);
        editCity = findViewById(R.id.editCity);
        editDistrict = findViewById(R.id.editDistrict);
        btnSave = findViewById(R.id.btnSave);
        btnBack = findViewById(R.id.btnBack);
    }

    private void loadUserData() {
        editUsername.setText(sessionManager.getUserName());
        editPhone.setText(sessionManager.getPhone());
        editFirstName.setText(sessionManager.getFirstName());
        editLastName.setText(sessionManager.getLastName());
        editCity.setText(sessionManager.getCity(), false);
        editDistrict.setText(sessionManager.getDistrict(), false);
    }

    private void loadCities() {
        RetrofitClient.getUserApi().getCities().enqueue(new Callback<List<CityDto.Info>>() {
            @Override
            public void onResponse(Call<List<CityDto.Info>> call, Response<List<CityDto.Info>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    citiesList = response.body();
                    List<String> cityNames = new ArrayList<>();
                    for (CityDto.Info city : citiesList) {
                        cityNames.add(city.getName());
                    }
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(ProfileActivity.this,
                            android.R.layout.simple_dropdown_item_1line, cityNames);
                    editCity.setAdapter(adapter);
                }
            }

            @Override
            public void onFailure(Call<List<CityDto.Info>> call, Throwable t) {
                Toast.makeText(ProfileActivity.this, "Şehirler yüklenemedi", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadDistricts(String cityId) {
        RetrofitClient.getUserApi().getDistricts(cityId).enqueue(new Callback<List<String>>() {
            @Override
            public void onResponse(Call<List<String>> call, Response<List<String>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    List<String> districts = response.body();
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(ProfileActivity.this,
                            android.R.layout.simple_dropdown_item_1line, districts);
                    editDistrict.setAdapter(adapter);
                    editDistrict.setText("", false); // Sadece dropdown'ı temizle, menü açılmasın
                }
            }

            @Override
            public void onFailure(Call<List<String>> call, Throwable t) {
                Toast.makeText(ProfileActivity.this, "İlçeler yüklenemedi", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveProfile() {
        String username = editUsername.getText().toString().trim();
        String phone = editPhone.getText().toString().trim();
        String firstName = editFirstName.getText().toString().trim();
        String lastName = editLastName.getText().toString().trim();
        String city = editCity.getText().toString().trim();
        String district = editDistrict.getText().toString().trim();

        if (username.length() < 3) {
            Toast.makeText(this, "Kullanıcı adı en az 3 karakter olmalıdır.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!phone.matches("^(05|5)\\d{9}$|^\\+905\\d{9}$")) {
            Toast.makeText(this, "Geçersiz telefon formatı. Lütfen 05XX, 5XX veya +905XX formatını kullanın.", Toast.LENGTH_LONG).show();
            return;
        }

        if (username.isEmpty() || phone.isEmpty()) {
            Toast.makeText(this, "Kullanıcı adı ve telefon numarası zorunludur.", Toast.LENGTH_SHORT).show();
            return;
        }

        String userId = sessionManager.getUserId();
        if (userId == null || userId.isEmpty()) {
            Toast.makeText(this, "Oturum hatası, ID bulunamadı.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Butonu devre dışı bırakıp kullanıcıya işlem yapıldığını göster
        btnSave.setEnabled(false);
        btnSave.setText("KAYDEDİLİYOR...");

        UserDto.UpdateRequest request = new UserDto.UpdateRequest(username, phone, firstName, lastName, city, district);

        RetrofitClient.getUserApi().updateUser(userId, request).enqueue(new Callback<UserDto.Response>() {
            @Override
            public void onResponse(Call<UserDto.Response> call, Response<UserDto.Response> response) {
                btnSave.setEnabled(true);
                btnSave.setText("KAYDET");

                if (response.isSuccessful() && response.body() != null) {
                    // Sadece sunucu onay verirse lokal cihazı da güncelle
                    sessionManager.saveExtendedUser(username, phone, firstName, lastName, city, district);
                    Toast.makeText(ProfileActivity.this, "Profil başarıyla güncellendi.", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    String errorMsg = "Güncelleme başarısız: " + response.code();
                    try {
                        if (response.errorBody() != null) {
                            errorMsg += " - " + response.errorBody().string();
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                    Toast.makeText(ProfileActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<UserDto.Response> call, Throwable t) {
                btnSave.setEnabled(true);
                btnSave.setText("KAYDET");
                Toast.makeText(ProfileActivity.this, "Bağlantı hatası: Sunucuya ulaşılamıyor.", Toast.LENGTH_LONG).show();
            }
        });
    }
}
