package net.ossrs.yasea.demo.studio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Relaunches the studio after a reboot when kiosk "start on boot" is enabled. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Prefs p = new Prefs(context);
        if (p.kiosk() && p.kioskOnBoot()) {
            Intent i = new Intent(context, StudioActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(i);
            } catch (RuntimeException ignored) {
                // Android 10+ may block background launches; the Home alias covers that case
            }
        }
    }
}
