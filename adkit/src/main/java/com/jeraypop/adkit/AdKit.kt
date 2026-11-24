package com.jeraypop.adkit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
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

    private var config: AdConfig? = null
    private var privacyChecker: PrivacyChecker? = null
    private var navigator: AdNavigator? = null
    private var initialized = false

    /** TTAdSdk.start 的异步结果，开屏页必须等它成功后再请求广告 */
    private var sdkStarted = false
    private var sdkStartFailed = false
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
    internal fun whenSdkStarted(listener: (Boolean) -> Unit) {
        val immediate: Boolean? = synchronized(this) {
            when {
                sdkStarted -> true
                sdkStartFailed -> false
                else -> {
                    startListeners.add(listener)
                    null
                }
            }
        }

        if (immediate != null) {
            mainHandler.post { listener(immediate) }
        }
    }

    private fun notifySdkStartResult(success: Boolean) {
        val listeners = synchronized(this) {
            sdkStarted = success
            sdkStartFailed = !success
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

        if (config.splashAdId.isNullOrBlank()) {
            if (openHomeAfter) openHome(context)
            return
        }

        if (!initIfAgreed(context)) {
            if (openHomeAfter) openHome(context)
            return
        }

        val intent = Intent(context, AdSplashActivity::class.java)
        intent.putExtra(AdSplashActivity.EXTRA_OPEN_HOME_AFTER, openHomeAfter)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
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

    @JvmStatic
    fun loadBanner(activity: Activity, container: FrameLayout, listener: AdViewListener? = null) {
        if (!ensureInitialized(activity)) {
            listener?.onError(-1, "广告 SDK 未初始化")
            return
        }
        AdLoaders.loadBanner(activity, container, listener)
    }

    @JvmStatic
    fun loadNative(activity: Activity, container: FrameLayout, listener: AdViewListener? = null) {
        if (!ensureInitialized(activity)) {
            listener?.onError(-1, "广告 SDK 未初始化")
            return
        }
        AdLoaders.loadNative(activity, container, listener)
    }

    @JvmStatic
    fun loadReward(activity: Activity, listener: RewardAdListener? = null) {
        if (!ensureInitialized(activity)) {
            listener?.onError(-1, "广告 SDK 未初始化")
            return
        }
        AdLoaders.loadReward(activity, listener)
    }

    @JvmStatic
    fun loadInterstitial(activity: Activity, listener: FullScreenAdListener? = null) {
        if (!ensureInitialized(activity)) {
            listener?.onError(-1, "广告 SDK 未初始化")
            return
        }
        AdLoaders.loadFullScreen(activity, AdLoaders.Kind.INTERSTITIAL, listener)
    }

    @JvmStatic
    fun loadFullScreen(activity: Activity, listener: FullScreenAdListener? = null) {
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
