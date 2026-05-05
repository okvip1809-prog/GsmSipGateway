package com.gsmsipgateway;
import android.content.*;
import com.facebook.react.bridge.*;

public class SipBridgeModule extends ReactContextBaseJavaModule {
    public SipBridgeModule(ReactApplicationContext ctx) { super(ctx); }
    @Override public String getName() { return "SipBridge"; }

    @ReactMethod
    public void stopService(Promise promise) {
        try {
            Intent i = new Intent(getReactApplicationContext(), GsmSipBridgeService.class);
            boolean stopped = getReactApplicationContext().stopService(i);
            promise.resolve(stopped ? "Service stopped" : "Service was not running");
        } catch (Exception e) { promise.reject("ERROR", e.getMessage()); }
    }

    /**
     * Cấu hình Dual SIM (hỗ trợ 2 tài khoản SIP)
     * cfg: {
     *   host: String,
     *   port: int,
     *   username_sim1: String,
     *   password_sim1: String,
     *   username_sim2: String,
     *   password_sim2: String,
     *   answer_rings: int (optional)
     * }
     */
    @ReactMethod
    public void saveDualSipConfig(ReadableMap cfg, Promise promise) {
        try {
            SharedPreferences.Editor p = getReactApplicationContext()
                .getSharedPreferences("sip_config", Context.MODE_PRIVATE).edit();
            
            // Lưu cấu hình server
            p.putString("host", cfg.getString("host"));
            p.putInt("port", cfg.getInt("port"));
            
            // Lưu cấu hình SIM1
            p.putString("username_sim1", cfg.getString("username_sim1"));
            p.putString("password_sim1", cfg.getString("password_sim1"));
            
            // Lưu cấu hình SIM2
            p.putString("username_sim2", cfg.getString("username_sim2"));
            p.putString("password_sim2", cfg.getString("password_sim2"));
            
            // Lưu answer rings
            p.putInt("answer_rings", cfg.hasKey("answer_rings") ? cfg.getInt("answer_rings") : 1);
            
            p.apply();
            
            // Khởi động lại service
            Intent i = new Intent(getReactApplicationContext(), GsmSipBridgeService.class);
            i.setAction("ACTION_RELOAD");
            getReactApplicationContext().startForegroundService(i);
            
            promise.resolve("Dual SIP config saved - Service restarted");
        } catch (Exception e) { promise.reject("ERROR", e.getMessage()); }
    }

    /**
     * Cấu hình cũ (single SIP account) - kept for backward compatibility
     */
    @ReactMethod
    public void saveConfig(ReadableMap cfg, Promise promise) {
        try {
            SharedPreferences.Editor p = getReactApplicationContext()
                .getSharedPreferences("sip_config", Context.MODE_PRIVATE).edit();
            p.putString("host", cfg.getString("host"));
            p.putInt("port", cfg.getInt("port"));
            p.putString("username", cfg.getString("username"));
            p.putString("password", cfg.getString("password"));
            p.putString("bridge_ext", cfg.getString("bridgeExtension"));
            p.putInt("answer_rings", cfg.hasKey("answerRings") ? cfg.getInt("answerRings") : 1);
            p.apply();
            Intent i = new Intent(getReactApplicationContext(), GsmSipBridgeService.class);
            i.setAction("ACTION_RELOAD");
            getReactApplicationContext().startForegroundService(i);
            promise.resolve("Config saved - Service restarted");
        } catch (Exception e) { promise.reject("ERROR", e.getMessage()); }
    }
}

