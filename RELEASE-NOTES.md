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

## 实况通知 → 小米超级岛

电池状态会显示在**状态栏 / 锁屏的实时活动**上。在 HyperOS 上，系统会把它渲染成**小米超级岛**：

```
[🔋]  功率+1103 mW                    温度41.2°C
```

### 两条路径，实机上只能选一条

HyperOS 对「通知上岛」有两套机制，**同时用会互相打架**：

| 路径 | 谁能控制岛的内容 |
|---|---|
| **AOSP 实况通知**（`PROMOTED_ONGOING`） | ❌ 不行。HyperOS 会用它自己的转换逻辑，把 `shortCriticalText` 塞进右区，**左区 `textInfo.title` 留成空串**，并且**完全忽略 `miui.focus.param`** |
| **原生岛载荷**（`miui.focus.param`） | ✅ 左右两区都由开发者控制（`imageTextInfoLeft` / `imageTextInfoRight`） |

因此本应用的做法是：**能上岛时走原生载荷，不请求实况通知提升**；不支持岛的机型（如 Pixel）才退回 AOSP 实况通知。

> 这个结论不是看文档猜的，是从 SystemUI 自己打印的岛模板里读出来的 ——
> 它会把最终模板 base64 打进 logcat（`IslandTemplateFactory: createBigIslandTemplate: ...`），
> 解码后能直接看到 `imageTextInfoLeft.textInfo.title` 是空串。

### 上岛前的能力自查

按官方《开发指南》第五节实现，三项都要过：

```java
persist.sys.feature.island          // 系统是否支持岛
notification_focus_protocol == 3    // OS3 才支持超级岛模板
content://miui.statusbar.notification.public → canShowFocus   // 本应用是否已获焦点通知授权
```

第三项**必须由应用自己调用**：该 provider 会校验调用方 uid 是否拥有传入的包名，
adb / root 调用一律被拒（实测报 `Package X is not owned by uid 0`）。

> ⚠️ 官方 Q&A 说焦点通知需邮件申请、按年续期。但本机 SystemUI 的 `canShowFocus`
> 返回 `true`，说明该设备上权限是通的。**换设备后这个查询结果可能不同** ——
> 应用已按此做降级：查不到权限就退回普通通知，不会静默失效。

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

Release 附件由**本地构建**产出，使用发布密钥签名（`CN=Root Battery Monitor, OU=CaiGit1`），可覆盖安装此前的正式版。

发布流程：

```bash
# keystore.properties 放在仓库根目录（已被 .gitignore，不入库），内容：
#   storeFile=/绝对路径/release.jks
#   storePassword=...
#   keyAlias=...
#   keyPassword=...
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

然后把该 APK 作为附件上传到对应的 GitHub Release。

> ⚠️ 密钥库丢失将**无法对已安装的包做覆盖更新**，请离线备份 `.jks` 与口令。

## 安全边界

- 仅执行只读命令（`su -c cat /sys/class/power_supply/...`），不写入 sysfs
- **未声明 `INTERNET` 权限**，不联网、不上传任何数据（关于页的作者头像也是随 APK 打包的，不走网络）
- 所有文件探测与读取均在 root 上下文完成
