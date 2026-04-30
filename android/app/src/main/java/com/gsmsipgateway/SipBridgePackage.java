package com.gsmsipgateway;
import com.facebook.react.*;
import com.facebook.react.bridge.*;
import com.facebook.react.uimanager.*;
import java.util.*;

public class SipBridgePackage implements ReactPackage {
    @Override public List<NativeModule> createNativeModules(ReactApplicationContext ctx) {
        List<NativeModule> modules = new ArrayList<>();
        modules.add(new SipBridgeModule(ctx));
        modules.add(new SipPhoneModule(ctx));
        return modules;
    }
    @Override public List<ViewManager> createViewManagers(ReactApplicationContext ctx) {
        return Collections.emptyList();
    }
}
