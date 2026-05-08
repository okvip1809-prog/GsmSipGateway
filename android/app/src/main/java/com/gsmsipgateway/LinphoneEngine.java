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
    private String pass;
    private TransportType preferredTransport = TransportType.Udp;
    private boolean pushCompatibility = false;

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

            // core.iterate() must be called regularly on the main thread so Linphone
            // dispatches SIP/call-state events (registration, INVITE, BYE, etc.)
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    if (core != null) core.iterate();
                    mainHandler.postDelayed(this, 20);
                }
            });

            Log.d(TAG, "Linphone Core created (iterate started)");
        } catch (Exception e) {
            Log.e(TAG, "Linphone Core init failed: " + e.getMessage());
            if (callback != null) {
                callback.onSipRegistrationFailed(-1, "Core init failed: " + e.getMessage());
            }
        }
    }

    private TransportType parseTransport(String value) {
        if (value == null) return TransportType.Udp;
        switch (value.trim().toLowerCase()) {
            case "tcp":
                return TransportType.Tcp;
            case "tls":
                return TransportType.Tls;
            default:
                return TransportType.Udp;
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

                    // Retry registration over TCP once when UDP path is blocked by network/NAT.
                    if ((code == 1 || code == 9 || code == 21)
                            && preferredTransport == TransportType.Udp
                            && host != null && !host.isEmpty()
                            && user != null && !user.isEmpty()
                            && pass != null && !pass.isEmpty()) {
                        preferredTransport = TransportType.Tcp;
                        Log.w(TAG, "Retrying register with TCP transport after UDP network error");
                        register(host, port, user, pass,
                            preferredTransport == TransportType.Tls ? "tls"
                                : (preferredTransport == TransportType.Tcp ? "tcp" : "udp"),
                            pushCompatibility,
                            "");
                        return;
                    }

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

    public void register(String host, int port, String user, String pass,
                         String transport, boolean pushEnabled, String pushToken) {
        String cleanHost = host == null ? "" : host.trim();
        if (cleanHost.startsWith("sip:")) {
            cleanHost = cleanHost.substring(4);
        }
        int atIdx = cleanHost.indexOf('@');
        if (atIdx >= 0 && atIdx + 1 < cleanHost.length()) {
            cleanHost = cleanHost.substring(atIdx + 1);
        }
        int semiIdx = cleanHost.indexOf(';');
        if (semiIdx > 0) {
            cleanHost = cleanHost.substring(0, semiIdx);
        }
        String cleanUser = user == null ? "" : user.trim();
        String cleanPass = pass == null ? "" : pass.trim();
        int safePort = port > 0 ? port : 5060;

        this.host = cleanHost;
        this.port = safePort;
        this.user = cleanUser;
        this.pass = cleanPass;
        this.preferredTransport = parseTransport(transport);
        this.pushCompatibility = pushEnabled;

        if (core == null) {
            Log.e(TAG, "register skipped: core is null");
            if (callback != null) callback.onSipRegistrationFailed(-1, "Core not initialized");
            return;
        }

        if (cleanHost.isEmpty() || cleanUser.isEmpty() || cleanPass.isEmpty()) {
            Log.e(TAG, "register skipped: missing host/user/pass");
            if (callback != null) callback.onSipRegistrationFailed(-3, "Missing host/username/password");
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
                cleanUser, cleanUser, cleanPass, null, null, cleanHost);
            core.addAuthInfo(authInfo);

            // Build account params
            AccountParams params = core.createAccountParams();

            // Identity: sip:user@host
            Address identity = Factory.instance().createAddress("sip:" + cleanUser + "@" + cleanHost);
            if (identity == null) throw new Exception("Invalid identity address");
            params.setIdentityAddress(identity);

            Address serverAddr = Factory.instance().createAddress("sip:" + cleanHost + ":" + safePort);
            if (serverAddr == null) throw new Exception("Invalid server address");
            serverAddr.setTransport(preferredTransport);
            params.setServerAddress(serverAddr);
            params.setOutboundProxyEnabled(true);

            params.setRegisterEnabled(true);
            // Push compatibility mode keeps REGISTER refresh shorter for mobile NAT/idle cases.
            params.setExpires(pushCompatibility ? 600 : 3600);
            params.setPublishEnabled(false);

            Account account = core.createAccount(params);
            core.addAccount(account);
            core.setDefaultAccount(account);

            // Start core (safe to call even if already running in Linphone 5.x)
            core.start();

            Log.d(TAG, "SIP register initiated: sip:" + cleanUser + "@" + cleanHost + ":" + safePort
                + " via " + preferredTransport
                + (pushCompatibility ? " (push compatibility on)" : ""));
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
