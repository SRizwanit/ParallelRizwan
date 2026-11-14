package top.niunaijun.blackbox.proxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import top.niunaijun.blackbox.utils.AppFailureNotifier;

/** Runs in the host main process. Notification tap / dialog Exit => destroy the cloned app. */
public class AppKillReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String pkg = intent.getStringExtra(AppFailureNotifier.EXTRA_PACKAGE);
        int userId = intent.getIntExtra(AppFailureNotifier.EXTRA_USER_ID, 0);
        int pid = intent.getIntExtra(AppFailureNotifier.EXTRA_PID, -1);
        if (pkg == null) return;

        if (AppFailureNotifier.ACTION_REPORT.equals(action)) {
            // sent by a cloned-app process; the notification must be posted from the host process
            AppFailureNotifier.postNotification(context.getApplicationContext(), pkg, userId, pid,
                    intent.getStringExtra(AppFailureNotifier.EXTRA_LABEL),
                    intent.getStringExtra(AppFailureNotifier.EXTRA_REASON),
                    intent.getStringExtra(AppFailureNotifier.EXTRA_DETAIL));
            return;
        }
        if (!AppFailureNotifier.ACTION_KILL.equals(action)) return;
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                AppFailureNotifier.killApp(context.getApplicationContext(), pkg, userId, pid);
            } finally {
                pr.finish();
            }
        }, "bb-kill").start();
    }
}
