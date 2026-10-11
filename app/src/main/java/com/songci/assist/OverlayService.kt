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
import android.os.SystemClock
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

    /** 上一次是否已就「状态条逼近首句」告警过（去重，避免每帧刷日志） */
    @Volatile
    private var occlusionWarned = false

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
            //
            // **v0.11.1 为消除位移动画又改回了恒定整屏**，于是遮挡问题复发：
            // Android 12+ 的「不可信触摸遮挡」拿**窗口 alpha** 判定，
            // 整屏 NOT_TOUCHABLE 层 alpha=1.0 > 上限 0.80 ⇒ 触摸全被丢弃。
            // 现在用 [Config.OVERLAY_WINDOW_ALPHA] 把窗口 alpha 压到上限之下，
            // 命中官方「足够半透明」例外。详见该常量的注释（含系统日志原文）。
            val params = WindowManager.LayoutParams(
                0,
                0,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    // **监听"玩家在别处点了一下"**（需求3第二项）。
                    //
                    // FLAG_WATCH_OUTSIDE_TOUCH 只让我们**收到** ACTION_OUTSIDE，
                    // 不会消费任何触摸 —— 配合 FLAG_NOT_TOUCHABLE，游戏照常收到点击。
                    // 收到后转成 CaptureService.notifyUserTouch()，结束「命中后降速」。
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                alpha = Config.OVERLAY_WINDOW_ALPHA
                windowAnimations = 0
            }
            // ACTION_OUTSIDE 只在本窗口是"触摸会经过的那一层"时才送达。
            // 这里故意**不**用 setOnTouchListener（那会消费事件），而是在
            // onTouchEvent 之外单独挂一个 listener：本窗口带 NOT_TOUCHABLE，
            // 系统只会把 ACTION_OUTSIDE 发给它，其余事件不会到达，所以不影响游戏。
            view.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    onOutsideTouch(e)
                }
                false // 不消费
            }
            windowManager.addView(view, params)
            overlayView = view
            overlayParams = params
            // 初始没有内容 → 让窗口收成 0×0，屏幕上不留任何遮挡
            applyOverlayLayout()
        }

        if (statusView == null) {
            val status = StatusBarView(this)
            // **可拖动的**状态条窗口（需求1）。
            //
            // 历史脉络（每一次都是用户反馈驱动的）：
            // - 最初与高亮框共用一个窗口 → 高亮框一动、状态条跟着动
            // - 拆成独立固定窗口 → 不再乱动，但**不可拖动**
            // - 收窄到 74% 宽 → 避开左上角，修「游戏返回按钮点不动」
            // - 现在：去掉 FLAG_NOT_TOUCHABLE，**改成可拖动**
            //
            // 去掉 NOT_TOUCHABLE 的代价：落在状态条上的触摸不再传给游戏。
            // 这是"能拖动"必然的代价（悬浮球同理），而且用户拖到不想挡的地方即可。
            //
            // alpha 仍需压到 [Config.OVERLAY_WINDOW_ALPHA]：虽然可触摸窗口不参与
            // "穿透遮挡"判定，但它与高亮层在顶部仍会重叠（见
            // OverlayWindowAlphaTest 里那个 0.91 的事故）。
            val params = WindowManager.LayoutParams(
                0,
                0,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    // **允许把窗口放到屏幕外**（FloatingX 的 LP 默认值也含它）。
                    //
                    // 没有它时窗口被限制在屏内，"拖出屏幕/半隐"这类交互做不了；
                    // 它的 issue #235（允许出界）与 #188（拖不到底）都在这一点上。
                    // 出界范围由 clampStatusPos 在应用层控制，不会真的丢窗口。
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                // **纵向基准用 TOP**：`params.y` 就是屏幕坐标，**与窗口高度无关**。
                // 用 BOTTOM 会让位置依赖高度，而高度必然会变（有文案↔没文案），
                // 于是窗口反复跳、甚至被跳出屏幕（用户报"长条框有时突然消失"）。
                // "能拖到底"改由 FLAG_LAYOUT_NO_LIMITS + clampStatusPos 保证。
                gravity = Gravity.TOP or Gravity.START
                alpha = Config.OVERLAY_WINDOW_ALPHA
                // **关掉窗口自身的动画**：`updateViewLayout` 会播放一次窗口位移动画，
                // 移动期间旧位置的内容已不绘制、新位置还没到 —— 用户看到的就是
                // "长条框突然消失一下又回来"。
                //
                // 同类问题（CSDN《WindowManager 的 updateViewLayout取消动画效果》）：
                // 「悬浮窗移动后再点击按钮，悬浮窗会从移动前的位置移动到移动后的位置」，
                // 其解法是反射设 `PRIVATE_FLAG_NO_MOVE_ANIMATION`。那个字段是私有的，
                // 这里改用**公开 API** `windowAnimations = 0`（不依赖反射，不会被厂商 ROM 拦），
                // 效果等价：窗口不使用进出/移动动画。
                windowAnimations = 0
            }
            setNoMoveAnimation(params)
            status.setOnTouchListener { v, e -> onStatusTouch(v, e, params) }
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
                windowAnimations = 0
            }
            setNoMoveAnimation(params)
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

        val screen = realDisplaySize()
        if (screen.x <= 0) return
        // **保留可拖动高度**：没有文案时窗口不再收成 0（那样就完全摸不到，
        // 用户报告"屏幕上没有对应诗句后长条提示框无法拖动"）。见 StatusBarView.minDragHeightPx。
        val height = view.statusWindowHeightPx(keepDragHandle = true)

        // **收窄窗口，避开左上角/右上角** —— 见 STATUS_WINDOW_WIDTH_RATIO 的注释。
        val w = (screen.x * STATUS_WINDOW_WIDTH_RATIO).toInt().coerceAtLeast(1)

        // 位置：优先用**用户拖动后保存的位置**；没拖过才用默认的"水平居中 + 距顶 TOP_DP"。
        //
        // ### ⚠️ 拖动过程中绝不能重算位置（修过"回弹/拖不动"）
        //
        // 状态条文案每帧都在变（相似度、效果文本），每次变化都会调用本方法。
        // 而拖动期间 `params.x/y` 是**手指的实时位置**，偏好里存的还是**上一次松手的位置**
        // —— 于是每次文案变化都把窗口拽回旧保存值，表现就是：
        //
        // - 拖到下方 → 立刻回弹
        // - 有时干脆"拖不动"（被拽回的速度和拖动一样快）
        //
        // 所以拖动期间**只更新尺寸、不碰 x/y**。位置在松手时保存，松手后才会被这里读到。
        if (statusDragged) {
            if (params.width != w || params.height != height) {
                params.width = w
                params.height = height
                safeUpdate(view, params)
            }
            // **兜底释放**：正常情况下 ACTION_UP 会把 statusDragged 置回 false。
            // 但如果 UP 事件丢失（手势被系统打断、view 被移除等），这个标志会卡住 true，
            // 位置就永远不再受布局管理了。这里用"最后一次 MOVE 的时间"做超时释放。
            if (SystemClock.elapsedRealtime() - lastStatusMoveAt > STATUS_DRAG_STALE_MS) {
                statusDragged = false
                EventLog.log("status.drag", "拖动标志超时释放（未收到 ACTION_UP）")
            }
            return
        }

        val savedX = prefs.getInt(PREF_STATUS_X, Int.MIN_VALUE)
        val savedY = prefs.getInt(PREF_STATUS_Y, Int.MIN_VALUE)
        val defaultX = ((screen.x - w) / 2f).toInt()
        val defaultY = (StatusBarView.TOP_DP * resources.displayMetrics.density).roundToInt()
        // 保存的是**屏幕坐标 y（左上基准）**；写进 params 前按 gravity 换算（见 statusYFromBottom）
        val screenY = if (savedY == Int.MIN_VALUE) defaultY else clampStatusPos(savedY, screen.y)
        val x = if (savedX == Int.MIN_VALUE) defaultX else clampStatusPos(savedX, screen.x)
        val y = screenYToParamsY(screenY, height, screen.y)

        if (params.width == w && params.height == height &&
            params.x == x && params.y == y
        ) {
            return
        }
        params.width = w
        params.height = height
        params.x = x
        params.y = y
        safeUpdate(view, params)
        // **只在几何真的变化时打一次**：用户报"长条框有时突然消失"，
        // 而消失只可能是"窗口被改成 0 高"或"被移出屏幕"这两种。
        // 这行日志把两者都记下来（含当时的 showing 状态与文案长度），
        // 复现一次就能直接定位，不必再靠推理。
        if (height != lastLoggedStatusHeight || w != lastLoggedStatusWidth) {
            lastLoggedStatusHeight = height
            lastLoggedStatusWidth = w
            EventLog.log(
                "status.geom",
                "状态条窗口 ${w}x$height @($x,$y)；barEnabled=${statusView?.barEnabled} " +
                    "paused=$paused 文案=${statusView?.text?.length ?: -1}字 " +
                    "可见=${statusView?.visibility == View.VISIBLE}",
            )
        }
        // 尺寸或位置变了 → 检查有没有挡住首句/选项气泡（拖动后这是主要风险）
        checkStatusBarOcclusion()
        // **位置/尺寸变了必须重报自绘区域** —— 否则排除用的还是旧矩形，
        // App 自己的文案落在"没被排除"的地方就会被当成游戏选项（自锁事故）。
        publishSelfDrawnBounds()
    }

    /** 上一次记录的窗口几何（用于 [applyStatusLayout] 的变化点日志去重） */
    private var lastLoggedStatusWidth = -1
    private var lastLoggedStatusHeight = -1

    /**
     * **状态条遮挡检测**（需求1：不限制拖动方向，改为运行时检测）。
     *
     * 用户明确要求状态条可以任意方向拖动，但拖到两处会出问题：
     *
     * | 挡住的东西 | 后果 |
     * |---|---|
     * | 游戏首句（y ≈ 0.19） | **OCR 读不到首句** → 识别不出新句子（v0.11.0 的事故） |
     * | 选项气泡区（0.30~0.80） | 气泡被挡 → 找不到选项，只能显示"屏上没找到选项气泡" |
     *
     * 所以这里在**拖动结束**与**每次布局变化**时检查，一旦重叠就把事实记进事件日志，
     * 并在状态条上给出提示，让用户自己挪开 —— 不强制限制拖动。
     *
     * 与 [guardHeadOcclusion] 的分工：那个只看"底部贴顶"这一种传统布局；
     * 这个看任意位置下的**完整重叠**，是放开拖动后的必要补充。
     */
    private fun checkStatusBarOcclusion() {
        val view = statusView ?: return
        val params = statusParams ?: return
        if (view.statusWindowHeightPx() <= 0) return

        val screen = realDisplaySize()
        if (screen.x <= 0 || screen.y <= 0) return

        val left = params.x.toFloat() / screen.x
        val right = (params.x + params.width).toFloat() / screen.x
        val top = params.y.toFloat() / screen.y
        val bottom = (params.y + params.height).toFloat() / screen.y

        val problems = ArrayList<String>(2)
        // 1) 首句带：以 GAME_HEAD_LINE_RATIO 为中心的一条窄带
        val headTop = Config.GAME_HEAD_LINE_RATIO - Config.MIN_HEAD_CLEARANCE_RATIO * 2
        val headBottom = Config.GAME_HEAD_LINE_RATIO + Config.MIN_HEAD_CLEARANCE_RATIO * 6
        if (bottom > headTop && top < headBottom) problems += "游戏首句"
        // 2) 选项气泡区
        if (bottom > Config.OPTION_REGION_TOP && top < Config.OPTION_REGION_BOTTOM) problems += "选项气泡区"

        if (problems.isEmpty()) return
        val msg = "状态条（%.2f,%.2f-%.2f,%.2f）挡住了%s，OCR 可能读不到 → 建议拖开".format(
            left, top, right, bottom, problems.joinToString("与"),
        )
        // 去重：同一位置只报一次（拖动会连续触发 layout）
        if (msg == lastOcclusionMsg) return
        lastOcclusionMsg = msg
        EventLog.log("status.occlusion", msg)
    }

    /** 上一次报过的状态条遮挡描述（去重，避免拖动过程中刷爆环形缓冲） */
    private var lastOcclusionMsg: String? = null


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
        // **代际快照**：暂停会让 [hideGeneration] 自增，从而作废"暂停之前发出的"所有回调。
        //
        // 为什么需要它：OCR 是异步的，点击悬浮球那一刻，主线程消息队列里可能已经
        // 排着若干个「已 post、尚未执行」的帧回调。只判断 `paused` 不够严密 ——
        // 那些回调执行时 paused 已是 true 会被拦住，但如果**暂停之后**又有新帧
        // 在极短时间内到达（例如触摸事件与帧回调交错），仍可能把框重新画回来。
        // 代际计数让"暂停前的那一批"整批失效，语义比单个布尔更准确。
        val gen = hideGeneration
        main.post {
            if (paused || gen != hideGeneration) return@post
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

    /**
     * 「隐藏代际」。每次 [hideAllTips] 自增，用于作废此前发出的帧回调。
     *
     * 只在主线程读写（`onFrameOutcome` 的 post 体、`hideAllTips`），所以不需要原子类型。
     */
    private var hideGeneration = 0L

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
        // **恢复可见**：hideAllTips 会把两个 view 置 GONE，这里保证一旦有新结论就重新显示。
        // 放在绘制路径上而不是只放在"恢复取帧"那一处 —— 因为 `paused` 与 visibility
        // 是两套状态，任何一条路径漏改都会导致浮层永久消失。
        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
        statusView?.let { if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE }
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
                setStatusText(statusLineOf(outcome))
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
        // **没匹配到词句时不要让状态条消失**（用户报告："屏幕上没有匹配到对应词句
        // 的时候长条框就是会消失"）。原因：原来这里传 null，而 StatusBarView.onDraw
        // 的判据是 `if (!showing || t.isNullOrBlank()) return` —— 一个像素都不画。
        //
        // 现在改成显示弱化占位条：窗口始终在、位置始终稳、随时能抓住拖动，
        // 同时占位样式是暗底灰字，与真实结论（亮底白字）明显不同，不会误导。
        //
        // 只有"用户关掉状态条"或"暂停"时才真正不显示（那两种是显式操作）。
        view.idleNotice = text.isNullOrBlank() && showStatusBar && !paused
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
     * 状态条完整文案 = 结论 + **词句效果**，**同一行**。
     *
     * ### 为什么必须是同一行（真机事故）
     *
     * v0.11.0 曾把效果做成**第二行**。结果状态条底边压到了游戏的**首句文字**上
     * （游戏把首句固定画在 `y ≈ 0.19`，历次日志实测 60/60 次）。
     *
     * 首句是**取帧时一起被 OCR 的** —— 盖住它就等于让 OCR 读不到首句：
     *
     * ```
     * 该局 95 帧里 90 帧「首句未匹配」   ← 识别不出新句子
     * 同时顶部按钮也点不动了              ← 遮挡层变高
     * ```
     *
     * 用户的原话是「效果就放在原有的单行的那个框里就行了，OCR 又不看那个框，而且位置也够」
     * —— 完全正确：状态条是 App 自己画的，OCR 要读的是**底下的游戏文字**，
     * 而且单行框的横向空间本来就很富余（屏宽 2608px，文案只占约 500px）。
     *
     * 效果与结论用 ` ｜ ` 分隔，例如：
     * ```
     * 应选：江城子（屏上没找到选项气泡） ｜ 军心+4、战斗力+2
     * ```
     */
    private fun statusLineOf(outcome: FrameOutcome): String {
        val head = statusTextOf(outcome)
        val eff = statusSubTextOf(outcome) ?: return head
        return if (head.isEmpty()) eff else "$head$EFFECT_SEPARATOR$eff"
    }

    /**
     * 效果文案：`军心+4、战斗力+2`。
     *
     * 口径（用户确认）：
     * - **不显示「词元」** —— 这一步在生成期就排掉了（effects.json 里根本不含词元）
     * - 显示形式为**名称+数值**，不做游戏原味描述
     * - 只认到首句（`PaiOnly`）也要显示 —— 不必等找到气泡
     */
    private fun statusSubTextOf(outcome: FrameOutcome): String? {
        val effects = when (outcome) {
            is FrameOutcome.Hit -> outcome.effects
            is FrameOutcome.PaiOnly -> outcome.effects
            else -> emptyList()
        }
        if (effects.isEmpty()) return null
        return effects.joinToString("、") { it.label() }
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
     * - **状态条**：它**实际所在的那块矩形**（可拖动后不再是"顶部整条"）
     * - **调试面板**：`OverlayView` 里固定在 `DEBUG_TOP_RATIO` 起的一块
     *
     * ### ⚠️ 状态条必须按**实际位置**上报（放开拖动后的关键修正）
     *
     * 原来这里写死 `floatArrayOf(0f, 0f, 1f, bottomRatio)` —— 假定状态条永远是
     * "屏幕顶部整条"。状态条一旦可拖动，这个假定就不成立：
     * 拖到别处后它**实际画的位置没被排除** → OCR 把 App 自己的文案读回来 →
     * `scanOptions` 命中里面的词牌名 → 框画到自己文字上 →
     * 文字被读回又继续命中 → **框永不消失**（v0.10.x 那个自锁事故会原样复发）。
     *
     * 所以改成读 `statusParams` 的真实几何。这一条是放开拖动的前置条件，
     * 由 `StatusBarSelfDrawnBoundsTest` 守护。
     */
    private fun publishSelfDrawnBounds() {
        val p = PipelineHolder.get() ?: return
        val screen = realDisplaySize()
        val screenH = screen.y.coerceAtLeast(1)
        val screenW = screen.x.coerceAtLeast(1)
        val out = ArrayList<FloatArray>(2)

        // 1) 状态条：**按窗口实际矩形**上报（拖动后位置会变）
        statusView?.let { sv ->
            val params = statusParams
            if (showStatusBar && !sv.text.isNullOrBlank() && params != null) {
                val left = (params.x.toFloat() / screenW).coerceIn(0f, 1f)
                val top = (params.y.toFloat() / screenH).coerceIn(0f, 1f)
                val right = ((params.x + params.width).toFloat() / screenW).coerceIn(0f, 1f)
                val bottom = ((params.y + params.height).toFloat() / screenH).coerceIn(0f, 1f)
                if (right > left && bottom > top) {
                    out += floatArrayOf(left, top, right, bottom)
                }
                // 传统"贴顶"布局下继续用原来那条哨兵（语义是"底边离首句多近"）；
                // 拖动到别处后由 checkStatusBarOcclusion 负责，避免哨兵误报。
                //
                // 判据从 `params.y == 0` 改成"在默认位置"：默认 y 现在是 TOP_DP（不是 0），
                // 用 ==0 会导致哨兵永不触发。
                val defaultY = (StatusBarView.TOP_DP * resources.displayMetrics.density).roundToInt()
                if (params.y == defaultY) {
                    guardHeadOcclusion(bottom, sv.statusWindowHeightPx())
                }
            }
        }

        // 2) 调试面板：**用实测矩形**（原来是写死的 [0,0.45]-[0.6,0.57]）
        //
        // 写死那个有多处不对：宽 60% 屏宽、高 12% 屏高，而真实面板的宽度取决于
        // 最长一行的实测像素宽、高度取决于行数。文字窄时排除区过大（误伤周围游戏
        // 文字），文字宽或行数多时盖不住面板（面板文字被当成游戏选项 → 自锁）。
        overlayView?.let { ov ->
            ov.debugBoundsNormalized()?.let { out += it }
        }

        // 3) **悬浮球**：它也是 App 自己画的，同样要排除
        //
        // 之前漏了它。球上写的是「词」，通常不会撞上词牌名，所以没暴露问题；
        // 但"自绘区域"这个概念上它算漏了一块 —— 一旦以后球上换了文案就会踩坑。
        ballView?.let { bv ->
            ballParams?.let { bp ->
                if (bv.width > 0 && bv.height > 0) {
                    out += floatArrayOf(
                        (bp.x.toFloat() / screenW).coerceIn(0f, 1f),
                        (bp.y.toFloat() / screenH).coerceIn(0f, 1f),
                        ((bp.x + bv.width).toFloat() / screenW).coerceIn(0f, 1f),
                        ((bp.y + bv.height).toFloat() / screenH).coerceIn(0f, 1f),
                    )
                }
            }
        }

        p.selfDrawnBounds = out
    }

    /**
     * **遮挡哨兵**：状态条底边离游戏首句太近就报警。
     *
     * ### 要防的是什么
     *
     * 状态条是 App 自己画的浮层，而首句是**取帧时一起被 OCR 的**。状态条压住首句
     * 就等于让 OCR 读不到首句 —— 表现为「识别不出新句子」，而用户会以为是引擎不行。
     * **App 遮住自己要读的字**，是这个工程里最难自查的错误。
     *
     * 真机事故（v0.11.0）：效果做成状态条第二行 → 该局 95 帧里 90 帧「首句未匹配」。
     *
     * ### 为什么用日志而不是直接改布局
     *
     * 布局该由设计常量决定，不在运行时"自动缩"。这里只负责**让它可观测**：
     * 一旦有人再加内容把状态条撑高，日志里就会出现 `overlay.occlusion`，
     * 而不是等用户反馈"识别不出新句子"。
     *
     * 只在**跨过阈值时**打一次（用标志位去重），否则每帧都会刷。
     */
    private fun guardHeadOcclusion(bottomRatio: Float, heightPx: Int) {
        val clearance = Config.GAME_HEAD_LINE_RATIO - bottomRatio
        val bad = clearance < Config.MIN_HEAD_CLEARANCE_RATIO
        if (bad == occlusionWarned) return
        occlusionWarned = bad
        if (bad) {
            EventLog.log(
                "overlay.occlusion",
                "状态条底边 %.3f 已逼近游戏首句 %.3f（净空 %.3f < %.3f），OCR 可能读不到首句！状态条高 %d px。本行来自 v0.11.0 的事故守护（当时 95 帧里 90 帧读不到首句）".format(
                    bottomRatio, Config.GAME_HEAD_LINE_RATIO, clearance,
                    Config.MIN_HEAD_CLEARANCE_RATIO, heightPx,
                ),
            )
        } else {
            EventLog.log(
                "overlay.occlusion",
                "状态条底边 %.3f，首句 %.3f，净空 %.3f ✓".format(
                    bottomRatio, Config.GAME_HEAD_LINE_RATIO, clearance,
                ),
            )
        }
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

    // ------------------------------------------------------------------ 需求3b：监听玩家点击

    /** 上一次上报玩家点击的时刻（去重：一次手势可能送来多个 OUTSIDE） */
    @Volatile
    private var lastOutsideAt = 0L

    /**
     * 玩家在**别处**点了一下（高亮层窗口收到 `ACTION_OUTSIDE`）。
     *
     * ### 为什么需要它（用户需求）
     *
     * 「高亮框已经指向正确选项后，直到玩家下次点击前不必再持续取帧。」
     * 玩家一点，说明他正在做选择 → 立刻结束降速，恢复正常档位，
     * 这样下一题能第一时间出框（不用等超时兜底）。
     *
     * ### 为什么去重
     *
     * 一次手势可能产生多个 `ACTION_OUTSIDE`（多指、滑动）。这里按 200ms 合并，
     * 避免重复打日志刷爆事件环形缓冲。
     */
    private fun onOutsideTouch(e: MotionEvent) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOutsideAt < OUTSIDE_DEDUP_MS) return
        lastOutsideAt = now
        CaptureService.notifyUserTouch()
        EventLog.log("touch.outside", "玩家点击 (%.0f,%.0f) → 恢复取帧档位".format(e.rawX, e.rawY))
    }

    /** ACTION_OUTSIDE 的去重窗口 */
    private val OUTSIDE_DEDUP_MS = 200L

    // ------------------------------------------------------------------ 需求1：状态条拖动

    /** 状态条拖动用：是否已进入拖动 */
    private var statusDragged = false

    /** 最近一次状态条 ACTION_MOVE 的时刻（用于拖动标志的兜底释放） */
    private var lastStatusMoveAt = 0L

    /** 拖动标志的闲置超时：超过这么久没有 MOVE 事件就认为手势已结束 */
    private val STATUS_DRAG_STALE_MS = 2_000L

    /**
     * 状态条拖出屏幕时至少保留多少 dp 可见。
     *
     * 取 24dp 而不是更大的值：用户要的是"能拉到底"。24dp 约等于一个手指能按到的宽度，
     * 既能让人把窗口拖回来，又不会在屏幕边缘留出一大段"拖不到"的距离。
     */
    private val STATUS_DRAG_KEEP_VISIBLE_DP = 24f

    /**
     * 状态条拖出屏幕时**至少保留多少像素可见**。
     *
     * ### 取自 FloatingX 的做法
     *
     * 参考实现 [FloatingX](https://github.com/Petterpx/FloatingX) 的 §2.3：
     * 悬浮窗位置真值用**锚点**表达，拖动时按 `rawX/rawY` 算增量，并用
     * `FxOverflow(top, bottom, left, right)` 显式允许出界；它的 issue
     * **#188「拖不到底」** 就是这么治的。
     *
     * 关键点：夹取边界是**固定屏幕边距**，与窗口自身尺寸**无关**。
     * 我之前的实现用 `size.y - v.height` —— 于是"能拖到哪"会随窗口高度变化，
     * 高度一变可用范围就变，表现就是"被一条看不见的线卡住"。
     *
     * 现在：边界 = 屏幕范围 ± 一个固定余量，窗口尺寸不再参与计算。
     *
     * ### 为什么缓存起来
     *
     * 它被 **[onStatusTouch] 的每个 ACTION_MOVE 调用**。原来每次都读
     * `resources.displayMetrics.density` —— MOVE 是每帧都来的热路径，重复取
     * Resources 是没必要的开销（拖动"卡顿"的一个可能来源）。density 在进程内不变，
     * 缓存一次即可。
     */
    private val statusKeepVisiblePx: Int by lazy {
        (STATUS_DRAG_KEEP_VISIBLE_DP * resources.displayMetrics.density).roundToInt()
    }

    /**
     * 状态条坐标夹取：允许拖出屏幕（大部分移出去也行），但**始终保留一条边可见**，
     * 否则就再也点不到了。
     *
     * 边界与窗口尺寸无关 —— 见 [statusKeepVisiblePx] 的说明。
     */
    private fun clampStatusPos(raw: Int, screen: Int): Int {
        val keep = statusKeepVisiblePx
        return raw.coerceIn(-screen + keep, screen - keep)
    }

    // ------------------------------------------------------------------ 状态条的纵向基准

    /**
     * 状态条窗口用 **`Gravity.TOP or Gravity.START`**，`params.y` 就是**屏幕坐标本身**
     * （距屏幕顶边），**与窗口高度无关**。
     *
     * ### 为什么不能用 `Gravity.BOTTOM`（我走过的弯路）
     *
     * `BOTTOM` 基准下 `params.y = screenH - screenY - height`，**位置依赖窗口高度**。
     * 而状态条的高度必然会变（有文案 ↔ 没文案、拖动中重算），于是同一个 `screenY`
     * 每算一次就偏一点 —— 窗口在屏幕上跳、甚至在边缘被跳出屏幕，
     * 用户看到的就是"长条框有的时候会突然消失"。
     *
     * `TOP` 基准没有这个问题：`params.y == screenY`，高度怎么变位置都不动。
     *
     * ### "能拖到底"怎么保证
     *
     * 靠 `FLAG_LAYOUT_NO_LIMITS` + `clampStatusPos` 允许 `params.y` 取到屏幕高度
     * （即窗口底边可以超出屏幕），而不是靠换 gravity。
     *
     * 换算函数保留成恒等，是为了让"屏幕坐标 ↔ params 坐标"的边界只有一个地方，
     * 以后若要再换基准不必满地改。
     */
    private val statusYFromBottom: Boolean
        get() = false

    /** 屏幕坐标 y（左上基准）→ `params.y` */
    private fun screenYToParamsY(screenY: Int, height: Int, screenH: Int): Int =
        if (statusYFromBottom) screenH - screenY - height else screenY

    /** `params.y` → 屏幕坐标 y（左上基准） */
    private fun paramsYToScreenY(paramsY: Int, height: Int, screenH: Int): Int =
        if (statusYFromBottom) screenH - paramsY - height else paramsY

    /**
     * 按下时**手指相对窗口左上角的偏移**（像素）。
     *
     * ### 这是拖动能不能用的关键（我第一版写错的地方）
     *
     * 错误实现是 `params.x = rawX - v.width/2` —— 把**窗口中心**强行对齐到手指。
     * 状态条宽 1420px、中心在 x≈960；用户按住左端（rawX≈300）稍微一动：
     *
     * ```
     * params.x = 300 - 710 = -410 → 钳到 0   → 窗口瞬移，手感上就是"拖不动"
     * ```
     *
     * 正确做法：记下按下那一刻的偏移，之后 `params.x = rawX - offsetX`，
     * 窗口就**跟着手指平移**，不会跳。
     */
    private var statusTouchOffsetX = 0
    private var statusTouchOffsetY = 0

    /**
     * 状态条拖动（用户需求：「把顶部长条的那个提示框变为可以拖动的」）。
     *
     * ### 为什么可以直接改 `params.x/y`
     *
     * 窗口位置由 `WindowManager.LayoutParams.x/y` 决定（`gravity = TOP or START`），
     * 拖动就是改这两个值再 `updateViewLayout`。**窗口尺寸不随拖动变**，
     * 所以不会触发 WindowManager 的位移动画（那是"框飞过来"的成因）。
     *
     * ### 不限方向（用户明确要求）
     *
     * 水平与垂直都放开。垂直拖动会把状态条拖到选项气泡上的风险，由
     * [checkStatusBarOcclusion] 在运行时**检测并告警**，而不是限制拖动。
     */
    private fun onStatusTouch(v: View, event: MotionEvent, params: WindowManager.LayoutParams): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                statusDragged = false
                // **偏移必须与手指在同一坐标系里算**（屏幕左上为原点）。
                //
                // ### 这里曾经混用两套坐标，导致"一拖就飞走"
                //
                // 上一版写成 `rawY - params.y`。改成 `Gravity.BOTTOM` 之后
                // `params.y` 是"距屏幕**底部**的偏移"，而 `rawY` 是"距屏幕**顶部**"
                // —— 两者相减毫无意义。实测（screenH=1152, h=209）：
                //
                // ```
                // offsetY = 896 - (-137) = 1033
                // 屏幕 y  = 473 - 1033   = -560    ← 窗口飞到屏幕外（日志实测值）
                // ```
                //
                // 现在先把窗口位置换算到**屏幕坐标**，再算偏移；
                // MOVE 里也全用屏幕坐标，只在写入 `params` 时换算一次。
                val screen = realDisplaySize()
                val winScreenY = paramsYToScreenY(params.y, v.height, screen.y)
                statusTouchOffsetX = event.rawX.roundToInt() - params.x
                statusTouchOffsetY = event.rawY.roundToInt() - winScreenY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val size = realDisplaySize()
                // 窗口在屏幕上的当前位置（用于判断"相对按下点移动了多少"）
                val winScreenY = paramsYToScreenY(params.y, v.height, size.y)
                if (!statusDragged &&
                    abs(event.rawX.roundToInt() - params.x - statusTouchOffsetX) +
                    abs(event.rawY.roundToInt() - winScreenY - statusTouchOffsetY) > touchSlop
                ) {
                    statusDragged = true
                }
                if (statusDragged) {
                    lastStatusMoveAt = SystemClock.elapsedRealtime()
                    // 全程在**屏幕坐标**里算，最后一步才换算成 params（按 gravity）
                    params.x = clampStatusPos(event.rawX.roundToInt() - statusTouchOffsetX, size.x)
                    val screenY = clampStatusPos(event.rawY.roundToInt() - statusTouchOffsetY, size.y)
                    params.y = screenYToParamsY(screenY, v.height, size.y)
                    safeUpdate(v, params)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (statusDragged) {
                    val size = realDisplaySize()
                    // 保存的也是**屏幕坐标 y**（与默认值、clampStatusPos 同一基准），
                    // 这样切换 gravity 时持久化数据不会错位
                    val screenY = paramsYToScreenY(params.y, v.height, size.y)
                    prefs.edit()
                        .putInt(PREF_STATUS_X, params.x)
                        .putInt(PREF_STATUS_Y, screenY)
                        .apply()
                    val base = if (statusYFromBottom) "BOTTOM" else "TOP"
                    EventLog.log(
                        "status.drag",
                        "状态条移到 屏幕(x=${params.x}, y=$screenY)；窗口 ${v.width}x${v.height}，" +
                            "屏幕 ${size.x}x${size.y}，params.y=${params.y}（基准=$base）",
                    )
                    // 拖完立刻检查有没有挡住首句/选项气泡，并重报自绘区域
                    checkStatusBarOcclusion()
                    publishSelfDrawnBounds()
                }
                statusDragged = false
                return true
            }
        }
        return false
    }

    private fun safeUpdate(v: View, params: WindowManager.LayoutParams) {
        runCatching { windowManager.updateViewLayout(v, params) }
    }

    /**
     * **禁止窗口的位移动画**（"长条框突然消失一下"的根因）。
     *
     * ### 症状与成因
     *
     * `WindowManager.updateViewLayout()` 在窗口几何变化时会播放一次**窗口位移动画**。
     * 动画期间旧位置的内容已不再绘制、新位置的内容还没到达，
     * 于是中间那段时间窗口是**空的** —— 用户看到的就是"长条框突然消失"。
     * 而状态条每帧都会因文案变化触发 `updateViewLayout`，所以这个现象会反复出现。
     *
     * ### 依据（社区同类实现）
     *
     * - CSDN《WindowManager 的 updateViewLayout取消动画效果》记录的现象与我们一致
     *   （「悬浮窗移动后再点击按钮，悬浮窗会从移动前的位置移动到移动后的位置」），
     *   解法是反射设 `PRIVATE_FLAG_NO_MOVE_ANIMATION`
     * - FloatingX 的 `FxWindowContainer`（成熟悬浮窗库）同样关掉窗口位移动画
     *
     * ### 实现
     *
     * 1. **公开 API**：`windowAnimations = 0`（不依赖反射，必生效）
     * 2. **反射兜底**：`PRIVATE_FLAG_NO_MOVE_ANIMATION` 精确对应"移动动画"，
     *    公开 API 关不掉它。反射失败只影响观感，所以整段 `runCatching`。
     */
    private fun setNoMoveAnimation(p: WindowManager.LayoutParams) {
        runCatching {
            val f = p.javaClass.getField("privateFlags")
            f.setInt(p, f.getInt(p) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        }
    }

    /** `WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION`（隐藏字段）的常量值 */
    private val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x00000040

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
        // 球的位置变了 → 自绘区域跟着变（球也在排除名单里，见 publishSelfDrawnBounds）
        publishSelfDrawnBounds()
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
            hideAllTips()
        } else {
            // **恢复可见**（与 hideAllTips 的 GONE 配对，缺了它浮层就再也不出现）
            overlayView?.visibility = View.VISIBLE
            statusView?.visibility = View.VISIBLE
            // 清掉"暂停前的那份结论"，等下一帧重新画
            lastOutcome = null
            hideGeneration++      // 暂停期间的旧回调已作废；恢复后从新帧开始
        }
        EventLog.log("ball.tap", if (paused) "暂停取帧" else "开始取帧")
        Log.i(TAG, "悬浮球点击：paused=$paused")
    }

    /**
     * **隐藏除悬浮球以外的全部提示**（高亮框 / 状态条 / 调试面板）。
     *
     * ### 为什么要单独抽成一个方法，并且无条件调用
     *
     * 用户报告：「点击悬浮球关闭取帧时，框没有全部隐藏」。
     *
     * 纯静态排查过整条链路（`OverlayView.onDraw` 的 `if (paused) return`、
     * `StatusBarView.showing` 的 `!paused`、`onFrameOutcome` 的 `paused` 拦截），
     * 逻辑上都是对的，且从 v0.10.1 起没变过 —— **不是回归**。
     *
     * 所以这里做两件事：
     * 1. **把隐藏动作显式化、幂等化**：不依赖 `paused` 这条隐式链路是否被正确读到，
     *    直接清状态 + 手动 `invalidate()` + 把状态条窗口收成 0 高。
     *    这样即使某个 view 的 `paused` 因故没生效，隐藏结果依然成立。
     * 2. 打**可验证的埋点**：把三个 view 的 `paused`、高亮/文案是否已清、窗口高度
     *    一起记进事件日志 —— 下次再出问题，日志里能直接看出是哪一环没跟上，
     *    而不是继续靠读代码猜。
     */
    private fun hideAllTips() {
        // 0) 作废"暂停之前发出的"帧回调（见 onFrameOutcome 的代际说明）
        hideGeneration++
        // 1) 高亮框 + 调试面板：清状态 + **直接置 GONE**
        //
        // ### 为什么用 visibility 而不是把窗口尺寸改成 0
        //
        // 参考实现（以及 Android 悬浮窗的通行做法）都是 `view.visibility = GONE`：
        // 它让 view 不绘制、不参与布局，**不依赖任何"状态计算"是否正确**。
        //
        // 我之前改成"清 text + 把 statusParams.height 置 0"，那要求
        // `statusWindowHeightPx()==0` 或"强制置 0"两处都真的生效 —— 一旦某处
        // 被提前 return 挡掉，窗口就还留着旧高度和旧画面。用 GONE 就没有这个前提。
        overlayView?.let { ov ->
            ov.clear()          // 连 debugLines 一起清（见 OverlayView.clear 注释）
            ov.visibility = View.GONE
            ov.invalidate()
        }
        // 2) 状态条：清文案 + GONE
        setStatusText(null)
        statusView?.let { sv ->
            sv.paused = true
            sv.text = null
            sv.visibility = View.GONE
            sv.invalidate()
        }
        // 3) 面板内容与浮层不再重画
        lastOutcome = null
        applyOverlayLayout()

        // 4) 埋点：把三者的真实状态记下来，供下次定位
        //    刻意**不用** String.format 的 %s（Kotlin 里传可空值会打出 "null"，
        //    埋点本身失效过一次），改成显式拼接，缺什么一眼能看出来。
        val overlayPart = overlayView?.let { ov ->
            "vis=${if (ov.visibility == View.VISIBLE) "VISIBLE" else "GONE"} paused=${ov.paused} " +
                "highlight=${if (ov.highlight == null) "已清" else "★还在"} debug=${ov.debugLines.size}"
        } ?: "★view为空"
        val statusPart = statusView?.let { sv ->
            "vis=${if (sv.visibility == View.VISIBLE) "VISIBLE" else "GONE"} paused=${sv.paused} " +
                "text=${if (sv.text.isNullOrBlank()) "已清" else "★还有${sv.text!!.length}字"} " +
                "h=${sv.statusWindowHeightPx()}px winH=${statusParams?.height ?: -1}px"
        } ?: "★view为空"
        EventLog.log("ball.hide", "隐藏提示（paused=$paused）：overlay[$overlayPart] status[$statusPart]")
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

        /**
         * 状态条被拖动后保存的位置。
         *
         * 用 `Int.MIN_VALUE` 作"从未拖过"的哨兵值（而不是 0）—— 因为 `(0,0)` 是
         * 一个**合法的用户位置**（拖到左上角），用 0 当默认值就区分不出这两种情况。
         */
        private const val PREF_STATUS_X = "status_x"
        private const val PREF_STATUS_Y = "status_y"
        const val PREF_SHOW_STATUS = "show_status_bar"
        const val PREF_DEBUG = "debug_mode"

        /** 一帧结论超过这个时长才送达就不画了（切换页面时防误报） */
        private const val STALE_FRAME_MS = 1_500L

        /** 效果副行里多条效果的分隔符 */
        private const val EFFECT_SEPARATOR = " ｜ "

        /** 调试面板的存活时间（比正常高亮长，方便看清） */
        private const val DEBUG_TTL_MS = 1_200L

        /**
         * 高亮层窗口在内容包围盒外扩多少 dp。
         *
         * 留一点余量，避免描边（以及抗锯齿）被窗口边缘裁掉。
         */
        private const val OVERLAY_WINDOW_PADDING_DP = 8f

        /**
         * 状态条窗口宽度占屏宽的比例（**两侧各留 13% 空白**）。
         *
         * ### 为什么必须收窄（MuMu 实测，Android 12 / API 32）
         *
         * 原来状态条窗口是**整屏宽** `[0,0]-[1920,106]`，于是它和高亮层
         * `[0,0]-[1920,1080]` 在**左上角重叠**。而 Android 12+ 的遮挡判定是
         * **组合累加**的：
         *
         * ```
         * 两个窗口各 alpha=0.70 → 组合 = 1 - (1-0.70)² = 0.91 > 0.80 → 触摸被丢弃
         * ```
         *
         * 系统日志（点左上角返回按钮时）：
         *
         * ```
         * W InputDispatcher: Untrusted touch due to occlusion by com.songci.assist/10044
         *                    (obscuring opacity = 0.91, maximum allowed = 0.80)
         * D Stack of obscuring windows during untrusted touch (110, 45):
         *     * type=2038, alpha=0.70, frame=[0,0][1920,106]   touchableRegion=<empty>  ← 状态条
         *     * type=2038, alpha=0.70, frame=[0,0][1920,1080]  touchableRegion=[0,0][1920,1080] ← 高亮层
         * ```
         *
         * **这正是「游戏左上角返回按钮点不动、但别处正常」的原因** ——
         * 游戏把返回按钮放在左上角，而那里恰好是两个悬浮窗唯一的重叠区。
         *
         * ### 为什么要靠"收窄"而不是一味压 alpha
         *
         * 压 alpha 是全局妥协：要让 `1-(1-a)² ≤ 0.80` 得 `a ≤ 0.5528`，
         * 高亮框会淡到 50% 才够（实测 0.5 可行、0.6 被拦）。
         * **收窄之后左上角只剩高亮层一个窗口**，子窗口 alpha 就能用回 0.70：
         * 单个 0.70 < 0.80 ✓，而且**高亮框只淡到 70% 而不是 50%**。
         *
         * ### 为什么不破坏"框不飞过来"
         *
         * 宽度与横向位置都由这个常量推导，**与内容无关** ⇒ 窗口几何不随内容变
         * ⇒ 不会触发 WindowManager 的位移动画（那是 v0.11.0 的老问题）。
         * 只有首次布局时会从初始值过渡一次。
         *
         * 0.74 的取值依据：最长状态文案是
         * `应选：X（屏上没找到选项气泡） ｜ 民心-10、战斗力+10`（约 33 字），
         * 按 15sp 计约 1155px；屏宽 1920 的 74% = 1420px，留足余量，
         * 同时两侧各留 250px 空白把两个上角让出来。
         */
        private const val STATUS_WINDOW_WIDTH_RATIO = 0.74f

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
