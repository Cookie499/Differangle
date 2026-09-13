# 摄像机实体与显示屏方块方案讨论

本文讨论下一阶段的游戏内资源模型。本阶段先确定数据契约与生命周期，不立即实现方块、实体或轨迹系统。

## 结论

采用“显示屏方块保存 Screen 配置、不可见 Camera 实体保存 Camera 运动状态”的组合方案：

```text
Screen 基座/支架方块实体
  └── screenUuid, cameraUuid, size, resolution, fps, enabled

Camera 实体（客户端实体，可 Invisible）
  └── cameraUuid, position, rotation, fov, near/far, interpolation, path
```

Screen 与 Camera 通过 UUID 关联，而不是保存实体运行时对象引用。方块实体负责持久化和方块位置；Camera 实体负责位置、朝向、可见性和轨迹。删除或卸载一方时，另一方保留数据但渲染状态变为“未解析”，不自动删除用户配置。

## 参考图转化出的几何约束

用户提供的三张四视图参考图只作为外观和空间关系参考，图中的网格、坐标轴、视图名称和建模软件按钮不属于项目功能要求。按参考图，模型由墙面基座、连接杆和屏幕面组成：

- 基座贴在墙面上，是唯一需要对应 Minecraft 方块位置的方块实体；它保存 Screen 配置和 Camera UUID。
- 连接杆从基座连接到屏幕背面，屏幕和连接杆都是客户端绘制的几何，不占用方块碰撞体，也不参与方块遮挡判定。
- 屏幕面是独立的矩形平面，允许相对基座做平移、三轴旋转和长宽缩放；连接杆至少需要跟随基座到屏幕的端点变换。
- 顶视、侧视和正视展示了同一对象在不同方向的旋转关系；运行时不应把“贴墙方向”限制成只能朝向四个方位。
- 屏幕边框、黑色显示面和连接杆材质属于渲染表现；画面纹理只绑定到显示面，不写入基座方块模型。

因此运行时几何应采用：

```text
基座方块位置 + 基座面朝向
        ↓
屏幕局部 Transform（offset / quaternion / width / height）
        ↓
屏幕面 Model Matrix + 连接杆端点矩阵
```

基座方块状态建议只保存墙面朝向和基础形态；可调数值放在方块实体，避免为每个尺寸/旋转组合注册大量 BlockState。客户端渲染器根据方块实体的配置创建 Screen Surface 和 Rod Mesh，配置变化时增加 `revision` 并使画面缓存失效。

## 显示屏基座与支架

建议拆成两个方块：

- `screen_base`：功能方块和 Screen 方块实体，默认占一格、贴墙放置，负责保存配置、绑定 Camera、提供交互入口。
- `screen_stand`：支架/外壳方块，可选连接方向和尺寸外观；不保存渲染缓存。它通过方块实体或相邻方块位置找到 `screen_base`。

第一版应先只实现 `screen_base` 的数据和渲染挂点，支架几何与屏幕几何都由同一个客户端 BlockEntityRenderer 输出，避免把渲染状态复制到多个方块。屏幕画面不是普通方块模型的一部分；Screen Surface 仍由客户端渲染系统根据基座面朝向、局部变换和尺寸绘制。所有这些渲染几何都使用空碰撞形状，不影响玩家碰撞、选取和方块光照采样。

Screen 方块实体持久化字段建议：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `screen_uuid` | UUID | 稳定 Screen 标识；方块复制时必须重新生成 |
| `camera_uuid` | UUID? | 绑定的 Camera；为空时显示未绑定占位色 |
| `width`, `height` | float | 世界单位尺寸，限制在 0.125–64 |
| `resolution_width`, `resolution_height` | int | Target 分辨率，限制在 16–2048 |
| `update_rate` | int | FPS 上限，限制在 1–240 |
| `mode` | enum | `texture`、`embedded` 或 `auto`；当前建议默认 texture |
| `enabled` | bool | 是否绘制和参与调度 |
| `uv_flip` | enum | 纹理上下/左右方向，解决方块朝向和 UV 约定 |
| `revision` | long | 配置变化计数；用于失效 Frame Cache |

额外的几何字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `screen_offset` | double[3] | 相对基座锚点的平移 |
| `screen_rotation` | quaternion[4] | 相对基座面的旋转，必须归一化 |
| `rod_start`, `rod_end` | double[3] | 连接杆端点；默认从基座锚点连接到屏幕后中心 |
| `frame_depth` | float | 屏幕边框厚度，仅影响客户端几何 |
| `render_collision` | bool | 预留调试开关，默认 false；不改变 Minecraft 碰撞体 |

渲染 Target、可见区块集合和 GPU 句柄禁止写入方块实体 NBT。它们属于客户端运行时缓存，并由 `screen_uuid` + `revision` 作为键。

## Camera 实体

Camera 实体是客户端功能实体，不代表可碰撞的世界对象：

- 默认 `invisible = true`、无阴影、无碰撞、无重力、无选择框。
- 可选调试可见模式，显示轴线、视锥体、UUID 和路径关键帧。
- 实体位置使用 Minecraft 世界坐标；旋转使用 yaw、pitch、roll，内部转换为规范化四元数。
- 实体 UUID 是主标识。不要把实体名称作为关联键；名称可以重命名。
- 实体可以在客户端世界切换时重建，但 UUID 和保存数据必须恢复。

Camera 实体持久化字段建议：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `camera_uuid` | UUID | 稳定关联标识 |
| `display_name` | string | UI/指令显示名称，不参与关联 |
| `invisible` | bool | 是否隐藏调试实体 |
| `position` | double[3] | 可选冗余；实体本身的坐标是权威值 |
| `yaw`, `pitch`, `roll` | float | 角度，加载时规范化 |
| `fov` | float | 垂直 FOV，建议 1–179 |
| `near_plane`, `far_plane` | float | 裁剪面 |
| `update_rate`, `priority` | int | Texture 调度参数 |
| `interpolation.enabled` | bool | 是否在关键帧之间插值 |
| `interpolation.type` | enum | `step`、`linear`、`smoothstep`、`cubic` |
| `interpolation.rotation` | enum | `slerp` 或 `nlerp`；默认 `slerp` |
| `path` | UUID? | 轨迹资源标识 |
| `path_time` | double | 当前轨迹时间，单位 tick 或秒，必须明确一种 |
| `path_loop` | enum | `none`、`loop`、`ping_pong` |

Position 的权威关系需要固定：运行时以实体坐标/旋转为当前值；轨迹播放器每 tick 计算目标值并写回实体状态；NBT 只在世界保存或显式修改时更新，避免每帧写盘。

## 关键帧和插值

关键帧单独建模为 `CameraKeyframe`，不要把轨迹数组直接塞进实体类：

```kotlin
data class CameraKeyframe(
    val time: Double,
    val position: Vec3d,
    val rotation: Quaternionf,
    val fov: Float,
    val nearPlane: Float,
    val farPlane: Float,
)
```

轨迹资源包含有序关键帧、插值设置和循环设置。至少支持：

- `step`：保持上一个关键帧，适合切镜头。
- `linear`：位置、FOV、裁剪面线性插值。
- `smoothstep`：在线性参数上施加平滑曲线。
- `cubic`：位置使用 Catmull–Rom；FOV 使用标量 cubic；旋转仍使用分段 quaternion slerp。

旋转禁止对 yaw/pitch/roll 三个标量直接线性插值，否则会在 ±180° 处跳变。统一转换为 Quaternion，使用 shortest-path slerp；roll 也必须参与插值。插值结果每次归一化，并在完成后写回 Camera 的运行时状态。

推荐时间流程：

```text
tick 时间 → path_time
        → 找到前后关键帧
        → 计算 t 和 easing
        → position / rotation / FOV 插值
        → 更新 Camera 实体运行时状态
        → CameraSystem 看到配置 revision/pose 变化并失效缓存
```

暂停、单步和倒放应作用于 `path_time`，而不是改写世界 tick。轨迹越界时按照 `path_loop` 处理；关键帧为空或只含一个时保持静态。

## 指令和 API 边界

客户端指令用于调试和快速布置，API 用于方块实体、Replay、脚本和其他模组。两者必须调用同一个 `CameraController`，不能各自维护一套状态。

建议指令形状：

```text
/differangle camera create <name> [invisible]
/differangle camera remove <uuid|name>
/differangle camera select <uuid|name>
/differangle camera bind <screen-pos|screen-uuid> <camera-uuid>
/differangle camera invisible <uuid|name> <true|false>
/differangle camera pose <uuid|name> <x> <y> <z> <yaw> <pitch> <roll>
/differangle camera fov <uuid|name> <degrees>
/differangle camera move <uuid|name> <x> <y> <z> [ticks]
/differangle camera look <uuid|name> <yaw> <pitch> <roll> [ticks]
/differangle camera interpolation <uuid|name> <on|off> <step|linear|smoothstep|cubic>
/differangle camera keyframe add <uuid|name> <time> [fov]
/differangle camera keyframe remove <uuid|name> <time>
/differangle camera play <uuid|name> [path] [loop|ping_pong]
/differangle camera pause|stop|seek <uuid|name> ...
/differangle screen configure <pos|uuid> <width> <height> <resX> <resY> <fps>
/differangle screen bind <pos|uuid> <camera-uuid>
```

命令解析、权限和跨世界定位仍需区分：客户端命令只影响本地客户端实体/缓存；若未来需要服务器保存 Camera/Screen，应另加服务端协议和权限，不能把客户端命令伪装成服务器状态。

建议 API：

```kotlin
interface CameraController {
    fun create(definition: CameraDefinition): UUID
    fun setPose(id: UUID, target: CameraPose, durationTicks: Int = 0,
                interpolation: InterpolationSpec = InterpolationSpec.default)
    fun attachPath(id: UUID, path: CameraPath?)
    fun seek(id: UUID, time: Double)
    fun setInvisible(id: UUID, invisible: Boolean)
    fun bind(screen: ScreenHandle, camera: UUID?)
    fun snapshot(id: UUID): CameraSnapshot?
}
```

所有公开 API 修改都必须在客户端主线程排队；实际 GPU Target 创建/释放必须在渲染线程执行。`setPose(durationTicks = 0)` 是立即设置，持续时间大于零时创建一个临时的两点轨迹；这让 `/move` 和 Replay 导入共享同一套行为。

## 与 Replay 关键帧的关系

Replay 的关键帧应作为输入适配器，而不是复制 Camera 播放器：

```text
Replay keyframes ─┐
自定义 CameraPath ─┼→ CameraController → Camera 实体运行时状态
指令 move/look ───┘
```

Replay 导入时保留原始时间轴和采样率，在适配层转换成 `CameraKeyframe`。CameraController 统一处理插值、循环、暂停、seek 和 FOV；这样 Replay 摄像机和普通摄像机的渲染、Screen 绑定、缓存失效规则一致。不要让 Replay 直接操作 RenderTarget 或绕过 CameraSystem。

## 镜子预案

镜子是 Screen 的一种特殊 Surface。它显示反射视图，而不是绑定一个固定 Camera UUID 的普通画面。镜面几何仍由基座方块实体对应的客户端 BlockEntityRenderer 绘制；镜面、边框和连接杆继续使用同一套局部 Transform、尺寸、旋转和平移数据。

### 反射视图模型

对镜面所在平面建立单位法线 `n` 和平面上一点 `p`。主视角位置、方向经过平面反射后得到虚拟 Camera：

```text
reflectedPosition = position - 2 * dot(position - p, n) * n
reflectedDirection = direction - 2 * dot(direction, n) * n
```

实际实现应使用矩阵反射变换 `R`，再从 `R * mainView` 得到反射 View；不要只反射 yaw/pitch，也不要在欧拉角上做镜像。反射视图需要独立的 Frustum、FOV、近远裁剪和 Camera-specific Depth。

镜面投影要处理手性翻转：反射会改变绕序和法线方向。渲染管线必须明确是否关闭背面剔除，或对反射 View 使用等价的 winding 修正；不能靠交换纹理左右方向掩盖几何手性问题。

### 两种实现路径

| 路径 | 做法 | 适用阶段 |
| --- | --- | --- |
| Texture Mirror | 用反射 Camera 渲染一张 Target，再把 Target 绘制到镜面 Quad | 第一版推荐，缓存和调试简单 |
| Embedded Mirror | 用镜面多边形写 Stencil，再在主帧缓冲直接绘制反射 View | 第二阶段，适合低延迟和小镜面 |

第一版镜子应采用 Texture Mirror，并限制为单次反射。镜子不再额外创建 Camera 实体；它在每次刷新时从主 Camera 和镜面 Transform 生成临时 `ReflectedCameraView`。如果需要固定监控角度，仍使用普通 Screen + Camera UUID，而不是把镜子当成 Camera。

### 裁剪和深度

反射 Camera 的近裁剪面必须位于镜面背后，防止镜面自身和背后的几何进入反射画面。Texture 模式可在反射 Target 内使用反射平面的裁剪面；Embedded 模式还需要把镜面多边形写入 Stencil，并让反射绘制只通过该 Stencil。

镜面最终颜色必须通过主视角的镜面表面深度测试写入。反射世界的深度只属于反射 View，不得直接和主世界深度比较；若要支持主世界物体遮挡镜子，需要先完成镜面 Surface 深度，再在主合成阶段做一次明确的深度比较。

### 递归策略

镜子对着镜子会产生递归。第一版固定：

- `max_reflection_depth = 1`，反射视图中再次出现的镜面使用黑色、环境色或上一帧缓存。
- 不在反射 View 中再次调用镜子渲染队列，避免无限递归和 GPU 峰值。
- 相同镜面在同一主帧只生成一次反射 Target；多个观察到它的 Surface 共享该 Target。
- 镜面很小或背向主 Camera 时跳过更新，保留上一帧并显示年龄。

后续可支持深度 2 或递归缓存，但必须有全局预算、分辨率缩放和循环检测。递归深度属于渲染设置，不写入普通 Screen 的 Camera 配置。

### 非主视图中的 Screen、Mirror 与循环保护

递归风险不只来自“镜子对着镜子”。普通 Screen 绑定的 Camera 也可能看到另一个 Screen，而后者绑定回前一个 Camera；镜子、Screen 和 Camera 因此形成一个渲染依赖图。例如：

```text
主视图 → Screen A → Camera A → Screen B → Camera B → Screen A
主视图 → Mirror A → 反射视图 → Screen A
```

如果在生成父 Target 的函数中同步生成子 Target，就会出现调用栈递归、同一 Target 重入、GPU 工作量指数增长，最终表现为卡死、栈溢出或显存耗尽。即使依赖图没有环，多层 Screen 也可能在一帧内反复绘制同一个 Camera，因此不能只依赖 Java/Kotlin 的栈深度异常来兜底。

主动把内部渲染改为 Texture 有帮助，但单独不能修复问题。Texture 只隔离了帧缓冲和深度附件；如果 Texture Target 尚未生成时仍在当前调用栈里立即渲染它，依旧会递归。正确语义应是：嵌套视图只采样已经完成的缓存，缺少缓存时返回占位色并把目标加入后续渲染队列，禁止在父视图内部同步创建和绘制该目标。Embedded 模式则默认禁止出现在嵌套视图中。

每个渲染请求需要携带上下文，而不是只传一个 Camera：

```kotlin
data class RenderContext(
    val frameId: Long,
    val depth: Int,
    val activeKeys: Set<SurfaceViewKey>,
    val rootViewRevision: Long,
)
```

`SurfaceViewKey` 至少包含 Surface/Camera UUID、根视图版本和递归层级。调度器应执行以下规则：

1. `depth >= maxNestedDepth` 或目标已经在 `activeKeys` 中时，立即使用上一帧、环境色或黑色占位，并记录被截断的依赖边。
2. 同一 `SurfaceViewKey` 在一帧只渲染一次；其他观察者共享其 Target。缓存键还要包含 Surface 变换、分辨率和资源版本。
3. 先调度无环依赖，再处理有环部分；遇到重复节点就在重复边处截断，而不是尝试求完整的递归闭包。
4. 嵌套视图的 Surface 策略固定为 `CACHE_ONLY`：命中有效 Texture 才采样，未命中不触发同步渲染。
5. 除递归深度外还要限制每帧 Target 数量、总像素数和更新时间；低于可见面积阈值或背向观察者的目标可以继续使用旧缓存。

等价的核心逻辑如下：

```text
renderView(key, context):
  if context.depth >= maxDepth or key in context.activeKeys:
      return fallback(key)
  if cache.valid(key):
      return cache.sample(key)
  active.add(key)
  try:
      drawWorld(key, nestedSurfaces = CACHE_ONLY)
      cache.store(key)
      return cache.sample(key)
  finally:
      active.remove(key)
```

第一版建议统一采用“主视图可渲染 Screen/Mirror；所有非主视图内部只使用 Texture + `CACHE_ONLY`；最大嵌套深度为 1；无缓存使用占位色”的策略。这样 Texture 提供资源隔离，深度限制和路径循环检测提供终止性，缓存调度避免重复绘制。后续若开放深度 2，必须先加入依赖图调度、像素预算和诊断信息（Target 命中/未命中、截断次数、循环路径、每帧 Target 数）。

至少需要覆盖以下回归场景：Screen 自引用（A→A）、两节点环（A→B→A）、Mirror→Screen→Mirror、多个 Surface 共享同一 Camera，以及目标尚未生成时的占位回退。验证标准是渲染调用始终返回、帧内 Target 数量有上限，并且不会在嵌套绘制中再次进入 Embedded 路径。

### 镜面数据字段

镜面可以复用 Screen 基座实体，增加以下字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `surface_type` | enum | `screen` 或 `mirror` |
| `mirror_tint` | ARGB | 反射颜色乘数，默认不染色 |
| `mirror_roughness` | float | 预留粗糙度；第一版只允许 0（完美平面镜） |
| `reflection_update_rate` | int | 反射 Target 刷新上限 |
| `max_reflection_depth` | int | 第一版强制为 1 |
| `reflection_clear_color` | ARGB | 反射不可见或未加载时的占位色 |
| `flip_x`, `flip_y` | bool | 仅控制最终 UV 映射，不改变反射几何 |

镜面不保存反射 Camera UUID。运行时可生成稳定的 `reflection_key = screen_uuid + main_view_revision + surface_revision`，用于 Frame Cache；主 Camera 移动、镜面变换变化或资源重载都会使该键失效。

### 指令与 API 预案

```text
/differangle mirror create <screen-id>
/differangle mirror remove <screen-id>
/differangle mirror tint <screen-id> <color>
/differangle mirror fps <screen-id> <value>
/differangle mirror depth <screen-id> <value>
/differangle mirror roughness <screen-id> <value>
```

```kotlin
interface MirrorController {
    fun setType(screen: ScreenHandle, type: SurfaceType)
    fun setTint(screen: ScreenHandle, argb: Int)
    fun setUpdateRate(screen: ScreenHandle, fps: Int)
    fun setMaxDepth(screen: ScreenHandle, depth: Int)
    fun snapshot(screen: ScreenHandle): MirrorSnapshot?
}
```

`MirrorController` 只修改 Surface 配置；反射矩阵由渲染阶段根据主 Camera 快照计算。这样镜子会自然跟随主视角移动，同时不会污染用户创建的 Camera 实体或 Replay 轨迹。

### 与普通 Screen 的边界

镜子和普通 Screen 共用基座、几何变换、分辨率、更新调度、Target 池和资源生命周期；它们的差异仅在 View 来源和合成规则：

```text
普通 Screen: Camera UUID → Camera View → Target → Surface
镜子:       Main View + Plane → Reflected View → Target → Surface
```

镜子默认不支持独立 Camera UUID、Replay 轨迹和跨维度反射。需要固定视角、路径动画或 Replay 控制时，应创建普通 Camera 并绑定 Screen。

### 实现顺序

1. 先把 `surface_type` 和镜面字段加入 Screen 配置校验，但仍显示占位色。
2. 实现平面反射矩阵和反射 Frustum 单元测试，覆盖任意旋转镜面、背面观察和远距离坐标。
3. 在 Texture 模式完成单次反射、镜面裁剪和 Target 缓存。
4. 加入镜面可见性检测、动态分辨率和更新预算。
5. 最后再做 Embedded 镜面 Stencil/深度合成和递归深度 2。

镜子方案不改变 Camera 实体和 Replay 的数据契约；它是基于主视角派生 View 的 Surface 类型。

## 生命周期和一致性规则

1. 放置 Screen 基座：生成新的 `screen_uuid`，默认未绑定 Camera。
2. 创建/加载 Camera 实体：注册 UUID；若 UUID 已存在，拒绝重复注册并显示错误。
3. 绑定时只保存 UUID，不保存跨世界实体引用。
4. Camera 移动、轨迹推进、FOV 改变都会增加运行时 revision，并使相关 Frame Cache 失效。
5. 方块破坏时释放 Screen 的 GPU/运行时资源，保留 Camera 实体。
6. Camera 删除时 Screen 进入未绑定状态，显示占位色并保留 `camera_uuid` 以便恢复；提供显式“解绑并清空”操作。
7. 客户端世界切换或资源重载时释放所有 GPU 资源，重新扫描方块实体和 Camera 实体。
8. 网络/存档加载的数据全部经过范围校验；非法 FOV、分辨率、尺寸、NaN 坐标和过长轨迹必须拒绝或回退默认值。

## 分阶段实现建议

下一阶段先做数据和可观察性，不直接上完整 Replay：

1. `ScreenBlockEntity` + `screen_base` 方块，完成 NBT、放置/破坏和 UUID 生成。
2. `CameraEntity` 的 Invisible、无碰撞、UUID 注册和基础同步。
3. `CameraController`，让现有 `CameraDefinition` 从实体快照生成；保留现有客户端指令作为兼容入口。
4. `/differangle screen ...` 与 `/differangle camera ...` 的绑定、配置和状态指令。
5. 两点 move/look 插值，再加入 step/linear/smoothstep。
6. CameraPath 和关键帧列表，最后接 Replay 适配器与 cubic。
7. 基于方块实体朝向计算 Screen Surface，并补齐支架外观。

这样每一步都能在游戏内验证 UUID 绑定、实体隐身、数值保存、位置控制和缓存失效，再扩展到复杂轨迹。

## 未决问题

- Camera 实体由客户端独有还是由服务端生成并同步？当前建议先客户端独有。
- Screen 方块是否允许多人看到各自不同的客户端 Camera？若允许，绑定必须明确是本地绑定还是共享绑定。
- 屏幕尺寸的权威来源是方块状态、方块实体 NBT 还是相邻支架数量？当前建议方块状态只保存朝向，数值在方块实体。
- `path_time` 使用 tick 还是秒？当前建议使用 tick 的 double，Replay 导入时保留原始时间换算。
- Camera 是否允许跨维度？若允许，Screen 必须显示未解析状态，禁止引用其他 ClientLevel 的对象。
- 方块复制、活塞移动和结构方块复制时如何处理 UUID？默认复制生成新 Screen UUID，Camera UUID 不自动复制。
