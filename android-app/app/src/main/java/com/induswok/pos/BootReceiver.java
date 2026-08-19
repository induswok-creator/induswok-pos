package com.induswok.pos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** Restarts the order-watcher service after the phone reboots. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            Intent svc = new Intent(ctx, OrderNotifyService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
        } catch (Throwable t) {
            // Android 12+ may block background starts — the service also starts
            // the next time the app is opened, so this is only a fallback.
            Log.w("IW", "boot start blocked: " + t.getMessage());
        }
    }
}
