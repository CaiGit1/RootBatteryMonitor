---
name: Bug 报告
about: 应用崩溃、数据不正确或读取失败
title: "[Bug] "
labels: bug
---

## 环境信息

- **设备型号**：
- **ROM / 系统版本**（例：MIUI 15 / Android 15）：
- **root 方案与版本**（KernelSU / Magisk / 其他）：
- **应用版本**：

  ```bash
  adb shell dumpsys package com.caigit1.rootbattery | grep versionName
  ```

## 问题描述

简要说明发生了什么。

## 复现步骤

1.
2.
3.

## 期望行为

## 实际行为

## 崩溃日志

```bash
adb logcat -c
# 然后复现问题
adb logcat -d -b crash
```

<details>
<summary>点击展开粘贴日志</summary>

```
在此粘贴
```

</details>

## 应用内「环境自检」结果

请点一次应用内的**环境自检**，把四项结果贴上来：

- Root 可用：
- 电池节点：
- 节点可读：
- 供电节点：

## 设备上的原始数据

```bash
adb shell su -c "ls /sys/class/power_supply/; echo ---; cat /sys/class/power_supply/*/uevent"
```

<details>
<summary>点击展开粘贴原始 uevent</summary>

```
在此粘贴
```

</details>

## 补充信息

截图或其他有助于定位的线索。
