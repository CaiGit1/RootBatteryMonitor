# Root Battery Monitor

一个仅支持 **已 root Android 设备** 的电池监控应用。通过 `su` 只读读取内核 `power_supply` 的 `uevent` 节点，在 Jetpack Compose 界面中展示电量、温度、电压、电流、功率、容量损耗、循环次数与充电器状态。

> 数据**仅本地显示**：不上传、不联网、不写入 sysfs。

## 功能

- Kotlin + Jetpack Compose（Material 3）
- **自动探测电池节点**：遍历 `/sys/class/power_supply/*/uevent`，兼容 `battery` / `bms` / `BAT0` / `maxfg` 等不同 ROM 命名
- **供电侧节点读取**：同时解析 `usb` / `wireless` / `ucsi`，按信息量择优展示充电器输入电压、电流、`USB_TYPE`、电流上限、输入限流与温度
- **电池健康度**：满电容量 ÷ 设计容量
- **计算功率 V×I**：与内核 `POWER_NOW` 并列显示，便于交叉校验
- **原始 uevent 全量展开**：内核报了什么就展示什么
- 环境自检：root 可用 / 电池节点 / 节点可读 / 供电节点
- 前台服务持续采集 + 常驻通知
- 温度、电压趋势图
- 采集周期可调（2 / 5 / 10 / 30 秒）

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
