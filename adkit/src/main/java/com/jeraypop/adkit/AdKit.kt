package com.jeraypop.adkit

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import com.bytedance.sdk.openadsdk.TTAdConfig
import com.bytedance.sdk.openadsdk.TTAdSdk

/**
 * 广告统一入口。
 *
 * 接入顺序：
 * 1. Application.onCreate 调 [setup] 注册配置、隐私判断和主页跳转；
 * 2. 调 [initIfAgreed]，已同意隐私的应用会在进程启动时初始化；
 * 3. 用户刚点“同意”后调 [onPrivacyAgreed]；
 * 4. 启动页调 [startSplash]。
 */
object AdKit {

    /** 所有 Activity 都 stop 后，延迟这么久才判定为「进入后台」，避开旋转重建的瞬时空档 */
    private const val BACKGROUND_MARK_DELAY_MS = 700L

    private var config: AdConfig? = null
    private var privacyChecker: PrivacyChecker? = null
    private var navigator: AdNavigator? = null
    private var initialized = false

    /** 上一次真正展示开屏的时间（elapsedRealtime），用于回前台补开屏的频控 */
    private var lastSplashShownAt = 0L

    /** 前台回补开屏是否已注册，保证只注册一次 */
    private var foregroundSplashInstalled = false

    /** TTAdSdk.start 的异步结果，开屏页必须等它成功后再请求广告 */
    private var sdkStarted = false
    private var sdkStartFailed = false

    /** start 失败后允许自动重试的次数（仅针对谢幕后再次请求开屏的场景） */
    private const val MAX_START_RETRIES = 1
    private var startRetryCount = 0
    private var startRetrying = false

    private val startListeners = mutableListOf<(Boolean) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun setup(context: Context, config: AdConfig, privacyChecker: PrivacyChecker) {
        this.config = config
        this.privacyChecker = privacyChecker
        AdLog.d("setup appId=${config.appId} appName=${config.appName}")
    }

    @JvmStatic
    fun setNavigator(navigator: AdNavigator?) {
        this.navigator = navigator
    }

    @JvmStatic
    fun currentConfig(): AdConfig? = config

    @JvmStatic
    fun isInitialized(): Boolean = initialized

    @JvmStatic
    fun isSdkStarted(): Boolean = sdkStarted

    /**
     * 等待 TTAdSdk.start 结果。已成功/失败会立即回调，未完成则排队。
     * 回调切到主线程。
     */
    /**
     * 等待 TTAdSdk.start 结果。已成功/失败会立即回调，未完成则排队。
     * 若上次 start 失败，这里会自动重试一次（每个进程最多一次），避免一次失败导致整个进程内开屏全部失效。
     * 回调切到主线程。
     */
    internal fun whenSdkStarted(context: Context, listener: (Boolean) -> Unit) {
        var needRetry = false
        val immediate: Boolean? = synchronized(this) {
            when {
                sdkStarted -> true
                sdkStartFailed -> {
                    if (startRetryCount < MAX_START_RETRIES && !startRetrying) {
                        startRetryCount++
                        startRetrying = true
                        startListeners.add(listener)
                        needRetry = true
                        null
                    } else {
                        false
                    }
                }
                else -> {
                    startListeners.add(listener)
                    null
                }
            }
        }

        if (needRetry) {
            retryStart(context.applicationContext)
            return
        }
        if (immediate != null) {
            mainHandler.post { listener(immediate) }
        }
    }

    private fun retryStart(context: Context) {
        AdLog.w("TTAdSdk.start 上次失败，重试第 $startRetryCount 次")
        val accepted = try {
            TTAdSdk.start(object : TTAdSdk.Callback {
                override fun success() {
                    AdLog.d("TTAdSdk start retry success")
                    notifySdkStartResult(true)
                }

                override fun fail(code: Int, msg: String?) {
                    AdLog.e("TTAdSdk start retry fail code=$code msg=$msg")
                    notifySdkStartResult(false)
                }
            })
            true
        } catch (e: Exception) {
            AdLog.e("TTAdSdk.start 重试异常", e)
            false
        }
        if (!accepted) {
            notifySdkStartResult(false)
        }
    }

    private fun notifySdkStartResult(success: Boolean) {
        val listeners = synchronized(this) {
            sdkStarted = success
            sdkStartFailed = !success
            startRetrying = false
            if (success) {
                startRetryCount = 0
            }
            val copy = startListeners.toList()
            startListeners.clear()
            copy
        }

        if (listeners.isNotEmpty()) {
            mainHandler.post {
                listeners.forEach { it(success) }
            }
        }
    }

    @JvmStatic
    fun isPrivacyAgreed(context: Context): Boolean {
        return privacyChecker?.isAgreed(context) == true
    }

    /**
     * 已同意隐私才初始化广告 SDK。
     * @return true 表示 SDK 已可用
     */
    @JvmStatic
    @Synchronized
    fun initIfAgreed(context: Context): Boolean {
        if (initialized) return true

        val config = config ?: run {
            AdLog.w("AdKit 尚未 setup")
            return false
        }
        if (!isPrivacyAgreed(context)) {
            AdLog.w("隐私政策未同意，暂不初始化广告 SDK")
            return false
        }

        // 隐私同意后再拉取广告总开关
        AdSwitch.fetch(config.adSwitchUrl)

        if (config.appId.isBlank() || config.appId.startsWith("TODO")) {
            AdLog.e("appId 未配置")
            return false
        }

        val adConfig = TTAdConfig.Builder()
            .appId(config.appId)
            .appName(config.appName)
            .useMediation(config.useMediation)
            .supportMultiProcess(false)
            .debug(config.debug)
            .build()

        if (!TTAdSdk.init(context.applicationContext, adConfig)) {
            AdLog.e("TTAdSdk.init 失败")
            notifySdkStartResult(false)
            return false
        }

        initialized = true
        TTAdSdk.start(object : TTAdSdk.Callback {
            override fun success() {
                AdLog.d("TTAdSdk start success")
                notifySdkStartResult(true)
            }

            override fun fail(code: Int, msg: String?) {
                AdLog.e("TTAdSdk start fail code=$code msg=$msg")
                notifySdkStartResult(false)
            }
        })
        return true
    }

    /** 用户刚点“同意隐私政策”后调用 */
    @JvmStatic
    fun onPrivacyAgreed(context: Context) {
        initIfAgreed(context)
    }

    /**
     * 启动开屏：
     * - 未同意隐私 / 没配开屏 / SDK 初始化失败：直接走主页；
     * - 否则打开库内的 [AdSplashActivity]。
     *
     * @param openHomeAfter 开屏结束后是否跳主页。从后台回到前台补开屏时传 false，
     *                      这样开屏页关掉后会直接露出下面的页面，不会重复新建 Activity。
     */
    @JvmStatic
    fun startSplash(context: Context, openHomeAfter: Boolean = true) {
        val config = config ?: run {
            if (openHomeAfter) openHome(context)
            return
        }

        if (!isPrivacyAgreed(context)) {
            AdLog.w("隐私政策未同意，跳过开屏直接进主页")
            if (openHomeAfter) openHome(context)
            return
        }

        // 长驻应用回到前台时按缓存有效期刷新开关
        AdSwitch.fetch(config.adSwitchUrl)
        if (AdSwitch.isResolved() && !AdSwitch.isEnabled()) {
            AdLog.d("广告开关已关闭，跳过开屏")
            if (openHomeAfter) openHome(context)
            return
        }

        if (config.splashAdId.isNullOrBlank()) {
            if (openHomeAfter) openHome(context)
            return
        }

        if (!initIfAgreed(context)) {
            if (openHomeAfter) openHome(context)
            return
        }

        // 回前台补开屏才做频控：两次开屏间隔小于配置值时直接跳过，冷启动不受限制
        if (!openHomeAfter && isInSplashInterval(config)) {
            return
        }

        lastSplashShownAt = SystemClock.elapsedRealtime()

        val intent = Intent(context, AdSplashActivity::class.java)
        intent.putExtra(AdSplashActivity.EXTRA_OPEN_HOME_AFTER, openHomeAfter)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 距上次真正展示开屏还不到 [AdConfig.splashIntervalMs] 时返回 true */
    private fun isInSplashInterval(config: AdConfig): Boolean {
        val interval = config.splashIntervalMs
        if (interval <= 0L || lastSplashShownAt == 0L) return false

        val elapsed = SystemClock.elapsedRealtime() - lastSplashShownAt
        if (elapsed < interval) {
            AdLog.d("开屏频控中，跳过（${elapsed}ms < ${interval}ms）")
            return true
        }
        return false
    }

    /**
     * 安装「回到前台补开屏」能力，整个进程只需调用一次（重复调用会被忽略）。
     *
     * 判定规则：
     * - 应用内页面切换（同为进程内 Activity）不算后台，不会触发；
     * - 全部 Activity 都 stop 并持续 [BACKGROUND_MARK_DELAY_MS] 才算进入后台，
     *   这样旋转/配置变更重建不会误触发；
     * - 回到前台后只对 [shouldSplash] 返回 true 的页面补开屏，
     *   间隔由 [AdConfig.splashIntervalMs] 控制（默认 30 秒）。
     */
    @JvmStatic
    fun installForegroundSplash(
        application: Application,
        shouldSplash: (Activity) -> Boolean = { true }
    ) {
        if (foregroundSplashInstalled) return
        foregroundSplashInstalled = true

        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                private var startedCount = 0
                private var isBackground = false
                private val handler = Handler(Looper.getMainLooper())
                private val markBackground = Runnable {
                    if (startedCount == 0) {
                        isBackground = true
                        AdLog.d("应用进入后台")
                    }
                }

                override fun onActivityStarted(activity: Activity) {
                    val fromBackground = isBackground
                    startedCount++

                    if (!fromBackground) return

                    isBackground = false
                    handler.removeCallbacks(markBackground)

                    // 开屏自己、正在退出的壳页不再叠加开屏
                    if (activity is AdSplashActivity || activity.isFinishing) return
                    if (!shouldSplash(activity)) return

                    AdLog.d("回到前台，补一次开屏: ${activity.javaClass.simpleName}")
                    startSplash(activity, openHomeAfter = false)
                }

                override fun onActivityStopped(activity: Activity) {
                    // 配置变更的旧实例先不减，等 onActivityDestroyed 再平衡，避免瞬时空档
                    if (activity.isChangingConfigurations) return
                    startedCount = (startedCount - 1).coerceAtLeast(0)
                    if (startedCount == 0) {
                        handler.postDelayed(markBackground, BACKGROUND_MARK_DELAY_MS)
                    }
                }

                override fun onActivityDestroyed(activity: Activity) {
                    if (!activity.isChangingConfigurations) return
                    startedCount = (startedCount - 1).coerceAtLeast(0)
                    if (startedCount == 0) {
                        handler.postDelayed(markBackground, BACKGROUND_MARK_DELAY_MS)
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            }
        )
    }

    /** 广告结束后的统一主页跳转 */
    @JvmStatic
    fun openHome(context: Context) {
        val navigator = navigator
        if (navigator != null) {
            navigator.openHome(context)
            return
        }

        val nextActivity = config?.nextActivity
        if (nextActivity != null) {
            val intent = Intent(context, nextActivity)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent != null) {
            if (context !is Activity) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
        } else {
            AdLog.e("没有可用的主页跳转目标")
        }
    }

    /** 当前广告总开关状态；未拉取到结果前默认 true */
    @JvmStatic
    fun isAdEnabled(): Boolean = AdSwitch.isEnabled()

    @JvmStatic
    fun loadBanner(activity: Activity, container: FrameLayout, listener: AdViewListener? = null) {
        if (!AdSwitch.isEnabled()) {
            listener?.onError(-1, "广告开关已关闭")
            return
        }
        if (!ensureInitialized(activity)) {
            listener?.onError(-1, "广告 SDK 未初始化")
            return
        }
        AdLoaders.loadBanner(activity, container, listener)
    }

    @JvmStatic
    fun loadNative(activity: Activity, container: FrameLayout, listener: AdViewListener? = null) {
        if (!AdSwitch.isEnabled()) {
            listener?.onError(-1, "广告开关已关闭")
            return
        }
        if (!ensureInitialized(activity)) {
            listener?.onError(-1, "广告 SDK 未初始化")
            return
        }
        AdLoaders.loadNative(activity, container, listener)
    }

    @JvmStatic
    fun loadReward(activity: Activity, listener: RewardAdListener? = null) {
        if (!AdSwitch.isEnabled()) {
            listener?.onError(-1, "广告开关已关闭")
            return
        }
        if (!ensureInitialized(activity)) {
            listener?.onError(-1, "广告 SDK 未初始化")
            return
        }
        AdLoaders.loadReward(activity, listener)
    }

    @JvmStatic
    fun loadInterstitial(activity: Activity, listener: FullScreenAdListener? = null) {
        if (!AdSwitch.isEnabled()) {
            listener?.onError(-1, "广告开关已关闭")
            return
        }
        if (!ensureInitialized(activity)) {
            listener?.onError(-1, "广告 SDK 未初始化")
            return
        }
        AdLoaders.loadFullScreen(activity, AdLoaders.Kind.INTERSTITIAL, listener)
    }

    @JvmStatic
    fun loadFullScreen(activity: Activity, listener: FullScreenAdListener? = null) {
        if (!AdSwitch.isEnabled()) {
            listener?.onError(-1, "广告开关已关闭")
            return
        }
        if (!ensureInitialized(activity)) {
            listener?.onError(-1, "广告 SDK 未初始化")
            return
        }
        AdLoaders.loadFullScreen(activity, AdLoaders.Kind.FULL_SCREEN, listener)
    }

    @JvmStatic
    fun destroy() {
        AdLoaders.destroy()
    }

    private fun ensureInitialized(context: Context): Boolean {
        return initialized || initIfAgreed(context)
    }
}
