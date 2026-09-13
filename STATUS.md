# SteveHUD 项目状态

> 这份文档是项目的"压缩上下文"：把决策、已验证的硬事实、当前进度都固化下来，
> 供新会话直接接手，不必重读历史。

## 一、产品定位

**赛事转播图形系统**，不是某个赛制的 HUD。参照奥运会转播的分工：图形包装共用，
数据由各项目自己的计分/计时系统喂进来。v1 目标覆盖 PVP 两队对抗、队伍型起床战争、竞速。

## 二、已确认的决策

| 决策 | 结论 |
|---|---|
| 模组版本 | **策展**：1.21.1 / 1.21.4 / 1.21.8 / 1.21.11（1.21.1 的 jar 同时声明支持 1.21） |
| 插件版本 | **一个 jar 通吃全部 1.21.x** |
| 档案 vs 成绩 | 档案**人工维护**，成绩**自动采集**，两条管道互不阻塞 |
| 图形服务位置 | **只由模组在本机开** HTTP；插件只做数据源与下发 |
| 图形服务的实时通道 | **SSE，不是 WebSocket**。数据是单向的（状态推给浏览器，编辑器的写入走普通 POST），SSE 覆盖得了，且 `curl` 就能诊断 |
| 图形服务的 HTTP 实现 | **手写 `ServerSocket`，不用 `com.sun.net.httpserver`**。后者在一个模块里，MC 启动参数会限制模块集；手写版零模块依赖，而且**能在单测里跑** |
| **版面文档** | **一份 JSON，三处共用**：游戏内 HUD、网页包装、版面编辑器。这是"窗口形式和样式全服务器统一"的实现方式 |
| **版面的两种范围** | **本机版面**（本机文件，驱动本机 OBS 与 HUD，**不需要权限**）与**全服版面**（服务端下发，所有屏幕，**需要 `stevehud.admin`**）。判据是影响范围而非重要程度；优先级 全服 > 本机；**默认没有全服版面** |
| 版面编辑器 | **网页端做全部编辑**（拖拽/缩放/图层/属性/动画/配色），游戏内只做预设切换（`/shudlayout`）。原计划的两套编辑器合并为一套 |
| 游戏内 UI 库 | **不需要了**。设置界面用 ClothConfig，HUD 是自己的绘制层，版面编辑器在网页端——原来那个"UI 库选型"问题消失 |
| ClothConfig 定位 | **仅客户端个人偏好**（缩放/透明/显隐/端口），不涉及内容编辑；**外部前置，不打包** |
| 编辑器 UI 规范 | **打版台（dark console）**（用本地 `ui-ux-pro-max` 技能库定）：深色底 `#080a0f`、4px 圆角、层次只靠 1px 边框 + 中性灰阶；强调色取自被编辑的版面包，所以工具被它编辑的东西染色 |
| 编辑器的页面布局 | **可改**：左右两栏可拖宽窄（180–640px）、双击折叠，状态存 localStorage；左栏「预设 / 图层 / 数据」，右栏「属性 / 主题 / JSON」，控制台贴底可折叠 |
| **数据看板的元素类型** | 共 **16 种**：8 种常驻信息 + 8 种看板（`statCompare` / `leaderBoard` / `seriesChart` / `kpiTiles` / `rosterCard` / `seriesScore` / `timeline` / `headToHead`） |
| **看板的数据形状** | **一种**：`Board{key,title,unit,rows:[Metric{key,label,sub,side,value,display,delta,state,index,time,series}]}`。所有看板从同一张表里取数，所以加第十种看板不动协议也不加命令 |
| **元素级开放参数** | `Layout.Element.options: Map<String,String>`。渲染器认识的键有中文标签与控件；不认识的键**原样保留并往返**，所以"先在编辑器里存一个设置、渲染器以后再支持"是可行的顺序 |
| **「不遮挡观看」** | **三条可执行预算**：单个未声明元素压进画面窗口 ≤2%、整场景 ≤3%（含已声明的中场大卡时 ≤70%）、文字缩到下限不得仍溢出。声明方式是元素自己的 `options.window = "allow"` |
| 版面包预设 | **arena（竞技场）/ olympia（奥林匹亚）/ clean（边线）**，替换掉原来的 esports / olympic / minimal |
| ✗ 不做 | 不内嵌 Chromium、不用游戏原生 GUI 做编辑器、不引入任何网络请求（字体/CDN） |

## 三、架构与"承重性质"

```
protocol/                     零 MC 依赖
  model/Layout.java           版面文档模型（三渲染器共用的唯一契约）
  model/Layouts.java          预设加载、归一化、回落
  model/BroadcastState.java   比赛状态（服务端拥有）
  resources/stevehud/layout/  三份内置版面包：arena / olympia / clean
mod/common/                   零 MC 依赖
  client/SteveHudLink.java    链路状态机 + revision 追踪
  client/anim/Anim.java       墙钟动画运行时（13 个单测）
  client/layout/Anchor.java   锚点定位算术（两边共用的同一套公式）
  client/layout/Bindings.java 绑定路径求值（event.name、sides[0].score…）
  client/layout/ElementMetrics.java  各类型的参考宽度 + 框比例（"文字跟随框"的基准）
  client/layout/LayoutStore.java     版面读写、原子落盘、坏文件不覆盖
  client/layout/JsonMerge.java       局部更新的合并规则
  client/web/LocalGraphicsServer.java  手写 HTTP + SSE
mod/client-mc/                碰 MC API 但跨版本不变
  mc/hud/                     版面驱动的 HUD：MatchHud 遍历元素 → Panels 绘制
  mc/web/WebBridge.java       解包网页、持有版面、广播、外改热重载、本地预览
  resources/stevehud/web/     包装页 / 编辑器 / 样式表（无构建步骤）
mod/mc1.21.x/                 只放不可共享的 impl（目前只有 HudScale）
plugin/                       Spigot/Paper 插件，一个 jar 通吃
tools/web-preview.mjs         纯浏览器预览服务器（不需要开游戏）
tools/overlay-audit.mjs       版面审计：无头浏览器量遮挡预算、文字裁切、元素重叠、页面报错
```

**"零 MC 依赖"是承重性质，不是风格偏好。** 正因为 `protocol` 与 `mod/common` 不引用任何
Minecraft 类，四个版本模块才能原样共享它们，完全不需要 Loom 的 `namedElements` 跨版本
重映射或 shadow 合并。三个 Loom 模块通过 `sourceSets.main.java.srcDir` 共同编译
`mod/client-mc`，所以那层也**不存在多份副本**。

**"版面文档是唯一契约"同样是承重性质。** 三处渲染器（游戏内 / 浏览器 / 编辑器）都从同一份
文档读位置、尺寸、配色、动画。所以它们不可能各画各的——这是设计出来的，不是碰巧的。
编辑器里的画布直接嵌真实的包装页（同源 iframe，1920×1080），拖拽框的几何**从那个 iframe
的 DOM 里读回来**，而不是在编辑器里重算一遍锚点算术：两份实现迟早会分歧，而编辑器一旦
说错话，它就不再可信。

## 四、四条已实测验证的硬事实

前三条来自实机联调，第四条来自这一轮的浏览器实测。共同点是：**不实测就绝对想不到**。

1. **Fabric 的版本范围语法不是 Maven 语义。** `[1.21.4,1.21.5)` 匹配不到任何版本，
   连自己的下界都不匹配。正确写法是空格分隔的比较式 `>=1.21.4- <1.21.5-`。
   可离线验证：`VersionPredicate.parse(range).test(Version.parse("1.21.4"))`。

2. **插件通道的线格式是裸字节，没有长度前缀。** Bukkit 把整个字节数组直接写进 payload。
   用 `PacketCodecs.STRING` 会把 JSON 首字节当长度读掉并断连
   （`found 65 bytes extra whilst reading packet`：`{`=123 被当成 varint 长度，189−1−123=65）。
   解法：解除码时读满剩余字节。

3. **Paper 只向"注册过该通道"的客户端投递，且静默丢弃、不报错。**
   Fabric API 会自动替模组注册，但服务端必须显式检查并告警——否则故障完全无声。

4. **缩放的正确实现是"绕元素自己的角"，而不是"整屏 zoom"。**
   我第一版在浏览器里把 `transform-origin` 设在锚点角、却按"绕原点"的公式算位置，
   结果元素**在 scale=1 时完全看不出问题**，一旦放大：右上角元素的右边缘从 1894 跑到 1462，
   整整偏了 432 像素。修法是两边统一成"缩放绕原点、位置负责贴边"，与 `Anchor.place()` 一致。
   **教训：只有把 scale 设成非 1 才能验证这类 bug。**

同类的还有两条"只有量才能发现"的：

- **自动宽度的元素，内容变了必须重新定位。** 计时器从 `00:00` 变到 `128:45` 会变宽，
  而右锚定的元素是在测量之后才定位的，于是右边缘溢出屏幕 18.6 像素、公告也没居中。
  修法是每次状态更新后都重新定位一遍。
- **一条不变量写在两处，等于没写。** "没有元素的版面不算版面" 我先只在消息体的
  `present()` 里判了一次，`LayoutSource` 自己没兜。单测直接把它挑出来了：绕过 `present()`
  的调用者会推一份空版面上去，把整场比赛的屏幕清空。规则要在**渲染器之前的最后一站**兜住，
  而不是在调用链的某一环。
- **`enableScissor` 用的是窗口坐标，不受矩阵变换影响。** 在缩放变换里按绘制坐标设裁剪，
  scale=1 时正确、其他任何 scale 都错。

## 五、跨版本 API 实证结论（javap 核过，非推测）

### 可写一份源码、四版本通用

| API | 备注 |
|---|---|
| `HudRenderCallback.EVENT.register(...)` | 四版本都存在且都会触发。1.21.4+ 标了 `@Deprecated`（只是警告）。**这是唯一四版本通用的 HUD 挂载点** |
| `DrawContext.fill(int×5)` / `fillGradient(int×6)` | 一致 |
| `drawText` / `drawTextWithShadow` / `drawCenteredTextWithShadow` | 全部重载一致 |
| `enableScissor` / `disableScissor` / `getScaledWindowWidth` / `getScaledWindowHeight` | 一致（但**裁剪不跟随矩阵**，见上） |
| `TextRenderer.getWidth` / `trimToWidth` / 公有字段 `fontHeight` | 一致 |
| `PlayerSkinDrawer.draw(ctx, entry.getSkinTextures(), x, y, size)` | 类型推断可绕开 SkinTextures 换包问题 |
| `ClientPlayNetworkHandler.getPlayerList()` | 一致 |
| `Util.getMeasuringTimeMs()` / `System.nanoTime()` | 一致（动画时钟用它，**不用** RenderTickCounter） |
| `MinecraftClient.getCurrentFps()` / `getScaledWindowWidth()` | 一致 |

### *不可*通用，必须每版本一份（已隔离进 `mod/mc1.21.x/.../impl/`）

| API | 差异 |
|---|---|
| `DrawContext.getMatrices()` | 1.21.1/1.21.4 是 `MatrixStack`（`push/scale(f,f,f)`）；1.21.8/1.21.11 是 JOML `Matrix3x2fStack`（`pushMatrix/scale(f,f)`）。**连方法名都不重叠** |
| `Style.withFont` | 1.21.1–1.21.8 收 `Identifier`；1.21.11 收 `StyleSpriteSource` |
| `KeyBinding` 构造器第 4 参 | 1.21.1–1.21.8 是 `String`；1.21.11 是 `KeyBinding.Category` |
| 鼠标方法 | 1.21.11 改 `mouseClicked(Click,boolean)` |
| `Screen.init` / `Screen.keyPressed` | 1.21.11 都换了签名（→ **别覆写**） |
| `hasPermissionLevel(int)` | **1.21.11 上不存在** → 权限判定移到服务端，握手时下发 `HelloBody.operator` |
| `RenderTickCounter` 取值 | 改过名（→ **别用它**，动画走墙钟） |
| `drawBorder` | 1.21.11 移除，改 `drawStrokedRectangle` |

**结论：跨版本策略是"先核实再写"，不是"写完靠编译器告诉你"。** 上面这张表就是资产。

## 六、进度

| 阶段 | 状态 |
|---|---|
| M0 环境骨架、协议握手、四版本打包 | **完成**（实机验证） |
| 测试工具链（探针 11 项 / 冒烟 / 单元测试 110 项） | **完成** |
| 版面文档模型 + 三份内置版面包 | **完成**（协议侧 33 个单测） |
| 游戏内 HUD 由文档驱动 + 调色板 + 每元素样式 | **完成**（四版本编译通过） |
| M4 本机图形服务（HTTP + SSE + 静态页 + REST） | **完成**（22 个单测） |
| M4 网页包装（七元素、动画、主题、`--u` 画布单位） | **完成**（浏览器实测） |
| M5 版面编辑器（拖拽/缩放/图层/属性/动画/配色/预设/实时预览/保存广播） | **完成**（浏览器实测） |
| 本地预览数据（无比赛时排版面） | **完成** |
| 编辑器 UI 规范（Brutalism + Monochrome，直角纯黑） | **完成**（浏览器实测：零圆角、零阴影、零模糊、对比度达标） |
| M2 服务端数据源、档案、CSV 导出 | 未开始 |
| M6 obs-websocket 文本源集成 | 未开始 |
| M3 真字体资源、快捷键桥接 | 未开始 |

### 网页侧这一轮的关键实现

- **`--u` 画布单位**：一个 `--u` = 1920×1080 设计画布上的 1 像素。所有尺寸写成
  `calc(N * var(--u))`，渲染器按实际视口算一个统一比例。**这就是编辑器里拖到哪、
  OBS 里就在哪**的原因。用 `vmin` 看起来等价，实际上窗口一旦不是 16:9 就与编辑器脱节。
- **`--k` 框比例（"文字跟随框"）**：每个元素再乘一个"文档框宽 ÷ 该类型参考宽"。
  于是把框拖宽，框里的字、间距、圆点全部等比跟着变，而不是固定字号配一个大空框。
  参考宽度放在 `ElementMetrics`（可测），overlay 的 JS 副本有单测逐项比对。
- **`--uk = --u × --k`**：包装页元素的内部尺寸统一用它。
  注意 `--uk` 必须在 `.el` 上**重新声明**而不是继承——自定义属性继承的是计算后的值，
  在 `.el` 上覆盖 `--k` 不会改变继承来的 `--uk`。
- **两盒结构**：`.el` 承载文档的 scale（**绕左上角**，与 `Anchor.place()` 的假设一致），
  `.el-body` 承载入场动画的 transform。放同一个盒子上，动画会覆盖 scale，
  元素入场时会瞬间缩回 1×。
- **命名 SSE 事件**：状态走默认事件（`onmessage` 能读，向后兼容），版面走具名
  `layout` 事件。两者必须分开：状态每秒都在变，版面很少变，混在一起会让 overlay
  在每次比分变化时重建整棵元素树。
- **编辑器不覆盖未保存的改动**，并且**自己保存的回声不能被误报成冲突**
  （`saveInFlight` 标志）——否则运营会学会忽略那个唯一不能忽略的警告。

## 七、已知边界

- **客户端→服务端方向未通**：`ClientPlayNetworking.canSend` 对 Bukkit 服务端返回 false。
  不影响现有任何功能（数据是服务端下发的单方向）。
- **无独立 JDK 21**：靠 Gradle toolchain 自动下载到 `~/.gradle/jdks`。功能可用，属健壮性欠账。
- **`placeholder-api-2.5.2+1.21.3.jar`** 在 1.21.4 实例的 mods 里，版本不匹配，是潜在干扰源。
- **真正的自定义字形未做**：游戏内是原版位图字体 + 描边/字距/放大。
  **版面、配色、动画一致，字形不一致**——这是设计分工，不是没做完。
- **网页不加载任何网络字体**：转播机常离线。字体栈优先本机已安装的同名字体，缺失则回落系统栈。
- **编辑器不响应 `prefers-reduced-motion` 之外的系统偏好**；包装页（OBS 输出）**有意不响应**
  减少动效——那是画给观众的内容，不是给操作者的界面。

## 八、工程约定

- **快速通道**：`./gradlew -PskipMods :protocol:test :mod:common:test`
  → 约 1 分钟（对比全量构建数分钟）。改非 MC 代码一律先跑这条。
- **纯浏览器验证**：`node tools/web-preview.mjs` → `http://127.0.0.1:8788/`。
  它读**源码目录**的页面与**协议模块里的同一份预设**，所以改页面刷新即可见，
  而且验的就是模组会画的那份东西。网页侧的任何改动都必须先过这一关。
- **改完网页/样式必须重启预览服务器**：`node` 进程不会热重载服务端脚本，
  而页面是从磁盘读的——只有 `/api/*` 的行为会停留在旧代码上（这个坑踩过一次：
  改了 `/api/presets` 的标签，页面却一直显示旧值）。
- **验证几何要看数值，不要只看截图**：screenshot 在这个会话里无法回看。
  遍历 `getBoundingClientRect()`、`getComputedStyle()` 断言贴边/居中/缩放倍数/
  对比度/圆角/阴影，比肉眼看更严格。本轮 4 个 bug 全是这样抓到的。
- **测试服**：`.\scripts\test-server.ps1 start|stop|status|logs`（PowerShell）。
  `stop` 按端口杀——前台启动被 Ctrl+C 会留下孤儿 java 进程占着 `session.lock`。
- **冒烟测试**：`.\scripts\smoke-test.ps1`，退出码即结论，不需要开游戏。
- **日志读取必须带 `-Encoding UTF8`**：Paper 写 UTF-8，中文 Windows 默认按 ANSI 解码。
- **不要把后台服务端的输出管道给 `tail`**：它继承了调用方的 stdout 句柄，管道永不 EOF。
- **不要用反序切片改整段 Markdown**：本轮我把 `TESTING.md` 第三到第九章切没了，
  因为 `src[a:b]` 的 a>b 会得到空串。改长文档前先备份，或老老实实用行号。
