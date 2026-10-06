package com.caigit1.rootbattery

import android.content.Context
import android.graphics.Color
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
import kotlin.math.roundToInt

/**
 * 悬浮窗（在其他应用上层显示电池信息）。
 *
 * 用原生 View 而不是 Compose：悬浮窗跑在 Service 里，用 ComposeView 需要额外
 * 提供 ViewTreeLifecycleOwner / SavedStateRegistryOwner / ViewModelStoreOwner，
 * 对一个只读的紧凑信息卡片来说不划算，且更容易踩到生命周期相关的坑。
 *
 * **线程约束（重要）**：所有 View / WindowManager 操作都必须在主线程。
 * 之前把 addView 放在协程的 Dispatchers.Default 上，直接抛
 * `Can't create handler inside thread ... that has not called Looper.prepare()`，
 * 悬浮窗静默不显示。为避免调用方（Service 的轮询协程）再犯同样的错，
 * 这里在类内部统一把公开方法派发到主线程，调用方不需要关心线程。
 *
 * 权限：需要 SYSTEM_ALERT_WINDOW（「显示在其他应用上层」），
 * 由用户在系统设置里授予。
 */
class FloatingOverlay(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var rowsContainer: LinearLayout? = null
    private val valueViews = LinkedHashMap<OverlayField, TextView>()

    val isShowing: Boolean get() = rootView != null

    fun show(fields: Set<OverlayField>) = onMain { showInternal(fields) }

    fun update(snapshot: BatterySnapshot) = onMain { updateInternal(snapshot) }

    fun hide() = onMain { hideInternal() }

    // ────────────────────────── 主线程实现 ──────────────────────────

    private fun showInternal(fields: Set<OverlayField>) {
        if (rootView != null) {
            applyFields(fields)
            return
        }

        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "show 跳过：未获得 SYSTEM_ALERT_WINDOW")
            return
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(218, 18, 18, 22))
                setStroke(dp(1), Color.argb(70, 255, 255, 255))
            }
            setPadding(dp(10), dp(6), dp(10), dp(8))
            elevation = dp(6).toFloat()
        }

        // 顶部拖动提示条
        card.addView(
            TextView(context).apply {
                text = "⋮⋮  BATTERY"
                setTextColor(Color.argb(150, 255, 255, 255))
                textSize = 9f
                letterSpacing = 0.08f
                setPadding(0, 0, 0, dp(3))
            }
        )

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
            Log.i(TAG, "悬浮窗已添加，字段数=${valueViews.size}")
        } catch (t: Throwable) {
            Log.e(TAG, "addView 失败", t)
            rootView = null
            layoutParams = null
            rowsContainer = null
        }
    }

    /** 重建可见行。字段集合变化时调用（主线程）。 */
    private fun applyFields(fields: Set<OverlayField>) {
        val container = rowsContainer ?: return
        container.removeAllViews()
        valueViews.clear()

        val ordered = OverlayField.entries.filter { it in fields }
        if (ordered.isEmpty()) {
            container.addView(
                TextView(context).apply {
                    text = "未选择字段"
                    setTextColor(LABEL_COLOR)
                    textSize = 11f
                }
            )
            return
        }

        ordered.forEach { field ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                TextView(context).apply {
                    text = field.label
                    setTextColor(LABEL_COLOR)
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(
                        dp(52), LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
            )
            val value = TextView(context).apply {
                text = "--"
                setTextColor(VALUE_COLOR)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(value)
            container.addView(row)
            valueViews[field] = value
        }
    }

    private fun updateInternal(snapshot: BatterySnapshot) {
        if (rootView == null) return
        valueViews.forEach { (field, view) ->
            view.text = field.textOf(snapshot)
            if (field == OverlayField.LEVEL) {
                view.setTextColor(levelColor(snapshot.levelPercent))
            }
        }
    }

    private fun hideInternal() {
        if (rootView == null) return
        rootView?.let { v -> runCatching { windowManager.removeView(v) } }
        rootView = null
        layoutParams = null
        rowsContainer = null
        valueViews.clear()
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
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - downRawX).roundToInt()
                    lp.y = startY + (event.rawY - downRawY).roundToInt()
                    runCatching { windowManager.updateViewLayout(target, lp) }
                    true
                }

                else -> false
            }
        }
    }

    private fun levelColor(level: Int?): Int = when {
        level == null -> VALUE_COLOR
        level <= 20 -> Color.parseColor("#FF6B6B")
        level <= 50 -> Color.parseColor("#FFC857")
        else -> Color.parseColor("#7BE495")
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "FloatingOverlay"
        val LABEL_COLOR: Int = Color.argb(175, 220, 220, 230)
        val VALUE_COLOR: Int = Color.WHITE
    }
}
