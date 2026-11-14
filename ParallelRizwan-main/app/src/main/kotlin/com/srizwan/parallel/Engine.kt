package com.srizwan.parallel

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.utils.AbiUtils
import java.io.File
import java.util.concurrent.Executors

/** A cloned app living inside the virtual space [userId]. */
data class CloneItem(val label: String, val icon: Drawable?, val packageName: String, val userId: Int)

/** An app installed on the phone that can be cloned. */
data class DeviceApp(val label: String, val icon: Drawable?, val packageName: String)

/** Thin wrapper around BlackBoxCore. Every call is safe to use from a background thread. */
object Engine {
    const val MAX_SPACES = 10

    /** One background thread for all engine work (lives longer than any Activity). */
    val io = Executors.newSingleThreadExecutor()

    private val core: BlackBoxCore get() = BlackBoxCore.get()

    private fun labelOf(ai: ApplicationInfo): String = try {
        BlackBoxCore.getPackageManager().getApplicationLabel(ai).toString()
    } catch (e: Throwable) {
        ai.packageName ?: "?"
    }

    private fun iconOf(ai: ApplicationInfo): Drawable? = try {
        BlackBoxCore.getPackageManager().getApplicationIcon(ai)
    } catch (e: Throwable) {
        null
    }

    fun onReady(callback: () -> Unit) {
        try {
            core.addServiceAvailableCallback(Runnable { callback() })
        } catch (e: Throwable) {
            // refresh() is also called directly, so a missing callback is not fatal
        }
    }

    fun listClones(): List<CloneItem> {
        val out = ArrayList<CloneItem>()
        val users = try { core.users } catch (e: Throwable) { null } ?: return out
        for (u in users) {
            val apps = try { core.getInstalledApplications(0, u.id) } catch (e: Throwable) { null } ?: continue
            for (ai in apps) {
                val pkg = ai.packageName ?: continue
                out.add(CloneItem(labelOf(ai), iconOf(ai), pkg, u.id))
            }
        }
        return out
    }

    fun deviceApps(): List<DeviceApp> {
        val pm = BlackBoxCore.getPackageManager()
        val host = BlackBoxCore.getHostPkg()
        val out = ArrayList<DeviceApp>()
        for (ai in pm.getInstalledApplications(0)) {
            val pkg = ai.packageName ?: continue
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            if (pkg == host) continue
            val src = ai.sourceDir ?: continue
            try {
                if (!AbiUtils.isSupport(File(src))) continue
                if (core.isBlackBoxApp(pkg)) continue
            } catch (e: Throwable) {
                continue
            }
            out.add(DeviceApp(labelOf(ai), iconOf(ai), pkg))
        }
        out.sortBy { it.label.lowercase() }
        return out
    }

    /** Clones [pkg] into the first free space. Returns null on success, else an error text. */
    fun clone(pkg: String): String? {
        var space = -1
        for (i in 0 until MAX_SPACES) {
            // NOTE: core.isInstalled() falls back to the HOST package manager when the engine
            // service is not ready, and then answers "true" for every space (=> false "max 10"
            // error). So we ask the engine's own per-space app list instead.
            val used = try {
                core.getInstalledApplications(0, i)?.any { it.packageName == pkg } ?: false
            } catch (e: Throwable) { false }
            if (!used) { space = i; break }
        }
        if (space < 0) return "সর্বোচ্চ $MAX_SPACES টি কপি তৈরি করা যায়"
        return try {
            val r = core.installPackageAsUser(pkg, space)
            if (r != null && r.success) null else (r?.msg ?: "ইনস্টল ব্যর্থ হয়েছে")
        } catch (e: Throwable) {
            e.message ?: "ইনস্টল ব্যর্থ হয়েছে"
        }
    }

    fun launch(pkg: String, userId: Int): Boolean = try {
        core.launchApk(pkg, userId)
    } catch (e: Throwable) {
        false
    }

    fun clearData(pkg: String, userId: Int) {
        try { core.clearPackage(pkg, userId) } catch (e: Throwable) { }
    }

    fun remove(pkg: String, userId: Int) {
        try { core.uninstallPackageAsUser(pkg, userId) } catch (e: Throwable) { }
        trimEmptySpaces()
    }

    /** Drops trailing spaces that no longer hold any app. */
    private fun trimEmptySpaces() {
        try {
            while (true) {
                val users = core.users
                if (users.isNullOrEmpty()) return
                val last = users.last().id
                val apps = core.getInstalledApplications(0, last)
                if (apps != null && apps.isNotEmpty()) return
                core.deleteUser(last)
            }
        } catch (e: Throwable) {
            // best effort
        }
    }
}
