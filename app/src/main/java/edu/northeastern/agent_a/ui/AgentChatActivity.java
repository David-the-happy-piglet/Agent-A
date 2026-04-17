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

/**
 * AgentChatActivity is the main screen of the app.
 * It handles user input (text and voice), runs the agent pipeline,
 * and displays results in a chat-style RecyclerView.
 *
 * The main flow for each user message:
 *   handleUserInput() -> Planner.createPlan() -> presentPlan() -> executePlan()
 */
public class AgentChatActivity extends AppCompatActivity {

    private static final String TAG = "AgentChatActivity";

    // ── LLM switcher labels ───────────────────────────────────────────────

    // Order must match the array passed to the Spinner in setupLlmSpinner()
    private static final String LLM_MOCK    = "MockLLM";
    private static final String LLM_GEMINI  = "Gemini";
    private static final String LLM_MINIMAX = "MiniMax";
    private static final String LLM_CUSTOM  = "Custom LLM";

    // SharedPreferences keys for saving custom LLM config across sessions
    private static final String PREFS_LLM          = "custom_llm";
    private static final String PREF_CUSTOM_NAME    = "name";
    private static final String PREF_CUSTOM_BASE_URL = "base_url";
    private static final String PREF_CUSTOM_MODEL   = "model";
    private static final String PREF_CUSTOM_API_KEY = "api_key";

    // ── Views ─────────────────────────────────────────────────────────────

    private RecyclerView rvMessages;
    private EditText etInput;
    private MaterialButton btnSend;
    private TextView tvStatus;
    private ImageButton btnVoice;
    private Spinner spinnerLlm; // (Improvement #2) lets the user pick the LLM backend

    // ── Agent components ──────────────────────────────────────────────────

    private ChatAdapter adapter;
    private SessionStore sessionStore;
    private Planner planner;
    private Executor executor;
    private Policy policy;
    private ToolRegistry registry;

    // ── Background threads ────────────────────────────────────────────────

    private ExecutorService planningExecutor; // background thread for Planner
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor(); // background thread for tool execution

    // ── State ─────────────────────────────────────────────────────────────

    private Plan pendingPlan;
    private String pendingRetryText; // saved input to re-run after permission is granted
    private boolean spinnerReady = false; // suppresses the initial onItemSelected callback

    // ── Permission launchers ──────────────────────────────────────────────

    /**
     * Handles the result of the READ_CONTACTS permission request.
     * If granted and there is a saved input, re-runs it.
     * If denied, tells the user to provide a phone number manually.
     */
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

    /**
     * Handles the result of the media read permission request (images/video).
     * If all permissions are granted and there is a saved input, re-runs it.
     * If denied, informs the user that media search is unavailable.
     */
    private final ActivityResultLauncher<String[]> mediaPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    grants -> {
                        boolean allGranted = !grants.isEmpty();
                        for (Boolean granted : grants.values()) {
                            if (!Boolean.TRUE.equals(granted)) { allGranted = false; break; }
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

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_agent_chat);

        initViews();
        initAgent();

        // Apply status bar height as top padding so the top bar does not overlap system UI
        View topBar = findViewById(R.id.topBar);
        ViewCompat.setOnApplyWindowInsetsListener(topBar, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), systemBars.top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        // Apply keyboard height as bottom padding so the input area stays above the keyboard
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

    /**
     * Called when the app is reopened via a deep link (e.g. Spotify OAuth redirect).
     * Passes the new intent to handleSpotifyRedirect() for processing.
     *
     * @param intent the new Intent containing the redirect URI data
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSpotifyRedirect(intent);
    }

    /** Shuts down background thread pools to avoid resource leaks. */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (planningExecutor != null) planningExecutor.shutdownNow();
        bgExecutor.shutdownNow();
    }

    // ── Init ──────────────────────────────────────────────────────────────

    /** Binds all views, sets up click listeners, the RecyclerView layout, and the LLM Spinner. */
    private void initViews() {
        rvMessages  = findViewById(R.id.rvMessages);
        etInput     = findViewById(R.id.etInput);
        btnSend     = findViewById(R.id.btnSend);
        tvStatus    = findViewById(R.id.tvStatus);
        btnVoice    = findViewById(R.id.btnVoice);
        spinnerLlm  = findViewById(R.id.spinnerLlm);

        sessionStore     = new SessionStore();
        adapter          = new ChatAdapter(sessionStore.getMessages());
        planningExecutor = Executors.newSingleThreadExecutor();

        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rvMessages.setLayoutManager(lm);
        rvMessages.setAdapter(adapter);

        btnSend.setOnClickListener(v -> onSendClicked());
        btnVoice.setOnClickListener(v -> handleVoiceButtonClick());
        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { onSendClicked(); return true; }
            return false;
        });

        setupLlmSpinner();
    }

    /**
     * Registers all tools into the ToolRegistry and creates the Planner, Executor, and Policy.
     * Default LLM is MockLLM; the Spinner switches it at runtime.
     */
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

        planner  = new Planner(new MockLLMClient(), registry);
        executor = new Executor(registry);
        policy   = new Policy();
    }

    // ── Spotify redirect ──────────────────────────────────────────────────

    /**
     * Handles the OAuth redirect URI from Spotify after the user authorizes the app.
     * If the intent contains a valid Spotify redirect, completes the auth flow on a
     * background thread and retries any pending Spotify command.
     *
     * @param intent the Intent to check for a Spotify redirect URI
     */
    private void handleSpotifyRedirect(Intent intent) {
        Uri data = intent != null ? intent.getData() : null;
        SpotifyAuthManager authManager = new SpotifyAuthManager();
        if (!authManager.canHandleRedirect(data)) return;

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
                // Retry the Spotify command that triggered the auth flow
                ToolResult retryResult = new SpotifyControlTool().execute(this, pendingArgs);
                runOnUiThread(() -> {
                    addAssistant(authMessage);
                    addAssistant(retryResult.getStatus() == ToolResult.Status.SUCCESS
                            ? "Spotify: " + retryResult.displayText()
                            : "Spotify failed: " + retryResult.displayText());
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

    // ── LLM Switcher (Improvement #2) ─────────────────────────────────────

    /**
     * Sets up the Spinner with four LLM options: MockLLM, Gemini, MiniMax, Custom LLM.
     * spinnerReady flag suppresses the automatic onItemSelected fired on first attach.
     * When the user picks an option, calls switchLlm() to swap the backend.
     */
    private void setupLlmSpinner() {
        String[] options = {LLM_MOCK, LLM_GEMINI, LLM_MINIMAX, LLM_CUSTOM};
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, options);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLlm.setAdapter(spinnerAdapter);
        spinnerLlm.setSelection(0);

        spinnerLlm.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!spinnerReady) { spinnerReady = true; return; }
                switchLlm(options[position]);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /**
     * Hot-swaps the LLM backend inside Planner without restarting the Activity.
     * Called on the main thread from the Spinner callback.
     * If a required API key is missing, reverts the Spinner to MockLLM and shows a warning.
     * If Custom LLM is selected and no config is saved, opens the config dialog first.
     *
     * @param label the display name of the selected LLM option
     */
    private void switchLlm(String label) {
        switch (label) {
            case LLM_GEMINI:
                String geminiKey = BuildConfig.GEMINI_API_KEY.trim();
                if (geminiKey.isEmpty()) {
                    addAssistant("Gemini API key not found. Add GEMINI_API_KEY to local.properties.");
                    spinnerLlm.setSelection(0);
                    return;
                }
                planner.setLlmClient(new GeminiLLMClient(geminiKey));
                break;

            case LLM_MINIMAX:
                String miniMaxKey = BuildConfig.MINIMAX_API_KEY.trim();
                if (miniMaxKey.isEmpty()) {
                    addAssistant("MiniMax API key not found. Add MINIMAX_API_KEY to local.properties.");
                    spinnerLlm.setSelection(0);
                    return;
                }
                planner.setLlmClient(new MiniMaxLLMClient(registry));
                break;

            case LLM_CUSTOM:
                // Load saved config; if incomplete, show the config dialog instead
                CustomLlmConfig config = loadCustomLlmConfig();
                if (!config.isComplete()) { showCustomLlmDialog(); return; }
                planner.setLlmClient(new ConfigurableLLMClient(
                        registry, config.apiKey, config.baseUrl, config.model));
                label = "Custom LLM (" + config.name + ")";
                break;

            case LLM_MOCK:
            default:
                planner.setLlmClient(new MockLLMClient());
                break;
        }

        addAssistant("Switched to " + label);
        setStatus("Planner: " + label + " ready");
        Log.i(TAG, "LLM switched to: " + label);
    }

    /**
     * Shows a dialog for the user to enter a custom OpenAI-compatible LLM config.
     * Pre-fills fields from any previously saved config.
     * On save, persists the config and immediately swaps the LLM backend.
     * On cancel, reverts the Spinner back to MockLLM.
     */
    private void showCustomLlmDialog() {
        CustomLlmConfig existing = loadCustomLlmConfig();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, 8, padding, 0);

        EditText name    = makeConfigEditText("Display name", existing.name);
        EditText baseUrl = makeConfigEditText(
                "Base URL, e.g. https://api.openai.com/v1/chat/completions", existing.baseUrl);
        EditText model   = makeConfigEditText("Model, e.g. gpt-4o-mini", existing.model);
        EditText apiKey  = makeConfigEditText("API key", existing.apiKey);
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

    /**
     * Creates a single-line EditText pre-filled with an existing value, used in the custom LLM dialog.
     *
     * @param hint  the placeholder text shown when the field is empty
     * @param value the existing value to pre-fill, may be empty
     * @return a configured EditText ready to add to a layout
     */
    private EditText makeConfigEditText(String hint, String value) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setText(value);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return editText;
    }

    /**
     * Returns the value if non-empty, otherwise returns the fallback string.
     *
     * @param value    the string to check
     * @param fallback the default to use if value is null or empty
     * @return value or fallback
     */
    private String emptyToDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /**
     * Loads the custom LLM config from SharedPreferences.
     *
     * @return a CustomLlmConfig with the saved values, or empty strings if nothing is saved
     */
    private CustomLlmConfig loadCustomLlmConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_LLM, MODE_PRIVATE);
        return new CustomLlmConfig(
                prefs.getString(PREF_CUSTOM_NAME, "Custom"),
                prefs.getString(PREF_CUSTOM_BASE_URL, ""),
                prefs.getString(PREF_CUSTOM_MODEL, ""),
                prefs.getString(PREF_CUSTOM_API_KEY, ""));
    }

    /**
     * Saves the custom LLM config to SharedPreferences so it persists across sessions.
     *
     * @param config the config to save
     */
    private void saveCustomLlmConfig(CustomLlmConfig config) {
        getSharedPreferences(PREFS_LLM, MODE_PRIVATE)
                .edit()
                .putString(PREF_CUSTOM_NAME, config.name)
                .putString(PREF_CUSTOM_BASE_URL, config.baseUrl)
                .putString(PREF_CUSTOM_MODEL, config.model)
                .putString(PREF_CUSTOM_API_KEY, config.apiKey)
                .apply();
    }

    // ── User input handling ───────────────────────────────────────────────

    /** Reads the input field, clears it, and passes the text to handleUserInput(). */
    private void onSendClicked() {
        String text = etInput.getText().toString().trim();
        if (text.isEmpty()) return;
        etInput.setText("");
        handleUserInput(text);
    }

    /**
     * Entry point for all user messages (text and voice).
     * Disables input while Planner runs on a background thread.
     * On success, calls presentPlan() on the main thread.
     * On failure, shows the error in the chat.
     *
     * @param text the user's natural language input
     */
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

    /**
     * Decides what to do with the Plan returned by Planner.
     * If the plan has no actions, shows the assistant message directly.
     * If it has actions, Policy always requires a confirmation dialog (prototype default).
     * The dialog result comes back via ActionPreviewHelper.Callback.
     *
     * @param plan             the Plan returned by Planner
     * @param originalUserText the original input, kept in case execution needs to retry
     */
    private void presentPlan(Plan plan, String originalUserText) {
        if (!plan.hasActions()) {
            addAssistant(plan.getAssistantMessage());
            setStatus(getString(R.string.status_ready));
            setInputEnabled(true);
            return;
        }

        setStatus("Planned " + plan.getActions().size() + " action(s). Awaiting confirmation...");

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

    // ── Plan execution (Improvement #1) ───────────────────────────────────

    /**
     * Runs the plan on a background thread using bgExecutor.
     * Each tool result is delivered individually back on the main thread
     * so the status bar updates live as steps complete (Improvement #1).
     *
     * Three result statuses:
     *   SUCCESS         -- show the result text
     *   NEED_PERMISSION -- request the required permission and save input for retry
     *   FAIL            -- show an error message
     *
     * @param plan             the confirmed plan to execute
     * @param originalUserText the original input, saved for retry if permission is needed
     */
    private void executePlan(Plan plan, String originalUserText) {
        int totalSteps = plan.getActions().size();
        setStatus("Executing... Step 1/" + totalSteps);
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

            // Deliver each result to the main thread one at a time for live progress updates
            for (int i = 0; i < results.size(); i++) {
                final int stepIndex = i;
                final ToolResult r  = results.get(i);
                final String stepLabel = "Step " + (stepIndex + 1) + "/" + totalSteps + ": ";

                runOnUiThread(() -> {
                    // Advance the status bar to the next step number
                    int nextStep = stepIndex + 2;
                    if (nextStep <= totalSteps) {
                        setStatus("Executing... Step " + nextStep + "/" + totalSteps);
                    } else {
                        setStatus(getString(R.string.status_ready));
                    }

                    switch (r.getStatus()) {
                        case SUCCESS:
                            addAssistant(stepLabel + r.displayText());
                            break;
                        case NEED_PERMISSION:
                            addAssistant(stepLabel + r.displayText());
                            pendingRetryText = originalUserText;
                            requestPermissionForTool(r);
                            setStatus(getString(R.string.status_ready));
                            pendingPlan = null;
                            setInputEnabled(true);
                            return; // stop here — will retry from the permission launcher
                        case FAIL:
                        default:
                            addAssistant(stepLabel + "Failed -- " + r.displayText());
                            break;
                    }

                    if (stepIndex == results.size() - 1) {
                        pendingPlan = null;
                        setInputEnabled(true);
                    }
                });
            }
        });
    }

    /**
     * Routes the permission request to the correct launcher based on which tool needs it.
     * If no handler is registered for the tool, informs the user.
     *
     * @param result the ToolResult that reported NEED_PERMISSION
     */
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
        addAssistant("Permission is required, but no handler is registered for: " + toolName);
    }

    /**
     * Returns the correct media read permissions for the current Android version.
     * Android 13+ uses granular READ_MEDIA_IMAGES / READ_MEDIA_VIDEO.
     * Older versions use the broader READ_EXTERNAL_STORAGE.
     *
     * @return the array of permission strings to request
     */
    private String[] mediaPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
            };
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    /**
     * Adds a USER message to the session and refreshes the list.
     *
     * @param text the user's message text
     */
    private void addUser(String text) {
        sessionStore.addMessage(new Message(Message.Role.USER, text));
        notifyAndScroll();
    }

    /**
     * Adds an ASSISTANT message to the session and refreshes the list.
     *
     * @param text the assistant's message text
     */
    private void addAssistant(String text) {
        sessionStore.addMessage(new Message(Message.Role.ASSISTANT, text));
        notifyAndScroll();
    }

    /** Tells the adapter a new message was added and scrolls the list to the bottom. */
    private void notifyAndScroll() {
        adapter.notifyItemInserted(sessionStore.getMessages().size() - 1);
        rvMessages.scrollToPosition(sessionStore.getMessages().size() - 1);
    }

    /**
     * Updates the status bar text shown at the top of the screen.
     *
     * @param text the status message to display
     */
    private void setStatus(String text) {
        tvStatus.setText(text);
    }

    /**
     * Enables or disables the text input field and send button together.
     *
     * @param enabled true to allow input, false to block it during processing
     */
    private void setInputEnabled(boolean enabled) {
        etInput.setEnabled(enabled);
        btnSend.setEnabled(enabled);
    }

    // ── Voice recognition ─────────────────────────────────────────────────

    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private boolean isListening = false;

    /**
     * Toggles voice recognition on and off.
     * Also handles the case where the voice engine gets stuck by destroying and recreating it.
     */
    private void handleVoiceButtonClick() {
        if (tvStatus.getText().toString().contains("busy")) {
            isListening = false;
            if (speechRecognizer != null) { speechRecognizer.destroy(); speechRecognizer = null; }
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

    /**
     * Checks microphone permission before starting voice recognition.
     * If already granted, starts listening immediately.
     * If not, requests the permission and waits for the result.
     */
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

    /** Stops the recognizer and resets the mic button icon. */
    private void stopVoiceAction() {
        isListening = false;
        if (speechRecognizer != null) speechRecognizer.stopListening();
        btnVoice.setImageResource(android.R.drawable.ic_btn_speak_now);
    }

    /**
     * Creates a SpeechRecognizer and starts listening.
     * onResults() picks the top recognition match and passes it to handleUserInput().
     * onError() handles common failure cases like no match found or engine busy.
     */
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

    // ── Custom LLM config data class ──────────────────────────────────────

    /**
     * Holds the user-provided config for a custom OpenAI-compatible LLM endpoint.
     * isComplete() returns true only when all required fields are filled in.
     */
    private static class CustomLlmConfig {
        final String name;
        final String baseUrl;
        final String model;
        final String apiKey;

        CustomLlmConfig(String name, String baseUrl, String model, String apiKey) {
            this.name    = name    != null ? name.trim()    : "Custom";
            this.baseUrl = baseUrl != null ? baseUrl.trim() : "";
            this.model   = model   != null ? model.trim()   : "";
            this.apiKey  = apiKey  != null ? apiKey.trim()  : "";
        }

        /** @return true if baseUrl, model, and apiKey are all non-empty */
        boolean isComplete() {
            return !baseUrl.isEmpty() && !model.isEmpty() && !apiKey.isEmpty();
        }
    }
}