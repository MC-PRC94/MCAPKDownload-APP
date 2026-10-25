package com.mcstation.download.data.mc

/**
 * Minecraft 安装包下载渠道。
 *
 * 三个渠道的包内容、签名、提供方都不同，安装时**互相不能覆盖**（签名不一致），
 * 因此下载前必须让用户明确选择，并在已装渠道与所选渠道不同时提示备份数据。
 *
 * 【文案】[subtitle] / [provider] 由用户指定，不要随意改动。
 */
enum class DownloadChannel(
    /** 弹窗中显示的名称 */
    val title: String,
    /** 名称下方的小字说明 */
    val subtitle: String,
    /** 包格式：true = 单 APK（可直接安装），false = APKS 拆分容器（需拆分安装） */
    val singleApk: Boolean,
    /** 是否已支持下载（false 时选项置灰） */
    val available: Boolean = true,
    /**
     * 是否出现在「请选择下载渠道」弹窗里。
     * 官方包（APKS）暂未接入，用户要求先不提供该选项 —— 但枚举保留，
     * 用于识别手机里已装的官方包（跨渠道时仍会提示备份）。
     */
    val selectable: Boolean = true,
) {
    /** 标准去验证包：mcdl.xbu.hk 提供，单 APK，免谷歌验证 */
    DEV_VERIFIED(
        title = "标准去验证包",
        subtitle = "推荐 适合大部分玩家 由mcdl.xbu.hk提供",
        singleApk = true,
    ),

    /** 官方包：mcdl.xbu.hk 提供，APKS 五件套，需要正版与谷歌框架（不提供选择，仅用于识别已装包） */
    OFFICIAL(
        title = "官方包",
        subtitle = "适合有正版的玩家 由mcdl.xbu.hk提供",
        singleApk = false,
        available = false,
        selectable = false,
    ),

    /** MCPELIFE 包（界面显示为「第三方去验证包」）：mcpelife.com 提供，单 APK，含注入文件 */
    MCPELIFE(
        title = "第三方去验证包",
        subtitle = "不推荐 有部分注入文件 由mcpelife.com提供",
        singleApk = true,
    );

    /** 弹窗标题（用户指定） */
    companion object {
        const val DIALOG_TITLE = "请选择下载渠道"
        /** 已安装标记：在选项后追加（用户未指定文案，可随时修改） */
        const val INSTALLED_BADGE = "已安装"
        /** 暂未开放的标记 */
        const val UNAVAILABLE_BADGE = "暂未开放"
        /** 默认渠道：标准去验证包（推荐项） */
        val DEFAULT = DEV_VERIFIED
    }
}
