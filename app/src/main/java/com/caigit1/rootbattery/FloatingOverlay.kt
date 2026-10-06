package com.caigit1.rootbattery

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt

/**
 * 悬浮窗（在其他应用上层显示电池信息）。
 *
 * 用原生 View 而不是 Compose：悬浮窗跑在 Service 里，用 ComposeView 需要额外
 * 提供 ViewTreeLifecycleOwner / SavedStateRegistryOwner / ViewModelStoreOwner，
 * 对一个只读的紧凑信息卡片来说不划算，且更容易踩到生命周期相关的坑。
 *
 * **配色**：走 Material You。`dynamicDark/LightColorScheme` 是普通函数（非 @Composable），
 * 可以直接在这里调用，因此悬浮窗与主界面拿到的是完全一致的动态色板，
 * 不依赖 XML 主题上的 DynamicColors 包裹。
 *
 * **透明度**：用户可调的是**背景**不透明度。文字保持不透明 —— 若文字也跟着变透明，
 * 低不透明度下会彻底读不清。
 *
 * **线程约束**：所有 View / WindowManager 操作都必须在主线程。之前把 addView 放在
 * 协程的 Dispatchers.Default 上，直接抛
 * `Can't create handler inside thread ... that has not called Looper.prepare()`，
 * 悬浮窗静默不显示。这里在类内部统一派发到主线程，调用方不必关心线程。
 */
class FloatingOverlay(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var rowsContainer: LinearLayout? = null
    private var cardBackground: GradientDrawable? = null
    private var handleView: TextView? = null

    private val labelViews = ArrayList<TextView>()
    private val valueViews = LinkedHashMap<OverlayField, TextView>()

    private var lastAlpha = SettingsStore.DEFAULT_OVERLAY_ALPHA
    private var lastBackground = OverlayBackground.DEFAULT
    private var lastThemeMode = ThemeMode.DEFAULT
    private var lastSnapshot: BatterySnapshot? = null

    /** 当前已渲染的字段集合，用于避免无谓的整卡重建 */
    private var currentFields: Set<OverlayField> = emptySet()

    /** 上次已上色的电量值，用于避免重复 setTextColor */
    private var lastLevel: Int? = null

    /** 拖动中暂缓内容刷新，见 [updateInternal] */
    private var dragging = false
    private var pendingSnapshot: BatterySnapshot? = null

    val isShowing: Boolean get() = rootView != null

    fun show(
        fields: Set<OverlayField>,
        alpha: Float,
        backgroundStyle: OverlayBackground,
        themeMode: ThemeMode
    ) = onMain { showInternal(fields, alpha, backgroundStyle, themeMode) }

    fun update(snapshot: BatterySnapshot) = onMain { updateInternal(snapshot) }

    fun hide() = onMain { hideInternal() }

    // ────────────────────────── 主线程实现 ──────────────────────────

    private fun showInternal(
        fields: Set<OverlayField>,
        alpha: Float,
        backgroundStyle: OverlayBackground,
        themeMode: ThemeMode
    ) {
        lastBackground = backgroundStyle
        lastThemeMode = themeMode

        if (rootView != null) {
            // 字段集合没变就不重建行：重建会让 WRAP_CONTENT 的悬浮窗整体重新测量/布局
            if (fields != currentFields) applyFields(fields)
            applyAppearance(alpha)
            return
        }

        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "show 跳过：未获得 SYSTEM_ALERT_WINDOW")
            return
        }

        cardBackground = GradientDrawable().apply { cornerRadius = dp(12).toFloat() }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground
            setPadding(dp(10), dp(6), dp(10), dp(8))
            elevation = dp(6).toFloat()
        }

        handleView = TextView(context).apply {
            text = "⋮⋮  BATTERY"
            textSize = 9f
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(3))
        }
        card.addView(handleView)

        rowsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }.also { card.addView(it) }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(140)
        }

        attachDrag(card, lp)

        try {
            windowManager.addView(card, lp)
            rootView = card
            layoutParams = lp
            applyFields(fields)
            applyAppearance(alpha)
            Log.i(TAG, "悬浮窗已添加，字段数=${valueViews.size}")
        } catch (t: Throwable) {
            Log.e(TAG, "addView 失败", t)
            rootView = null
            layoutParams = null
            rowsContainer = null
            cardBackground = null
        }
    }

    /** 重建可见行（主线程）。 */
    private fun applyFields(fields: Set<OverlayField>) {
        currentFields = fields
        lastLevel = null
        val container = rowsContainer ?: return
        container.removeAllViews()
        labelViews.clear()
        valueViews.clear()

        val ordered = OverlayField.entries.filter { it in fields }
        if (ordered.isEmpty()) {
            val empty = TextView(context).apply {
                text = "未选择字段"
                textSize = 11f
            }
            container.addView(empty)
            labelViews.add(empty)
            return
        }

        ordered.forEach { field ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

            val label = TextView(context).apply {
                text = field.label
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    dp(52), LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val value = TextView(context).apply {
                text = "--"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            row.addView(label)
            row.addView(value)
            container.addView(row)

            labelViews.add(label)
            valueViews[field] = value
        }
        // 新建的 TextView 没有颜色，重新套一遍配色
        applyAppearance(lastAlpha)
    }

    /** 套用 Material You 配色 + 背景不透明度。 */
    private fun applyAppearance(alpha: Float) {
        lastAlpha = alpha.coerceIn(SettingsStore.MIN_OVERLAY_ALPHA, 1f)
        val (background, onBackground) = backgroundPair(lastBackground)

        // 背景可透明，文字始终不透明，否则低不透明度下读不清
        cardBackground?.apply {
            setColor(background.withAlpha(lastAlpha))
            setStroke(dp(1), onBackground.withAlpha(lastAlpha * 0.25f))
        }

        val secondaryText = onBackground.copy(alpha = 0.78f)
        handleView?.setTextColor(secondaryText.toArgb())
        labelViews.forEach { it.setTextColor(secondaryText.toArgb()) }
        lastLevel = null // 配色变了，强制重新上色
        applyValueColors(lastSnapshot)
    }

    /**
     * 按所选角色取「背景色 + 配套前景色」。
     *
     * Material You 的 surface / surfaceContainer* 是中性色（深色下约 #2B2930，看着就是黑灰），
     * 只有 primary / secondary / tertiary 的 container 才真正携带壁纸色度。
     * 前景一律用对应的 on*Container，保证任意角色下对比度都达标。
     */
    private fun backgroundPair(style: OverlayBackground): Pair<Color, Color> {
        val scheme = materialYouScheme(context, resolveDarkTheme(context, lastThemeMode))
        return when (style) {
            OverlayBackground.SURFACE -> scheme.surfaceContainerHigh to scheme.onSurface
            OverlayBackground.PRIMARY -> scheme.primaryContainer to scheme.onPrimaryContainer
            OverlayBackground.SECONDARY -> scheme.secondaryContainer to scheme.onSecondaryContainer
            OverlayBackground.TERTIARY -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        }
    }

    /**
     * 数值文字配色。
     *
     * 注意分工：**Material You 只用于背景**；「电量」这一行仍按电量高低取绿/黄/红，
     * 因为它是整张卡片最需要一眼判断的信息，退化成单一主题色反而丢失了语义。
     * 其余数值用背景的配套前景色，保证在任意 Material You 角色上都可读。
     */
    private fun applyValueColors(snapshot: BatterySnapshot?) {
        val dark = resolveDarkTheme(context, lastThemeMode)
        val semantic = semanticColorsFor(dark)
        val (_, onBackground) = backgroundPair(lastBackground)

        valueViews.forEach { (field, view) ->
            val level = snapshot?.levelPercent
            val color = if (field == OverlayField.LEVEL && level != null) {
                when {
                    level <= 20 -> semantic.levelLow
                    level <= 50 -> semantic.levelMid
                    else -> semantic.levelHigh
                }
            } else {
                onBackground
            }
            view.setTextColor(color.toArgb())
        }
    }

    /** Compose Color → ARGB int，并整体乘上一个不透明度。 */
    private fun Color.withAlpha(alpha: Float): Int =
        copy(alpha = alpha.coerceIn(0f, 1f)).toArgb()

    private fun updateInternal(snapshot: BatterySnapshot) {
        if (rootView == null) return
        lastSnapshot = snapshot

        // 拖动期间不刷新内容：每次 setText 都会让这个 WRAP_CONTENT 悬浮窗重新测量/布局，
        // 与拖动的 updateViewLayout 抢主线程，表现就是「拖起来一卡一卡」。
        // 先记下来，等手指抬起再一次性补上。
        if (dragging) {
            pendingSnapshot = snapshot
            return
        }
        applySnapshot(snapshot)
    }

    /**
     * 刷新数值。
     *
     * 两处「没必要就别做」的判断是卡顿的关键解药：悬浮窗是 WRAP_CONTENT 窗口，
     * **任何一次 setText 都会触发窗口级 relayout**。内核数值大多时候没变化，
     * 却会以刷新间隔的频率反复触发，纯属浪费。
     */
    private fun applySnapshot(snapshot: BatterySnapshot) {
        valueViews.forEach { (field, view) ->
            val text = field.textOf(snapshot)
            if (view.text?.toString() != text) view.text = text
        }
        if (snapshot.levelPercent != lastLevel) {
            lastLevel = snapshot.levelPercent
            applyValueColors(snapshot)
        }
    }

    private fun hideInternal() {
        if (rootView == null) return
        rootView?.let { v -> runCatching { windowManager.removeView(v) } }
        rootView = null
        layoutParams = null
        rowsContainer = null
        cardBackground = null
        handleView = null
        labelViews.clear()
        valueViews.clear()
        lastSnapshot = null
        pendingSnapshot = null
        currentFields = emptySet()
        lastLevel = null
        dragging = false
        Log.i(TAG, "悬浮窗已移除")
    }

    // ────────────────────────── 工具 ──────────────────────────

    /** 确保在主线程执行：View / WindowManager 操作不允许在无 Looper 的线程上跑。 */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    /** 整张卡片可拖动；只处理 DOWN/MOVE，不消费 UP，避免影响系统手势。 */
    private fun attachDrag(target: View, lp: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0

        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val nx = startX + (event.rawX - downRawX).roundToInt()
                    val ny = startY + (event.rawY - downRawY).roundToInt()
                    // 位置没变就不发 IPC。触摸采样率可达 120Hz，无谓的 updateViewLayout
                    // 会把 WindowManagerService 刷满，这也是拖动掉帧的来源之一。
                    if (nx != lp.x || ny != lp.y) {
                        lp.x = nx
                        lp.y = ny
                        runCatching { windowManager.updateViewLayout(target, lp) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    pendingSnapshot?.let {
                        pendingSnapshot = null
                        applySnapshot(it)
                    }
                    false
                }

                else -> false
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "FloatingOverlay"
    }
}
