# 安全政策

## 报告漏洞

请勿通过公开 Issue 报告安全漏洞，改用 GitHub 的私密报告：

<https://github.com/CaiGit1/RootBatteryMonitor/security/advisories/new>

7 天内确认，修复后发布公告。需要署名可以在公告中致谢。

## 支持范围

当前维护 1.1.x 及之后的版本。

## 安全边界

本应用需要 root 权限。它使用 root 做的事仅限：

- 只读命令，如 `su -c cat /sys/class/power_supply/...`
- 不写入 sysfs，不修改系统状态
- 未声明 `INTERNET` 权限，没有数据上传路径
- 不申请 `READ_LOGS`、`READ_PHONE_STATE`、`QUERY_ALL_PACKAGES` 等无关权限

如发现超出上述范围的行为（静默写入、联网、多余权限、数据外传），请按漏洞流程报告。

## 以下情况不是安全问题

- ROM 的 SELinux 策略阻止访问 `/sys/class/power_supply/*`，应用会在环境自检中提示
- 部分机型的 uevent 字段缺失或单位异常，应用按原值展示，不做猜测性修正（见 README 的数据说明）
- 首次调用 `su` 时由 KernelSU / Magisk 弹出授权框

## 使用风险

授予 root 意味着交出设备的最高控制权。建议授权前自行审阅源码，核心逻辑集中在 `RootBatteryReader.kt`。
