package top.niunaijun.blackbox.utils;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.AppFailureActivity;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.proxy.AppKillReceiver;

/**
 * Tells the user that a cloned app is not running (black screen, crash, hang, early death).
 * Works from any process of the host app (host UI, :black service, :pN client processes).
 *
 * 1. shows AppFailureActivity (dialog with the reason + Exit button), and
 * 2. posts a notification (needs POST_NOTIFICATIONS on Android 13+); tapping it kills the app.
 */
public final class AppFailureNotifier {
    private static final String TAG = "AppFailureNotifier";

    public static final String CHANNEL_ID = "blackbox_app_failure";
    public static final String ACTION_KILL = "top.niunaijun.blackbox.action.KILL_APP";
    public static final String ACTION_REPORT = "top.niunaijun.blackbox.action.REPORT_FAILURE";

    public static final String EXTRA_PACKAGE = "bb_pkg";
    public static final String EXTRA_USER_ID = "bb_user";
    public static final String EXTRA_PID = "bb_pid";
    public static final String EXTRA_REASON = "bb_reason";
    public static final String EXTRA_DETAIL = "bb_detail";
    public static final String EXTRA_LABEL = "bb_label";

    private static final long DEDUPE_MS = 4000;
    private static String sLastKey = "";
    private static long sLastTime = 0;

    private AppFailureNotifier() {
    }

    public static void report(Context ctx, String pkg, int userId, int pid,
                              String reason, String detail, boolean showDialog) {
        try {
            if (ctx == null) ctx = BlackBoxCore.getContext();
            if (ctx == null || pkg == null) return;

            String key = pkg + "|" + userId + "|" + reason;
            synchronized (AppFailureNotifier.class) {
                long now = System.currentTimeMillis();
                if (key.equals(sLastKey) && now - sLastTime < DEDUPE_MS) return;
                sLastKey = key;
                sLastTime = now;
            }

            String label = labelOf(ctx, pkg, userId);
            Slog.w(TAG, "App failure: " + pkg + " u" + userId + " pid=" + pid + " : " + reason);

            if (BActivityThread.getAppConfig() != null) {
                // Inside a cloned-app process the notification / activity APIs are virtualised,
                // so the real notification is posted by the host main process instead
                // (AppFailureActivity and AppKillReceiver both do it).
                sendReportBroadcast(ctx, pkg, userId, pid, label, reason, detail);
            } else {
                postNotification(ctx, pkg, userId, pid, label, reason, detail);
            }
            if (showDialog) showDialog(ctx, pkg, userId, pid, label, reason, detail);
        } catch (Throwable t) {
            Slog.e(TAG, "report failed: " + t.getMessage(), t);
        }
    }

    /** Used by the engine service: only reports when no failure notification for this app is showing yet. */
    public static void reportIfNotShown(Context ctx, String pkg, int userId, int pid,
                                        String reason, String detail) {
        try {
            if (ctx == null) ctx = BlackBoxCore.getContext();
            if (ctx == null || pkg == null) return;
            if (Build.VERSION.SDK_INT >= 23) {
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    int id = notificationId(pkg, userId);
                    for (android.service.notification.StatusBarNotification n : nm.getActiveNotifications()) {
                        if (n.getId() == id) return;
                    }
                }
            }
            report(ctx, pkg, userId, pid, reason, detail, true);
        } catch (Throwable t) {
            Slog.w(TAG, "reportIfNotShown: " + t.getMessage());
        }
    }

    /** Kills the cloned app completely. Safe to call from the host process. */
    public static void killApp(Context ctx, String pkg, int userId, int pid) {
        try {
            BlackBoxCore.get().stopPackage(pkg, userId);
        } catch (Throwable t) {
            Slog.w(TAG, "stopPackage failed: " + t.getMessage());
        }
        if (pid > 0 && pid != Process.myPid()) {
            try {
                Process.killProcess(pid);
            } catch (Throwable ignored) {
            }
        }
        cancel(ctx, pkg, userId);
    }

    public static void cancel(Context ctx, String pkg, int userId) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(notificationId(pkg, userId));
        } catch (Throwable ignored) {
        }
    }

    public static boolean canPostNotifications(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ internals

    private static int notificationId(String pkg, int userId) {
        return 0x0B1ACC00 + (pkg.hashCode() & 0xFFFF) * 16 + (userId & 0xF);
    }

    private static String labelOf(Context ctx, String pkg, int userId) {
        try {
            ApplicationInfo ai = BlackBoxCore.getBPackageManager().getApplicationInfo(pkg, 0, userId);
            if (ai != null) {
                CharSequence l = ctx.getPackageManager().getApplicationLabel(ai);
                if (l != null && l.length() > 0) return l.toString();
            }
        } catch (Throwable ignored) {
        }
        return pkg;
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "App failure alerts", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Shown when a cloned app crashes, hangs or shows a black screen");
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            Slog.w(TAG, "channel: " + t.getMessage());
        }
    }

    private static Intent killIntent(Context ctx, String pkg, int userId, int pid) {
        Intent i = new Intent(ACTION_KILL);
        i.setComponent(new ComponentName(ctx.getPackageName(), AppKillReceiver.class.getName()));
        i.putExtra(EXTRA_PACKAGE, pkg);
        i.putExtra(EXTRA_USER_ID, userId);
        i.putExtra(EXTRA_PID, pid);
        return i;
    }

    private static int piFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }

    public static void postNotification(Context ctx, String pkg, int userId, int pid,
                                         String label, String reason, String detail) {
        if (label == null) label = pkg;
        if (reason == null) reason = "অজানা কারণ";
        if (!canPostNotifications(ctx)) {
            Slog.w(TAG, "Notifications not allowed - only the dialog will be shown");
            return;
        }
        ensureChannel(ctx);
        int id = notificationId(pkg, userId);
        PendingIntent tap = PendingIntent.getBroadcast(ctx, id,
                killIntent(ctx, pkg, userId, pid), piFlags());

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(label + " চলছে না")
                .setContentText(reason + " - বন্ধ করতে ট্যাপ করুন")
                .setStyle(new Notification.BigTextStyle().bigText(
                        reason + (detail == null || detail.isEmpty() ? "" : "\n" + detail)
                                + "\n\nট্যাপ করলে অ্যাপটি সম্পূর্ণ বন্ধ (destroy) হবে।"))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setWhen(System.currentTimeMillis());
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id, b.build());
    }

    private static void sendReportBroadcast(Context ctx, String pkg, int userId, int pid,
                                            String label, String reason, String detail) {
        try {
            Intent i = killIntent(ctx, pkg, userId, pid);
            i.setAction(ACTION_REPORT);
            i.putExtra(EXTRA_LABEL, label);
            i.putExtra(EXTRA_REASON, reason);
            i.putExtra(EXTRA_DETAIL, detail);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            Slog.w(TAG, "report broadcast failed: " + t.getMessage());
        }
    }

    private static void showDialog(Context ctx, String pkg, int userId, int pid,
                                   String label, String reason, String detail) {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(ctx.getPackageName(), AppFailureActivity.class.getName()));
            i.putExtra(EXTRA_PACKAGE, pkg);
            i.putExtra(EXTRA_USER_ID, userId);
            i.putExtra(EXTRA_PID, pid);
            i.putExtra(EXTRA_LABEL, label);
            i.putExtra(EXTRA_REASON, reason);
            i.putExtra(EXTRA_DETAIL, detail);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            ctx.startActivity(i);
        } catch (Throwable t) {
            // Background-activity-start can be blocked on new Android versions;
            // the notification is the fallback in that case.
            Slog.w(TAG, "dialog start blocked: " + t.getMessage());
        }
    }
}
