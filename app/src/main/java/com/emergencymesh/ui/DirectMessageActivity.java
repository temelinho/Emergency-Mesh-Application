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
import android.widget.ImageButton;
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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DirectMessageActivity extends BaseActivity {

    public static final String EXTRA_PEER_ID   = "peer_id";
    public static final String EXTRA_PEER_NAME = "peer_name";

    private String myNodeId;
    private String peerId;
    private String peerName;

    private MessageDatabase db;
    private ListView listMessages, listConversations;
    private EditText editMessage;
    private TextView txtTitle, txtSubTitle, tvAvatarLetter;
    private View layoutInbox, layoutChat, layoutAvatar;
    private ImageButton btnBack;

    private DmAdapter dmAdapter;
    private ConversationAdapter convAdapter;
    private final List<Message> messageList = new ArrayList<>();
    private final List<Conversation> conversationList = new ArrayList<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    // ─── BroadcastReceiver ────────────────────────────────────────────────────
    private final BroadcastReceiver dmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (EmergencyService.ACTION_MESSAGE_RECEIVED.equals(intent.getAction())) {
                refreshData();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_direct_message);

        db = MessageDatabase.getInstance(this);
        myNodeId = NodeId.get(this);

        initViews();

        peerId   = getIntent().getStringExtra(EXTRA_PEER_ID);
        peerName = getIntent().getStringExtra(EXTRA_PEER_NAME);

        if (peerId != null && !peerId.isEmpty()) {
            openChat(peerId, peerName);
        } else {
            openInbox();
        }
    }

    private void initViews() {
        txtTitle        = findViewById(R.id.txtDmPeerName);
        txtSubTitle     = findViewById(R.id.txtDmSubTitle);
        tvAvatarLetter  = findViewById(R.id.tvDmAvatarLetter);
        layoutAvatar    = findViewById(R.id.layoutDmAvatar);
        layoutInbox     = findViewById(R.id.layoutDmInbox);
        layoutChat      = findViewById(R.id.layoutDmChat);
        btnBack         = findViewById(R.id.btnDmBack);
        editMessage     = findViewById(R.id.editDmMessage);
        listMessages    = findViewById(R.id.listDmMessages);
        listConversations = findViewById(R.id.listDmConversations);

        dmAdapter = new DmAdapter();
        listMessages.setAdapter(dmAdapter);

        convAdapter = new ConversationAdapter();
        listConversations.setAdapter(convAdapter);

        btnBack.setOnClickListener(v -> {
            if (layoutChat.getVisibility() == View.VISIBLE) {
                openInbox();
            } else {
                finish();
            }
        });

        findViewById(R.id.btnDmSend).setOnClickListener(v -> sendDirectMessage());
        findViewById(R.id.btnDmNewContact).setOnClickListener(v -> showNewConversationDialog());

        listConversations.setOnItemClickListener((parent, view, position, id) -> {
            Conversation conv = conversationList.get(position);
            openChat(conv.peerId, conv.peerName);
        });
    }

    private void openInbox() {
        peerId = null;
        peerName = null;
        layoutInbox.setVisibility(View.VISIBLE);
        layoutChat.setVisibility(View.GONE);
        layoutAvatar.setVisibility(View.GONE);
        txtTitle.setText("Mesajlar");
        txtSubTitle.setText("Sohbetlerin");
        refreshData();
    }

    private void openChat(String id, String name) {
        this.peerId = id;
        this.peerName = name != null ? name : resolveUsernameById(id);
        
        layoutInbox.setVisibility(View.GONE);
        layoutChat.setVisibility(View.VISIBLE);
        layoutAvatar.setVisibility(View.VISIBLE);
        
        txtTitle.setText(peerName != null ? peerName : shortenId(peerId));
        txtSubTitle.setText("Uçtan uca şifreli 🔒");
        tvAvatarLetter.setText(String.valueOf(txtTitle.getText().charAt(0)).toUpperCase());

        refreshData();
    }

    private void refreshData() {
        if (layoutInbox.getVisibility() == View.VISIBLE) {
            refreshConversationList();
        } else {
            refreshMessages();
        }
    }

    private void refreshConversationList() {
        List<Message> allMessages = db.getAll();
        Map<String, Conversation> convMap = new HashMap<>();

        for (Message m : allMessages) {
            String otherSide = null;
            if (m.getReceiverId() != null && !m.getReceiverId().isEmpty()) {
                // Bu bir özel mesaj
                if (m.getSenderId().equals(myNodeId)) {
                    otherSide = m.getReceiverId();
                } else if (m.getReceiverId().equals(myNodeId)) {
                    otherSide = m.getSenderId();
                }
            }

            if (otherSide != null) {
                Conversation existing = convMap.get(otherSide);
                if (existing == null || m.getTimestamp() > existing.lastTimestamp) {
                    String name = resolveUsernameFromMessage(m, otherSide);
                    convMap.put(otherSide, new Conversation(otherSide, name, m.getContent(), m.getTimestamp()));
                }
            }
        }

        conversationList.clear();
        conversationList.addAll(convMap.values());
        Collections.sort(conversationList, (c1, c2) -> Long.compare(c2.lastTimestamp, c1.lastTimestamp));
        convAdapter.notifyDataSetChanged();
    }

    private void refreshMessages() {
        if (peerId == null) return;
        List<Message> msgs = db.getDirectConversation(myNodeId, peerId);
        messageList.clear();
        messageList.addAll(msgs);
        dmAdapter.notifyDataSetChanged();
        if (!messageList.isEmpty()) {
            listMessages.setSelection(messageList.size() - 1);
        }
    }

    private void sendDirectMessage() {
        if (peerId == null) return;
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

    private void showNewConversationDialog() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, 0);

        EditText editUsername = new EditText(this);
        editUsername.setHint("Kullanıcı adı girin...");
        layout.addView(editUsername);

        new AlertDialog.Builder(this)
                .setTitle("🔍 Kişi Bul")
                .setView(layout)
                .setPositiveButton("Sohbet Başlat", (dialog, which) -> {
                    String name = editUsername.getText().toString().trim();
                    String foundId = resolveIdByUsername(name);
                    if (foundId != null) {
                        openChat(foundId, name);
                    } else {
                        Toast.makeText(this, "Kullanıcı bulunamadı!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    // ─── Yardımcı Metotlar ────────────────────────────────────────────────────
    private String resolveUsernameFromMessage(Message m, String targetId) {
        String content = m.getContent();
        if (content != null && content.contains("|||")) {
            String[] parts = content.split("\\|\\|\\|", 2);
            if (parts.length == 2) {
                if (m.getSenderId().equals(targetId)) return parts[0];
            }
        }
        return resolveUsernameById(targetId);
    }

    private String resolveUsernameById(String id) {
        List<Message> all = db.getAll();
        for (int i = all.size() - 1; i >= 0; i--) {
            Message m = all.get(i);
            if (m.getSenderId().equals(id) && m.getContent() != null && m.getContent().contains("|||")) {
                return m.getContent().split("\\|\\|\\|", 2)[0];
            }
        }
        return null;
    }

    private String resolveIdByUsername(String username) {
        List<Message> all = db.getAll();
        for (int i = all.size() - 1; i >= 0; i--) {
            Message m = all.get(i);
            if (m.getContent() != null && m.getContent().contains("|||")) {
                String name = m.getContent().split("\\|\\|\\|", 2)[0];
                if (name.equalsIgnoreCase(username)) return m.getSenderId();
            }
        }
        return null;
    }

    private String shortenId(String id) {
        if (id == null) return "?";
        return id.length() > 8 ? id.substring(0, 8) + "…" : id;
    }

    // ─── Veri Modeli ──────────────────────────────────────────────────────────
    private static class Conversation {
        String peerId, peerName, lastMessage;
        long lastTimestamp;
        Conversation(String id, String name, String msg, long ts) {
            this.peerId = id; this.peerName = name; this.lastMessage = msg; this.lastTimestamp = ts;
        }
    }

    // ─── Adapters ─────────────────────────────────────────────────────────────
    private class ConversationAdapter extends ArrayAdapter<Conversation> {
        ConversationAdapter() { super(DirectMessageActivity.this, 0, conversationList); }
        @NonNull
        @Override
        public View getView(int pos, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_conversation, parent, false);
            Conversation c = getItem(pos);
            TextView tvName = convertView.findViewById(R.id.tvConvName);
            TextView tvLast = convertView.findViewById(R.id.tvConvLastMessage);
            TextView tvTime = convertView.findViewById(R.id.tvConvTime);
            TextView tvAvatar = convertView.findViewById(R.id.tvConvAvatar);

            String displayName = c.peerName != null ? c.peerName : shortenId(c.peerId);
            tvName.setText(displayName);
            
            String displayMsg = c.lastMessage;
            if (displayMsg != null && displayMsg.contains("|||")) displayMsg = displayMsg.split("\\|\\|\\|", 2)[1];
            tvLast.setText(displayMsg);
            
            tvTime.setText(timeFmt.format(new Date(c.lastTimestamp)));
            tvAvatar.setText(String.valueOf(displayName.charAt(0)).toUpperCase());
            return convertView;
        }
    }

    private class DmAdapter extends ArrayAdapter<Message> {
        DmAdapter() { super(DirectMessageActivity.this, 0, messageList); }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int pos) { return myNodeId.equals(getItem(pos).getSenderId()) ? 0 : 1; }
        @NonNull
        @Override
        public View getView(int pos, @Nullable View convertView, @NonNull ViewGroup parent) {
            Message m = getItem(pos);
            boolean isMine = myNodeId.equals(m.getSenderId());
            if (convertView == null) convertView = LayoutInflater.from(getContext()).inflate(isMine ? R.layout.item_dm_sent : R.layout.item_dm_received, parent, false);
            
            TextView tvContent = convertView.findViewById(R.id.tvDmContent);
            TextView tvTime = convertView.findViewById(R.id.tvDmTime);
            
            String content = m.getContent();
            if (content != null && content.contains("|||")) content = content.split("\\|\\|\\|", 2)[1];
            
            tvContent.setText(content);
            tvTime.setText(timeFmt.format(new Date(m.getTimestamp())));
            return convertView;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(dmReceiver, new IntentFilter(EmergencyService.ACTION_MESSAGE_RECEIVED),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Context.RECEIVER_NOT_EXPORTED : 0);
        refreshData();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(dmReceiver);
    }

    @Override
    public void onBackPressed() {
        if (layoutChat.getVisibility() == View.VISIBLE) {
            openInbox();
        } else {
            super.onBackPressed();
        }
    }
}

