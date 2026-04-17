package edu.northeastern.agent_a.core.tools;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import edu.northeastern.agent_a.BuildConfig;

public class EmailSummaryTool implements Tool {

    private static final String TAG = "EmailSummaryTool";
    public static final int RC_SIGN_IN = 9001;
    private static final String GMAIL_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";

    @Override
    public String name() { return "email.summary"; }

    @Override
    public RiskLevel defaultRiskLevel() { return RiskLevel.MEDIUM; }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(name(),
                "Summarizes today's latest 10 Gmail emails using OAuth2. Requires user login.",
                new LinkedHashMap<>(), RiskLevel.MEDIUM);
    }

    @Override
    public ToolResult execute(Context context, Map<String, String> args) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);

        if (account == null || !GoogleSignIn.hasPermissions(account, new Scope(GMAIL_SCOPE))) {
            requestLogin(context);
            return ToolResult.needPermission("Please sign in with your Google account to read emails.");
        }

        try {
            List<String> snippets = fetchEmailSnippets(context, account);
            if (snippets.isEmpty()) {
                return ToolResult.success("No emails found from today.");
            }

            String combinedText = String.join("\n---\n", snippets);
            // We return the raw snippets. The Planner/Executor flow in AgentChatActivity
            // should handle the LLM summarization since this tool returns ToolResult to the Executor.
            return ToolResult.success("Fetched " + snippets.size() + " email snippets:\n" + combinedText);

        } catch (IOException e) {
            Log.e(TAG, "Gmail API error", e);
            return ToolResult.fail("Failed to fetch emails: " + e.getMessage());
        }
    }

    private void requestLogin(Context context) {
        if (!(context instanceof Activity)) return;
        Activity activity = (Activity) context;

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(GMAIL_SCOPE))
                .requestIdToken(BuildConfig.GOOGLE_CLIENT_ID)
                .build();

        GoogleSignInClient client = GoogleSignIn.getClient(activity, gso);
        activity.startActivityForResult(client.getSignInIntent(), RC_SIGN_IN);
    }

    private List<String> fetchEmailSnippets(Context context, GoogleSignInAccount account) throws IOException {
        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(GMAIL_SCOPE));
        credential.setSelectedAccount(account.getAccount());

        Gmail service = new Gmail.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential)
                .setApplicationName("Agent-A")
                .build();

        // Query for today's messages (simplified)
        ListMessagesResponse response = service.users().messages().list("me")
                .setMaxResults(10L)
                .setQ("newer_than:1d")
                .execute();

        List<Message> messages = response.getMessages();
        List<String> snippets = new ArrayList<>();

        if (messages != null) {
            for (Message msg : messages) {
                Message fullMsg = service.users().messages().get("me", msg.getId()).execute();
                snippets.add(fullMsg.getSnippet());
            }
        }
        return snippets;
    }
}
