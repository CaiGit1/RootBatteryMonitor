package com.caigit1.rootbattery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 双击悬浮窗唤起时置位；界面消费掉后复位。 */
    private val openOverlaySettings = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15（targetSdk 35）已强制 edge-to-edge。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        openOverlaySettings.value =
            intent?.getBooleanExtra(EXTRA_OPEN_OVERLAY_SETTINGS, false) == true

        requestNotificationPermissionIfNeeded()
        setContent {
            val vm: BatteryMonitorViewModel = viewModel(factory = BatteryMonitorViewModel.Factory)
            val ui by vm.uiState.collectAsState()

            // 状态栏/导航栏图标必须跟随**应用**的明暗设置，而不是系统设置。
            // 若强制浅色时仍按系统（深色）取图标色，就会出现亮色顶栏配浅色图标、完全看不见。
            val darkTheme = resolveDarkTheme(LocalContext.current, ui.themeMode)
            LaunchedEffect(darkTheme) {
                val barStyle = if (darkTheme) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    )
                }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
            }

            RootBatteryMonitorTheme(themeMode = ui.themeMode) {
                AppRoot(
                    vm = vm,
                    ui = ui,
                    openOverlaySettings = openOverlaySettings.value,
                    onOpenOverlaySettingsHandled = { openOverlaySettings.value = false }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 应用已在后台时再次双击悬浮窗：走这里而不是 onCreate
        if (intent.getBooleanExtra(EXTRA_OPEN_OVERLAY_SETTINGS, false)) {
            openOverlaySettings.value = true
        }
    }

    /**
     * Android 13 (API 33) 起通知是运行时权限。
     * 未授权不会让 startForeground 崩溃，但前台服务的常驻通知不会显示。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        /** 双击悬浮窗时带过来的标记：进入应用后直接跳到悬浮窗设置。 */
        const val EXTRA_OPEN_OVERLAY_SETTINGS = "open_overlay_settings"
    }
}

private val PAGE_TITLES = listOf("概览", "详情", "曲线", "设置")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AppRoot(
    vm: BatteryMonitorViewModel,
    ui: BatteryMonitorUiState,
    openOverlaySettings: Boolean,
    onOpenOverlaySettingsHandled: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { PAGE_TITLES.size })

    // 从系统「显示在其他应用上层」设置页返回后重新校验权限
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refreshOverlayPermission() }

    // 「关于」是设置页下的子页面：用局部状态做钻取即可，不必为此引入导航库
    var aboutVisible by remember { mutableStateOf(false) }

    // 双击悬浮窗唤起时，除了切到设置页，还要滚到悬浮窗外观那一张卡
    var scrollSettingsToOverlay by remember { mutableStateOf(false) }

    LaunchedEffect(openOverlaySettings) {
        if (openOverlaySettings) {
            aboutVisible = false
            scrollSettingsToOverlay = true
            pagerState.animateScrollToPage(PAGE_TITLES.lastIndex)
            onOpenOverlaySettingsHandled()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (aboutVisible) "关于" else "Root Battery Monitor") },
                navigationIcon = {
                    if (aboutVisible) {
                        IconButton(onClick = { aboutVisible = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (!aboutVisible) {
                        Text(
                            formatInterval(ui.intervalMs),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        IconButton(onClick = vm::refreshNow) {
                            Icon(Icons.Filled.Refresh, contentDescription = "立即刷新")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                PAGE_TITLES.forEachIndexed { index, title ->
                    val tabSelected = !aboutVisible && pagerState.currentPage == index
                    NavigationBarItem(
                        selected = tabSelected,
                        // 处于子页面时点任意标签都先退回主分页
                        onClick = {
                            aboutVisible = false
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        icon = { TabIcon(index, tabSelected) },
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { padding ->
        if (aboutVisible) {
            AboutPage(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) { page ->
                when (page) {
                    0 -> OverviewPage(
                        ui = ui,
                        onRefresh = vm::refreshNow,
                        onOpenDetails = { scope.launch { pagerState.animateScrollToPage(1) } }
                    )
                    1 -> DetailsPage(ui, onSelfCheck = vm::runSelfCheck)
                    2 -> ChartsPage(ui)
                    else -> SettingsPage(
                        ui = ui,
                        onIntervalChange = vm::setIntervalMs,
                        onAlertsChange = vm::setAlertsEnabled,
                        onNotificationChange = vm::setNotificationEnabled,
                        onLiveUpdateChange = vm::setLiveUpdateEnabled,
                        onIslandModeChange = vm::setIslandMode,
                        onOverlayChange = vm::setOverlayEnabled,
                        onOverlayFieldChange = vm::setOverlayField,
                        onOverlayAlphaChange = vm::setOverlayAlpha,
                        onOverlayBackgroundChange = vm::setOverlayBackground,
                        onOverlayLockedChange = vm::setOverlayLocked,
                        onThemeModeChange = vm::setThemeMode,
                        scrollToOverlay = scrollSettingsToOverlay,
                        onOverlayScrollHandled = { scrollSettingsToOverlay = false },
                        onSelfCheck = vm::runSelfCheck,
                        onOpenAbout = { aboutVisible = true },
                        onOpenOverlaySettings = {
                            overlayPermissionLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TabIcon(index: Int, selected: Boolean) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    when (index) {
        0 -> Icon(Icons.Filled.Home, contentDescription = null, tint = tint)
        1 -> Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = tint)
        2 -> TrendTabIcon(tint)
        else -> Icon(Icons.Filled.Settings, contentDescription = null, tint = tint)
    }
}

/** 曲线页图标：core 图标集里没有合适的，直接画一条折线。 */
@Composable
private fun TrendTabIcon(tint: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val pts = listOf(0.08f to 0.72f, 0.34f to 0.42f, 0.56f to 0.62f, 0.92f to 0.18f)
        for (i in 0 until pts.lastIndex) {
            drawLine(
                color = tint,
                start = Offset(pts[i].first * size.width, pts[i].second * size.height),
                end = Offset(pts[i + 1].first * size.width, pts[i + 1].second * size.height),
                strokeWidth = 2.6f * density,
                cap = StrokeCap.Round
            )
        }
    }
}

// ══════════════════════════ 概览 ══════════════════════════

@Composable
private fun OverviewPage(
    ui: BatteryMonitorUiState,
    onRefresh: () -> Unit,
    onOpenDetails: () -> Unit
) {
    val snap = ui.latest
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { RootStatusRow(ui.rootReady) }

        ui.error?.let { err ->
            item { ErrorBanner(err.message) }
        }

        item { LevelCard(snap) }

        item {
            // 两列小卡片：简单易读，需要细节时切到「详情」页
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("温度", snap?.temperatureText ?: "--", Modifier.weight(1f), onOpenDetails)
                    StatTile("电压", snap?.voltageText ?: "--", Modifier.weight(1f), onOpenDetails)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("电流", snap?.currentText ?: "--", Modifier.weight(1f), onOpenDetails)
                    StatTile("功率", snap?.computedPowerText ?: "--", Modifier.weight(1f), onOpenDetails)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("健康度", snap?.healthPercentText ?: "--", Modifier.weight(1f), onOpenDetails)
                    StatTile("循环次数", snap?.cycleCount?.toString() ?: "--", Modifier.weight(1f), onOpenDetails)
                }
            }
        }

        item {
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("状态", style = MaterialTheme.typography.titleSmall)
                    MetricRow("充电状态", snap?.status ?: "--")
                    MetricRow("充电类型", snap?.chargeType ?: "--")
                    MetricRow("充电协议", snap?.charger?.usbTypeShort ?: "--")
                    MetricRow("供电节点", snap?.charger?.node ?: "--")
                    MetricRow("供电电压/电流", snap?.charger?.let { "${it.voltageText} / ${it.currentText}" } ?: "--")
                    MetricRowStacked("数据源", snap?.sourcePath ?: "--")
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRefresh, modifier = Modifier.weight(1f)) { Text("立即刷新") }
                Text(
                    text = "左右滑动切换页面",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.CenterVertically)
                )
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun RootStatusRow(rootReady: Boolean) {
    val semantic = LocalSemanticColors.current
    val color = if (rootReady) semantic.ok else semantic.error
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Text(
            if (rootReady) "Root 可用" else "Root 未就绪",
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun LevelCard(snapshot: BatterySnapshot?) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    snapshot?.levelPercent?.let { "$it" } ?: "--",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    snapshot?.status ?: "--",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            snapshot?.levelPercent?.let {
                LinearProgressIndicator(
                    progress = { it / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            }
            Text(
                "电量 / 状态" + (snapshot?.chargeType?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    AppCard(modifier = modifier, onClick = onClick) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

// ══════════════════════════ 详情 ══════════════════════════

@Composable
private fun DetailsPage(ui: BatteryMonitorUiState, onSelfCheck: () -> Unit) {
    val snap = ui.latest
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("环境自检", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onSelfCheck) { Text("重新检测") }
            }
        }

        item { SelfCheckCard(ui.selfChecks) }

        item {
            ExpandableSection("电压 / 电流 / 功率", initiallyExpanded = true) {
                MetricRow("电压", snap?.voltageText ?: "--")
                MetricRow("开路电压 OCV", snap?.ocvText ?: "--")
                MetricRow("满电电压上限", snap?.voltageMaxText ?: "--")
                MetricRow("电流", snap?.currentText ?: "--")
                MetricRow("功率", snap?.computedPowerText ?: "--")
                Hint(
                    "电流与功率保留内核原始符号，正负表示方向（本机为「负 = 充电」，各 ROM 约定相反）。\n" +
                        "功率由「电压 × 电流」实时计算。内核上报的 POWER_NOW / POWER_AVG 在本机恒为 " +
                        "10000 / 5000 的固定占位值，与实际相差百倍，故不予采用；" +
                        "其原始值仍可在下方「原始 uevent」中查看。"
                )
            }
        }

        item {
            ExpandableSection("容量与寿命") {
                MetricRow("满电容量", snap?.chargeFullText ?: "--")
                MetricRow("设计容量", snap?.chargeFullDesignText ?: "--")
                MetricRow("健康度", snap?.healthPercentText ?: "--")
                MetricRow("循环次数", snap?.cycleCount?.toString() ?: "--")
                MetricRow("电荷计数", snap?.chargeCounterText ?: "--")
            }
        }

        item {
            ExpandableSection("充电器（供电侧节点）") {
                val c = snap?.charger
                MetricRowStacked("节点", c?.node ?: "--")
                MetricRow("在线", c?.onlineText ?: "--")
                MetricRow("类型", c?.type ?: "--")
                MetricRowStacked("充电协议", c?.protocol?.activeText ?: "--")
                MetricRow("输入电压", c?.voltageText ?: "--")
                MetricRow("输入电流", c?.currentText ?: "--")
                MetricRow("电流上限", c?.currentMaxText ?: "--")
                MetricRow("输入限流", c?.inputLimitText ?: "--")
                MetricRow("充电器温度", c?.temperatureText ?: "--")
            }
        }

        item {
            ExpandableSection("充电控制") {
                MetricRow("恒流充电", snap?.constantChargeCurrentText ?: "--")
                MetricRow("限流档位", snap?.chargeControlLimitText ?: "--")
            }
        }

        item {
            ExpandableSection("时间估算") {
                MetricRow("预计充满", snap?.timeToFullText ?: "--")
                MetricRow("预计耗尽", snap?.timeToEmptyText ?: "--")
                Hint("内核用 -1 / 0xFFFF(65535) 表示未知，已统一显示为 --。")
            }
        }

        item {
            ExpandableSection("电池身份") {
                MetricRow("节点名", snap?.name ?: "--")
                MetricRow("类型", snap?.type ?: "--")
                MetricRow("技术", snap?.technology ?: "--")
                MetricRow("型号", snap?.modelName ?: "--")
                MetricRow("在位", snap?.presentText ?: "--")
                MetricRowStacked("数据源", snap?.sourcePath ?: "--")
            }
        }

        item { RawUeventCard(snap?.raw ?: emptyMap()) }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

/**
 * 可展开分组。
 *
 * **整张卡片**都可点（之前只有右侧那几像素文字可点，触控目标太小、也不明显），
 * 并配一个会旋转的箭头 + 水波纹反馈。
 *
 * 展开过程不是"瞬间出现"：用 [AnimatedVisibility] 做 spring 垂直展开 + 淡入，
 * 收起则用阻尼更大的 spring 快速收拢（展开要"弹"，收起要"利落"）。
 */
@Composable
private fun ExpandableSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    AppCard(onClick = { expanded = !expanded }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            ExpandIndicator(expanded)
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeIn(animationSpec = tween(durationMillis = 160)),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + fadeOut(animationSpec = tween(durationMillis = 110))
        ) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content
            )
        }
    }
}

@Composable
private fun SelfCheckCard(checks: List<SelfCheckItem>) {
    val semantic = LocalSemanticColors.current
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (checks.isEmpty()) {
                Text("尚未检测", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            checks.forEach { check ->
                Text(
                    "• ${check.title}：${if (check.ok) "通过" else "失败"}",
                    color = if (check.ok) semantic.ok else semantic.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RawUeventCard(raw: Map<String, String>) {
    var expanded by remember { mutableStateOf(false) }

    AppCard(onClick = { expanded = !expanded }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "原始 uevent（${raw.size} 项）",
                style = MaterialTheme.typography.titleSmall
            )
            ExpandIndicator(expanded)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeIn(animationSpec = tween(durationMillis = 160)),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + fadeOut(animationSpec = tween(durationMillis = 110))
        ) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                raw.toSortedMap().forEach { (key, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            key.removePrefix("POWER_SUPPLY_"),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════ 曲线 ══════════════════════════

@Composable
private fun ChartsPage(ui: BatteryMonitorUiState) {
    val semantic = LocalSemanticColors.current
    val points = ui.history
    val spanText = windowLabel(points)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("采样窗口", style = MaterialTheme.typography.titleSmall)
                    Text(spanText, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "间隔 ${formatInterval(ui.intervalMs)} · 最多保留 600 个点",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            MetricChart(
                title = "温度",
                unit = "°C",
                values = points.map { it.temperatureCelsius?.toFloat() },
                color = semantic.chartTemperature
            )
        }
        item {
            MetricChart(
                title = "电压",
                unit = "mV",
                values = points.map { it.voltageMv?.toFloat() },
                color = semantic.chartVoltage
            )
        }
        item {
            MetricChart(
                title = "电流",
                unit = "mA",
                values = points.map { it.currentMa?.toFloat() },
                color = semantic.chartCurrent
            )
        }
        item {
            MetricChart(
                title = "功率",
                unit = "mW",
                values = points.map { it.powerMw?.toFloat() },
                color = semantic.chartPower
            )
        }

        item {
            val first = points.firstOrNull()
            val last = points.lastOrNull()
            if (first != null && last != null && points.size >= 2) {
                Card {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("本窗口极值", style = MaterialTheme.typography.titleSmall)
                        val temps = points.mapNotNull { it.temperatureCelsius?.toFloat() }
                        val volts = points.mapNotNull { it.voltageMv?.toFloat() }
                        if (temps.isNotEmpty()) {
                            MetricRow(
                                "温度 最低/最高",
                                "%.1f / %.1f °C".format(temps.min(), temps.max())
                            )
                        }
                        if (volts.isNotEmpty()) {
                            MetricRow(
                                "电压 最低/最高",
                                "${volts.min().roundToInt()} / ${volts.max().roundToInt()} mV"
                            )
                        }
                        MetricRow("电量变化", "${first.levelPercent ?: 0}% → ${last.levelPercent ?: 0}%")
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

/**
 * 单指标折线图。
 *
 * 精细度处理：
 *  - 纵轴 5 档网格 + 明确单位标注（不只画一条线）
 *  - 上下各留 8% 余量，曲线不贴边
 *  - 用 StrokeCap.Round 抗锯齿连接，点数多时仍保持可读
 *  - null 值断开而非补 0（避免把"读不到"画成 0 造成误判）
 */
@Composable
private fun MetricChart(
    title: String,
    unit: String,
    values: List<Float?>,
    color: Color
) {
    val present = values.filterNotNull()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    present.lastOrNull()?.let { "${fmtValue(it)}$unit" } ?: "--",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))

            if (present.size < 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("样本不足（至少需要 2 个有效点）", style = MaterialTheme.typography.bodySmall)
                }
                return@Column
            }

            val minV = present.min()
            val maxV = present.max()
            val span = (maxV - minV).takeIf { it > 0f } ?: 1f
            val lo = minV - span * 0.08f
            val hi = maxV + span * 0.08f
            val range = (hi - lo).takeIf { it > 0f } ?: 1f
            val divisions = 4
            // 绘制前压缩点数：屏幕宽只有 ~800px，600 个点画不出更多信息，
            // 却会让每帧的描边代价成倍上升（滚动曲线页时最明显）。
            val drawn = remember(values) { downsampleForDraw(values) }

            Row(modifier = Modifier.height(150.dp)) {
                Column(
                    modifier = Modifier.width(64.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    for (i in 0..divisions) {
                        val v = hi - range * i / divisions
                        Text(
                            "${fmtAxis(v, range)}$unit",
                            style = MaterialTheme.typography.labelSmall,
                            color = labelColor
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // 网格只有 5 条，继续用 drawLine
                    for (i in 0..divisions) {
                        val y = size.height * i / divisions
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                    }

                    // 曲线合成**一条** Path 再 drawPath，而不是逐段 drawLine。
                    // 逐段画时 Skia 要为每一段单独做带圆头端点的描边细分，
                    // 600 点 × 4 张图 = 2400 次；合并后只剩 4 次绘制调用。
                    val step = size.width / (drawn.size - 1).coerceAtLeast(1)
                    val path = Path()
                    var started = false
                    drawn.forEachIndexed { index, raw ->
                        if (raw == null) {
                            started = false // 断线，不补 0
                            return@forEachIndexed
                        }
                        val x = index * step
                        val y = size.height * (1f - (raw - lo) / range)
                        if (started) {
                            path.lineTo(x, y)
                        } else {
                            path.moveTo(x, y)
                            started = true
                        }
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(
                            width = 3f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append("纵轴单位 $unit · ${present.size} 个有效点 / 共 ${values.size} 个")
                    if (drawn.size < values.size) append("（绘图压缩至 ${drawn.size} 点）")
                },
                style = MaterialTheme.typography.labelSmall,
                color = labelColor
            )
        }
    }
}

// ══════════════════════════ 设置 ══════════════════════════

/**
 * 设置页里「悬浮窗外观」卡片的 item 下标 —— 双击悬浮窗唤起时滚到这里。
 *
 * LazyColumn 的 item 顺序就是下面 `item {}` 的书写顺序，**改动顺序时必须同步这个常量**。
 * 当前顺序：0 刷新间隔 / 1 深色模式 / 2 后台与悬浮窗 / 3 悬浮窗显示字段 /
 *          4 悬浮窗外观 / 5 其他 / 6 使用说明 / 7 关于 / 8 Spacer
 */
private const val SETTINGS_ITEM_OVERLAY_APPEARANCE = 4

@Composable
private fun SettingsPage(
    ui: BatteryMonitorUiState,
    onIntervalChange: (Long) -> Unit,
    onAlertsChange: (Boolean) -> Unit,
    onNotificationChange: (Boolean) -> Unit,
    onLiveUpdateChange: (Boolean) -> Unit,
    onIslandModeChange: (IslandMode) -> Unit,
    onOverlayChange: (Boolean) -> Unit,
    onOverlayFieldChange: (OverlayField, Boolean) -> Unit,
    onOverlayAlphaChange: (Float) -> Unit,
    onOverlayBackgroundChange: (OverlayBackground) -> Unit,
    onOverlayLockedChange: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    scrollToOverlay: Boolean,
    onOverlayScrollHandled: () -> Unit,
    onSelfCheck: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    val sliderSteps =
        ((SettingsStore.MAX_INTERVAL_MS - SettingsStore.MIN_INTERVAL_MS) / SettingsStore.STEP_INTERVAL_MS - 1)
            .toInt()
    val alphaSteps =
        ((1f - SettingsStore.MIN_OVERLAY_ALPHA) / SettingsStore.OVERLAY_ALPHA_STEP - 1).roundToInt()

    val listState = rememberLazyListState()
    LaunchedEffect(scrollToOverlay) {
        if (scrollToOverlay) {
            listState.animateScrollToItem(SETTINGS_ITEM_OVERLAY_APPEARANCE)
            onOverlayScrollHandled()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("刷新间隔", style = MaterialTheme.typography.titleSmall)
                        Text(
                            formatInterval(ui.intervalMs),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Slider(
                        value = ui.intervalMs.toFloat(),
                        onValueChange = { onIntervalChange(it.roundToLong()) },
                        valueRange = SettingsStore.MIN_INTERVAL_MS.toFloat()..
                            SettingsStore.MAX_INTERVAL_MS.toFloat(),
                        steps = sliderSteps
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0.2s", style = MaterialTheme.typography.labelSmall)
                        Text("5.0s", style = MaterialTheme.typography.labelSmall)
                    }

                    if (ui.intervalMs <= SettingsStore.FAST_WARN_MS) {
                        Hint("当前为高频档位。电池 uevent 由内核约每秒更新一次，低于 0.5s 的间隔基本只是在重复读取同一份数据，建议仅在观察瞬时波动时临时使用。")
                    } else {
                        Hint("步进 0.1s，范围 0.2s – 5.0s。设置会自动持久化并同步给后台服务。")
                    }
                }
            }
        }

        item {
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("深色模式", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = ui.themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                    Hint(
                        "独立于系统设置：系统是深色时也可让本应用保持浅色。\n" +
                            "悬浮窗的明暗与状态栏图标会同步跟随此设置。"
                    )
                }
            }
        }

        item {
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("后台与悬浮窗", style = MaterialTheme.typography.titleSmall)

                    SwitchRow(
                        title = "通知栏明细",
                        subtitle = "常驻通知显示实时电量/温度/电压",
                        checked = ui.notificationEnabled,
                        onChange = onNotificationChange
                    )

                    SwitchRow(
                        title = "实况通知（Android 16）",
                        subtitle = "提升到状态栏/锁屏的实时活动，直接显示功率与温度",
                        checked = ui.liveUpdateEnabled,
                        onChange = onLiveUpdateChange
                    )

                    if (Build.VERSION.SDK_INT < 36) {
                        Hint("当前系统低于 Android 16（API 36），实况通知不可用；升级系统后此项自动生效。")
                    } else {
                        Text("呈现方式", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IslandMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = ui.islandMode == mode,
                                    onClick = { onIslandModeChange(mode) },
                                    label = { Text(mode.label) }
                                )
                            }
                        }
                        Hint(ui.islandMode.hint)

                        // 设备自查结果直接摆出来：出问题时能一眼分清是「ROM 不支持」
                        // 还是「本应用没被授权」，不用去翻 logcat。
                        val effective = if (
                            ui.islandMode != IslandMode.AOSP && ui.canPostIsland
                        ) {
                            "小米超级岛（原生载荷，左右两区可控）"
                        } else {
                            "AOSP 实况通知（短关键文本）"
                        }
                        Hint(
                            "设备：${ui.romLabel.ifEmpty { "未知" }}\n" +
                                "原生岛载荷可用：${if (ui.canPostIsland) "是" else "否"}\n" +
                                "当前实际使用：$effective"
                        )

                        if (!ui.promotedAllowed && !ui.canPostIsland) {
                            Hint(
                                "系统未允许本应用发布实况通知，因此它只会出现在下拉通知栏里，" +
                                    "状态栏不会有缩略文本。这取决于 ROM 实现与系统设置。"
                            )
                        }
                    }

                    SwitchRow(
                        title = "悬浮窗",
                        subtitle = "在其他应用上层显示所选指标",
                        checked = ui.overlayEnabled,
                        onChange = onOverlayChange
                    )

                    if (!ui.overlayPermissionGranted) {
                        Hint("尚未获得「显示在其他应用上层」权限，开启悬浮窗前需要先授予。")
                        OutlinedButton(onClick = onOpenOverlaySettings) {
                            Text("打开系统设置授予权限")
                        }
                    }
                }
            }
        }

        item {
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("悬浮窗显示字段", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "已选 ${ui.overlayFields.size} 项（至少保留一项）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowChips(
                        fields = OverlayField.entries,
                        selected = ui.overlayFields,
                        onToggle = onOverlayFieldChange
                    )
                }
            }
        }

        item {
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("悬浮窗外观", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "不透明度 ${(ui.overlayAlpha * 100).roundToInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Text(
                        "背景取色（Material You）",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OverlayBackground.entries.forEach { style ->
                            FilterChip(
                                selected = ui.overlayBackground == style,
                                onClick = { onOverlayBackgroundChange(style) },
                                label = { Text(style.label) }
                            )
                        }
                    }
                    Hint(
                        "Material You 的「中性灰」是 surface 系列，只带极淡的壁纸色调；" +
                            "要明显的壁纸配色请选主色 / 次色 / 第三色。"
                    )

                    Slider(
                        value = ui.overlayAlpha,
                        onValueChange = onOverlayAlphaChange,
                        valueRange = SettingsStore.MIN_OVERLAY_ALPHA..1f,
                        steps = alphaSteps
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${(SettingsStore.MIN_OVERLAY_ALPHA * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text("100%", style = MaterialTheme.typography.labelSmall)
                    }

                    Hint(
                        "背景取自 Material You 动态色板（跟随壁纸），并随明暗模式切换。\n" +
                            "调低可减少对下方内容的遮挡；文字始终保持不透明，避免低不透明度下读不清。"
                    )

                    Spacer(Modifier.height(2.dp))

                    SwitchRow(
                        title = "勿扰模式（锁定悬浮窗）",
                        subtitle = "触摸直接穿透到下层应用：不能拖动，也不能双击唤起本应用",
                        checked = ui.overlayLocked,
                        onChange = onOverlayLockedChange
                    )

                    if (ui.overlayLocked) {
                        Hint("已锁定。想解除请回到本页关闭；锁定期间悬浮窗标题会显示 🔒。")
                    }
                }
            }
        }

        item {
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("其他", style = MaterialTheme.typography.titleSmall)
                    SwitchRow(
                        title = "异常告警",
                        subtitle = "读取失败时在界面顶部提示",
                        checked = ui.alertsEnabled,
                        onChange = onAlertsChange
                    )
                    OutlinedButton(onClick = onSelfCheck) { Text("运行环境自检") }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("使用说明", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "• 所有读取都在 root 上下文完成，仅执行只读命令，不写入 sysfs\n" +
                            "• 应用未声明 INTERNET 权限，不联网、不上传\n" +
                            "• 高频刷新会明显增加耗电，长时间挂悬浮窗建议用 1s 以上间隔",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            AppCard(onClick = onOpenAbout) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("关于", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "作者、项目地址与许可证",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "›",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 字段较多，用两行 FilterChip 排布；超出用 FlowRow 会引入额外实验 API，这里直接分组换行。 */
@Composable
private fun FlowChips(
    fields: List<OverlayField>,
    selected: Set<OverlayField>,
    onToggle: (OverlayField, Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        fields.chunked(3).forEach { rowFields ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowFields.forEach { field ->
                    val isOn = field in selected
                    FilterChip(
                        selected = isOn,
                        onClick = { onToggle(field, !isOn) },
                        label = { Text(field.label) }
                    )
                }
            }
        }
    }
}

// ══════════════════════════ 关于 ══════════════════════════

private const val PROJECT_URL = "https://github.com/CaiGit1/RootBatteryMonitor"

/** 「关于」子页面。由设置页底部入口进入，顶栏提供返回。 */
@Composable
private fun AboutPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    // 直接问 PackageManager，省得为读一个版本号去打开 buildConfig 生成
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 头像随 APK 打包（res/drawable-nodpi），不走网络 —— 因此无需
                        // INTERNET 权限，也不违背「不联网、不上传」的声明。
                        Image(
                            painter = painterResource(R.drawable.ic_author),
                            contentDescription = "作者头像",
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .border(
                                    1.5.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape
                                )
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Root Battery Monitor",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "版本 $version",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        "仅支持已 root 设备的电池监控应用。通过只读读取内核 power_supply 的 " +
                            "uevent 节点，展示电量、温度、电压、电流、功率、容量损耗与充电器状态。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("作者与许可", style = MaterialTheme.typography.titleSmall)
                    MetricRow("作者", "Anna Yanami (CaiGit1)")
                    MetricRow("GitHub", "CaiGit1")
                    MetricRow("许可证", "MIT")
                    MetricRow("版权", "© 2026 Anna Yanami")
                }
            }
        }

        item {
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("链接", style = MaterialTheme.typography.titleSmall)
                    LinkRow("项目主页", "CaiGit1/RootBatteryMonitor") {
                        uriHandler.openUri(PROJECT_URL)
                    }
                    LinkRow("问题反馈", "Issues") {
                        uriHandler.openUri("$PROJECT_URL/issues")
                    }
                    LinkRow("安全政策", "SECURITY.md") {
                        uriHandler.openUri("$PROJECT_URL/blob/main/SECURITY.md")
                    }
                    LinkRow("许可证全文", "LICENSE (MIT)") {
                        uriHandler.openUri("$PROJECT_URL/blob/main/LICENSE")
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("权限与安全边界", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "• 所有读取均在 root 上下文完成，仅执行只读命令，不写入 sysfs\n" +
                            "• 未声明 INTERNET 权限，不联网、不上传任何数据\n" +
                            "• 悬浮窗需要「显示在其他应用上层」，由用户手动授予",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun LinkRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.primary)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ══════════════════════════ 通用 ══════════════════════════

/**
 * 带点按反馈的卡片。
 *
 * 按下用 spring 轻微缩小、松手弹回 —— 与悬浮窗的缩放反馈是同一套手感。
 * 不可点的卡片（[onClick] 为 null）不加动画：没有任何动作却会动，
 * 反而让人误以为能点。
 */
@Composable
private fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    colors: CardColors = CardDefaults.cardColors(),
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "cardPressScale"
    )

    Card(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = ripple()
                    ) { onClick() }
                } else {
                    Modifier
                }
            ),
        colors = colors,
        content = content
    )
}

/** 展开 / 收起指示：文字 + 会旋转的箭头，整块都是触控目标。 */
@Composable
private fun ExpandIndicator(expanded: Boolean) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "expandArrow"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (expanded) "收起" else "展开",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "收起" else "展开",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { rotationZ = rotation }
        )
    }
}


@Composable
private fun ErrorBanner(message: String) {
    val semantic = LocalSemanticColors.current
    Card(colors = CardDefaults.cardColors(containerColor = semantic.errorContainer)) {
        Text(
            text = "异常：$message",
            color = semantic.onErrorContainer,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun MetricRow(name: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

/** 值很长时（如 ucsi 节点全名）左右排会挤在一起，改为上下堆叠。 */
@Composable
private fun MetricRowStacked(name: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(name)
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatInterval(ms: Long): String = "%.1fs".format(ms / 1000.0)

private fun windowLabel(points: List<TrendPoint>): String {
    if (points.size < 2) return "等待采样…"
    val seconds = (points.last().timestampMs - points.first().timestampMs) / 1000.0
    return "最近 %.1f 秒 · %d 个采样点".format(seconds, points.size)
}

private fun fmtValue(v: Float): String =
    if (kotlin.math.abs(v) < 100f) "%.1f".format(v) else v.roundToInt().toString()

private fun fmtAxis(v: Float, range: Float): String =
    if (range < 10f) "%.1f".format(v) else v.roundToInt().toString()

/** 绘制前的目标桶数。每桶最多产出 2 个点（最小 + 最大），故实际点数 ≤ 2×此值。 */
private const val MAX_CHART_BUCKETS = 90

/**
 * 绘制前的点数压缩。
 *
 * 用「按桶取最小 / 最大」而不是等距抽样：电流存在瞬时尖峰，等距抽样会把尖峰整段吃掉，
 * 而每桶同时保留最小值和最大值能保住曲线的包络形状。
 */
private fun downsampleForDraw(
    values: List<Float?>,
    maxBuckets: Int = MAX_CHART_BUCKETS
): List<Float?> {
    if (values.size <= maxBuckets * 2) return values

    val bucketSize = (values.size + maxBuckets - 1) / maxBuckets
    val out = ArrayList<Float?>(maxBuckets * 2)

    var i = 0
    while (i < values.size) {
        val end = minOf(i + bucketSize, values.size)
        var minV: Float? = null
        var maxV: Float? = null

        for (j in i until end) {
            val v = values[j] ?: continue
            val lo0 = minV
            if (lo0 == null || v < lo0) minV = v
            val hi0 = maxV
            if (hi0 == null || v > hi0) maxV = v
        }

        minV?.let { out.add(it) }
        maxV?.let { if (it != minV) out.add(it) }
        i = end
    }
    return out
}
