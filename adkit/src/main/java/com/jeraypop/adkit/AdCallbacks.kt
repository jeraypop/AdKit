package com.jeraypop.adkit

/**
 * Banner / 原生模板广告回调。
 */
interface AdViewListener {
    fun onLoaded()
    fun onError(code: Int, message: String?)
}

/**
 * 激励视频回调。onReward 只会回调一次，应用在这里发奖。
 */
interface RewardAdListener {
    fun onLoaded()
    fun onShow()
    fun onReward()
    fun onClose()
    fun onError(code: Int, message: String?)
}

/**
 * 插屏 / 全屏视频回调。
 */
interface FullScreenAdListener {
    fun onLoaded()
    fun onShow()
    fun onClose()
    fun onError(code: Int, message: String?)
}

open class SimpleAdViewListener : AdViewListener {
    override fun onLoaded() = Unit
    override fun onError(code: Int, message: String?) = Unit
}

open class SimpleRewardAdListener : RewardAdListener {
    override fun onLoaded() = Unit
    override fun onShow() = Unit
    override fun onReward() = Unit
    override fun onClose() = Unit
    override fun onError(code: Int, message: String?) = Unit
}

open class SimpleFullScreenAdListener : FullScreenAdListener {
    override fun onLoaded() = Unit
    override fun onShow() = Unit
    override fun onClose() = Unit
    override fun onError(code: Int, message: String?) = Unit
}
