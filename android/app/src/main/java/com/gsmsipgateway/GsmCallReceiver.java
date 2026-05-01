package com.gsmsipgateway;

import android.content.*;
import android.os.Build;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

public class GsmCallReceiver extends BroadcastReceiver {
    private static final String TAG = "GsmCallReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
        
        // Phát hiện SIM slot (Dual SIM support)
        int simSlot = detectSimSlot(context, intent);
        
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            Intent si = new Intent(context, GsmSipBridgeService.class);
            si.setAction("ACTION_INCOMING_CALL");
            si.putExtra("caller_number", number != null ? number : "Unknown");
            si.putExtra("sim_slot", simSlot);
            Log.d(TAG, "Incoming call from " + number + " on SIM slot " + simSlot);
            context.startForegroundService(si);
        }
        if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            Intent si = new Intent(context, GsmSipBridgeService.class);
            si.setAction("ACTION_CALL_ENDED");
            si.putExtra("sim_slot", simSlot);
            Log.d(TAG, "Call ended on SIM slot " + simSlot);
            context.startForegroundService(si);
        }
    }

    /**
     * Phát hiện SIM slot nào gọi vào
     * Slot 0 (SIM1) -> Sử dụng SIP 1001
     * Slot 1 (SIM2) -> Sử dụng SIP 1002
     */
    private int detectSimSlot(Context context, Intent intent) {
        try {
            // Trên Android 5.1+ (API 22+): sử dụng SubscriptionManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                // Lấy subscription ID từ intent (nếu có)
                if (intent.hasExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)) {
                    int subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 0);
                    int slot = SubscriptionManager.getSlotIndex(subId);
                    if (slot >= 0) {
                        Log.d(TAG, "Detected SIM slot from intent: " + slot);
                        return slot;
                    }
                }

                // Fallback: Kiểm tra cuộc gọi hiện tại trên mỗi slot
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm == null) return 0;

                // Lấy số SIM slots
                SubscriptionManager sm = (SubscriptionManager) context.getSystemService(
                    Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (sm != null) {
                    int[] subIds = sm.getActiveSubscriptionIdList();
                    if (subIds != null) {
                        for (int subId : subIds) {
                            int slot = SubscriptionManager.getSlotIndex(subId);
                            if (slot >= 0) {
                                Log.d(TAG, "Active SIM found at slot: " + slot);
                                // Chọn slot thứ nhất có sẵn
                                return slot;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error detecting SIM slot: " + e.getMessage());
        }

        // Default: SIM slot 0
        return 0;
    }
}
