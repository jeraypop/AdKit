package com.jeraypop.adkit

import android.app.Activity
import android.content.Context
import android.graphics.Color
import androidx.annotation.DrawableRes

/**
 * 单个应用接入 AdKit 的配置。
 */
data class AdConfig @JvmOverloads constructor(
    val appId: String,
    val appName: String,
    val splashAdId: String? = null,
    val bannerAdId: String? = null,
    val nativeAdId: String? = null,
    val interstitialAdId: String? = null,
    val fullScreenAdId: String? = null,
    val rewardAdId: String? = null,
    val debug: Boolean = false,
    val useMediation: Boolean = true,
    val splashUi: SplashUiConfig = SplashUiConfig(),
    /** 广告结束后要打开的 Activity；不设置则使用 AdNavigator */
    val nextActivity: Class<out Activity>? = null
)

/**
 * 开屏广告页的 UI 配置。
 */
data class SplashUiConfig @JvmOverloads constructor(
    /** 0 表示使用应用图标 */
    @DrawableRes val brandIconRes: Int = 0,
    val countdownSeconds: Int = 5,
    val exposedFallbackMs: Long = 5000L,
    val backgroundColor: Int = Color.WHITE,
    val countdownTextColor: Int = Color.parseColor("#99000000")
)

/**
 * 各应用自己的隐私判断，AdKit 不绑定具体存储 key。
 */
fun interface PrivacyChecker {
    fun isAgreed(context: Context): Boolean
}

/**
 * 广告结束后的主页跳转。Activity 型应用也可以直接用 AdConfig.nextActivity。
 */
fun interface AdNavigator {
    fun openHome(context: Context)
}
