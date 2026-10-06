# Root Battery Monitor

[![Android Build](https://github.com/CaiGit1/RootBatteryMonitor/actions/workflows/android-build.yml/badge.svg)](https://github.com/CaiGit1/RootBatteryMonitor/actions/workflows/android-build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

展示电池状态的 Android 应用。

有 root 时直读内核 `power_supply` 节点，字段最全；没有 root 时自动改用系统公开 API，
仍能显示电量、温度、电压、电流、功率、充电状态与循环次数，充电协议、健康度与原始
uevent 需要 root。

数据只在本机显示，不做任何网络请求。

APK 见 [Releases](https://github.com/CaiGit1/RootBatteryMonitor/releases)。

## 功能

**四个页面**（底部导航，可左右滑动）

- **概览** — 电量与充电状态、温度、电压、电流、功率、健康度、循环次数
- **详情** — 按分组展开查看容量寿命、充电器、充电控制、时间估算、电池身份，以及原始 uevent
- **曲线** — 温度 / 电压 / 电流 / 功率 四张折线图，带单位与极值统计
- **设置** — 刷新间隔、深色模式、通知与实况、悬浮窗、关于

**悬浮窗** — 在其他应用上层显示自选指标，整卡可拖动。背景取 Material You 壁纸色，可选主色 / 次色 / 第三色 / 中性灰，不透明度 20%–100%。双击跳到悬浮窗设置；勿扰模式下触摸穿透，不可拖动也不会误触。

**通知与实况** — 常驻通知显示实时数据。Android 16 支持实况通知；HyperOS 上显示为小米超级岛（左区功率、右区温度）。设置里可切换自动 / 小米超级岛 / 类原生 AOSP。

**刷新** — 0.2s – 5.0s 滑块可调，步进 0.1s。高频档位复用单个常驻 `su` 进程，不反复创建。

**主题** — 深色模式可独立于系统设置；Android 12+ 跟随壁纸取色。

## 数据说明

- 功率由「电压 × 电流」计算。`POWER_NOW` / `POWER_AVG` 在部分机型上是固定占位值，不采用；原始值仍可在「原始 uevent」查看
- 电流与功率保留内核符号，正负表示方向（各 ROM 约定不同）
- `CHARGE_COUNTER` 按原值展示，各 ROM 单位不统一
- 充电协议取 `USB_TYPE` 中方括号标出的当前生效项
- `TIME_TO_*` 的 `-1` 与 `65535` 显示为 `--`

## 要求

- Android 8.0+
- 有 root 则字段最全；无 root 时自动降级，不影响使用
- 部分 ROM 的 SELinux 策略会阻止读取 sysfs，此时也会走免 root 路径

## 构建

需要 JDK 17，以及 `compileSdk 35` / `build-tools 34.0.0` 的 Android SDK。

```bash
echo "sdk.dir=/path/to/AndroidSDK" > local.properties
./gradlew :app:assembleDebug
```

发布包签名从仓库外的 `keystore.properties` 读取，文件不存在时跳过签名：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

密钥库丢失后无法覆盖更新已安装的应用，请离线备份。

## 许可证

[MIT](LICENSE) © 2026 Anna Yanami (CaiGit1)
