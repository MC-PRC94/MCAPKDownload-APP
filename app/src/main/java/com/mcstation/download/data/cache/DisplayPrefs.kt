package com.mcstation.download.data.cache

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * 显示偏好（设置页「显示」分组）：
 * - 字体大小 fontScale：**只**缩放文字（等价于 Android 的字体大小 / Compose 的 LocalDensity.fontScale）
 * - 显示尺寸 uiScale：整体缩放界面（等价于 Android 的显示大小 / Compose 的 LocalDensity.density）
 *
 * 两者相乘作用于最终文字：实际字号 = density × uiScale × fontScale。
 * 系统的字体大小/显示大小设置依然生效，这里是在系统基础上再叠加一层。
 *
 * 用 Compose 的 mutableStateOf 持有当前值：MainActivity 在 composition 里直接读 `.value`，
 * 用户在设置页拖动滑块时 setXxx() 更新状态 → 整个界面立即重组生效（无需重启）。
 */
object DisplayPrefs {

    private const val FILE = "display_prefs"
    private const val KEY_FONT_SCALE = "font_scale"
    private const val KEY_UI_SCALE = "ui_scale"
    private const val KEY_THEME_COLOR = "theme_color"

    /** 字体大小可选范围：80% ~ 140%，步进 10% */
    const val MIN_FONT_SCALE = 0.8f
    const val MAX_FONT_SCALE = 1.4f
    const val FONT_STEP = 0.1f

    /** 显示尺寸可选范围：85% ~ 115%，步进 5%（再大容易让固定尺寸控件显得局促） */
    const val MIN_UI_SCALE = 0.85f
    const val MAX_UI_SCALE = 1.15f
    const val UI_STEP = 0.05f

    /** 当前字体缩放（Compose 可观察状态，默认 100%） */
    val fontScale = mutableStateOf(1f)

    /** 当前界面缩放（Compose 可观察状态，默认 100%） */
    val uiScale = mutableStateOf(1f)

    /** 自定义主题色 id（Theme.kt 的 ThemeColorOptions），null = 默认紫 */
    val themeColorId = mutableStateOf<String?>(null)

    @Volatile
    private var loaded = false

    /** 应用启动时调用一次：从 SharedPreferences 读回上次选择 */
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        fontScale.value = sp.getFloat(KEY_FONT_SCALE, 1f).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        uiScale.value = sp.getFloat(KEY_UI_SCALE, 1f).coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
        themeColorId.value = sp.getString(KEY_THEME_COLOR, null)
    }

    fun setFontScale(context: Context, value: Float) {
        val v = value.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        fontScale.value = v
        sp(context).edit().putFloat(KEY_FONT_SCALE, v).apply()
    }

    fun setUiScale(context: Context, value: Float) {
        val v = value.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
        uiScale.value = v
        sp(context).edit().putFloat(KEY_UI_SCALE, v).apply()
    }

    /** 切换主题色（传 null 恢复默认紫），立即全局重组生效 */
    fun setThemeColor(context: Context, id: String?) {
        themeColorId.value = id
        sp(context).edit().putString(KEY_THEME_COLOR, id).apply()
    }

    /** 恢复默认（100% / 100%） */
    fun reset(context: Context) {
        setFontScale(context, 1f)
        setUiScale(context, 1f)
    }

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
