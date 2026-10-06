# Root Battery Monitor v1.2.0

自 v1.1.0 以来的一次功能级更新：悬浮窗、高频刷新、界面重构、Material You 主题，以及若干**基于真机实测**修正的数据准确性问题。

---

## 悬浮窗

- **Material You 动态取色**：背景走壁纸色板，可在 **主色 / 次色 / 第三色 / 中性灰** 四种角色间切换
  > 说明：Material You 的 `surface` / `surfaceContainer*` 是**中性色**，只带极淡的壁纸色调（深色下约 `#2B2930`，看着就是黑灰）。要明显的壁纸配色需选主色 / 次色 / 第三色 —— 这也是默认值。
- **背景不透明度 20% – 100% 可调**（文字保持不透明，避免低不透明度下读不清）
- **11 项指标自选**，新增 **充电协议**（PD / PPS / DCP / CDP / SDP / HVDCP / BrickID …）
- **手势**：按下 / 拖动有缩放反馈，松手用 `OvershootInterpolator` 回弹；**双击直接跳到本应用的悬浮窗设置页**
- **勿扰（锁定）模式**：给窗口加 `FLAG_NOT_TOUCHABLE`，触摸直接穿透到下层应用 —— 不可拖动、不可双击唤起本应用，适合长期挂机防误触；标题显示 🔒
- 整卡可拖动；明暗与状态栏图标跟随应用的主题设置，而非系统

## 刷新与性能

- 刷新间隔改为**滑块：0.2s – 5.0s，步进 0.1s**，设置自动持久化并同步给后台服务
- 新增**常驻 root shell**：高频刷新复用单个 `su` 进程，而不是每拍 fork 一次
  > 这是 0.2s 档位的必要前提 —— 否则等于每秒 5 次进程创建 + KernelSU 授权链路往返，开销与耗电都不可接受。
- 电池节点路径与供电节点改为缓存（供电节点 5s TTL），昂贵探测不跟着高频跑
- 采样循环扣掉读取耗时再 delay，保证间隔是「两次采样起点之差」
- 曲线改用**单条 `Path` + 一次 `drawPath`** 绘制（原为逐段 `drawLine`，600 点 × 4 图 = 2400 次带圆头端点的描边），并在绘制前按「桶内最小 / 最大」压缩点数，保留电流尖峰包络
- 悬浮窗拖动期间暂缓内容刷新；数值未变化时不调用 `setText`
  > 悬浮窗是 `WRAP_CONTENT` 窗口，**任何一次 `setText` 都会触发窗口级 relayout**，与拖动的 `updateViewLayout` 抢主线程，正是「拖动一卡一卡」的来源。
- 实测：拖动测试中 `Missed Vsync = 0`，帧耗时 50th 9ms / 99th 12ms，无帧超出 60Hz 预算

## 界面

- 底部导航 + 左右滑动切换 4 页：**概览 / 详情 / 曲线 / 设置**
- 概览页改为紧凑两列卡片；细节移入详情页的可折叠分组
- **曲线独立成页**：温度 / 电压 / 电流 / 功率 四张折线图，**纵轴带明确单位**、5 档网格、本窗口极值统计；null 值断线而非补 0
- **卡片点按动画**：可点卡片统一走 spring 缩放反馈（按下 0.975、松手阻尼弹回）
- **展开 / 收起是动画**：spring 垂直展开 + 淡入，收起用更大阻尼快速收拢；指示器为「展开 / 收起」文字 + 会旋转的箭头，且**整张卡片**都是触控目标
- 概览页指标卡点按可直接跳到「详情」页
- **关于**子页面：作者头像、版本、项目地址、许可证与权限边界说明

## 主题

- **深色模式可独立设置**（跟随系统 / 始终浅色 / 始终深色），与系统设置解耦
- Android 12+ 启用 **Material You 动态取色**（跟随壁纸）
- edge-to-edge 显示，**状态栏 / 导航栏图标跟随应用的明暗设置**（强制浅色时同样正确反色）
- 语义色（成功 / 失败 / 曲线配色）随明暗切换，不写死浅色专用值

## 数据准确性（基于真机实测修正）

| 项 | 处理 |
|---|---|
| `POWER_SUPPLY_POWER_NOW` / `POWER_AVG` | **不再采用**。实测机型上恒为 `10000` / `5000` 的固定占位值，与实际相差百倍，展示它只会误导。界面一律显示「电压 × 电流」自算功率；原始值仍可在「原始 uevent」中查看 |
| 电流与功率 | **保留内核原始符号并显式标注正负**（方向约定各 ROM 相反） |
| `POWER_SUPPLY_USB_TYPE` | **不是单值**。内核列出全部支持协议并用方括号标出当前生效项，例如 `Unknown [SDP] DCP CDP ACA C PD PD_DRP PD_PPS BrickID`。此前当单值显示，界面上会糊出一整行标识符并撑宽悬浮窗；现只展示当前协议 |
| `POWER_SUPPLY_CHARGE_COUNTER` | 各 ROM 单位不统一，**按原值展示**，不做换算 |

## 应用图标

自适应图标（深青绿渐变 + 白色电池轮廓与闪电），含 monochrome 层。替换掉原先的 `@android:drawable/stat_sys_warning`（系统警告三角）。

## 安装

```bash
adb install -r RootBatteryMonitor-1.2.0.apk
```

- 需要已 root 设备（KernelSU / Magisk 等授予本应用 root）
- `minSdk 26` / `targetSdk 35`
- 需授予「显示在其他应用上层」才能使用悬浮窗

## 关于签名

Release 附件一律为**发布密钥签名**（`CN=Root Battery Monitor, OU=CaiGit1`），可覆盖安装此前的正式版。

自动发布工作流在**未配置密钥库时会直接失败、拒绝发布** —— 因为 debug 签名（`CN=Android Debug`）与正式密钥是两把完全不同的钥匙，发出去只会让使用者遇到签名冲突、无法覆盖更新。宁可不发，也不发一个签名不对的包。

要启用自动发布，在仓库 **Settings → Secrets and variables → Actions** 中添加以下四项：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | 密钥库文件的 base64（见下方生成命令） |
| `KEYSTORE_PASSWORD` | `storePassword` |
| `KEY_ALIAS` | `keyAlias` |
| `KEY_PASSWORD` | `keyPassword` |

生成 `KEYSTORE_BASE64`（PowerShell，直接把结果放进剪贴板，不经过屏幕）：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\Users\Yanami\.android-keystore\rootbattery-release.jks")) | Set-Clipboard
```

> ⚠️ 密钥库丢失将**无法对已安装的包做覆盖更新**，请离线备份 `.jks` 与口令。
>
> ⚠️ 若把密钥库放进 Secrets，请确认该仓库的 Secrets 访问范围与协作者权限；不愿托管密钥的话，退回「本地 `assembleRelease` 后手动上传附件」即可。

## 安全边界

- 仅执行只读命令（`su -c cat /sys/class/power_supply/...`），不写入 sysfs
- **未声明 `INTERNET` 权限**，不联网、不上传任何数据（关于页的作者头像也是随 APK 打包的，不走网络）
- 所有文件探测与读取均在 root 上下文完成
