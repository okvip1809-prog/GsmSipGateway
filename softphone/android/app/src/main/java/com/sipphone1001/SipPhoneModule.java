package com.sipphone1001;

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
    public String getName() { return "SipPhone"; }

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
                String from = "";
                try {
                    Address remote = call.getRemoteAddress();
                    if (remote != null && remote.getUsername() != null) from = remote.getUsername();
                } catch (Exception ignored) {}
                map.putString("from", from);

                if (state == Call.State.IncomingReceived) {
                    currentCall = call;
                    emit("SipPhoneIncoming", map);
                } else if (state == Call.State.Connected || state == Call.State.StreamsRunning) {
                    currentCall = call;
                    emit("SipPhoneCallState", map);
                } else if (state == Call.State.End || state == Call.State.Released || state == Call.State.Error) {
                    currentCall = null;
                    emit("SipPhoneCallState", map);
                }
            }

            @Override
            public void onMessageReceived(Core c, org.linphone.core.ChatRoom room,
                    org.linphone.core.ChatMessage message) {
                WritableMap map = Arguments.createMap();
                String from = "";
                String text = "";
                try {
                    if (message.getFromAddress() != null) from = message.getFromAddress().getUsername();
                    if (message.getUtf8Text() != null) text = message.getUtf8Text();
                } catch (Exception ignored) {}
                map.putString("from", from != null ? from : "");
                map.putString("text", text);
                emit("SipPhoneMessage", map);
            }
        });
        core.start();
    }

    @ReactMethod
    public void register(String inHost, int inPort, String inUser, String inPass, Promise promise) {
        try {
            ensureCore();
            host = inHost != null ? inHost.trim() : "";
            port = inPort > 0 ? inPort : 5060;
            username = inUser != null ? inUser.trim() : "";
            String password = inPass != null ? inPass.trim() : "";
            if (host.isEmpty() || username.isEmpty() || password.isEmpty()) {
                throw new Exception("Missing host/username/password");
            }
            for (Account acc : core.getAccountList()) core.removeAccount(acc);
            core.clearAllAuthInfo();

            AuthInfo authInfo = Factory.instance().createAuthInfo(username, username, password, null, null, host);
            core.addAuthInfo(authInfo);

            AccountParams params = core.createAccountParams();
            Address identity = Factory.instance().createAddress("sip:" + username + "@" + host);
            Address server = Factory.instance().createAddress("sip:" + host + ":" + port);
            if (identity == null || server == null) throw new Exception("Invalid SIP address");

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

            promise.resolve("Registering " + username + "@" + host);
        } catch (Exception e) {
            Log.e(TAG, "register failed: " + e.getMessage());
            promise.reject("REGISTER_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void call(String target, Promise promise) {
        try {
            ensureCore();
            if (target == null || target.trim().isEmpty()) throw new Exception("Empty target");
            String clean = target.trim();
            String uri = clean.startsWith("sip:") ? clean
                    : clean.contains("@") ? "sip:" + clean
                    : "sip:" + clean + "@" + host + ":" + port;

            Address to = Factory.instance().createAddress(uri);
            if (to == null) throw new Exception("Invalid address: " + uri);

            CallParams params = core.createCallParams(null);
            if (params != null) params.setMediaEncryption(MediaEncryption.None);

            Call c = core.inviteAddressWithParams(to, params);
            if (c == null) throw new Exception("Failed to create call");
            currentCall = c;
            promise.resolve("Calling " + uri);
        } catch (Exception e) {
            promise.reject("CALL_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void answer(Promise promise) {
        try {
            if (currentCall == null) throw new Exception("No incoming call");
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
            if (currentCall != null) { currentCall.terminate(); currentCall = null; }
            promise.resolve("Hangup OK");
        } catch (Exception e) {
            promise.reject("HANGUP_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void sendMessage(String toUser, String text, Promise promise) {
        try {
            ensureCore();
            if (toUser == null || toUser.trim().isEmpty()) throw new Exception("Empty recipient");
            if (text == null || text.trim().isEmpty()) throw new Exception("Empty message");
            if (!registered || host == null || host.trim().isEmpty()) throw new Exception("Not logged in to SIP account");

            String cleanTo = toUser.trim();
            String uri = cleanTo.startsWith("sip:") ? cleanTo
                    : cleanTo.contains("@") ? "sip:" + cleanTo
                    : "sip:" + cleanTo + "@" + host + ":" + port;

            Address peer = Factory.instance().createAddress(uri);
            if (peer == null) throw new Exception("Invalid peer address");

            Object room;
            try {
                Method m = core.getClass().getMethod("getChatRoom", Address.class);
                room = m.invoke(core, peer);
            } catch (Exception ex) {
                room = null;
            }
            if (room == null) {
                try {
                    Method m = core.getClass().getMethod("getOrCreateBasicChatRoom", Address.class);
                    room = m.invoke(core, peer);
                } catch (Exception ignored) {
                    room = null;
                }
            }
            if (room == null) {
                try {
                    Method m = core.getClass().getMethod("getChatRoomFromUri", String.class);
                    room = m.invoke(core, uri);
                } catch (Exception ignored) {
                    room = null;
                }
            }
            if (room == null) throw new Exception("Unable to get chat room");

            Object message;
            try {
                Method m = room.getClass().getMethod("createMessageFromUtf8", String.class);
                message = m.invoke(room, text.trim());
            } catch (NoSuchMethodException ex) {
                Method m = room.getClass().getMethod("createMessage", String.class);
                message = m.invoke(room, text.trim());
            }
            try {
                Method sendChatMessage = room.getClass().getMethod("sendChatMessage", message.getClass());
                sendChatMessage.invoke(room, message);
            } catch (NoSuchMethodException ex) {
                Method send = message.getClass().getMethod("send");
                send.invoke(message);
            }

            WritableMap map = Arguments.createMap();
            map.putString("from", username);
            map.putString("to", cleanTo);
            map.putString("text", text.trim());
            emit("SipPhoneMessageSent", map);
            promise.resolve("Message sent");
        } catch (Exception e) {
            promise.reject("MSG_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void getStatus(Promise promise) {
        WritableMap map = Arguments.createMap();
        map.putBoolean("registered", registered);
        map.putBoolean("inCall", currentCall != null);
        map.putString("username", username);
        map.putString("host", host);
        promise.resolve(map);
    }

    @ReactMethod
    public void unregister(Promise promise) {
        try {
            if (currentCall != null) { currentCall.terminate(); currentCall = null; }
            if (core != null) { core.stop(); core = null; }
            registered = false;
            promise.resolve("Unregistered");
        } catch (Exception e) {
            promise.reject("UNREGISTER_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void addListener(String eventName) {}
    @ReactMethod
    public void removeListeners(int count) {}
}
