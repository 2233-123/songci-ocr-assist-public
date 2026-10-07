package com.songci.assist

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮窗生命周期。
 *
 * **两个独立 window**（设计文档 §5）：
 *  - 高亮层 [TYPE_APPLICATION_OVERLAY] + `FLAG_NOT_TOUCHABLE` + `FLAG_NOT_FOCUSABLE`：完全不挡操作；
 *  - 悬浮球单独一个可触摸 window（`FLAG_NOT_FOCUSABLE`，保留可触摸）。
 * 合在一个 window 里会让悬浮球也点不动，所以必须拆开。
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences

    private var overlayView: OverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var ballView: FloatingBallView? = null
    private var ballParams: WindowManager.LayoutParams? = null

    /** 状态条（"应选：X"）单独一个**固定位置**的窗口，见 [applyStatusLayout] */
    private var statusView: StatusBarView? = null
    private var statusParams: WindowManager.LayoutParams? = null

    /** 暂停：不再画高亮/状态条，浮层仍然存在。 */
    @Volatile
    var paused: Boolean = false
        private set

    /** 顶部状态条开关（偏好持久化）。 */
    var showStatusBar: Boolean
        get() = prefs.getBoolean(PREF_SHOW_STATUS, true)
        set(value) {
            prefs.edit().putBoolean(PREF_SHOW_STATUS, value).apply()
            statusView?.barEnabled = value
            applyStatusLayout()
        }

    /** 调试面板开关（偏好持久化；排查「为什么没出框」）。 */
    var debugMode: Boolean
        get() = prefs.getBoolean(PREF_DEBUG, false)
        set(value) {
            prefs.edit().putBoolean(PREF_DEBUG, value).apply()
            overlayView?.debugMode = value
            if (!value) overlayView?.debugLines = emptyList()
        }

    private var lastOutcome: FrameOutcome? = null
    private var dragged = false
    private var ballPlaced = false
    private var ballMoving = false

    /** onDestroy 之后不能再弹窗/更新布局 */
    @Volatile
    private var destroyed = false

    private val main = Handler(Looper.getMainLooper())

    private val clearRunnable: Runnable by lazy {
        Runnable {
            lastOutcome = null
            overlayView?.clear()
            setStatusText(null)
            // 内容清空 → 窗口收成 0×0，屏幕上不留任何遮挡
            applyOverlayLayout()
        }
    }

    private val context get() = this

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                if (!Settings.canDrawOverlays(this)) {
                    Log.w(TAG, "没有悬浮窗权限，OverlayService 不显示")
                    // 悬浮层没了，取帧也没有意义：一起停掉，别白耗电
                    CaptureService.stop(this)
                    stopSelf()
                    return START_NOT_STICKY
                }
                show()
            }

            ACTION_HIDE -> stopSelf()
            // 系统重建服务（intent 为 null）时不要留一个没有窗口的空服务
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ 显示 / 移除
    private fun show() {
        if (overlayView == null) {
            val view = OverlayView(this).apply {
                debugMode = this@OverlayService.debugMode
            }
            // **不用全屏窗口**：窗口尺寸先给 0，等有内容时再按内容包围盒调整。
            //
            // 早期是 MATCH_PARENT 全屏窗（虽然带 FLAG_NOT_TOUCHABLE），但实测在
            // 华为 HarmonyOS 上会让下层游戏收不到点击 —— 改为「按内容定尺寸的窄窗口」，
            // 从根源上规避 OEM 的触摸遮挡判定（详见 OverlayView 类注释）。
            val params = WindowManager.LayoutParams(
                0,
                0,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            windowManager.addView(view, params)
            overlayView = view
            overlayParams = params
            // 初始没有内容 → 让窗口收成 0×0，屏幕上不留任何遮挡
            applyOverlayLayout()
        }

        if (statusView == null) {
            val status = StatusBarView(this)
            // **固定位置**的状态条窗口：宽度整屏、位置恒为屏幕顶部正中。
            // 不做成随内容移动的窗口，否则高亮框一动它就跟着动（用户反馈过）。
            // 虽然铺满屏幕宽度，但带 FLAG_NOT_TOUCHABLE（可触摸区域为空），不挡游戏。
            val params = WindowManager.LayoutParams(
                0,
                0,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            windowManager.addView(status, params)
            statusView = status
            statusParams = params
            applyStatusLayout()
        }

        if (ballView == null) {
            val ball = FloatingBallView(this).apply { isClickable = true }
            val size = (Config.BALL_SIZE_DP * resources.displayMetrics.density).roundToInt()
            val params = WindowManager.LayoutParams(
                size,
                size,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt(PREF_BALL_X, -1)
                y = prefs.getInt(PREF_BALL_Y, -1)
            }
            ball.setOnTouchListener { v, e -> onBallTouch(v, e, params) }
            windowManager.addView(ball, params)
            ballView = ball
            ballParams = params
            ballPlaced = false
            // 等 window 完成一次布局后再摆右下角初始位置
            main.postDelayed({ placeBallIfNeeded() }, 100L)
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /**
     * 高亮层窗口：**整屏、位置钉死在 (0,0)、永不移动**。
     *
     * ### 为什么不再"按内容包围盒缩小窗口"（用户反馈"框要从左上角飞过来"）
     *
     * 原实现让窗口贴合内容包围盒（无内容时收成 0×0）。但**悬浮窗的
     * `params.x/y/width/height` 一旦变化，`WindowManager` 默认会播放窗口移动动画** ——
     * 于是每次出框，窗口都从上一个位置（左上角）带着动画滑到目标位置。
     *
     * 用户明确要求：「让高亮框直接出现在对应位置，不要有移动动画」。
     *
     * ### 修法
     *
     * 窗口尺寸固定为整屏、`x/y` 固定为 0 —— **窗口几何再也不变**，
     * `WindowManager` 就没有可动画的对象；每次只是 `view.invalidate()` 重画
     * （高亮框是画布上的内容，不是窗口本身）。这样框就是**直接出现在目标位置**。
     *
     * 因为窗口恒定，`updateViewLayout` 在第一帧之后不会再被调用（下面有提前返回），
     * 每帧只走 `applyLayout` + `invalidate`。
     *
     * ### 会不会又挡住游戏按钮？
     *
     * 不会。本窗口带 `FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE`，触摸事件直接穿透；
     * 当初"华为/鸿蒙按钮点不动"的根因是 **`MATCH_PARENT` 的触摸遮挡判定**，
     * 正确修法是这两个 flag（已经加上了），而**不是**把窗口缩小。
     * 缩窗口只是绕开，代价就是现在这个飞行动画。
     *
     * ### 状态条为什么仍单独一个窗口
     *
     * 见 [applyStatusLayout]：两者的**包围盒原本被并成一个窗口**，导致高亮框一动、
     * 状态条在屏幕上就跟着跑。拆开是必须的，与本次改动无关。
     */
    /**
     * **真实物理显示尺寸**（`getRealMetrics`），不是应用窗口尺寸。
     *
     * ### 为什么必须用它
     *
     * 高亮坐标是**按捕获帧的尺寸归一化**的，而捕获面（`ScreenFrameReader`）用的是
     * `Display.getRealMetrics()` = 真实物理显示。若这里用 `resources.displayMetrics`
     * （应用窗口尺寸），两端尺寸不一致，框就会按比例偏掉。
     *
     * 真机实测这两个值**确实不同**：
     *
     * ```
     * getRealMetrics       : 2608 x 1200   <- 帧 / 归一化基准
     * resources.displayMetrics: 2464 x 1152   <- 应用窗口（之前拿来当窗口尺寸了）
     * ```
     *
     * 于是气泡 x=0.66 被算成 `0.66*2464 = 1626px`，而正确位置是 `0.66*2608 = 1721px` ——
     * **横向偏约 95px，框画不中气泡**。
     *
     * 这个坑在 [ScreenFrameReader] 的注释里早就记过（"用 displayMetrics 会拿到
     * 应用窗口尺寸"），但只修了捕获侧，没同步修悬浮窗侧。
     */
    private fun realDisplaySize(): android.graphics.Point {
        val p = android.graphics.Point()
        runCatching {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.getRealMetrics(
                android.util.DisplayMetrics().also { dm -> p.set(dm.widthPixels, dm.heightPixels) },
            )
        }
        if (p.x > 0 && p.y > 0) return p
        // 兜底：真实尺寸取不到时退回应用窗口尺寸（至少不会崩）
        val dm = resources.displayMetrics
        return android.graphics.Point(
            dm.widthPixels.coerceAtLeast(1),
            dm.heightPixels.coerceAtLeast(1),
        )
    }

    private fun applyOverlayLayout() {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        if (destroyed) return

        val size = realDisplaySize()
        val screenW = size.x
        val screenH = size.y
        if (screenW <= 0 || screenH <= 0) return

        // **窗口整屏 ⇒ 画布原点就在屏幕 (0,0) ⇒ 绘制原点必须是 (0,0)。**
        //
        // 这里曾经调用 `layoutFor` 拿它算出的 `contentOriginX/Y`。那套公式是给
        // 「贴合内容包围盒的窄窗口」用的：窗口顶边在 `bounds.top - padding`，
        // 所以内容坐标要向下平移同样距离才落进窗口（`contentOriginY = -top`）。
        //
        // 窗口改成恒定整屏之后这个偏移就成了 bug —— 真机诊断值：
        //
        //     几何: view=2464x1152 content=2464x1152 origin=(0,-444) hl=(0.66,0.42,0.70,0.45)
        //
        // `originY = -444` 把本该画在 y≈516px 的框抬到 72px（接近屏幕顶部），
        // 调试面板也被抬到左上角 —— 表现就是「框只出现在左上角」。
        //
        // 现在直接强制 (0,0)：内容坐标 == 画布坐标 == 真实屏幕坐标，不再有任何偏移。
        view.applyLayout(
            OverlayView.WindowLayout(
                x = 0,
                y = 0,
                width = screenW,
                height = screenH,
                contentOriginX = 0,
                contentOriginY = 0,
                screenW = screenW,
                screenH = screenH,
            ),
        )

        if (params.width == screenW && params.height == screenH &&
            params.x == 0 && params.y == 0
        ) {
            return
        }
        params.width = screenW
        params.height = screenH
        params.x = 0
        params.y = 0
        safeUpdate(view, params)
    }

    /**
     * 状态条窗口：**永远钉在屏幕顶部正中，宽度铺满屏幕、高度按内容自适应**。
     *
     * 为什么单独一个窗口：之前状态条和高亮框共用一个窗口，而那个窗口的位置是两者
     * 包围盒的**并集**，于是高亮框一移动、状态条在屏幕上就跟着跑
     * （用户反馈"顶部的框会动来动去"）。拆开后窗口位置只由分辨率决定，
     * 高亮框再怎么动都不会影响它。
     */
    private fun applyStatusLayout() {
        val view = statusView ?: return
        val params = statusParams ?: return
        if (destroyed) return

        val screenW = resources.displayMetrics.widthPixels
        if (screenW <= 0) return
        val height = view.statusWindowHeightPx()

        if (params.width == screenW && params.height == height &&
            params.x == 0 && params.y == 0
        ) {
            return
        }
        params.width = screenW
        params.height = height
        params.x = 0
        params.y = 0
        safeUpdate(view, params)
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(clearRunnable)
        main.removeCallbacks(quitRunnable)
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        statusView?.let { runCatching { windowManager.removeView(it) } }
        ballView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        statusView = null
        ballView = null
        ballParams = null
        overlayParams = null
        statusParams = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 来自流水线的结果
    /**
     * 由 [CaptureService] 在流水线线程调用；内部切到主线程。
     *
     * @param frameAt 该结论对应的帧时刻（[android.os.SystemClock.elapsedRealtime]），
     *                太旧的结果丢弃，避免快速切页时画出过期的框。
     */
    fun onFrameOutcome(outcome: FrameOutcome, frameAt: Long, diag: FrameDiag? = null) {
        main.post {
            if (paused) return@post
            if (frameAt > 0 && android.os.SystemClock.elapsedRealtime() - frameAt > STALE_FRAME_MS) {
                return@post
            }
            // 悬浮球的实心/空心跟着真实取帧状态走（用户一眼能看出有没有在跑）
            ballView?.running = CaptureService.isRunning
            if (diag != null) {
                overlayView?.debugLines = diagLines(diag, outcome)
            }
            applyOutcome(outcome)
        }
    }

    /** 调试面板内容：帧数 / 顶部 OCR / 匹配结果 / **窗口与绘制几何**。 */
    private fun diagLines(diag: FrameDiag, outcome: FrameOutcome): List<String> {
        val status = when (outcome) {
            is FrameOutcome.Hit -> "命中 → ${outcome.pai}（已框住气泡）"
            is FrameOutcome.PaiOnly -> "认出词牌 ${outcome.pai}，但屏上没找到气泡"
            is FrameOutcome.NoMatch -> "首句未匹配"
            is FrameOutcome.Empty -> "本帧无文本"
        }
        // 几何诊断：改了「窗口恒整屏」之后出现"框只在左上角"的问题，
        // 需要把窗口尺寸、view 实测尺寸、绘制原点、内容坐标尺寸一起打出来才能定位。
        val g = overlayView?.geometryDesc() ?: "（无 view）"
        return listOf(
            "帧 #${diag.frameCount}${if (diag.idle) " (省电)" else ""}",
            "顶部OCR: ${diag.headText.ifEmpty { "（空）" }}",
            if (diag.pai.isNotEmpty()) "匹配: ${diag.pai}  sim=%.2f".format(diag.similarity) else "匹配: 无",
            "状态: $status",
            "几何: $g",
        )
    }

    private fun applyOutcome(outcome: FrameOutcome) {
        val view = overlayView ?: return
        // 每帧都从偏好同步一次：开关可能是在悬浮层已经跑起来之后才打开的，
        // 只靠 setter 会在「先点开始、后开调试」的顺序下永远看不到面板
        val dbg = debugMode
        if (view.debugMode != dbg) view.debugMode = dbg
        // 每次重画前先把自己画在哪报给流水线 —— 状态条文案里含词牌名，
        // 不排除的话会被当成气泡选中，并自锁导致框永不消失（见 publishSelfDrawnBounds）
        publishSelfDrawnBounds()
        when (outcome) {
            is FrameOutcome.Empty, is FrameOutcome.NoMatch -> {
                lastOutcome = null
                main.removeCallbacks(clearRunnable)
                // 调试模式下保留诊断文本，否则「没命中」时面板会一闪就没，看不到原因
                if (dbg) {
                    view.highlight = null
                    setStatusText(null)
                    applyOverlayLayout()
                    main.postDelayed(clearRunnable, DEBUG_TTL_MS)
                } else {
                    view.clear()
                    setStatusText(null)
                    applyOverlayLayout()
                }
                return
            }

            is FrameOutcome.Hit, is FrameOutcome.PaiOnly -> {
                if (outcome == lastOutcome && !dbg) {
                    // 同一结论重复推送：只续期，不重画
                    main.removeCallbacks(clearRunnable)
                    main.postDelayed(clearRunnable, Config.HIGHLIGHT_TTL_MS)
                    return
                }
                lastOutcome = outcome
                view.highlight = (outcome as? FrameOutcome.Hit)?.target
                setStatusText(statusTextOf(outcome))
                // 内容变了 → 重新贴合窗口（状态条窗口位置固定，只可能变高度）
                applyOverlayLayout()
                main.removeCallbacks(clearRunnable)
                // 连续无命中（例如已经选完）→ 淡出
                main.postDelayed(clearRunnable, if (dbg) DEBUG_TTL_MS else Config.HIGHLIGHT_TTL_MS)
            }
        }
    }

    /**
     * 更新顶部状态条。
     *
     * 状态条在**独立窗口**里（见 [StatusBarView]），窗口位置固定、只在文案变化时
     * 调整高度，因此它不会像以前那样被高亮框的位置带着来回移动。
     */
    private fun setStatusText(text: String?) {
        val view = statusView ?: return
        view.barEnabled = showStatusBar
        view.paused = paused
        view.text = text
        applyStatusLayout()
    }

    private fun statusTextOf(outcome: FrameOutcome): String = when (outcome) {
        is FrameOutcome.Hit -> context.getString(
            R.string.status_hit_format,
            outcome.headText,
            outcome.pai,
            outcome.similarity,
        )

        is FrameOutcome.PaiOnly -> context.getString(R.string.status_pai_only_format, outcome.pai)
        else -> ""
    }

    /**
     * 把自己画在屏幕上的区域（归一化）告诉流水线，让它**排除**这些文字。
     *
     * ### 为什么必须做（用户反馈 + 离线验证）
     *
     * 用户：「择律后回到主界面，高亮框却没即时消失…旧的高亮框就会误导玩家」，
     * 并判断「就是因为你 OCR 识别到了自己的提示框」。
     *
     * 状态条画的是 `应选：蝶恋花（屏上没找到选项气泡）`。OCR 一旦在括号处断块，
     * 就产出**恰好等于词牌名的块** —— 而 `scanOptions` 的第一优先判据正是
     * "整块恰好等于词牌名" → 框画到 App 自己的文案上，并且**自锁**：
     * 框在状态条上 → 文字被读回 → 继续命中 → `HIGHLIGHT_TTL_MS` 不断重置 → 框永不消失。
     *
     * 所以这里把两个自绘区域报上去：
     * - **状态条**：屏幕顶部整条（高度按内容自适应）
     * - **调试面板**：`OverlayView` 里固定在 `DEBUG_TOP_RATIO` 起的一块
     */
    private fun publishSelfDrawnBounds() {
        val p = PipelineHolder.get() ?: return
        val screenH = realDisplaySize().y.coerceAtLeast(1)
        val out = ArrayList<FloatArray>(2)

        // 1) 状态条：顶部整条（高度按内容自适应）
        statusView?.let { sv ->
            if (showStatusBar && !sv.text.isNullOrBlank()) {
                val h = sv.statusWindowHeightPx().coerceAtLeast(1)
                out += floatArrayOf(0f, 0f, 1f, (h.toFloat() / screenH).coerceIn(0f, 0.5f))
            }
        }

        // 2) 调试面板：从 DEBUG_TOP_RATIO 起，占屏幕一部分
        overlayView?.let { ov ->
            if (ov.debugMode && ov.debugLines.isNotEmpty()) {
                val top = OverlayView.DEBUG_TOP_RATIO
                out += floatArrayOf(0f, top, 0.6f, (top + 0.12f).coerceAtMost(1f))
            }
        }

        p.selfDrawnBounds = out
    }

    // ------------------------------------------------------------------ 悬浮球手势
    private fun onBallTouch(v: View, event: MotionEvent, params: WindowManager.LayoutParams): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragged = false
                ballMoving = false
                main.postDelayed(quitRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX.roundToInt() - params.x
                val dy = event.rawY.roundToInt() - params.y
                if (!dragged && abs(dx) + abs(dy) > touchSlop) {
                    dragged = true
                    main.removeCallbacks(quitRunnable)
                }
                if (dragged) {
                    params.x = event.rawX.roundToInt() - v.width / 2
                    params.y = event.rawY.roundToInt() - v.height / 2
                    safeUpdate(v, params)
                    ballMoving = true
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(quitRunnable)
                if (dragged || ballMoving) {
                    snapAndSave(v, params)
                } else if (CaptureService.isRunning) {
                    togglePause()
                } else {
                    // 还没开始取帧：直接拉起录屏授权（透明页，不用离开游戏）
                    requestCaptureFromOverlay()
                }
                return true
            }
        }
        return false
    }

    private val quitRunnable: Runnable by lazy {
        Runnable {
            main.removeCallbacks(quitRunnable)
            confirmQuit()
        }
    }

    private val touchSlop: Int get() = ViewConfiguration.get(this).scaledTouchSlop

    private fun safeUpdate(v: View, params: WindowManager.LayoutParams) {
        runCatching { windowManager.updateViewLayout(v, params) }
    }

    /** 靠近边缘时吸附，并持久化位置。 */
    private fun snapAndSave(v: View, params: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        val snap = (Config.BALL_SNAP_DP * metrics.density).roundToInt()
        val maxX = (metrics.widthPixels - v.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - v.height).coerceAtLeast(0)
        if (params.x <= snap) params.x = 0
        if (params.x >= maxX - snap) params.x = maxX
        if (params.y <= snap) params.y = 0
        if (params.y >= maxY - snap) params.y = maxY
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
        safeUpdate(v, params)
        prefs.edit().putInt(PREF_BALL_X, params.x).putInt(PREF_BALL_Y, params.y).apply()
    }

    /** 首次显示时把球放到右下角（需要知道屏幕尺寸，放在主线程做一次）。 */
    private fun placeBallIfNeeded() {
        if (ballPlaced) return
        val v = ballView ?: return
        val params = ballParams ?: return
        if (params.x < 0 || params.y < 0) {
            val metrics = resources.displayMetrics
            params.x = metrics.widthPixels - v.width - (24 * metrics.density).roundToInt()
            params.y = (metrics.heightPixels * 0.72f).roundToInt()
            safeUpdate(v, params)
            prefs.edit().putInt(PREF_BALL_X, params.x).putInt(PREF_BALL_Y, params.y).apply()
        }
        ballPlaced = true
    }

    /**
     * 悬浮球单击：**开始 / 暂停取帧**。
     *
     * 注意分工（用户明确要求）：
     * - 脚本里的「开始」= 建立投屏（录屏会话）
     * - 悬浮球单击 = 真正开始**取帧**（把画面送去 OCR 匹配）
     *
     * 所以投屏刚建立时是「待取帧」状态（`reading = false`），用户在游戏里点一下球
     * 才开始读数 —— 这样既不会把脚本画面当成题目，也不用来回切窗口。
     */
    private fun togglePause() {
        paused = !paused
        ballView?.paused = paused
        overlayView?.paused = paused
        if (paused) {
            overlayView?.clear()
            setStatusText(null)
        } else {
            lastOutcome = null
        }
        EventLog.log("ball.tap", if (paused) "暂停取帧" else "开始取帧")
        Log.i(TAG, "悬浮球点击：paused=$paused")
    }

    /**
     * 投屏还没建立时点球（老流程兜底）：拉起透明的 [CaptureBridgeActivity] 建立投屏。
     * 现在主流程是脚本里先点「开始」，这里只作为备用入口。
     */
    private fun requestCaptureFromOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "没有悬浮窗权限，无法开始取帧")
            return
        }
        EventLog.log("ball.tap", "请求开始取帧（透明桥接页）")
        runCatching {
            val intent = Intent(this, CaptureBridgeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }.onFailure {
            EventLog.log("ball.fail", "拉起授权失败：${it.javaClass.simpleName}")
        }
    }

    /**
     * 长按悬浮球 = 停止取帧并退出提示器。
     *
     * 以前长按弹确认框，现在直接停 —— 因为「停止」这个操作在游戏里就要能一键完成，
     * 弹框反而多一步；误触风险由长按本身（500ms）兜住。
     */
    private fun confirmQuit() {
        if (destroyed) return
        EventLog.log("ball.longPress", "停止取帧并退出")
        CaptureService.stop(this)
        stopSelf()
    }

    /**
     * 最近任务被划掉时**只清掉悬浮层**，不要连带把取帧服务也停掉。
     *
     * 之前这里调了 `CaptureService.stop()`：在分屏/切后台等场景下任务被移除时，
     * 取帧服务会跟着一起被杀，表现就是"刚授权就开始就变成已停止"。
     * 取帧该不该停由用户显式操作（通知栏「停止」/ 悬浮球长按退出）决定。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        EventLog.log("overlay.onTaskRemoved", "只移除悬浮层，保留取帧服务")
        Log.i(TAG, "onTaskRemoved：只移除悬浮层，保留取帧服务")
        stopSelf()
    }

    companion object {
        private const val TAG = "OverlayService"
        const val ACTION_SHOW = "com.songci.assist.action.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.songci.assist.action.HIDE_OVERLAY"

        /** 偏好文件名与键；MainActivity / CaptureService 读写同一份 */
        const val PREFS = "songci_overlay"
        private const val PREF_BALL_X = "ball_x"
        private const val PREF_BALL_Y = "ball_y"
        const val PREF_SHOW_STATUS = "show_status_bar"
        const val PREF_DEBUG = "debug_mode"

        /** 一帧结论超过这个时长才送达就不画了（切换页面时防误报） */
        private const val STALE_FRAME_MS = 1_500L

        /** 调试面板的存活时间（比正常高亮长，方便看清） */
        private const val DEBUG_TTL_MS = 1_200L

        /**
         * 高亮层窗口在内容包围盒外扩多少 dp。
         *
         * 留一点余量，避免描边（以及抗锯齿）被窗口边缘裁掉。
         */
        private const val OVERLAY_WINDOW_PADDING_DP = 8f

        /** 是否开启调试面板（[CaptureService] 据此决定要不要每帧算诊断） */
        fun isDebugEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_DEBUG, false)

        /** [CaptureService] 用它把结果送进悬浮层；没有悬浮层时为 null。 */
        @Volatile
        var instance: OverlayService? = null
            private set

        fun show(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_SHOW),
            )
        }

        fun hide(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_HIDE),
            )
        }
    }
}
