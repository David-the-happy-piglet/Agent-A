package edu.northeastern.agent_a.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.northeastern.agent_a.R;
import edu.northeastern.agent_a.core.memory.Message;

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_USER = 1;
    private static final int VIEW_TYPE_ASSISTANT = 2;
    private static final int VIEW_TYPE_LOADING = 3;

    private final List<Message> messages;
    private boolean isLoading = false;
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    private static final Pattern URL_PATTERN = Pattern.compile("(https?://\\S+\\.(?:png|jpg|jpeg|gif))", Pattern.CASE_INSENSITIVE);

    public ChatAdapter(List<Message> messages) {
        this.messages = messages;
    }

    public void setLoading(boolean loading) {
        if (this.isLoading != loading) {
            this.isLoading = loading;
            if (loading) {
                notifyItemInserted(messages.size());
            } else {
                notifyItemRemoved(messages.size());
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (position == messages.size()) {
            return VIEW_TYPE_LOADING;
        }
        Message msg = messages.get(position);
        return msg.getRole() == Message.Role.USER ? VIEW_TYPE_USER : VIEW_TYPE_ASSISTANT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_USER) {
            return new UserViewHolder(inflater.inflate(R.layout.item_message_user, parent, false));
        } else if (viewType == VIEW_TYPE_ASSISTANT) {
            return new AssistantViewHolder(inflater.inflate(R.layout.item_message_assistant, parent, false));
        } else {
            return new LoadingViewHolder(inflater.inflate(R.layout.item_loading, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof MessageViewHolder) {
            Message msg = messages.get(position);
            MessageViewHolder msgHolder = (MessageViewHolder) holder;
            
            msgHolder.tvText.setText(msg.getText());
            msgHolder.tvTimestamp.setText(TIME_FMT.format(new Date(msg.getTimestamp())));

            if (holder instanceof AssistantViewHolder) {
                AssistantViewHolder assistantHolder = (AssistantViewHolder) holder;
                
                // Handle SubTasks
                List<Message.SubTask> subTasks = msg.getSubTasks();
                if (subTasks != null && !subTasks.isEmpty()) {
                    assistantHolder.llSubTasks.setVisibility(View.VISIBLE);
                    assistantHolder.llSubTasks.removeAllViews();
                    LayoutInflater inflater = LayoutInflater.from(assistantHolder.itemView.getContext());
                    
                    for (Message.SubTask st : subTasks) {
                        View stView = inflater.inflate(R.layout.item_subtask, assistantHolder.llSubTasks, false);
                        TextView tvLabel = stView.findViewById(R.id.tvSubtaskLabel);
                        ProgressBar pb = stView.findViewById(R.id.pbSubtask);
                        ImageView iv = stView.findViewById(R.id.ivSubtaskStatus);
                        
                        tvLabel.setText(st.label);
                        
                        if (st.isRunning) {
                            pb.setVisibility(View.VISIBLE);
                            iv.setVisibility(View.GONE);
                        } else if (st.isCompleted) {
                            pb.setVisibility(View.GONE);
                            iv.setVisibility(View.VISIBLE);
                            iv.setImageResource(android.R.drawable.checkbox_on_background);
                        } else {
                            // Waiting
                            pb.setVisibility(View.GONE);
                            iv.setVisibility(View.VISIBLE);
                            iv.setImageResource(android.R.drawable.checkbox_off_background);
                        }
                        
                        assistantHolder.llSubTasks.addView(stView);
                    }
                } else {
                    assistantHolder.llSubTasks.setVisibility(View.GONE);
                }

                // Handle images
                Matcher matcher = URL_PATTERN.matcher(msg.getText());
                if (matcher.find()) {
                    String imageUrl = matcher.group(1);
                    assistantHolder.ivContent.setVisibility(View.VISIBLE);
                    Glide.with(assistantHolder.itemView.getContext())
                            .load(imageUrl)
                            .placeholder(android.R.drawable.progress_indeterminate_horizontal)
                            .error(android.R.drawable.stat_notify_error)
                            .into(assistantHolder.ivContent);
                } else {
                    assistantHolder.ivContent.setVisibility(View.GONE);
                }
            }
        }
    }

    @Override
    public int getItemCount() {
        return messages.size() + (isLoading ? 1 : 0);
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        final TextView tvText;
        final TextView tvTimestamp;

        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            tvText = itemView.findViewById(R.id.tvMessageText);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
        }
    }

    static class UserViewHolder extends MessageViewHolder {
        UserViewHolder(@NonNull View itemView) { super(itemView); }
    }

    static class AssistantViewHolder extends MessageViewHolder {
        final ImageView ivContent;
        final LinearLayout llSubTasks;
         AssistantViewHolder(@NonNull View itemView) { 
            super(itemView);
            ivContent = itemView.findViewById(R.id.ivContentImage);
            llSubTasks = itemView.findViewById(R.id.llSubTasks);
        }
    }

    static class LoadingViewHolder extends RecyclerView.ViewHolder {
        LoadingViewHolder(@NonNull View itemView) { super(itemView); }
    }
}
