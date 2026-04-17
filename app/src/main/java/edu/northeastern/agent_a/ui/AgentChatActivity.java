package edu.northeastern.agent_a.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
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
import edu.northeastern.agent_a.core.tools.EarthPhotoTool;
import edu.northeastern.agent_a.core.tools.EmailSummaryTool;
import edu.northeastern.agent_a.core.tools.NewsFeedTool;
import edu.northeastern.agent_a.core.tools.MapsNavigateTool;
import edu.northeastern.agent_a.core.tools.PhoneDialTool;
import edu.northeastern.agent_a.core.tools.Plan;
import edu.northeastern.agent_a.core.tools.SmsComposeTool;
import edu.northeastern.agent_a.core.tools.ToolRegistry;
import edu.northeastern.agent_a.core.tools.ToolResult;
import edu.northeastern.agent_a.llm.GeminiLLMClient;
import edu.northeastern.agent_a.llm.MiniMaxLLMClient;
import edu.northeastern.agent_a.llm.MockLLMClient;


public class AgentChatActivity extends AppCompatActivity {

    private static final String TAG = "AgentChatActivity";
    private static final long BACKGROUND_TIMEOUT_MS = 30000; // 30 seconds

    /**
     * SECURITY SWITCH: 
     * Set to true to enable biometric/passcode protection.
     * Set to false for testing without auth data.
     */
    private static final boolean ENABLE_SECURITY = true; 

    // Hardcoded credentials for MiniMax as local.properties is blocked
    private static final String MINIMAX_KEY = "sk-api-6YAGoYgYvcz2mDpeFzdRtGG2fFKPl4OcJJOO3wAqRIGVTrPA6cXFeh9y4pmkYgY8t8I9f81qnEnNiJhPrxhz4rtpGKIgETCSsdvdxDDaNVC0oWZWsaFT0jk";
    private static final String MINIMAX_URL = "https://api.minimax.io/v1/text/chatcompletion_v2";
    private static final String MINIMAX_MODEL = "M2-her";

    private RecyclerView rvMessages;
    private EditText etInput;
    private MaterialButton btnSend;
    private TextView tvStatus;
    private ImageButton btnVoice;
    private Spinner spinnerLlm;
    private View overlay; // Protection overlay

    private ChatAdapter adapter;
    private SessionStore sessionStore;
    private Planner planner;
    private Executor executor;
    private Policy policy;
    private ExecutorService planningExecutor;
    private ToolRegistry registry;
    private String plannerModeLabel = "MiniMax";

    private String pendingRetryText;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    // Biometric security fields
    private BiometricHelper biometricHelper;
    private long lastTimeInBackground = 0;

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
        setupLlmSpinner();
        
        setupInsets();
        setupBiometricSecurity();

        addAssistant(getString(R.string.welcome_message));
    }

    private void setupBiometricSecurity() {
        overlay = new View(this);
        overlay.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setBackgroundColor(ContextCompat.getColor(this, android.R.color.black));
        overlay.setElevation(100f);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        ((ViewGroup) findViewById(android.R.id.content)).addView(overlay);

        if (!ENABLE_SECURITY || isEmulator()) {
            Log.i(TAG, "Security disabled or running on emulator. Bypassing authentication.");
            overlay.setVisibility(View.GONE);
            return;
        }

        biometricHelper = new BiometricHelper(this, new BiometricHelper.AuthCallback() {
            @Override
            public void onSuccess() {
                overlay.setVisibility(View.GONE);
            }

            @Override
            public void onFailure() {
                finishAffinity();
            }
        });

        // Initial Cold Start Authentication
        biometricHelper.showBiometricPrompt();
    }

    private boolean isEmulator() {
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator");
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!ENABLE_SECURITY || isEmulator()) {
            return;
        }

        if (lastTimeInBackground != 0) {
            long timeDiff = System.currentTimeMillis() - lastTimeInBackground;
            if (timeDiff > BACKGROUND_TIMEOUT_MS) {
                overlay.setVisibility(View.VISIBLE);
                if (biometricHelper != null) {
                    biometricHelper.showBiometricPrompt();
                }
            } else {
                overlay.setVisibility(View.GONE);
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        lastTimeInBackground = System.currentTimeMillis();
    }

    private void setupInsets() {
        View topBar = findViewById(R.id.topBar);
        View inputArea = findViewById(R.id.inputArea);
        View root = findViewById(R.id.root);
        
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime());

            // 保护策略：如果系统返回高度太小（如模拟器上），设置一个最小的 24dp padding
            int minTopPadding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
            int finalTopPadding = Math.max(systemBars.top, minTopPadding);

            // 增加额外的内边距，确保内容不被状态栏图标遮挡且可点击
            topBar.setPadding(topBar.getPaddingLeft(), finalTopPadding, topBar.getPaddingRight(), topBar.getPaddingBottom());

            // 底部处理
            int bottomPadding = Math.max(systemBars.bottom, imeInsets.bottom);
            inputArea.setPadding(inputArea.getPaddingLeft(), inputArea.getPaddingTop(), inputArea.getPaddingRight(), bottomPadding);

            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void initViews() {
        rvMessages = findViewById(R.id.rvMessages);
        etInput = findViewById(R.id.etInput);
        btnSend = findViewById(R.id.btnSend);
        tvStatus = findViewById(R.id.tvStatus);
        btnVoice = findViewById(R.id.btnVoice);
        spinnerLlm = findViewById(R.id.spinnerLlm);
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

    private void setupLlmSpinner() {
        String[] llms = {"MiniMax", "Gemini", "Mock"};
        ArrayAdapter<String> llmAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, llms);
        llmAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLlm.setAdapter(llmAdapter);

        spinnerLlm.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = llms[position];
                if (planner == null) return;
                
                switch (selected) {
                    case "MiniMax":
                        planner.setLlmClient(new MiniMaxLLMClient(registry, MINIMAX_KEY, MINIMAX_URL, MINIMAX_MODEL));
                        plannerModeLabel = "MiniMax";
                        break;
                    case "Gemini":
                        planner.setLlmClient(new GeminiLLMClient(BuildConfig.GEMINI_API_KEY));
                        plannerModeLabel = "Gemini";
                        break;
                    case "Mock":
                        planner.setLlmClient(new MockLLMClient());
                        plannerModeLabel = "Mock";
                        break;
                }
                setStatus("Planner: " + plannerModeLabel + " ready");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void initAgent() {
        registry = new ToolRegistry();
        registry.register(new PhoneDialTool());
        registry.register(new SmsComposeTool());
        registry.register(new MapsNavigateTool());
        registry.register(new ContactsLookupTool());
        registry.register(new EmailSummaryTool());
        registry.register(new NewsFeedTool());
        registry.register(new EarthPhotoTool());

        // Default to MiniMax using the hardcoded key
        planner = new Planner(new MiniMaxLLMClient(registry, MINIMAX_KEY, MINIMAX_URL, MINIMAX_MODEL), registry);
        plannerModeLabel = "MiniMax";
        setStatus("Planner: MiniMax ready");

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
        adapter.setLoading(true);
        rvMessages.scrollToPosition(adapter.getItemCount() - 1);

        planningExecutor.execute(() -> {
            try {
                Plan plan = planner.createPlan(text, sessionStore);
                runOnUiThread(() -> {
                    adapter.setLoading(false);
                    presentPlan(plan, text);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    adapter.setLoading(false);
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
                + " action(s). Awaiting confirmation…");

        if (policy.requiresPreview(plan)) {
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
                            setInputEnabled(true);
                            return;
                        case FAIL:
                        default:
                            addAssistant(stepLabel + "Failed — " + r.displayText());
                            break;
                    }
                }
                setStatus(getString(R.string.status_ready));
                setInputEnabled(true);
            });
        });
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
        rvMessages.scrollToPosition(adapter.getItemCount() - 1);
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
                }@Override public void onPartialResults(Bundle partialResults) {}
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
