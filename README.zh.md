<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="BYDMate 图标">

# BYDMate

### BYD DiLink 5.0 行程记录与能耗分析

[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![GitHub release](https://img.shields.io/github/v/release/AndyShaman/BYDMate?style=flat-square)](https://github.com/AndyShaman/BYDMate/releases)
[![Sponsor](https://img.shields.io/badge/赞助-FF69B4?style=flat-square&logo=githubsponsors&logoColor=white)](SUPPORT.md)

**真实能耗、GPS 路线、自动化、AI 分析 — 完全本地运行，无需云端。**

**中文** | [English](README.en.md) | [Русский](README.md)

[功能](#功能) | [截图](#截图) | [自动化](#自动化) | [AI 洞察](#ai-洞察) | [ABRP](#abrp--实时遥测) | [安装](#安装) | [编译](#从源码编译) | [赞助](SUPPORT.md)

</div>

---

## 关于

BYDMate 是一款运行在 BYD DiLink 5.0 车机（方程豹 钛3）上的 Android 应用。它可以记录行程、GPS 路线、BMS 真实能耗、充电记录，并提供 AI 驱动的驾驶分析。所有数据完全在车机上本地运行，无需云端，也不需要 Google Play Services。

原车车载电脑**低估能耗约 10-30%**。BYDMate 直接从 BMS（energydata SQLite）读取数据并显示真实数值。此外还提供了原车系统没有的数据：停车耗电、电芯均衡、行程费用、AI 洞察。

只有两项可选功能会向外发送数据：通过 OpenRouter 的 AI 洞察和发送到 A Better Route Planner 的实时遥测。两者默认关闭，需手动开启。

---

## 功能

| | 功能 | 描述 |
|---|---------|-------------|
| **BMS** | 真实能耗 | 来自 BMS（energydata）的数据，而非原车估算。以 25 公里滑动窗口显示趋势 |
| **GPS** | 行程记录 | GPS 路线、距离、速度 |
| **Charge** | 充电记录 | 自动记录 AC/DC 充电，周期与累计统计，支持手动添加和编辑 |
| **AI** | AI 洞察 | 通过 LLM（OpenRouter）进行驾驶分析 |
| **Idle** | 停车耗电 | 来自 energydata 的静态耗电监测 |
| **Bat** | 电池健康 | 温度、SoH（ 钛3）、电芯均衡、12V 电压 |
| **Map** | 路线地图 | 行程详情中使用 osmdroid（OpenStreetMap） |
| **Rules** | 自动化 | WHEN→THEN 规则：参数触发 → D+ 命令 |
| **Widget** | 悬浮窗 | 7 字段悬浮窗叠加在其他应用之上：SOC、续航、能耗+趋势、时间、车内温度、电池温度、12V |
| **Auto** | 自启动 | WorkManager 开机自启 |
| **CSV** | 数据导出 | 行程和充电记录导出为 CSV |

---

## 截图

### 仪表盘

<img src="docs/screenshots/dashboard.jpg" alt="仪表盘" width="800">

SOC 圆环周围有四个悬浮窗风格的字段：上方是行程时长、里程表和车内温度；下方是当前行程距离、预估续航和当前行程能耗（带趋势箭头）。颜色和趋势逻辑与悬浮窗保持一致，确保在主屏幕和其他应用之上信息阅读体验一致。

圆环下方：AI 洞察、电池健康小卡片（ 钛3 上的 SoH、温度、12V）、停车耗电、最近行程、周期筛选。

### AI 洞察（展开）

<img src="docs/screenshots/dashboard-insight-expanded.jpg" alt="AI 洞察展开" width="800">

*LLM 驾驶效率分析：能耗、趋势、电池、建议*

### 电池健康（展开）

<img src="docs/screenshots/dashboard-battery.jpg" alt="电池健康" width="800">

*温度、SoH（ 钛3）、12V 蓄电池、电芯均衡、电压*

### 行程

<img src="docs/screenshots/trips.jpg" alt="行程手风琴列表" width="800">

*月份 > 日期 > 行程 手风琴式列表，带筛选和能耗颜色标识*

### 自动化

<img src="docs/screenshots/automation.jpg" alt="自动化" width="800">

*WHEN→THEN 规则，条件和动作编辑器，触发配置*

### 设置

<img src="docs/screenshots/settings.jpg" alt="设置" width="800">

*电池、电价、货币、AI 设置（OpenRouter API）、数据导出*

---

## 自动化

**自动化**标签页允许你创建规则，通过 D+ API 来控制车辆。

### 工作原理

**当** 条件满足 **→ 则** 执行命令。

示例：
- SOC < 20% → 开启内循环
- 速度 > 0 → 关闭遮阳帘
- 车外温度 < 0 → 开启后视镜加热

### 能力

| | 描述 |
|---|-------------|
| **25 种触发器** | SOC、速度、温度、车门、车窗、胎压、驾驶模式、地理围栏、时段等 |
| **41 条命令** | 车窗（包括单独控制驾驶位和乘客位）、空调、灯光、门锁、天窗、后视镜。全部通过 D+ API |
| **8 种动作类型** | D+ 命令、静默/声音通知、启动应用、拨打电话、导航、URL、Yandex Music |
| **边沿触发** | 仅在 false→true 转换时触发（不是每 3 秒重复触发） |
| **冷却时间** | 可配置的两次触发之间的间隔 |
| **悬浮窗确认** | 动作执行前弹出"取消/执行"窗口，15 秒超时 → 自动取消 |
| **安全保护** | 车速 > 80 km/h 时禁止开窗，CAN/SHELL 命令已屏蔽 |
| **日志** | 完整的触发历史及结果 |
| **模板** | 6 个预设规则快速上手 |

### 逻辑

- **AND** — 所有条件必须同时满足
- **OR** — 任一条件满足即可
- **仅 P 档** — 规则仅在车辆处于 P 档时触发

---

## 悬浮窗

紧凑的 260×108 dp 悬浮窗，叠加在其他应用之上。在地图、媒体播放器和 BYD 应用中均可见。

<img src="docs/screenshots/widget-infographic.jpg" alt="悬浮窗图例：各区域说明" width="900">

### 显示内容

三行共七个字段。颜色：图标灰色，数值白色。边框和 SOC% 以状态颜色高亮（SOC 或 12V 中较差的那个）。

**顶行**（小字，13sp）：
- ⏱ **当前行程时长** — `N 分钟` 或 `X 小时 Y 分钟`（如 `47 分钟`、`1 小时 12 分钟`）。从点火开启开始计时，熄火结束。行车中的停车（开空调等人、等红灯）计入行程 — 只要电气系统仍在工作，计时器就不会重置
- 🚗 **车内温度** — °C，来自 DiPlus

**中间行**（大字，关键数值）：
- **SOC %**（18sp 加粗，彩色）— 动力电池电量。绿色 > 50%，黄色 20-50%，红色 < 20%
- **~N 公里**（28sp，白色）— 预估续航：`SOC × 电池容量 ÷ 基准能耗 × 100`。波浪号表示这是估算值，而非原车读数。基准能耗的计算方式见下方"续航"小节
- **X.X ↓**（18sp，趋势颜色）— **当前行程能耗**，kWh/100km，带趋势箭头（详见下文）

**底行**（小字，13sp）：
- 🔋 **电池温度** — °C，来自 DiPlus
- ⚡ **12V** — 蓄电池电压，V。正常 12.5-14.7 V，< 12.0 V = 黄色，< 11.7 V = 红色

### 能耗与趋势箭头（右侧区块）

右侧数字为当前行程能耗，单位 kWh/100km。计算方式为点火以来消耗的能量除以同期行驶的公里数。随着行驶，该数值会逐渐收敛到最终写入行程记录的值：熄火时悬浮窗显示的数值，就是最终存入行程卡片的值。

**前 2 公里**内，悬浮窗会从上一次行程的平均能耗平滑过渡到当前行程的能耗：300 米以下显示上次数值，300 米到 2 公里之间线性混合当前数值，2 公里之后仅显示当前数值。这样做避免了冷启动和加速时出现的 50-60 kWh/100km 的吓人尖峰：在行程还很短时，以上次行程已经稳定的平均值为准，只有当距离足够有代表性后，数值才会切换到本次行程的实际能耗。

**停车时**（熄火状态）悬浮窗显示上次完整行程的平均能耗 — 即该行程最后一刻显示的数值。

**续航** `~N 公里` 采用加权混合计算：50% 来自上次完整行程，30% 来自上上次，20% 来自再上一次（3 公里以下的短行程不计入，因为不具有代表性）。长途行驶时，当前行程最近 10 公里的能耗也会纳入混合：其权重从最初 3 公里时的零开始，到 25 公里时增长至一半。这样在驾驶风格变化时（市区末段上高速、高速回到市区），预估能快速跟上，但又不会因短途或开空调停车而频繁跳动。

**趋势箭头**在行驶 2 公里后出现，将 25 公里滑动平均值与你的常用风格（最近 10 次行程的平均值）进行比较：

- **↓ 绿色** — 驾驶比平时更节能
- **→ 白色**（直线）— 在常用范围内
- **↑ 黄色** — 能耗高于平时

箭头不会因每个红灯而跳动 — 有一定的惯性：要改变颜色，能耗必须明显偏离基准并保持至少一分钟。

**此处"一次行程"的定义**。一次点火循环：启动 → 熄火。行程中开空调停车自然会计入 — 多消耗的 kWh 会反映在分母中。短暂的停顿（红绿灯、重连）不会将行程拆分为两段。如果在高速上 DiLink 杀掉了应用，重启后会从真实的点火时刻继续计算，而非从零开始。

### 操作

- **点击** — 打开 BYDMate
- **长按（1.5 秒）** — 隐藏，直到再次打开 BYDMate
- **拖入垃圾桶** — 完全关闭
- 开关、透明度、重置位置 — 在 **设置 → 悬浮窗** 中

---

## 充电记录

**充电记录**标签页自动记录每次实际的电量补充：按月列出充电记录、周期和累计统计、AC 与 DC 筛选。并非每次插入充电枪都会生成记录：只有当 SoC 确实上升时才会创建记录。如果有人刚插上枪一分钟就拔掉，日志中不会有任何记录。

### 什么算一次充电

如果充电期间电池容量或 SoC 有所增长，就会写入记录。BYDMate 依次尝试三个数据源，取第一个可用的值：

1. **容量增量**（kWh），如果车载系统报告了更新后的值。
2. **SoC 增量**（活跃充电期间），按当前电池容量换算为 kWh。
3. **粗略估算**，根据 SoC 增量与标称容量计算，如果前两种方式均无数据。

如果 BYDMate 在充电期间运行，记录会立即出现。如果插枪发生在应用启动之前，或者车辆进入了深度睡眠，BYDMate 会在下次启动时发现 SoC 相比充电前有所跃升，然后补上记录。因此即使是在车库中离线充电，也会出现在日志中。

### 如何区分 AC 与 DC

充电类型按优先级由两个信号决定：

1. **车载系统的枪状态**：gun-state 2 = AC，3 或 4 = DC。某些 BYD 车型上该值不一定始终存在，此时进入下一步。
2. **会话平均功率**：大于 15 kW = DC，否则为 AC。AC 充电物理上不超过 11 kW，DC 充电站最低从 22 kW（CCS 慢充）起步，因此 15 kW 的阈值可以可靠地区分两种模式。

充电记录标签页有三个筛选器："全部"、"AC"、"DC"。

### 手动添加与编辑

如果某条记录缺失或数据看起来异常：

- **标签页顶部的 `+ 充电` 按钮**：手动添加充电记录，填写日期、时长、kWh、电价。
- **长按某条记录**：弹出"编辑"/"删除"菜单。编辑模式允许修改已有记录的任意字段。

> 此功能正在积极测试中。在 钛3 上运行稳定。在其他 BYD 车型上自动检测可能出现偏差：例如车载系统可能不报告功率或枪类型，导致 AC/DC 判断错误。遇此情况请手动编辑记录，如有可能请将日志发送至 [Issues](https://github.com/AndyShaman/BYDMate/issues)。

---

## 行程数据来源

BYDMate 支持两种行程数据后端，可在 **设置 → 行程数据来源** 或首次启动向导中切换。

<img src="docs/screenshots/data-source-toggle.jpg" alt="行程数据来源切换" width="800">

| 模式 | 适用车型 | 读取内容 |
|------|-----------------|--------------|
| **BYD energydata** |（方程豹 钛3）及其他内置 BMS `energydata` 数据库的车型 | BYD SQLite：精确能耗（BMS）、里程、时长、充电 |
| **DiPlus TripInfo** | 宋及其他**没有**内置 energydata 的车型 | DiPlus 数据库：行程列表、SOC 起止值、平均速度 |

**如何选择：** 如果行驶 2-3 趟后行程列表仍然为空 — 请切换模式。 钛3 需要 energydata（更精确）；宋及类似车型使用 TripInfo（唯一可用的数据源）。

在 `DiPlus TripInfo` 模式下，能耗通过 SOC 差值计算 — 比 BMS 大约粗略 1 kWh/100km，但这已是在没有其他数据源的情况下的最佳方案。

---

## 电池健康（SoH）

SoH（State of Health，健康状态）是动力电池的"健康"百分比，由车辆的车载系统按其内部算法计算。

在 **BYD  （方程豹 钛3）** 上，BYDMate 直接从车载系统读取此值并显示在"电池健康"卡片中。这是**来自车辆的真正 SoH**，而非根据 SoC 差值估算的数值：BYDMate 只是读取车辆自己记录的数据。

在其他 BYD 车型上，对此值的访问尚未确认，因此 SoH 不显示。卡片中的其他指标（电池温度、12V、电芯均衡、最低/最高电压）在所有安装了 DiPlus 的车型上均可用。

如果你的车通过 DiPlus 暴露了 SoH 并且你希望帮助添加支持，请提交 [Issue](https://github.com/AndyShaman/BYDMate/issues)，附上车型和出厂年份。

---

## 如何开启 SoH 和自动充电记录（ 钛3）

 钛3 上的 SoH 和自动充电记录通过额外的"系统数据"模式工作，该模式从车辆的车载系统读取数据。一次性开启：

1. 打开 **设置**，打开 **"系统数据（实验性）"** 开关。
2. DiLink 将弹出系统 **ADB 调试**权限对话框，显示密钥指纹。点击 **"允许"（Allow）**，勾选 **"始终允许来自此计算机的连接"（Always allow from this computer）**，这样 DiLink 不会在每次应用启动时重复询问。
3. 之后，SoH 会出现在"电池健康"卡片中，充电记录开始以真实的 kWh 数值自动记录。

如果不开启此模式，BYDMate 的其他功能（行程、能耗、悬浮窗、自动化）照常工作。只有 SoH 和自动充电记录不可用。

> 此模式标记为"实验性"，因为仅在 钛3 上进行了测试。在其他 BYD 车型上对这些数据的访问尚未确认。

---

## 如果你不是 钛3

BYDMate 在（方程豹 钛3）上开发和测试。在其他 BYD 车型上大多数功能仍然可用，但存在一些差异。首次启动前请检查：

- **行程数据来源**：对于没有内置 BMS `energydata` 数据库的车型（宋、元及类似车型），请在设置或首次启动向导中切换到 **DiPlus TripInfo**。参见上方"行程数据来源"部分。
- **电池容量**：默认为 72.9 kWh（ 钛3）。前往 **设置 → 电池** 设置你自己的容量。例如：Atto 3 = 60.5 kWh，Seal AWD = 82.5 kWh，汉 EV = 85.4 kWh。否则续航和行程费用计算将不准确。
- **SoH**：仅在 钛3 上显示。在其他车型上"电池健康"卡片正常显示，但不含 SoH 字段。
- **充电记录**：AC/DC 判断算法针对 钛3 调校。在其他车型上记录可能出现延迟或功率不准确，尤其是 DC 充电。当自动判断不准时，请使用手动添加和编辑。
- **自动化和悬浮窗**：在所有车型上表现一致，因为它们使用 DiPlus API。

如果有功能不工作或显示异常数据，请提交 [Issue](https://github.com/AndyShaman/BYDMate/issues)，附上你的车型和 DiLink 固件版本。我们需要这些报告来扩大支持范围。

---

## 目标设备

| 参数 | 值 |
|----------|-------|
| 平台 | DiLink 5.0（Android 12，API 32） |
| 芯片 | Snapdragon 780G |
| 屏幕 | 15.6" 横屏，1920x1200 |
| GMS | 无（AOSP，不含 Google Play Services） |
| 测试车型 | BYD  钛3（方程豹 钛3） |

---

## 工作原理

```
BYD energydata (BMS SQLite)  →  HistoryImporter    →  Room DB  →  Compose UI
DiPlus API (localhost:8988)  →  TrackingService     ↗     ↓
Android LocationManager      →  TripTracker (GPS)   ↗   AI (OpenRouter)
DiPlus sendCmd API           ←  AutomationEngine   ←  Rules (Room DB)
```

| 数据 | 来源 |
|------|--------|
| 能耗、里程、时长 | BYD energydata (BMS) |
| SOC、速度、温度 | DiPlus API (`getDiPars`) |
| 电芯电压、12V | DiPlus API |
| GPS 坐标 | Android LocationManager |
| AI 分析 | OpenRouter API（可选） |
| 车辆控制 | DiPlus sendCmd API（自动化） |

**无需 OBD 适配器** — BYD 会屏蔽第三方 OBD 设备。BYDMate 使用与 BYD 内置应用相同的 API。

---

## 安装

### 1. 开启 ADB

没有 ADB 时，BYDMate 运行在基础模式。以下功能需要 ADB 调试：

- **电池健康（SoH）** — 来自 BMS 的精确数值，而非横线占位。
- **自动充电日志** — 应用自动记录充电开始和结束。没有 ADB 则只能手动添加充电记录。
- **自动化** — 触发器和动作（车窗、空调、灯光控制等）。没有 ADB 则自动化标签页不可用。

没有 ADB 你仍然可用：行程和里程记录、能耗、悬浮窗、AI 洞察。

要启用这些功能，安装 BYDMate 后打开 **设置 → "系统数据（实验性）"**。DiLink 会弹出一次"允许 ADB 调试"对话框 — 点击 **允许（Allow）** 并勾选 **"始终允许来自此计算机的连接"（Always allow from this computer）**。

- **DiLink 3 / 4** — 可自行开启 ADB：安装 [BydDevelopmentTools](https://disk.yandex.by/d/e3gEnY9P2Y9_fQ)，进入 *设置 → 版本管理*，连续点击 *恢复出厂设置* 10 次，启用 *USB 连接时开启调试模式* 和 *无线 ADB 调试开关*。在更新版本的 DiLink 3/4 固件上，ADB 可能和 DiLink 5 一样被锁定 — 此时请按以下方式处理。
- **DiLink 5.0** — ADB 调试**已被锁定**，只能从中国远程解锁。通常通过 **淘宝** 卖家完成（搜索 `DiLink 5.0`，国内约 ¥40 / 国外约 ¥80，支付宝付款）。卖家通过你提供的二维码远程打开工程菜单，之后 ADB 即可正常启用。

  详细步骤：[PDF 指南（俄语）](docs/guides/dilink5-adb-activation-ru.pdf) — 已包含在仓库中。

### 2. 安装 DiPlus（D+）

车机必须安装 **[DiPlus (D+)](https://drive.google.com/file/d/1ndKgzh-HWRPrPw2eTbKh9pwhdDwYJ0Ug/view?usp=drive_link)** — 这是访问车辆数据的桥接应用。

最简单的方式（无需 ADB）：

1. 从上方链接下载 APK
2. 传输到 U 盘（或直接通过 DiLink 浏览器下载）
3. 在 DiLink 文件管理器中打开文件并安装
4. 如有提示，允许安装未知来源应用

通过 ADB 的替代方式（如果已在第 1 步开启）：

```bash
adb connect <DiLink-IP>:5555
adb install DiPlus.apk
```

DiLink 的 IP 地址可以在车机的 Wi-Fi 设置中找到。

### 3. 安装 BYDMate

1. 从 [**Releases**](https://github.com/AndyShaman/BYDMate/releases) 下载 BYDMate APK
2. 传输到 DiLink：通过 U 盘、网络或 ADB（`adb install BYDMate.apk`）
3. 如有提示，允许安装未知来源应用

### 4. 首次启动

1. 打开 BYDMate — 出现设置向导
2. 授予 **位置** 和 **存储** 权限（用于 GPS 和读取 energydata）
3. 选择**行程数据来源** —  钛3 选 `BYD energydata`，宋及其他没有内置 BMS 数据库的车型选 `DiPlus TripInfo`（参见[上方说明](#行程数据来源)）
4. 设置**电价**（用于行程费用计算）

### 5. 后台运行

**重要：** 关闭 BYDMate 的"禁止后台应用"，否则 DiLink 会杀掉应用：

<img src="docs/screenshots/dilink-whitelist.jpg" alt="禁止后台应用 — BYDMate 设为 OFF" width="600">

*DiLink > 设置 > 通用 > 禁止后台应用 > BYDMate = **OFF***

### 6. 配置（可选）

在 **设置** 中可以修改：
- **电池容量** — 默认 72.9 kWh（ 钛3）
- **电价** — 家庭充电（AC）和快充（DC），货币
- **能耗阈值** — 颜色标识的边界值（绿色/黄色/红色）

---

## AI 洞察

BYDMate 可以通过 AI（LLM）分析你的驾驶统计数据。这是可选功能 — 应用不开启此功能完全可用。

### 设置

1. 在 [OpenRouter](https://openrouter.ai/) 注册（免费）
2. 在 OpenRouter 控制台创建 **API Key**（Keys 部分）
3. 在 BYDMate 中打开 **设置** → **AI 洞察** 区域
4. 将 API 密钥粘贴到"OpenRouter API Key"
5. 点击 **"选择模型"** — 显示可用 LLM 列表（含免费模型）
6. 点击 **"保存并获取洞察"**

### 分析内容

AI 接收 7 天和 30 天的匿名统计数据，并返回：

- **事实** — 基于真实数据计算的指标（能耗趋势、短途行程占比、停车耗电）
- **洞察** — LLM 提供的关联分析、异常发现和行为建议

请求**每天发送一次**。结果在本地缓存。不会传输任何个人数据（GPS、路线）— 仅发送聚合统计数据。

---

## ABRP — 实时遥测

BYDMate 可以通过官方 Iternio Telemetry API 将实时车辆数据发送到 [A Better Route Planner](https://abetterrouteplanner.com/)（ABRP）。ABRP 利用这些数据根据你真实的电池状态来更新路线规划和剩余续航，而非使用平均理论值。

此功能**可选**，默认关闭，需在设置中手动开启。

### 如何获取 Token

ABRP 使用"通用实时数据 Token（Generic Live Data Token）"— 车库中每辆车一个独立的 Token：

1. 打开 [abetterrouteplanner.com](https://abetterrouteplanner.com/) 并登录。
2. 进入车库，选择你要获取实时数据的车辆。车辆必须**已保存在车库**中，否则 Token 不会出现。
3. 齿轮图标 → **"车辆设置"** → **"数据"** → **"连接实时数据"**。
4. 在提供商列表中选择 **"Generic"**，点击 **"关联"（Link）**。显示一长串 Token 字符串 — 这就是 `User Token`。

**如果列表中没有"Generic"**：在 ABRP 车库中将车辆型号代码切换到任何常见的 BYD 车型（如 BYD Atto 3 或 BYD Seal），保存后 Generic 即会出现。关联 Token 后，可以将型号代码改回。

### 在 BYDMate 中设置

1. **设置** → **"ABRP — 遥测"** 区域。
2. 将获取的 Token 粘贴到 **"来自 ABRP 的实时数据 Token"** 字段。
3. 可选：ABRP 车型代码（如果你知道在 ABRP 库中你车辆的确切代码）和发送间隔（5-120 秒，默认 12 秒 — Iternio 推荐值）。
4. 点击 **"保存 ABRP"**，然后打开 **"实时数据 → A Better Route Planner"** 开关。未保存 Token 时开关不可用。
5. DiLink 上的 ABRP 应用（或手机浏览器）现在可以看到实时的 SOC、功率、温度、充电状态。

### 发送的内容

仅发送聚合的车辆指标，不含标识符：

- **SOC** — 当前动力电池电量百分比
- **Speed** — 速度，km/h
- **Power** — 当前牵引功率（充电时为负值，按 Iternio 要求）
- **Battery / cabin / exterior temp** — 电池、车内和车外温度
- **Capacity** — 标称电池容量
- **Odometer** — 里程，km
- **Tire pressures** — 四个轮胎的气压
- **is_charging / is_parked** — 状态标志
- **is_dcfc / kwh_charged** — 充电站类型（DC vs AC）和当前会话的 kWh（仅在开启"系统数据"模式时发送 — 否则这些字段直接省略）
- **soh** — 真实电池 SoH（ 钛3，需开启"系统数据"模式）

### 不会发送的内容

- **不传输 GPS 坐标。** ABRP 作为独立的 Android 应用直接在 DiLink 上运行，会自己从操作系统读取位置。通过第三方渠道重复发送坐标只会将位置泄露给外部服务器。
- 也不会发送：VIN、设备标识符、行程历史、路线、用户设置。

### ABRP 如何计算剩余续航

ABRP 结合其车型库模型与遥测数据（当前 SOC、电池温度、行驶速度、风力、道路剖面、海拔）来得出预测。BYDMate 不会发送自己计算的"预估续航"— ABRP 有自己更精确的路线感知估算，还会考虑天气和海拔因素。

---

## 从源码编译

```bash
# 要求：JDK 17，Android SDK 34
git clone https://github.com/AndyShaman/BYDMate.git
cd BYDMate
./gradlew assembleDebug
```

---

## 技术栈

- **Kotlin** 2.1 + **Jetpack Compose** + Material 3
- **Room** (SQLite) + **Hilt** (DI) + **OkHttp**
- **osmdroid** (OpenStreetMap) + **Coroutines/Flow**
- Min SDK 29 / Target SDK 29 / Compile SDK 34

---

## 致谢

- **[BYD Trip Info](https://www.byd-seal-forum.de/forum/thread/1811-byd-trip-info-app/)** (`org.jayb.bydapp`) by jayb — 原始 DiLink 行程应用，BYDMate 的灵感来源
- **[DiPlus](https://www.dilink.cn/)**（迪加）by Van Design — 车辆数据桥接应用

---

## 赞助项目

本项目非商业性质，仅为爱好开发。如果你想表示感谢，详见 [SUPPORT.md](SUPPORT.md)。如果不想，也感谢你的信任。

---

## 许可证

**GPLv3**，含附加署名条款。
详见 [LICENSE](LICENSE)。

Copyright (C) 2026 [AndyShaman](https://github.com/AndyShaman)
