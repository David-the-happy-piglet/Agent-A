package edu.northeastern.agent_a.core.tools;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;

import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class ContactsLookupTool implements Tool {

    @Override
    public String name() { return "contacts.lookup"; }

    @Override
    public RiskLevel defaultRiskLevel() { return RiskLevel.MEDIUM; }

    @Override
    public ToolSpec spec() {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("name", "String — contact name to search for");
        return new ToolSpec("contacts.lookup",
                "Searches contacts by name and returns their phone number. Requires READ_CONTACTS permission; "
                        + "if denied, ask user to provide the phone number directly.",
                params, RiskLevel.MEDIUM);
    }

    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        String name = args.get("name");
        if (name == null || name.trim().isEmpty()) {
            return ToolResult.fail("No contact name provided.");
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.needPermission(
                    "READ_CONTACTS permission is required to look up \""
                            + name + "\". Please grant the permission and try again, "
                            + "or provide the phone number directly.");
        }

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                    },
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + name.trim() + "%"},
                    null
            );

            if (cursor != null && cursor.moveToFirst()) {
                String foundName = cursor.getString(0);
                String phone = cursor.getString(1);
                Map<String, String> data = new HashMap<>();
                data.put("name", foundName);
                data.put("phone", phone);
                return ToolResult.success(
                        "Found contact: " + foundName + " (" + phone + ")", data);
            }

            return ToolResult.fail("No contact found matching \"" + name
                    + "\". Please provide the phone number directly.");
        } catch (Exception e) {
            return ToolResult.fail("Contact lookup failed: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
    }
}
