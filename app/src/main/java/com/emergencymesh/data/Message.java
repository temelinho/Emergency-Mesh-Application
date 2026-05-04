package com.emergencymesh.data;

import java.util.UUID;

public class Message {

    private String id; // Benzersiz mesaj ID
    private String senderId; // Gönderen node ID
    private String receiverId; // Alıcı node ID (null = herkese, dolu = özel mesaj)
    private String content; // Mesaj içeriği
    private long timestamp; // Oluşturulma zamanı
    private int hopCount; // Kaç node üzerinden geçti

    // Yeni eklenen alanlar (Backend sync için)
    private Double latitude; // Enlem (opsiyonel)
    private Double longitude; // Boylam (opsiyonel)
    private String priority; // Öncelik (örn: HIGH, LOW)

    public Message() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
        this.hopCount = 0;
    }

    public Message(String senderId, String content) {
        this.id = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
        this.hopCount = 0;
        this.senderId = senderId;
        this.receiverId = null; // null = broadcast
        this.content = content;
        this.priority = determinePriority(content);
    }

    public Message(String senderId, String receiverId, String content) {
        this.id = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
        this.hopCount = 0;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.content = content;
        this.priority = determinePriority(content);
    }

    public Message(String senderId, String content, Double latitude, Double longitude) {
        this.id = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
        this.hopCount = 0;
        this.senderId = senderId;
        this.receiverId = null;
        this.content = content;
        this.latitude = latitude;
        this.longitude = longitude;
        this.priority = determinePriority(content);
    }

    private static String determinePriority(String content) {
        if (content == null) return "LOW";
        
        // İsmi temizleyip sadece koda bakmak için (Username|||!1 formatı için)
        String actualContent = content;
        if (content.contains("|||")) {
            String[] parts = content.split("\\|\\|\\|", 2);
            if (parts.length == 2) actualContent = parts[1];
        }

        if (actualContent.startsWith("!3")) {
            return "EMERGENCY"; // Enkaz Altındayım
        } else if (actualContent.startsWith("!2") || actualContent.startsWith("!7")) {
            return "HIGH";      // Yardım Gerekiyor veya Tehlike
        } else if (actualContent.startsWith("!5")) {
            return "MEDIUM";    // Gıda/Erzak İhtiyacı
        } else if (actualContent.startsWith("!1") || actualContent.startsWith("!8")) {
            return "LOW";       // Güvendeyim veya Şarj Az
        }
        
        return "LOW";
    }

    // Full constructor (DB'den okurken)
    public Message(String id, String senderId, String receiverId, String content, long timestamp, int hopCount,
            Double latitude, Double longitude, String priority) {
        this.id = id;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.content = content;
        this.timestamp = timestamp;
        this.hopCount = hopCount;
        this.latitude = latitude;
        this.longitude = longitude;
        this.priority = priority;
    }

    /** Geriye dönük uyumluluk için (receiverId=null) */
    public Message(String id, String senderId, String content, long timestamp, int hopCount,
            Double latitude, Double longitude, String priority) {
        this(id, senderId, null, content, timestamp, hopCount, latitude, longitude, priority);
    }

    public Message(String id, String senderId, String content, long timestamp, int hopCount) {
        this(id, senderId, null, content, timestamp, hopCount, null, null, determinePriority(content));
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(String receiverId) {
        this.receiverId = receiverId;
    }

    public boolean isDirectMessage() {
        return receiverId != null && !receiverId.isEmpty();
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public int getHopCount() {
        return hopCount;
    }

    public void setHopCount(int hopCount) {
        this.hopCount = hopCount;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public void incrementHop() {
        this.hopCount++;
    }

    // Serileştirme: transfer için basit format
    // Format v2: id|senderId|receiverId|timestamp|hopCount|lat|lng|priority|content
    // (İçerik en sonda olmalı ki içindeki | işaretleri sistemi bozmasın)
    public String serialize() {
        String latStr = latitude != null ? String.valueOf(latitude) : "";
        String lngStr = longitude != null ? String.valueOf(longitude) : "";
        String prioStr = priority != null ? priority : "LOW";
        String rcvStr = receiverId != null ? receiverId : "";

        return id + "|" + senderId + "|" + rcvStr + "|" + timestamp + "|" + hopCount + "|" +
                latStr + "|" + lngStr + "|" + prioStr + "|" + content;
    }

    public static Message deserialize(String data) {
        try {
            // Sınıra 9 diyoruz, 9. parça (content) içindeki | işaretleri bölünmeyecek
            String[] parts = data.split("\\|", 9);
            if (parts.length < 9)
                return null;

            String id = parts[0];
            String senderId = parts[1];
            String receiverId = parts[2].isEmpty() ? null : parts[2];
            long timestamp = Long.parseLong(parts[3]);
            int hopCount = Integer.parseInt(parts[4]);

            Double latitude = null;
            Double longitude = null;
            String priority = "LOW";

            if (!parts[5].isEmpty())
                latitude = Double.parseDouble(parts[5]);
            if (!parts[6].isEmpty())
                longitude = Double.parseDouble(parts[6]);

            priority = parts[7];
            String content = parts[8];

            return new Message(id, senderId, receiverId, content, timestamp, hopCount, latitude, longitude, priority);
        } catch (Exception e) {
            return null;
        }
    }
}
