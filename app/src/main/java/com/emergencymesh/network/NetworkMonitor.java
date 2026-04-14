package com.emergencymesh.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

public class NetworkMonitor {

    public interface Listener {
        void onInternetAvailable();

        void onInternetLost();
    }

    private static final String TAG = "NetworkMonitor";

    private final Context ctx;
    private final Listener listener;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean isConnected = false;

    public NetworkMonitor(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        try {
            connectivityManager = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) {
                Log.e(TAG, "ConnectivityManager null");
                return;
            }

            // İlk durum kontrolü
            isConnected = checkCurrentConnection();
            if (isConnected && listener != null) {
                listener.onInternetAvailable();
            }

            // Callback oluştur
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    Log.i(TAG, "İnternet bağlantısı mevcut");
                    if (!isConnected) {
                        isConnected = true;
                        if (listener != null) {
                            listener.onInternetAvailable();
                        }
                    }
                }

                @Override
                public void onLost(@NonNull Network network) {
                    Log.i(TAG, "İnternet bağlantısı kesildi");
                    // Başka aktif bağlantı var mı kontrol et
                    if (!checkCurrentConnection()) {
                        isConnected = false;
                        if (listener != null) {
                            listener.onInternetLost();
                        }
                    }
                }

                @Override
                public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
                    boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

                    if (hasInternet && !isConnected) {
                        isConnected = true;
                        Log.i(TAG, "İnternet doğrulandı");
                        if (listener != null) {
                            listener.onInternetAvailable();
                        }
                    }
                }
            };

            // Callback'i kaydet
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();

            connectivityManager.registerNetworkCallback(request, networkCallback);
            Log.i(TAG, "Network monitor başlatıldı");
        } catch (Exception e) {
            Log.e(TAG, "Network monitor başlatma hatası: " + e.getMessage());
        }
    }

    public void stop() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.w(TAG, "Callback zaten unregister edilmiş");
            }
        }
        Log.i(TAG, "Network monitor durduruldu");
    }

    public boolean isConnected() {
        return isConnected;
    }

    private boolean checkCurrentConnection() {
        if (connectivityManager == null)
            return false;

        Network network = connectivityManager.getActiveNetwork();
        if (network == null)
            return false;

        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        if (capabilities == null)
            return false;

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
