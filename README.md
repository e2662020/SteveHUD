# SteveHUD

赛事转播图形系统 for Minecraft 1.21.x。

不是"某个赛制的 HUD"，而是一套**共用的图形包装 + 可插拔的数据源**，参照奥运会转播的分工：
图形包装是共用的，数据由各项目自己的计分/计时系统喂进来。目前规划内建 PVP 两队对抗、
队伍型起床战争、竞速三类赛制。

## 组成

| 部分 | 说明 |
|---|---|
| `plugin/` | 服务端插件。权威赛事状态 + 数据源管道。**一个 jar 通吃全部 1.21.x** |
| `mod/` | Fabric 客户端模组。由版面文档驱动的游戏内 HUD + 本机图形服务。**每个 MC 版本一个 jar** |
| `protocol/` | 线上格式 + 版面文档模型 + 内置版面包。零 Minecraft 依赖，三处共用同一份定义 |
| `mod/client-mc/resources/stevehud/web/` | 赛事版面包与版面编辑器，无构建步骤，由模组本机服务器托管 |

## 版面文档：一份 JSON，三处共用

这是本项目的核心。**版面的唯一真相是一份 JSON**（`config/stevehud/layout.json`），
三个渲染器都读它：

| 渲染器 | 在哪 |
|---|---|
| 游戏内 HUD | 每个装了模组的客户端 |
| 网页包装 | OBS 浏览器源 → `http://127.0.0.1:8787/overlay/` |
| 版面编辑器 | `/shude` 或 `http://127.0.0.1:8787/editor/` |

文档里每个元素写的是**锚点 + 偏移 + 尺寸 + 缩放 + 配色 + 动画**，坐标一律是 1920×1080
设计像素，渲染器按实际视口算一个统一比例（`--u`）。所以换个分辨率、换个 OBS 画布，
版面是**等比缩放**而不是"长得差不多"。

于是三件事成为可能：

- **全服务器统一**：观感来自文档而不是各人设置，改一次所有人都变。
- **所见即所改**：编辑器的画布不是示意图，是嵌在 iframe 里的**真实包装页**，
  拖拽框的几何直接从那个 iframe 的 DOM 读回来，不重算一遍锚点算术。
- **换版面包**：内置三份（`/shudlayout olympic`），也可以手工改 JSON，
  约 1 秒内所有画面自动跟随。

元素的宽度还驱动**内部字号**：把框拖宽，框里的字、间距、圆点等比跟着变，而不是固定字号
配一个大空框（`--k` = 文档框宽 ÷ 该类型参考宽）。

### 两种范围，两种权限

"改版面"其实是两件事，权限完全不同。分清楚这一条，整套权限就自洽了：

| 范围 | 改的是什么 | 谁受影响 | 权限 |
|---|---|---|---|
| **本机版面** | 你这台机器的 `config/stevehud/layout.json` | 只有你这台机器的游戏内 HUD 与 OBS 包装页 | **不需要权限** |
| **全服版面** | 服务端持有的那一份（`/stevehud layout preset`） | 所有客户端 + 所有包装页 | **`stevehud.admin`** |

判据是**影响范围**，不是重要程度：本机版面改的是你自己的包装工作，不影响任何人，
锁在管理员后面等于把个人偏好锁起来；全服版面会改别人的屏幕，所以判在能强制的地方——服务端。

**优先级：全服版面 > 本机版面。** 默认**没有**全服版面，各客户端画自己的；
管理员显式设定后才接管。默认不设定是刻意的：一上来就强制一份，本机版面就永远用不上了，
而 OBS 包装恰恰是在本机版面上做的。

全服版面生效时，编辑器会明说"你改的是本机版面，暂时看不到"——不告诉运营这一点，
他会以为工具坏了，然后反复拖拽一个其实正常工作的东西。

## 已确认的决策

| 决策 | 结论 |
|---|---|
| 赛事种类 | 多赛制 + 可自定义；v1 必做 PVP 两队、起床战争、竞速 |
| 档案来源 | **人工维护**（选手/队伍/地区/编号），与自动成绩分离，两条管道互不阻塞 |
| 成绩来源 | 自动采集 + 命令/API 推送；对接原版记分板作为零配置兼容通道 |
| 模组版本 | **1.21.1 / 1.21.4 / 1.21.8 / 1.21.11**（策展，不追全部 12 个 1.21.x） |
| 游戏内 GUI | **不需要第三方 UI 库**：设置界面用 ClothConfig，HUD 是自己的绘制层（只用 `fill`/`fillGradient`/`drawText` 这些四版本一致的调用） |
| 版面编辑 | **全部在网页端**（拖拽/缩放/图层/属性/动画/配色/预设/实时预览）；游戏内只做预设切换 `/shudlayout` |
| 图形服务位置 | **只由模组在本机开** HTTP + **SSE**（不是 WebSocket），OBS 指向 `127.0.0.1` |
| 实时通道 | **SSE**。数据是单向的（状态推给浏览器，编辑器写入走普通 POST），SSE 覆盖得了，且 `curl` 就能诊断 |
| 编辑器 UI 规范 | **Brutalism + Minimalist Monochrome**：零圆角、无阴影无渐变、纯黑底、层次只靠 1px 边框 + 中性灰阶 + 反白；强调色取自被编辑的版面包 |
| 不引入网络请求 | 不加载网络字体、不用 CDN。转播机常离线，字体栈优先本机已安装的同名字体 |
| OBS 集成 | 通过 **obs-websocket 把实时数据写进 OBS 的文本源**（v5 协议的 `SetInputSettings`，写 `text` 字段）。这是"数据源 → OBS 文本"的直连路径 |
| 版面编辑器入口 | 客户端命令 `/stevehudeditor`，**按管理员权限放行**（服务端同步下来的 op 等级），在默认浏览器打开本机编辑器地址 |

### 两条并存的展示路径

| | obs-websocket 文本源 | 浏览器源（HTML 包装） |
|---|---|---|
| 数据怎么到画面 | 模组直接改写 OBS 里文本源的 `text` | 网页订阅本机 WebSocket，自己渲染 |
| 表现力 | 纯文本，字体/描边受限于 OBS 文本源 | 无上限：真字体、动画、图形、赞助商贴片 |
| 搭建成本 | 极低，OBS 里建个文本源就能用 | 要写网页 |
| 适合 | 快速上手的比分、计时、选手名 | 电竞赛事级包装 |

两条都保留：文本源解决"五分钟能播"，浏览器源解决"要好看"。
数据只从模组出一次，两条路径读同一份。


## 为什么模组要策展版本

1.21.x 的渲染 API 在 **1.21.2 / 1.21.5 / 1.21.6** 断过三次，导致没有任何一家现成 UI 库
覆盖全部 12 个版本。实测（Modrinth，2026-09）：

| UI 库 | 1.21.1 | 1.21.4 | 1.21.8 | 1.21.11 | 结论 |
|---|---|---|---|---|---|
| owo-lib | 0.13.0-alpha.15 | 0.12.20 | 0.12.23 | 0.13.0 | 全覆盖 |
| OneConfig | v1.1.17 | v1.1.17 | v1.1.17 | v1.1.17 | 全覆盖（需 Kotlin + Compose） |
| Modern UI | 3.13.0.1 | 3.12.0.3 | 3.13.0.3 | **无** | 出局 |

插件侧完全不受影响：Bukkit API 在一个大版本内稳定，所以跨版本的代价 100% 压在模组这一侧。

## 模块结构

```
SteveHUD/
├── protocol/            # 零 MC 依赖：信封编解码、分片重组、消息模型
├── plugin/              # Spigot/Paper 插件
├── mod/
│   ├── common/          # 零 MC 依赖：链路状态机、赛事状态镜像、布局模型（待填充）
│   ├── client-mc/       # 碰 MC API 但跨版本不变：payload、客户端入口、fabric.mod.json
│   ├── mc1.21.1/        # 只放该版本独有的 impl
│   ├── mc1.21.4/
│   ├── mc1.21.8/
│   └── mc1.21.11/
└── web/                 # 待开始
```

**关于 `client-mc`**：payload 与客户端入口必须引用 Minecraft 类，无法放进 `mod/common`；
但它们在四个版本里字节相同。所以单独一层，由四个 Loom 模块通过
`sourceSets.main.java.srcDir` 共同编译——**不存在四份副本，也就不会漂移**。
版本差异全部通过各模块的 `gradle.properties` 在构建期注入 `fabric.mod.json`。

`mod/common` 与 `protocol` 保持零 MC 依赖是一条**承重性质**，不是巧合：正因为它们
不引用任何 Minecraft 类，四个版本模块才能原样共享，彻底不需要 `namedElements`
跨版本重映射或 shadow 合并那套脆弱链路。

## 构建

需要一个 JDK 21。仓库已配置 foojay toolchain 自动下载，无需手动安装：

```bash
sh gradlew build                      # 全部模块
sh gradlew :protocol:test             # 协议单测
sh gradlew :mod:mc1.21.4:build        # 单个 MC 版本
sh gradlew :plugin:shadowJar          # 插件（产物 plugin/build/libs/SteveHUD-*-all.jar）
sh gradlew printVersionMatrix         # 打印四个版本模块的依赖矩阵
```

### 快速验证通道

配置四个 Loom 模块会让 Loom 为每个版本下载并 remap 一份 Minecraft，首次数分钟。
改 `protocol` / `mod:common` / `plugin` 时用 `-PskipMods` 跳过它们，反馈从分钟级降到秒级：

```bash
sh gradlew -PskipMods :protocol:test :mod:common:test :plugin:shadowJar
```

这两个模块加插件都不引用任何 Minecraft 类，所以跳过 Loom 不会漏掉任何检查。
**改完先跑这条，绿了再去跑全量。**


## 验证握手（M0 验收）

1. 部署插件：把 `plugin/build/libs/stevehud-*-all.jar` 放进服务端 `plugins/`
2. 启动服务端，确认日志出现 `SteveHUD enabled — protocol v1 ...`
3. 客户端装对应 MC 版本的模组 jar（+ Fabric API），进服
4. 服务端日志应出现握手；客户端日志应出现
   `SteveHUD server: SteveHUD-Plugin/0.1.0 (protocol v1, capabilities ...)`
5. `/stevehud status` 与 `/stevehud resend` 可用于诊断

## 当前状态

| 部分 | 状态 |
|---|---|
| 协议握手、分片、revision、四版本打包 | **完成**（实机验证） |
| 版面文档模型 + 三份内置版面包 | **完成** |
| 游戏内 HUD：由文档驱动、调色板、每元素样式与动画 | **完成** |
| 本机图形服务：HTTP + SSE + 静态页 + REST | **完成** |
| 网页包装：七元素、主题、`--u` 画布单位、`--k` 文字跟随框 | **完成**（浏览器实测） |
| 版面编辑器：拖拽/缩放/图层/属性/动画/配色/预设/实时预览/保存广播 | **完成**（浏览器实测） |
| 单元测试 | **110 项**（协议 33 + 共享 77），全绿 |
| 服务端数据源、档案、CSV 导出 | 未开始 |
| obs-websocket 文本源 | 未开始 |

M0 验收时两边日志的实际内容：

```
服务端  [SteveHUD] Greeting __RATE__ - client accepts:
        fabric:attachment_sync_v1, stevehud:main, fabric-screen-handler-api-v1:open_screen
客户端  SteveHUD server: SteveHUD-Plugin/0.1.0 (protocol v1, capabilities [snapshot, delta, layout])
```

（协议已升到 v3：v2 加了服务端下发的管理员标志，v3 携带广播状态。）

## 踩过的坑

这三条各花了整整一轮实机调试才定位，且都不是能猜出来的。

### 1. Fabric 的版本范围语法不是 Maven 语义

`"minecraft": "[1.21.4,1.21.5)"` **匹配不到任何版本**，连它自己的下界都不匹配，
表现为 `HARD_DEP ... 需要 Minecraft 的 [1.21.4,1.21.5) 版本，但已经安装了的版本 1.21.4 不对`。

正确写法是**空格分隔的比较式**：

```properties
minecraft_dependency_range=>=1.21.4- <1.21.5-
```

末尾的 `-` 是刻意的（cloth-config 与 modmenu 都这么写）：它让同一版本的预发布版
（`1.21.4-rc.3` 之类）也落进区间。这个语法可以**离线**验证，不必反复重启游戏——
用 Fabric Loader 自己的解析器：`VersionPredicate.parse(range).test(Version.parse("1.21.4"))`。

### 2. 插件通道的线格式是裸字节，没有长度前缀

Bukkit/Paper 发插件消息时把整个字节数组直接写进 payload，**没有任何字段结构**，
包自身的帧长度就是唯一分隔符。所以用 `PacketCodecs.STRING`（期待 VarInt 长度前缀）
会错位：客户端把 JSON 首字节 `{`(123) 当成长度读掉，剩 65 字节没消费，报
`packet ... was larger than I expected` 然后断连。

正确做法是解除码时**读满剩余字节**（vanilla 处理未知通道的 `DiscardedPayload` 就是这么做的），
见 `ChannelPayloads.rawBytes()`。这个易错点只写一次，两个 payload 共用。

### 3. Paper 只投递给"注册过该通道"的客户端

客户端必须通过 `minecraft:register` 声明它接受该通道，否则 Paper **静默丢弃，不报任何错**。
纯净客户端永远不会注册——这对诊断至关重要：**没有报错不等于包发出去了**。

好消息是 Fabric API 会自动替模组注册（实测 `client accepts:` 里就有 `stevehud:main`），
模组侧不需要自己发注册包。但服务端必须把这件事记下来，否则故障模式完全无声——
`ChannelSender` 因此显式检查并告警。


### 4. 缩放：绕元素自己的角，而不是整屏 zoom

第一版在浏览器里把 `transform-origin` 设在元素的锚点角、却按"绕原点"的公式算位置。
**scale=1 时完全看不出问题**；一旦把右上角元素放大 2 倍，它的右边缘从 1894 跑到 1462，
整整偏了 432 像素——正好是元素自己的宽度。修法是两边统一成"缩放绕左上角、位置负责贴边"，
与 `Anchor.place()` 的假设一致。

教训：**这类 bug 只有在 scale ≠ 1 时才存在**，所以"看起来对"不是验收。

### 5. 自动宽度的元素，内容变了必须重新定位

计时器从 `00:00` 变成 `128:45` 会变宽。右锚定的元素是"先测量、后定位"，
于是内容变宽之后位置没跟着更新，右边缘溢出屏幕 18.6 像素，公告也没居中。
修法是每次状态更新后都重新定位一遍——`width: 0` 的语义是"和内容一样大"，
那么内容一变，几何就得重算。

同一个 bug 还有第二副面孔：`enableScissor` 用的是窗口坐标、**不受矩阵变换影响**。
在缩放变换里按绘制坐标设裁剪，scale=1 时正确、其他任何 scale 都错。
所以滚动条的裁剪矩形是单独算的。


## 工具

### 测试服

一个入口，四个子命令（PowerShell）：

```powershell
.\scripts\test-server.ps1 start    # 构建并部署最新插件，后台启动
.\scripts\test-server.ps1 stop     # 停止，释放端口与世界锁
.\scripts\test-server.ps1 status   # 是否在跑、加载的是哪个插件构建
.\scripts\test-server.ps1 logs     # 跟随服务端日志
```

若执行策略拦截，加参数运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-server.ps1 start
```

`start` 是**后台启动**（`Start-Process -WindowStyle Hidden`），这是刻意的：前台运行的服务端
被 Ctrl+C 杀掉时，它的 java 子进程会活下来继续占着端口和世界的 `session.lock`，
下一次启动就会以 `LevelStorageSource` 报错神秘失败。因此 `stop` 是按**端口**找进程
（`Get-NetTCPConnection`），而不是信任某个记录下来的 pid。

一次性准备（下载并校验 Paper jar，sha256 固定）：`.\scripts\setup-test-server.ps1`

管理员（`__RATE__`，离线 UUID `86f2ef7e-8a56-3016-bef1-99731fa3223f`）已写在
`test-server/ops.json` 里。

> **不要把 `start` 的输出管道给 `tail` 之类的消费者。** 服务端是脱离启动的，但它会继承
> 调用方的 stdout 句柄，于是管道永远等不到 EOF，调用方看起来就永久挂住（`timeout` 也拦不住，
> 因为握着管道的是那个仍在运行的服务端）。重定向到文件没有这个问题：
> `.\scripts\test-server.ps1 start > start.log 2>&1`
> 同样地，脚本内部也把 gradle 的输出写进 `test-server/gradle-build.log` 而不是自己的 stdout，
> 因为 Gradle 守护进程是长期存活的，会以同样的方式握住管道。

> 日志读取一律带 `-Encoding UTF8`。Paper 写的是 UTF-8，中文 Windows 上
> `Get-Content` 默认按 ANSI 码页解码，会把日志里的非 ASCII 字符变成乱码。



### 网页预览服务器（不开游戏）

```powershell
node tools\web-preview.mjs
```

打开 **http://127.0.0.1:8788/overlay/**（编辑器在 `/editor/`）。

它读**源码目录**的页面，以及 **`protocol/src/main/resources/stevehud/layout/` 里的同一份预设**——
不是复制品，所以在这里调好的版面就是游戏里会画出来的版面。改页面 → 刷新浏览器 → 看效果，
没有构建、没有复制、不用重开游戏。它同时实现了模组的 REST 表面
（`/api/state`、`/api/layout`、`/api/layout?preset=`、`/api/presets`、`/api/preview`、`/events`），
所以编辑器在这里和在游戏里行为一致。

**改完服务端脚本必须重启它**：`node` 不热重载自己，而页面是从磁盘读的——
只有 `/api/*` 的行为会停留在旧代码上。

### 测试

**先跑这个**——不需要开游戏，35 秒给出服务端半边的 PASS/FAIL，且退出码可用于自动化：

```powershell
.\scripts\smoke-test.ps1              # 起服 → 探针 → 核对日志 → 停服
.\scripts\smoke-test.ps1 -KeepRunning # 测完不停服，接着玩游戏内测试
```

完整的测试方法（自动 + 游戏内手测清单 + 失败模式对照表）见 **[TESTING.md](TESTING.md)**。

### 协议探针

`tools/protocol-probe/` —— 无头协议探针，冒烟测试的核心。以纯净协议客户端连上服务端并**断言**
收到的插件消息：通道、JSON 合法性、协议版本、消息类型、分片状态、revision、
以及 hello body 的每个字段（共 10 项），最后返回退出码。

它会先注册通道作为阳性对照，从而把"服务端没发"和"客户端没注册"区分开。反过来也能验证这条规则
本身——`SKIP_REGISTER=1` 时它断言"我们的通道上什么都没收到"，**规则成立时预期 PASS**：

```bash
cd tools/protocol-probe && node probe.mjs
SKIP_REGISTER=1 node probe.mjs        # 断言相反，预期同样 PASS
```

两种模式都通过才算测试可信。若负向对照失败（即不注册也能收到），说明注册规则已不适用，
此时正向的 PASS 不能再作为"注册成功"的证据。


### 向窗口发送输入（未验证）

`tools/send-to-window.ps1` —— 绕过 computer-use broker，直接请 Windows 聚焦窗口并投递按键。

存在的理由：本机的 broker 无法合成原始输入（报 `window-scoped raw input requires native
activateWindow support`），而 Minecraft 的整个界面画在 OpenGL 表面上、不暴露任何无障碍元素
（窗口树里只有标题栏），所以语义路径和坐标路径都到不了游戏。

**注意它尚未被验证过**，而且向一个读不到画面的游戏投递输入本质上是盲操作——务必配合一条
效果可在别处观察的命令（例如服务端日志行）来确认输入是否真的到达。


## 已知约束与待办

- **JDK 21 来源**：本机无独立 JDK，`JAVA_HOME` 未设，Gradle daemon 跑在 Minecraft
  启动器自带的 runtime 上（见 `daemon-*.out.log` 的 `javaHome=`）。功能可用，
  但会被启动器更新破坏，需要装一个独立 JDK 21。
- **真字体字形未做**：游戏内是原版位图字体 + 描边/字距。**版面、配色、动画一致，字形不一致**——
  这是设计分工，不是没做完。
- **obs-websocket 文本源未接**：目前只有浏览器源这一条路径。
- **服务端侧无持久化**：重启服务端后比赛数据回到初始值；版式在客户端侧持久化。
- **客户端到服务端的方向还没通**：`ClientPlayNetworking.canSend(...)` 对 Bukkit 服务端返回
  false（Bukkit 从不发送服务端侧的通道广播），所以客户端目前不发自己的 hello，
  服务端也就拿不到客户端的协议版本与能力。M1 需要换机制，最可能是无条件发送。
  这不影响 M0 验收——M0 验收的是服务端到客户端这一向。
