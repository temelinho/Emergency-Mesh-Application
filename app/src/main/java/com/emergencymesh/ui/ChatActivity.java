package com.emergencymesh.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.emergencymesh.R;
import com.emergencymesh.data.Message;
import com.emergencymesh.data.MessageDatabase;
import com.emergencymesh.service.EmergencyService;
import com.emergencymesh.util.NodeId;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatActivity extends BaseActivity {
    private ArrayAdapter<String> messageAdapter;
    private final List<String> messageDisplayList = new ArrayList<>();
    private EditText editMessage;
    private TextView txtMessageCount;
    private MessageDatabase db;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private String myNodeId;
    private ListView listView;

    private final BroadcastReceiver chatReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (EmergencyService.ACTION_MESSAGE_RECEIVED.equals(intent.getAction())) {
                refreshMessageList();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        db = MessageDatabase.getInstance(this);
        myNodeId = NodeId.get(this);

        editMessage = findViewById(R.id.editMessage);
        txtMessageCount = findViewById(R.id.txtMessageCount);
        listView = findViewById(R.id.listMessages);

        messageAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, messageDisplayList);
        listView.setAdapter(messageAdapter);

        findViewById(R.id.btnSend).setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            sendMessage();
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        refreshMessageList();
    }

    private void sendMessage() {
        String content = editMessage.getText().toString().trim();
        if (content.isEmpty())
            return;

        Intent i = new Intent(this, EmergencyService.class);
        i.setAction(EmergencyService.ACTION_SEND_LOCAL_MESSAGE);
        i.putExtra(EmergencyService.EXTRA_LOCAL_MESSAGE, content);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        editMessage.setText("");
        refreshMessageList();
    }

    private void refreshMessageList() {
        List<Message> messages = db.getAll();
        messageDisplayList.clear();
        for (Message msg : messages) {
            String time = timeFormat.format(new Date(msg.getTimestamp()));
            String sender = msg.getSenderId().equals(myNodeId) ? "Ben" : msg.getSenderId();

            String content = msg.getContent();

            // Gizli İsim Mühürünü (|||) Çöz
            if (content != null && content.contains("|||")) {
                String[] parts = content.split("\\|\\|\\|", 2);
                if (parts.length == 2) {
                    // Eğer mesajı kendimiz atmadıysak, karşı tarafın gerçek adını kullan
                    if (!msg.getSenderId().equals(myNodeId)) {
                        sender = parts[0];
                    }
                    content = parts[1];
                }
            }

            if (content != null && content.length() >= 2 && content.startsWith("!")) {
                String tag = content.substring(0, 2);
                switch (tag) {
                    case "!1": content = content.replaceFirst("!1", "✅ Güvendeyim").trim(); break;
                    case "!2": content = content.replaceFirst("!2", "⚕️ Tıbbi Yardım").trim(); break;
                    case "!3": content = content.replaceFirst("!3", "🏚️ Enkaz Altındayım").trim(); break;
                    case "!4": content = content.replaceFirst("!4", "🔥 Yangın").trim(); break;
                    case "!5": content = content.replaceFirst("!5", "💧 Erzak İhtiyacı").trim(); break;
                    case "!6": content = content.replaceFirst("!6", "⛺ Barınma İhtiyacı").trim(); break;
                    case "!7": content = content.replaceFirst("!7", "⚠️ Genel Tehlike").trim(); break;
                    case "!8": content = content.replaceFirst("!8", "🔋 Şarj Bitiyor").trim(); break;
                    case "!9": content = content.replaceFirst("!9", "🆘 Diğer").trim(); break;
                }
            }

            messageDisplayList.add("[" + time + "] " + sender + ": " + content);
        }
        messageAdapter.notifyDataSetChanged();
        if (txtMessageCount != null) {
            txtMessageCount.setText(messages.size() + " mesaj");
        }
        listView.setSelection(messageAdapter.getCount() - 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(EmergencyService.ACTION_MESSAGE_RECEIVED);
        registerReceiver(chatReceiver, filter,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Context.RECEIVER_NOT_EXPORTED : 0);
        refreshMessageList();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(chatReceiver);
    }
}
