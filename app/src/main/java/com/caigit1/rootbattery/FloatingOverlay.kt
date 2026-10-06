package com.caigit1.rootbattery

import android.content.Context
import android.content.Intent
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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮窗（在其他应用上层显示电池信息）。
 *
 * 用原生 View 而不是 Compose：悬浮窗跑在 Service 里，用 ComposeView 需要额外
 * 提供 ViewTreeLifecycleOwner / SavedStateRegistryOwner / ViewModelStoreOwner，
 * 对一个只读的紧凑信息卡片来说不划算，且更容易踩到生命周期相关的坑。
 *
 * **配色**：走 Material You。`dynamicDark/LightColorScheme` 是普通函数（非 @Composable），
 * 可以直接在这里调用，因此悬浮窗与主界面拿到的是完全一致的动态色板。
 *
 * **交互**：
 *  - 点按 / 拖动有缩放动画（松手用 OvershootInterpolator 回弹）
 *  - **双击**打开本应用的悬浮窗设置页
 *  - **勿扰模式**：加 `FLAG_NOT_TOUCHABLE`，触摸直接穿透到下层应用，不可拖动也不可唤起本应用
 *
 * **线程约束**：所有 View / WindowManager 操作都必须在主线程。之前把 addView 放在
 * 协程的 Dispatchers.Default 上，直接抛
 * `Can't create handler inside thread ... that has not called Looper.prepare()`，
 * 悬浮窗静默不显示。这里在类内部统一派发到主线程。
 */
class FloatingOverlay(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

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
    private var lastLocked = false

    private var currentFields: Set<OverlayField> = emptySet()
    private var lastLevel: Int? = null

    private var dragging = false
    private var pendingSnapshot: BatterySnapshot? = null

    /** 手势判定用的临时状态 */
    private var downAtMs = 0L
    private var movedBeyondSlop = false
    private var lastTapAtMs = 0L

    val isShowing: Boolean get() = rootView != null

    fun show(
        fields: Set<OverlayField>,
        alpha: Float,
        backgroundStyle: OverlayBackground,
        themeMode: ThemeMode,
        locked: Boolean
    ) = onMain { showInternal(fields, alpha, backgroundStyle, themeMode, locked) }

    fun update(snapshot: BatterySnapshot) = onMain { updateInternal(snapshot) }

    fun hide() = onMain { hideInternal() }

    // ────────────────────────── 主线程实现 ──────────────────────────

    private fun showInternal(
        fields: Set<OverlayField>,
        alpha: Float,
        backgroundStyle: OverlayBackground,
        themeMode: ThemeMode,
        locked: Boolean
    ) {
        lastBackground = backgroundStyle
        lastThemeMode = themeMode

        if (rootView != null) {
            // 字段集合没变就不重建行：重建会让 WRAP_CONTENT 的悬浮窗整体重新测量/布局
            if (fields != currentFields) applyFields(fields)
            applyAppearance(alpha)
            applyLockState(locked)
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

        attachGestures(card, lp)

        try {
            windowManager.addView(card, lp)
            rootView = card
            layoutParams = lp
            applyFields(fields)
            applyAppearance(alpha)
            applyLockState(locked)
            Log.i(TAG, "悬浮窗已添加，字段数=${valueViews.size} locked=$locked")
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
     * 勿扰（锁定）模式。
     *
     * 加 `FLAG_NOT_TOUCHABLE` 后触摸事件直接穿透到下层应用 —— 既不能拖动、
     * 也不会收到双击，等同把悬浮窗变成纯展示。用窗口标志而不是"忽略事件"，
     * 连一次输入分发都不会走到这里。
     */
    private fun applyLockState(locked: Boolean) {
        lastLocked = locked
        handleView?.text = if (locked) "🔒  BATTERY" else "⋮⋮  BATTERY"

        val view = rootView ?: return
        val lp = layoutParams ?: return

        val currentlyNotTouchable =
            (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        if (currentlyNotTouchable == locked) return

        lp.flags = if (locked) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { windowManager.updateViewLayout(view, lp) }
            .onFailure { Log.w(TAG, "切换锁定状态失败", it) }
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

    private fun updateInternal(snapshot: BatterySnapshot) {
        if (rootView == null) return
        lastSnapshot = snapshot

        // 拖动期间不刷新内容：每次 setText 都会让这个 WRAP_CONTENT 悬浮窗重新测量/布局，
        // 与拖动的 updateViewLayout 抢主线程，表现就是「拖起来一卡一卡」。
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
     * **任何一次 setText 都会触发窗口级 relayout**。
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
        lastTapAtMs = 0L
        Log.i(TAG, "悬浮窗已移除")
    }

    // ────────────────────────── 手势与动画 ──────────────────────────

    /**
     * 触摸处理：拖动 + 点按反馈 + 双击。
     *
     * 不用 GestureDetector：它的滚动/双击判定会和自己实现的 updateViewLayout 拖动打架，
     * 手写状态机反而更可控（我们只需要区分「点」和「拖」两种意图）。
     */
    private fun attachGestures(target: View, lp: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0

        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    movedBeyondSlop = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    downAtMs = System.currentTimeMillis()
                    animateScale(PRESS_SCALE, PRESS_MS, DecelerateInterpolator())
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val nx = startX + (event.rawX - downRawX).roundToInt()
                    val ny = startY + (event.rawY - downRawY).roundToInt()

                    if (!movedBeyondSlop &&
                        (abs(event.rawX - downRawX) > touchSlop ||
                            abs(event.rawY - downRawY) > touchSlop)
                    ) {
                        movedBeyondSlop = true
                        // 进入拖动：从"按下去"的深度回一点，形成"拿起来"的手感
                        animateScale(DRAG_SCALE, DRAG_MS, DecelerateInterpolator())
                    }

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
                    // 松手回弹：Overshoot 让卡片弹一下再落定，即"灵动"的来源
                    animateScale(1f, RELEASE_MS, OvershootInterpolator(2.4f))

                    if (event.actionMasked == MotionEvent.ACTION_UP && !movedBeyondSlop) {
                        onTapResolved()
                    }

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

    /** 抬手时判定单击 / 双击。只有"点"才算，拖动过的不参与。 */
    private fun onTapResolved() {
        val now = System.currentTimeMillis()
        if (now - lastTapAtMs <= DOUBLE_TAP_WINDOW_MS) {
            lastTapAtMs = 0L
            openOverlaySettings()
        } else {
            lastTapAtMs = now
        }
    }

    /**
     * 双击 → 打开本应用的悬浮窗设置页。
     *
     * 从后台启动 Activity 在 Android 10+ 默认受限，但已获得 SYSTEM_ALERT_WINDOW
     * 的应用属于豁免情形，因此这里可以直接 startActivity。
     */
    private fun openOverlaySettings() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OPEN_OVERLAY_SETTINGS, true)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "打开应用悬浮窗设置页失败", it) }
    }

    private fun animateScale(scale: Float, durationMs: Long, interpolator: android.view.animation.Interpolator) {
        rootView?.animate()
            ?.scaleX(scale)?.scaleY(scale)
            ?.setDuration(durationMs)
            ?.setInterpolator(interpolator)
            ?.start()
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

    /** Compose Color → ARGB int，并整体乘上一个不透明度。 */
    private fun Color.withAlpha(alpha: Float): Int =
        copy(alpha = alpha.coerceIn(0f, 1f)).toArgb()

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "FloatingOverlay"

        /** 按下时缩到多小 */
        const val PRESS_SCALE = 0.93f
        const val PRESS_MS = 90L

        /** 拖动中略微缩小（比按下时浅一点，形成"拿起来"的手感） */
        const val DRAG_SCALE = 0.96f
        const val DRAG_MS = 120L

        /** 松手回弹时长；配合 OvershootInterpolator 弹出余韵 */
        const val RELEASE_MS = 260L

        const val DOUBLE_TAP_WINDOW_MS = 300L
    }
}
