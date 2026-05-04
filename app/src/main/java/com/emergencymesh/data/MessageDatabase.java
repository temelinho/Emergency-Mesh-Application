package com.emergencymesh.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MessageDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "emergencymesh.db";
    private static final int DB_VERSION = 3; // V2'den V3'e: receiver_id eklendi

    private static final String TABLE_MESSAGES = "messages";
    private static final String COL_ID = "id";
    private static final String COL_SENDER_ID = "sender_id";
    private static final String COL_CONTENT = "content";
    private static final String COL_TIMESTAMP = "timestamp";
    private static final String COL_HOP_COUNT = "hop_count";
    private static final String COL_SYNCED = "synced"; // İnternete gönderildi mi?

    // V2 Yeni Kolonlar
    private static final String COL_LATITUDE = "latitude";
    private static final String COL_LONGITUDE = "longitude";
    private static final String COL_PRIORITY = "priority";

    // V3 Yeni Kolonlar
    private static final String COL_RECEIVER_ID = "receiver_id"; // null = broadcast, dolu = özel mesaj

    private static MessageDatabase instance;

    public static synchronized MessageDatabase getInstance(Context ctx) {
        if (instance == null) {
            instance = new MessageDatabase(ctx.getApplicationContext());
        }
        return instance;
    }

    private MessageDatabase(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String sql = "CREATE TABLE " + TABLE_MESSAGES + " (" +
                COL_ID + " TEXT PRIMARY KEY, " +
                COL_SENDER_ID + " TEXT, " +
                COL_RECEIVER_ID + " TEXT, " +
                COL_CONTENT + " TEXT, " +
                COL_TIMESTAMP + " INTEGER, " +
                COL_HOP_COUNT + " INTEGER, " +
                COL_LATITUDE + " REAL, " +
                COL_LONGITUDE + " REAL, " +
                COL_PRIORITY + " TEXT, " +
                COL_SYNCED + " INTEGER DEFAULT 0)";
        db.execSQL(sql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // Veri kaybı yaşanmaması için ALTER TABLE ile yeni kolonlar ekleniyor
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN " + COL_LATITUDE + " REAL;");
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN " + COL_LONGITUDE + " REAL;");
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN " + COL_PRIORITY + " TEXT DEFAULT 'LOW';");
        }
        if (oldVersion < 3) {
            // V3: Özel mesajlaşma için receiver_id kolonu eklendi
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN " + COL_RECEIVER_ID + " TEXT;");
        }
    }

    // Mesaj ekle (duplicate kontrolü ile)
    public boolean insert(Message msg) {
        return insert(msg, false);
    }

    /**
     * Mesaj ekle.
     * @param alreadySynced true ise mesaj sunucuya zaten gönderilmiş sayılır
     *                      (mesh'ten gelen mesajlar için kullanılır,
     *                       böylece sadece orijinal gönderici sunucuya yükler)
     */
    public boolean insert(Message msg, boolean alreadySynced) {
        if (exists(msg.getId())) {
            return false; // Zaten var
        }

        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_ID, msg.getId());
        cv.put(COL_SENDER_ID, msg.getSenderId());
        if (msg.getReceiverId() != null) cv.put(COL_RECEIVER_ID, msg.getReceiverId());
        cv.put(COL_CONTENT, msg.getContent());
        cv.put(COL_TIMESTAMP, msg.getTimestamp());
        cv.put(COL_HOP_COUNT, msg.getHopCount());

        if (msg.getLatitude() != null)
            cv.put(COL_LATITUDE, msg.getLatitude());
        if (msg.getLongitude() != null)
            cv.put(COL_LONGITUDE, msg.getLongitude());
        if (msg.getPriority() != null)
            cv.put(COL_PRIORITY, msg.getPriority());

        cv.put(COL_SYNCED, alreadySynced ? 1 : 0);

        long result = db.insert(TABLE_MESSAGES, null, cv);
        return result != -1;
    }

    // Mesaj var mı?
    public boolean exists(String messageId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_MESSAGES,
                new String[] { COL_ID },
                COL_ID + "=?",
                new String[] { messageId },
                null, null, null);

        boolean exists = c.getCount() > 0;
        c.close();
        return exists;
    }

    // Tüm mesajları getir (yeniden eskiye)
    public List<Message> getAll() {
        List<Message> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        Cursor c = db.query(TABLE_MESSAGES,
                null, null, null, null, null,
                COL_TIMESTAMP + " ASC");

        while (c.moveToNext()) {
            Message msg = cursorToMessage(c);
            list.add(msg);
        }
        c.close();
        return list;
    }

    // Son N mesajı getir (Sync için)
    public List<Message> getRecentMessages(int limit) {
        List<Message> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_MESSAGES,
                null, null, null, null, null,
                COL_TIMESTAMP + " DESC",
                String.valueOf(limit));

        while (c.moveToNext()) {
            list.add(cursorToMessage(c));
        }
        c.close();
        return list;
    }

    // Tüm mesaj ID'lerini getir (sync karşılaştırması için)
    public Set<String> getAllIds() {
        Set<String> ids = new HashSet<>();
        SQLiteDatabase db = getReadableDatabase();

        Cursor c = db.query(TABLE_MESSAGES,
                new String[] { COL_ID },
                null, null, null, null, null);

        while (c.moveToNext()) {
            ids.add(c.getString(0));
        }
        c.close();
        return ids;
    }

    // Belirli ID'lere sahip olmayan mesajları getir (karşı node'a göndermek için)
    public List<Message> getMessagesNotIn(Set<String> excludeIds) {
        List<Message> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        Cursor c = db.query(TABLE_MESSAGES,
                null, null, null, null, null,
                COL_TIMESTAMP + " ASC");

        while (c.moveToNext()) {
            String id = c.getString(c.getColumnIndexOrThrow(COL_ID));
            if (!excludeIds.contains(id)) {
                Message msg = cursorToMessage(c);
                list.add(msg);
            }
        }
        c.close();
        return list;
    }

    // Sync edilmemiş mesajları getir (internet bulunduğunda)
    public List<Message> getUnsynced() {
        List<Message> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        Cursor c = db.query(TABLE_MESSAGES,
                null,
                COL_SYNCED + "=0",
                null, null, null,
                COL_TIMESTAMP + " ASC");

        while (c.moveToNext()) {
            Message msg = cursorToMessage(c);
            list.add(msg);
        }
        c.close();
        return list;
    }

    // Mesajı synced olarak işaretle
    public void markSynced(String messageId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_SYNCED, 1);
        db.update(TABLE_MESSAGES, cv, COL_ID + "=?", new String[] { messageId });
    }

    // Mesaj sayısı
    public int getCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_MESSAGES, null);
        c.moveToFirst();
        int count = c.getInt(0);
        c.close();
        return count;
    }

    // ─── ÖZEL MESAJLAŞMA ────────────────────────────────────────────────────────

    /**
     * İki kullanıcı arasındaki özel mesajları getirir.
     * sender veya receiver olarak myId ve peerId eşleşmeli.
     */
    public List<Message> getDirectConversation(String myId, String peerId) {
        List<Message> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        // (sender=me AND receiver=peer) OR (sender=peer AND receiver=me)
        String sel = "((" + COL_SENDER_ID + "=? AND " + COL_RECEIVER_ID + "=?) OR (" +
                COL_SENDER_ID + "=? AND " + COL_RECEIVER_ID + "=?))";
        Cursor c = db.query(TABLE_MESSAGES, null, sel,
                new String[]{myId, peerId, peerId, myId},
                null, null, COL_TIMESTAMP + " ASC");

        while (c.moveToNext()) {
            list.add(cursorToMessage(c));
        }
        c.close();
        return list;
    }

    /**
     * Bu cihazın daha önce mesaj aldığı/gönderdiği benzersiz kullanıcı ID'lerini döndürür.
     * Konuşma listesi için kullanılır.
     */
    public Set<String> getKnownUserIds(String myId) {
        Set<String> result = new java.util.LinkedHashSet<>();
        SQLiteDatabase db = getReadableDatabase();

        // Bana özel mesaj gönderenler
        Cursor c1 = db.query(TABLE_MESSAGES,
                new String[]{COL_SENDER_ID},
                COL_RECEIVER_ID + "=?",
                new String[]{myId}, null, null, COL_TIMESTAMP + " DESC");
        while (c1.moveToNext()) {
            String sid = c1.getString(0);
            if (!sid.equals(myId)) result.add(sid);
        }
        c1.close();

        // Benim özel mesaj gönderdiğim alıcılar
        Cursor c2 = db.query(TABLE_MESSAGES,
                new String[]{COL_RECEIVER_ID},
                COL_SENDER_ID + "=? AND " + COL_RECEIVER_ID + " IS NOT NULL",
                new String[]{myId}, null, null, COL_TIMESTAMP + " DESC");
        while (c2.moveToNext()) {
            String rid = c2.getString(0);
            if (rid != null && !rid.isEmpty()) result.add(rid);
        }
        c2.close();

        return result;
    }

    private Message cursorToMessage(Cursor c) {
        String id = c.getString(c.getColumnIndexOrThrow(COL_ID));
        String senderId = c.getString(c.getColumnIndexOrThrow(COL_SENDER_ID));
        String content = c.getString(c.getColumnIndexOrThrow(COL_CONTENT));
        long timestamp = c.getLong(c.getColumnIndexOrThrow(COL_TIMESTAMP));
        int hopCount = c.getInt(c.getColumnIndexOrThrow(COL_HOP_COUNT));

        String receiverId = null;
        int rcvIdx = c.getColumnIndex(COL_RECEIVER_ID);
        if (rcvIdx != -1 && !c.isNull(rcvIdx)) {
            receiverId = c.getString(rcvIdx);
        }

        Double latitude = null;
        int latIdx = c.getColumnIndex(COL_LATITUDE);
        if (latIdx != -1 && !c.isNull(latIdx)) {
            latitude = c.getDouble(latIdx);
        }

        Double longitude = null;
        int lngIdx = c.getColumnIndex(COL_LONGITUDE);
        if (lngIdx != -1 && !c.isNull(lngIdx)) {
            longitude = c.getDouble(lngIdx);
        }

        String priority = "LOW";
        int prioIdx = c.getColumnIndex(COL_PRIORITY);
        if (prioIdx != -1 && !c.isNull(prioIdx)) {
            priority = c.getString(prioIdx);
        }

        return new Message(id, senderId, receiverId, content, timestamp, hopCount, latitude, longitude, priority);
    }
}
