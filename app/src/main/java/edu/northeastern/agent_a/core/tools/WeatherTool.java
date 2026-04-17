package edu.northeastern.agent_a.core.tools;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class WeatherTool implements Tool {

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    @Override
    public String name() { return "weather.lookup"; }

    @Override
    public RiskLevel defaultRiskLevel() { return RiskLevel.LOW; }

    @Override
    public ToolSpec spec() {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("location", "String — optional city/address; leave empty to use network/IP-based approximate location");
        return new ToolSpec(
                "weather.lookup",
                "Fetches current weather and today's high/low for a city or approximate current location.",
                params,
                RiskLevel.LOW);
    }

    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        String location = args.getOrDefault("location", "").trim();
        try {
            JSONObject root = fetchWeather(location);
            return ToolResult.success(formatWeather(root, location));
        } catch (Exception e) {
            return ToolResult.fail("Weather lookup failed: " + e.getMessage());
        }
    }

    private JSONObject fetchWeather(String location) throws Exception {
        String encodedLocation = location.isEmpty() ? "" : Uri.encode(location);
        URL url = new URL("https://wttr.in/" + encodedLocation + "?format=j1");

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "AgentA/1.0 weather client");
        conn.setRequestProperty("Accept", "application/json");

        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + conn.getResponseCode());
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    private String formatWeather(JSONObject root, String requestedLocation) throws Exception {
        JSONObject current = root.getJSONArray("current_condition").getJSONObject(0);
        JSONObject today = root.getJSONArray("weather").getJSONObject(0);

        String place = requestedLocation;
        JSONArray nearestArea = root.optJSONArray("nearest_area");
        if (nearestArea != null && nearestArea.length() > 0) {
            JSONObject area = nearestArea.optJSONObject(0);
            if (area != null) {
                String areaName = firstValue(area.optJSONArray("areaName"));
                String region = firstValue(area.optJSONArray("region"));
                String country = firstValue(area.optJSONArray("country"));
                StringBuilder name = new StringBuilder();
                if (!areaName.isEmpty()) name.append(areaName);
                if (!region.isEmpty()) {
                    if (name.length() > 0) name.append(", ");
                    name.append(region);
                }
                if (!country.isEmpty()) {
                    if (name.length() > 0) name.append(", ");
                    name.append(country);
                }
                if (name.length() > 0) {
                    place = name.toString();
                }
            }
        }
        if (place == null || place.trim().isEmpty()) {
            place = "your approximate location";
        }

        String desc = firstValue(current.optJSONArray("weatherDesc"));
        String tempC = current.optString("temp_C", "?");
        String feelsC = current.optString("FeelsLikeC", "?");
        String humidity = current.optString("humidity", "?");
        String windMph = current.optString("windspeedMiles", "?");
        String windDir = current.optString("winddir16Point", "");
        String precipMm = current.optString("precipMM", "?");
        String minC = today.optString("mintempC", "?");
        String maxC = today.optString("maxtempC", "?");

        return "Weather for " + place + ":\n"
                + "Now: " + desc + ", " + tempC + " C (feels like " + feelsC + " C)\n"
                + "Today: " + minC + "-" + maxC + " C\n"
                + "Humidity: " + humidity + "%\n"
                + "Wind: " + windMph + " mph " + windDir + "\n"
                + "Precipitation: " + precipMm + " mm";
    }

    private String firstValue(JSONArray array) {
        if (array == null || array.length() == 0) {
            return "";
        }
        JSONObject first = array.optJSONObject(0);
        return first != null ? first.optString("value", "") : "";
    }
}
