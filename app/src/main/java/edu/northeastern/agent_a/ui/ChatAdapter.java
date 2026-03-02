package edu.northeastern.agent_a.ui;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import edu.northeastern.agent_a.R;
import edu.northeastern.agent_a.core.memory.Message;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.MessageViewHolder> {

    private final List<Message> messages;
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    public ChatAdapter(List<Message> messages) {
        this.messages = messages;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        Message msg = messages.get(position);
        boolean isUser = msg.getRole() == Message.Role.USER;

        holder.tvText.setText(msg.getText());
        holder.tvTimestamp.setText(TIME_FMT.format(new Date(msg.getTimestamp())));

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) holder.container.getLayoutParams();
        if (isUser) {
            params.gravity = Gravity.END;
            holder.container.setBackgroundResource(R.drawable.bg_user_bubble);
            holder.tvText.setTextColor(holder.itemView.getContext().getColor(R.color.user_bubble_text));
            holder.tvTimestamp.setTextColor(holder.itemView.getContext().getColor(R.color.user_timestamp));
        } else {
            params.gravity = Gravity.START;
            holder.container.setBackgroundResource(R.drawable.bg_assistant_bubble);
            holder.tvText.setTextColor(holder.itemView.getContext().getColor(R.color.assistant_bubble_text));
            holder.tvTimestamp.setTextColor(holder.itemView.getContext().getColor(R.color.assistant_timestamp));
        }
        holder.container.setLayoutParams(params);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout container;
        final TextView tvText;
        final TextView tvTimestamp;

        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            container = itemView.findViewById(R.id.bubbleContainer);
            tvText = itemView.findViewById(R.id.tvMessageText);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
        }
    }
}
