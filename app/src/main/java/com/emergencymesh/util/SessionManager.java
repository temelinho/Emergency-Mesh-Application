package com.emergencymesh.util;

import android.content.Context;
import android.content.SharedPreferences;

public class SessionManager {
    private static final String PREF_NAME = "EmergencyMeshSession";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_USER_NAME = "user_name";
    private static final String KEY_PHONE = "user_phone";
    private static final String KEY_FIRST_NAME = "first_name";
    private static final String KEY_LAST_NAME = "last_name";
    private static final String KEY_CITY = "city";
    private static final String KEY_DISTRICT = "district";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";

    private final SharedPreferences pref;
    private final SharedPreferences.Editor editor;

    public SessionManager(Context context) {
        pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        editor = pref.edit();
    }

    public void saveUser(String id, String username, String phone) {
        editor.putString(KEY_USER_ID, id);
        editor.putString(KEY_USER_NAME, username);
        editor.putString(KEY_PHONE, phone);
        editor.putBoolean(KEY_IS_LOGGED_IN, true);
        editor.apply();
    }

    public void saveExtendedUser(String username, String phone, String firstName, String lastName, String city, String district) {
        editor.putString(KEY_USER_NAME, username);
        editor.putString(KEY_PHONE, phone);
        editor.putString(KEY_FIRST_NAME, firstName);
        editor.putString(KEY_LAST_NAME, lastName);
        editor.putString(KEY_CITY, city);
        editor.putString(KEY_DISTRICT, district);
        editor.apply();
    }

    public boolean isLoggedIn() {
        return pref.getBoolean(KEY_IS_LOGGED_IN, false);
    }

    public String getUserId() {
        return pref.getString(KEY_USER_ID, null);
    }

    public String getUserName() {
        return pref.getString(KEY_USER_NAME, "");
    }
    
    public String getPhone() {
        return pref.getString(KEY_PHONE, "");
    }

    public String getFirstName() {
        return pref.getString(KEY_FIRST_NAME, "");
    }

    public String getLastName() {
        return pref.getString(KEY_LAST_NAME, "");
    }

    public String getCity() {
        return pref.getString(KEY_CITY, "");
    }

    public String getDistrict() {
        return pref.getString(KEY_DISTRICT, "");
    }

    public void logout() {
        editor.clear();
        editor.apply();
    }
}
