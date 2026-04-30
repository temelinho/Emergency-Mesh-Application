package com.emergencymesh.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.emergencymesh.R;
import com.emergencymesh.network.RetrofitClient;
import com.emergencymesh.network.UserDto;
import com.emergencymesh.util.SessionManager;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class LoginActivity extends AppCompatActivity {

    private EditText editPhone, editPassword;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        sessionManager = new SessionManager(this);
        
        // Şifresiz giriş (Bypass) için otomatik test kullanıcısı oluşturuluyor
        sessionManager.saveUser("local_test_user", "YerelKullanici", "05550000000");
        com.emergencymesh.util.NodeId.set(this, "local_test_user");
        
        startActivity(new Intent(this, MainActivity.class));
        finish();
        // return; // Yönlendirmeden sonraki kodlar derleme hatası vermemesi için yorum satırına alındı
        /*
        editPhone = findViewById(R.id.editPhone);
        editPassword = findViewById(R.id.editPassword);
        Button btnLogin = findViewById(R.id.btnLogin);

        btnLogin.setOnClickListener(v -> performLogin());

        findViewById(R.id.btnGoToRegister).setOnClickListener(v -> {
            startActivity(new Intent(this, RegisterActivity.class));
        });
        */
    }

    private void performLogin() {
        String phone = editPhone.getText().toString().trim();
        String password = editPassword.getText().toString().trim();

        if (phone.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Lütfen tüm alanları doldurun", Toast.LENGTH_SHORT).show();
            return;
        }

        UserDto.LoginRequest request = new UserDto.LoginRequest(phone, password);
        RetrofitClient.getUserApi().login(request).enqueue(new Callback<UserDto.Response>() {
            @Override
            public void onResponse(Call<UserDto.Response> call, Response<UserDto.Response> response) {
                if (response.isSuccessful() && response.body() != null) {
                    UserDto.Response user = response.body();
                    sessionManager.saveUser(user.getId(), user.getUsername(), user.getPhoneNumber());
                    com.emergencymesh.util.NodeId.set(LoginActivity.this, user.getId());
                    Toast.makeText(LoginActivity.this, "Giriş başarılı", Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(LoginActivity.this, MainActivity.class));
                    finish();
                } else {
                    Toast.makeText(LoginActivity.this, "Giriş başarısız: " + response.message(), Toast.LENGTH_SHORT)
                            .show();
                }
            }

            @Override
            public void onFailure(Call<UserDto.Response> call, Throwable t) {
                Toast.makeText(LoginActivity.this, "Sunucu hatası: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
}
