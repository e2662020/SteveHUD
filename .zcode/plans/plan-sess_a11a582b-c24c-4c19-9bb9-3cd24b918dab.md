# SteveHUD 实施方案

## 定位（已确认）

SteveHUD 是一套**赛事转播图形系统**，不是某个赛制的 HUD。参照奥运会转播的分工：图形包装共用，数据由各项目自己的计分/计时系统喂进来。

已确认的六项决策：

| 决策 | 结论 |
|---|---|
| 赛事种类 | 多赛制 + 可自定义；v1 必做 PVP 两队对抗、队伍型起床战争、竞速类 |
| 档案来源 | 人工维护（选手/队伍/地区/编号），与自动成绩分离，两条管道互不阻塞 |
| 成绩来源 | 自动采集 + 命令/API 推送；对接原版记分板作为零配置兼容通道 |
| 模组版本 | 只出 **1.21.1 / 1.21.4 / 1.21.8 / 1.21.11** 四个 jar；插件一个 jar 通吃全部 1.21.x |
| 游戏内 GUI | 用现成声明式 UI 库；ClothConfig 只做客户端个人偏好，不涉及内容编辑 |
| 版面编辑 | 网页编辑器做深度编辑（拖拽/图层/属性/动画曲线），游戏内只做预设选择与位置微调 |
| 图形服务位置 | **只由模组在本机开** HTTP + WebSocket，OBS 指向 127.0.0.1 |

## 架构

```
MC 服务端                                   直播机（本机）
┌─────────────────────┐                    ┌──────────────────────────────────┐
│ Spigot/Paper 插件    │                    │ Fabric 模组                       │
│                      │  插件通道(JSON)    │  ├─ 收包 → 赛事状态镜像            │
│ 赛事状态机            │ ─────────────────► │  ├─ 游戏内 HUD 渲染（布局引擎）     │
│ 数据源管道            │                    │  ├─ 本机 HTTP :8787 静态+REST      │
│ 档案管理              │                    │  ├─ 本机 WebSocket :8787 实时推送  │
│ CSV/JSON/SQLite 导出  │                    │  ├─ ClothConfig 客户端偏好         │
└─────────────────────┘                    │  └─ obs-websocket 客户端(可选)     │
                                            └───────────┬──────────────────────┘
                                                        │ 127.0.0.1:8787
                                            ┌───────────▼──────────────────────┐
                                            │ OBS 浏览器源：/overlay/*.html     │
                                            │ 网页编辑器：/editor/              │
                                            └──────────────────────────────────┘
```

## 仓库结构

```
SteveHUD/
├── settings.gradle.kts      # protocol, plugin, mod:{common,mc1.21.1,mc1.21.4,mc1.21.8,mc1.21.11}
├── buildSrc/                # 约定插件: stevehud.common / stevehud.mod
├── protocol/                # 纯 Java、零 Minecraft 依赖（插件与模组共享）
│   ├── model/               # Event, Match, Side, Competitor, StatValue, LayoutDocument
│   ├── codec/               # Gson 信封编解码 + 版本协商 + 分片重组
│   └── bind/                # 数据绑定路径求值器
├── plugin/
│   ├── source/              # 数据源: ScoreboardSource / CommandSource / ApiSource
│   ├── collector/           # 采集器: Duel / Bedwars / Race
│   ├── command/             # /stevehud 命令树
│   ├── net/                 # 通道下发: 快照+增量+revision+分片
│   ├── profile/             # 档案: 选手/队伍/地区/旗标
│   └── export/              # CSV / JSON / SQLite（原子写）
├── mod/
│   ├── common/              # 布局引擎、渲染抽象接口、本机服务、obs 客户端、ClothConfig
│   └── mc1.21.{1,4,8,11}/   # 各版本 impl: HUD 挂载点、绘制服务、字体、皮肤、mixin
└── web/                     # pnpm + Vite + Vue 3 + GSAP；构建产物打进 mod/common 的 resources
    ├── shared/              # 协议客户端、绑定求值、动画运行时
    ├── overlay/             # 赛事版面包（选手条/比分牌/成绩表/排名榜/计时器/跑马灯）
    └── editor/              # 可视化版面编辑器
```

## 数据模型（赛制无关是核心）

```
Event  赛事      id, 名称, 项目类型, 状态
 └ Match 场次     id, 名称("男子100米决赛"), 阶段, 状态(未开始/进行中/已结束)
    └ Side 参赛方 id, 名称, 缩写, 主色, 队标, 地区/旗标, 分数, 排名, 状态
       └ Competitor 选手  id, 游戏ID, 真名, 地区, 号码, 队伍, 头像, stats
          └ stats: Map<String, StatValue>   ← 开放键值，不硬编码
```

**关键设计：统计字段是开放的 key-value**，所以"起床战争的床状态/队分"、"竞速的检查点/分段时间/进度"、"决斗的回合/血量/装备"都能表达，加赛制不需要改协议。版面通过绑定路径取值，例如 `${sides[0].competitors[1].stats.kills}`。

## 协议

- 单通道 `stevehud:main`，JSON 信封：`{v, rev, type: snapshot|delta|layout|command, payload}`
- 握手：模组发 `hello{protocolVersion, capabilities}`，插件回 `snapshot{rev, 全量}`；之后只发 delta
- `rev` 单调递增，模组检测到跳号就请求重发快照 → 重连/后加入自动补齐
- 超过单包上限时按 `chunk{i,n,id}` 分片重组
- 版面文档走独立 type，支持热重载

## 服务端"高度可自定义"的落点

统一 `DataSource` 接口 + **映射层**。映射层把"记分板 objective 名字"或"采集器事件"映射到统计字段路径，这是可自定义的入口：

1. `ScoreboardSource` — 读原版 objective/team，零配置接入现有服务器
2. `CommandSource` — `/stevehud set <路径> <值>`，人工或脚本推送
3. `ApiSource` — 外部系统 HTTP POST 或插件 API 调用
4. 内置采集器（各自可开关）：Duel（击杀/血量/回合）、Bedwars（床状态/队分/击杀）、Race（检查点/计时/进度）

## 模组侧要点

- **布局引擎**：版面文档 → 元素树 → 渲染。元素类型：矩形/圆角/渐变/九宫格/文本/贴图/玩家头像/进度条/计时器/表格/跑马灯；属性里写绑定路径
- **动画**：淡入淡出/滑入/缩放/数字滚动/打字机/跑马灯 + 缓动曲线，由状态变化触发
- **渲染抽象接口**（跨版本隔离）：`IDrawService`（fill/drawText/drawTexture/ninePatch/clip）、`IMatrixStack`、`IFont`、`ISkinProvider`、`IHudHook`、`IWindow`、`IKeyBind`。这四个版本的 HUD 挂载点、字体 API、矩阵栈、贴图绘制都变过，全部关在各版本 impl 里
- **本机服务**：`com.sun.net.httpserver` 提供静态资源 + REST，Java-WebSocket 提供实时推送，只绑定 127.0.0.1，端口可配
- **保活自愈**（因图形服务在模组侧而成为硬需求）：通道断线自动重连 + 快照补齐；本机服务异常自动重启；面板显示链路状态
- **obs-websocket v5 客户端**（可选）：按赛事事件切场景、改文本源、触发回放
- **ClothConfig**：客户端个人偏好——HUD 位置/缩放/透明度/元素显隐/按键/端口/主题。用 AutoConfig 注解式，ModMenu 挂入口，附带"打开网页编辑器"按钮

## 网页侧要点

- `shared/`：协议客户端（自动重连 + rev 补齐）、绑定求值、GSAP 动画运行时、旗标与字体资源
- `overlay/`：每个版面是独立 HTML，可单独作为 OBS 浏览器源，也可组合。首批做：选手信息条（lower third）、对阵比分牌、成绩表、排名榜、计时器、跑马灯
- `editor/`：拖拽画布 + 图层树 + 属性面板 + 动画时间轴 + 实时预览 + JSON 导入导出
- 技术栈：pnpm + Vite + Vue 3 + GSAP + Naive UI
- 构建产物输出到 `mod/common/src/main/resources/stevehud/web/`，由模组本机服务器托管

## 里程碑

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| **M0** 环境与骨架 | 装独立 JDK 21；建仓库、buildSrc 约定插件、protocol 空壳 | 插件与模组能互相握手并收到一条 hello |
| **M1** 协议与数据内核 | 数据模型、信封编解码、分片、revision 补齐、绑定求值器 | 单测：大快照分片重组、跳号补发 |
| **M2** 服务端数据源与命令 | 赛事状态机、`/stevehud` 命令树、四类数据源、档案、CSV/JSON 导出 | 在 Paper 1.21.4 测试服上跑通一场模拟比赛 |
| **M3** 模组收包与游戏内 HUD | 四版本 impl、渲染抽象、布局引擎、数据绑定、动画 | 四个版本各自能显示同一份布局 |
| **M4** 本机 Web 服务与版面包 | HTTP+WS + 断线重连、赛事版面包、OBS 浏览器源跑通 | OBS 里实时看到比分随比赛变化 |
| **M5** 网页编辑器 | 拖拽/图层/属性/动画曲线/预览/导入导出，保存后模组热重载 | 网页改动画，游戏内 1 秒内生效 |
| **M6** 三大赛制预设 | PVP 两队 / 起床战争 / 竞速：采集器 + 专用版面 + 预设样式 | 三种赛制各完整跑一场 |
| **M7** 集成与打磨 | obs-websocket、导出完善、多版本打包、文档 | 四个 jar + 一个插件 jar 发布 |

## 风险与对策

| 风险 | 对策 |
|---|---|
| **机器上没有独立 JDK 21**（只有 MC 启动器自带的，会被启动器覆盖；`JAVA_HOME` 未设） | M0 第一步装 Temurin 21 并设 `JAVA_HOME`，Gradle 用 toolchain 锁定 |
| **UI 库版本覆盖不一致**——1.21.4 是险点（ModernUI 已停更该版本），OneConfig 全中但会拖进 Compose Multiplatform + Kotlin | M0 设一个决策门：逐个核实候选库对 1.21.1/1.21.4/1.21.8/1.21.11 的实际支持，选覆盖全的那一个；若无人全覆盖，则**屏幕类 UI 用库、HUD 叠加层用一层薄图元绘制**（HUD 必须由网页编辑器产出的布局驱动，这一点任何通用 UI 库都不提供，不是"手搓框架"） |
| 图形服务在模组侧 → 模组崩溃即断流 | 保活自愈做成 M4 的必做项而非优化项：通道重连、服务自重启、链路状态可视化；插件侧同时做 CSV/JSON 落盘作为灾难备份 |
| 插件通道包体限制 | M1 分片 + 重组，单测覆盖 |
| 玩家头像：网页拿不到游戏内皮肤 | 模组侧做本地代理缓存（拉 Mojang/crafatar 后落盘并缓存），网页只请求本机地址，避免直播时依赖外网 |
| 四个 MC 版本 = 四份渲染适配 | 渲染抽象接口从 M3 一开始就按版本断点设计；每个版本只允许改 impl 目录 |
| 游戏内字体表现力远不如网页 | 明确分工：赛事级观感以网页为准，游戏内定位为"干净可读 + 关键动画" |

## 待确认的默认假设（有异议就推翻，否则按此执行）

1. **插件用 Paper API 编译，但只用 Spigot 兼容的 API 面**，`api-version: '1.21'`，这样 Spigot 和 Paper 都能装。你本机测试服是 Paper 1.21.4，且 Paper 构件已在 Gradle 缓存里，Spigot 构件需要 BuildTools 现场构建（很慢）。
2. **只做 Fabric**，NeoForge 不在范围内（与你现有生态一致）。
3. **模组与插件用 Java 21**。若最终选定的 UI 库强制要求 Kotlin（如 OneConfig），则只在该库相关模块引入 Kotlin，其余保持 Java。
4. **本机服务默认端口 8787**，仅绑定 127.0.0.1。
5. **前端默认栈** pnpm + Vite + Vue 3 + GSAP + Naive UI。

## 下一步

先做 M0：装独立 JDK 21 → 建仓库骨架 → 打通"插件发一条 hello、模组收到并打印"的最小闭环。这个闭环一旦跑通，后面所有工作都是在它上面加内容；如果 M0 卡在 UI 库版本覆盖上，我会立刻回来告诉你，而不是硬撑。