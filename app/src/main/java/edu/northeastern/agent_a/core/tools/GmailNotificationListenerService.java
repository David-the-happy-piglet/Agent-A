package edu.northeastern.agent_a.core.tools;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class GmailNotificationListenerService extends NotificationListenerService {

    static final String GMAIL_PACKAGE = "com.google.android.gm";
    static final String PREFS_NAME = "gmail_notification_cache";
    static final String KEY_EMAILS = "emails";
    private static final int MAX_CACHED_EMAILS = 100;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !GMAIL_PACKAGE.equals(sbn.getPackageName())) {
            return;
        }

        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) {
            return;
        }

        String title = safeString(notification.extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = safeString(notification.extras.getCharSequence(Notification.EXTRA_TEXT));
        String bigText = safeString(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String subText = safeString(notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        CharSequence[] lines = notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);

        if (title.isEmpty() && text.isEmpty() && bigText.isEmpty()) {
            return;
        }

        try {
            JSONObject item = new JSONObject();
            item.put("timestamp", sbn.getPostTime());
            item.put("title", title);
            item.put("text", text);
            item.put("bigText", bigText);
            item.put("subText", subText);

            JSONArray parsedLines = new JSONArray();
            if (lines != null) {
                for (CharSequence line : lines) {
                    String value = safeString(line);
                    if (!value.isEmpty()) {
                        parsedLines.put(value);
                    }
                }
            }
            item.put("lines", parsedLines);

            append(item);
        } catch (JSONException ignored) {
            // Ignore malformed notification extras.
        }
    }

    private void append(JSONObject item) throws JSONException {
        JSONArray existing = new JSONArray(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_EMAILS, "[]"));
        JSONArray next = new JSONArray();
        next.put(item);

        for (int i = 0; i < existing.length() && next.length() < MAX_CACHED_EMAILS; i++) {
            next.put(existing.getJSONObject(i));
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_EMAILS, next.toString())
                .apply();
    }

    private String safeString(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }
}
