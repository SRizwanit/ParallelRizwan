package com.srizwan.parallel

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserManager

/**
 * Android "Work profile" (managed profile) support.
 *
 *  - Parallel becomes the profile owner of a new work profile (a second, isolated Android user).
 *  - The copy of Parallel inside the profile can bring an app into the profile
 *    (DevicePolicyManager.installExistingPackage) and start it.
 *  - The personal-side Parallel asks it to do that through a cross-profile intent (ACTION_WORK_OPEN).
 */
object WorkProfile {
    const val ACTION_WORK_OPEN = "com.srizwan.parallel.action.WORK_OPEN"
    const val EXTRA_PKG = "wp_pkg"
    const val REQ_PROVISION = 200

    fun admin(ctx: Context) = ComponentName(ctx, WorkProfileAdmin::class.java)

    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** True when this Parallel copy is the one running INSIDE the work profile. */
    fun isInsideProfile(ctx: Context): Boolean = try {
        dpm(ctx).isProfileOwnerApp(ctx.packageName)
    } catch (e: Throwable) { false }

    /** Can this phone create a work profile at all? */
    fun isSupported(ctx: Context): Boolean = try {
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)) false
        else if (Build.VERSION.SDK_INT >= 24)
            dpm(ctx).isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        else true
    } catch (e: Throwable) { false }

    /** Is there already a work profile that contains Parallel? */
    fun exists(ctx: Context): Boolean = try {
        val um = ctx.getSystemService(Context.USER_SERVICE) as UserManager
        val me = Process.myUserHandle()
        val others = um.userProfiles.filter { it != me }
        if (others.isEmpty()) false
        else if (Build.VERSION.SDK_INT >= 21) {
            val la = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            others.any { la.getActivityList(ctx.packageName, it).isNotEmpty() }
        } else true
    } catch (e: Throwable) { false }

    /** Starts the system "create work profile" flow. Result arrives in onActivityResult(REQ_PROVISION). */
    fun startCreate(act: Activity): Boolean {
        val i = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        if (Build.VERSION.SDK_INT >= 23) {
            i.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin(act))
        } else {
            @Suppress("DEPRECATION")
            i.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME, act.packageName)
        }
        if (i.resolveActivity(act.packageManager) == null) return false
        act.startActivityForResult(i, REQ_PROVISION)
        return true
    }

    /** Runs inside the work profile (profile owner side): enable it and allow the personal side to talk to us. */
    fun setupInsideProfile(ctx: Context) {
        try {
            val d = dpm(ctx)
            val a = admin(ctx)
            if (!d.isProfileOwnerApp(ctx.packageName)) return
            try { d.setProfileName(a, "Parallel Work") } catch (e: Throwable) { }
            try { d.setProfileEnabled(a) } catch (e: Throwable) { }
            // only the profile copy may answer ACTION_WORK_OPEN
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, WorkOpenActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
            val f = IntentFilter(ACTION_WORK_OPEN).apply { addCategory(Intent.CATEGORY_DEFAULT) }
            d.addCrossProfileIntentFilter(a, f, DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)
        } catch (e: Throwable) { }
    }

    /** Personal side: ask the profile copy of Parallel to bring [pkg] into the profile and open it. */
    fun openInWork(act: Activity, pkg: String): Boolean = try {
        act.startActivity(Intent(ACTION_WORK_OPEN).setPackage(act.packageName).putExtra(EXTRA_PKG, pkg))
        true
    } catch (e: Throwable) { false }

    /** Profile side: install-existing (API 28+) and launch. Call off the main thread. */
    fun openInsideProfile(ctx: Context, pkg: String): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= 28 && isInsideProfile(ctx)) {
                dpm(ctx).installExistingPackage(admin(ctx), pkg)
            }
        } catch (e: Throwable) { }
        repeat(10) {
            val li = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (li != null) {
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(li)
                return true
            }
            Thread.sleep(400)
        }
        return false
    }
}
