package edu.northeastern.agent_a.core.tools;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
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
            JSONObject root = fetchWttrWeather(location);
            return ToolResult.success(formatWeather(root, location));
        } catch (Exception wttrError) {
            try {
                return ToolResult.success(fetchOpenMeteoWeather(location));
            } catch (Exception fallbackError) {
                if (isUnknownHost(wttrError) || isUnknownHost(fallbackError)) {
                    return ToolResult.fail("Weather lookup failed: this device cannot resolve weather service hostnames. "
                            + "Check the emulator/device internet connection, DNS/Private DNS, VPN/proxy settings, then try again.");
                }
                return ToolResult.fail("Weather lookup failed: "
                        + wttrError.getMessage()
                        + "; fallback also failed: "
                        + fallbackError.getMessage());
            }
        }
    }

    private JSONObject fetchWttrWeather(String location) throws Exception {
        String encodedLocation = location.isEmpty() ? "" : Uri.encode(location);
        URL url = new URL("https://wttr.in/" + encodedLocation + "?format=j1");

        return new JSONObject(httpGet(url));
    }

    private String fetchOpenMeteoWeather(String location) throws Exception {
        String resolvedLocation = location;
        if (resolvedLocation.isEmpty()) {
            resolvedLocation = approximateLocationName();
        }
        if (resolvedLocation.isEmpty()) {
            throw new IllegalStateException("please provide a city or place name");
        }

        JSONObject geoRoot = new JSONObject(httpGet(new URL(
                "https://geocoding-api.open-meteo.com/v1/search?name="
                        + Uri.encode(resolvedLocation)
                        + "&count=1&language=en&format=json")));
        JSONArray results = geoRoot.optJSONArray("results");
        if (results == null || results.length() == 0) {
            throw new IllegalStateException("could not find location \"" + resolvedLocation + "\"");
        }

        JSONObject place = results.getJSONObject(0);
        double latitude = place.getDouble("latitude");
        double longitude = place.getDouble("longitude");
        String placeName = formatPlaceName(place);

        String forecastUrl = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + latitude
                + "&longitude=" + longitude
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,wind_speed_10m,wind_direction_10m,weather_code"
                + "&daily=temperature_2m_max,temperature_2m_min"
                + "&temperature_unit=celsius"
                + "&wind_speed_unit=mph"
                + "&timezone=auto"
                + "&forecast_days=1";
        JSONObject forecast = new JSONObject(httpGet(new URL(forecastUrl)));
        JSONObject current = forecast.getJSONObject("current");
        JSONObject daily = forecast.getJSONObject("daily");

        return "Weather for " + placeName + ":\n"
                + "Now: " + weatherCodeDescription(current.optInt("weather_code", -1))
                + ", " + formatNumber(current.optDouble("temperature_2m")) + " C"
                + " (feels like " + formatNumber(current.optDouble("apparent_temperature")) + " C)\n"
                + "Today: " + formatNumber(daily.getJSONArray("temperature_2m_min").optDouble(0))
                + "-" + formatNumber(daily.getJSONArray("temperature_2m_max").optDouble(0)) + " C\n"
                + "Humidity: " + current.optInt("relative_humidity_2m", -1) + "%\n"
                + "Wind: " + formatNumber(current.optDouble("wind_speed_10m"))
                + " mph " + compassDirection(current.optDouble("wind_direction_10m")) + "\n"
                + "Precipitation: " + formatNumber(current.optDouble("precipitation")) + " mm";
    }

    private String httpGet(URL url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "AgentA/1.0 weather client");
        conn.setRequestProperty("Accept", "application/json");

        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                String errorBody = "";
                if (conn.getErrorStream() != null) {
                    errorBody = readStream(conn.getErrorStream());
                }
                throw new IllegalStateException("HTTP " + conn.getResponseCode()
                        + (errorBody.isEmpty() ? "" : " " + errorBody));
            }

            return readStream(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private String readStream(java.io.InputStream stream) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private String approximateLocationName() throws Exception {
        JSONObject root = new JSONObject(httpGet(new URL("https://ipapi.co/json/")));
        String city = root.optString("city", "").trim();
        String region = root.optString("region", "").trim();
        String country = root.optString("country_name", "").trim();
        StringBuilder sb = new StringBuilder();
        if (!city.isEmpty()) sb.append(city);
        if (!region.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(region);
        }
        if (!country.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(country);
        }
        return sb.toString();
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

    private boolean isUnknownHost(Throwable throwable) {
        while (throwable != null) {
            if (throwable instanceof UnknownHostException) {
                return true;
            }
            throwable = throwable.getCause();
        }
        return false;
    }

    private String formatPlaceName(JSONObject place) {
        String name = place.optString("name", "");
        String admin1 = place.optString("admin1", "");
        String country = place.optString("country", "");
        StringBuilder sb = new StringBuilder();
        if (!name.isEmpty()) sb.append(name);
        if (!admin1.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(admin1);
        }
        if (!country.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(country);
        }
        return sb.length() > 0 ? sb.toString() : "requested location";
    }

    private String formatNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "?";
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private String compassDirection(double degrees) {
        if (Double.isNaN(degrees)) {
            return "";
        }
        String[] directions = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int index = (int) Math.round(degrees / 45.0) % directions.length;
        return directions[index];
    }

    private String weatherCodeDescription(int code) {
        switch (code) {
            case 0: return "Clear sky";
            case 1: return "Mainly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45:
            case 48: return "Fog";
            case 51:
            case 53:
            case 55: return "Drizzle";
            case 56:
            case 57: return "Freezing drizzle";
            case 61:
            case 63:
            case 65: return "Rain";
            case 66:
            case 67: return "Freezing rain";
            case 71:
            case 73:
            case 75: return "Snow";
            case 77: return "Snow grains";
            case 80:
            case 81:
            case 82: return "Rain showers";
            case 85:
            case 86: return "Snow showers";
            case 95: return "Thunderstorm";
            case 96:
            case 99: return "Thunderstorm with hail";
            default: return "Unknown conditions";
        }
    }
}
