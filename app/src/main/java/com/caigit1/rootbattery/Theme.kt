package com.caigit1.rootbattery

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
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
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Android 12+ 跟随壁纸取色（Material You）。MIUI / HyperOS 同样支持。 */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    CompositionLocalProvider(
        LocalSemanticColors provides if (darkTheme) DarkSemantics else LightSemantics
    ) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
