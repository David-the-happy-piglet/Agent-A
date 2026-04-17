package edu.northeastern.agent_a.ui;

import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.Executor;

public class BiometricHelper {

    private static final String TAG = "BiometricHelper";
    private static final int MAX_ATTEMPTS = 3;

    public interface AuthCallback {
        void onSuccess();
        void onFailure();
    }

    private int failureCount = 0;
    private final FragmentActivity activity;
    private final AuthCallback callback;

    public BiometricHelper(FragmentActivity activity, AuthCallback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    public void showBiometricPrompt() {
        checkSupport();
        
        Executor executor = ContextCompat.getMainExecutor(activity);
        
        BiometricPrompt biometricPrompt = new BiometricPrompt(activity, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        Log.e(TAG, "Auth error: " + errString + " code: " + errorCode);
                        
                        if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || 
                            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            errorCode == BiometricPrompt.ERROR_LOCKOUT ||
                            errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                            callback.onFailure();
                        }
                    }

                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        int type = result.getAuthenticationType();
                        String typeStr = (type == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC) ? "Biometric" : "Device Credential";
                        Log.d(TAG, "Auth success via: " + typeStr);
                        failureCount = 0;
                        callback.onSuccess();
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        failureCount++;
                        Log.w(TAG, "Auth failed. Count: " + failureCount);
                        if (failureCount >= MAX_ATTEMPTS) {
                            Toast.makeText(activity, "Too many failed attempts", Toast.LENGTH_SHORT).show();
                            callback.onFailure();
                        }
                    }
                });

        BiometricPrompt.PromptInfo.Builder promptBuilder = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Security Authentication")
                .setSubtitle("Face, Fingerprint, or Passcode")
                .setConfirmationRequired(false)
                .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG | 
                        BiometricManager.Authenticators.BIOMETRIC_WEAK | 
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL);

        biometricPrompt.authenticate(promptBuilder.build());
    }

    private void checkSupport() {
        BiometricManager manager = BiometricManager.from(activity);
        int strong = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        int weak = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        
        Log.d(TAG, "Support Check - Strong: " + strong + ", Weak: " + weak);
    }
}
