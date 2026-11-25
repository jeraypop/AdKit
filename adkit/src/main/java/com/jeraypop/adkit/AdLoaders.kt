package com.jeraypop.adkit

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.bytedance.sdk.openadsdk.AdSlot
import com.bytedance.sdk.openadsdk.TTAdConstant
import com.bytedance.sdk.openadsdk.TTAdDislike
import com.bytedance.sdk.openadsdk.TTAdNative
import com.bytedance.sdk.openadsdk.TTAdSdk
import com.bytedance.sdk.openadsdk.TTFullScreenVideoAd
import com.bytedance.sdk.openadsdk.TTNativeExpressAd
import com.bytedance.sdk.openadsdk.TTRewardVideoAd
import com.bytedance.sdk.openadsdk.mediation.ad.MediationAdSlot
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal object AdLoaders {

    enum class Kind {
        INTERSTITIAL,
        FULL_SCREEN
    }

    private var bannerAd: TTNativeExpressAd? = null
    private var nativeAd: TTNativeExpressAd? = null
    private var rewardAd: TTRewardVideoAd? = null
    private var interstitialAd: TTFullScreenVideoAd? = null
    private var fullScreenAd: TTFullScreenVideoAd? = null

    // Banner 去重、节流与失败重试（库内统一处理，接入方无需适配）
    private const val BANNER_REQUEST_INTERVAL = 10_000L
    private val BANNER_RETRY_DELAYS = longArrayOf(3_000L, 8_000L)
    private val bannerStates = WeakHashMap<FrameLayout, BannerState>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private class BannerState {
        var loading = false
        var loaded = false
        var lastRequestTime = 0L
        var attempt = 0
        var generation = 0
        var retryRunnable: Runnable? = null
        var listener: AdViewListener? = null
        var activityRef: WeakReference<Activity>? = null
    }

    private var rewardShown = false
    private var rewardGranted = false
    private var interstitialShown = false
    private var fullScreenShown = false

    private fun adNative(context: Context): TTAdNative? {
        return TTAdSdk.getAdManager()?.createAdNative(context)
    }

    fun loadBanner(activity: Activity, container: FrameLayout, listener: AdViewListener?) {
        val adId = AdKit.currentConfig()?.bannerAdId
        if (adId.isNullOrBlank()) {
            listener?.onError(-1, "未配置 Banner 广告位")
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return

        val state = bannerStates.getOrPut(container) { BannerState() }
        // 已加载或正在加载：避免页面切换时重复请求触发穿山甲频次限制
        if (state.loading || state.loaded) return
        val now = System.currentTimeMillis()
        if (now - state.lastRequestTime < BANNER_REQUEST_INTERVAL) return

        state.loading = true
        state.lastRequestTime = now
        state.attempt = 0
        state.listener = listener
        state.activityRef = WeakReference(activity)
        state.generation++
        val generation = state.generation

        // 等容器完成布局再请求，避免 0 尺寸导致渲染失败
        container.post {
            if (generation != state.generation) return@post
            if (!container.isAttachedToWindow) {
                state.loading = false
                return@post
            }
            requestBanner(container, state, generation)
        }
    }

    private fun requestBanner(container: FrameLayout, state: BannerState, generation: Int) {
        val activity = state.activityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            state.loading = false
            return
        }
        val adId = AdKit.currentConfig()?.bannerAdId
        if (adId.isNullOrBlank()) {
            state.loading = false
            state.listener?.onError(-1, "未配置 Banner 广告位")
            return
        }
        val native = adNative(activity)
        if (native == null) {
            state.loading = false
            state.listener?.onError(-1, "TTAdNative 为空")
            return
        }

        val density = activity.resources.displayMetrics.density
        val slot = AdSlot.Builder()
            .setCodeId(adId)
            .setImageAcceptedSize((320 * density).toInt(), (150 * density).toInt())
            .build()

        native.loadBannerExpressAd(slot, object : TTAdNative.NativeExpressAdListener {
            override fun onError(code: Int, message: String?) {
                if (generation != state.generation) return
                AdLog.e("Banner 加载失败 code=$code msg=$message attempt=${state.attempt}")
                handleBannerFailure(container, state, generation, code, message)
            }

            override fun onNativeExpressAdLoad(ads: MutableList<TTNativeExpressAd>?) {
                if (generation != state.generation) return
                val ad = ads?.firstOrNull()
                if (ad == null) {
                    handleBannerFailure(container, state, generation, -1, "Banner 返回为空")
                    return
                }
                bannerAd?.destroy()
                bannerAd = ad
                state.loading = false
                state.loaded = true
                state.listener?.onLoaded()
                bindBannerExpressAd(activity, ad, container, state, generation)
            }
        })
    }

    private fun handleBannerFailure(
        container: FrameLayout,
        state: BannerState,
        generation: Int,
        code: Int,
        message: String?
    ) {
        state.loading = false
        state.loaded = false
        container.visibility = View.GONE

        val activity = state.activityRef?.get()
        val canRetry = activity != null
                && !activity.isFinishing
                && !activity.isDestroyed
                && container.isAttachedToWindow
                && state.attempt < BANNER_RETRY_DELAYS.size
        if (!canRetry) {
            state.listener?.onError(code, message)
            return
        }

        val delay = BANNER_RETRY_DELAYS[state.attempt]
        state.attempt++
        state.loading = true
        val runnable = Runnable {
            if (generation != state.generation) {
                state.loading = false
                return@Runnable
            }
            val act = state.activityRef?.get()
            if (act == null || act.isFinishing || act.isDestroyed || !container.isAttachedToWindow) {
                state.loading = false
                state.listener?.onError(code, message)
                return@Runnable
            }
            AdLog.e("Banner 重试 ${state.attempt}")
            requestBanner(container, state, generation)
        }
        state.retryRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    private fun bindBannerExpressAd(
        activity: Activity,
        ad: TTNativeExpressAd,
        container: FrameLayout,
        state: BannerState,
        generation: Int
    ) {
        ad.setExpressInteractionListener(object : TTNativeExpressAd.ExpressAdInteractionListener {
            override fun onAdClicked(view: View?, type: Int) = Unit

            override fun onAdShow(view: View?, type: Int) {
                container.visibility = View.VISIBLE
            }

            override fun onRenderFail(view: View?, msg: String?, code: Int) {
                if (generation != state.generation) return
                AdLog.e("Banner 渲染失败 code=$code msg=$msg attempt=${state.attempt}")
                handleBannerFailure(container, state, generation, code, msg)
            }

            override fun onRenderSuccess(view: View?, width: Float, height: Float) {
                if (generation != state.generation) return
                showExpressView(container, view)
            }
        })

        ad.setDislikeCallback(activity, object : TTAdDislike.DislikeInteractionCallback {
            override fun onShow() = Unit

            override fun onSelected(position: Int, value: String?, enforce: Boolean) {
                container.removeAllViews()
                container.visibility = View.GONE
                // 用户关闭后允许下次重新请求
                state.loaded = false
                state.lastRequestTime = 0L
            }

            override fun onCancel() = Unit
        })

        val adView = ad.expressAdView
        if (adView != null) {
            showExpressView(container, adView)
        } else {
            ad.render()
        }
    }

    fun loadNative(activity: Activity, container: FrameLayout, listener: AdViewListener?) {
        val adId = AdKit.currentConfig()?.nativeAdId
        if (adId.isNullOrBlank()) {
            listener?.onError(-1, "未配置原生广告位")
            return
        }

        val native = adNative(activity)
        if (native == null) {
            listener?.onError(-1, "TTAdNative 为空")
            return
        }

        val slot = AdSlot.Builder()
            .setCodeId(adId)
            .setAdCount(1)
            .setExpressViewAcceptedSize(350f, 0f)
            .build()

        native.loadNativeExpressAd(slot, object : TTAdNative.NativeExpressAdListener {
            override fun onError(code: Int, message: String?) {
                AdLog.e("原生加载失败 code=$code msg=$message")
                listener?.onError(code, message)
            }

            override fun onNativeExpressAdLoad(ads: MutableList<TTNativeExpressAd>?) {
                val ad = ads?.firstOrNull() ?: return
                nativeAd?.destroy()
                nativeAd = ad
                listener?.onLoaded()
                bindExpressAd(activity, ad, container, listener)
            }
        })
    }

    private fun bindExpressAd(
        activity: Activity,
        ad: TTNativeExpressAd,
        container: FrameLayout,
        listener: AdViewListener?
    ) {
        ad.setExpressInteractionListener(object : TTNativeExpressAd.ExpressAdInteractionListener {
            override fun onAdClicked(view: View?, type: Int) = Unit

            override fun onAdShow(view: View?, type: Int) {
                container.visibility = View.VISIBLE
            }

            override fun onRenderFail(view: View?, msg: String?, code: Int) {
                AdLog.e("模板广告渲染失败 code=$code msg=$msg")
                listener?.onError(code, msg)
            }

            override fun onRenderSuccess(view: View?, width: Float, height: Float) {
                showExpressView(container, view)
            }
        })

        ad.setDislikeCallback(activity, object : TTAdDislike.DislikeInteractionCallback {
            override fun onShow() = Unit

            override fun onSelected(position: Int, value: String?, enforce: Boolean) {
                container.removeAllViews()
                container.visibility = View.GONE
            }

            override fun onCancel() = Unit
        })

        val adView = ad.expressAdView
        if (adView != null) {
            showExpressView(container, adView)
        } else {
            ad.render()
        }
    }

    private fun showExpressView(container: FrameLayout, view: View?) {
        view ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        container.removeAllViews()
        container.addView(view)
        container.visibility = View.VISIBLE
    }

    fun loadReward(activity: Activity, listener: RewardAdListener?) {
        val adId = AdKit.currentConfig()?.rewardAdId
        if (adId.isNullOrBlank()) {
            listener?.onError(-1, "未配置激励广告位")
            return
        }

        val native = adNative(activity)
        if (native == null) {
            listener?.onError(-1, "TTAdNative 为空")
            return
        }

        rewardShown = false
        rewardGranted = false

        val slot = AdSlot.Builder()
            .setCodeId(adId)
            .setOrientation(TTAdConstant.ORIENTATION_VERTICAL)
            .setMediationAdSlot(
                MediationAdSlot.Builder()
                    .setExtraObject("show_adn_load_error_detail", true)
                    .build()
            )
            .build()

        native.loadRewardVideoAd(slot, object : TTAdNative.RewardVideoAdListener {
            override fun onError(code: Int, message: String?) {
                AdLog.e("激励加载失败 code=$code msg=$message")
                listener?.onError(code, message)
            }

            override fun onRewardVideoAdLoad(ad: TTRewardVideoAd?) {
                ad ?: return
                rewardAd = ad
                listener?.onLoaded()
            }

            override fun onRewardVideoCached() {
                rewardAd?.let { showReward(activity, it, listener) }
            }

            override fun onRewardVideoCached(ad: TTRewardVideoAd?) {
                ad ?: return
                rewardAd = ad
                showReward(activity, ad, listener)
            }
        })
    }

    private fun showReward(
        activity: Activity,
        ad: TTRewardVideoAd,
        listener: RewardAdListener?
    ) {
        if (rewardShown) return
        rewardShown = true

        ad.setRewardAdInteractionListener(object :
            TTRewardVideoAd.RewardAdInteractionListener {
            override fun onAdShow() {
                listener?.onShow()
            }

            override fun onAdVideoBarClick() = Unit

            override fun onAdClose() {
                listener?.onClose()
            }

            override fun onVideoComplete() {
                AdLog.d("激励视频播放完成")
                grantReward(listener)
            }

            override fun onVideoError() {
                listener?.onError(-1, "激励视频播放失败")
            }

            override fun onRewardVerify(
                rewardValid: Boolean,
                rewardAmount: Int,
                rewardName: String?,
                errorCode: Int,
                errorMsg: String?
            ) = Unit

            override fun onRewardArrived(
                isRewardValid: Boolean,
                rewardType: Int,
                extraInfo: Bundle?
            ) {
                AdLog.d("onRewardArrived valid=$isRewardValid type=$rewardType")
                if (isRewardValid) {
                    grantReward(listener)
                }
            }

            override fun onSkippedVideo() {
                listener?.onClose()
            }
        })

        ad.showRewardVideoAd(activity)
    }

    private fun grantReward(listener: RewardAdListener?) {
        if (rewardGranted) return
        rewardGranted = true
        AdLog.d("发放激励奖励")
        listener?.onReward()
    }

    fun loadFullScreen(activity: Activity, kind: Kind, listener: FullScreenAdListener?) {
        val config = AdKit.currentConfig()
        val adId = when (kind) {
            Kind.INTERSTITIAL -> config?.interstitialAdId
            Kind.FULL_SCREEN -> config?.fullScreenAdId
        }
        if (adId.isNullOrBlank()) {
            listener?.onError(-1, "未配置${if (kind == Kind.INTERSTITIAL) "插屏" else "全屏"}广告位")
            return
        }

        val native = adNative(activity)
        if (native == null) {
            listener?.onError(-1, "TTAdNative 为空")
            return
        }

        if (kind == Kind.INTERSTITIAL) {
            interstitialShown = false
        } else {
            fullScreenShown = false
        }

        val slot = AdSlot.Builder()
            .setCodeId(adId)
            .setOrientation(TTAdConstant.ORIENTATION_VERTICAL)
            .build()

        native.loadFullScreenVideoAd(slot, object : TTAdNative.FullScreenVideoAdListener {
            override fun onError(code: Int, message: String?) {
                AdLog.e("全屏加载失败 code=$code msg=$message")
                listener?.onError(code, message)
            }

            override fun onFullScreenVideoAdLoad(ad: TTFullScreenVideoAd?) {
                ad ?: return
                if (kind == Kind.INTERSTITIAL) interstitialAd = ad else fullScreenAd = ad
                listener?.onLoaded()
            }

            override fun onFullScreenVideoCached() {
                val ad = if (kind == Kind.INTERSTITIAL) interstitialAd else fullScreenAd
                ad?.let { showFullScreen(activity, it, kind, listener) }
            }

            override fun onFullScreenVideoCached(ad: TTFullScreenVideoAd?) {
                ad ?: return
                if (kind == Kind.INTERSTITIAL) interstitialAd = ad else fullScreenAd = ad
                showFullScreen(activity, ad, kind, listener)
            }
        })
    }

    private fun showFullScreen(
        activity: Activity,
        ad: TTFullScreenVideoAd,
        kind: Kind,
        listener: FullScreenAdListener?
    ) {
        val alreadyShown = if (kind == Kind.INTERSTITIAL) interstitialShown else fullScreenShown
        if (alreadyShown) return

        if (kind == Kind.INTERSTITIAL) {
            interstitialShown = true
        } else {
            fullScreenShown = true
        }

        ad.setFullScreenVideoAdInteractionListener(
            object : TTFullScreenVideoAd.FullScreenVideoAdInteractionListener {
                override fun onAdShow() {
                    listener?.onShow()
                }

                override fun onAdVideoBarClick() = Unit

                override fun onAdClose() {
                    listener?.onClose()
                }

                override fun onVideoComplete() = Unit

                override fun onSkippedVideo() = Unit
            }
        )

        ad.showFullScreenVideoAd(activity)
    }

    fun destroy() {
        resetBannerStates()
        bannerAd?.destroy()
        bannerAd = null

        nativeAd?.destroy()
        nativeAd = null

        interstitialAd?.mediationManager?.destroy()
        interstitialAd = null

        fullScreenAd?.mediationManager?.destroy()
        fullScreenAd = null

        rewardAd?.mediationManager?.destroy()
        rewardAd = null
    }

    private fun resetBannerStates() {
        bannerStates.values.forEach { state ->
            state.generation++
            state.retryRunnable?.let { mainHandler.removeCallbacks(it) }
            state.retryRunnable = null
            state.loading = false
            state.loaded = false
            state.listener = null
            state.activityRef = null
        }
        bannerStates.clear()
    }
}
