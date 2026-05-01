package com.gsmsipgateway;

import android.util.Log;
import org.linphone.core.Account;
import org.linphone.core.AccountParams;
import org.linphone.core.Address;
import org.linphone.core.AuthInfo;
import org.linphone.core.Call;
import org.linphone.core.Core;
import org.linphone.core.CoreListenerStub;
import org.linphone.core.Factory;
import org.linphone.core.MediaEncryption;
import org.linphone.core.PayloadType;
import org.linphone.core.RegistrationState;
import org.linphone.core.StreamType;
import org.linphone.core.TransportType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Quản lý 2 tài khoản SIP cho Dual SIM
 * SIM1 -> Account 1001
 * SIM2 -> Account 1002
 */
public class SipAccountManager {
    private static final String TAG = "SipAccountMgr";
    
    public static final int ACCOUNT_SIM1 = 0; // SIP 1001
    public static final int ACCOUNT_SIM2 = 1; // SIP 1002
    
    private Core core;
    private Map<Integer, SipAccount> accounts = new HashMap<>();
    private DualSipCallback callback;
    private CoreListenerStub coreListener;

    public interface DualSipCallback {
        void onAccountRegistered(int simSlot, String username);
        void onAccountRegistrationFailed(int simSlot, int errorCode, String errorMessage);
        void onAccountCallConnected(int simSlot);
        void onAccountCallEnded(int simSlot);
        void onIncomingCall(int simSlot, Call call, String dialedNumber);
    }

    public static class SipAccount {
        public int simSlot;
        public String host;
        public int port;
        public String username;
        public String password;
        public String extension; // extension để gọi khi có cuộc gọi GSM
        public boolean registered;
        public Call currentCall;
        public Account account;

        public SipAccount(int simSlot, String host, int port, String username, String password, String extension) {
            this.simSlot = simSlot;
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.extension = extension;
            this.registered = false;
            this.currentCall = null;
            this.account = null;
        }
    }

    public SipAccountManager(Core core, DualSipCallback callback) {
        this.core = core;
        this.callback = callback;
        initializeAccounts();
        attachCoreListener();
    }

    private void attachCoreListener() {
        if (core == null) return;

        coreListener = new CoreListenerStub() {
            @Override
            public void onAccountRegistrationStateChanged(Core core, Account account,
                    RegistrationState state, String message) {
                handleRegistrationStateChanged(account, state, message);
            }

            @Override
            public void onCallStateChanged(Core core, Call call, Call.State state, String message) {
                handleCallStateChanged(call, state, message);
            }
        };
        core.addListener(coreListener);
    }

    private void initializeAccounts() {
        // Khởi tạo 2 tài khoản SIP (mặc định)
        accounts.put(ACCOUNT_SIM1, new SipAccount(ACCOUNT_SIM1, "", 5060, "1001", "", "1001"));
        accounts.put(ACCOUNT_SIM2, new SipAccount(ACCOUNT_SIM2, "", 5060, "1002", "", "1002"));
    }

    /**
     * Cấu hình tài khoản SIP cho một SIM slot
     */
    public void configureAccount(int simSlot, String host, int port, String username, String password, String extension) {
        if (!accounts.containsKey(simSlot)) {
            Log.e(TAG, "Invalid sim slot: " + simSlot);
            return;
        }
        
        SipAccount acc = accounts.get(simSlot);
        acc.host = host != null ? host.trim() : "";
        acc.port = port;
        acc.username = username != null ? username.trim() : "";
        acc.password = password != null ? password.trim() : "";
        acc.extension = extension != null ? extension.trim() : "";
        
        Log.d(TAG, "Account " + simSlot + " configured: " + acc.username + "@" + acc.host + ":" + acc.port);
    }

    /**
     * Đăng ký cả 2 tài khoản SIP
     */
    public void registerAll() {
        if (core == null) {
            Log.e(TAG, "registerAll: core is null");
            return;
        }

        try {
            // Xóa tất cả tài khoản cũ
            for (Account acc : core.getAccountList()) {
                core.removeAccount(acc);
            }
            core.clearAllAuthInfo();

            // Đăng ký từng tài khoản
            for (SipAccount sipAcc : accounts.values()) {
                if (sipAcc.host.isEmpty() || sipAcc.username.isEmpty() || sipAcc.password.isEmpty()) {
                    Log.w(TAG, "Skip account " + sipAcc.simSlot + ": incomplete config");
                    continue;
                }
                sipAcc.registered = false;
                sipAcc.currentCall = null;
                registerAccount(sipAcc);
            }

            // core.start() is called once in GsmSipBridgeService.onCreate();
            // do not call it here to avoid resetting the iterate loop.
        } catch (Exception e) {
            Log.e(TAG, "registerAll failed: " + e.getMessage());
        }
    }

    private void registerAccount(SipAccount sipAcc) {
        try {
            // Thêm auth info
            AuthInfo authInfo = Factory.instance().createAuthInfo(
                sipAcc.username, sipAcc.username, sipAcc.password, null, null, sipAcc.host);
            core.addAuthInfo(authInfo);

            // Tạo account params
            AccountParams params = core.createAccountParams();

            // Identity: sip:username@host
            Address identity = Factory.instance().createAddress("sip:" + sipAcc.username + "@" + sipAcc.host);
            if (identity == null) throw new Exception("Invalid identity address");
            params.setIdentityAddress(identity);

            // Server address
            Address serverAddr = Factory.instance()
                    .createAddress("sip:" + sipAcc.host + ":" + sipAcc.port);
            if (serverAddr == null) throw new Exception("Invalid server address");
            serverAddr.setTransport(TransportType.Udp);
            params.setServerAddress(serverAddr);
            params.setOutboundProxyEnabled(true);

            params.setRegisterEnabled(true);
            params.setExpires(3600);
            params.setPublishEnabled(false);

            // Tạo account
            Account account = core.createAccount(params);
            if (account == null) throw new Exception("createAccount returned null");

            sipAcc.account = account;
            core.addAccount(account);

            Log.d(TAG, "Account registered: " + sipAcc.username + "@" + sipAcc.host + " (slot=" + sipAcc.simSlot + ")");
        } catch (Exception e) {
            Log.e(TAG, "registerAccount failed for " + sipAcc.username + ": " + e.getMessage());
            if (callback != null) {
                callback.onAccountRegistrationFailed(sipAcc.simSlot, -1, e.getMessage());
            }
        }
    }

    /**
     * Gọi SIP extension từ một tài khoản
     */
    public boolean callExtension(int simSlot, String extensionOrNumber) {
        SipAccount sipAcc = accounts.get(simSlot);
        if (sipAcc == null) {
            Log.e(TAG, "callExtension: account not found for slot " + simSlot);
            return false;
        }

        if (!sipAcc.registered) {
            Log.e(TAG, "callExtension: account " + simSlot + " not registered");
            return false;
        }

        if (sipAcc.currentCall != null) {
            Log.w(TAG, "callExtension: account " + simSlot + " already has active call");
            return false;
        }

        try {
            String dest = extensionOrNumber;
            if (!dest.contains("@")) {
                dest = "sip:" + dest + "@" + sipAcc.host + ":" + sipAcc.port;
            } else if (!dest.startsWith("sip:")) {
                dest = "sip:" + dest;
            }

            Address toAddr = Factory.instance().createAddress(dest);
            if (toAddr == null) throw new Exception("Invalid destination address");

            // Set outgoing account
            core.setDefaultAccount(sipAcc.account);

            // Gọi
            Call call = core.inviteAddress(toAddr);
            if (call != null) {
                sipAcc.currentCall = call;
                Log.d(TAG, "Calling " + dest + " from account " + simSlot);
                return true;
            } else {
                Log.e(TAG, "inviteAddress returned null");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "callExtension failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Trả lời cuộc gọi SIP
     */
    public void answerCall(int simSlot) {
        SipAccount sipAcc = accounts.get(simSlot);
        if (sipAcc == null || sipAcc.currentCall == null) {
            Log.e(TAG, "answerCall: no call to answer for slot " + simSlot);
            return;
        }

        try {
            core.setDefaultAccount(sipAcc.account);
            sipAcc.currentCall.accept();
            Log.d(TAG, "Call answered for account " + simSlot);
        } catch (Exception e) {
            Log.e(TAG, "answerCall failed: " + e.getMessage());
        }
    }

    /**
     * Kết thúc cuộc gọi
     */
    public void hangup(int simSlot) {
        SipAccount sipAcc = accounts.get(simSlot);
        if (sipAcc == null || sipAcc.currentCall == null) {
            Log.w(TAG, "hangup: no call for account " + simSlot);
            return;
        }

        try {
            sipAcc.currentCall.terminate();
            sipAcc.currentCall = null;
            Log.d(TAG, "Call terminated for account " + simSlot);
        } catch (Exception e) {
            Log.e(TAG, "hangup failed: " + e.getMessage());
        }
    }

    /**
     * Kết thúc cả 2 cuộc gọi
     */
    public void hangupAll() {
        for (SipAccount sipAcc : accounts.values()) {
            hangup(sipAcc.simSlot);
        }
    }

    /**
     * Xử lý sự kiện đăng ký
     */
    public void handleRegistrationStateChanged(Account account, RegistrationState state, String message) {
        int simSlot = -1;
        String username = "";

        for (SipAccount sipAcc : accounts.values()) {
            if (sipAcc.account == account) {
                simSlot = sipAcc.simSlot;
                username = sipAcc.username;
                break;
            }
        }

        if (simSlot == -1) {
            Log.w(TAG, "handleRegistrationStateChanged: account not found");
            return;
        }

        SipAccount sipAcc = accounts.get(simSlot);
        Log.d(TAG, "Registration state [slot=" + simSlot + "]: " + state + " | " + message);

        if (state == RegistrationState.Ok) {
            sipAcc.registered = true;
            if (callback != null) callback.onAccountRegistered(simSlot, username);
        } else if (state == RegistrationState.Failed) {
            sipAcc.registered = false;
            // Lấy error code
            int errorCode = -1;
            if (account.getError() != null) {
                errorCode = account.getError().toInt();
            }
            if (callback != null) callback.onAccountRegistrationFailed(simSlot, errorCode, message);
        }
    }

    /**
     * Xử lý sự kiện cuộc gọi
     */
    public void handleCallStateChanged(Call call, Call.State state, String message) {
        int simSlot = -1;

        for (SipAccount sipAcc : accounts.values()) {
            if (sipAcc.currentCall == call) {
                simSlot = sipAcc.simSlot;
                break;
            }
        }

        // Incoming calls may not be tracked yet, map by account.
        if (simSlot == -1 && call != null) {
            Account callAccount = call.getAccount();
            if (callAccount != null) {
                for (SipAccount sipAcc : accounts.values()) {
                    if (sipAcc.account == callAccount) {
                        simSlot = sipAcc.simSlot;
                        break;
                    }
                }
            }
        }

        if (simSlot == -1) {
            Log.w(TAG, "handleCallStateChanged: call not tracked");
            return;
        }

        SipAccount sipAcc = accounts.get(simSlot);
        Log.d(TAG, "Call state [slot=" + simSlot + "]: " + state + " | " + message);

        switch (state) {
            case IncomingReceived:
                sipAcc.currentCall = call;
                String dialedNumber = "";
                try {
                    Address toAddr = call.getToAddress();
                    if (toAddr != null) dialedNumber = toAddr.getUsername();
                    if (dialedNumber == null || dialedNumber.isEmpty()) {
                        dialedNumber = call.getRemoteAddress().getUsername();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error getting dialed number: " + e.getMessage());
                }
                Log.i(TAG, "[RTP] INCOMING slot=" + simSlot
                    + " from=" + call.getRemoteAddress().asString()
                    + " to=" + dialedNumber);
                logAudioCodecs(call, simSlot, "INVITE");
                if (callback != null) callback.onIncomingCall(simSlot, call, dialedNumber);
                break;
            case Connected:
                sipAcc.currentCall = call;
                Log.i(TAG, "[RTP] CONNECTED slot=" + simSlot);
                logAudioCodecs(call, simSlot, "Connected");
                if (callback != null) callback.onAccountCallConnected(simSlot);
                break;
            case StreamsRunning:
                sipAcc.currentCall = call;
                Log.i(TAG, "[RTP] STREAMS_RUNNING slot=" + simSlot
                    + " audioDir=" + call.getAudioStats().getIceState());
                logAudioStats(call, simSlot);
                if (callback != null) callback.onAccountCallConnected(simSlot);
                break;
            case End:
            case Released:
            case Error:
                Log.i(TAG, "[RTP] CALL_END slot=" + simSlot + " reason=" + message);
                if (call != null) logAudioStats(call, simSlot);
                sipAcc.currentCall = null;
                if (callback != null) callback.onAccountCallEnded(simSlot);
                break;
            default:
                break;
        }
    }

    /** Log codec đã negotiate tại thời điểm SDP */
    private void logAudioCodecs(Call call, int slot, String phase) {
        try {
            org.linphone.core.CallParams currentParams = call.getCurrentParams();
            if (currentParams == null) {
                Log.w(TAG, "[RTP] " + phase + " slot=" + slot + " params=null (SDP not yet exchanged)");
                return;
            }
            PayloadType pt = currentParams.getUsedAudioPayloadType();
            if (pt != null) {
                Log.i(TAG, "[RTP] " + phase + " slot=" + slot
                    + " audioCodec=" + pt.getMimeType()
                    + "/" + pt.getClockRate()
                    + " enc=" + currentParams.getMediaEncryption());
            } else {
                Log.w(TAG, "[RTP] " + phase + " slot=" + slot + " audioCodec=null — no audio stream negotiated");
            }
        } catch (Exception e) {
            Log.w(TAG, "[RTP] logAudioCodecs error: " + e.getMessage());
        }
    }

    /** Log RTP statistics: jitter, lost packets, ice state */
    private void logAudioStats(Call call, int slot) {
        try {
            org.linphone.core.CallStats stats = call.getAudioStats();
            if (stats == null) {
                Log.w(TAG, "[RTP] audioStats=null slot=" + slot);
                return;
            }
            Log.i(TAG, "[RTP] STATS slot=" + slot
                + " iceState=" + stats.getIceState()
                + " sndPayload=" + stats.getSenderPayloadType()
                + " rcvPayload=" + stats.getReceiverPayloadType()
                + " jitter=" + String.format("%.1f", stats.getJitterBufferSizeMs()) + "ms"
                + " lostSnd=" + String.format("%.1f%%", stats.getSenderLossRate())
                + " lostRcv=" + String.format("%.1f%%", stats.getReceiverLossRate())
                + " localAddr=" + stats.getLocalAddress()
                + " remoteAddr=" + stats.getRemoteAddress());
        } catch (Exception e) {
            Log.w(TAG, "[RTP] logAudioStats error: " + e.getMessage());
        }
    }

    public SipAccount getAccount(int simSlot) {
        return accounts.get(simSlot);
    }

    public boolean isAccountRegistered(int simSlot) {
        SipAccount acc = accounts.get(simSlot);
        return acc != null && acc.registered;
    }

    public void destroy() {
        hangupAll();
        if (core != null && coreListener != null) {
            core.removeListener(coreListener);
        }
        accounts.clear();
        coreListener = null;
        core = null;
        callback = null;
    }
}
