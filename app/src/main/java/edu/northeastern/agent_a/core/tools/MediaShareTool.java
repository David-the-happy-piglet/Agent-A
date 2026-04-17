package edu.northeastern.agent_a.core.tools;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Telephony;

import androidx.core.content.ContextCompat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class MediaShareTool implements Tool {

    @Override
    public String name() { return "media.share"; }

    @Override
    public RiskLevel defaultRiskLevel() { return RiskLevel.HIGH; }

    @Override
    public ToolSpec spec() {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("file_type", "String — 'photo' | 'image' | 'video' | 'any'");
        params.put("recency", "String — 'latest' or 'recent'");
        params.put("contact_name", "String — optional recipient name for display");
        params.put("phone", "String — optional recipient phone number from contacts.lookup");
        params.put("message", "String — optional text to include");
        return new ToolSpec(
                "media.share",
                "Finds the latest accessible photo/video from MediaStore and opens Android share/MMS compose with the attachment. Requires media read permission.",
                params,
                RiskLevel.HIGH);
    }

    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        String fileType = args.getOrDefault("file_type", "photo").toLowerCase(Locale.US).trim();
        if (!hasMediaPermission(context, fileType)) {
            return ToolResult.needPermission("Media permission is required to find recent photos or videos.");
        }

        Candidate candidate;
        try {
            candidate = findLatest(context, fileType);
        } catch (Exception e) {
            return ToolResult.fail("Media search failed: " + e.getMessage());
        }

        if (candidate == null) {
            return ToolResult.fail("No recent " + friendlyType(fileType) + " found in shared media.");
        }

        String phone = args.getOrDefault("phone", "").trim();
        String contactName = args.getOrDefault("contact_name", "").trim();
        String message = args.getOrDefault("message", "").trim();

        try {
            openShareIntent(context, candidate, phone, message);
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(candidate.displayName).append(" and opened sharing");
            if (!contactName.isEmpty()) {
                sb.append(" for ").append(contactName);
            } else if (!phone.isEmpty()) {
                sb.append(" for ").append(phone);
            }
            sb.append(". Please confirm the final send in the target app.");
            return ToolResult.success(sb.toString());
        } catch (Exception e) {
            return ToolResult.fail("Failed to share media: " + e.getMessage());
        }
    }

    private boolean hasMediaPermission(Context context, String fileType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean needsImages = !"video".equals(fileType);
            boolean needsVideo = "video".equals(fileType) || "any".equals(fileType);
            boolean imagesGranted = !needsImages
                    || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
            boolean videoGranted = !needsVideo
                    || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED;
            return imagesGranted && videoGranted;
        }

        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private Candidate findLatest(Context context, String fileType) {
        if ("video".equals(fileType)) {
            return queryLatestVideo(context);
        }

        if ("any".equals(fileType)) {
            Candidate image = queryLatestImage(context);
            Candidate video = queryLatestVideo(context);
            if (image == null) return video;
            if (video == null) return image;
            return image.dateAdded >= video.dateAdded ? image : video;
        }

        return queryLatestImage(context);
    }

    private Candidate queryLatestImage(Context context) {
        return queryLatest(
                context,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "image/*",
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATE_ADDED);
    }

    private Candidate queryLatestVideo(Context context) {
        return queryLatest(
                context,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "video/*",
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.DATE_ADDED);
    }

    private Candidate queryLatest(Context context, Uri baseUri, String fallbackMime,
                                  String idColumn, String nameColumn,
                                  String mimeColumn, String dateColumn) {
        String[] projection = new String[]{idColumn, nameColumn, mimeColumn, dateColumn};
        String sortOrder = dateColumn + " DESC";

        try (Cursor cursor = context.getContentResolver().query(
                baseUri, projection, null, null, sortOrder)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }

            long id = cursor.getLong(0);
            String displayName = cursor.getString(1);
            String mimeType = cursor.getString(2);
            long dateAdded = cursor.getLong(3);
            Uri contentUri = ContentUris.withAppendedId(baseUri, id);
            return new Candidate(
                    contentUri,
                    displayName != null ? displayName : "recent media",
                    mimeType != null ? mimeType : fallbackMime,
                    dateAdded);
        }
    }

    private void openShareIntent(Context context, Candidate candidate,
                                 String phone, String message) {
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType(candidate.mimeType);
        sendIntent.putExtra(Intent.EXTRA_STREAM, candidate.uri);
        if (!message.isEmpty()) {
            sendIntent.putExtra(Intent.EXTRA_TEXT, message);
            sendIntent.putExtra("sms_body", message);
        }
        if (!phone.isEmpty()) {
            sendIntent.putExtra("address", phone);
            String smsPackage = Telephony.Sms.getDefaultSmsPackage(context);
            if (smsPackage != null && !smsPackage.trim().isEmpty()) {
                sendIntent.setPackage(smsPackage);
            }
        }
        sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Intent chooser = Intent.createChooser(sendIntent, "Share " + candidate.displayName);
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(chooser);
    }

    private String friendlyType(String fileType) {
        if ("video".equals(fileType)) return "video";
        if ("any".equals(fileType)) return "media item";
        return "photo";
    }

    private static class Candidate {
        final Uri uri;
        final String displayName;
        final String mimeType;
        final long dateAdded;

        Candidate(Uri uri, String displayName, String mimeType, long dateAdded) {
            this.uri = uri;
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.dateAdded = dateAdded;
        }
    }
}
