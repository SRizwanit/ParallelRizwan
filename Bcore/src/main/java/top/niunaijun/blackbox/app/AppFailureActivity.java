package top.niunaijun.blackbox.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import top.niunaijun.blackbox.utils.AppFailureNotifier;
import top.niunaijun.blackbox.utils.FailureLog;

/** Dialog: "why is this app not running" + Exit button. Runs in the host main process. */
public class AppFailureActivity extends Activity {
    private AlertDialog mDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        show(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        show(intent);
    }

    private void show(Intent intent) {
        if (intent == null) {
            finish();
            return;
        }
        if (mDialog != null && mDialog.isShowing()) mDialog.dismiss();

        final String pkg = intent.getStringExtra(AppFailureNotifier.EXTRA_PACKAGE);
        final int userId = intent.getIntExtra(AppFailureNotifier.EXTRA_USER_ID, 0);
        final int pid = intent.getIntExtra(AppFailureNotifier.EXTRA_PID, -1);
        String label = intent.getStringExtra(AppFailureNotifier.EXTRA_LABEL);
        String reason = intent.getStringExtra(AppFailureNotifier.EXTRA_REASON);
        final String detail = intent.getStringExtra(AppFailureNotifier.EXTRA_DETAIL);
        if (label == null) label = pkg;

        StringBuilder msg = new StringBuilder();
        msg.append(reason == null ? "অজানা কারণ" : reason);
        if (detail != null && !detail.isEmpty()) msg.append("\n\n").append(detail);
        msg.append("\n\nExit চাপলে অ্যাপটি সম্পূর্ণ বন্ধ (destroy) হবে।");

        // post the notification from this (host main) process
        try {
            AppFailureNotifier.postNotification(getApplicationContext(), pkg, userId, pid, label,
                    reason == null ? "অজানা কারণ" : reason, detail);
        } catch (Throwable ignored) {
        }

        mDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(label + " চলছে না")
                .setMessage(msg.toString())
                .setCancelable(false)
                .setPositiveButton("Exit", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final Context app = getApplicationContext();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                if (pkg != null) AppFailureNotifier.killApp(app, pkg, userId, pid);
                            }
                        }, "bb-kill").start();
                        finish();
                    }
                })
                .setNeutralButton("লগ কপি", null)
                .setNegativeButton("বন্ধ", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        finish();
                    }
                })
                .create();
        mDialog.show();
        // "Copy log" must not close the dialog
        mDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                StringBuilder all = new StringBuilder();
                all.append(pkg).append(" u").append(userId).append('\n');
                if (detail != null) all.append(detail).append('\n');
                String full = FailureLog.read(getApplicationContext(), pkg);
                if (full != null) all.append("\n========== ORIGINAL ERROR LOG ==========\n").append(full);
                cm.setPrimaryClip(ClipData.newPlainText("app-failure", all.toString()));
                Toast.makeText(this, "কপি হয়েছে (original error লগসহ)", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (mDialog != null && mDialog.isShowing()) mDialog.dismiss();
        super.onDestroy();
    }
}
