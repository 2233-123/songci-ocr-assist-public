package com.songci.assist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

/**
 * 前台服务：持有 MediaProjection，通过 SurfaceTexture 产出帧，交给 [FramePipeline]。
 *
 * 只做「拿帧」，不认识文字（设计文档 §3.1 职责边界）。
 *
 * Android 14+ 的强制要求（QPR 之后每帧会话都要重新授权）：
 *  - 必须由前台服务持有 MediaProjection；
 *  - 必须在拿到 projection **之后、创建 virtual display 之前**注册 `Callback`；
 *  - 必须声明 `foregroundServiceType="mediaProjection"`（manifest 已声明）。
 */
class CaptureService : Service() {

    private lateinit var notificationManager: NotificationManager
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    /** 投屏取帧器（VirtualDisplay + ImageReader，1:1 尺寸、detach/attach 换 surface） */
    private var frameReader2: ScreenFrameReader? = null

    /** 建立投屏时的尺寸（内容尺寸未知时用） */
    @Volatile
    private var bufferWidth = 0

    @Volatile
    private var bufferHeight = 0
    private var readerThread: HandlerThread? = null
    private var readerHandler: Handler? = null

    private val captureExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "songci-capture").apply { isDaemon = true }
    }

    private var pipeline: FramePipeline? = null
    private var ocrEngine: MlKitOcrEngine? = null

    /**
     * 最近一次取到帧的时刻（[SystemClock.elapsedRealtime]）。
     *
     * 只用于诊断/判断取帧是否还在工作；**画框的过期判定不等价于它** ——
     * 每个结论带着自己那一帧的时刻（见 [deliver] 的 frameAt 参数）。
     */
    @Volatile
    var lastFrameAt: Long = 0L
        private set

    /** 是否已经进入前台（没进过就不该调 stopForeground） */
    @Volatile
    private var isForeground = false

    /** 当前取帧链路的状态文案（引导页显示，避免"卡在等待系统确认"这种瞎猜） */
    @Volatile
    var stateText: String = ""
        private set

    /** 最近一帧诊断（本地保留；引导页读静态副本即可） */
    @Volatile
    private var lastDiag: FrameDiag? = null

    /** 当前 capture surface 的尺寸（用于诊断与重建对比） */
    @Volatile
    private var captureSize: android.graphics.Point = android.graphics.Point(0, 0)

    /**
     * 更新状态并通知引导页。
     *
     * @param reason 停止/失败的具体来源（写进诊断区，定位到底是谁把投屏停了）
     */
    private fun setState(text: String, reason: String? = null) {
        stateText = text
        publishState(text)
        // 只有「运行中」才算真的在跑；失败/停止都要让悬浮球回到「单击=开始取帧」
        publishRunning(text.startsWith(getString(R.string.cap_state_running, 0, 0).substringBefore("0x0")))
        if (reason != null) {
            lastReason = reason
            publishReason(reason)
        }
        EventLog.log("state", "$text${reason?.let { " / $it" } ?: ""}")
        val cb = receive
        if (cb != null) {
            Handler(mainLooper).post { cb.invoke(text) }
        }
    }

    /** 最近一次停止/失败原因（诊断用） */
    @Volatile
    private var lastReason: String? = null

    /** 投屏建好的时刻（用于计算"存活多久被回收"） */
    @Volatile
    private var projectionStartedAt = 0L

    /** widget/activity 观察到的事件，例如「投屏被系统停止」。 */
    @Volatile
    var onStopped: ((String) -> Unit)? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 这是关键事件：系统回收了投屏。记录"建好之后多久被回收"，
            // 用于区分"一开始就被拒"还是"切到游戏之后被回收"。
            val alive = if (projectionStartedAt > 0) {
                SystemClock.elapsedRealtime() - projectionStartedAt
            } else {
                -1
            }
            EventLog.log("projection.onStop", "投屏存活 ${alive}ms（-1 表示还没建好就被停）")
            Log.i(TAG, "MediaProjection.onStop（用户停止投屏 / 系统回收）")
            releaseProjection()

            // Android 16 在显示方向变化时会回收投屏（本机已多次实测）。这里立刻
            // 重新申请一次：PROJECT_MEDIA 已授权的机器上授权流程只要几十毫秒、
            // 不弹任何界面，投屏能马上接上，用户基本无感。
            if (autoRestart()) return

            updateNotification(getString(R.string.notif_stopped))
            // 系统回收投屏的常见触发：切后台、锁屏、被省电策略掐断、其它 App 抢占
            setState(getString(R.string.cap_state_stopped), "onStop：投屏被系统回收或用户在通知栏停止")
            onStopped?.invoke(getString(R.string.notif_stopped))
            // 投影已失效，服务没有存在的意义了：撤掉常驻通知并退出
            stopEverything("onStop 之后收尾")
        }

        /**
         * 捕获区域尺寸变化（旋转屏幕、进游戏变横屏、分屏等）。
         *
         * 交给 [ScreenFrameReader.onContentResized]：它会
         * 「先 detach 旧 surface → resize VirtualDisplay → attach 新 surface → 延后回收旧 reader」。
         * 这个顺序是社区验证过的做法（[droidVNC-NG #336](https://github.com/bk138/droidVNC-NG/issues/336)：
         * "detach the old surface before creating/attaching a new one 🎉"）。
         *
         * 绝不能 release VirtualDisplay —— 那会让系统判定投影失效并立即回收（实测只活 21ms）。
         */
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            if (contentWidth != width || contentHeight != height) {
                EventLog.log("projection.contentResize", "${width}x$height → 换消费者（detach 后 attach）")
            }
            contentWidth = width
            contentHeight = height
            frameReader2?.onContentResized(width, height)
        }
    }

    /** 主线程 Handler（旋转回调要在主线程改窗口相关对象） */
    private val mainHandler: Handler get() = Handler(mainLooper)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        EventLog.log("svc.onCreate", "")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        EventLog.log("svc.onStartCommand", "action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (data == null || resultCode == 0) {
                    Log.w(TAG, "缺少 MediaProjection 授权数据，服务不启动")
                    setState(getString(R.string.cap_state_failed, "无授权数据"))
                    stopEverything("授权数据为空（resultCode=$resultCode）")
                    return START_NOT_STICKY
                }
                // Android 14+ 要求：先以 mediaProjection 类型进入前台，再 getMediaProjection
                startForegroundIfNeeded(getString(R.string.notif_capturing))
                startProjection(resultCode, data)
            }

            ACTION_STOP -> stopEverything("收到 ACTION_STOP（在通知栏点了停止）")
            else -> stopEverything("服务被异常启动（action=${intent?.action}）")
        }
        return START_NOT_STICKY
    }

    /**
     * 撤掉常驻通知并退出（投屏已失效 / 用户点停止）。
     *
     * @param reason 谁触发的，写进诊断区（这是定位"为什么总是已停止"的关键）
     */
    private fun stopEverything(reason: String = "内部收尾") {
        if (!stopping) {
            stopping = true
            userWantsCapture = false
            publishReason(reason)
        }
        mainHandler.removeCallbacks(frameWatchdog)
        // 注意：不在这里恢复方向设置（本应用不修改系统方向，见 startProjection 注释）
        runCatching { notificationManager.cancel(NOTIFICATION_ID) }
        if (isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            isForeground = false
        }
        stopSelf()
    }

    /** 已经进入收尾流程（避免重复上报原因） */
    @Volatile
    private var stopping = false

    /** 自动恢复次数（防止被系统反复回收时无限重启） */
    @Volatile
    private var autoRestarts = 0

    /** 抖动窗口起点与窗口内重启次数（防止陷入"每隔几百毫秒被回收一次"的死循环） */
    @Volatile
    private var restartWindowStart = 0L
    @Volatile
    private var restartsInWindow = 0

    /**
     * 投屏被系统回收后自动重新申请。
     *
     * Android 16 在显示方向变化时回收投屏，而方向由系统/游戏决定，应用拦不住。
     * 但 `PROJECT_MEDIA` 已授权的机器上，重新申请不需要用户交互（实测授权页
     * 只存在 ~65ms 就自动返回），所以这里直接原地接上。
     *
     * @return true 表示已在重启，调用方不要再走"停止"流程
     */
    private fun autoRestart(): Boolean {
        if (autoRestarts >= MAX_AUTO_RESTARTS) {
            EventLog.log("projection.autoRestart", "已达上限 ${MAX_AUTO_RESTARTS} 次，改走停止流程")
            return false
        }
        // 抖动保护：如果短时间内反复被回收（例如死循环），停下来并明确提示，
        // 而不是无限闪屏。20 秒内超过 4 次就认为是异常循环。
        val now = SystemClock.elapsedRealtime()
        if (now - restartWindowStart > RESTART_WINDOW_MS) {
            restartWindowStart = now
            restartsInWindow = 0
        }
        restartsInWindow++
        if (restartsInWindow > MAX_RESTARTS_IN_WINDOW) {
            EventLog.log(
                "projection.autoRestart",
                "${RESTART_WINDOW_MS / 1000}秒内第 $restartsInWindow 次被回收 → 判定异常循环，停止重试",
            )
            return false
        }
        if (!userWantsCapture) {
            EventLog.log("projection.autoRestart", "用户已主动停止，不再自动恢复")
            return false
        }
        autoRestarts++
        EventLog.log("projection.autoRestart", "第 $autoRestarts 次自动恢复")
        // 稍等一拍再拉起：等旧的 VirtualDisplay / ImageReader 彻底释放，
        // 否则可能拿不到新的投影（也不能在 onStop 回调里同步重建）
        val intent = Intent(this, CaptureBridgeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            Handler(mainLooper).postDelayed({
                runCatching { startActivity(intent) }
                    .onFailure { EventLog.log("projection.autoRestart", "拉起失败：${it.javaClass.simpleName}") }
            }, AUTO_RESTART_DELAY_MS)
            true
        } catch (t: Throwable) {
            EventLog.log("projection.autoRestart", "调度失败：${t.javaClass.simpleName}")
            false
        }
    }

    /** 用户是否希望正在取帧（长按悬浮球/点停止会置 false，避免被当成"意外停止"反复重启） */
    @Volatile
    private var userWantsCapture = true

    // ------------------------------------------------------------------ 投屏
    private fun startProjection(resultCode: Int, data: Intent) {
        releaseProjection()
        setState(getString(R.string.cap_state_connecting))

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            // Android 14+：本服务必须已经以 mediaProjection 类型进入前台（见调用处），
            // 否则这里会抛 SecurityException；另外 token 只能用一次，重用时也抛。
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection 抛异常", t)
            setState(getString(R.string.cap_state_failed, t.javaClass.simpleName))
            stopEverything()
            return
        }
        if (mp == null) {
            Log.w(TAG, "getMediaProjection 返回 null")
            setState(getString(R.string.cap_state_failed, "null projection"))
            stopEverything()
            return
        }
        // 必须在 createVirtualDisplay 之前注册
        mp.registerCallback(projectionCallback, Handler(mainLooper))
        projection = mp

        val size = displaySize()
        val width = size.x
        val height = size.y
        val density = resources.displayMetrics.densityDpi
        EventLog.log(
            "display.size",
            "捕获面 ${width}x$height 真实显示 ${realDisplaySize().x}x${realDisplaySize().y} " +
                "旋转=${currentRotation()} dpi=$density",
        )
        captureSize = size

        val thread = HandlerThread("songci-frame").also { it.start() }
        readerThread = thread
        val handler = Handler(thread.looper)
        readerHandler = handler

        val vd = createCaptureSurface(mp, width, height, density, handler)
        if (vd == null) {
            setState(getString(R.string.cap_state_failed, "createVirtualDisplay"))
            stopEverything()
            return
        }
        virtualDisplay = vd

        pipeline = FramePipeline(
            ocr = MlKitOcrEngine().also { ocrEngine = it },
            index = { IndexHolder.index },
        ).also { p ->
            p.listener = { outcome, frameAt, diag -> deliver(outcome, frameAt, diag) }
        }
        // 索引异步加载：这里是启动瞬间的一次预热，加载失败也能在引导页看到
        IndexHolder.get(this) { }
        // 注意：这里**故意不碰系统方向设置**。
        //
        // 早先一版在录制期间把 user_rotation 锁成横屏、停止时恢复原值，结果适得其反：
        // 恢复动作本身会把屏幕从横屏转回竖屏（user_rotation=0），而 Android 16
        // 一旦方向变化就立即回收 MediaProjection —— 等于自己把投屏掐了。
        // 实测日志：orientation.unlock → ROTATION_0 → Content Recording: stopping。
        // 方向交给系统/用户，本应用不再修改系统设置。
        projectionStartedAt = SystemClock.elapsedRealtime()
        // 新一轮开始：重置计数与"用户希望取帧"标记
        autoRestarts = 0
        restartWindowStart = SystemClock.elapsedRealtime()
        restartsInWindow = 0
        imageCallbacks = 0
        acceptedFrames = 0
        processedFrames = 0
        userWantsCapture = true
        stopping = false
        // 帧流看门狗：消费者尺寸与内容不一致时，生产者会静默停止送帧，
        // 这里负责发现并换掉消费者（不动投影）
        mainHandler.removeCallbacks(frameWatchdog)
        mainHandler.postDelayed(frameWatchdog, FRAME_WATCHDOG_INTERVAL_MS)
        // 记录初始内容尺寸，供看门狗比对
        contentWidth = width
        contentHeight = height
        EventLog.log("projection.started", "${width}x$height dpi=$density")
        setState(getString(R.string.cap_state_running, width, height))
        Log.i(TAG, "投屏已启动 ${width}x$height dpi=$density")
    }

    /**
     * 取**真实显示尺寸**来建 VirtualDisplay。
     *
     * 这里连续踩了两个坑，都靠实机抓帧才看清：
     *
     * 1. `resources.displayMetrics` → 应用窗口尺寸（曾拿到 1200x1800）；
     * 2. `maximumWindowMetrics` → 返回 2608x1200，而实际显示是 1200x2608；
     * 3. `currentWindowMetrics` → 曾返回 2608x1200，真实显示 2464x1152（仍然不一致）。
     *
     * 只要 VirtualDisplay 与真实显示尺寸不一致，系统就会把镜像内容**缩放并偏移**塞进来
     * —— 实测读回的画面是"一大片黑 + 角落一小块灰"（约 45% 缩放），OCR 一个字都读不出。
     *
     * 所以直接用 `Display.getRealMetrics()`（真实显示，不受窗口/分屏影响）。
     */
    private fun displaySize(): android.graphics.Point {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val p = android.graphics.Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay?.getRealMetrics(
            android.util.DisplayMetrics().also { dm -> p.set(dm.widthPixels, dm.heightPixels) },
        )
        if (p.x > 0 && p.y > 0) return p
        val dm = resources.displayMetrics
        return android.graphics.Point(
            dm.widthPixels.coerceAtLeast(1),
            dm.heightPixels.coerceAtLeast(1),
        )
    }

    /** 供诊断：真实显示尺寸，不看窗口 */
    private fun realDisplaySize(): android.graphics.Point {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val p = android.graphics.Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay?.getRealMetrics(
            android.util.DisplayMetrics().also { dm -> p.set(dm.widthPixels, dm.heightPixels) },
        )
        if (p.x > 0 && p.y > 0) return p
        val dm = resources.displayMetrics
        return android.graphics.Point(dm.widthPixels, dm.heightPixels)
    }

    /** 当前屏幕旋转角度（0/90/180/270），仅用于诊断 */
    private fun currentRotation(): Int {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        return when (wm.defaultDisplay?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /**
     * 建立投屏取帧（VirtualDisplay + ImageReader，1:1 不缩放）。
     *
     * 尺寸用**真实显示尺寸**（`getRealMetrics`），并且换消费者时遵循社区验证过的顺序：
     * 「先 detach 旧 surface → resize → attach 新 surface → 延后回收旧 reader」。
     * 详见 [ScreenFrameReader] 的类注释（含三条踩坑记录与参考链接）。
     */
    private fun createCaptureSurface(
        mp: MediaProjection,
        width: Int,
        height: Int,
        density: Int,
        handler: Handler,
    ): VirtualDisplay? {
        val consumer = ScreenFrameReader(
            projection = mp,
            handler = handler,
            onFrame = { frame -> onFrameFromReader(frame) },
            onLog = { tag, msg -> EventLog.log(tag, msg) },
        )
        consumer.setDensity(density)
        if (!consumer.start(this)) {
            EventLog.log("frame.setup", "取帧器启动失败")
            return null
        }
        frameReader2 = consumer
        val vd = virtualDisplayOf(consumer)
        if (vd == null) {
            EventLog.log("frame.setup", "VirtualDisplay 为空")
            return null
        }
        return vd
    }

    /** 从取帧器里取出 VirtualDisplay（供 Service 记录与释放） */
    private fun virtualDisplayOf(consumer: ScreenFrameReader): VirtualDisplay? =
        runCatching { consumer.virtualDisplayOrNull() }.getOrNull()

    /**
     * 收到一帧原始像素 → 交给 OCR 线程池。
     *
     * 帧数据在 ImageReader 回调线程上产生，这里立刻转成 Bitmap 再进线程池，
     * 避免 Image 被回收后像素失效。
     */
    private fun onFrameFromReader(frame: ScreenFrameReader.FrameData) {
        val now = SystemClock.elapsedRealtime()
        imageCallbacks++
        // 只保留"有意义的动作"日志：取帧回调每秒约 30 次，全打会把 EventLog 的
        // 60 条环形缓冲冲爆，导致真正的诊断（scanHead/match）被挤掉 —— 前面两轮
        // 的诊断"没打印"就是这个原因。
        val p = pipeline ?: return
        if (!p.shouldAcceptFrame(now)) {
            droppedFrames++
            return
        }
        // **只保留最新帧**：OCR 约 300ms/帧，而画面有 30fps。如果上一个还在处理，
        // 直接丢弃当前帧 —— 否则任务在线程池里排队，提示会延迟好几秒才出现。
        if (ocrBusy) {
            droppedFrames++
            return
        }
        ocrBusy = true
        p.markAccepted(now)
        acceptedFrames++
        lastFrameAt = now
        captureExecutor.execute {
            try {
                processPixels(p, frame, now)
            } finally {
                ocrBusy = false
            }
        }
    }

    /** 进入处理的帧数（诊断用） */
    @Volatile
    private var imageCallbacks = 0

    @Volatile
    private var acceptedFrames = 0

    @Volatile
    private var droppedFrames = 0

    /** 是否有帧正在 OCR（保证永远只处理最新帧，不排队） */
    @Volatile
    private var ocrBusy = false

    @Volatile
    private var processedFrames = 0

    /** 上次做二次识别（补救）的时刻，用于限流 */
    @Volatile
    private var lastRetryAt = 0L

    /** 内容当前的真实尺寸（onCapturedContentResize 通知） */
    @Volatile
    private var contentWidth = 0

    @Volatile
    private var contentHeight = 0

    /** 帧流看门狗：长时间无帧时记录，便于定位（不再主动重建，避免打断帧流） */
    private val frameWatchdog = object : Runnable {
        override fun run() {
            if (projection == null) return
            val now = SystemClock.elapsedRealtime()
            val idle = if (lastFrameAt > 0) now - lastFrameAt else now - projectionStartedAt
            if (idle > FRAME_STALL_MS * 3) {
                EventLog.log("frame.stall", "${idle}ms 没有帧")
                lastFrameAt = now
            }
            mainHandler.postDelayed(this, FRAME_WATCHDOG_INTERVAL_MS)
        }
    }

    /** 在取帧线程池里做 OCR，并把结论推给流水线。 */
    private fun processPixels(
        p: FramePipeline,
        frame: ScreenFrameReader.FrameData,
        capturedAt: Long,
    ) {
        var bitmap: Bitmap? = null
        // 开关必须在 OCR/匹配之前设好：匹配发生在流水线的线程上
        MatcherDebug.enabled = OverlayService.isDebugEnabled(this)
        try {
            bitmap = Bitmap.createBitmap(
                frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888,
            )
            processedFrames++
            if (processedFrames % 10 == 1) {
                EventLog.log(
                    "frame.process",
                    "第 $processedFrames 帧 OCR（${frame.width}x${frame.height}）",
                )
                // 实机诊断：落盘一张，方便 adb pull 下来直接看画面
                if (processedFrames <= 11) dumpFrame(bitmap)
            }
            // **只裁掉底部无用区，按原生分辨率识别**（关键性能取舍）。
            //
            // 实测：全屏 2608x1200 单次 OCR 要 1.6~2.4 秒；裁到 y ∈ [0, 0.85] 后
            // 像素少 15%，同时把底部立绘/按钮/歌词区整块剔除（那里文字多且无用，
            // 实测能多识出十几个块，白花时间）。
            //
            // 注意不能裁得更狠：首句那行位置会随游戏 UI 浮动，裁到 0.7 以下就会
            // 切掉首句（曾经踩过，导致"永远匹配不上"）。0.85 保留充分余量。
            // ---- 直接识别：不做"等画面停稳"的门控 ----
            //
            // 曾经试过用画面指纹判断动画停稳后再 OCR（想解决"首句先渐入、选项后渐入"
            // 导致选项读不出的问题）。**已撤销**，原因：
            // 游戏顶部有**持续飘动的粒子动画**，画面永远不会真正静止，
            // 结果要么一直等（框不出现），要么只能靠 2 秒兜底才识别一次（更慢）。
            // 结论：识别时机的门控在这里不成立，老老实实每帧都识别。
            val engine = ocrEngine ?: return
            val ocrSource = FramePreprocessorImages.cropBand(
                bitmap, 0f, Config.OCR_CROP_BOTTOM,
            )
            val result = engine.recognize(ocrSource)
            val rawBlocks = if (ocrSource === bitmap) {
                result.blocks
            } else {
                FramePreprocessorImages.mapBlocksToFullFrame(
                    result.blocks, 0f, Config.OCR_CROP_BOTTOM, 1,
                )
            }
            if (ocrSource !== bitmap) ocrSource.recycle()
            val blocks = rawBlocks
            if (processedFrames <= 3 || processedFrames % 20 == 1) {
                // 逐块打印：确认 ML Kit 到底读到了哪些行、各自在什么 y 位置
                val dump = blocks
                    .map { it.normalized() }
                    .sortedBy { it.centerY }
                    .joinToString(" ｜ ") {
                        "y=%.2f:%s".format(it.centerY, VerseIndex.normalize(it.text))
                    }
                EventLog.log("frame.ocr", "第 $processedFrames 帧共 ${blocks.size} 块 → $dump")
            }
            // **同步**跑匹配：这样日志里能直接看到本帧的结论
            val screen = displaySize()
            val debug = OverlayService.isDebugEnabled(this)
            var matched = p.matchNow(
                blocks = blocks,
                frameWidth = result.frameWidth,
                frameHeight = result.frameHeight,
                frameAt = capturedAt,
                debug = debug,
                screenWidth = screen.x,
                screenHeight = screen.y,
                rotation = currentRotation(),
            )

            // ---- 二次识别（补救 OCR 漏读）----
            //
            // 游戏里首句与词牌气泡都是艺术字体，ML Kit 时常读错/漏读：
            // 实测有「仅词牌 X（未找到气泡）」和「首句未匹配」两类。
            // 对策：**只对相应区域裁剪并放大 2 倍再识别一次**，把结果合并回整帧。
            //
            // **必须限流**：二次识别要多花一次 OCR（放大 2 倍后像素翻 4 倍，最慢）。
            // 实测不限流时每帧花 2.5~3 秒（0.3fps），用户感受就是"半天没反应"。
            // 这里只允许「距上次补救 ≥ [RETRY_MIN_INTERVAL_MS]」时才补一次，
            // 保证正常帧仍是 2fps 的节流节奏。
            val nowMs = SystemClock.elapsedRealtime()
            var secondPass = ""
            val failed = matched.startsWith("首句未匹配") || matched.startsWith("仅词牌")
            if (failed && nowMs - lastRetryAt >= RETRY_MIN_INTERVAL_MS) {
                lastRetryAt = nowMs
                val band = if (matched.startsWith("首句未匹配")) {
                    Config.TOP_CROP_TOP to Config.TOP_CROP_BOTTOM
                } else {
                    Config.OPTION_REGION_TOP to Config.OPTION_REGION_BOTTOM
                }
                val alt = recognizeBand(bitmap, band.first, band.second)
                if (alt.isNotEmpty()) {
                    val merged = FramePreprocessorImages.mergeBlocks(blocks, alt)
                    val retry = runCatching {
                        p.matchNow(
                            blocks = merged,
                            frameWidth = result.frameWidth,
                            frameHeight = result.frameHeight,
                            frameAt = capturedAt,
                            debug = debug,
                            screenWidth = screen.x,
                            screenHeight = screen.y,
                            rotation = currentRotation(),
                        )
                    }.getOrNull()
                    secondPass = "→二次:${retry ?: "无"}(${alt.size}块)"
                    if (retry != null && retry != matched) matched = retry
                } else {
                    secondPass = "→二次:无补充"
                }
            } else if (failed) {
                // 限流跳过：明确写出来，避免误以为"没做补救"
                secondPass = "→二次:限流跳过"
            }

            val topBlocks = blocks
                .map { it.normalized() }
                .filter { it.centerY <= Config.TOP_CROP_BOTTOM }
                .sortedBy { it.top }
                .joinToString("/") { VerseIndex.normalize(it.text).take(12) }
            EventLog.log(
                "帧$processedFrames",
                "$matched $secondPass｜N=${blocks.size}｜顶部[$topBlocks]",
            )        } catch (t: Throwable) {
            Log.w(TAG, "取帧失败", t)
        } finally {
            bitmap?.recycle()
        }
    }

    /** 顶部区域文本采样（诊断：确认 OCR 是否读到了首句） */
    private fun List<TextBlock>.sampleText(): String =
        map { it.normalized() }
            .filter { it.centerY < Config.TOP_CROP_BOTTOM }
            .sortedBy { it.centerY }
            .take(3)
            .joinToString(" / ") { VerseIndex.normalize(it.text) }
            .ifEmpty { "（空）" }

    /** 中部区域文本采样（诊断：确认三个词牌气泡是否被识别出来） */
    private fun List<TextBlock>.optionText(): String =
        map { it.normalized() }
            .filter {
                it.centerY >= Config.OPTION_REGION_TOP && it.centerY <= Config.OPTION_REGION_BOTTOM
            }
            .sortedBy { it.centerY }
            .take(6)
            .joinToString(" / ") { VerseIndex.normalize(it.text) }
            .ifEmpty { "（空）" }

    /**
     * 二次识别：裁出 `y ∈ [top, bottom]` 的横带，**放大 [OCR_RETRY_SCALE] 倍**后 OCR，
     * 再把坐标映射回整帧。
     *
     * 为什么要放大：游戏里首句/词牌是艺术字体，原生分辨率下 ML Kit 时常读错一两个字
     * （实测「定风波」气泡漏读、首句整行漏读）。放大后笔画分离度更好，识别率明显提升。
     * 只在第一次匹配失败时调用，正常帧不增加开销。
     */
    private fun recognizeBand(src: Bitmap, top: Float, bottom: Float): List<TextBlock> {
        val engine = ocrEngine ?: return emptyList()
        val band = FramePreprocessorImages.cropBand(src, top, bottom)
        val scaled = FramePreprocessorImages.scaleUp(band, OCR_RETRY_SCALE)
        val blocks = try {
            engine.recognize(scaled).blocks
        } catch (t: Throwable) {
            Log.w(TAG, "二次识别失败", t)
            emptyList()
        } finally {
            if (scaled !== band && scaled !== src) scaled.recycle()
            if (band !== src) band.recycle()
        }
        if (blocks.isEmpty()) return emptyList()
        return FramePreprocessorImages.mapBlocksToFullFrame(blocks, top, bottom, OCR_RETRY_SCALE)
    }

    /** 把处理过的帧存成 PNG（`adb pull` 用） */
    private fun dumpFrame(bitmap: Bitmap) {
        runCatching {
            val dir = getExternalFilesDir(null) ?: filesDir
            java.io.FileOutputStream(java.io.File(dir, "frame-first.png")).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            EventLog.log("gl.dump", "已保存 ${java.io.File(dir, "frame-first.png").absolutePath}")
        }.onFailure { EventLog.log("gl.dump", "保存失败: ${it.javaClass.simpleName}") }
    }

    /** 把流水线结论送到悬浮层（悬浮层未运行时什么都不做）。 */
    private fun deliver(outcome: FrameOutcome, frameAt: Long, diag: FrameDiag?) {
        if (diag != null) {
            // 同时留一份给引导页：悬浮层要是画不出来（权限/厂商限制），
            // 至少还能在 App 里看到 OCR 到底读到了什么
            lastDiag = diag
            publishDiag(renderDiag(diag))
            onDiag?.invoke()
        }
        val overlay = OverlayService.instance ?: return
        overlay.onFrameOutcome(outcome, frameAt, diag)
    }

    /** 把一帧的诊断渲染成多行文本（引导页诊断区直接显示）。 */
    private fun renderDiag(d: FrameDiag): String = buildString {
        appendLine("帧 #${d.frameCount}  捕获尺寸 ${d.frameWidth}x${d.frameHeight}${if (d.idle) "  (省电)" else ""}")
        appendLine("屏幕尺寸 ${d.screenWidth}x${d.screenHeight}  旋转 ${d.rotation}")
        appendLine("顶部首句候选: ${d.headText.ifEmpty { "（空）" }}")
        appendLine(
            if (d.pai.isNotEmpty()) "匹配: ${d.pai}  sim=%.3f".format(d.similarity)
            else "匹配: 无（阈值 %.2f）".format(Config.SIMILARITY_THRESHOLD)
        )
        appendLine("气泡: ${d.target?.let { "x %.2f-%.2f y %.2f-%.2f".format(it.left, it.right, it.top, it.bottom) } ?: "未找到"}")
        appendLine("OCR 文本块（共 ${d.blocks.size}）:")
        if (d.blocks.isEmpty()) appendLine("  （空 —— OCR 没读到任何文字）")
        d.blockLines(12).forEach { appendLine("  $it") }
    }

    // ------------------------------------------------------------------ 生命周期
    private fun releaseProjection() {
        pipeline?.listener = null
        pipeline?.shutdown()
        pipeline = null
        ocrEngine?.close()
        ocrEngine = null

        // 取帧器负责释放 VirtualDisplay + ImageReader
        val consumer = frameReader2
        frameReader2 = null
        if (consumer != null) {
            runCatching { consumer.release() }
        }
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        readerThread?.quitSafely()
        readerThread = null
        readerHandler = null

        projection?.unregisterCallback(projectionCallback)
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        lastFrameAt = 0L
    }

    override fun onDestroy() {
        EventLog.log("svc.onDestroy", "")
        releaseProjection()
        captureExecutor.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 通知
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_songci)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text ?: getString(R.string.notif_capturing))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_action_stop), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundIfNeeded(text: String?) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true
    }

    private fun updateNotification(text: String) {
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_START = "com.songci.assist.action.START_CAPTURE"
        const val ACTION_STOP = "com.songci.assist.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "songci_capture"
        private const val NOTIFICATION_ID = 1001

        /**
         * 投屏被系统回收后的自动恢复上限。
         *
         * Android 16 每次显示方向变化都会回收投屏，正常玩一局可能会被回收好几次，
         * 所以给足余量；同时用上限兜住异常情况（例如授权被撤销后无限重试）。
         */
        private const val MAX_AUTO_RESTARTS = 40

        /** 自动恢复前的等待：让旧的 VirtualDisplay / ImageReader 彻底释放 */
        private const val AUTO_RESTART_DELAY_MS = 400L

        /** 二次识别（补救 OCR 漏读）的放大倍数 */
        private const val OCR_RETRY_SCALE = 2

        /**
         * 两次二次识别之间的最小间隔。
         *
         * 二次识别要多花一次 OCR（放大 2 倍 → 像素 4 倍，最慢的那一步）。
         * 实测不限流时每帧 2.5~3 秒（0.3fps），用户感受是"半天没反应"。
         * 限流后正常帧仍有 2fps 的响应速度，漏读帧每 3 秒补一次也够用。
         */
        private const val RETRY_MIN_INTERVAL_MS = 3000L






        /** 抖动保护窗口：20 秒内最多自动恢复 4 次，超过就判定异常循环并停下 */
        private const val RESTART_WINDOW_MS = 20_000L
        private const val MAX_RESTARTS_IN_WINDOW = 4

        /** 帧流看门狗：多久没帧才认为消费者尺寸过期（并换掉消费者自救） */
        private const val FRAME_STALL_MS = 3000L
        private const val FRAME_WATCHDOG_INTERVAL_MS = 1000L

        /** 引导页注册的回调：取帧链路状态变化（成功/失败原因）时回传，避免静默失败 */
        @Volatile
        var receive: ((String) -> Unit)? = null

        /**
         * 当前取帧状态文案；服务没起来时为 null。
         *
         * 用静态引用是为了让引导页在**服务被系统杀掉**（回调永远不会来）之后，
         * 仍然能重新进入界面时看到上一次的真实状态，而不是继续显示「未开始」。
         */
        @Volatile
        var instanceState: String? = null
            private set

        internal fun publishState(text: String) {
            instanceState = text
        }

        /** 取帧是否正在运行（悬浮球据此决定「单击=开始取帧」还是「单击=暂停」） */
        @Volatile
        var isRunning: Boolean = false
            private set

        internal fun publishRunning(running: Boolean) {
            isRunning = running
        }

        /** 最近一帧的诊断文本（引导页显示；不依赖悬浮层能不能画出来） */
        @Volatile
        var diagText: String? = null
            private set

            internal fun publishDiag(text: String) {
            diagText = text
        }

        /** 最近的事件日志（引导页诊断区显示，不依赖 adb） */
        fun eventLog(): String = EventLog.dump()

        fun clearEventLog() = EventLog.clear()

        /** 最近的停止/失败原因（诊断区显示，定位到底是谁把投屏停了） */
        @Volatile
        var stopReason: String? = null
            private set

        internal fun publishReason(reason: String) {
            stopReason = reason
        }

        /** 引导页用的回调：有新诊断时刷新 */
        @Volatile
        var onDiag: (() -> Unit)? = null

        /** 启动取帧服务（带 MediaProjection 授权结果）。 */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            // 用 stopService 而不是 startService(ACTION_STOP)：应用退到后台后再
            // startService 在 API 26+ 会抛 IllegalStateException（后台不允许启动服务）
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}
