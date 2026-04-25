package edu.northeastern.agent_a.core.tools;

import android.app.Activity;
import android.content.Context;
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
import com.google.api.services.gmail.model.MessagePartHeader;

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
                "Fetches and summarizes the last 10 emails from your Gmail inbox.",
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
            List<String> summaries = fetchEmailSummaries(context, account);
            if (summaries.isEmpty()) {
                return ToolResult.success("No emails found in your inbox.");
            }

            StringBuilder result = new StringBuilder("Here are your last 10 emails:\n\n");
            for (int i = 0; i < summaries.size(); i++) {
                result.append(i + 1).append(". ").append(summaries.get(i)).append("\n\n");
            }
            return ToolResult.success(result.toString());

        } catch (IOException e) {
            Log.e(TAG, "Gmail API error", e);
            if (e.getMessage() != null && e.getMessage().contains("403")) {
                 return ToolResult.fail("Gmail API Access Denied. Please ensure the Gmail API is enabled in the Google Cloud Console.");
            }
            return ToolResult.fail("Failed to fetch emails: " + e.getMessage());
        }
    }

    private void requestLogin(Context context) {
        if (!(context instanceof Activity)) return;
        Activity activity = (Activity) context;

        GoogleSignInOptions.Builder builder = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(GMAIL_SCOPE));

        if (BuildConfig.GOOGLE_CLIENT_ID != null && !BuildConfig.GOOGLE_CLIENT_ID.isEmpty()) {
            builder.requestIdToken(BuildConfig.GOOGLE_CLIENT_ID);
        }

        GoogleSignInOptions gso = builder.build();
        GoogleSignInClient client = GoogleSignIn.getClient(activity, gso);
        
        client.signOut().addOnCompleteListener(task -> {
            activity.startActivityForResult(client.getSignInIntent(), RC_SIGN_IN);
        });
    }

    private List<String> fetchEmailSummaries(Context context, GoogleSignInAccount account) throws IOException {
        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(GMAIL_SCOPE));
        credential.setSelectedAccount(account.getAccount());

        Gmail service = new Gmail.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential)
                .setApplicationName("Agent-A")
                .build();

        // Fetch last 10 messages from Inbox
        ListMessagesResponse response = service.users().messages().list("me")
                .setMaxResults(10L)
                .setQ("label:INBOX")
                .execute();

        List<com.google.api.services.gmail.model.Message> messages = response.getMessages();
        List<String> summaries = new ArrayList<>();

        if (messages != null) {
            for (com.google.api.services.gmail.model.Message msg : messages) {
                com.google.api.services.gmail.model.Message fullMsg = 
                        service.users().messages().get("me", msg.getId()).execute();
                
                String subject = "No Subject";
                String from = "Unknown Sender";
                
                List<MessagePartHeader> headers = fullMsg.getPayload().getHeaders();
                for (MessagePartHeader header : headers) {
                    if ("Subject".equalsIgnoreCase(header.getName())) {
                        subject = header.getValue();
                    } else if ("From".equalsIgnoreCase(header.getName())) {
                        from = header.getValue();
                    }
                }

                String snippet = fullMsg.getSnippet();
                summaries.add("**From:** " + from + "\n**Subject:** " + subject + "\n**Snippet:** " + snippet);
            }
        }
        return summaries;
    }
}
