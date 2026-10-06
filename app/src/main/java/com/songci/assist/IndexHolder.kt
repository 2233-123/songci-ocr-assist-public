package com.songci.assist

import android.content.Context
import java.util.concurrent.Executors

/**
 * 索引载入器：整进程加载一次，之后复用。
 *
 * 读 assets 是 IO，放在后台线程；加载失败要能被界面看到（不静默失败），
 * 所以错误也缓存下来。
 */
object IndexHolder {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "songci-index").apply { isDaemon = true }
    }

    @Volatile
    private var loaded: VerseIndex? = null

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var loading: Boolean = false
        private set

    /** 已加载好的索引；没加载完返回 null。 */
    val index: VerseIndex? get() = loaded

    /** 已经在内存里的就立刻回调，否则后台加载完再回调（回调在主线程）。 */
    fun get(context: Context, callback: (VerseIndex?) -> Unit) {
        loaded?.let {
            callback(it)
            return
        }
        loading = true
        executor.execute {
            val result = runCatching {
                val json = context.applicationContext.assets.open(VerseIndex.ASSET_NAME)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                VerseIndex.fromJson(json)
            }
            val value = result.getOrNull()
            if (value != null) {
                loaded = value
                error = null
            } else {
                error = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                    ?: "未知错误"
            }
            loading = false
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(value) }
        }
    }
}
