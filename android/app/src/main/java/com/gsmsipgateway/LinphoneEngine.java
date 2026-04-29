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
    }

    public static String sipErrorName(int code) {
        switch (code) {
            case 0:  return "NO_ERROR";
            case 1:  return "NETWORK_ERROR";
            case 2:  return "INVALID_CREDENTIALS";
            case 3:  return "SERVER_ERROR";
            case 4:  return "IN_PROGRESS";
            case 5:  return "TIME_OUT (NAT?)";
            case 6:  return "CROSS_DOMAIN_AUTH";
            case 7:  return "CLIENT_ERROR";
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

            // Server: sip:host:port;transport=udp
            Address serverAddr = Factory.instance()
                    .createAddress("sip:" + host + ":" + port + ";transport=udp");
            if (serverAddr == null) throw new Exception("Invalid server address");
            params.setServerAddress(serverAddr);

            params.setRegisterEnabled(true);
            params.setExpires(60);
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
