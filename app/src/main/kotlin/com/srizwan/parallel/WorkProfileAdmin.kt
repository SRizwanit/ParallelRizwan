package com.srizwan.parallel

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Profile-owner component of the work profile. */
class WorkProfileAdmin : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        WorkProfile.setupInsideProfile(context)
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Throwable) { }
    }
}

/** Android 9+: system asks which kind of profile we want to create. */
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // "android.app.extra.PROVISIONING_MODE" = 2 -> managed profile
        setResult(RESULT_OK, Intent().putExtra("android.app.extra.PROVISIONING_MODE", 2))
        finish()
    }
}

/** Android 9+: last step of provisioning - nothing to check, just accept. */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkProfile.setupInsideProfile(this)
        setResult(RESULT_OK)
        finish()
    }
}

/** Runs in the work profile only: installs + opens the requested app there. */
class WorkOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent?.getStringExtra(WorkProfile.EXTRA_PKG)
        if (pkg.isNullOrEmpty()) { finish(); return }
        Thread {
            val ok = try { WorkProfile.openInsideProfile(applicationContext, pkg) } catch (e: Throwable) { false }
            runOnUiThread {
                if (!ok) Toast.makeText(this, "❌ Work profile-এ অ্যাপটি খোলা যায়নি", Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }
}
