# Root Battery Monitor

[![Android Build](https://github.com/CaiGit1/RootBatteryMonitor/actions/workflows/android-build.yml/badge.svg)](https://github.com/CaiGit1/RootBatteryMonitor/actions/workflows/android-build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

一个仅支持 **已 root Android 设备** 的电池监控应用。通过 `su` 只读读取内核 `power_supply` 的 `uevent` 节点，在 Jetpack Compose 界面中展示电量、温度、电压、电流、功率、容量损耗、循环次数与充电器状态。

> 数据**仅本地显示**：不上传、不联网、不写入 sysfs。

## 功能

**界面**：底部导航 + 左右滑动切换 4 页

| 页面 | 内容 |
|---|---|
| 概览 | 大字号电量与状态、两列紧凑指标卡（温度/电压/电流/功率 V×I/健康度/循环次数）、供电信息 |
| 详情 | 分组折叠卡片（电压功率、容量寿命、充电器、充电控制、时间估算、电池身份）+ 环境自检 + 原始 uevent 展开 |
| 曲线 | 温度 / 电压 / 电流 / 功率 四张折线图，**纵轴带明确单位**、5 档网格、本窗口极值统计 |
| 设置 | 刷新间隔滑块、后台与悬浮窗开关、悬浮窗字段多选 |

**主题**：跟随系统深色模式；Android 12+ 启用 **Material You 动态取色**（跟随壁纸）；edge-to-edge 显示，状态栏/导航栏图标自动适配明暗。语义色（成功/失败/曲线配色）随明暗主题切换，不写死浅色专用值。

**数据**
- **自动探测电池节点**：遍历 `/sys/class/power_supply/*/uevent`，兼容 `battery` / `bms` / `BAT0` / `maxfg` 等不同 ROM 命名
- **供电侧节点读取**：同时解析 `usb` / `wireless` / `ucsi`，按信息量择优展示充电器输入电压、电流、`USB_TYPE`、电流上限、输入限流与温度
- **电池健康度**：满电容量 ÷ 设计容量
- **计算功率 V×I**：与内核 `POWER_NOW` 并列显示，便于交叉校验
- **原始 uevent 全量展开**：内核报了什么就展示什么
- 环境自检：root 可用 / 电池节点 / 节点可读 / 供电节点 / 常驻 shell

**刷新与后台**

- 刷新间隔 **0.2s – 5.0s 滑块可调，步进 0.1s**，设置自动持久化并同步给后台服务
- **常驻 root shell**：高频刷新复用单个 `su` 进程，而不是每拍 fork 一次 —— 0.2s 档位下这是必要前提，否则等于每秒 5 次进程创建
- **悬浮窗**：在其他应用上层显示自选指标（11 项可选），整卡可拖动，电量按高低变色
- 前台服务 + 常驻通知；通知栏明细可单独关闭（只留悬浮窗时通知显示简化信息）
- 曲线最多保留 600 个采样点（0.2s 档位约 2 分钟窗口）
- root 读取失败时自动降级为一次性 `su` 命令，并退避重试

## 关键限制

- 仅支持已 root 设备（KernelSU / Magisk 等需授予本应用 root）
- ROM / SELinux 策略可能阻止访问 sysfs，此时自检会明确提示原因
- 不同设备的 `uevent` 字段与单位存在差异，见下文「数据口径」

## 数据口径

**不替内核「修数」**，以下处理方式均为如实呈现：

| 字段 | 说明 |
|---|---|
| `POWER_SUPPLY_TEMP` | 0.1 °C，÷10 转为 °C |
| `POWER_SUPPLY_VOLTAGE_*` | µV，÷1000 转为 mV |
| `POWER_SUPPLY_CURRENT_NOW` | µA，÷1000 转为 mA。**充电时可能为负**（内核符号约定，各 ROM 不一致） |
| `POWER_SUPPLY_POWER_NOW` | µW，÷1000 转为 mW。实测部分机型该值与 `V×I` 相差百倍，故两者并列显示而非二选一 |
| `POWER_SUPPLY_CHARGE_COUNTER` | 各 ROM 单位不统一，**按原值展示**，不做换算（曾按 µAh→mAh 换算得出误导性的「3 mAh」） |
| `POWER_SUPPLY_TIME_TO_*` | 秒。内核用 `-1` / `0xFFFF(65535)` 表示未知，统一显示为 `--` |

## 构建

前置：**JDK 17** 与 Android SDK（`compileSdk 35`、`build-tools 34.0.0`）。

```bash
# 1) 指向本机 SDK（该文件已 gitignore）
echo "sdk.dir=/path/to/AndroidSDK" > local.properties

# 2) 构建
./gradlew :app:assembleDebug      # 调试包 → app/build/outputs/apk/debug/
./gradlew :app:assembleRelease    # 发布包 → app/build/outputs/apk/release/
```

### 发布签名（可选）

`assembleRelease` 的签名配置从**仓库外**的 `keystore.properties` 读取；该文件缺失时自动退化为不签名，因此 `assembleDebug` 与 CI 完全不受影响。

```properties
# keystore.properties —— 已加入 .gitignore，请勿提交
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

> ⚠️ 密钥库丢失后将**无法对已安装的包做覆盖更新**，请务必离线备份 `.jks` 与口令。

## 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 安全约束

- 仅执行只读命令，例如 `su -c cat /sys/class/power_supply/battery/uevent`
- 不写入 sysfs
- 不做任何网络上传
- 所有文件探测与读取均在 **root 上下文**完成。普通应用 UID 自 Android 10 起受 SELinux 限制无法 `stat` `/sys`，用 `File.exists()` 会恒为 `false` 并导致误报

## 手工验证建议

- 在至少两台不同 ROM 的 root 设备上验证
- 覆盖充电、放电、高温场景
- 验证 root 未授权 / SELinux 拒绝 / 节点不存在时的 UI 提示
- 用 `adb shell su -c cat /sys/class/power_supply/*/uevent` 与界面数值交叉核对

## 许可证

[MIT](LICENSE) © 2026 Anna Yanami (CaiGit1)

## 安全

本应用需要 root 权限。其权限使用边界（只读、不写 sysfs、无网络权限）与漏洞报告方式见 [SECURITY.md](SECURITY.md)。

