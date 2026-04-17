package edu.northeastern.agent_a.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import edu.northeastern.agent_a.BuildConfig;
import edu.northeastern.agent_a.R;
import edu.northeastern.agent_a.core.agent.Executor;
import edu.northeastern.agent_a.core.agent.Planner;
import edu.northeastern.agent_a.core.agent.Policy;
import edu.northeastern.agent_a.core.memory.Message;
import edu.northeastern.agent_a.core.memory.SessionStore;
import edu.northeastern.agent_a.core.tools.AppCapabilityTool;
import edu.northeastern.agent_a.core.tools.ContactsLookupTool;
import edu.northeastern.agent_a.core.tools.EmailSummaryTool;
import edu.northeastern.agent_a.core.tools.MapsNavigateTool;
import edu.northeastern.agent_a.core.tools.MediaShareTool;
import edu.northeastern.agent_a.core.tools.NewsFeedTool;
import edu.northeastern.agent_a.core.tools.PhoneDialTool;
import edu.northeastern.agent_a.core.tools.Plan;
import edu.northeastern.agent_a.core.tools.SmsComposeTool;
import edu.northeastern.agent_a.core.tools.SpotifyAuthManager;
import edu.northeastern.agent_a.core.tools.SpotifyControlTool;
import edu.northeastern.agent_a.core.tools.ToolRegistry;
import edu.northeastern.agent_a.core.tools.ToolResult;
import edu.northeastern.agent_a.core.tools.WeatherTool;
import edu.northeastern.agent_a.llm.ConfigurableLLMClient;
import edu.northeastern.agent_a.llm.GeminiLLMClient;
import edu.northeastern.agent_a.llm.MiniMaxLLMClient;
import edu.northeastern.agent_a.llm.MockLLMClient;

public class AgentChatActivity extends AppCompatActivity {

    private static final String TAG = "AgentChatActivity";

    // ── LLM switcher labels (order must match buildLlmClient switch) ──────
    private static final String LLM_MOCK   = "MockLLM";
    private static final String LLM_GEMINI = "Gemini";
    private static final String LLM_MINIMAX = "MiniMax";
    private static final String LLM_CUSTOM = "Custom LLM";

    private static final String PREFS_LLM = "custom_llm";
    private static final String PREF_CUSTOM_NAME = "name";
    private static final String PREF_CUSTOM_BASE_URL = "base_url";
    private static final String PREF_CUSTOM_MODEL = "model";
    private static final String PREF_CUSTOM_API_KEY = "api_key";

    // ── Views ─────────────────────────────────────────────────────────────
    private RecyclerView rvMessages;
    private EditText etInput;
    private MaterialButton btnSend;
    private TextView tvStatus;
    private ImageButton btnVoice;
    private Spinner spinnerLlm;

    // ── Agent components ──────────────────────────────────────────────────
    private ChatAdapter adapter;
    private SessionStore sessionStore;
    private Planner planner;
    private Executor executor;
    private Policy policy;
    private ToolRegistry registry;

    // ── Background threads ────────────────────────────────────────────────
    private ExecutorService planningExecutor;
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    // ── State ─────────────────────────────────────────────────────────────
    private Plan pendingPlan;
    private String pendingRetryText;
    private boolean spinnerReady = false; // suppresses the initial onItemSelected callback

    // ── Permission launcher ───────────────────────────────────────────────
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

    private final ActivityResultLauncher<String[]> mediaPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    grants -> {
                        boolean allGranted = !grants.isEmpty();
                        for (Boolean granted : grants.values()) {
                            if (!Boolean.TRUE.equals(granted)) {
                                allGranted = false;
                                break;
                            }
                        }

                        if (allGranted && pendingRetryText != null) {
                            String retryText = pendingRetryText;
                            pendingRetryText = null;
                            addAssistant("Media permission granted. Retrying...");
                            handleUserInput(retryText);
                        } else if (!allGranted) {
                            addAssistant("Media permission denied. I can't search recent photos/videos without it.");
                            setInputEnabled(true);
                        }
                    });

    // ═════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_agent_chat);

        initViews();
        initAgent();

        // Fix top overlap: apply status bar height as top padding on the top bar
        View topBar = findViewById(R.id.topBar);
        ViewCompat.setOnApplyWindowInsetsListener(topBar, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), systemBars.top, v.getPaddingRight(), v.getPaddingBottom());
            return insets; // pass through so inputArea also receives insets
        });

        View inputArea = findViewById(R.id.inputArea);
        ViewCompat.setOnApplyWindowInsetsListener(inputArea, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets imeInsets  = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottomPadding = Math.max(systemBars.bottom, imeInsets.bottom);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottomPadding);
            return WindowInsetsCompat.CONSUMED;
        });

        addAssistant(getString(R.string.welcome_message));
        handleSpotifyRedirect(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSpotifyRedirect(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (planningExecutor != null) planningExecutor.shutdownNow();
        bgExecutor.shutdownNow();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Init
    // ═════════════════════════════════════════════════════════════════════

    private void initViews() {
        rvMessages  = findViewById(R.id.rvMessages);
        etInput     = findViewById(R.id.etInput);
        btnSend     = findViewById(R.id.btnSend);
        tvStatus    = findViewById(R.id.tvStatus);
        btnVoice    = findViewById(R.id.btnVoice);
        spinnerLlm  = findViewById(R.id.spinnerLlm);

        sessionStore    = new SessionStore();
        adapter         = new ChatAdapter(sessionStore.getMessages());
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

        setupLlmSpinner();
    }

    private void initAgent() {
        registry = new ToolRegistry();
        registry.register(new PhoneDialTool());
        registry.register(new SmsComposeTool());
        registry.register(new MapsNavigateTool());
        registry.register(new ContactsLookupTool());
        registry.register(new EmailSummaryTool());
        registry.register(new NewsFeedTool());
        registry.register(new SpotifyControlTool());
        registry.register(new MediaShareTool());
        registry.register(new WeatherTool());
        registry.register(new AppCapabilityTool());

        // Default to MockLLM; the Spinner will trigger a switch if the user selects another
        planner  = new Planner(new MockLLMClient(), registry);
        executor = new Executor(registry);
        policy   = new Policy();
    }

    private void handleSpotifyRedirect(Intent intent) {
        Uri data = intent != null ? intent.getData() : null;
        SpotifyAuthManager authManager = new SpotifyAuthManager();
        if (!authManager.canHandleRedirect(data)) {
            return;
        }

        setInputEnabled(false);
        setStatus("Connecting Spotify...");
        bgExecutor.execute(() -> {
            try {
                String authMessage = authManager.handleRedirect(this, data);
                Map<String, String> pendingArgs = authManager.consumePendingCommand(this);
                if (pendingArgs.isEmpty()) {
                    runOnUiThread(() -> {
                        addAssistant(authMessage);
                        setStatus(getString(R.string.status_ready));
                        setInputEnabled(true);
                    });
                    return;
                }

                ToolResult retryResult = new SpotifyControlTool().execute(this, pendingArgs);
                runOnUiThread(() -> {
                    addAssistant(authMessage);
                    addAssistant(retryResult.getStatus() == ToolResult.Status.SUCCESS
                            ? "✅ Spotify: " + retryResult.displayText()
                            : "❌ Spotify: " + retryResult.displayText());
                    setStatus(getString(R.string.status_ready));
                    setInputEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    addAssistant("Spotify authorization failed: " + e.getMessage());
                    setStatus(getString(R.string.status_ready));
                    setInputEnabled(true);
                });
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    // LLM Switcher (Improvement #2)
    // ═════════════════════════════════════════════════════════════════════

    private void setupLlmSpinner() {
        String[] options = {LLM_MOCK, LLM_GEMINI, LLM_MINIMAX, LLM_CUSTOM};
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, options);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLlm.setAdapter(spinnerAdapter);
        spinnerLlm.setSelection(0); // default: MockLLM

        spinnerLlm.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!spinnerReady) {
                    // Skip the initial callback fired when the Spinner is first attached
                    spinnerReady = true;
                    return;
                }
                String selected = options[position];
                switchLlm(selected);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /**
     * Hot-swaps the LLM backend inside Planner without restarting the Activity.
     * Called on the main thread from the Spinner callback.
     */
    private void switchLlm(String label) {
        switch (label) {
            case LLM_GEMINI:
                String geminiKey = BuildConfig.GEMINI_API_KEY.trim();
                if (geminiKey.isEmpty()) {
                    addAssistant("⚠️ Gemini API key not found. Add GEMINI_API_KEY to local.properties.");
                    spinnerLlm.setSelection(0); // revert to Mock
                    return;
                }
                planner.setLlmClient(new GeminiLLMClient(geminiKey));
                break;

            case LLM_MINIMAX:
                String miniMaxKey = BuildConfig.MINIMAX_API_KEY.trim();
                if (miniMaxKey.isEmpty()) {
                    addAssistant("⚠️ MiniMax API key not found. Add MINIMAX_API_KEY to local.properties.");
                    spinnerLlm.setSelection(0); // revert to Mock
                    return;
                }
                planner.setLlmClient(new MiniMaxLLMClient(registry));
                break;

            case LLM_CUSTOM:
                CustomLlmConfig config = loadCustomLlmConfig();
                if (!config.isComplete()) {
                    showCustomLlmDialog();
                    return;
                }
                planner.setLlmClient(new ConfigurableLLMClient(
                        registry, config.apiKey, config.baseUrl, config.model));
                label = "Custom LLM (" + config.name + ")";
                break;

            case LLM_MOCK:
            default:
                planner.setLlmClient(new MockLLMClient());
                break;
        }

        addAssistant("🔄 Switched to " + label);
        setStatus("Planner: " + label + " ready");
        Log.i(TAG, "LLM switched to: " + label);
    }

    private void showCustomLlmDialog() {
        CustomLlmConfig existing = loadCustomLlmConfig();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, 8, padding, 0);

        EditText name = makeConfigEditText("Display name", existing.name);
        EditText baseUrl = makeConfigEditText(
                "Base URL, e.g. https://api.openai.com/v1/chat/completions",
                existing.baseUrl);
        EditText model = makeConfigEditText("Model, e.g. gpt-4o-mini", existing.model);
        EditText apiKey = makeConfigEditText("API key", existing.apiKey);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        layout.addView(name);
        layout.addView(baseUrl);
        layout.addView(model);
        layout.addView(apiKey);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Add Custom LLM")
                .setMessage("Use any OpenAI-compatible chat completions endpoint.")
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    CustomLlmConfig config = new CustomLlmConfig(
                            emptyToDefault(name.getText().toString().trim(), "Custom"),
                            baseUrl.getText().toString().trim(),
                            model.getText().toString().trim(),
                            apiKey.getText().toString().trim());
                    if (!config.isComplete()) {
                        addAssistant("Custom LLM config is incomplete. Please provide base URL, model, and API key.");
                        spinnerLlm.setSelection(0);
                        return;
                    }
                    saveCustomLlmConfig(config);
                    planner.setLlmClient(new ConfigurableLLMClient(
                            registry, config.apiKey, config.baseUrl, config.model));
                    addAssistant("Switched to Custom LLM (" + config.name + ")");
                    setStatus("Planner: Custom LLM ready");
                })
                .setNegativeButton(R.string.btn_cancel, (dialog, which) -> spinnerLlm.setSelection(0))
                .show();
    }

    private EditText makeConfigEditText(String hint, String value) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setText(value);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return editText;
    }

    private String emptyToDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private CustomLlmConfig loadCustomLlmConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_LLM, MODE_PRIVATE);
        return new CustomLlmConfig(
                prefs.getString(PREF_CUSTOM_NAME, "Custom"),
                prefs.getString(PREF_CUSTOM_BASE_URL, ""),
                prefs.getString(PREF_CUSTOM_MODEL, ""),
                prefs.getString(PREF_CUSTOM_API_KEY, ""));
    }

    private void saveCustomLlmConfig(CustomLlmConfig config) {
        getSharedPreferences(PREFS_LLM, MODE_PRIVATE)
                .edit()
                .putString(PREF_CUSTOM_NAME, config.name)
                .putString(PREF_CUSTOM_BASE_URL, config.baseUrl)
                .putString(PREF_CUSTOM_MODEL, config.model)
                .putString(PREF_CUSTOM_API_KEY, config.apiKey)
                .apply();
    }

    // ═════════════════════════════════════════════════════════════════════
    // User input handling
    // ═════════════════════════════════════════════════════════════════════

    private void onSendClicked() {
        String text = etInput.getText().toString().trim();
        if (text.isEmpty()) return;
        etInput.setText("");
        handleUserInput(text);
    }

    private void handleUserInput(String text) {
        addUser(text);
        setInputEnabled(false);
        String currentLlm = (String) spinnerLlm.getSelectedItem();
        setStatus("Planner: " + currentLlm + " planning...");

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

        setStatus("Planned " + plan.getActions().size() + " action(s). Awaiting confirmation…");

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

    // ═════════════════════════════════════════════════════════════════════
    // Plan execution with step-by-step feedback (Improvement #1)
    // ═════════════════════════════════════════════════════════════════════

    private void executePlan(Plan plan, String originalUserText) {
        int totalSteps = plan.getActions().size();
        setStatus("Executing… Step 1/" + totalSteps);
        setInputEnabled(false);

        bgExecutor.execute(() -> {
            List<ToolResult> results;
            try {
                results = executor.execute(this, plan);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    addAssistant("❌ Error: " + e.getMessage());
                    setStatus(getString(R.string.status_ready));
                    pendingPlan = null;
                    setInputEnabled(true);
                });
                return;
            }

            // Deliver each step result individually back on the main thread,
            // updating the status bar as we go so the user sees live progress.
            for (int i = 0; i < results.size(); i++) {
                final int stepIndex  = i;
                final ToolResult r   = results.get(i);
                final String stepLabel = "Step " + (stepIndex + 1) + "/" + totalSteps + ": ";

                runOnUiThread(() -> {
                    // Update progress in the status bar for every completed step
                    int nextStep = stepIndex + 2; // "next" step number (1-indexed)
                    if (nextStep <= totalSteps) {
                        setStatus("Executing… Step " + nextStep + "/" + totalSteps);
                    } else {
                        setStatus(getString(R.string.status_ready));
                    }

                    switch (r.getStatus()) {
                        case SUCCESS:
                            addAssistant("✅ " + stepLabel + r.displayText());
                            break;

                        case NEED_PERMISSION:
                            addAssistant("🔑 " + stepLabel + r.displayText());
                            pendingRetryText = originalUserText;
                            requestPermissionForTool(r);
                            setStatus(getString(R.string.status_ready));
                            pendingPlan = null;
                            setInputEnabled(true);
                            // Return early — permission flow will retry the whole input
                            return;

                        case FAIL:
                        default:
                            addAssistant("❌ " + stepLabel + "Failed — " + r.displayText());
                            break;
                    }

                    // After the last step, re-enable input
                    if (stepIndex == results.size() - 1) {
                        pendingPlan = null;
                        setInputEnabled(true);
                    }
                });
            }
        });
    }

    private void requestPermissionForTool(ToolResult result) {
        Map<String, String> audit = result.getAudit();
        String toolName = audit != null ? audit.getOrDefault("tool", "") : "";

        if ("contacts.lookup".equals(toolName)) {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS);
            return;
        }

        if ("media.share".equals(toolName)) {
            mediaPermissionLauncher.launch(mediaPermissions());
            return;
        }

        addAssistant("Permission is required, but no permission handler is registered for " + toolName + ".");
    }

    private String[] mediaPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
            };
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    // ═════════════════════════════════════════════════════════════════════
    // UI helpers
    // ═════════════════════════════════════════════════════════════════════

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

    private void setInputEnabled(boolean enabled) {
        etInput.setEnabled(enabled);
        btnSend.setEnabled(enabled);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Voice recognition
    // ═════════════════════════════════════════════════════════════════════

    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
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
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
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

    private void stopVoiceAction() {
        isListening = false;
        if (speechRecognizer != null) speechRecognizer.stopListening();
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
                    ArrayList<String> matches =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        handleUserInput(matches.get(0));
                        etInput.setText("");
                    }
                    stopVoiceAction();
                }

                @Override
                public void onError(int error) {
                    stopVoiceAction();
                    if (error == SpeechRecognizer.ERROR_CLIENT) {
                        setStatus("Voice engine is busy. Tap again.");
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
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        }

        isListening = true;
        btnVoice.setImageResource(android.R.drawable.ic_media_pause);
        setStatus("Listening...");
        speechRecognizer.startListening(speechRecognizerIntent);
    }

    private static class CustomLlmConfig {
        final String name;
        final String baseUrl;
        final String model;
        final String apiKey;

        CustomLlmConfig(String name, String baseUrl, String model, String apiKey) {
            this.name = name != null ? name.trim() : "Custom";
            this.baseUrl = baseUrl != null ? baseUrl.trim() : "";
            this.model = model != null ? model.trim() : "";
            this.apiKey = apiKey != null ? apiKey.trim() : "";
        }

        boolean isComplete() {
            return !baseUrl.isEmpty() && !model.isEmpty() && !apiKey.isEmpty();
        }
    }
}
