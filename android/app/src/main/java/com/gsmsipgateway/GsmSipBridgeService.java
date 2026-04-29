package com.gsmsipgateway;

import android.app.*;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.content.*;
import android.os.*;
import android.telecom.TelecomManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;

public class GsmSipBridgeService extends Service implements LinphoneEngine.BridgeCallback {
    private static final String TAG = "GsmSipBridgeService";
    private static final String CH = "gsm_sip_bridge";
    private static final int RING_INTERVAL_MS = 5000;
    private LinphoneEngine sip;
    private String host, user, pass, ext;
    private int port, answerRings;
    private boolean isSipRegistered = false;
    private boolean bridgeInProgress = false;
    private int bridgeAttempts = 0;
    private int sipRetryCount = 0;
    private static final int MAX_SIP_RETRIES = 5;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable bridgeRunnable = this::bridgeToExtension;
    private final Runnable answerRunnable = this::answerAndBridge;
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;
    // FIX: Track AudioFocusRequest so it can be properly abandoned (deprecated 3-arg form removed)
    private AudioFocusRequest audioFocusRequest;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        audioManager = getSystemService(AudioManager.class);
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":bridge");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(10 * 60 * 1000L);
        }
        startForeground(1, note("Initializing..."));
        reload();
    }

    private void reload() {
        SharedPreferences p = getSharedPreferences("sip_config", MODE_PRIVATE);
        host = p.getString("host", "103.82.193.58");
        port = p.getInt("port", 5060);
        user = p.getString("username", "3001");
        pass = p.getString("password", "");
        ext  = p.getString("bridge_ext", "1001");
        answerRings = Math.max(1, p.getInt("answer_rings", 1));
        isSipRegistered = false;
        bridgeInProgress = false;
        bridgeAttempts = 0;
        sipRetryCount = 0;
        handler.removeCallbacks(answerRunnable);
        handler.removeCallbacks(bridgeRunnable);
        if (sip != null) sip.destroy();
        sip = new LinphoneEngine(this, this);
        sip.register(host, port, user, pass);
        updateNote("Registering SIP " + user + "@" + host + ":" + port + "...");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "ACTION_INCOMING_CALL":
                    String caller = intent.getStringExtra("caller_number");
                    bridgeInProgress = true;
                    bridgeAttempts = 0;
                    handler.removeCallbacks(answerRunnable);
                    handler.removeCallbacks(bridgeRunnable);
                    int answerDelayMs = Math.max(0, answerRings - 1) * RING_INTERVAL_MS;
                    updateNote("Incoming: " + caller + " | answering after ring " + answerRings);
                    handler.postDelayed(answerRunnable, answerDelayMs);
                    break;
                case "ACTION_CALL_ENDED":
                    handler.removeCallbacks(answerRunnable);
                    handler.removeCallbacks(bridgeRunnable);
                    bridgeInProgress = false;
                    bridgeAttempts = 0;
                    if (sip != null) sip.hangup();
                    resetAudio();
                    updateNote("Ready - Waiting...");
                    break;
                case "ACTION_RELOAD":
                    reload();
                    break;
            }
        }
        return START_STICKY;
    }

    private void autoAnswer() {
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tm != null) tm.acceptRingingCall();
            Log.d(TAG, "GSM auto-answered");
        } catch (SecurityException e) { Log.e(TAG, e.getMessage()); }
    }

    private void answerAndBridge() {
        if (!bridgeInProgress) {
            return;
        }
        prepareAudio();
        autoAnswer();
        handler.removeCallbacks(bridgeRunnable);
        handler.postDelayed(bridgeRunnable, 1200);
    }

    private void bridgeToExtension() {
        if (!bridgeInProgress) {
            return;
        }
        if (!isSipRegistered) {
            scheduleBridgeRetry("SIP not registered yet");
            return;
        }
        // FIX: indentation was broken in original (Log line had no leading whitespace)
        Log.d(TAG, "SIP registered, bridging to extension " + ext);
        updateNote("Dialing SIP extension...");
        boolean success = sip.callSip(ext, host, port);
        if (!success) {
            scheduleBridgeRetry("Failed to dial " + ext);
        }
    }

    @Override public void onSipRegistered() {
        isSipRegistered = true;
        Log.d(TAG, "SIP Registration successful");
        updateNote("SIP Registered - Ready");
    }

    @Override public void onSipRegistrationFailed(int errorCode, String errorMessage) {
        isSipRegistered = false;
        sipRetryCount++;
        Log.e(TAG, "SIP Registration failed | code=" + errorCode + " | " + errorMessage + " | attempt=" + sipRetryCount);
        if (sipRetryCount > MAX_SIP_RETRIES) {
            Log.e(TAG, "Giving up after " + MAX_SIP_RETRIES + " attempts.");
            updateNote("[FAILED] Reg [" + LinphoneEngine.sipErrorName(errorCode) + "] - Check server/credentials");
            return;
        }
        long delayMs = Math.min(5000L * (1L << (sipRetryCount - 1)), 60000L);
        updateNote("Reg failed [" + LinphoneEngine.sipErrorName(errorCode) + "] retry " + sipRetryCount + "/" + MAX_SIP_RETRIES + " in " + (delayMs/1000) + "s");
        handler.postDelayed(() -> {
            if (sip != null) {
                Log.d(TAG, "Retrying SIP registration...");
                sip.register(host, port, user, pass);
            }
        }, delayMs);
    }

    @Override public void onSipCallConnected() { updateNote("Bridge Active"); }
    @Override public void onSipCallEnded() {
        bridgeInProgress = false;
        bridgeAttempts = 0;
        resetAudio();
        updateNote("Ready - Waiting...");
    }

    private void scheduleBridgeRetry(String reason) {
        bridgeAttempts++;
        if (bridgeAttempts > 10) {
            bridgeInProgress = false;
            updateNote("Bridge failed");
            Log.e(TAG, reason + ", giving up after " + bridgeAttempts + " attempts");
            return;
        }
        Log.w(TAG, reason + ", retrying in 500ms");
        handler.postDelayed(bridgeRunnable, 500);
    }

    private void prepareAudio() {
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);
            // FIX: Replaced deprecated 3-arg requestAudioFocus() (deprecated since API 26)
            // with the AudioFocusRequest builder API (available since API 26 = app's minSdk)
            AudioAttributes playbackAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(playbackAttrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusChange -> {
                    Log.d(TAG, "Audio focus changed: " + focusChange);
                })
                .build();
            int result = audioManager.requestAudioFocus(audioFocusRequest);
            Log.d(TAG, "AudioFocus request result: " + result);
        }
    }

    private void resetAudio() {
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            // FIX: Replaced deprecated abandonAudioFocus(null) with proper AudioFocusRequest form
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
                audioFocusRequest = null;
            }
        }
    }

    private void createChannel() {
        NotificationChannel c = new NotificationChannel(CH, "GSM-SIP Bridge", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(c);
    }
    private Notification note(String t) {
        return new NotificationCompat.Builder(this, CH)
            .setContentTitle("GSM-SIP Gateway").setContentText(t)
            .setSmallIcon(android.R.drawable.ic_menu_call).setOngoing(true).build();
    }
    private void updateNote(String t) { getSystemService(NotificationManager.class).notify(1, note(t)); }

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        handler.removeCallbacks(answerRunnable);
        handler.removeCallbacks(bridgeRunnable);
        resetAudio();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (sip != null) sip.destroy();
        super.onDestroy();
    }
}
