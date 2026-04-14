package com.emergencymesh.util;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

public class NodeId {

    private static final String PREF = "emergencymesh";
    private static final String KEY = "node_id";

    public static String get(Context ctx) {
        return getOrCreate(ctx);
    }

    public static String getOrCreate(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String id = sp.getString(KEY, null);

        if (id == null) {
            // Sunucu tam (36 karakter) UUID formatı bekliyor, ilk 8 haneyi KESMEYİN.
            id = UUID.randomUUID().toString();
            sp.edit().putString(KEY, id).apply();
        }
        return id;
    }

    public static void set(Context ctx, String newId) {
        if (newId != null && !newId.isEmpty()) {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sp.edit().putString(KEY, newId).apply();
        }
    }
}
