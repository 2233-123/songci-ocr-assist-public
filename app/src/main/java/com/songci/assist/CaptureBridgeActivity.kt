package com.songci.assist

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * 透明桥接页：**只用来拉起录屏授权**，授权完立刻把自己连同任务一起退到后台。
 *
 * ### 为什么需要它
 *
 * MediaProjection 授权需要 Activity 参与，但用户此刻在游戏里。如果为了授权切回
 * 主界面，屏幕方向会变（游戏横屏 / 桌面竖屏），而 **Android 16 一旦显示方向变化
 * 就立即停掉 MediaProjection** —— 这正是"刚授权就变已停止"的根因。
 *
 * 本页全透明、锁横屏，授权流程在游戏画面上完成；结束后调
 * [moveTaskToBack] 把整个任务退到后台，**前台自动回到游戏**，
 * 用户感觉不到任何跳转。
 *
 * 前置条件：`PROJECT_MEDIA` 应用操作项需为 allow（一次性 adb 授权，
 * 见 README「免弹窗授权」）。授权后系统不再弹确认框，本页只有几十毫秒。
 */
class CaptureBridgeActivity : AppCompatActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        EventLog.log("bridge.result", "resultCode=${result.resultCode} data=${data != null}")
        var ok = false
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptureService.start(this, result.resultCode, data)
            ok = true
        } else {
            EventLog.log("bridge.cancelled", "用户取消了授权")
        }
        finishToForeground(ok)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.log("bridge.onCreate", "")
        if (savedInstanceState != null) {
            // 重建（例如系统回收）时不再弹一次授权，避免死循环
            finishToForeground(false)
            return
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            EventLog.log("bridge.noOverlay", "没有悬浮窗权限，直接退出")
            finishToForeground(false)
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        runCatching { launcher.launch(captureIntent(manager)) }
            .onFailure {
                EventLog.log("bridge.fail", "拉起授权失败：${it.javaClass.simpleName}")
                finishToForeground(false)
            }
    }

    /**
     * 录制意图。
     *
     * Android 14 起 `createScreenCaptureIntent()` 会弹出「选择应用 / 整个屏幕」选择器，
     * 多一次界面跳转就多一次方向变化的机会。这里用官方推荐的
     * [MediaProjectionConfig.createConfigForDefaultDisplay] 直接锁定**整个屏幕**，
     * 跳过应用选择器。
     */
    private fun captureIntent(manager: MediaProjectionManager): Intent =
        if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            @Suppress("DEPRECATION")
            manager.createScreenCaptureIntent()
        }

    /** 用户按返回：当成取消处理，一样要让出前台 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        EventLog.log("bridge.back", "用户返回")
        finishToForeground(false)
    }

    /**
     * 收尾：把自己 finish 掉，并把**整个任务退到后台**。
     *
     * 只 finish 是不够的：我们的任务栈里可能还留着 MainActivity，系统会把它重新
     * 拉到前台（用户看到的就是"莫名其妙跳回脚本应用"）。moveTaskToBack 之后
     * 前台会落到用户原先在用的游戏上。
     */
    private fun finishToForeground(started: Boolean) {
        EventLog.log("bridge.finish", if (started) "已开始取帧，任务退到后台" else "未开始，任务退到后台")
        runCatching { moveTaskToBack(true) }
        finish()
    }
}

