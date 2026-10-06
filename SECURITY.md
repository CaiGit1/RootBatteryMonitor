# 安全政策

## 报告漏洞

请**不要**通过公开 Issue 报告安全漏洞。

请使用 GitHub 的私密漏洞报告功能：

<https://github.com/CaiGit1/RootBatteryMonitor/security/advisories/new>

我们会在 7 天内确认收到，并在修复后发布公告。如果你希望，可以在公告中署名致谢。

## 支持范围

| 版本 | 是否维护 |
|---|---|
| 1.1.x | ✅ |
| < 1.1 | ❌ |

## 本项目的安全边界

本应用需要 **root 权限**，因此「它拿 root 做什么」本身就是安全面的一部分。当前设计约束：

- **只读**：仅执行 `su -c id`、`su -c ls`、`su -c cat /sys/class/power_supply/...`
- **不写入 sysfs**，不修改任何系统状态
- **无网络能力**：应用未声明 `INTERNET` 权限，不存在数据上传路径
- 不申请 `READ_LOGS`、`READ_PHONE_STATE`、`QUERY_ALL_PACKAGES` 等无关权限

如果你发现任何**超出上述约束**的行为（例如静默写入、联网、申请多余权限、把数据传出设备），请按漏洞流程报告。

## 已知的「非漏洞」行为

以下属于预期行为，不是安全问题：

- 不同 ROM 的 SELinux 策略阻止访问 `/sys/class/power_supply/*`，导致读取失败——应用会在环境自检中明确提示
- 部分机型的 `uevent` 字段缺失或单位异常（例如 `POWER_NOW` 与 `V×I` 不一致），应用选择**如实展示**而非猜测修正，详见 README 的「数据口径」
- 首次调用 `su` 时由 KernelSU / Magisk 弹出授权框

## 使用风险提示

授予 root 权限意味着应用获得设备的最高控制权。**请在授权前自行审查源码**（代码量很小，核心逻辑集中在 `RootBatteryReader.kt`）。
