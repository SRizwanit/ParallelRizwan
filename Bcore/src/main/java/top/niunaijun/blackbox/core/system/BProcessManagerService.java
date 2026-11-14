package top.niunaijun.blackbox.core.system;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.IBActivityThread;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.notification.BNotificationManagerService;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.utils.AppFailureNotifier;
import top.niunaijun.blackbox.utils.BlackScreenWatchdog;
import top.niunaijun.blackbox.utils.FailureLog;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.ApplicationThreadCompat;
import top.niunaijun.blackbox.utils.compat.BundleCompat;
import top.niunaijun.blackbox.utils.provider.ProviderCall;


public class BProcessManagerService implements ISystemService {

    /** A main app process that dies this soon after it started (and nobody killed it) is reported. */
    private static final long EARLY_DEATH_MS = 20000;
    private final Set<Integer> mIntentionalKills = new HashSet<>();

    private void markIntentional(int pid) {
        if (pid <= 0) return;
        synchronized (mIntentionalKills) {
            mIntentionalKills.add(pid);
        }
    }

    private boolean consumeIntentional(int pid) {
        synchronized (mIntentionalKills) {
            return mIntentionalKills.remove(pid);
        }
    }

    private void reportEarlyDeath(final ProcessRecord app, final int pid, final long lived) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // give the app's own crash report (if any) time to show first
                    Thread.sleep(1500);
                    Context ctx = BlackBoxCore.getContext();
                    String reason = "অ্যাপ হঠাৎ বন্ধ হয়ে গেছে";
                    String exitInfo = null;
                    // Android 11+: ask the OS for the REAL exit reason (native crash / signal / self exit / low memory ...)
                    for (int i = 0; i < 4 && exitInfo == null; i++) {
                        exitInfo = queryExitReason(pid, lived);
                        if (exitInfo == null) Thread.sleep(700);
                    }
                    // the ORIGINAL error text from logcat (saved to a file; "লগ কপি" copies it)
                    String logSummary = FailureLog.capture(ctx, app.getPackageName(), app.userId, pid, exitInfo, lived);

                    StringBuilder d = new StringBuilder();
                    d.append("প্রসেস চালুর ").append(lived / 1000).append(" সেকেন্ডের মধ্যেই বন্ধ হয়ে গেছে।\n");
                    d.append(exitInfo != null ? "আসল কারণ (OS): " + exitInfo
                            : "OS কারণ জানায়নি (Android 11-এর নিচে বা তথ্য নেই)");
                    d.append('\n');
                    if (logSummary != null) d.append("\nমূল error:\n").append(logSummary).append('\n');
                    d.append('\n').append(BlackScreenWatchdog.memoryInfo());
                    d.append("\n\n(\"লগ কপি\" চাপলে পুরো original error লগ কপি হবে)");
                    String detail = d.toString();
                    Slog.w(TAG, "early death pid=" + pid + " pkg=" + app.getPackageName() + " : " + detail);
                    AppFailureNotifier.reportIfNotShown(ctx, app.getPackageName(),
                            app.userId, pid, reason, detail);
                } catch (Throwable ignored) {
                }
            }
        }, "bb-early-death").start();
    }

    /** Returns a human-readable exit reason for the host process with this pid, or null. */
    private static String queryExitReason(int pid, long lived) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            Context c = BlackBoxCore.getContext();
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<android.app.ApplicationExitInfo> list =
                    am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 8);
            if (list == null || list.isEmpty()) return null;
            android.app.ApplicationExitInfo e = null;
            if (pid > 0) {
                for (android.app.ApplicationExitInfo x : list) {
                    if (x.getPid() == pid) { e = x; break; }
                }
            }
            if (e == null) {
                // pid unknown/not matched: take the newest recent exit of one of our :pN client processes
                long now = System.currentTimeMillis();
                for (android.app.ApplicationExitInfo x : list) {
                    String pn = x.getProcessName();
                    if (pn != null && pn.startsWith(c.getPackageName() + ":p")
                            && now - x.getTimestamp() < lived + 30000L) { e = x; break; }
                }
            }
            if (e == null) return null;
            String name;
            String hint = "";
            int st = e.getStatus();
            switch (e.getReason()) {
                case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE:
                    name = "CRASH_NATIVE (নেটিভ ক্র্যাশ)";
                    hint = " signal=" + st + (st == 11 ? " (SIGSEGV)" : st == 6 ? " (SIGABRT)" : st == 4 ? " (SIGILL)" : st == 7 ? " (SIGBUS)" : "");
                    break;
                case android.app.ApplicationExitInfo.REASON_CRASH:
                    name = "CRASH (Java Exception)";
                    break;
                case android.app.ApplicationExitInfo.REASON_SIGNALED:
                    name = "SIGNALED (কেউ signal পাঠিয়ে মেরেছে)";
                    hint = " signal=" + st + (st == 9 ? " (SIGKILL)" : st == 31 ? " (SIGSYS/seccomp)" : "");
                    break;
                case android.app.ApplicationExitInfo.REASON_EXIT_SELF:
                    name = "EXIT_SELF (অ্যাপ নিজেই exit করেছে)";
                    hint = " exitCode=" + st;
                    break;
                case android.app.ApplicationExitInfo.REASON_LOW_MEMORY:
                    name = "LOW_MEMORY (সিস্টেম RAM-এর জন্য মেরেছে)";
                    break;
                case android.app.ApplicationExitInfo.REASON_ANR:
                    name = "ANR (সাড়া দেয়নি)";
                    break;
                case android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                    name = "INITIALIZATION_FAILURE";
                    break;
                case android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                    name = "PERMISSION_CHANGE";
                    break;
                case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                    name = "EXCESSIVE_RESOURCE_USAGE";
                    break;
                case android.app.ApplicationExitInfo.REASON_USER_REQUESTED:
                    name = "USER_REQUESTED";
                    break;
                default:
                    name = "reason#" + e.getReason();
            }
            String desc = e.getDescription();
            return name + hint + (desc != null && !desc.isEmpty() ? " | " + desc : "")
                    + " | RSS=" + (e.getRss() / 1024) + "MB";
        } catch (Throwable t) {
            return null;
        }
    }

    public static final String TAG = "BProcessManager";

    public static BProcessManagerService sBProcessManagerService = new BProcessManagerService();
    private final Map<Integer, Map<String, ProcessRecord>> mProcessMap = new HashMap<>();
    private final List<ProcessRecord> mPidsSelfLocked = new ArrayList<>();
    private final Object mProcessLock = new Object();

    public static BProcessManagerService get() {
        return sBProcessManagerService;
    }

    public ProcessRecord startProcessLocked(String packageName, String processName, int userId, int bpid, int callingPid) {
        ApplicationInfo info = BPackageManagerService.get().getApplicationInfo(packageName, 0, userId);
        if (info == null)
            return null;
        ProcessRecord app;
        int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
        synchronized (mProcessLock) {
            Map<String, ProcessRecord> bProcess = mProcessMap.get(buid);

            if (bProcess == null) {
                bProcess = new HashMap<>();
            }
            if (bpid == -1) {
                app = bProcess.get(processName);
                if (app != null) {
                    if (app.initLock != null) {
                        app.initLock.block();
                    }
                    if (app.bActivityThread != null) {
                        return app;
                    }
                }
                bpid = getUsingBPidL();
                Slog.d(TAG, "init bUid = " + buid + ", bPid = " + bpid);
            }
            if (bpid == -1) {
                throw new RuntimeException("No processes available");
            }
            app = new ProcessRecord(info, processName);
            app.uid = Process.myUid();
            app.bpid = bpid;
            app.buid = BPackageManagerService.get().getAppId(packageName);
            app.callingBUid = getBUidByPidOrPackageName(callingPid, packageName);
            app.userId = userId;

            bProcess.put(processName, app);
            mPidsSelfLocked.add(app);

            synchronized (mProcessMap) {
                mProcessMap.put(buid, bProcess);
            }
            if (!initAppProcessL(app)) {
                
                bProcess.remove(processName);
                mPidsSelfLocked.remove(app);
                app = null;
            } else {
                app.pid = getPid(BlackBoxCore.getContext(), ProxyManifest.getProcessName(app.bpid));
            }
        }
        return app;
    }

    private int getUsingBPidL() {
        ActivityManager manager = (ActivityManager) BlackBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
        Set<Integer> usingPs = new HashSet<>();
        for (ActivityManager.RunningAppProcessInfo runningAppProcess : runningAppProcesses) {
            int i = parseBPid(runningAppProcess.processName);
            usingPs.add(i);
        }
        for (int i = 0; i < ProxyManifest.FREE_COUNT; i++) {
            if (usingPs.contains(i)) {
                continue;
            }
            return i;
        }
        return -1;
    }

    public void restartAppProcess(String packageName, String processName, int userId) {
        synchronized (mProcessLock) {
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            ProcessRecord app = findProcessByPid(callingPid);;
            if (app == null) {
                String stubProcessName = getProcessName(BlackBoxCore.getContext(), callingPid);
                int bpid = parseBPid(stubProcessName);
                startProcessLocked(packageName, processName, userId, bpid, callingPid);
            }
        }
    }

    private int parseBPid(String stubProcessName) {
        String prefix;
        if (stubProcessName == null) {
            return -1;
        } else {
            prefix = BlackBoxCore.getHostPkg() + ":p";
        }
        if (stubProcessName.startsWith(prefix)) {
            try {
                return Integer.parseInt(stubProcessName.substring(prefix.length()));
            } catch (NumberFormatException e) {
                
            }
        }
        return -1;
    }

    private boolean initAppProcessL(ProcessRecord record) {
        Log.d(TAG, "initProcess: " + record.processName);
        AppConfig appConfig = record.getClientConfig();
        Bundle bundle = new Bundle();
        bundle.putParcelable(AppConfig.KEY, appConfig);
        Bundle init = ProviderCall.callSafely(record.getProviderAuthority(), "_Black_|_init_process_", null, bundle);
        IBinder appThread = BundleCompat.getBinder(init, "_Black_|_client_");
        if (appThread == null || !appThread.isBinderAlive()) {
            return false;
        }
        attachClientL(record, appThread);

        createProc(record);
        return true;
    }

    private void attachClientL(final ProcessRecord app, final IBinder appThread) {
        IBActivityThread activityThread = IBActivityThread.Stub.asInterface(appThread);
        if (activityThread == null) {
            app.kill();
            return;
        }
        final long startedAt = SystemClock.uptimeMillis();
        try {
            appThread.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    Log.d(TAG, "App Died: " + app.processName);
                    appThread.unlinkToDeath(this, 0);
                    long lived = SystemClock.uptimeMillis() - startedAt;
                    // NOTE: app.pid is only assigned AFTER attachClientL() returns, so it must be read
                    // here (at death time), not captured earlier (that was always 0).
                    int deadPid = app.pid;
                    boolean intentional = consumeIntentional(deadPid);
                    if (!intentional && lived < EARLY_DEATH_MS
                            && app.processName != null && app.processName.equals(app.getPackageName())) {
                        reportEarlyDeath(app, deadPid, lived);
                    }
                    onProcessDie(app);
                }
            }, 0);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        app.bActivityThread = activityThread;
        try {
            app.appThread = ApplicationThreadCompat.asInterface(activityThread.getActivityThread());
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        app.initLock.open();
    }

    public void onProcessDie(ProcessRecord record) {
        synchronized (mProcessLock) {
            record.kill();
            Map<String, ProcessRecord> process = mProcessMap.get(record.buid);
            if (process != null) {
                process.remove(record.processName);
                if (process.isEmpty()) {
                    mProcessMap.remove(record.buid);
                }
            }
            mPidsSelfLocked.remove(record);

            removeProc(record);
            BNotificationManagerService.get().deletePackageNotification(record.getPackageName(), record.userId);
        }
    }

    public ProcessRecord findProcessRecord(String packageName, String processName, int userId) {
        synchronized (mProcessMap) {
            int appId = BPackageManagerService.get().getAppId(packageName);
            int buid = BUserHandle.getUid(userId, appId);
            Map<String, ProcessRecord> processRecordMap = mProcessMap.get(buid);
            if (processRecordMap == null)
                return null;
            return processRecordMap.get(processName);
        }
    }

    public void killAllByPackageName(String packageName) {
        synchronized (mProcessLock) {
            synchronized (mPidsSelfLocked) {
                List<ProcessRecord> tmp = new ArrayList<>(mPidsSelfLocked);
                int appId = BPackageManagerService.get().getAppId(packageName);
                for (ProcessRecord processRecord : mPidsSelfLocked) {
                    int appId1 = BUserHandle.getAppId(processRecord.buid);
                    if (appId == appId1) {
                        mProcessMap.remove(processRecord.buid);
                        tmp.remove(processRecord);
                        markIntentional(processRecord.pid);
                        processRecord.kill();
                    }
                }
                mPidsSelfLocked.clear();
                mPidsSelfLocked.addAll(tmp);
            }
        }
    }

    public void killPackageAsUser(String packageName, int userId) {
        synchronized (mProcessLock) {
            int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
            Map<String, ProcessRecord> process = mProcessMap.get(buid);
            if (process == null)
                return;
            for (ProcessRecord value : process.values()) {
                markIntentional(value.pid);
                value.kill();
                mPidsSelfLocked.remove(value);
            }
            mProcessMap.remove(buid);
        }
    }

    public List<ProcessRecord> getPackageProcessAsUser(String packageName, int userId) {
        synchronized (mProcessMap) {
            int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
            Map<String, ProcessRecord> process = mProcessMap.get(buid);
            if (process == null)
                return new ArrayList<>();
            return new ArrayList<>(process.values());
        }
    }

    public int getBUidByPidOrPackageName(int pid, String packageName) {
        ProcessRecord callingProcess = findProcessByPid(pid);
        if (callingProcess == null) {
            return BPackageManagerService.get().getAppId(packageName);
        }
        return BUserHandle.getAppId(callingProcess.buid);
    }

    public int getUserIdByCallingPid(int callingPid) {
        ProcessRecord callingProcess = findProcessByPid(callingPid);
        if (callingProcess == null) {
            return 0;
        }
        return callingProcess.userId;
    }

    public ProcessRecord findProcessByPid(int pid) {
        synchronized (mPidsSelfLocked) {
            for (ProcessRecord processRecord : mPidsSelfLocked) {
                if (processRecord.pid == pid)
                    return processRecord;
            }
            return null;
        }
    }

    private static String getProcessName(Context context, int pid) {
        String processName = null;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
            if (info.pid == pid) {
                processName = info.processName;
                break;
            }
        }
        if (processName == null) {
            throw new RuntimeException("processName = null");
        }
        return processName;
    }

    public static int getPid(Context context, String processName) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
            for (ActivityManager.RunningAppProcessInfo runningAppProcess : runningAppProcesses) {
                if (runningAppProcess.processName.equals(processName)) {
                    return runningAppProcess.pid;
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return -1;
    }

    private static void createProc(ProcessRecord record) {
        File cmdline = new File(BEnvironment.getProcDir(record.bpid), "cmdline");
        try {
            FileUtils.writeToFile(record.processName.getBytes(), cmdline);
        } catch (IOException ignored) {
        }
    }

    private static void removeProc(ProcessRecord record) {
        FileUtils.deleteDir(BEnvironment.getProcDir(record.bpid));
    }

    @Override
    public void systemReady() {
        FileUtils.deleteDir(BEnvironment.getProcDir());
    }
}
