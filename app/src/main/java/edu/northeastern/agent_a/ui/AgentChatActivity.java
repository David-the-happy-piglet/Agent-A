package edu.northeastern.agent_a.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
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

import edu.northeastern.agent_a.R;
import edu.northeastern.agent_a.core.agent.Executor;
import edu.northeastern.agent_a.core.agent.Planner;
import edu.northeastern.agent_a.core.agent.Policy;
import edu.northeastern.agent_a.core.memory.Message;
import edu.northeastern.agent_a.core.memory.SessionStore;
import edu.northeastern.agent_a.core.tools.ContactsLookupTool;
import edu.northeastern.agent_a.core.tools.EmailSummaryTool;
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

    private ImageButton btnVoice;


    private ChatAdapter adapter;
    private SessionStore sessionStore;
    private Planner planner;
    private Executor executor;
    private Policy policy;

    private Plan pendingPlan;
    private String pendingRetryText;

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
        btnVoice=findViewById(R.id.btnVoice);
        sessionStore = new SessionStore();
        adapter = new ChatAdapter(sessionStore.getMessages());

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
        List<ToolResult> results = executor.execute(this, plan);

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
                    return;
                case FAIL:
                default:
                    addAssistant(stepLabel + "Failed — " + r.displayText());
                    break;
            }
        }

        setStatus(getString(R.string.status_ready));
        pendingPlan = null;
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

    // 1. define record audio permission code
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;

    // 2. check and request audio permission
    private void checkAndRequestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            // if no permission, ask from user
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO_PERMISSION);
        } else {
            //if there's permission, start voice recognition
            startVoiceRecognition();
        }
    }

    // 3. handle user permission response
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // User gave permission
                Toast.makeText(this, "Permission granted, you may speak", Toast.LENGTH_SHORT).show();
                startVoiceRecognition();
            } else {
                // User declined
                Toast.makeText(this, "Need permission to record audio", Toast.LENGTH_LONG).show();
            }
        }
    }

    // 4. 语音识别的入口（这是你下一步要写的逻辑）
    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private boolean isListening = false;
    private void handleVoiceButtonClick() {
        if (!isListening) {
            // 如果当前没在录，就去查权限并开启
            checkAndRequestAudioPermission();
            isListening = true;
            setStatus("Listening..."); // 给用户反馈
        } else {
            // 如果正在录，点击则停止
            if (speechRecognizer != null) {
                speechRecognizer.stopListening(); // 关键：这会触发 onResults
            }
            isListening = false;
            setStatus(getString(R.string.status_ready));
        }
    }
    private void startVoiceRecognition() {
        // 1. 初始化检查
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());

            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String spokenText = matches.get(0);
                        etInput.setText(spokenText);
                        handleUserInput(spokenText);

                    }
                }

                @Override
                public void onError(int error) {
                    isListening = false;
                    String message = "Error: " + error;
                    Toast.makeText(AgentChatActivity.this, message, Toast.LENGTH_SHORT).show();
                }

                // 其他 override 方法可以暂时留空
                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }

        // 2. 开始监听
        speechRecognizer.startListening(speechRecognizerIntent);
    }
}
