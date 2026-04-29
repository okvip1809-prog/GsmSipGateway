package com.gsmsipgateway;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.sip.*;
import android.util.Log;

public class LinphoneEngine {
    private static final String TAG = "SipEngine";
    // FIX: Store context as a field so it can be referenced from nested methods
    private final Context ctx;
    private SipManager sipManager;
    private SipProfile localProfile;
    private SipAudioCall currentCall;
    private BridgeCallback callback;
    private String host, user;

    public interface BridgeCallback {
        void onSipRegistered();
        void onSipRegistrationFailed(int errorCode, String errorMessage);
        void onSipCallConnected();
        void onSipCallEnded();
    }

    /** Maps android.net.sip.SipErrorCode int values to human-readable names */
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
        // FIX: Save context field so it can be used in PendingIntent and elsewhere
        this.ctx = ctx.getApplicationContext();
        this.callback = cb;
        boolean voipOk = SipManager.isVoipSupported(ctx);
        boolean apiOk  = SipManager.isApiSupported(ctx);
        if (voipOk && apiOk) {
            sipManager = SipManager.newInstance(ctx);
            Log.d(TAG, "SipManager created successfully");
        } else {
            Log.e(TAG, "SIP NOT supported on this device: isVoipSupported=" + voipOk + " isApiSupported=" + apiOk);
            if (callback != null) callback.onSipRegistrationFailed(-1, "SIP API not supported on this device (isVoip=" + voipOk + ")");
        }
    }

    public void register(String host, int port, String user, String pass) {
        this.host = host;
        this.user = user;
        if (sipManager == null) {
            Log.e(TAG, "register skipped: SIP manager unavailable");
            if (callback != null) callback.onSipRegistrationFailed(-1, "SipManager is null");
            return;
        }
        try {
            if (localProfile != null) {
                try { sipManager.close(localProfile.getUriString()); } catch (Exception ignored) {}
            }
            SipProfile.Builder builder = new SipProfile.Builder(user, host);
            builder.setPassword(pass);
            // FIX: setAuthUserName is required by Asterisk/FreePBX PJSIP to authenticate correctly
            builder.setAuthUserName(user);
            builder.setPort(port);
            builder.setProtocol("UDP");
            // FIX: Removed builder.setOutboundProxy(host+":"+port) — mixing port into the proxy
            // domain string was malformed and caused Asterisk to reject the REGISTER request.
            // Port is already handled by setPort() above.
            builder.setAutoRegistration(true);
            localProfile = builder.build();
            Log.d(TAG, "SipProfile built: uri=" + localProfile.getUriString() + " host=" + host + " port=" + port + " user=" + user);

            Intent intent = new Intent();
            intent.setAction("android.SipDemo.INCOMING_CALL");
            // FIX: Use stored ctx field instead of null — passing null caused NullPointerException
            PendingIntent pi = PendingIntent.getBroadcast(
                this.ctx, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
            sipManager.open(localProfile, pi, null);
            sipManager.setRegistrationListener(localProfile.getUriString(),
                new SipRegistrationListener() {
                    @Override public void onRegistering(String localProfileUri) {
                        Log.d(TAG, "Registering...");
                    }
                    @Override public void onRegistrationDone(String localProfileUri, long expiryTime) {
                        Log.d(TAG, "Registered OK | uri=" + localProfileUri + " | expires=" + expiryTime);
                        if (callback != null) callback.onSipRegistered();
                    }
                    @Override public void onRegistrationFailed(String localProfileUri, int errorCode, String errorMessage) {
                        Log.e(TAG, "Registration FAILED | code=" + errorCode + " (" + sipErrorName(errorCode) + ") | msg=" + errorMessage + " | uri=" + localProfileUri);
                        if (callback != null) callback.onSipRegistrationFailed(errorCode, sipErrorName(errorCode) + ": " + errorMessage);
                    }
                });
        } catch (Exception e) {
            Log.e(TAG, "register exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (callback != null) callback.onSipRegistrationFailed(-2, "Exception: " + e.getMessage());
        }
    }

    public boolean callSip(String ext, String remoteHost, int remotePort) {
        if (sipManager == null || localProfile == null) {
            Log.e(TAG, "callSip skipped: SIP stack not ready");
            return false;
        }
        if (ext == null || ext.trim().isEmpty()) {
            Log.e(TAG, "callSip skipped: empty bridge extension");
            return false;
        }
        try {
            String cleanExt = ext.trim();
            String peerUri;
            if (cleanExt.startsWith("sip:")) {
                peerUri = cleanExt;
            } else if (cleanExt.contains("@")) {
                peerUri = "sip:" + cleanExt;
            } else {
                peerUri = "sip:" + cleanExt + "@" + remoteHost + ":" + remotePort;
            }
            Log.d(TAG, "Dialing peerUri=" + peerUri + " from " + localProfile.getUriString());
            currentCall = sipManager.makeAudioCall(
                localProfile.getUriString(),
                peerUri,
                new SipAudioCall.Listener() {
                    @Override public void onCallEstablished(SipAudioCall call) {
                        call.startAudio();
                        if (callback != null) callback.onSipCallConnected();
                    }
                    // FIX: Removed misaligned blank line before @Override
                    @Override public void onCallEnded(SipAudioCall call) {
                        if (callback != null) callback.onSipCallEnded();
                    }
                    @Override public void onError(SipAudioCall call, int errorCode, String errorMessage) {
                        Log.e(TAG, "Call error: " + errorMessage);
                        if (callback != null) callback.onSipCallEnded();
                    }
                }, 30
            );
            return currentCall != null;
        } catch (Exception e) {
            Log.e(TAG, "callSip error: " + e.getMessage());
            return false;
        }
    }

    public void hangup() {
        try {
            if (currentCall != null) {
                currentCall.endCall();
                currentCall.close();
                currentCall = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "hangup error: " + e.getMessage());
        }
    }

    public void destroy() {
        hangup();
        try {
            if (localProfile != null && sipManager != null) {
                sipManager.close(localProfile.getUriString());
            }
        } catch (Exception e) {
            Log.e(TAG, "destroy error: " + e.getMessage());
        }
    }
}
