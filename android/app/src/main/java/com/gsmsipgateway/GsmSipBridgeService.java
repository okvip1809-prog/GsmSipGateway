package com.gsmsipgateway;

import android.app.*;
import android.media.AudioManager;
import android.content.*;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.*;
import android.telecom.TelecomManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import org.linphone.core.Call;
import org.linphone.core.Core;
import org.linphone.core.Factory;
import org.linphone.core.RegistrationState;

/**
 * Dual SIM Gateway Service - hỗ trợ 2 tài khoản SIP cùng lúc
 * SIM Slot 0 -> SIP Account 1001
 * SIM Slot 1 -> SIP Account 1002
 */
public class GsmSipBridgeService extends Service implements SipAccountManager.DualSipCallback {
    private static final String TAG = "GsmSipBridgeService";
    private static final String CH = "gsm_sip_bridge";
    private static final int RING_INTERVAL_MS = 5000;
    
    private Core core;
    private SipAccountManager sipMgr;
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager.NetworkCallback networkCallback;
    
    // Track incoming calls per SIM slot
    private boolean[] bridgeInProgress = new boolean[2];
    private int[] bridgeAttempts = new int[2];
    private int[] sipRetryCount = new int[2];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable[] bridgeRunnables = new Runnable[2];
    private Runnable[] answerRunnables = new Runnable[2];
    private Runnable reRegisterRunnable;
    
    private static final int MAX_SIP_RETRIES = Integer.MAX_VALUE;

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
        
        // Khởi tạo Linphone Core
        try {
            Factory factory = Factory.instance();
            factory.setDebugMode(false, TAG);
            core = factory.createCore(null, null, this);
            Log.d(TAG, "Linphone Core created");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create Linphone Core: " + e.getMessage());
            updateNote("Failed to create SIP engine");
            return;
        }
        
        // Khởi tạo SIP Account Manager
        sipMgr = new SipAccountManager(core, this);
        
        // Khởi tạo runnables cho mỗi SIM slot
        for (int i = 0; i < 2; i++) {
            final int slot = i;
            bridgeRunnables[i] = () -> bridgeToExtension(slot);
            answerRunnables[i] = () -> answerAndBridge(slot);
        }
        reRegisterRunnable = this::reloadAccounts;
        
        startForeground(1, note("Initializing..."));
        registerNetworkCallback();
        reloadAccounts();
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        NetworkRequest req = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "Network available - scheduling SIP re-register");
                handler.removeCallbacks(reRegisterRunnable);
                handler.postDelayed(reRegisterRunnable, 2000);
            }
        };
        try { cm.registerNetworkCallback(req, networkCallback); }
        catch (Exception e) { Log.e(TAG, "registerNetworkCallback failed: " + e.getMessage()); }
    }

    private void reloadAccounts() {
        if (sipMgr == null || core == null) return;
        
        SharedPreferences p = getSharedPreferences("sip_config", MODE_PRIVATE);
        String host = p.getString("host", "103.82.193.58");
        int port = p.getInt("port", 5060);
        
        // Cấu hình SIP 1001 cho SIM1
        String user1 = p.getString("username_sim1", "1001");
        String pass1 = p.getString("password_sim1", "abc123123");
        sipMgr.configureAccount(SipAccountManager.ACCOUNT_SIM1, host, port, user1, pass1, user1);
        
        // Cấu hình SIP 1002 cho SIM2
        String user2 = p.getString("username_sim2", "1002");
        String pass2 = p.getString("password_sim2", "abc123123");
        sipMgr.configureAccount(SipAccountManager.ACCOUNT_SIM2, host, port, user2, pass2, user2);
        
        // Reset state
        for (int i = 0; i < 2; i++) {
            bridgeInProgress[i] = false;
            bridgeAttempts[i] = 0;
            sipRetryCount[i] = 0;
            handler.removeCallbacks(answerRunnables[i]);
            handler.removeCallbacks(bridgeRunnables[i]);
        }
        
        // Đăng ký cả 2 tài khoản
        sipMgr.registerAll();
        updateNote("Registering SIP accounts...");
        
        Log.d(TAG, "Reloaded accounts: " + user1 + " & " + user2 + " @ " + host + ":" + port);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            int simSlot = intent.getIntExtra("sim_slot", 0);
            
            switch (intent.getAction()) {
                case "ACTION_INCOMING_CALL": {
                    String caller = intent.getStringExtra("caller_number");
                    Log.d(TAG, "Incoming call from " + caller + " on SIM slot " + simSlot);
                    bridgeInProgress[simSlot] = true;
                    bridgeAttempts[simSlot] = 0;
                    handler.removeCallbacks(answerRunnables[simSlot]);
                    handler.removeCallbacks(bridgeRunnables[simSlot]);
                    
                    SharedPreferences p = getSharedPreferences("sip_config", MODE_PRIVATE);
                    int answerRings = Math.max(1, p.getInt("answer_rings", 1));
                    int answerDelayMs = Math.max(0, answerRings - 1) * RING_INTERVAL_MS;
                    
                    String statusTxt = "SIM" + (simSlot + 1) + " incoming: " + caller + " | answering after ring " + answerRings;
                    updateNote(statusTxt);
                    handler.postDelayed(answerRunnables[simSlot], answerDelayMs);
                    break;
                }
                case "ACTION_CALL_ENDED": {
                    Log.d(TAG, "Call ended on SIM slot " + simSlot);
                    handler.removeCallbacks(answerRunnables[simSlot]);
                    handler.removeCallbacks(bridgeRunnables[simSlot]);
                    bridgeInProgress[simSlot] = false;
                    bridgeAttempts[simSlot] = 0;
                    sipMgr.hangup(simSlot);
                    resetAudio();
                    updateNote("Ready - Waiting...");
                    break;
                }
                case "ACTION_RELOAD":
                    reloadAccounts();
                    break;
                case "ACTION_STOP":
                    Log.d(TAG, "Stop requested");
                    stopSelf();
                    return START_NOT_STICKY;
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

    private void answerAndBridge(int simSlot) {
        if (!bridgeInProgress[simSlot]) return;
        prepareAudio();
        autoAnswer();
        handler.removeCallbacks(bridgeRunnables[simSlot]);
        handler.postDelayed(bridgeRunnables[simSlot], 1200);
    }

    private void bridgeToExtension(int simSlot) {
        if (!bridgeInProgress[simSlot]) return;
        
        if (!sipMgr.isAccountRegistered(simSlot)) {
            scheduleBridgeRetry(simSlot, "SIP account " + simSlot + " not registered yet");
            return;
        }
        
        SipAccountManager.SipAccount acc = sipMgr.getAccount(simSlot);
        String ext = acc != null ? acc.extension : "";
        
        Log.d(TAG, "Bridging SIM slot " + simSlot + " to extension " + ext);
        updateNote("SIM" + (simSlot + 1) + " dialing " + ext + "...");
        
        boolean success = sipMgr.callExtension(simSlot, ext);
        if (!success) {
            scheduleBridgeRetry(simSlot, "Failed to dial extension from slot " + simSlot);
        }
    }

    private void scheduleBridgeRetry(int simSlot, String reason) {
        bridgeAttempts[simSlot]++;
        if (bridgeAttempts[simSlot] > 10) {
            bridgeInProgress[simSlot] = false;
            updateNote("SIM" + (simSlot + 1) + " bridge failed");
            Log.e(TAG, reason + ", giving up after " + bridgeAttempts[simSlot] + " attempts");
            return;
        }
        Log.w(TAG, reason + ", retrying in 500ms");
        handler.postDelayed(bridgeRunnables[simSlot], 500);
    }

    // ============ SipAccountManager.DualSipCallback implementations ============

    @Override
    public void onAccountRegistered(int simSlot, String username) {
        Log.d(TAG, "SIP Account registered: slot=" + simSlot + " user=" + username);
        updateNote("SIM" + (simSlot + 1) + " (" + username + ") Registered");
        sipRetryCount[simSlot] = 0;
    }

    @Override
    public void onAccountRegistrationFailed(int simSlot, int errorCode, String errorMessage) {
        sipRetryCount[simSlot]++;
        Log.e(TAG, "SIP registration failed: slot=" + simSlot + " code=" + errorCode + " | " + errorMessage);
        
        long delayMs = Math.min(5000L * (1L << Math.min(sipRetryCount[simSlot] - 1, 10)), 60000L);
        updateNote("SIM" + (simSlot + 1) + " reg failed, retry in " + (delayMs / 1000) + "s");
        
        handler.postDelayed(() -> {
            if (sipMgr != null) {
                Log.d(TAG, "Retrying SIP registration for slot " + simSlot);
                sipMgr.registerAll();
            }
        }, delayMs);
    }

    @Override
    public void onAccountCallConnected(int simSlot) {
        Log.d(TAG, "SIP call connected: slot=" + simSlot);
        updateNote("SIM" + (simSlot + 1) + " Bridge Active");
    }

    @Override
    public void onAccountCallEnded(int simSlot) {
        Log.d(TAG, "SIP call ended: slot=" + simSlot);
        bridgeInProgress[simSlot] = false;
        bridgeAttempts[simSlot] = 0;
        resetAudio();
        updateNote("Ready - Waiting...");
        
        // Kết thúc cuộc gọi GSM nếu có
        try {
            TelecomManager tm = getSystemService(TelecomManager.class);
            if (tm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tm.endCall();
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onIncomingCall(int simSlot, Call call, String dialedNumber) {
        Log.d(TAG, "Incoming SIP from Asterisk: slot=" + simSlot + " number=" + dialedNumber);
        if (dialedNumber == null || dialedNumber.trim().isEmpty()) {
            Log.e(TAG, "onIncomingCall: no number, ignoring");
            return;
        }
        
        // Trả lời cuộc gọi SIP
        sipMgr.answerCall(simSlot);
        
        // Gọi GSM ra ngoài
        updateNote("SIM" + (simSlot + 1) + " Outbound GSM: " + dialedNumber);
        prepareAudio();
        try {
            Intent intent = new Intent(Intent.ACTION_CALL);
            intent.setData(Uri.parse("tel:" + dialedNumber.trim()));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.d(TAG, "GSM outbound call initiated: " + dialedNumber);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initiate GSM outbound call: " + e.getMessage());
        }
    }

    private void prepareAudio() {
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);
            Log.d(TAG, "Audio mode set to IN_COMMUNICATION");
        }
    }

    private void resetAudio() {
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
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

    private void updateNote(String t) { 
        getSystemService(NotificationManager.class).notify(1, note(t)); 
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(reRegisterRunnable);
        for (int i = 0; i < 2; i++) {
            handler.removeCallbacks(answerRunnables[i]);
            handler.removeCallbacks(bridgeRunnables[i]);
        }
        
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null && networkCallback != null) {
            try { cm.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        }
        
        if (sipMgr != null) sipMgr.destroy();
        resetAudio();
        stopForeground(true);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}

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
    private static final int MAX_SIP_RETRIES = Integer.MAX_VALUE; // never give up
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable bridgeRunnable = this::bridgeToExtension;
    private final Runnable answerRunnable = this::answerAndBridge;
    private final Runnable reRegisterRunnable = this::reload;
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager.NetworkCallback networkCallback;


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
        registerNetworkCallback();
        reload();
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        NetworkRequest req = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "Network available - scheduling SIP re-register");
                handler.removeCallbacks(reRegisterRunnable);
                handler.postDelayed(reRegisterRunnable, 2000);
            }
        };
        try { cm.registerNetworkCallback(req, networkCallback); }
        catch (Exception e) { Log.e(TAG, "registerNetworkCallback failed: " + e.getMessage()); }
    }

    private void reload() {
        SharedPreferences p = getSharedPreferences("sip_config", MODE_PRIVATE);
        host = p.getString("host", "103.82.193.58");
        port = p.getInt("port", 5060);
        user = p.getString("username", "3001");
        pass = p.getString("password", "");
        ext  = p.getString("bridge_ext", "1001");
        host = host != null ? host.trim() : "";
        user = user != null ? user.trim() : "";
        pass = pass != null ? pass.trim() : "";
        ext = ext != null ? ext.trim() : "1001";
        answerRings = Math.max(1, p.getInt("answer_rings", 1));
        isSipRegistered = false;
        bridgeInProgress = false;
        bridgeAttempts = 0;
        sipRetryCount = 0;
        handler.removeCallbacks(answerRunnable);
        handler.removeCallbacks(bridgeRunnable);
        if (sip != null) sip.destroy();

        if (host == null || host.trim().isEmpty()
                || user == null || user.trim().isEmpty()
                || pass == null || pass.trim().isEmpty()) {
            sip = null;
            updateNote("SIP config missing (host/user/password)");
            Log.w(TAG, "Skip SIP register: incomplete config");
            return;
        }

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
                case "ACTION_STOP":
                    Log.d(TAG, "Stop requested");
                    stopSelf();
                    return START_NOT_STICKY;
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
        // No hard limit — always retry with exponential back-off (cap 60s)
        if (sipRetryCount > 10) sipRetryCount = 10; // cap for delay calculation only
        long delayMs = Math.min(5000L * (1L << (sipRetryCount - 1)), 60000L);
        updateNote("Reg failed [" + LinphoneEngine.sipErrorName(errorCode) + "] retry " + sipRetryCount + "/" + MAX_SIP_RETRIES + " in " + (delayMs/1000) + "s");
        handler.postDelayed(() -> {
            if (sip != null) {
                Log.d(TAG, "Retrying SIP registration...");
                sip.register(host, port, user, pass);
            }
        }, delayMs);
    }

    /**
     * Asterisk gọi vào Android (3001) để yêu cầu gọi GSM ra ngoài (VD: 1001 bấm 0351234567)
     * Đối với luồng này:
     *   1001 -> Asterisk -> INVITE sip:0351234567@android -> app (3001) -> GSM 0351234567
     */
    @Override
    public void onSipIncomingCall(Call incomingCall, String dialedNumber) {
        Log.d(TAG, "Incoming SIP from Asterisk - outbound GSM to: " + dialedNumber);
        if (dialedNumber == null || dialedNumber.trim().isEmpty()) {
            Log.e(TAG, "onSipIncomingCall: no number, ignoring");
            return;
        }
        // Trả lời cuộc gọi SIP từ Asterisk
        if (sip != null) sip.answerIncomingCall(incomingCall);
        // Gọi GSM ra ngoài
        updateNote("Outbound GSM: " + dialedNumber);
        prepareAudio();
        try {
            Intent intent = new Intent(Intent.ACTION_CALL);
            intent.setData(Uri.parse("tel:" + dialedNumber.trim()));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.d(TAG, "GSM outbound call initiated: " + dialedNumber);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initiate GSM outbound call: " + e.getMessage());
        }
    }

    @Override public void onSipCallConnected() { updateNote("Bridge Active"); }

    @Override public void onSipCallEnded() {
        bridgeInProgress = false;
        bridgeAttempts = 0;
        // Kết thúc cuộc gọi GSM nếu đang hoạt động (dùng cho luồng gọi ra GSM)
        try {
            TelecomManager tm = getSystemService(TelecomManager.class);
            if (tm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tm.endCall();
            }
        } catch (Exception ignored) {}
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
        // NOTE: Linphone SDK (Android 12+ compatible) manages AudioManager internally.
        // We only set the mode to MODE_IN_COMMUNICATION here; Linphone handles the rest.
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);
            Log.d(TAG, "Audio mode set to IN_COMMUNICATION (Linphone manages audio focus)");
        }
    }

    private void resetAudio() {
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            // Linphone manages audio focus internally; just reset the mode
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
        handler.removeCallbacks(reRegisterRunnable);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null && networkCallback != null) {
            try { cm.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        }
        if (sip != null) { sip.hangup(); sip.destroy(); sip = null; }
        resetAudio();
        stopForeground(true);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
