package com.songci.assist

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 引导页：三项授权（悬浮窗 / 通知 / 屏幕录制）+ 开始/停止 + 状态。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var overlayStatus: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var captureStatus: TextView
    private lateinit var stateText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var diagBox: TextView

    /** 从悬浮球开始取帧的时间点；用于让本页"路过"时自动让出前台 */
    private var captureStartedAt: Long? = null

    private companion object {
        /** 这段时间内发生的 onPause 视为"桥接页交接"，不把用户留在本页 */
        const val BRIDGE_HANDOFF_MS = 5000L
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        EventLog.log("activityResult", "resultCode=${result.resultCode} data=${data != null}")
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            captureStatus.text = getString(R.string.perm_state_requesting)
            // 先起悬浮层（取帧结果的落点），再起取帧服务；两者都可能失败，失败会回传状态
            runCatching { OverlayService.show(this) }
            runCatching { CaptureService.start(this, result.resultCode, data) }
                .onFailure {
                    captureStatus.text = getString(R.string.cap_state_failed, it.javaClass.simpleName)
                }
        } else {
            captureStatus.text = getString(R.string.perm_state_denied)
            renderRunning(false)
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { renderPermissionStates() }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { renderPermissionStates() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        startButton.setOnClickListener { startProjection() }
        stopButton.setOnClickListener { stopEverything() }

        // 取帧链路的状态回传（连接中 / 运行中 / 失败原因 / 已停止）。
        // 这条链路以前是静默的：失败时界面就一直停在「等待系统确认」。
        CaptureService.receive = { state ->
            runOnUiThread {
                if (::captureStatus.isInitialized) captureStatus.text = state
                if (::captureStatus.isInitialized) {
                    renderRunning(state.startsWith(capStateRunningPrefix()))
                }
            }
        }
        // 有新诊断就刷新（不依赖悬浮层能不能画出来）
        CaptureService.onDiag = {
            runOnUiThread { renderDiag() }
        }
    }

    override fun onResume() {
        super.onResume()
        EventLog.log("activity.onResume", "")
        renderPermissionStates()
        renderCaptureState()
        showIndexState()
        renderDiag()
        IndexHolder.get(this) { showIndexState() }
    }

    override fun onPause() {
        EventLog.log("activity.onPause", "")
        // 从悬浮球开始取帧时，本页只是"路过"——启动后立刻让出前台，别把用户
        // 从游戏里拽出来（Android 16 一换方向就停投屏，所以不能有界面跳转）
        captureStartedAt?.let {
            if (SystemClock.elapsedRealtime() - it < BRIDGE_HANDOFF_MS && CaptureService.isRunning) {
                EventLog.log("activity.yield", "从悬浮球开始取帧，本页退到后台")
                captureStartedAt = null
                moveTaskToBack(true)
            }
        }
        super.onPause()
    }

    /** 由悬浮球/桥接页开始取帧后，需要让出前台的时间点 */
    internal fun markCaptureStartedFromOverlay() {
        captureStartedAt = SystemClock.elapsedRealtime()
    }

    override fun onStop() {
        EventLog.log("activity.onStop", "")
        super.onStop()
    }

    /** 刷新诊断区（来自 CaptureService 的最近一帧或停止原因） */
    private fun renderDiag() {
        if (!::diagBox.isInitialized) return
        val text = CaptureService.diagText
        val reason = CaptureService.stopReason
        diagBox.text = when {
            text != null && reason == null -> text
            text != null -> text + "\n\n（最近一次停止原因：$reason）"
            reason != null -> getString(R.string.diag_no_frame, reason)
            else -> getString(R.string.diag_empty)
        }
    }

    private fun showIndexState() {
        val index = IndexHolder.index
        stateText.text = if (index != null) {
            getString(R.string.state_index_summary, index.verses.size, index.paiList.size)
        } else {
            getString(R.string.state_index_loading, IndexHolder.error ?: "-")
        }
    }

    override fun onDestroy() {
        CaptureService.receive = null
        CaptureService.onDiag = null
        super.onDestroy()
    }

    /**
     * 「开始」= 开始录屏（建立投屏会话）。
     *
     * MediaProjection 的授权框属于 SystemUI，只能由前台 Activity 拉起，所以这一步
     * 必然发生在脚本界面里 —— 与其让悬浮球去拉授权（会把脚本从游戏里拽出来，
     * 白跳一次窗口），不如就在这里堂堂正正地把录屏建立起来。
     *
     * 分工：
     * - 这里的「开始」= 建立投屏（录屏会话开始）
     * - 悬浮球的单击 = 开始/暂停**取帧**（真正把画面送去 OCR 匹配）
     *
     * 这样用户切到游戏后，在游戏里点一下球才开始读画面，不会把脚本画面误当成题目。
     */
    private fun startProjection() {
        if (!Settings.canDrawOverlays(this)) {
            overlayStatus.text = getString(R.string.perm_state_denied)
            toast(getString(R.string.need_overlay_first))
            openOverlaySettings()
            return
        }
        EventLog.log("ui", "点开始：建立投屏（录屏会话）")
        // 悬浮球先摆出来，用户切到游戏后点它才开始取帧
        runCatching { OverlayService.show(this) }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            @Suppress("DEPRECATION")
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast(getString(R.string.battery_already_ok))
            return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure {
            // 厂商 ROM 可能不允许，退回到电池优化列表
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun renderPermissionStates() {
        val overlayOk = Settings.canDrawOverlays(this)
        overlayStatus.text = getString(
            if (overlayOk) R.string.perm_state_granted else R.string.perm_state_denied,
        )
        overlayStatus.setTextColor(color(if (overlayOk) R.color.ok else R.color.bad))

        val notificationOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        notificationStatus.text = getString(
            if (notificationOk) R.string.perm_state_granted else R.string.perm_state_denied,
        )
        notificationStatus.setTextColor(color(if (notificationOk) R.color.ok else R.color.bad))
    }

    private fun renderRunning(running: Boolean) {
        // 「显示悬浮球」始终可用（球已经在挂着时重复点是幂等的）
        startButton.isEnabled = true
        stopButton.isEnabled = running
        stateText.text = getString(
            if (running) R.string.state_running else R.string.state_ball_only,
        )
    }

    /** 渲染当前取帧链路状态（连接中 / 运行中 / 失败原因 / 已停止）。 */
    private fun renderCaptureState() {
        val state = CaptureService.instanceState
        if (state.isNullOrEmpty()) {
            captureStatus.text = getString(R.string.perm_state_unknown)
            return
        }
        captureStatus.text = state
        // 「运行中…」才认为在跑；失败/停止都要把「开始」按钮放出来让用户重试
        renderRunning(state.startsWith(capStateRunningPrefix()))
    }

    /** 「取帧：运行中 …」的固定前缀，用于判断服务是否真的在跑 */
    private fun capStateRunningPrefix(): String =
        getString(R.string.cap_state_running, 0, 0).substringBefore("0x0")

    private fun stopEverything() {
        CaptureService.stop(this)
        OverlayService.hide(this)
        renderRunning(false)
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 与 OverlayService 共用的偏好文件（键也复用，别再写字面量） */
    private fun overlayPrefs() = getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE)

    private fun color(resId: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getColor(resId)
        } else {
            @Suppress("DEPRECATION")
            resources.getColor(resId)
        }

    // ------------------------------------------------------------------ 界面
    private fun buildContentView(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(title(getString(R.string.app_name), 22f))
        root.addView(hint(getString(R.string.intro_scope)))
        root.addView(hint(getString(R.string.intro_disclaimer)))

        overlayStatus = statusLine()
        notificationStatus = statusLine()
        captureStatus = statusLine().apply { text = getString(R.string.perm_state_unknown) }

        root.addView(permissionRow(
            title = getString(R.string.perm_overlay_title),
            desc = getString(R.string.perm_overlay_desc),
            status = overlayStatus,
            buttonText = getString(R.string.perm_overlay_button),
        ) { openOverlaySettings() })

        root.addView(permissionRow(
            title = getString(R.string.perm_notification_title),
            desc = getString(R.string.perm_notification_desc),
            status = notificationStatus,
            buttonText = getString(R.string.perm_notification_button),
        ) { requestNotificationPermission() })

        root.addView(permissionRow(
            title = getString(R.string.perm_capture_title),
            desc = getString(R.string.perm_capture_desc),
            status = captureStatus,
            buttonText = getString(R.string.perm_capture_button),
        ) { startProjection() })

        root.addView(permissionRow(
            title = getString(R.string.perm_battery_title),
            desc = getString(R.string.perm_battery_desc),
            status = statusLine().apply { text = getString(R.string.perm_battery_hint) },
            buttonText = getString(R.string.perm_battery_button),
        ) { requestIgnoreBatteryOptimizations() })

        val statusSwitch = Switch(this).apply {
            text = getString(R.string.pref_show_status_bar)
            isChecked = overlayPrefs().getBoolean(OverlayService.PREF_SHOW_STATUS, true)
            setOnCheckedChangeListener { _, checked ->
                overlayPrefs().edit().putBoolean(OverlayService.PREF_SHOW_STATUS, checked).apply()
            }
        }
        root.addView(statusSwitch, marginTop(dp(8)))

        // 调试面板：排查「为什么没出框」（显示帧数 / 顶部 OCR 文本 / 匹配结果）
        val debugSwitch = Switch(this).apply {
            text = getString(R.string.pref_debug)
            isChecked = overlayPrefs().getBoolean(OverlayService.PREF_DEBUG, false)
            setOnCheckedChangeListener { _, checked ->
                overlayPrefs().edit().putBoolean(OverlayService.PREF_DEBUG, checked).apply()
                OverlayService.instance?.debugMode = checked
                diagBox.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
        root.addView(debugSwitch)
        root.addView(hint(getString(R.string.pref_debug_hint)))

        // 诊断结果：显示在 App 自己界面上，**不依赖悬浮层**
        // （悬浮层要是画不出来，这里仍然能看到 OCR 读到了什么）
        diagBox = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(color(R.color.dim))
            setBackgroundColor(color(R.color.card))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = if (debugSwitch.isChecked) View.VISIBLE else View.GONE
            text = getString(R.string.diag_empty)
        }
        root.addView(diagBox, marginTop(dp(6)))

        // 事件日志：投屏建好/被回收的先后顺序，排查"为什么已停止"最有用
        val logButton = Button(this).apply {
            text = getString(R.string.diag_show_log)
            setOnClickListener {
                EventLog.log("ui", "查看事件日志")
                diagBox.visibility = View.VISIBLE
                diagBox.text = CaptureService.eventLog().ifEmpty { getString(R.string.diag_no_log) }
            }
        }
        root.addView(logButton, marginTop(dp(4)))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        startButton = Button(this).apply {
            text = getString(R.string.action_start)
            isEnabled = true
        }
        stopButton = Button(this).apply {
            text = getString(R.string.action_stop)
            isEnabled = true
        }
        buttons.addView(startButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons, marginTop(dp(8)))

        stateText = statusLine().apply { text = getString(R.string.state_stopped) }
        root.addView(stateText, marginTop(dp(8)))

        root.addView(hint(getString(R.string.manual_checklist_hint)))

        return ScrollView(this).apply { addView(root) }
    }

    private fun title(text: String, sizeSp: Float) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(color(R.color.dim))
        setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
    }

    private fun statusLine() = TextView(this).apply {
        textSize = 13f
    }

    private fun marginTop(px: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = px }

    private fun permissionRow(
        title: String,
        desc: String,
        status: TextView,
        buttonText: String,
        onClick: () -> Unit,
    ): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(color(R.color.card))
        }
        card.addView(TextView(this).apply { text = title; textSize = 16f })
        card.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(color(R.color.dim))
        })
        card.addView(status)
        card.addView(Button(this).apply {
            text = buttonText
            setOnClickListener { onClick() }
        })
        return card
    }
}
