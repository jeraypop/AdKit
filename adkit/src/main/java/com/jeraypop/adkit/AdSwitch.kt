package com.jeraypop.adkit

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 广告总开关：从服务端 JSON 读取 adswitch。
 * 约定格式：{"adswitch": true/false}，缺省视为 true。
 * 请求失败或超时也按 true 处理，避免服务不可用时广告全部消失。
 */
internal object AdSwitch {

    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 3000

    /** 开关缓存时间：长时间运行的应用可定期刷新，无需重启 */
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    @Volatile
    private var enabled = true

    @Volatile
    private var resolved = false

    @Volatile
    private var fetching = false

    @Volatile
    private var lastResolvedAt = 0L

    private var fetchedUrl: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val waiters = mutableListOf<(Boolean) -> Unit>()

    fun isEnabled(): Boolean = enabled

    fun isResolved(): Boolean = resolved

    /**
     * 拉取开关。同一 url 在缓存有效期（10 分钟）内只请求一次；失败时保持默认开启。
     * 只应在隐私政策同意后调用。
     */
    fun fetch(url: String?) {
        val target = url?.takeIf { it.isNotBlank() } ?: AdConfig.DEFAULT_AD_SWITCH_URL
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (fetching) return
            if (resolved && fetchedUrl == target && now - lastResolvedAt < CACHE_TTL_MS) return
            fetching = true
            fetchedUrl = target
        }

        Thread({
            AdLog.d("开始请求广告开关：$target")
            var result = true
            try {
                val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                }
                try {
                    val code = connection.responseCode
                    if (code in 200..299) {
                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        result = JSONObject(body).optBoolean("adswitch", true)
                    } else {
                        AdLog.w("广告开关请求失败 httpCode=$code")
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                AdLog.w("广告开关请求异常: ${e.message}")
            }
            resolve(result)
        }, "adkit-ad-switch").start()
    }

    private fun resolve(value: Boolean) {
        val listeners = synchronized(this) {
            enabled = value
            resolved = true
            fetching = false
            lastResolvedAt = System.currentTimeMillis()
            val copy = waiters.toList()
            waiters.clear()
            copy
        }
        AdLog.d("广告开关 adswitch=$value（等待中 ${listeners.size} 个）")
        if (listeners.isNotEmpty()) {
            mainHandler.post {
                AdLog.d("唤醒等待中的 await，enabled=$value")
                listeners.forEach { it(value) }
            }
        }
    }

    /**
     * 等待开关结果；超时则使用当前值（默认 true）。回调切到主线程。
     */
    fun await(timeoutMs: Long, callback: (Boolean) -> Unit) {
        AdLog.d("await 进入：resolved=$resolved enabled=$enabled timeout=${timeoutMs}ms")
        if (resolved) {
            mainHandler.post {
                AdLog.d("await 已解析立即回调 enabled=$enabled")
                callback(enabled)
            }
            return
        }
        synchronized(this) {
            if (!resolved) {
                waiters.add(callback)
                mainHandler.postDelayed({
                    var timedOut = false
                    synchronized(this) {
                        if (!resolved) {
                            waiters.remove(callback)
                            timedOut = true
                        }
                    }
                    if (timedOut) {
                        AdLog.w("await 超时（${timeoutMs}ms），按当前值放行 enabled=$enabled")
                        callback(enabled)
                    }
                }, timeoutMs)
                return
            }
        }
        mainHandler.post {
            AdLog.d("await 注册时已被解析，回调 enabled=$enabled")
            callback(enabled)
        }
    }
}
