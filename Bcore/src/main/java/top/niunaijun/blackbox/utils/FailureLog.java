package top.niunaijun.blackbox.utils;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects the ORIGINAL error text (logcat of the dead app process: "Fatal signal", exceptions,
 * native backtrace ...) so the "লগ কপি" button can copy it.
 * The host app and all its :pN processes share one UID, so plain `logcat -d` can read them.
 */
public final class FailureLog {
    private static final String TAG = "FailureLog";
    public static final String FILE = "bb_last_failure.log";
    private static final int MAX_CHARS = 200 * 1024;
    private static final Pattern LINE = Pattern.compile("^\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s");
    private static final String[] KEYS = {
            "Fatal signal", "FATAL EXCEPTION", "SIGSEGV", "SIGABRT", "SIGILL", "SIGBUS", "SIGSYS", "SIGKILL",
            "backtrace:", "Abort message", "AndroidRuntime", "am_crash", "am_proc_died", "Caused by:",
            "Exception", "Error:", "UnsatisfiedLinkError", "dlopen failed", "System.exit", "killProcess",
            "has died", "Process:", "pc ", "AntiDetection"
    };

    private FailureLog() {
    }

    /** Newest CrashMonitor file (has the full Java stack trace), if written in the last 3 minutes. */
    private static String newestCrashFile(Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir(), "crash_logs");
            File[] fs = dir.listFiles();
            if (fs == null || fs.length == 0) return null;
            File best = null;
            for (File f : fs) {
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            }
            if (best == null || System.currentTimeMillis() - best.lastModified() > 3 * 60 * 1000L) return null;
            java.io.FileInputStream in = new java.io.FileInputStream(best);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 60 * 1024) bo.write(buf, 0, n);
            } finally {
                in.close();
            }
            return best.getName() + "\n" + bo.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Writes the full log to cache/bb_last_failure.log and returns a short summary of the key error lines (or null). */
    public static String capture(Context ctx, String pkg, int userId, int pid, String osReason, long livedMs) {
        List<String> kept = new ArrayList<>();
        String error = null;
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "threadtime", "-t", "4000"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String host = ctx.getPackageName();
            String l;
            while ((l = r.readLine()) != null) {
                boolean pidMatch = false;
                Matcher m = LINE.matcher(l);
                if (pid > 0 && m.find()) {
                    pidMatch = String.valueOf(pid).equals(m.group(1));
                }
                if (pidMatch || l.contains(pkg) || l.contains(host) || l.contains(" DEBUG ")
                        || l.contains("Fatal signal") || l.contains("FATAL EXCEPTION")) {
                    kept.add(l);
                }
            }
        } catch (Throwable t) {
            error = t.toString();
            Slog.w(TAG, "logcat failed: " + t.getMessage());
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) { }
            }
        }

        List<String> key = new ArrayList<>();
        for (String l : kept) {
            for (String k : KEYS) {
                if (l.contains(k)) {
                    key.add(l.length() > 300 ? l.substring(0, 300) : l);
                    break;
                }
            }
        }
        // keep the LAST key lines (closest to the death)
        if (key.size() > 40) key = new ArrayList<>(key.subList(key.size() - 40, key.size()));

        StringBuilder sb = new StringBuilder();
        sb.append("pkg=").append(pkg).append(" u").append(userId).append(" pid=").append(pid)
                .append(" lived=").append(livedMs).append("ms\n");
        sb.append("device: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(" | Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(") | ")
                .append(android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "?").append('\n');
        sb.append("OS exit reason: ").append(osReason == null ? "(পাওয়া যায়নি)" : osReason).append("\n\n");
        if (error != null) sb.append("logcat পড়া যায়নি: ").append(error).append("\n\n");
        sb.append("=== মূল error লাইন (বাছাই) ===\n");
        if (key.isEmpty()) sb.append("(কোনো crash লাইন পাওয়া যায়নি)\n");
        for (String k : key) sb.append(k).append('\n');
        String crashFile = newestCrashFile(ctx);
        String stackHead = null;
        if (crashFile != null) {
            sb.append("\n=== CrashMonitor ফাইল (আসল Java stack trace) ===\n").append(crashFile).append('\n');
            int at = crashFile.indexOf("=== STACK TRACE ===");
            if (at >= 0) {
                String[] ls = crashFile.substring(at + 19).trim().split("\n");
                StringBuilder h = new StringBuilder();
                for (int i = 0; i < ls.length && i < 7; i++) {
                    String x = ls[i];
                    if (x.length() > 170) x = x.substring(0, 170) + "…";
                    h.append(x).append('\n');
                }
                stackHead = h.toString().trim();
            }
        }
        sb.append("\n=== পুরো লগ (এই অ্যাপ/প্রসেস সংক্রান্ত) ===\n");
        StringBuilder body = new StringBuilder();
        for (String l : kept) body.append(l).append('\n');
        if (body.length() > MAX_CHARS) body.delete(0, body.length() - MAX_CHARS);
        sb.append(body);

        try {
            File f = new File(ctx.getCacheDir(), FILE);
            FileOutputStream os = new FileOutputStream(f, false);
            try {
                os.write(sb.toString().getBytes("UTF-8"));
            } finally {
                os.close();
            }
        } catch (Throwable t) {
            Slog.w(TAG, "write failed: " + t.getMessage());
        }

        if (key.isEmpty() && stackHead == null) return null;
        StringBuilder sum = new StringBuilder();
        if (stackHead != null) sum.append("Java exception:\n").append(stackHead).append("\n\n");
        int from = Math.max(0, key.size() - 6);
        for (int i = from; i < key.size(); i++) {
            String s = key.get(i);
            if (s.length() > 170) s = s.substring(0, 170) + "…";
            sum.append(s).append('\n');
        }
        return sum.toString().trim();
    }

    /** Returns the saved log text for [pkg] if it is fresh, else null. */
    public static String read(Context ctx, String pkg) {
        try {
            File f = new File(ctx.getCacheDir(), FILE);
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 10 * 60 * 1000L) return null;
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            } finally {
                in.close();
            }
            String s = bo.toString("UTF-8");
            return (pkg != null && s.startsWith("pkg=" + pkg + " ")) ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
