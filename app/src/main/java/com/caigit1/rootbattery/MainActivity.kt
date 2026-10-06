package com.caigit1.rootbattery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15（targetSdk 35）已强制 edge-to-edge。显式调用并交由系统按当前明暗
        // 主题决定状态栏/导航栏图标颜色，避免深色模式下图标看不清。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            RootBatteryMonitorTheme {
                AppRoot()
            }
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
}

private val PAGE_TITLES = listOf("概览", "详情", "曲线", "设置")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AppRoot() {
    val vm: BatteryMonitorViewModel = viewModel(factory = BatteryMonitorViewModel.Factory)
    val ui by vm.uiState.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { PAGE_TITLES.size })

    // 从系统「显示在其他应用上层」设置页返回后重新校验权限
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refreshOverlayPermission() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Root Battery Monitor") },
                actions = {
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
            )
        },
        bottomBar = {
            NavigationBar {
                PAGE_TITLES.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        icon = { TabIcon(index, pagerState.currentPage == index) },
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { page ->
            when (page) {
                0 -> OverviewPage(ui, onRefresh = vm::refreshNow)
                1 -> DetailsPage(ui, onSelfCheck = vm::runSelfCheck)
                2 -> ChartsPage(ui)
                else -> SettingsPage(
                    ui = ui,
                    onIntervalChange = vm::setIntervalMs,
                    onAlertsChange = vm::setAlertsEnabled,
                    onNotificationChange = vm::setNotificationEnabled,
                    onOverlayChange = vm::setOverlayEnabled,
                    onOverlayFieldChange = vm::setOverlayField,
                    onSelfCheck = vm::runSelfCheck,
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
private fun OverviewPage(ui: BatteryMonitorUiState, onRefresh: () -> Unit) {
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
                    StatTile("温度", snap?.temperatureText ?: "--", Modifier.weight(1f))
                    StatTile("电压", snap?.voltageText ?: "--", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("电流", snap?.currentText ?: "--", Modifier.weight(1f))
                    StatTile("功率 V×I", snap?.computedPowerText ?: "--", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("健康度", snap?.healthPercentText ?: "--", Modifier.weight(1f))
                    StatTile("循环次数", snap?.cycleCount?.toString() ?: "--", Modifier.weight(1f))
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
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
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
            ExpandableSection("电压 / 功率", initiallyExpanded = true) {
                MetricRow("VOLTAGE_NOW", snap?.voltageText ?: "--")
                MetricRow("VOLTAGE_OCV", snap?.ocvText ?: "--")
                MetricRow("VOLTAGE_MAX", snap?.voltageMaxText ?: "--")
                MetricRow("CURRENT_NOW", snap?.currentText ?: "--")
                MetricRow("POWER_NOW（内核）", snap?.powerNowText ?: "--")
                MetricRow("POWER_AVG（内核）", snap?.powerAvgText ?: "--")
                MetricRow("自算功率 V×I", snap?.computedPowerText ?: "--")
                Hint("电流为内核原始符号（各 ROM 正负约定不同）；POWER_NOW 为内核上报值，部分机型与 V×I 差异很大。")
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
                MetricRowStacked("USB 类型", c?.usbType ?: "--")
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

@Composable
private fun ExpandableSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
            }
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
    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "原始 uevent（${raw.size} 项）",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
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
                title = "功率 V×I",
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
                    for (i in 0..divisions) {
                        val y = size.height * i / divisions
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                    }

                    val step = size.width / (values.size - 1).coerceAtLeast(1)
                    var prev: Offset? = null
                    values.forEachIndexed { index, raw ->
                        if (raw == null) {
                            prev = null // 断线，不补 0
                            return@forEachIndexed
                        }
                        val x = index * step
                        val y = size.height * (1f - (raw - lo) / range)
                        val current = Offset(x, y)
                        prev?.let {
                            drawLine(
                                color = color,
                                start = it,
                                end = current,
                                strokeWidth = 3f,
                                cap = StrokeCap.Round
                            )
                        }
                        prev = current
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "纵轴单位 $unit · ${present.size} 个有效点 / 共 ${values.size} 个",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor
            )
        }
    }
}

// ══════════════════════════ 设置 ══════════════════════════

@Composable
private fun SettingsPage(
    ui: BatteryMonitorUiState,
    onIntervalChange: (Long) -> Unit,
    onAlertsChange: (Boolean) -> Unit,
    onNotificationChange: (Boolean) -> Unit,
    onOverlayChange: (Boolean) -> Unit,
    onOverlayFieldChange: (OverlayField, Boolean) -> Unit,
    onSelfCheck: () -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    val sliderSteps =
        ((SettingsStore.MAX_INTERVAL_MS - SettingsStore.MIN_INTERVAL_MS) / SettingsStore.STEP_INTERVAL_MS - 1)
            .toInt()

    LazyColumn(
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

// ══════════════════════════ 通用 ══════════════════════════

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
