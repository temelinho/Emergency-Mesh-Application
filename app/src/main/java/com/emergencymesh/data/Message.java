package com.emergencymesh.data;

import java.util.UUID;

public class Message {

    private String id; // Benzersiz mesaj ID
    private String senderId; // Gönderen node ID
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
        this.content = content;
        this.priority = determinePriority(content);
    }

    public Message(String senderId, String content, Double latitude, Double longitude) {
        this.id = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
        this.hopCount = 0;
        this.senderId = senderId;
        this.content = content;
        this.latitude = latitude;
        this.longitude = longitude;
        this.priority = determinePriority(content);
    }

    private static String determinePriority(String content) {
        if (content != null && content.startsWith("!")) {
            return "HIGH"; // Acil durum etiketleri (örn. !3, !4)
        }
        return "LOW";
    }

    // Full constructor (DB'den okurken)
    public Message(String id, String senderId, String content, long timestamp, int hopCount,
            Double latitude, Double longitude, String priority) {
        this.id = id;
        this.senderId = senderId;
        this.content = content;
        this.timestamp = timestamp;
        this.hopCount = hopCount;
        this.latitude = latitude;
        this.longitude = longitude;
        this.priority = priority;
    }

    public Message(String id, String senderId, String content, long timestamp, int hopCount) {
        this(id, senderId, content, timestamp, hopCount, null, null, determinePriority(content));
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
    // Format: id|senderId|content|timestamp|hopCount|lat|lng|priority
    public String serialize() {
        String latStr = latitude != null ? String.valueOf(latitude) : "";
        String lngStr = longitude != null ? String.valueOf(longitude) : "";
        String prioStr = priority != null ? priority : "LOW";

        return id + "|" + senderId + "|" + content + "|" + timestamp + "|" + hopCount + "|" +
                latStr + "|" + lngStr + "|" + prioStr;
    }

    public static Message deserialize(String data) {
        try {
            // Sınıra 8 diyoruz, böylece sondaki boşlukları vb kaybetmeden alabiliriz
            String[] parts = data.split("\\|", 8);
            if (parts.length < 5)
                return null; // Geriye dönük uyumluluk (en az 5 alan)

            String id = parts[0];
            String senderId = parts[1];
            String content = parts[2];
            long timestamp = Long.parseLong(parts[3]);
            int hopCount = Integer.parseInt(parts[4]);

            Double latitude = null;
            Double longitude = null;
            String priority = "LOW";

            if (parts.length >= 8) {
                if (!parts[5].isEmpty())
                    latitude = Double.parseDouble(parts[5]);
                if (!parts[6].isEmpty())
                    longitude = Double.parseDouble(parts[6]);
                priority = parts[7];
            } else {
                // Eski mesaj formatı
                if (content != null && content.startsWith("!")) {
                    priority = "HIGH";
                }
            }

            return new Message(id, senderId, content, timestamp, hopCount, latitude, longitude, priority);
        } catch (Exception e) {
            return null;
        }
    }
}
