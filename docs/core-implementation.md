# Texture-first 最小核心实现

> 当前第 3–5 步（半透明地形、普通实体/方块实体、粒子/天气/云）的实现与验证见 [内容渲染说明](render-content.md)。下面的阶段记录反映各阶段当时的状态。

## 2026-09-15 客户端命令补全修复

现象：`/differangle` 的客户端命令在聊天里能执行（Fabric 用独立的客户端命令表执行），但 Tab 补全里看不到 `mode`/`layer`/`status`/`list`/`preview`。

原因：补全用的是服务端命令树那一份命令表。Fabric 的 `ClientCommandInternals#addCommands` 复制客户端命令时先 `addChild`、之后才把子节点填进那份副本；Brigadier 遇到同名节点只做合并、不会替换，于是与服务端同名的 `differangle` 根节点（`WorldCommands` 注册）把整棵客户端子树丢掉了。`src/test` 的 `ClientCommandMergeTest` 固定了这条合并语义。

修复：不注入 MC 内部。原版客户端命令表在每次收到命令包时都会被整体换成新实例，因此在已有的 `ClientTickEvents.END_CLIENT_TICK` 里对账 `player.connection.commands`，发现新实例就用完整副本重新合并客户端命令树（权限放开、命令改为空实现，只用于补全），最多晚 1 tick；同时给只输一半的客户端命令（`mode`、`layer`、`preview`、`preview camera|screen`）补上提示执行器，`/differangle mode` 不再报“错误的命令参数”，也不会被转发到服务端。

验证：`./gradlew build` 通过（含新增测试）；重启客户端后 `/differangle` 补全应列出 `camera|screen|mode|layer|status|list|preview`。

## 2026-09-13 屏幕消失修复

排查发现分阶段渲染重构未接通：`CameraRuntime` 仍调用 `SharedTerrainRenderer` 的旧方法，其中 `texture` 只清空 Target，`surface` 与 `embedded` 是空方法，因此没有异常也不会显示屏幕。

运行入口现改为 `CameraWorldRenderer`：Texture 与 Embedded 共用天空背景、地形阶段，`CameraCompositor` 负责屏幕合成及深度资源；删除旧空方法，避免再次误接。同步修复了天空管线误用 Surface shader、Surface uniform 大小不一致，以及地形环境 uniform 未绑定的问题。基础天空颜色与雾已接入，太阳、月亮、星星、云和完整末地天空仍未完成。

验证：Gradle build 和现有测试通过；隐藏 OpenGL 上下文中，8 个 shader 变体编译/链接通过，CameraView、CameraEnvironment、Surface 大小分别为 144、128、64 字节，与 CPU 端一致。该检查使用等价的 Projection 声明替换 Minecraft import，尚不能替代游戏内验收。重启开发客户端后执行 `/differangle demo`，分别检查 Texture / Embedded、墙体遮挡和切换模式；`/differangle status` 应显示非零 Screen 绘制数。以下内容保留最初核心设计记录。

## 范围与决策

选择开发设计文档中的 Texture Camera 作为第一条实现路线。当前内存版 `CameraDefinition` 是渲染与调度原型；资源化后它必须从持久化 `CameraEntity` 的同步状态派生，不再作为独立权威数据保存。现有 Screen 定义也将由 `ScreenBlockEntity` 派生。

不为每个 Screen 创建世界 Renderer。CameraBackend 是世界绘制与几何共享的接入边界，核心不会复制世界、区块网格或加载额外区块。这里提供了共享几何的架构边界，尚未实现或验证实际 Chunk Geometry 共享。

## 已实现

| 组件 | 行为 |
| --- | --- |
| CameraDefinition | 不可变位置、四元数、垂直 FOV、近远裁剪面、分辨率、FPS、优先级与启用状态 |
| ScreenDefinition | Camera ID 绑定、独立位置/旋转/宽高，居中的局部 XY Quad 变换 |
| CameraSystem | 注册、更新、删除、绑定校验、显式主视角可见 Screen 输入 |
| 调度 | 默认每帧最多更新一个 Camera；默认 256×144、15 FPS、70° FOV |
| 公平性 | 从未渲染的 Camera 优先，然后按最长未刷新时间排序；同龄时使用优先级和 ID |
| 帧缓存 | 多 Screen 共用 Target；未到 FPS 截止时间复用；不可见时暂停更新、保留缓存 |
| 失效与释放 | Camera 配置改变立即失效；最后一个 Screen 移除/改绑时释放旧 Camera Target；删除 Camera 同时删除其 Screen |
| 异常处理 | Camera 绘制失败销毁可能被部分覆盖的 Target，下次可立即重试；拒绝递归渲染及绘制中的注册表修改 |
| TextureCameraBackend | 使用 26.2 TextureTarget 创建独立 RGBA8 颜色及深度资源，要求调用方提供真实世界/表面绘制函数 |
| 客户端生命周期 | 安装后端时释放旧系统；世界引用改变或客户端停止时清理资源 |

没有默认空绘制后端：单纯创建一张 Texture 不应被当成世界画面已完成。生产适配器必须提供两个绘制函数，目前尚无内置实现。

## 核心使用方式

下面的 `backend` 是实现了 `CameraBackend<T>` 的绘制后端；测试目录提供了可直接运行的记录型后端，用于验证调度，不绘制游戏画面。

```kotlin
val system = CameraSystem(backend, maxUpdatesPerFrame = 1)
system.putCamera(CameraDefinition(
    id = "lobby",
    position = Position(10.0, 70.0, 20.0),
    rotation = Rotation.minecraftDegrees(yaw = 90f, pitch = 10f),
))
system.putScreen(ScreenDefinition(
    id = "monitor",
    cameraId = "lobby",
    position = Position(0.0, 65.0, 5.0),
    width = 4f,
    height = 2.25f,
))

// 主世界渲染接入点每帧调用一次；origin 为主视角的渲染坐标原点。
val stats = system.renderFrame(System.nanoTime(), listOf("monitor"), origin)
```

所有当前原型系统操作必须在渲染线程执行。`nowNanos` 使用单调时钟，不使用系统日期时间。资源化后的关闭语义为：关闭的 Screen 保留几何并显示常量黑色，但不提出 Camera 更新需求；关闭的 Camera 让所有绑定 Screen 黑屏，并停止更新、释放 GPU Target。预算未覆盖且没有缓存的 Camera 暂时显示黑色。

注册生产后端时使用 `DifferangleClient.installBackend(TextureCameraBackend(drawWorld, drawSurface))`，返回受客户端生命周期管理的 CameraSystem。初始化本身不会调用 `renderFrame`；世界渲染集成负责在正确绘制阶段传入可见 Screen，并在资源重载时清理系统。`clear()` 会清空定义和缓存，需要重新注册 Camera/Screen。

## 坐标与深度约定

- 位置使用 Double。构造矩阵前先减去渲染原点，再转 Float，保留远距离世界坐标的小数精度。
- Camera 的单位四元数朝向 -Z，+Y 向上。`minecraftDegrees` 转换 Minecraft yaw/pitch，并支持 roll。
- `viewMatrix(origin)` 的输入顶点必须相对于同一 `origin`。默认 origin 是 Camera 位置，此时矩阵只含旋转；不要把绝对世界坐标直接乘上它。
- FOV 是垂直角度，宽高比来自 Target 分辨率，与 Screen 的世界尺寸独立。
- 投影默认采用右手系、NDC 深度 [-1, 1]，也可显式生成 [0, 1]。生产后端应匹配当前 GPU pipeline 的深度约定；反向 Z 和其他特殊投影需要后端另行适配。
- Screen 的局部顶点范围是 X/Y 各 [-0.5, 0.5]，正面法线为 +Z。显示 Quad 只渲染正面，背面在 CPU 需求判断和 GPU Cull 两处过滤；外壳/背板可由普通模型单独表现。
- Camera Target 的深度只用于 Camera 自身；合成 Screen 时应使用 Screen 表面在主视角的深度。
- `CameraFrame` 和 Target 是借用引用，系统更新或清理后不能继续持有或使用。

## 验证与后续接入

CameraEntity 持久化、Screen 基座方块、UUID 绑定、开关/黑屏、单面显示、轨迹插值和 Replay 适配的确定方案见[camera-entity-screen-blocks.md](camera-entity-screen-blocks.md)。当前核心 API 仍是过渡层，实现资源实体时需要按该契约替换数据来源。

自动化测试验证矩阵方向、FOV/裁剪面、远坐标精度、20 Screen 共用一个 Target、FPS 边界、调度公平性、隐藏/禁用过滤、失效/释放、异常恢复及递归保护。测试不需要启动 Minecraft 或 GPU。

GPU 适配代码已按本地 26.2 API 编译验证，但尚未进行游戏内 GPU 验证。下一步是设计文档第 48 节的最小实验：通过一个已有区块的几何，完成独立 Camera → TextureTarget → 旋转 Quad，并验证主视角渲染状态恢复。几何共享、主视角可见性复用、Iris/Sodium、动态分辨率、关键帧与 Embedded 均不在本次实现范围内。
