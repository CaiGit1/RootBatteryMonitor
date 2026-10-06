package com.caigit1.rootbattery

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 语义色。
 *
 * 这些颜色有明确含义（成功/失败/温度/电压…），不能直接用 Material 的角色色代替，
 * 但**必须**跟着明暗主题走：之前在界面里写死 `Color(0xFFC62828)` 这类浅色专用值，
 * 切到深色模式后要么刺眼要么几乎看不清。
 */
data class SemanticColors(
    val ok: Color,
    val error: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val chartTemperature: Color,
    val chartVoltage: Color,
    val chartCurrent: Color,
    val chartPower: Color,
    val levelLow: Color,
    val levelMid: Color,
    val levelHigh: Color
)

private val LightSemantics = SemanticColors(
    ok = Color(0xFF2E7D32),
    error = Color(0xFFC62828),
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = Color(0xFFB71C1C),
    chartTemperature = Color(0xFFD32F2F),
    chartVoltage = Color(0xFF1976D2),
    chartCurrent = Color(0xFF7B1FA2),
    chartPower = Color(0xFF00897B),
    levelLow = Color(0xFFD32F2F),
    levelMid = Color(0xFFF9A825),
    levelHigh = Color(0xFF2E7D32)
)

private val DarkSemantics = SemanticColors(
    ok = Color(0xFF7BE495),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF4A1F1F),
    onErrorContainer = Color(0xFFFFCDD2),
    chartTemperature = Color(0xFFFF7B72),
    chartVoltage = Color(0xFF7FB8FF),
    chartCurrent = Color(0xFFD0A2FF),
    chartPower = Color(0xFF5BD6C6),
    levelLow = Color(0xFFFF7B72),
    levelMid = Color(0xFFFFD166),
    levelHigh = Color(0xFF7BE495)
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemantics }

@Composable
fun RootBatteryMonitorTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Android 12+ 跟随壁纸取色（Material You）。MIUI / HyperOS 同样支持。 */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = resolveDarkTheme(context, themeMode)
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    CompositionLocalProvider(
        LocalSemanticColors provides semanticColorsFor(darkTheme)
    ) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

// ─────────────────── 非 Compose 环境（悬浮窗 Service）用的取色入口 ───────────────────

internal fun semanticColorsFor(darkTheme: Boolean): SemanticColors =
    if (darkTheme) DarkSemantics else LightSemantics

/** 在非 Compose 环境判断系统是否深色。 */
internal fun isNightMode(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

/** 把用户选择的 [ThemeMode] 解析成最终的明暗结果。 */
internal fun resolveDarkTheme(context: Context, mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isNightMode(context)
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * 在非 Compose 环境取 Material You 配色。
 *
 * `dynamicDarkColorScheme` / `dynamicLightColorScheme` 本身是**普通函数**（不是 @Composable），
 * 可以直接在 Service 里调用，从而保证悬浮窗与主界面取到完全一致的色板，
 * 不必再依赖 `DynamicColors.wrapContextIfAvailable` 那套 XML 主题反射。
 */
internal fun materialYouScheme(context: Context, darkTheme: Boolean): ColorScheme {
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    return when {
        dynamicAvailable && darkTheme -> dynamicDarkColorScheme(context)
        dynamicAvailable -> dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
}
