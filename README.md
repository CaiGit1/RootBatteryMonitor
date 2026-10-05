# Root Battery Monitor (Android)

Root Battery Monitor 是一个仅支持 **已 root Android 设备** 的电池监控应用，周期读取 `/sys/class/power_supply/battery/uevent`，并在 Compose GUI 中展示电量、温度、电压、状态和趋势。

## 功能概览

- Kotlin + Jetpack Compose 界面
- Root 权限检测与环境自检
- 读取并解析 `uevent` 键值数据
- 周期采集 + 异常重试
- 前台服务持续采集
- 温度/电压趋势视图
- 默认仅本地显示，不上传数据

## 关键限制

- 仅在已 root 设备可用
- ROM/SELinux 策略可能阻止访问 `uevent`
- 不同设备字段可能有差异

## 本地构建

```bash
cd /home/runner/work/github-slideshow/github-slideshow/android-root-battery-monitor
./gradlew :app:assembleDebug
```

> 需要本地安装 Android SDK（本仓库未包含）。

## 安全约束

- 仅执行只读命令：`su -c cat /sys/class/power_supply/battery/uevent`
- 不写入 sysfs
- 不做网络上传

## 手工验证建议

- 在至少两台 root 设备验证（不同 ROM）
- 覆盖充电、放电、高温场景
- 验证 root 丢失、权限拒绝、路径不存在时的 UI 提示
