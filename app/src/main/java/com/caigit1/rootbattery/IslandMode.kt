package com.caigit1.rootbattery

/**
 * 实况通知 / 超级岛的呈现方式。
 *
 * 为什么要给用户选：同一份通知在两套系统上的最佳形态不一样 ——
 *  - 小米 HyperOS 走原生岛载荷，**岛左右两区都由开发者控制**，还能填满整条岛；
 *  - 类原生 AOSP（Pixel 等）没有岛，只有 Android 16 的实况通知（Live Updates），
 *    状态栏胶囊显示的是「短关键文本」。
 *
 * 两条路**互斥**：HyperOS 上若同时发实况通知，系统会用自己的转换逻辑覆盖岛内容
 * （把短关键文本塞进右区、左区留空，并忽略 miui.focus.param），所以必须二选一。
 */
enum class IslandMode(val label: String, val hint: String) {

    /** 按设备 ROM 与系统能力自动选 */
    AUTO(
        label = "自动",
        hint = "按设备判断：小米设备走超级岛原生载荷，其他设备走 AOSP 实况通知"
    ),

    /** 强制小米超级岛原生载荷 */
    XIAOMI(
        label = "小米超级岛",
        hint = "用 miui.focus.param，岛左右两区都可控；设备不支持时自动退回 AOSP"
    ),

    /** 强制 AOSP 实况通知 */
    AOSP(
        label = "类原生 AOSP",
        hint = "用 Android 16 实况通知（Live Updates），状态栏胶囊显示短关键文本"
    );

    companion object {
        val DEFAULT = AUTO

        fun fromName(raw: String?): IslandMode =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
