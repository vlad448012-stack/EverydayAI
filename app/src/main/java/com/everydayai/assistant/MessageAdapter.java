package com.everydayai.assistant;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.Holder> {

    private final List<ChatMessage> list;
    public MessageAdapter(List<ChatMessage> list) { this.list = list; }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup p, int t) {
        View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_message, p, false);
        return new Holder(v);
    }

    @Override public void onBindViewHolder(@NonNull Holder h, int pos) { h.bind(list.get(pos)); }
    @Override public int getItemCount() { return list.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        LinearLayout userBlock, aiBlock;
        TextView userText, aiText, userTime, aiTime;

        Holder(View v) {
            super(v);
            userBlock = v.findViewById(R.id.user_block);
            aiBlock = v.findViewById(R.id.ai_block);
            userText = v.findViewById(R.id.user_text);
            aiText = v.findViewById(R.id.ai_text);
            userTime = v.findViewById(R.id.user_time);
            aiTime = v.findViewById(R.id.ai_time);
        }

        void bind(ChatMessage m) {
            if (m.isUser()) {
                userBlock.setVisibility(View.VISIBLE);
                aiBlock.setVisibility(View.GONE);
                userText.setText(m.getText());
                userTime.setText(m.getTime());
            } else {
                aiBlock.setVisibility(View.VISIBLE);
                userBlock.setVisibility(View.GONE);
                aiText.setText(m.getText());
                aiTime.setText(m.getTime());
            }
        }
    }
}
