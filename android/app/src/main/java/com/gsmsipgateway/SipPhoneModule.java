package com.gsmsipgateway;

import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

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
import org.linphone.core.RegistrationState;
import org.linphone.core.TransportType;

import java.lang.reflect.Method;

public class SipPhoneModule extends ReactContextBaseJavaModule {
    private static final String TAG = "SipPhoneModule";

    private final ReactApplicationContext ctx;
    private Core core;
    private Call currentCall;
    private String host = "";
    private int port = 5060;
    private String username = "";
    private boolean registered = false;

    public SipPhoneModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.ctx = reactContext;
    }

    @Override
    public String getName() {
        return "SipPhone";
    }

    private void emit(String eventName, WritableMap payload) {
        if (ctx.hasActiveReactInstance()) {
            ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit(eventName, payload);
        }
    }

    private void ensureCore() throws Exception {
        if (core != null) return;
        Factory factory = Factory.instance();
        factory.setDebugMode(false, TAG);
        core = factory.createCore(null, null, ctx);
        core.addListener(new CoreListenerStub() {
            @Override
            public void onAccountRegistrationStateChanged(Core c, Account account,
                                                          RegistrationState state, String message) {
                registered = state == RegistrationState.Ok;
                WritableMap map = Arguments.createMap();
                map.putString("state", state.toString());
                map.putString("message", message != null ? message : "");
                emit("SipPhoneRegistration", map);
            }

            @Override
            public void onCallStateChanged(Core c, Call call, Call.State state, String message) {
                WritableMap map = Arguments.createMap();
                map.putString("state", state.toString());
                map.putString("message", message != null ? message : "");

                Address remote = call != null ? call.getRemoteAddress() : null;
                String from = "";
                if (remote != null && remote.getUsername() != null) {
                    from = remote.getUsername();
                }
                map.putString("from", from);

                if (state == Call.State.IncomingReceived) {
                    currentCall = call;
                    emit("SipPhoneIncomingCall", map);
                } else if (state == Call.State.Connected || state == Call.State.StreamsRunning) {
                    currentCall = call;
                    emit("SipPhoneCall", map);
                } else if (state == Call.State.End || state == Call.State.Released || state == Call.State.Error) {
                    currentCall = null;
                    emit("SipPhoneCall", map);
                }
            }

        });
        core.start();
    }

    @ReactMethod
    public void registerAccount(String inHost, int inPort, String inUsername, String inPassword, Promise promise) {
        try {
            ensureCore();

            host = inHost != null ? inHost.trim() : "";
            port = inPort > 0 ? inPort : 5060;
            username = inUsername != null ? inUsername.trim() : "";
            String password = inPassword != null ? inPassword.trim() : "";

            if (host.isEmpty() || username.isEmpty() || password.isEmpty()) {
                throw new Exception("Missing host/username/password");
            }

            for (Account account : core.getAccountList()) {
                core.removeAccount(account);
            }
            core.clearAllAuthInfo();

            AuthInfo authInfo = Factory.instance().createAuthInfo(
                    username, username, password, null, null, host);
            core.addAuthInfo(authInfo);

            AccountParams params = core.createAccountParams();
            Address identity = Factory.instance().createAddress("sip:" + username + "@" + host);
            Address server = Factory.instance().createAddress("sip:" + host + ":" + port);
            if (identity == null || server == null) {
                throw new Exception("Invalid SIP address");
            }

            server.setTransport(TransportType.Udp);
            params.setIdentityAddress(identity);
            params.setServerAddress(server);
            params.setRegisterEnabled(true);
            params.setOutboundProxyEnabled(true);
            params.setExpires(3600);

            Account account = core.createAccount(params);
            core.addAccount(account);
            core.setDefaultAccount(account);
            core.start();

            promise.resolve("Register started");
        } catch (Exception e) {
            Log.e(TAG, "registerAccount failed: " + e.getMessage());
            promise.reject("REGISTER_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void makeCall(String target, Promise promise) {
        try {
            ensureCore();
            if (target == null || target.trim().isEmpty()) {
                throw new Exception("Target is empty");
            }

            String cleanTarget = target.trim();
            String uri;
            if (cleanTarget.startsWith("sip:")) {
                uri = cleanTarget;
            } else if (cleanTarget.contains("@")) {
                uri = "sip:" + cleanTarget;
            } else {
                uri = "sip:" + cleanTarget + "@" + host + ":" + port;
            }

            Address to = Factory.instance().createAddress(uri);
            if (to == null) throw new Exception("Invalid target uri");

            CallParams params = core.createCallParams(null);
            if (params != null) {
                params.setMediaEncryption(MediaEncryption.None);
            }
            Call call = core.inviteAddressWithParams(to, params);
            if (call == null) throw new Exception("Failed to create call");

            currentCall = call;
            promise.resolve("Calling " + uri);
        } catch (Exception e) {
            promise.reject("CALL_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void answerCall(Promise promise) {
        try {
            if (core == null || currentCall == null) {
                throw new Exception("No incoming call");
            }
            CallParams params = core.createCallParams(currentCall);
            if (params != null) {
                params.setMediaEncryption(MediaEncryption.None);
                currentCall.acceptWithParams(params);
            } else {
                currentCall.accept();
            }
            promise.resolve("Answered");
        } catch (Exception e) {
            promise.reject("ANSWER_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void hangup(Promise promise) {
        try {
            if (currentCall != null) {
                currentCall.terminate();
                currentCall = null;
            }
            promise.resolve("Hangup done");
        } catch (Exception e) {
            promise.reject("HANGUP_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void sendMessage(String toUser, String text, Promise promise) {
        try {
            ensureCore();
            if (toUser == null || toUser.trim().isEmpty()) throw new Exception("toUser is empty");
            if (text == null || text.trim().isEmpty()) throw new Exception("text is empty");

            Address peer = Factory.instance().createAddress("sip:" + toUser.trim() + "@" + host);
            if (peer == null) throw new Exception("Invalid peer address");

            Method getChatRoomMethod = core.getClass().getMethod("getChatRoom", Address.class);
            Object room = getChatRoomMethod.invoke(core, peer);
            if (room == null) throw new Exception("Unable to get chat room");

            Object message;
            try {
                Method createMessageUtf8 = room.getClass().getMethod("createMessageFromUtf8", String.class);
                message = createMessageUtf8.invoke(room, text.trim());
            } catch (NoSuchMethodException ex) {
                Method createMessage = room.getClass().getMethod("createMessage", String.class);
                message = createMessage.invoke(room, text.trim());
            }

            Method sendMethod = message.getClass().getMethod("send");
            sendMethod.invoke(message);

            WritableMap map = Arguments.createMap();
            map.putString("from", username);
            map.putString("to", toUser.trim());
            map.putString("text", text.trim());
            emit("SipPhoneMessageSent", map);

            promise.resolve("Message sent");
        } catch (Exception e) {
            promise.reject("MESSAGE_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void getStatus(Promise promise) {
        WritableMap map = Arguments.createMap();
        map.putBoolean("registered", registered);
        map.putBoolean("inCall", currentCall != null);
        map.putString("host", host);
        map.putInt("port", port);
        map.putString("username", username);
        promise.resolve(map);
    }

    @ReactMethod
    public void unregister(Promise promise) {
        try {
            if (core != null) {
                if (currentCall != null) {
                    currentCall.terminate();
                    currentCall = null;
                }
                core.stop();
                core = null;
            }
            registered = false;
            promise.resolve("Unregistered");
        } catch (Exception e) {
            promise.reject("UNREGISTER_FAILED", e.getMessage());
        }
    }
}
