package com.emergencymesh.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

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

public class DirectMessageActivity extends BaseActivity {

    public static final String EXTRA_PEER_ID   = "peer_id";
    public static final String EXTRA_PEER_NAME = "peer_name";

    private String myNodeId;
    private String peerId;
    private String peerName;

    private MessageDatabase db;
    private ListView listMessages;
    private EditText editMessage;
    private TextView txtPeerName, txtMessageCount;
    private DmAdapter dmAdapter;
    private final List<Message> messageList = new ArrayList<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    // ─── BroadcastReceiver ────────────────────────────────────────────────────
    private final BroadcastReceiver dmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (EmergencyService.ACTION_MESSAGE_RECEIVED.equals(intent.getAction())) {
                String dmPeer = intent.getStringExtra("dm_peer_id");
                // Sadece bu konuşmaya ait mesajları yenile
                if (dmPeer == null || dmPeer.equals(peerId)) {
                    refreshMessages();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_direct_message);

        db = MessageDatabase.getInstance(this);
        myNodeId = NodeId.get(this);

        peerId   = getIntent().getStringExtra(EXTRA_PEER_ID);
        peerName = getIntent().getStringExtra(EXTRA_PEER_NAME);

        // Eğer peerId yoksa kullanıcıya sor
        if (peerId == null || peerId.isEmpty()) {
            showNewConversationDialog();
        }

        initViews();
        refreshMessages();
    }

    private void initViews() {
        txtPeerName    = findViewById(R.id.txtDmPeerName);
        txtMessageCount = findViewById(R.id.txtDmMessageCount);
        editMessage    = findViewById(R.id.editDmMessage);
        listMessages   = findViewById(R.id.listDmMessages);

        if (peerName != null && !peerName.isEmpty()) {
            txtPeerName.setText(peerName);
            // Avatar harfini set et
            TextView tvAvatar = findViewById(R.id.tvDmAvatarLetter);
            if (tvAvatar != null) {
                tvAvatar.setText(String.valueOf(peerName.charAt(0)).toUpperCase());
            }
        } else if (peerId != null) {
            txtPeerName.setText(shortenId(peerId));
        } else {
            txtPeerName.setText("Yeni Konuşma");
        }

        dmAdapter = new DmAdapter();
        listMessages.setAdapter(dmAdapter);

        findViewById(R.id.btnDmBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnDmSend).setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            sendDirectMessage();
        });
        findViewById(R.id.btnDmNewContact).setOnClickListener(v -> showNewConversationDialog());
    }

    private void sendDirectMessage() {
        if (peerId == null || peerId.isEmpty()) {
            showNewConversationDialog();
            return;
        }
        String content = editMessage.getText().toString().trim();
        if (content.isEmpty()) return;

        Intent i = new Intent(this, EmergencyService.class);
        i.setAction(EmergencyService.ACTION_SEND_DIRECT_MESSAGE);
        i.putExtra(EmergencyService.EXTRA_LOCAL_MESSAGE, content);
        i.putExtra(EmergencyService.EXTRA_DIRECT_RECEIVER, peerId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        editMessage.setText("");
        refreshMessages();
    }

    private void refreshMessages() {
        if (peerId == null || peerId.isEmpty()) return;
        List<Message> msgs = db.getDirectConversation(myNodeId, peerId);
        messageList.clear();
        messageList.addAll(msgs);
        dmAdapter.notifyDataSetChanged();
        if (txtMessageCount != null) {
            txtMessageCount.setText(msgs.size() + " mesaj");
        }
        if (!messageList.isEmpty()) {
            listMessages.setSelection(messageList.size() - 1);
        }
    }

    // ─── Yeni konuşma dialog ──────────────────────────────────────────────────
    private void showNewConversationDialog() {
        // Basit bir username girişi — eski UUID listesi kaldırıldı
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, 0);

        TextView label = new TextView(this);
        label.setText("Kullanıcı adı girin:");
        label.setTextSize(14f);
        label.setPadding(0, 0, 0, pad / 2);
        layout.addView(label);

        EditText editUsername = new EditText(this);
        editUsername.setHint("Örn: ahmet, ayse...");
        editUsername.setSingleLine(true);
        editUsername.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        layout.addView(editUsername);

        new AlertDialog.Builder(this)
                .setTitle("💬 Yeni Özel Mesaj")
                .setView(layout)
                .setPositiveButton("Başlat", (dialog, which) -> {
                    String enteredName = editUsername.getText().toString().trim();
                    if (enteredName.isEmpty()) {
                        Toast.makeText(this, "Kullanıcı adı boş olamaz!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // Ağ geçmişinden bu kullanıcı adına ait Node ID'yi bul
                    String foundId = resolveIdByUsername(enteredName);
                    if (foundId == null) {
                        Toast.makeText(this,
                                "\"" + enteredName + "\" adlı kullanıcı henüz ağda mesaj atmamış.\n" +
                                "Genel Sohbet'te mesajına uzun basarak özel mesaj başlat.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    peerId   = foundId;
                    peerName = enteredName;
                    if (txtPeerName != null) txtPeerName.setText("💬 " + peerName);
                    TextView tvAvatar = findViewById(R.id.tvDmAvatarLetter);
                    if (tvAvatar != null) tvAvatar.setText(String.valueOf(peerName.charAt(0)).toUpperCase());
                    refreshMessages();
                })
                .setNegativeButton("İptal", null)
                .show();

        // Klavyeyi otomatik aç
        editUsername.postDelayed(() -> {
            editUsername.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(editUsername, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }

    /** Mesaj geçmişinde kullanıcı adına göre Node ID bul */
    private String resolveIdByUsername(String username) {
        List<Message> all = db.getAll();
        for (int i = all.size() - 1; i >= 0; i--) {
            Message m = all.get(i);
            String content = m.getContent();
            if (content != null && content.contains("|||")) {
                String[] parts = content.split("\\|\\|\\|", 2);
                if (parts.length == 2 && parts[0].trim().equalsIgnoreCase(username)) {
                    return m.getSenderId();
                }
            }
        }
        return null;
    }

    private String shortenId(String id) {
        if (id == null) return "?";
        return id.length() > 8 ? id.substring(0, 8) + "…" : id;
    }

    // ─── Custom Adapter ───────────────────────────────────────────────────────
    private class DmAdapter extends ArrayAdapter<Message> {
        DmAdapter() {
            super(DirectMessageActivity.this, 0, messageList);
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            Message msg = getItem(position);
            return (msg != null && myNodeId.equals(msg.getSenderId())) ? 0 : 1;
        }

        @NonNull
        @Override
        public View getView(int pos, @Nullable View convertView, @NonNull ViewGroup parent) {
            Message msg = getItem(pos);
            boolean isMine = msg != null && myNodeId.equals(msg.getSenderId());

            if (convertView == null) {
                int layout = isMine ? R.layout.item_dm_sent : R.layout.item_dm_received;
                convertView = LayoutInflater.from(getContext()).inflate(layout, parent, false);
            }

            TextView tvContent = convertView.findViewById(R.id.tvDmContent);
            TextView tvTime    = convertView.findViewById(R.id.tvDmTime);
            TextView tvSender  = convertView.findViewById(R.id.tvDmSender);

            if (msg != null) {
                String time = timeFmt.format(new Date(msg.getTimestamp()));
                String content = msg.getContent();
                String sender = isMine ? "Ben" : shortenId(msg.getSenderId());

                // İsim mühürünü çöz
                if (content != null && content.contains("|||")) {
                    String[] parts = content.split("\\|\\|\\|", 2);
                    if (parts.length == 2) {
                        if (!isMine) sender = parts[0];
                        content = parts[1];
                    }
                }

                if (tvContent != null) tvContent.setText(content);
                if (tvTime != null)    tvTime.setText(time);
                if (tvSender != null)  {
                    String initial = sender.isEmpty() ? "?" : String.valueOf(sender.charAt(0)).toUpperCase();
                    tvSender.setText(initial);
                }
            }
            return convertView;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(EmergencyService.ACTION_MESSAGE_RECEIVED);
        registerReceiver(dmReceiver, f,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Context.RECEIVER_NOT_EXPORTED : 0);
        refreshMessages();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(dmReceiver);
    }
}
