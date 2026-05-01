package com.emergencymesh.util;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/**
 * Uygulamanın sesli yönlendirme motorudur.
 * Afetzedeleri sesli olarak bilgilendirmek ve motive etmek için kullanılır.
 */
public class VoiceAssistant implements TextToSpeech.OnInitListener {

    private static final String TAG = "VoiceAssistant";
    private TextToSpeech tts;
    private boolean isReady = false;

    // Buton tag kodlarına karşılık gelen sesli mesajlar
    private static final java.util.Map<String, String> BUTTON_MESSAGES = new java.util.HashMap<>();
    static {
        BUTTON_MESSAGES.put("!1", "Güvendeyim bildirimi gönderildi. Yakındaki cihazlara iletildi.");
        BUTTON_MESSAGES.put("!2", "Acil yardım çağrısı gönderildi. Kurtarma ekipleri bilgilendirildi. Sakin olun, yardım yolda.");
        BUTTON_MESSAGES.put("!3", "Enkaz altında olduğunuz bildirildi. Lütfen hareketsiz kalın ve güçlü sesler çıkarmaya çalışın. Kurtarma ekipleri geliyor.");
        BUTTON_MESSAGES.put("!5", "Tedarik talebi gönderildi. Su ve gıda ihtiyacınız bildirildi.");
        BUTTON_MESSAGES.put("!7", "Tehlike uyarısı gönderildi. Bulunduğunuz bölge tehlikeli olarak işaretlendi.");
        BUTTON_MESSAGES.put("!8", "Batarya düşük uyarısı gönderildi. Lütfen telefonu kapatmayın ve şarj etmeye çalışın.");
    }

    // Durum değişikliği mesajları (backend'den gelen status güncellemeleri)
    public static final String MSG_ASSIGNED = "Müjde! Bir kurtarma ekibi size atandı. Yardım yolda. Sakin olun, telefonunuzu kapatmayın. Yakında kurtarılacaksınız.";
    public static final String MSG_RESOLVED = "Durumunuz çözüldü olarak işaretlendi. Umarız güvendesinizdir. Geçmiş olsun.";
    public static final String MSG_ACTIVE_REMINDER = "Uygulamanız çalışıyor. Konumunuz yetkililere iletildi. Telefonunuzu kapatmayın.";

    public VoiceAssistant(Context context) {
        tts = new TextToSpeech(context, this);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(new Locale("tr", "TR"));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Türkçe yoksa varsayılan dil kullan
                tts.setLanguage(Locale.getDefault());
                Log.w(TAG, "Türkçe TTS bulunamadı, varsayılan dil kullanılıyor.");
            }
            isReady = true;
            Log.i(TAG, "TTS motoru hazır.");
        } else {
            Log.e(TAG, "TTS başlatılamadı. Status: " + status);
        }
    }

    /**
     * Verilen metni sesli okur.
     */
    public void speak(String text) {
        if (isReady && tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "em_tts_" + System.currentTimeMillis());
        }
    }

    /**
     * Butona tıklanınca ilgili sesli mesajı söyler.
     * @param buttonTag Butonun tag kodu (ör: "!1", "!3")
     */
    public void announceButtonAction(String buttonTag) {
        String message = BUTTON_MESSAGES.get(buttonTag);
        if (message != null) {
            speak(message);
        } else {
            speak("Bildirim gönderildi.");
        }
    }

    /**
     * Kaynakları serbest bırakır. Activity'nin onDestroy'unda çağrılmalıdır.
     */
    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        isReady = false;
    }
}
