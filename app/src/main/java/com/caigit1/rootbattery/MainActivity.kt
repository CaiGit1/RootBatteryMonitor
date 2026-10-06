package com.caigit1.rootbattery

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            MaterialTheme {
                val vm: BatteryMonitorViewModel = viewModel(factory = BatteryMonitorViewModel.Factory)
                val ui by vm.uiState.collectAsState()
                BatteryMonitorScreen(
                    uiState = ui,
                    onRefresh = vm::refreshNow,
                    onSelfCheck = vm::runSelfCheck,
                    onPollingChange = vm::setPollingSeconds,
                    onAlertsChange = vm::setAlertsEnabled,
                    onServiceChange = vm::setForegroundServiceEnabled
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatteryMonitorScreen(
    uiState: BatteryMonitorUiState,
    onRefresh: () -> Unit,
    onSelfCheck: () -> Unit,
    onPollingChange: (Int) -> Unit,
    onAlertsChange: (Boolean) -> Unit,
    onServiceChange: (Boolean) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Root Battery Monitor") })
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                val statusColor = if (uiState.rootReady) Color(0xFF2E7D32) else Color(0xFFC62828)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(statusColor, RoundedCornerShape(10.dp))
                    )
                    Text(if (uiState.rootReady) "Root 可用" else "Root 未就绪", fontWeight = FontWeight.Bold)
                }
            }

            item {
                if (uiState.error != null) {
                    ErrorBanner(uiState.error.message)
                }
            }

            item {
                MetricsCard(uiState.latest)
            }

            item {
                ControlPanel(
                    pollingSeconds = uiState.pollingSeconds,
                    alertsEnabled = uiState.alertsEnabled,
                    serviceEnabled = uiState.serviceEnabled,
                    onRefresh = onRefresh,
                    onSelfCheck = onSelfCheck,
                    onPollingChange = onPollingChange,
                    onAlertsChange = onAlertsChange,
                    onServiceChange = onServiceChange
                )
            }

            item {
                SelfCheckCard(uiState.selfChecks)
            }

            item {
                TrendCard(uiState.history)
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
        Text(
            text = "异常: $message",
            color = Color(0xFFB71C1C),
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun MetricsCard(snapshot: BatterySnapshot?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        SectionCard("核心指标") {
            MetricRow("电量", snapshot?.levelPercent?.let { "$it%" } ?: "--")
            snapshot?.levelPercent?.let {
                LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth())
            }
            MetricRow("温度", snapshot?.temperatureText ?: "--")
            MetricRow("电压", snapshot?.voltageText ?: "--")
            MetricRow("电流", snapshot?.currentText ?: "--")
            MetricRow("功率", snapshot?.powerNowText ?: "--")
            MetricRow("状态", snapshot?.status ?: "--")
            MetricRow("健康", snapshot?.health ?: "--")
            MetricRow("充电类型", snapshot?.chargeType ?: "--")
        }

        SectionCard("容量与寿命") {
            MetricRow("满电容量", snapshot?.chargeFullText ?: "--")
            MetricRow("设计容量", snapshot?.chargeFullDesignText ?: "--")
            MetricRow("健康度", snapshot?.healthPercentText ?: "--")
            MetricRow("循环次数", snapshot?.cycleCount?.toString() ?: "--")
            MetricRow("电荷计数", snapshot?.chargeCounterText ?: "--")
        }

        SectionCard("电压 / 功率细节") {
            MetricRow("VOLTAGE_NOW", snapshot?.voltageText ?: "--")
            MetricRow("VOLTAGE_OCV", snapshot?.ocvText ?: "--")
            MetricRow("VOLTAGE_MAX", snapshot?.voltageMaxText ?: "--")
            MetricRow("POWER_NOW", snapshot?.powerNowText ?: "--")
            MetricRow("POWER_AVG", snapshot?.powerAvgText ?: "--")
            MetricRow("计算功率 V×I", snapshot?.computedPowerText ?: "--")
            Text(
                "电流为内核原始符号（各 ROM 正负约定不同）；POWER_NOW 为内核上报值，可能与 V×I 不一致。",
                style = MaterialTheme.typography.bodySmall
            )
        }

        SectionCard("充电器（供电侧节点）") {
            val c = snapshot?.charger
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

        SectionCard("充电控制") {
            MetricRow("恒流充电", snapshot?.constantChargeCurrentText ?: "--")
            MetricRow("限流档位", snapshot?.chargeControlLimitText ?: "--")
        }

        SectionCard("时间估算") {
            MetricRow("预计充满", snapshot?.timeToFullText ?: "--")
            MetricRow("预计耗尽", snapshot?.timeToEmptyText ?: "--")
        }

        SectionCard("电池身份") {
            MetricRow("节点名", snapshot?.name ?: "--")
            MetricRow("类型", snapshot?.type ?: "--")
            MetricRow("技术", snapshot?.technology ?: "--")
            MetricRow("型号", snapshot?.modelName ?: "--")
            MetricRow("在位", snapshot?.presentText ?: "--")
            MetricRowStacked("数据源", snapshot?.sourcePath ?: "--")
        }

        RawUeventCard(snapshot?.raw ?: emptyMap())
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun RawUeventCard(raw: Map<String, String>) {
    var expanded by remember { mutableStateOf(false) }
    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("原始 uevent（${raw.size} 项）", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "展开")
                }
            }
            if (expanded) {
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

@Composable
private fun ControlPanel(
    pollingSeconds: Int,
    alertsEnabled: Boolean,
    serviceEnabled: Boolean,
    onRefresh: () -> Unit,
    onSelfCheck: () -> Unit,
    onPollingChange: (Int) -> Unit,
    onAlertsChange: (Boolean) -> Unit,
    onServiceChange: (Boolean) -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("控制面板", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 5, 10, 30).forEach { sec ->
                    FilterChip(
                        selected = pollingSeconds == sec,
                        onClick = { onPollingChange(sec) },
                        label = { Text("${sec}s") }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("异常告警")
                Switch(checked = alertsEnabled, onCheckedChange = onAlertsChange)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("前台服务")
                Switch(checked = serviceEnabled, onCheckedChange = onServiceChange)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefresh) {
                    Text("立即刷新")
                }
                Button(onClick = onSelfCheck) {
                    Text("环境自检")
                }
            }
        }
    }
}

@Composable
private fun SelfCheckCard(selfChecks: List<SelfCheckItem>) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Root 权限检测 / 环境自检", style = MaterialTheme.typography.titleMedium)
            selfChecks.forEach { check ->
                val color = if (check.ok) Color(0xFF1B5E20) else Color(0xFFC62828)
                Text("• ${check.title}: ${if (check.ok) "通过" else "失败"}", color = color)
                Text(check.detail, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun TrendCard(history: List<BatterySnapshot>) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("趋势视图（温度/电压）", style = MaterialTheme.typography.titleMedium)
            TrendPlot(history.mapNotNull { it.temperatureCelsius?.toFloat() }, Color(0xFFD32F2F), "温度")
            TrendPlot(history.mapNotNull { it.voltageNowMv?.toFloat() }, Color(0xFF1976D2), "电压")
        }
    }
}

@Composable
private fun TrendPlot(points: List<Float>, color: Color, label: String) {
    Text(label, style = MaterialTheme.typography.bodySmall)
    if (points.size < 2) {
        Text("样本不足")
        return
    }

    val min = points.minOrNull() ?: 0f
    val max = points.maxOrNull() ?: min + 1f
    val span = (max - min).takeIf { it > 0f } ?: 1f

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
    ) {
        val step = size.width / (points.size - 1)
        for (i in 0 until points.lastIndex) {
            val x1 = i * step
            val y1 = size.height - ((points[i] - min) / span) * size.height
            val x2 = (i + 1) * step
            val y2 = size.height - ((points[i + 1] - min) / span) * size.height
            drawLine(color = color, start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = 4f)
        }
        drawRect(color = Color.LightGray, style = Stroke(1f))
    }
}
