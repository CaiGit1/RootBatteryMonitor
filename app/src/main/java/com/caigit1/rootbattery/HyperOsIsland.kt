package com.caigit1.rootbattery

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * 小米超级岛（HyperOS 焦点通知）能力探测。
 *
 * 三个接口均按官方《开发指南》原样实现
 * （dev.mi.com/xiaomihyperos/documentation/detail?pId=2131 第五节「查询接口」）：
 *
 *  1. 当前 OS 是否支持岛功能         → 反射读 `persist.sys.feature.island`
 *  2. 当前 OS 的焦点通知协议版本     → 读系统设置 `notification_focus_protocol`
 *                                    （1=OS1，2=OS2，3=OS3 且支持小米超级岛）
 *  3. 当前应用是否已获焦点通知权限   → 调用 `content://miui.statusbar.notification.public`
 *                                    的 `canShowFocus`
 *
 * 第 3 项必须由应用自己调用：该 provider 会校验**调用方 uid 是否拥有传入的包名**，
 * 因此 adb / root 调用一律被拒（实测报 `Package X is not owned by uid 0`）。
 *
 * 这些查询不是可有可无的装饰：小米超级岛是白名单授权制（企业认证 + 方案提报 + 按年续期），
 * 应用必须先问清楚自己有没有权限，再决定是发原生岛通知还是退回普通通知。
 */
object HyperOsIsland {

    private const val TAG = "HyperOsIsland"

    private const val KEY_FEATURE_ISLAND = "persist.sys.feature.island"
    private const val KEY_FOCUS_PROTOCOL = "notification_focus_protocol"
    private const val AUTHORITY = "content://miui.statusbar.notification.public"

    /** 官方指南里发布岛通知用的两个 extras key */
    const val EXTRA_FOCUS_PARAM = "miui.focus.param"
    const val EXTRA_FOCUS_PICS = "miui.focus.pics"

    /** `notification_focus_protocol` 取值：OS3 起支持小米超级岛 */
    const val PROTOCOL_OS3 = 3

    /** 当前 OS 是否支持岛功能（读 `persist.sys.feature.island`） */
    fun isIslandSupported(): Boolean = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getDeclaredMethod(
            "getBoolean", String::class.java, Boolean::class.javaPrimitiveType
        )
        (method.invoke(null, KEY_FEATURE_ISLAND, false) as? Boolean) ?: false
    }.getOrDefault(false)

    /** `notification_focus_protocol` 的展示名 */
    fun protocolLabel(protocol: Int): String = protocolName(protocol)

    /** 岛参数里引用的图片 key；实机图片通过 `miui.focus.pics` 这个 Bundle 传 */
    private const val PIC_BATTERY = "miui.focus.pic_battery"

    fun picKey(): String = PIC_BATTERY

    /**
     * 构造 `miui.focus.param` 的 JSON。
     *
     * 字段结构照抄官方《开发指南》第四节「模板接入示例」，只把示例里的打车内容
     * 换成电池数据：
     *  - `bigIslandArea.imageTextInfoLeft.textInfo` 是**大岛左区**（图 + 文字），
     *    正是实况通知自己渲染不到、一直空着的那一段
     *  - `smallIslandArea` 是收起后的小岛
     *  - `baseInfo` 是焦点通知卡片（锁屏 / 通知中心）
     *
     * ⚠️ 官方示例里这个键印成了 `miui.focus.paramtextInfo`，明显是文档把上一个
     * 字符串常量粘进了键名。按周围键的命名风格（`picInfo` / `frontTitle`）判断
     * 真实键名应为 `textInfo`，故此处用 `textInfo`。
     */
    fun buildParams(powerText: String, tempText: String, levelText: String): String {
        val picInfo = JSONObject().apply {
            put("type", 1)
            put("pic", PIC_BATTERY)
        }
        // 左区放功率、右区放温度，两侧都只用**纯数值**：
        //  - 去掉 frontTitle（原先的「功率」「温度」汉字）：岛是按内容宽度撑开的，
        //    多两个字标签就白占一截宽度，而图标本身已经说明这是电池信息；
        //  - 两侧格式统一为「数值+单位」，读起来是并列关系而不是主从关系。
        //
        // 关于岛宽度：实测改不动。已逐一试过「缩短内容」「只填左区」「把
        // islandFirstFloat/enableFloat 都关掉」，胶囊始终是同一个宽度 ——
        // 那是 SystemUI 大岛的固定尺寸，不是按内容算的。
        val bigIslandArea = JSONObject().apply {
            put(
                "imageTextInfoLeft",
                JSONObject().apply {
                    put("type", 1)
                    put("picInfo", picInfo)
                    put(
                        "textInfo",
                        JSONObject().apply {
                            put("title", powerText)
                            put("useHighLight", false)
                        }
                    )
                }
            )
            put(
                "imageTextInfoRight",
                JSONObject().apply {
                    put("type", 2)
                    put(
                        "textInfo",
                        JSONObject().apply {
                            put("title", tempText)
                            put("useHighLight", false)
                        }
                    )
                }
            )
            put("picInfo", picInfo)
        }

        val paramIsland = JSONObject().apply {
            put("islandProperty", 1)
            put("bigIslandArea", bigIslandArea)
            // 摘要态（收起后的小岛）。实测带上 textInfo 也能显示文字，
            // 所以收起态同样能看到功率，不必为了窄而牺牲信息。
            put(
                "smallIslandArea",
                JSONObject().apply {
                    put("picInfo", picInfo)
                    put(
                        "textInfo",
                        JSONObject().apply {
                            put("title", powerText)
                            put("useHighLight", false)
                        }
                    )
                }
            )
        }

        val paramV2 = JSONObject().apply {
            put("protocol", 1)
            put("business", "batterymonitor")
            put("updatable", true)
            // enableFloat=false：**更新时不要自动展开**。
            // 这个应用每秒都在刷新，若每次更新都把岛弹开，等于每秒抢一次注意力。
            // 首次出现仍保留默认的自动展开（islandFirstFloat 默认 true），
            // 否则用户根本看不到功率与温度。
            put("enableFloat", false)
            put("param_island", paramIsland)
            put("baseInfo", JSONObject().apply {
                put("title", "电池")
                put("content", "$powerText  ·  $tempText  ·  $levelText")
                put("type", 2)
            })
        }

        return JSONObject().apply { put("param_v2", paramV2) }.toString()
    }

    /** 焦点通知协议版本；1=OS1，2=OS2，3=OS3（支持超级岛） */
    fun focusProtocolVersion(context: Context): Int = runCatching {
        Settings.System.getInt(context.contentResolver, KEY_FOCUS_PROTOCOL, 0)
    }.getOrDefault(0)

    /**
     * 当前应用是否已获焦点通知权限。
     *
     * 这是判断「能不能上岛」的唯一权威口径 —— 它由 SystemUI 侧的 provider 回答，
     * 而不是我们猜。未获授权时小米会把岛参数直接忽略，退回普通通知。
     */
    fun hasFocusPermission(context: Context): Boolean = runCatching {
        val extras = Bundle().apply { putString("package", context.packageName) }
        val result = context.contentResolver.call(
            Uri.parse(AUTHORITY), "canShowFocus", null, extras
        )
        result?.getBoolean("canShowFocus", false) ?: false
    }.getOrDefault(false)

    /**
     * 把三项能力查询结果打一条日志。
     * 排查「岛为什么没上」时，这一条就能区分是系统不支持、还是本应用没被授权。
     */
    fun logCapabilities(context: Context) {
        probe(context)
    }

    data class Capabilities(
        val islandSupported: Boolean,
        val protocolVersion: Int,
        val focusGranted: Boolean,
        val rom: RomInfo
    ) {
        /** 可以走原生岛载荷：系统支持岛 + OS3 协议 + 本应用已获焦点通知授权 */
        val canPostIsland: Boolean
            get() = islandSupported && protocolVersion >= PROTOCOL_OS3 && focusGranted
    }

    /** 实际采用哪条呈现路径 */
    enum class EffectiveMode { XIAOMI_ISLAND, AOSP_LIVE_UPDATE }

    /**
     * 依据用户选择与设备能力，决定这一次到底走哪条路。
     *
     * 注意「强制小米」在设备不支持时也会退回 AOSP —— 硬发一个系统不认的载荷
     * 只会得到一个既不显示岛、又丢掉了实况通知的通知，比退回更差。
     */
    fun resolveMode(mode: IslandMode, caps: Capabilities): EffectiveMode = when (mode) {
        IslandMode.AOSP -> EffectiveMode.AOSP_LIVE_UPDATE
        IslandMode.XIAOMI,
        IslandMode.AUTO -> if (caps.canPostIsland) {
            EffectiveMode.XIAOMI_ISLAND
        } else {
            EffectiveMode.AOSP_LIVE_UPDATE
        }
    }

    /**
     * 一次问齐三项能力并打日志。
     *
     * 服务侧务必**缓存**这个结果：`hasFocusPermission` 是一次跨进程调用，
     * 在 0.2s 刷新档位下每拍都问等于每秒 5 次 IPC，纯属浪费。
     */
    fun probe(context: Context): Capabilities {
        val caps = Capabilities(
            islandSupported = isIslandSupported(),
            protocolVersion = focusProtocolVersion(context),
            focusGranted = hasFocusPermission(context),
            rom = detectRom()
        )
        Log.i(
            TAG,
            "超级岛能力: ROM=${caps.rom.label}(${if (caps.rom.isXiaomi) "小米系" else "非小米"})" +
                "  系统支持=${caps.islandSupported}" +
                "  协议版本=${caps.protocolVersion}(${protocolName(caps.protocolVersion)})" +
                "  本应用已授权焦点通知=${caps.focusGranted}" +
                "  可用原生岛载荷=${caps.canPostIsland}"
        )
        return caps
    }

    // ────────────────────────── ROM 检测 ──────────────────────────

    /**
     * 设备 ROM 信息。
     *
     * @param isXiaomi 是否为小米系 ROM（MIUI / HyperOS）
     * @param label    给用户看的名称，如 `HyperOS 4.0 · OS3.0.304.0.WMKCNXM`
     */
    data class RomInfo(val isXiaomi: Boolean, val label: String)

    /**
     * 检测 ROM。
     *
     * 用系统属性而不是 `Build.MANUFACTURER`：小米系 ROM 也被第三方移植到过别家机型上，
     * 而能力取决于 **ROM** 而不是硬件品牌。`ro.mi.os.version.name`（HyperOS）与
     * `ro.miui.ui.version.name`（MIUI）任一非空即判为小米系。
     *
     * 注意这只是**给用户看的辅助信息**，真正的判定仍以 [Capabilities.canPostIsland]
     * 那个三项查询为准 —— ROM 名字对不上但系统能力齐全的情况是存在的。
     */
    fun detectRom(): RomInfo {
        val hyperOs = systemProperty("ro.mi.os.version.name")
        val miui = systemProperty("ro.miui.ui.version.name")
        val incremental = systemProperty("ro.build.version.incremental")
        val isXiaomi = hyperOs.isNotEmpty() || miui.isNotEmpty()

        val label = buildString {
            when {
                hyperOs.isNotEmpty() -> append("HyperOS ").append(hyperOs)
                miui.isNotEmpty() -> append("MIUI ").append(miui)
                else -> append(android.os.Build.BRAND).append(' ').append(android.os.Build.MODEL)
            }
            if (incremental.isNotEmpty()) append(" · ").append(incremental)
        }
        return RomInfo(isXiaomi, label)
    }

    private fun systemProperty(key: String): String = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getDeclaredMethod("get", String::class.java)
        (method.invoke(null, key) as? String)?.trim().orEmpty()
    }.getOrDefault("")

    private fun protocolName(protocol: Int): String = when (protocol) {
        1 -> "OS1"
        2 -> "OS2"
        3 -> "OS3"
        else -> "未知"
    }
}
