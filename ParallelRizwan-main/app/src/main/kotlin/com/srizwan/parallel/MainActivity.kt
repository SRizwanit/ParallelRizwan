package com.srizwan.parallel

import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.PRIMARY_DARK
        setContentView(buildUi())
        Engine.onReady { ui.post { refresh() } }
        refresh()
        permStep = 0
        nextPermissionStep()
    }

    // ------------------------------------------------------------------ permissions

    private var permStep = 0

    private fun runtimePermissions(): List<String> {
        val l = ArrayList<String>()
        l += listOf(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.GET_ACCOUNTS,
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS, Manifest.permission.RECEIVE_MMS
        )
        if (Build.VERSION.SDK_INT < 33) {
            l += listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 26) l += Manifest.permission.READ_PHONE_NUMBERS
        if (Build.VERSION.SDK_INT >= 26) l += Manifest.permission.ANSWER_PHONE_CALLS
        if (Build.VERSION.SDK_INT >= 29) l += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 31) l += listOf(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
        if (Build.VERSION.SDK_INT >= 33) l += listOf(
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 34) l += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        return l
    }

    /** False when the user denied POST_NOTIFICATIONS or switched the app's notifications off. */
    private fun notificationsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        return try {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).areNotificationsEnabled()
        } catch (e: Throwable) {
            true
        }
    }

    private fun missing(list: List<String>): Array<String> =
        list.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    /** Step 0: normal runtime permissions -> 1: background location -> 2: all-files access -> 3: overlay -> 4: notifications on. */
    private fun nextPermissionStep() {
        while (permStep < 5) {
            val step = permStep++
            when (step) {
                0 -> {
                    val m = missing(runtimePermissions())
                    if (m.isNotEmpty()) { requestPermissions(m, 100); return }
                }
                1 -> if (Build.VERSION.SDK_INT >= 29) {
                    val m = missing(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    if (m.isNotEmpty()) { requestPermissions(m, 101); return }
                }
                2 -> if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (e: Throwable) {
                        try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (e2: Throwable) { }
                    }
                    return // continue from onResume() when the user comes back
                }
                3 -> if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (e: Throwable) { }
                    return
                }
                // needed so the "app is not running" notification (tap = destroy app) can be shown
                4 -> if (!notificationsAllowed()) {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        )
                    } catch (e: Throwable) { }
                    return
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        nextPermissionStep()
    }

    private var pausedForSettings = false

    override fun onPause() {
        super.onPause()
        if (permStep in 3..5) pausedForSettings = true
    }

    override fun onResume() {
        super.onResume()
        if (pausedForSettings) {
            pausedForSettings = false
            nextPermissionStep()
        }
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(22))
            val r = dp(26).toFloat()
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Palette.PRIMARY_DARK, Palette.PRIMARY)
            ).apply { setCornerRadii(floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)) }
        }
        header.addView(tv("🌀 Parallel", 28f, Color.WHITE, bold = true))
        header.addView(tv("একই অ্যাপ, আলাদা অ্যাকাউন্ট", 14f, Color.parseColor("#E0DDFF")))
        subtitle = tv("", 13f, Color.WHITE).apply { setPadding(0, dp(10), 0, 0) }
        header.addView(subtitle)
        column.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        status = tv("", 14f, Palette.TEXT, bold = true).apply {
            background = roundBg(Palette.AMBER, 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
        }
        column.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(16), dp(12), dp(16), 0)
        })

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(110))
        }
        column.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val add = tv("➕  অ্যাপ ক্লোন করুন", 16f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = roundBg(Palette.PRIMARY, 30)
            setPadding(dp(28), dp(16), dp(28), dp(16))
            elevation = dp(6).toFloat()
            setOnClickListener { showPicker() }
        }
        root.addView(add, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(26)
        })
        root.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION")
            val top = ins.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = ins.systemWindowInsetBottom
            header.setPadding(dp(22), dp(26) + top, dp(22), dp(22))
            (add.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(26) + bottom
            add.requestLayout()
            ins
        }
        return root
    }

    // ------------------------------------------------------------------ clone list

    private fun refresh() {
        Engine.io.execute {
            val items = Engine.listClones()
            ui.post { render(items) }
        }
    }

    private fun render(items: List<CloneItem>) {
        subtitle.text = if (items.isEmpty()) "কোনো ক্লোন নেই" else "📦 মোট ক্লোন: ${items.size}"
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(tv("📭", 56f).apply { gravity = Gravity.CENTER; setPadding(0, dp(60), 0, dp(8)) })
            list.addView(tv("এখনো কোনো অ্যাপ ক্লোন করা হয়নি", 16f, Palette.TEXT, bold = true).apply { gravity = Gravity.CENTER })
            list.addView(tv("নিচের ➕ বাটনে চাপ দিয়ে শুরু করুন", 13f, Palette.MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
            return
        }
        for (item in items) list.addView(cloneRow(item))
    }

    private fun appIcon(icon: Drawable?, sizeDp: Int): View =
        if (icon != null) {
            ImageView(this).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            }
        } else {
            tv("📦", 26f).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            }
        }

    private fun cloneRow(item: CloneItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(Palette.CARD, 18)
            setPadding(dp(14), dp(12), dp(10), dp(12))
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        row.addView(appIcon(item.icon, 46))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        info.addView(tv(item.label, 16f, Palette.TEXT, bold = true).apply { oneLine() })
        info.addView(tv("স্পেস ${item.userId + 1} · ${item.packageName}", 11f, Palette.MUTED).apply { oneLine() })
        row.addView(info)

        row.addView(emojiButton("▶️", Palette.GREEN) { openClone(item) })
        row.addView(emojiButton("🧹", Palette.AMBER) { confirm("🧹 ডেটা মুছবেন?", "${item.label} (স্পেস ${item.userId + 1}) এর সব ডেটা মুছে যাবে।") { clearClone(item) } })
        row.addView(emojiButton("🗑️", Palette.RED) { confirm("🗑️ ক্লোন মুছবেন?", "${item.label} (স্পেস ${item.userId + 1}) ক্লোনটি ডেটাসহ মুছে যাবে।") { removeClone(item) } })
        return row
    }

    // ------------------------------------------------------------------ actions

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun setStatus(msg: String?) {
        if (msg == null) {
            status.visibility = View.GONE
        } else {
            status.text = msg
            status.visibility = View.VISIBLE
        }
    }

    private fun openClone(item: CloneItem) {
        Engine.io.execute {
            val ok = Engine.launch(item.packageName, item.userId)
            if (!ok) ui.post { toast("❌ অ্যাপটি খোলা যায়নি") }
        }
    }

    private fun clearClone(item: CloneItem) {
        setStatus("⏳ ডেটা মোছা হচ্ছে…")
        Engine.io.execute {
            Engine.clearData(item.packageName, item.userId)
            ui.post { setStatus(null); toast("✅ ডেটা মোছা হয়েছে") }
        }
    }

    private fun removeClone(item: CloneItem) {
        setStatus("⏳ মোছা হচ্ছে…")
        Engine.io.execute {
            Engine.remove(item.packageName, item.userId)
            ui.post { setStatus(null); refresh() }
        }
    }

    private fun startClone(app: DeviceApp) {
        setStatus("⏳ ${app.label} ক্লোন হচ্ছে…")
        Engine.io.execute {
            val error = Engine.clone(app.packageName)
            ui.post {
                setStatus(null)
                toast(if (error == null) "✅ ${app.label} ক্লোন হয়েছে" else "❌ $error")
                refresh()
            }
        }
    }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("হ্যাঁ") { _, _ -> onYes() }
            .setNegativeButton("না", null)
            .show()
    }

    // ------------------------------------------------------------------ picker

    private fun showPicker() {
        val search = EditText(this).apply {
            hint = "🔍 অ্যাপ খুঁজুন"
            setSingleLine()
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ScrollView(context).apply { addView(box) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(380)))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("➕ কোন অ্যাপ ক্লোন করবেন?")
            .setView(wrap)
            .setNegativeButton("বাতিল", null)
            .create()

        var apps: List<DeviceApp> = emptyList()
        fun fill() {
            val q = search.text.toString().trim()
            box.removeAllViews()
            val shown = apps.filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
            if (shown.isEmpty()) {
                box.addView(tv("কিছু পাওয়া যায়নি", 14f, Palette.MUTED).apply { setPadding(0, dp(20), 0, 0) })
                return
            }
            for (app in shown) {
                box.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                    addView(appIcon(app.icon, 40))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), 0, 0, 0)
                        addView(tv(app.label, 15f, Palette.TEXT, bold = true).apply { oneLine() })
                        addView(tv(app.packageName, 11f, Palette.MUTED).apply { oneLine() })
                    })
                    setOnClickListener { dialog.dismiss(); startClone(app) }
                })
            }
        }

        box.addView(tv("⏳ লোড হচ্ছে…", 14f, Palette.MUTED).apply { setPadding(0, dp(20), 0, 0) })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = fill()
        })
        dialog.show()

        Engine.io.execute {
            val loaded = Engine.deviceApps()
            ui.post {
                apps = loaded
                if (!isFinishing) fill()
            }
        }
    }
}
