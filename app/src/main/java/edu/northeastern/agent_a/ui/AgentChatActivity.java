package edu.northeastern.agent_a.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import edu.northeastern.agent_a.R;
import edu.northeastern.agent_a.core.agent.Executor;
import edu.northeastern.agent_a.core.agent.Planner;
import edu.northeastern.agent_a.core.agent.Policy;
import edu.northeastern.agent_a.core.memory.Message;
import edu.northeastern.agent_a.core.memory.SessionStore;
import edu.northeastern.agent_a.core.tools.ContactsLookupTool;
import edu.northeastern.agent_a.core.tools.EmailSummaryTool;
import edu.northeastern.agent_a.core.tools.NewsFeedTool;
import edu.northeastern.agent_a.core.tools.MapsNavigateTool;
import edu.northeastern.agent_a.core.tools.PhoneDialTool;
import edu.northeastern.agent_a.core.tools.Plan;
import edu.northeastern.agent_a.core.tools.SmsComposeTool;
import edu.northeastern.agent_a.core.tools.ToolRegistry;
import edu.northeastern.agent_a.core.tools.ToolResult;
import edu.northeastern.agent_a.llm.MockLLMClient;

public class AgentChatActivity extends AppCompatActivity {

    private RecyclerView rvMessages;
    private EditText etInput;
    private MaterialButton btnSend;
    private TextView tvStatus;

    private ChatAdapter adapter;
    private SessionStore sessionStore;
    private Planner planner;
    private Executor executor;
    private Policy policy;

    private Plan pendingPlan;
    private String pendingRetryText;

    // Single background thread for tool execution (network I/O must not run on UI thread)
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> contactsPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted && pendingRetryText != null) {
                            String retryText = pendingRetryText;
                            pendingRetryText = null;
                            addAssistant("Permission granted. Retrying...");
                            handleUserInput(retryText);
                        } else if (!granted) {
                            addAssistant("Permission denied. Please provide the phone number directly.");
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_agent_chat);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        initAgent();

        addAssistant(getString(R.string.welcome_message));
    }

    private void initViews() {
        rvMessages = findViewById(R.id.rvMessages);
        etInput = findViewById(R.id.etInput);
        btnSend = findViewById(R.id.btnSend);
        tvStatus = findViewById(R.id.tvStatus);

        sessionStore = new SessionStore();
        adapter = new ChatAdapter(sessionStore.getMessages());

        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rvMessages.setLayoutManager(lm);
        rvMessages.setAdapter(adapter);

        btnSend.setOnClickListener(v -> onSendClicked());

        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                onSendClicked();
                return true;
            }
            return false;
        });
    }

    private void initAgent() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new PhoneDialTool());
        registry.register(new SmsComposeTool());
        registry.register(new MapsNavigateTool());
        registry.register(new ContactsLookupTool());
        registry.register(new EmailSummaryTool());
        registry.register(new NewsFeedTool());

        planner = new Planner(new MockLLMClient(), registry);
        executor = new Executor(registry);
        policy = new Policy();
    }

    private void onSendClicked() {
        String text = etInput.getText().toString().trim();
        if (text.isEmpty()) return;
        etInput.setText("");
        handleUserInput(text);
    }

    private void handleUserInput(String text) {
        addUser(text);
        setStatus(getString(R.string.status_planning));

        Plan plan = planner.createPlan(text, sessionStore);

        if (!plan.hasActions()) {
            addAssistant(plan.getAssistantMessage());
            setStatus(getString(R.string.status_ready));
            return;
        }

        setStatus("Planned " + plan.getActions().size() + " action(s). Awaiting confirmation…");

        if (policy.requiresPreview(plan)) {
            pendingPlan = plan;
            ActionPreviewHelper.show(this, plan, new ActionPreviewHelper.Callback() {
                @Override
                public void onConfirm() {
                    addAssistant(plan.getAssistantMessage());
                    executePlan(plan, text);
                }

                @Override
                public void onCancel() {
                    addAssistant(getString(R.string.cancelled));
                    setStatus(getString(R.string.status_ready));
                    pendingPlan = null;
                }
            });
        } else {
            addAssistant(plan.getAssistantMessage());
            executePlan(plan, text);
        }
    }

    private void executePlan(Plan plan, String originalUserText) {
        setStatus(getString(R.string.status_executing));
        btnSend.setEnabled(false);  // prevent double-submit while tools are running

        bgExecutor.execute(() -> {
            List<ToolResult> results;
            try {
                results = executor.execute(this, plan);
            } catch (Exception e) {
                // Unexpected error from executor — restore UI and bail out
                runOnUiThread(() -> {
                    addAssistant("Error: " + e.getMessage());
                    setStatus(getString(R.string.status_ready));
                    pendingPlan = null;
                    btnSend.setEnabled(true);
                });
                return;
            }

            runOnUiThread(() -> {
                for (int i = 0; i < results.size(); i++) {
                    ToolResult r = results.get(i);
                    String stepLabel = "Step " + (i + 1) + "/" + plan.getActions().size() + ": ";

                    switch (r.getStatus()) {
                        case SUCCESS:
                            addAssistant(stepLabel + r.displayText());
                            break;
                        case NEED_PERMISSION:
                            addAssistant(stepLabel + r.displayText());
                            pendingRetryText = originalUserText;
                            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS);
                            setStatus(getString(R.string.status_ready));
                            pendingPlan = null;
                            btnSend.setEnabled(true);
                            return;
                        case FAIL:
                        default:
                            addAssistant(stepLabel + "Failed — " + r.displayText());
                            break;
                    }
                }

                setStatus(getString(R.string.status_ready));
                pendingPlan = null;
                btnSend.setEnabled(true);  // re-enable input after all tools finish
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor.shutdownNow();
    }

    private void addUser(String text) {
        sessionStore.addMessage(new Message(Message.Role.USER, text));
        notifyAndScroll();
    }

    private void addAssistant(String text) {
        sessionStore.addMessage(new Message(Message.Role.ASSISTANT, text));
        notifyAndScroll();
    }

    private void notifyAndScroll() {
        adapter.notifyItemInserted(sessionStore.getMessages().size() - 1);
        rvMessages.scrollToPosition(sessionStore.getMessages().size() - 1);
    }

    private void setStatus(String text) {
        tvStatus.setText(text);
    }
}
