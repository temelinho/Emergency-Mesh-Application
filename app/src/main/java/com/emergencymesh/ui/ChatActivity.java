package com.emergencymesh.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

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

    private final List<Message> messageList = new ArrayList<>();
    private ChatAdapter chatAdapter;

    private EditText editMessage;
    private TextView txtMessageCount;
    private MessageDatabase db;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private String myNodeId;
    private ListView listView;

    // ─── BroadcastReceiver ────────────────────────────────────────────────────
    private final BroadcastReceiver chatReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (EmergencyService.ACTION_MESSAGE_RECEIVED.equals(intent.getAction())) {
                refreshMessageList();
            }
        }
    };

    // ─── onCreate ─────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        db = MessageDatabase.getInstance(this);
        myNodeId = NodeId.get(this);

        editMessage     = findViewById(R.id.editMessage);
        txtMessageCount = findViewById(R.id.txtMessageCount);
        listView        = findViewById(R.id.listMessages);

        chatAdapter = new ChatAdapter();
        listView.setAdapter(chatAdapter);

        // Long press → Özel Mesaj dialog
        listView.setOnItemLongClickListener((parent, view, pos, id) -> {
            if (pos >= messageList.size()) return false;
            Message msg = messageList.get(pos);
            if (msg.getSenderId().equals(myNodeId)) return false; // kendi mesajı

            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showDirectMessagePrompt(msg);
            return true;
        });

        findViewById(R.id.btnSend).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            sendMessage();
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        refreshMessageList();
    }

    // ─── Mesaj Gönder ────────────────────────────────────────────────────────
    private void sendMessage() {
        String content = editMessage.getText().toString().trim();
        if (content.isEmpty()) return;

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

    // ─── Long Press → DM Prompt ──────────────────────────────────────────────
    private void showDirectMessagePrompt(Message msg) {
        String senderDisplay = msg.getSenderId();
        String content = msg.getContent();
        if (content != null && content.contains("|||")) {
            String[] parts = content.split("\\|\\|\\|", 2);
            if (parts.length == 2) senderDisplay = parts[0];
        }

        final String peerId   = msg.getSenderId();
        final String peerName = senderDisplay;

        new AlertDialog.Builder(this)
                .setTitle("💬 " + peerName)
                .setMessage("Bu kişiyle özel mesajlaşmak ister misin?")
                .setPositiveButton("📩 Özel Mesaja Geç", (d, w) -> {
                    Intent intent = new Intent(this, DirectMessageActivity.class);
                    intent.putExtra(DirectMessageActivity.EXTRA_PEER_ID,   peerId);
                    intent.putExtra(DirectMessageActivity.EXTRA_PEER_NAME, peerName);
                    startActivity(intent);
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    // ─── Mesaj Listesini Yenile ───────────────────────────────────────────────
    private void refreshMessageList() {
        List<Message> all = db.getAll();
        List<Message> broadcast = new ArrayList<>();
        for (Message m : all) {
            if (!m.isDirectMessage()) broadcast.add(m);
        }
        messageList.clear();
        messageList.addAll(broadcast);
        chatAdapter.notifyDataSetChanged();

        if (txtMessageCount != null) {
            txtMessageCount.setText(broadcast.size() + " mesaj");
        }
        if (!messageList.isEmpty()) {
            listView.setSelection(messageList.size() - 1);
        }
    }

    // ─── Custom Adapter ───────────────────────────────────────────────────────
    private class ChatAdapter extends ArrayAdapter<Message> {
        ChatAdapter() {
            super(ChatActivity.this, 0, messageList);
        }

        @Override
        public int getViewTypeCount() { return 2; }

        @Override
        public int getItemViewType(int position) {
            Message msg = getItem(position);
            return (msg != null && msg.getSenderId().equals(myNodeId)) ? 0 : 1;
        }

        @NonNull
        @Override
        public View getView(int pos, @Nullable View convertView, @NonNull ViewGroup parent) {
            Message msg  = getItem(pos);
            boolean mine = (msg != null && msg.getSenderId().equals(myNodeId));

            if (convertView == null) {
                int layout = mine ? R.layout.item_chat_sent : R.layout.item_chat_received;
                convertView = LayoutInflater.from(getContext()).inflate(layout, parent, false);
            }

            if (msg == null) return convertView;

            String time    = timeFormat.format(new Date(msg.getTimestamp()));
            String sender  = mine ? "Ben" : msg.getSenderId();
            String content = msg.getContent();

            // İsim mühürünü çöz
            if (content != null && content.contains("|||")) {
                String[] parts = content.split("\\|\\|\\|", 2);
                if (parts.length == 2) {
                    if (!mine) sender = parts[0];
                    content = parts[1];
                }
            }

            // Durum etiketleri
            if (content != null && content.length() >= 2 && content.startsWith("!")) {
                switch (content.substring(0, 2)) {
                    case "!1": content = "✅ Güvendeyim" + content.substring(2).trim(); break;
                    case "!2": content = "⚕️ Tıbbi Yardım" + content.substring(2).trim(); break;
                    case "!3": content = "🏚️ Enkaz Altındayım" + content.substring(2).trim(); break;
                    case "!4": content = "🔥 Yangın" + content.substring(2).trim(); break;
                    case "!5": content = "💧 Erzak İhtiyacı" + content.substring(2).trim(); break;
                    case "!6": content = "⛺ Barınma İhtiyacı" + content.substring(2).trim(); break;
                    case "!7": content = "⚠️ Genel Tehlike" + content.substring(2).trim(); break;
                    case "!8": content = "🔋 Şarj Bitiyor" + content.substring(2).trim(); break;
                    case "!9": content = "🆘 Diğer" + content.substring(2).trim(); break;
                }
            }

            TextView tvContent = convertView.findViewById(R.id.tvChatContent);
            TextView tvTime    = convertView.findViewById(R.id.tvChatTime);

            if (tvContent != null) tvContent.setText(content);
            if (tvTime    != null) tvTime.setText(time);

            if (!mine) {
                // Avatar harfi
                TextView tvAvatar = convertView.findViewById(R.id.tvChatAvatarLetter);
                TextView tvName   = convertView.findViewById(R.id.tvChatSenderName);
                String initial = sender.isEmpty() ? "?" : String.valueOf(sender.charAt(0)).toUpperCase();
                if (tvAvatar != null) tvAvatar.setText(initial);
                if (tvName   != null) tvName.setText(sender);
            }

            return convertView;
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────
    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(EmergencyService.ACTION_MESSAGE_RECEIVED);
        registerReceiver(chatReceiver, f,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Context.RECEIVER_NOT_EXPORTED : 0);
        refreshMessageList();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(chatReceiver);
    }
}
