package com.gsmsipgateway;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.linphone.core.Account;
import org.linphone.core.AccountParams;
import org.linphone.core.Address;
import org.linphone.core.AuthInfo;
import org.linphone.core.Call;
import org.linphone.core.CallParams;
import org.linphone.core.Core;
import org.linphone.core.CoreListenerStub;
import org.linphone.core.Factory;
import org.linphone.core.MediaEncryption;
import org.linphone.core.Reason;
import org.linphone.core.RegistrationState;
import org.linphone.core.TransportType;

/**
 * SIP Engine using Linphone SDK 5.x
 * Replaces android.net.sip which was removed on Android 12+
 */
public class LinphoneEngine {
    private static final String TAG = "SipEngine";

    private final Context ctx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Core core;
    private BridgeCallback callback;
    private Call currentCall;
    private String host;
    private int port;
    private String user;

    public interface BridgeCallback {
        void onSipRegistered();
        void onSipRegistrationFailed(int errorCode, String errorMessage);
        void onSipCallConnected();
        void onSipCallEnded();
        /** Gọi khi Asterisk gọi vào app (3001) yêu cầu gọi GSM ra ngoài */
        void onSipIncomingCall(Call call, String dialedNumber);
    }

    public static String sipErrorName(int code) {
        switch (code) {
            case 0:  return "NONE";
            case 1:  return "NO_RESPONSE (network/unreachable)";
            case 2:  return "FORBIDDEN (403 - wrong credentials/IP denied)";
            case 3:  return "DECLINED (603)";
            case 4:  return "NOT_FOUND (404)";
            case 5:  return "NOT_ANSWERED";
            case 6:  return "BUSY (486)";
            case 7:  return "UNSUPPORTED_CONTENT";
            case 8:  return "BAD_EVENT";
            case 9:  return "IO_ERROR";
            case 10: return "DO_NOT_DISTURB";
            case 11: return "UNAUTHORIZED (401 - wrong password)";
            case 12: return "NOT_ACCEPTABLE (406)";
            case 13: return "NO_MATCH";
            case 14: return "MOVED_PERMANENTLY (301)";
            case 15: return "GONE (410)";
            case 16: return "TEMPORARILY_UNAVAILABLE (480)";
            case 17: return "ADDRESS_INCOMPLETE (484)";
            case 18: return "NOT_IMPLEMENTED (501)";
            case 19: return "BAD_GATEWAY (502)";
            case 20: return "SESSION_INTERVAL_TOO_SMALL";
            case 21: return "SERVER_TIMEOUT (504 - NAT/firewall?)";
            default: return "UNKNOWN(" + code + ")";
        }
    }

    public LinphoneEngine(Context ctx, BridgeCallback cb) {
        this.ctx = ctx.getApplicationContext();
        this.callback = cb;
        try {
            Factory factory = Factory.instance();
            factory.setDebugMode(false, TAG);
            core = factory.createCore(null, null, this.ctx);
            setupListeners();
            Log.d(TAG, "Linphone Core created successfully (Android 12+ compatible)");
        } catch (Exception e) {
            Log.e(TAG, "Linphone Core init failed: " + e.getMessage());
            if (callback != null) {
                callback.onSipRegistrationFailed(-1, "Core init failed: " + e.getMessage());
            }
        }
    }

    private void setupListeners() {
        core.addListener(new CoreListenerStub() {
            @Override
            public void onAccountRegistrationStateChanged(Core core, Account account,
                    RegistrationState state, String message) {
                Log.d(TAG, "Registration state: " + state + " | " + message);
                if (state == RegistrationState.Ok) {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onSipRegistered();
                    });
                } else if (state == RegistrationState.Failed) {
                    Reason reason = account.getError() != null ? account.getError() : Reason.Unknown;
                    int code = reason.toInt();
                    String errMsg = sipErrorName(code) + ": " + message;
                    Log.e(TAG, "Registration FAILED | code=" + code + " | " + errMsg);
                    mainHandler.post(() -> {
                        if (callback != null) callback.onSipRegistrationFailed(code, errMsg);
                    });
                }
            }

            @Override
            public void onCallStateChanged(Core core, Call call, Call.State state, String message) {
                Log.d(TAG, "Call state: " + state + " | " + message);
                switch (state) {
                    case IncomingReceived:
                        currentCall = call;
                        // Lấy số điện thoại cần gọi GSM từ To-header của INVITE
                        String dialedNumber = "";
                        try {
                            Address toAddr = call.getToAddress();
                            if (toAddr != null) dialedNumber = toAddr.getUsername();
                        } catch (Exception e) {
                            Log.w(TAG, "getToAddress failed, fallback to remote: " + e.getMessage());
                        }
                        if (dialedNumber == null || dialedNumber.isEmpty()) {
                            dialedNumber = call.getRemoteAddress().getUsername();
                        }
                        if (dialedNumber == null) dialedNumber = "";
                        final String finalNum = dialedNumber;
                        final Call finalCall = call;
                        mainHandler.post(() -> {
                            if (callback != null) callback.onSipIncomingCall(finalCall, finalNum);
                        });
                        break;
                    case Connected:
                    case StreamsRunning:
                        currentCall = call;
                        mainHandler.post(() -> {
                            if (callback != null) callback.onSipCallConnected();
                        });
                        break;
                    case End:
                    case Released:
                    case Error:
                        currentCall = null;
                        mainHandler.post(() -> {
                            if (callback != null) callback.onSipCallEnded();
                        });
                        break;
                    default:
                        break;
                }
            }
        });
    }

    public void register(String host, int port, String user, String pass) {
        this.host = host;
        this.port = port;
        this.user = user;

        if (core == null) {
            Log.e(TAG, "register skipped: core is null");
            if (callback != null) callback.onSipRegistrationFailed(-1, "Core not initialized");
            return;
        }

        try {
            // Remove existing accounts and auth info
            for (Account acc : core.getAccountList()) {
                core.removeAccount(acc);
            }
            core.clearAllAuthInfo();

            // Add authentication info
            AuthInfo authInfo = Factory.instance().createAuthInfo(
                user, user, pass, null, null, host);
            core.addAuthInfo(authInfo);

            // Build account params
            AccountParams params = core.createAccountParams();

            // Identity: sip:user@host
            Address identity = Factory.instance().createAddress("sip:" + user + "@" + host);
            if (identity == null) throw new Exception("Invalid identity address");
            params.setIdentityAddress(identity);

            // Server address with explicit UDP transport
            Address serverAddr = Factory.instance()
                    .createAddress("sip:" + host + ":" + port);
            if (serverAddr == null) throw new Exception("Invalid server address");
            serverAddr.setTransport(TransportType.Udp);
            params.setServerAddress(serverAddr);
            params.setOutboundProxyEnabled(true);

            params.setRegisterEnabled(true);
            params.setExpires(3600);
            params.setPublishEnabled(false);

            Account account = core.createAccount(params);
            core.addAccount(account);
            core.setDefaultAccount(account);

            // Start core (safe to call even if already running in Linphone 5.x)
            core.start();

            Log.d(TAG, "SIP register initiated: sip:" + user + "@" + host + ":" + port);
        } catch (Exception e) {
            Log.e(TAG, "register exception: " + e.getMessage());
            if (callback != null) callback.onSipRegistrationFailed(-2, "Exception: " + e.getMessage());
        }
    }

    public boolean callSip(String ext, String remoteHost, int remotePort) {
        if (core == null) {
            Log.e(TAG, "callSip skipped: core is null");
            return false;
        }
        if (ext == null || ext.trim().isEmpty()) {
            Log.e(TAG, "callSip skipped: empty extension");
            return false;
        }

        try {
            String cleanExt = ext.trim();
            String uri;
            if (cleanExt.startsWith("sip:")) {
                uri = cleanExt;
            } else if (cleanExt.contains("@")) {
                uri = "sip:" + cleanExt;
            } else {
                uri = "sip:" + cleanExt + "@" + remoteHost + ":" + remotePort;
            }

            Log.d(TAG, "Dialing: " + uri);
            Address addr = Factory.instance().createAddress(uri);
            if (addr == null) { Log.e(TAG, "Invalid address: " + uri); return false; }

            CallParams callParams = core.createCallParams(null);
            if (callParams == null) return false;
            callParams.setMediaEncryption(MediaEncryption.None);

            Call call = core.inviteAddressWithParams(addr, callParams);
            return call != null;
        } catch (Exception e) {
            Log.e(TAG, "callSip error: " + e.getMessage());
            return false;
        }
    }

    /** Trả lời incoming SIP call (từ Asterisk yêu cầu gọi GSM ra) */
    public boolean answerIncomingCall(Call call) {
        if (core == null || call == null) return false;
        try {
            CallParams params = core.createCallParams(call);
            if (params != null) {
                params.setMediaEncryption(MediaEncryption.None);
                call.acceptWithParams(params);
            } else {
                call.accept();
            }
            Log.d(TAG, "Answered incoming SIP call");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "answerIncomingCall failed: " + e.getMessage());
            return false;
        }
    }

    public void hangup() {
        try {
            if (currentCall != null) {
                currentCall.terminate();
                currentCall = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "hangup error: " + e.getMessage());
        }
    }

    public void destroy() {
        hangup();
        try {
            if (core != null) {
                core.stop();
            }
        } catch (Exception e) {
            Log.e(TAG, "destroy error: " + e.getMessage());
        }
        core = null;
    }
}
