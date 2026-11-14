package top.niunaijun.blackbox.utils;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Runs inside every cloned-app process. Detects:
 *  - black screen: an Activity exists but no real content frame was drawn within the timeout
 *  - hang: main thread stops answering while the app is in front
 * and reports it through {@link AppFailureNotifier}. Never touches processes without UI.
 */
public final class BlackScreenWatchdog {
    private static final String TAG = "BlackScreenWatchdog";

    /** Slow / low-RAM phones (Spark Go class) need a generous limit to avoid false alarms. */
    private static final long FIRST_FRAME_TIMEOUT_MS = 25000;
    private static final long HANG_TIMEOUT_MS = 15000;
    private static final long BEAT_INTERVAL_MS = 4000;

    public interface UiProbe {
        /** true when this process currently owns at least one live Activity. */
        boolean hasActivities();
    }

    private static boolean sInstalled;
    private static volatile boolean sHealthy;
    private static volatile boolean sReportedBlack;
    private static volatile boolean sReportedHang;
    private static volatile int sResumed;
    private static volatile long sLastBeat;
    private static boolean sCheckScheduled;
    private static String sPkg;
    private static int sUserId;
    private static UiProbe sProbe;
    private static Handler sMain;

    private BlackScreenWatchdog() {
    }

    public static synchronized void install(Application app, String pkg, int userId, UiProbe probe) {
        if (sInstalled || app == null) return;
        sInstalled = true;
        sPkg = pkg;
        sUserId = userId;
        sProbe = probe;
        sMain = new Handler(Looper.getMainLooper());
        try {
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity a, Bundle b) {
                    scheduleCheck();
                }

                @Override
                public void onActivityStarted(Activity a) {
                }

                @Override
                public void onActivityResumed(Activity a) {
                    sResumed++;
                    watchFirstFrame(a);
                }

                @Override
                public void onActivityPaused(Activity a) {
                    if (sResumed > 0) sResumed--;
                }

                @Override
                public void onActivityStopped(Activity a) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity a, Bundle b) {
                }

                @Override
                public void onActivityDestroyed(Activity a) {
                }
            });
            Slog.d(TAG, "installed for " + pkg + " u" + userId);
        } catch (Throwable t) {
            Slog.w(TAG, "install failed: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ black screen

    private static void scheduleCheck() {
        synchronized (BlackScreenWatchdog.class) {
            if (sHealthy || sReportedBlack || sCheckScheduled) return;
            sCheckScheduled = true;
        }
        sMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (BlackScreenWatchdog.class) {
                    sCheckScheduled = false;
                }
                if (sHealthy || sReportedBlack) return;
                boolean ui = false;
                try {
                    ui = sProbe != null && sProbe.hasActivities();
                } catch (Throwable ignored) {
                }
                if (!ui) return; // background-only process, nothing to watch
                sReportedBlack = true;
                report("ব্ল্যাক স্ক্রিন: অ্যাপের স্ক্রিনে কোনো কনটেন্ট আঁকা হয়নি ("
                        + (FIRST_FRAME_TIMEOUT_MS / 1000) + " সেকেন্ডের বেশি)");
            }
        }, FIRST_FRAME_TIMEOUT_MS);
    }

    private static void watchFirstFrame(final Activity a) {
        if (sHealthy) return;
        try {
            final View decor = a.getWindow().getDecorView();
            final ViewTreeObserver.OnDrawListener l = new ViewTreeObserver.OnDrawListener() {
                @Override
                public void onDraw() {
                    if (contentReady(decor)) markHealthy();
                    final ViewTreeObserver.OnDrawListener self = this;
                    decor.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                ViewTreeObserver o = decor.getViewTreeObserver();
                                if (o.isAlive()) o.removeOnDrawListener(self);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
            };
            ViewTreeObserver vto = decor.getViewTreeObserver();
            if (vto.isAlive()) vto.addOnDrawListener(l);
            decor.invalidate();
        } catch (Throwable t) {
            Slog.w(TAG, "watchFirstFrame: " + t.getMessage());
        }
    }

    private static boolean contentReady(View decor) {
        try {
            View c = decor.findViewById(android.R.id.content);
            if (c instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) c;
                return g.getChildCount() > 0 && g.getWidth() > 0 && g.getHeight() > 0;
            }
            return decor.getWidth() > 0 && decor.getHeight() > 0;
        } catch (Throwable t) {
            return true; // never raise a false alarm because of our own probe
        }
    }

    private static void markHealthy() {
        synchronized (BlackScreenWatchdog.class) {
            if (sHealthy) return;
            sHealthy = true;
        }
        startHeartbeat();
    }

    // ------------------------------------------------------------------ hang detection

    private static void startHeartbeat() {
        sLastBeat = System.currentTimeMillis();
        HandlerThread ht = new HandlerThread("bb-heartbeat", Process.THREAD_PRIORITY_BACKGROUND);
        ht.start();
        final Handler h = new Handler(ht.getLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                sMain.post(new Runnable() {
                    @Override
                    public void run() {
                        sLastBeat = System.currentTimeMillis();
                        sReportedHang = false;
                    }
                });
                if (sResumed > 0 && !sReportedHang && now - sLastBeat > HANG_TIMEOUT_MS) {
                    sReportedHang = true;
                    report("অ্যাপ সাড়া দিচ্ছে না (" + (HANG_TIMEOUT_MS / 1000) + " সেকেন্ডের বেশি হ্যাং)");
                }
                h.postDelayed(this, BEAT_INTERVAL_MS);
            }
        });
    }

    // ------------------------------------------------------------------ reporting

    public static String memoryInfo() {
        try {
            Context c = BlackBoxCore.getContext();
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return "ফাঁকা RAM: " + (mi.availMem / (1024 * 1024)) + " MB"
                    + (mi.lowMemory ? " (RAM কম - সিস্টেম অ্যাপ বন্ধ করে দিচ্ছে)" : "");
        } catch (Throwable t) {
            return "";
        }
    }

    private static void report(String reason) {
        AppFailureNotifier.report(BlackBoxCore.getContext(), sPkg, sUserId, Process.myPid(),
                reason, memoryInfo(), true);
    }
}
