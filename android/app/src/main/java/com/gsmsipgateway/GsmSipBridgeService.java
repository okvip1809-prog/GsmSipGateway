package com.gsmsipgateway;
import android.app.*;
import android.media.AudioManager;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.telecom.TelecomManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import org.linphone.core.Call;

public class GsmSipBridgeService extends Service implements LinphoneEngine.BridgeCallback {
    private static final String TAG = "GsmSipBridgeService";
    private static final String CH = "gsm_sip_bridge";
    private static final int RING_INTERVAL_MS = 5000;
    private LinphoneEngine sip;
    private String host, user, pass, ext;
    private int port, answerRings;
    private int sipRetryCount = 0;
    private boolean bridgeInProgress = false;
    private int bridgeAttempts = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable bridgeRunnable = this::bridgeToExtension;
    private final Runnable answerRunnable = this::answerAndBridge;
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;

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
        sip = new LinphoneEngine(this, this);
        reload();
    }

    private void reload() {
        SharedPreferences p = getSharedPreferences("sip_config", MODE_PRIVATE);
        host = p.getString("host", "");
        port = p.getInt("port", 5060);
        user = p.getString("username", "");
        pass = p.getString("password", "");
        ext  = p.getString("bridge_ext", "");
        answerRings = Math.max(1, p.getInt("answer_rings", 1));
        sipRetryCount = 0;
        bridgeInProgress = false;
        bridgeAttempts = 0;
        handler.removeCallbacks(answerRunnable);
        handler.removeCallbacks(bridgeRunnable);
        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            updateNote("No config - open app to configure");
            return;
        }
        if (sip != null) sip.register(host, port, user, pass);
        updateNote("Registering " + user + "@" + host + "...");
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
                    int delayMs = Math.max(0, answerRings - 1) * RING_INTERVAL_MS;
                    updateNote("Incoming: " + caller + " | answering after ring " + answerRings);
                    handler.postDelayed(answerRunnable, delayMs);
                    break;
                case "ACTION_CALL_ENDED":
                    handler.removeCallbacks(answerRunnable);
                    handler.removeCallbacks(bridgeRunnable);
                    bridgeInProgress = false;
                    bridgeAttempts = 0;
                    if (sip != null) sip.hangup();
                    resetAudio();
                    updateNote("Ready - " + user + "@" + host);
                    break;
                case "ACTION_RELOAD":
                    reload();
                    break;
                case "ACTION_STOP":
                    stopSelf();
                    return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    private void autoAnswer() {
        try {
            TelecomManager tm = getSystemService(TelecomManager.class);
            if (tm != null) tm.acceptRingingCall();
        } catch (SecurityException e) { Log.e(TAG, "autoAnswer: " + e.getMessage()); }
    }

    private void answerAndBridge() {
        if (bridgeInProgress) {
            prepareAudio();
            autoAnswer();
            handler.removeCallbacks(bridgeRunnable);
            handler.postDelayed(bridgeRunnable, 1200);
        }
    }

    private void bridgeToExtension() {
        if (bridgeInProgress) {
            if (sip == null || ext == null || ext.isEmpty()) {
                scheduleBridgeRetry("SIP not ready or ext not configured");
                return;
            }
            Log.d(TAG, "Bridging to SIP extension: " + ext);
            updateNote("Dialing " + ext + "...");
            boolean ok = sip.callSip(ext, host, port);
            if (ok == false) scheduleBridgeRetry("callSip failed");
        }
    }

    @Override
    public void onSipRegistered() {
        sipRetryCount = 0;
        Log.d(TAG, "SIP registered: " + user + "@" + host);
        updateNote("Ready - " + user + "@" + host);
    }

    @Override
    public void onSipRegistrationFailed(int errorCode, String errorMessage) {
        sipRetryCount++;
        Log.e(TAG, "SIP reg failed code=" + errorCode + " | " + errorMessage);
        int backoffPower = Math.min(sipRetryCount - 1, 10);
        long retryDelayMs = Math.min(5000L * (1L << backoffPower), 60000L);
        updateNote("Reg failed [" + LinphoneEngine.sipErrorName(errorCode) + "], retry in " + (retryDelayMs / 1000) + "s");
        handler.postDelayed(() -> {
            if (sip != null && host != null && host.length() > 0) {
                sip.register(host, port, user, pass);
            }
        }, retryDelayMs);
    }

    @Override
    public void onSipCallConnected() {
        Log.d(TAG, "SIP bridge call connected");
        updateNote("Bridge Active - " + user + "@" + host);
    }

    @Override
    public void onSipCallEnded() {
        Log.d(TAG, "SIP bridge call ended");
        bridgeInProgress = false;
        bridgeAttempts = 0;
        resetAudio();
        updateNote("Ready - " + user + "@" + host);
        try {
            TelecomManager tm = getSystemService(TelecomManager.class);
            if (tm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tm.endCall();
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onSipIncomingCall(Call call, String dialedNumber) {
        Log.d(TAG, "SIP incoming (Asterisk->GSM): " + dialedNumber);
        if (dialedNumber == null || dialedNumber.trim().isEmpty()) {
            try { if (call != null) call.terminate(); } catch (Exception ignored) {}
            return;
        }
        if (sip != null) sip.answerIncomingCall(call);
        updateNote("Outbound GSM: " + dialedNumber);
        prepareAudio();
        try {
            Intent i = new Intent(Intent.ACTION_CALL);
            i.setData(Uri.parse("tel:" + dialedNumber.trim()));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "GSM outbound failed: " + e.getMessage());
        }
    }

    private void prepareAudio() {
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);
        }
    }

    private void resetAudio() {
        if (audioManager != null) audioManager.setMode(AudioManager.MODE_NORMAL);
    }

    private void scheduleBridgeRetry(String reason) {
        bridgeAttempts++;
        if (bridgeAttempts > 10) {
            bridgeInProgress = false;
            updateNote("Bridge failed - " + user + "@" + host);
            return;
        }
        Log.w(TAG, reason + ", retry #" + bridgeAttempts);
        handler.postDelayed(bridgeRunnable, 500);
    }

    private void createChannel() {
        NotificationChannel c = new NotificationChannel(CH, "GSM-SIP Bridge", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(c);
    }

    private Notification note(String text) {
        return new NotificationCompat.Builder(this, CH)
            .setContentTitle("GSM-SIP Gateway")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .build();
    }

    private void updateNote(String text) {
        getSystemService(NotificationManager.class).notify(1, note(text));
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (sip != null) sip.destroy();
        resetAudio();
        stopForeground(true);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
