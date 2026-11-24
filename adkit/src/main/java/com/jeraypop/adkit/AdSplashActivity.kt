package com.jeraypop.adkit

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import com.bytedance.sdk.openadsdk.AdSlot
import com.bytedance.sdk.openadsdk.CSJAdError
import com.bytedance.sdk.openadsdk.CSJSplashAd
import com.bytedance.sdk.openadsdk.TTAdNative
import com.bytedance.sdk.openadsdk.TTAdSdk

/**
 * AdKit 内置开屏页：白底 + 应用图标 + 右上角倒计时，
 * 曝光后等广告关闭，超时/失败兜底进主页。
 */
class AdSplashActivity : Activity(),
    TTAdNative.CSJSplashAdListener,
    CSJSplashAd.SplashAdListener {

    private companion object {
        /** 等待 TTAdSdk.start 的上限（毫秒） */
        const val SDK_START_TIMEOUT_MS = 8000L
    }

    private lateinit var container: FrameLayout
    private lateinit var tvTitle: TextView

    private var config: AdConfig? = null
    private var splashAd: CSJSplashAd? = null

    private var countdown = 0
    @Volatile
    private var clickableAd = false
    @Volatile
    private var blockByAccessibility = false
    @Volatile
    private var adExposure = false

    private var stopNext = false
    private var retryCount = 0

    private val handler = Handler(Looper.getMainLooper())
    private val forceNext = Runnable { next() }

    /** 等 TTAdSdk.start 的上限，超时直接进主页，避免首次启动停在开屏页 */
    private val sdkStartTimeout = Runnable { next() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = AdKit.currentConfig()
        if (config == null || config.splashAdId.isNullOrBlank()) {
            next()
            return
        }
        this.config = config

        setContentView(createContentView(config))
        waitForSdkThenFetch()
    }

    /**
     * 首次安装时 SDK 可能刚 init，TTAdSdk.start 还是异步的。
     * 这里等 start 成功后再开始倒计时并请求开屏：
     * - 若倒计时先跑，start 还没成功页面就退出了，表现为「首次同意后没有开屏」；
     * - start 失败或等待超时则直接进主页，避免停在启动页。
     */
    private fun waitForSdkThenFetch() {
        if (!AdKit.initIfAgreed(this)) {
            next()
            return
        }

        handler.postDelayed(sdkStartTimeout, SDK_START_TIMEOUT_MS)

        AdKit.whenSdkStarted { success ->
            if (isFinishing || isDestroyed) return@whenSdkStarted
            handler.removeCallbacks(sdkStartTimeout)
            if (success) {
                startCountdown(config?.splashUi?.countdownSeconds ?: 5)
                fetchSplash()
            } else {
                next()
            }
        }
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).toInt()
    }

    private fun createContentView(config: AdConfig): View {
        val root = RelativeLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(config.splashUi.backgroundColor)
        }

        val brandIcon = ImageView(this).apply {
            if (config.splashUi.brandIconRes != 0) {
                setImageResource(config.splashUi.brandIconRes)
            } else {
                setImageDrawable(applicationInfo.loadIcon(packageManager))
            }
        }
        val brandLp = RelativeLayout.LayoutParams(dp(160f), dp(160f)).apply {
            addRule(RelativeLayout.CENTER_IN_PARENT)
        }
        root.addView(brandIcon, brandLp)

        tvTitle = TextView(this).apply {
            setTextColor(config.splashUi.countdownTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            isEnabled = false
            text = config.splashUi.countdownSeconds.toString()
            setOnClickListener { next() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x1F000000)
            }
        }

        val size = dp(32f)
        val titleLp = RelativeLayout.LayoutParams(size, size).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
            addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            topMargin = statusBarHeight() + dp(12f)
            rightMargin = dp(16f)
        }
        root.addView(tvTitle, titleLp)

        container = FrameLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(container)

        return root
    }

    private fun statusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private fun startCountdown(seconds: Int) {
        countdown = seconds
        tvTitle.text = countdown.toString()

        handler.postDelayed(object : Runnable {
            override fun run() {
                if (adExposure) return

                if (countdown <= 0) {
                    handler.postDelayed({
                        if (!adExposure) {
                            tvTitle.isEnabled = true
                            tvTitle.text = "进入主界面"
                            next()
                        }
                    }, 300L)
                    return
                }

                tvTitle.text = countdown.toString()
                countdown--
                handler.postDelayed(this, 1000L)
            }
        }, 1000L)
    }

    private fun fetchSplash() {
        val config = config ?: run {
            next()
            return
        }
        if (!AdKit.initIfAgreed(this)) {
            next()
            return
        }

        val adNative = TTAdSdk.getAdManager()?.createAdNative(this)
        if (adNative == null) {
            AdLog.e("TTAdNative 为空")
            next()
            return
        }

        val metrics = resources.displayMetrics
        val slot = AdSlot.Builder()
            .setCodeId(config.splashAdId)
            .setImageAcceptedSize(metrics.widthPixels, metrics.heightPixels)
            .build()

        adNative.loadSplashAd(slot, this, 3500)
    }

    private fun blockAccessibility(view: View) {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun performAccessibilityAction(
                host: View,
                action: Int,
                args: Bundle?
            ): Boolean {
                return if (action == AccessibilityNodeInfo.ACTION_CLICK ||
                    action == AccessibilityNodeInfo.ACTION_LONG_CLICK
                ) {
                    true
                } else {
                    super.performAccessibilityAction(host, action, args)
                }
            }
        }

        val accessibilityManager =
            getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        view.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN &&
                accessibilityManager?.isEnabled == true
            ) {
                v?.isClickable = false
            }
            false
        }
    }

    override fun onSplashLoadSuccess(ad: CSJSplashAd?) {
        AdLog.d("onSplashLoadSuccess")
        splashAd = ad
    }

    override fun onSplashLoadFail(error: CSJAdError?) {
        val code = error?.code ?: -1
        val message = error?.msg
        AdLog.e("onSplashLoadFail code=$code msg=$message")

        if (code == 40004 && retryCount < 1) {
            retryCount++
            container.removeAllViews()
            handler.postDelayed({ fetchSplash() }, 400L)
            return
        }

        next()
    }

    override fun onSplashRenderSuccess(ad: CSJSplashAd?) {
        AdLog.d("onSplashRenderSuccess")
        ad ?: return

        splashAd = ad
        ad.setSplashAdListener(this)

        val splashView = ad.splashView ?: return
        (splashView.parent as? ViewGroup)?.removeView(splashView)
        container.removeAllViews()
        container.addView(splashView)
        blockAccessibility(container)
    }

    override fun onSplashRenderFail(ad: CSJSplashAd?, error: CSJAdError?) {
        AdLog.e("onSplashRenderFail code=${error?.code} msg=${error?.msg}")
        next()
    }

    override fun onSplashAdShow(ad: CSJSplashAd?) {
        AdLog.d("onSplashAdShow")
        adExposure = true
        tvTitle.visibility = View.GONE

        val fallback = config?.splashUi?.exposedFallbackMs ?: 5000L
        handler.postDelayed(forceNext, fallback)
        handler.postDelayed({ clickableAd = true }, 500L)
    }

    override fun onSplashAdClick(ad: CSJSplashAd?) {
        AdLog.d("onSplashAdClick")
    }

    override fun onSplashAdClose(ad: CSJSplashAd?, closeType: Int) {
        AdLog.d("onSplashAdClose closeType=$closeType")

        if (!clickableAd) {
            blockByAccessibility = true
        }
        if (blockByAccessibility) {
            return
        }
        next()
    }

    private fun next() {
        if (stopNext) return
        stopNext = true
        handler.removeCallbacksAndMessages(null)
        AdKit.openHome(this)
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        splashAd?.mediationManager?.destroy()
        super.onDestroy()
    }
}
