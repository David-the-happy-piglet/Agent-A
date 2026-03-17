package edu.northeastern.agent_a.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import edu.northeastern.agent_a.BuildConfig;
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
import edu.northeastern.agent_a.llm.MiniMaxLLMClient;
import edu.northeastern.agent_a.llm.GeminiLLMClient;
import edu.northeastern.agent_a.llm.MockLLMClient;


public class AgentChatActivity extends AppCompatActivity {

    private static final String TAG = "AgentChatActivity";

    private RecyclerView rvMessages;
    private EditText etInput;
    private MaterialButton btnSend;
    private TextView tvStatus;

    private ImageButton btnVoice;


    private ChatAdapter adapter;
    private SessionStore sessionStore;
    private Planner planner;
    private Executor executor;
    private Policy policy;
    private ExecutorService planningExecutor;
    private String plannerModeLabel = "Mock";

    private Plan pendingPlan;
    private String pendingRetryText;

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
                            setInputEnabled(true);
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_agent_chat);
        initViews();
        initAgent();
        View inputArea = findViewById(R.id.inputArea);
        ViewCompat.setOnApplyWindowInsetsListener(inputArea, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime());

            int bottomPadding = Math.max(systemBars.bottom, imeInsets.bottom);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottomPadding);

            return WindowInsetsCompat.CONSUMED;
        });

        addAssistant(getString(R.string.welcome_message));
    }

    private void initViews() {
        rvMessages = findViewById(R.id.rvMessages);
        etInput = findViewById(R.id.etInput);
        btnSend = findViewById(R.id.btnSend);
        tvStatus = findViewById(R.id.tvStatus);
        btnVoice = findViewById(R.id.btnVoice);
        sessionStore = new SessionStore();
        adapter = new ChatAdapter(sessionStore.getMessages());
        planningExecutor = Executors.newSingleThreadExecutor();

        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rvMessages.setLayoutManager(lm);
        rvMessages.setAdapter(adapter);

        btnSend.setOnClickListener(v -> onSendClicked());
        btnVoice.setOnClickListener(v -> handleVoiceButtonClick());
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

        if (hasMiniMaxKey()) {
            planner = new Planner(new MiniMaxLLMClient(registry), registry);
            plannerModeLabel = "MiniMax";
            Log.i(TAG, "Planner mode: MiniMaxLLMClient");
            addAssistant("Planner mode: MiniMax");
            setStatus("Planner: MiniMax ready");
        } else {
            planner = new Planner(new MockLLMClient(), registry);
            plannerModeLabel = "Mock";
            Log.i(TAG, "Planner mode: MockLLMClient");
            addAssistant("Planner mode: Mock");
            setStatus("Planner: Mock ready");
        }
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
        setInputEnabled(false);
        setStatus("Planner: " + plannerModeLabel + " planning...");

        planningExecutor.execute(() -> {
            try {
                Plan plan = planner.createPlan(text, sessionStore);
                runOnUiThread(() -> presentPlan(plan, text));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    addAssistant("Planning failed: " + e.getMessage());
                    setStatus(getString(R.string.status_ready));
                    setInputEnabled(true);
                });
            }
        });
    }

    private void presentPlan(Plan plan, String originalUserText) {
        if (!plan.hasActions()) {
            addAssistant(plan.getAssistantMessage());
            setStatus(getString(R.string.status_ready));
            setInputEnabled(true);
            return;
        }

        setStatus("Planned " + plan.getActions().size()
                + " action(s). Awaiting confirmation\u2026");

        if (policy.requiresPreview(plan)) {
            pendingPlan = plan;
            ActionPreviewHelper.show(this, plan, new ActionPreviewHelper.Callback() {
                @Override
                public void onConfirm() {
                    addAssistant(plan.getAssistantMessage());
                    executePlan(plan, originalUserText);
                }

                @Override
                public void onCancel() {
                    addAssistant(getString(R.string.cancelled));
                    setStatus(getString(R.string.status_ready));
                    setInputEnabled(true);
                    pendingPlan = null;
                }
            });
        } else {
            addAssistant(plan.getAssistantMessage());
            executePlan(plan, originalUserText);
        }
    }

    private void executePlan(Plan plan, String originalUserText) {
        setStatus("Executing actions...");
        setInputEnabled(false);

        bgExecutor.execute(() -> {
            List<ToolResult> results;
            try {
                results = executor.execute(this, plan);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    addAssistant("Error: " + e.getMessage());
                    setStatus(getString(R.string.status_ready));
                    pendingPlan = null;
                    setInputEnabled(true);
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
                            setInputEnabled(true);
                            return;
                        case FAIL:
                        default:
                            addAssistant(stepLabel + "Failed \u2014 " + r.displayText());
                            break;
                    }
                }
                setStatus(getString(R.string.status_ready));
                pendingPlan = null;
                setInputEnabled(true);
            });
        });
    }

    private boolean hasMiniMaxKey() {
        return !BuildConfig.MINIMAX_API_KEY.trim().isEmpty();
    }

    private void setInputEnabled(boolean enabled) {
        etInput.setEnabled(enabled);
        btnSend.setEnabled(enabled);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (planningExecutor != null) {
            planningExecutor.shutdownNow();
        }
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

    // ── Voice recognition ───────────────────────────────────────────

    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;

    private void checkAndRequestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO_PERMISSION);
        } else {
            startVoiceRecognition();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission granted, you may speak", Toast.LENGTH_SHORT).show();
                startVoiceRecognition();
            } else {
                Toast.makeText(this, "Need permission to record audio", Toast.LENGTH_LONG).show();
            }
        }
    }

    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private boolean isListening = false;

    private void handleVoiceButtonClick() {
        if (tvStatus.getText().toString().contains("busy")) {
            isListening = false;
            if (speechRecognizer != null) {
                speechRecognizer.destroy();
                speechRecognizer = null;
            }
        }
        if (!isListening) {
            checkAndRequestAudioPermission();
            isListening = true;
            setStatus("Listening...");
            btnVoice.setImageResource(android.R.drawable.ic_media_pause);
        } else {
            stopVoiceAction();
        }
    }

    private void stopVoiceAction() {
        isListening = false;
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
        btnVoice.setImageResource(android.R.drawable.ic_btn_speak_now);
    }

    private void startVoiceRecognition() {
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
        } else {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String spokenText = matches.get(0);
                        handleUserInput(spokenText);
                        etInput.setText("");
                    }
                    stopVoiceAction();
                }

                @Override
                public void onError(int error) {
                    stopVoiceAction();
                    if (error == SpeechRecognizer.ERROR_CLIENT) {
                        setStatus("Voice engine is busy. tap again.");
                        if (speechRecognizer != null) {
                            speechRecognizer.destroy();
                            speechRecognizer = null;
                        }
                    } else if (error == 7) {
                        setStatus("No match found. Try again.");
                    } else {
                        setStatus("Error: " + error + ". Tap again.");
                    }
                }

                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {
                    btnVoice.setImageResource(android.R.drawable.ic_btn_speak_now);
                    setStatus("Processing...");
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }

        if (speechRecognizerIntent == null) {
            speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        }

        isListening = true;
        btnVoice.setImageResource(android.R.drawable.ic_media_pause);
        setStatus("Listening...");
        speechRecognizer.startListening(speechRecognizerIntent);
    }
}
