package edu.northeastern.agent_a.core.tools;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AppCapabilityTool implements Tool {

    @Override
    public String name() { return "apps.capability"; }

    @Override
    public RiskLevel defaultRiskLevel() { return RiskLevel.LOW; }

    @Override
    public ToolSpec spec() {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("query", "String — optional app name to search for");
        params.put("command", "String — optional user command to evaluate against installed apps and built-in tools");
        return new ToolSpec(
                "apps.capability",
                "Searches installed launchable apps and explains whether a requested command is likely executable on this phone.",
                params,
                RiskLevel.LOW);
    }

    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        String query = args.getOrDefault("query", "").trim();
        String command = args.getOrDefault("command", "").trim();

        List<AppInfo> apps = getLaunchableApps(context);
        if (!command.isEmpty()) {
            return ToolResult.success(evaluateCommand(context, command, apps));
        }

        if (!query.isEmpty()) {
            return ToolResult.success(searchApps(query, apps));
        }

        return ToolResult.success(listApps(apps));
    }

    private List<AppInfo> getLaunchableApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(intent, 0);

        List<AppInfo> apps = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            String label = String.valueOf(info.loadLabel(pm));
            String packageName = info.activityInfo != null ? info.activityInfo.packageName : "";
            apps.add(new AppInfo(label, packageName));
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    private String evaluateCommand(Context context, String command, List<AppInfo> apps) {
        String lower = command.toLowerCase(Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append("Capability check:\n");

        if (mentionsAny(lower, "spotify", "music", "song", "播放", "音乐")) {
            boolean installed = hasPackage(apps, "com.spotify.music") || hasLabel(apps, "spotify");
            sb.append(installed
                    ? "- Spotify is installed. Search/open/play-pause commands are available.\n"
                    : "- Spotify is not installed, so Spotify playback/search cannot run.\n");
        }

        if (mentionsAny(lower, "navigate", "map", "directions", "导航", "地图")) {
            boolean canOpenMaps = canResolve(context, new Intent(Intent.ACTION_VIEW,
                    Uri.parse("geo:0,0?q=Boston")));
            sb.append(canOpenMaps
                    ? "- A maps app can handle navigation intents.\n"
                    : "- No maps app was found for navigation intents.\n");
        }

        if (mentionsAny(lower, "call", "dial", "打电话", "拨打")) {
            boolean canDial = canResolve(context, new Intent(Intent.ACTION_DIAL,
                    Uri.parse("tel:5551234")));
            sb.append(canDial
                    ? "- Phone dialer is available.\n"
                    : "- No dialer app was found.\n");
        }

        if (mentionsAny(lower, "sms", "text", "message", "短信", "发给")) {
            boolean canSms = canResolve(context, new Intent(Intent.ACTION_SENDTO,
                    Uri.parse("smsto:5551234")));
            sb.append(canSms
                    ? "- SMS compose is available. The user still confirms sending.\n"
                    : "- No SMS app was found.\n");
        }

        if (mentionsAny(lower, "photo", "picture", "image", "video", "file", "照片", "视频", "文件")) {
            boolean canShare = canResolve(context, new Intent(Intent.ACTION_SEND).setType("image/*"));
            sb.append(canShare
                    ? "- Media search/share can open Android sharing or MMS compose after permission.\n"
                    : "- No app was found to share images.\n");
        }

        if (mentionsAny(lower, "weather", "forecast", "天气")) {
            sb.append("- Weather lookup is available through the network weather tool.\n");
        }

        if (sb.toString().equals("Capability check:\n")) {
            sb.append("- I did not recognize a specific built-in command. Installed app search is available, but execution depends on the target app's Android intents.\n");
        }

        return sb.toString().trim();
    }

    private String searchApps(String query, List<AppInfo> apps) {
        String lower = query.toLowerCase(Locale.US);
        List<String> matches = new ArrayList<>();
        for (AppInfo app : apps) {
            if (app.label.toLowerCase(Locale.US).contains(lower)
                    || app.packageName.toLowerCase(Locale.US).contains(lower)) {
                matches.add(app.label + " (" + app.packageName + ")");
            }
        }

        if (matches.isEmpty()) {
            return "No installed launchable app matched \"" + query + "\".";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Matching apps:\n");
        for (int i = 0; i < Math.min(matches.size(), 12); i++) {
            sb.append(i + 1).append(". ").append(matches.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private String listApps(List<AppInfo> apps) {
        if (apps.isEmpty()) {
            return "No launchable apps were found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Installed launchable apps (first ").append(Math.min(apps.size(), 20)).append("):\n");
        for (int i = 0; i < Math.min(apps.size(), 20); i++) {
            AppInfo app = apps.get(i);
            sb.append(i + 1).append(". ").append(app.label).append("\n");
        }
        return sb.toString().trim();
    }

    private boolean canResolve(Context context, Intent intent) {
        return intent.resolveActivity(context.getPackageManager()) != null;
    }

    private boolean hasPackage(List<AppInfo> apps, String packageName) {
        for (AppInfo app : apps) {
            if (packageName.equals(app.packageName)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasLabel(List<AppInfo> apps, String label) {
        String lower = label.toLowerCase(Locale.US);
        for (AppInfo app : apps) {
            if (app.label.toLowerCase(Locale.US).contains(lower)) {
                return true;
            }
        }
        return false;
    }

    private boolean mentionsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    private static class AppInfo {
        final String label;
        final String packageName;

        AppInfo(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }
}
