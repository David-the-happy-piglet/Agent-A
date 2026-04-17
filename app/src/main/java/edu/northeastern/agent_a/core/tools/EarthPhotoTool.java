package edu.northeastern.agent_a.core.tools;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class EarthPhotoTool implements Tool {

    private static final String TAG = "EarthPhotoTool";
    private static final String API_URL = "https://epic.gsfc.nasa.gov/api/natural";
    private static final String IMAGE_BASE_URL = "https://epic.gsfc.nasa.gov/archive/natural/";

    private final OkHttpClient client = new OkHttpClient();

    @Override
    public String name() {
        return "earth.photo";
    }

    @Override
    public RiskLevel defaultRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public ToolSpec spec() {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        return new ToolSpec(
                name(),
                "Fetches the latest photo of Earth taken by NASA's EPIC camera.",
                params,
                defaultRiskLevel()
        );
    }

    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        Request request = new Request.Builder()
                .url(API_URL)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return ToolResult.fail("NASA API error: " + response.code());
            }

            String jsonData = response.body().string();
            JSONArray array = new JSONArray(jsonData);
            if (array.length() == 0) {
                return ToolResult.fail("No photos available currently.");
            }

            JSONObject latest = array.getJSONObject(0);
            String imageId = latest.getString("image");
            String dateStr = latest.getString("date"); // Format: 2023-10-27 00:54:19

            // Parse date for URL: 2023/10/27
            String[] parts = dateStr.split(" ")[0].split("-");
            String year = parts[0];
            String month = parts[1];
            String day = parts[2];

            // Build URL: https://epic.gsfc.nasa.gov/archive/natural/YYYY/MM/DD/png/IMAGE_ID.png
            String photoUrl = String.format("%s%s/%s/%s/png/%s.png", 
                    IMAGE_BASE_URL, year, month, day, imageId);

            Log.d(TAG, "Fetched Earth photo URL: " + photoUrl);

            return ToolResult.success("Showing the latest photo of Earth from NASA's EPIC camera: " + photoUrl);

        } catch (IOException e) {
            Log.e(TAG, "Network error", e);
            return ToolResult.fail("Network error: Could not reach NASA EPIC service.");
        } catch (Exception e) {
            Log.e(TAG, "Parsing error", e);
            return ToolResult.fail("Error processing NASA data.");
        }
    }
}
