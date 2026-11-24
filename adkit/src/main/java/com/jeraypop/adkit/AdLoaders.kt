package com.jeraypop.adkit

import android.app.Activity
import android.content.Context
import android.os.Bundle
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

        val native = adNative(activity)
        if (native == null) {
            listener?.onError(-1, "TTAdNative 为空")
            return
        }

        val density = activity.resources.displayMetrics.density
        val slot = AdSlot.Builder()
            .setCodeId(adId)
            .setImageAcceptedSize((320 * density).toInt(), (150 * density).toInt())
            .build()

        native.loadBannerExpressAd(slot, object : TTAdNative.NativeExpressAdListener {
            override fun onError(code: Int, message: String?) {
                container.visibility = View.GONE
                AdLog.e("Banner 加载失败 code=$code msg=$message")
                listener?.onError(code, message)
            }

            override fun onNativeExpressAdLoad(ads: MutableList<TTNativeExpressAd>?) {
                val ad = ads?.firstOrNull() ?: return
                bannerAd?.destroy()
                bannerAd = ad
                listener?.onLoaded()
                bindExpressAd(activity, ad, container, listener)
            }
        })
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
}
