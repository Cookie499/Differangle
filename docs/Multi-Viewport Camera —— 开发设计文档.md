# Minecraft Multi-Viewport Camera

> 资源化方案（Screen 基座/支架方块、Invisible Camera 实体、UUID 绑定、轨迹插值、Replay 适配）见 [camera-entity-screen-blocks.md](camera-entity-screen-blocks.md)。镜子（反射 View、单次反射、Texture/Embedded 预案）也记录在该文档中。该文档记录下一阶段的数据契约和实现顺序。

## 1. 项目定位

一个基于 **Fabric / Minecraft 26.2** 的多视角摄像机与屏幕系统。Camera 与 Screen 由世界实体/方块实体持久化并同步，画面生成和合成在客户端完成。

核心目标：

- 在世界任意位置创建虚拟 Camera。
- Camera 支持任意位置、任意旋转、FOV。
- Camera 支持关键帧与移动路径。
- Camera 可以输出实时画面。
- 输出画面可以显示到任意位置、任意旋转、任意尺寸的 3D Screen。
- 多个 Screen 可以共享同一个 Camera。
- 多个 Camera 可以同时存在。
- 尽可能复用 Minecraft 主视角已经准备好的渲染数据。
- 尽可能避免每个 Camera 重复执行完整的世界渲染与后处理。
- 优先支持 Vanilla / Sodium / Iris，完整 Shader Multi-View 作为高级目标。
- 不把 Camera 的世界加载范围作为核心限制；Camera 使用客户端当前 World 数据进行渲染。

项目本质：

> **在同一个 Minecraft World 中提供多个 View，并把 View 合成到主画面，而不是创建多个独立 Minecraft 客户端。**

---

# 2. 核心设计原则

## 2.1 Geometry Once, View Many

世界几何尽量只准备、构建和上传一次。

```text
World Geometry
      │
      ├── Main View
      ├── Camera A
      ├── Camera B
      └── Camera C
```

尽量共享：

- Chunk Mesh
- Vertex Buffer
- Index Buffer
- Texture
- Material
- Entity RenderState
- Block Entity RenderState
- 其他与 View 无关的 GPU Resource

View 自身只维护：

- View Matrix
- Projection Matrix
- FOV
- Frustum
- Visibility Set
- Viewport / Scissor / Stencil
- Depth State
- Camera-specific history

---

## 2.2 CameraEntity 持久化，CameraSnapshot 提供 View

每个可被 Screen 绑定的 Camera 必须对应一个世界持久化 `CameraEntity`。实体 UUID 是稳定标识，实体坐标、旋转、FOV、裁剪面、开关和轨迹状态是权威数据；客户端每 tick 从实体同步状态派生不可变的 `CameraDefinition`/`CameraSnapshot`，供渲染器构造 View。

```kotlin
data class CameraDefinition(
    val id: UUID,
    val position: Vec3,
    val rotation: Quaternionf,
    val fov: Float,
    val nearPlane: Float,
    val farPlane: Float,
    val enabled: Boolean
)
```

CameraSnapshot 只描述当前渲染时刻：

> “从哪里看、朝哪里看、用什么 Projection 看。”

它不承担持久化。删除 CameraEntity 后，客户端快照与 GPU 缓存随之失效；CameraEntity 关闭时，绑定 Screen 显示黑色并停止该 Camera 的所有渲染工作。

---

## 2.3 Screen 是 Surface，而不是 Camera

Screen 不拥有世界渲染逻辑，但 `ScreenBlockEntity` 持久化绑定 UUID、Transform、分辨率、FPS 和 `enabled`。关闭 Screen 时保留基座、边框和连接杆，显示面使用固定黑色；它不采样 Texture，也不向调度器提出 Camera 更新需求。关闭 Camera 时所有绑定 Screen 同样显示黑色，并停止该 Camera 的 Culling、世界绘制、后处理和 Target 更新。

```text
Camera
   ↓
View
   ↓
Color / Depth / ...
   ↓
Screen Surface
```

这样：

```text
Camera A
   │
   ├── Screen 1
   ├── Screen 2
   └── Screen 3
```

只产生一次 Camera View。

Camera 更新条件是至少存在一个开启、已解析、正面可见的绑定 Screen。显示 Quad 是单面的：局部 `+Z` 为正面，CPU 需求判断和 GPU Raster 都剔除背面。背面不显示画面、不触发 Camera 更新；屏幕外壳或背板作为独立普通几何处理。

---

# 3. 两种 Camera Rendering Architecture

项目支持两类 Camera。

## 3.1 Texture Camera

传统稳定方案：

```text
Camera
  ↓
RenderTarget
  ↓
Color Texture
  ↓
Screen
```

优点：

- 实现简单
- Camera 与主画面解耦
- 可以独立更新
- 可以缓存整帧
- 容易实现不同分辨率
- 最容易兼容现有 Renderer

缺点：

- Camera 需要额外的 Color Render
- Camera 之后还需要把 Texture 再画到 Screen
- 如果 Camera 自己运行完整 Post Process，会产生重复计算

---

## 3.2 Embedded Camera

高级方案：

```text
Main Render
    │
    ├── Main World
    │
    ├── Camera A View
    │
    ├── Camera B View
    │
    └── Camera C View
            ↓
       Main Framebuffer
```

Camera 不一定先生成完整的最终 Texture。

而是：

> **直接将 Camera View 的几何绘制到主 Render Pass 的指定 Screen Region。**

Screen 使用：

- View Transform
- Projection Transform
- Scissor
- Stencil
- Camera-specific Depth

控制 Camera 内容如何进入主画面。

这是长期目标。

---

# 4. Embedded Camera 的核心思想

传统方式：

```text
Camera A
   ↓
完整渲染
   ↓
Texture A
   ↓
Screen
   ↓
主画面
```

Embedded 方式：

```text
World Geometry
       │
       ├── Main View
       │
       └── Camera A View
                │
                ↓
         Screen Region
                │
                ↓
         Main Framebuffer
```

关键思想：

> **Camera 不等于一张预先生成的最终图片，而是主 Render Pipeline 中的另一个 View。**

---

# 5. FOV 与 Projection

FOV 本身不是问题。

主 Camera：

```text
FOV = 70°
```

Camera A：

```text
FOV = 120°
```

二者分别拥有：

```text
MainProjection
CameraProjection
```

理论上的顶点变换：

```text
Main:
MainProjection * MainView * Model * Position

Camera:
CameraProjection * CameraView * Model * Position
```

因此每个 Camera 可以独立拥有：

- FOV
- Aspect Ratio
- Near Plane
- Far Plane
- Projection Matrix

---

# 6. Embedded Camera 最大的问题：Screen Region

Camera A 的 Projection 可能产生自己的 Clip Space。

但它最终只能显示在：

```text
Screen A
```

区域。

因此需要：

- Scissor
- Stencil
- Screen Plane Mask
- Portal-style clipping

例如：

```text
Main Framebuffer

┌──────────────────────────────┐
│                              │
│       Main World             │
│                              │
│        ┌──────────┐          │
│        │ Camera A │          │
│        │          │          │
│        └──────────┘          │
│                              │
└──────────────────────────────┘
```

Camera A 的 Draw 不能写入 Screen 之外。

---

# 7. 任意旋转 Screen

由于 Screen 可以任意旋转：

```text
rotation = Quaternionf
```

Screen 在主 Camera 中看到的区域不一定是矩形。

例如：

```text
        ┌─────────────┐
       /             /
      /   Screen    /
     /             /
    └─────────────┘
```

因此不能总是依赖简单的 `Viewport`。

推荐：

```text
Screen Geometry
      ↓
Stencil / Polygon Mask
      ↓
Camera View
```

Camera View 只允许写入 Screen 对应区域。

---

# 8. Screen 与 Camera Projection 的关系

Camera View 和 Screen Transform 是两个不同空间。

```text
World Space
     ↓
Camera View Space
     ↓
Camera Projection
     ↓
Camera Clip Space
     ↓
Screen / Portal Mapping
     ↓
Main Framebuffer
```

Screen 自身拥有：

```text
position
rotation
width
height
```

因此：

```text
Camera FOV
```

与：

```text
Screen 透视
```

是相互独立的。

---

# 9. Depth 是 Embedded Camera 的关键问题

主 Camera：

```text
Screen distance = 5 blocks
```

Camera A：

```text
Camera A:
Zombie = 20 blocks
Wall = 40 blocks
```

主 Depth：

```text
5
```

Camera Depth：

```text
20
40
```

二者不能直接混用。

因此：

```text
Main Depth
```

与：

```text
Camera Depth
```

必须作为不同 View Space 的信息管理。

---

# 10. Virtual Depth

Camera A 的 Depth 属于：

```text
Camera A View Space
```

如果要将其合成到主画面，需要进行：

```text
Camera Depth
      ↓
Screen / Portal Transform
      ↓
Main Framebuffer Depth Space
```

最终达到：

```text
Screen Surface
        +
Camera Virtual World
```

同时不会让 Camera 内部对象错误地与 Screen 外部世界发生深度冲突。

---

# 11. Shared Geometry

无论使用 Texture Camera 还是 Embedded Camera：

```text
Chunk Mesh
```

应该尽可能只生成一次。

例如：

```text
Chunk 123 GPU Buffer
       │
       ├── Main View
       ├── Camera A
       ├── Camera B
       └── Camera C
```

不同 View 共享：

- Vertex Buffer
- Index Buffer
- Texture
- Material

只改变：

- View Matrix
- Projection Matrix
- Visibility
- Depth State
- Target Region

---

# 12. Shared RenderState

Minecraft 26.2 的 World Rendering 正朝：

```text
Extraction
    ↓
RenderState
    ↓
Drawing
```

方向发展。

因此理想结构：

```text
World
 ↓
Shared Extraction
 ↓
RenderState
 ├── Main View
 ├── Camera A
 ├── Camera B
 └── Camera C
```

目标：

> **一次 Extraction，多 View Drawing。**

不能假定所有原版 Renderer 都已经完全 View-independent，具体实现必须针对 26.2 当前 API 验证。

---

# 13. Main Camera 可以“替 Camera 完成什么”

这是 Embedded Camera 的重要优化点。

Camera 最终显示在：

```text
Main Framebuffer
```

中。

因此部分 Final Post Process 不需要为每个 Camera 重复执行。

例如：

```text
Camera A
    ↓
Screen
    ↓
Main Composite
```

可以让：

```text
Bloom
Tone Mapping
Color Grading
Exposure
Vignette
部分颜色效果
```

在主画面统一处理。

---

# 14. Post Process 共享原则

目标结构：

```text
Main World
       \
Camera A \
Camera B  → Main Composition → Post Process → Final
Camera C /
```

避免：

```text
Main → Bloom
Camera A → Bloom
Camera B → Bloom
Camera C → Bloom
```

即：

```text
N Cameras
+
1 Shared Main Post Process
```

而不是：

```text
N Cameras
+
N Post Process
```

---

# 15. Bloom

Camera A：

```text
HDR Color
```

直接进入主画面：

```text
Screen
   ↓
Main Bloom
```

因此 Camera A 无需再独立做一次 Bloom。

最终：

```text
Camera A bright light
        ↓
Screen
        ↓
Main Bloom
```

可以自然产生主画面层面的 Bloom。

---

# 16. Tone Mapping / Color Grading

类似：

```text
Exposure
Contrast
Saturation
LUT
Temperature
Tone Mapping
```

都适合尽可能延后到主画面。

Camera 输出尽量保持：

```text
Intermediate Color
```

而不是：

```text
Final Display Color
```

---

# 17. DOF

DOF 同时存在于：

```text
Camera A
```

和：

```text
Main Camera
```

时可能产生双重模糊。

推荐：

```text
Camera A
   ↓
Color + Camera Depth
   ↓
Screen
   ↓
Main Camera DOF
```

默认不在 Camera 内执行完整 DOF。

这样 DOF 首先把 Screen 当成真实的主世界表面，再依据 Screen 与主 Camera 的关系决定清晰度。

---

# 18. Camera Color + Camera Depth

标准 Camera Target：

```text
Color
Depth
```

高级 Camera Target：

```text
Color
Depth
Normal
Motion Vector
```

用途：

```text
Color
    → Screen

Depth
    → Fog / DOF / Depth Effects

Normal
    → Advanced Effects

Motion Vector
    → TAA / Motion Blur / Temporal Effects
```

---

# 19. TAA

TAA 不能简单共享主 Camera History。

需要：

```text
Main Camera
  └── Main History

Camera A
  └── Camera A History

Camera B
  └── Camera B History
```

每个 Camera 可能需要：

```text
Current Color
Previous Color
Current Depth
Previous Depth
Current Transform
Previous Transform
```

第一阶段：

```text
Camera TAA = disabled
```

第二阶段再实现。

---

# 20. Motion Vector

高级 Camera Target：

```text
Color
Depth
Velocity
```

用于：

- TAA
- Motion Blur
- Temporal effects

Camera 的 Camera-to-Camera motion 必须独立于 Main Camera。

---

# 21. Particle Rendering

粒子应视为：

```text
Camera World Rendering
```

使用 Camera 自己的：

- Position
- Rotation
- Frustum
- View Matrix

而不是默认使用 Player Camera。

原则：

```text
Particle State
      ↓
Camera A View
```

而不是：

```text
Particle State
      ↓
Main Player Camera
```

---

# 22. Particle Layer

未来可以把粒子与低频地形分层：

```text
Static World
      +
Entity Layer
      +
Particle Layer
      ↓
Camera Composite
```

例如：

```text
Terrain     5 FPS
Entities   15 FPS
Particles  30 FPS
Screen      Main FPS
```

这样可以降低静态 Camera 的更新成本，同时保持动态效果。

---

# 23. Shadow

Shadow 属于世界空间 / 光照阶段，不是简单 Final Post Process。

因此：

```text
Main Shadow
```

不能直接等同：

```text
Camera A Shadow
```

但可以共享部分：

- Shadow Resource
- Light Information
- Static Shadow Data

具体可共享程度取决于 Renderer / Shader Backend。

第一阶段：

```text
Camera Shadow = simplified / independent
```

高级阶段：

```text
Shadow Resource Reuse
```

---

# 24. SSR / SSAO / Screen-space Effects

这类效果强依赖 Camera 自己的：

```text
Depth
Normal
View
Projection
```

因此不能简单交给主 Camera。

例如：

```text
Main Depth
```

不能代表：

```text
Camera A Depth
```

Camera A 如果要执行 SSR / SSAO，必须维护自己的 View Space 数据。

第一阶段建议：

```text
Camera SSR = disabled
Camera SSAO = simplified / disabled
```

---

# 25. Fog

Fog 通常依赖：

```text
Camera Position
Distance
Depth
World Environment
```

因此可以在 Camera 阶段计算。

也可以根据实现情况拆成：

```text
Camera Depth
     ↓
Camera Fog
```

然后再进入：

```text
Screen
```

Fog 是否延后到主 Composite，需要根据具体 Fog 类型决定。

---

# 26. Iris / Shader Compatibility

Iris 是最复杂的 Backend。

Shader Pipeline 可能包含：

```text
GBuffer
Shadow
Composite
Final
Depth
TAA
SSR
Custom Uniform
```

不能默认：

```text
切换 Camera Matrix
        ↓
Iris 自动支持第二 View
```

完整 Multi-View Iris 需要：

```text
Camera A
├── GBuffer A
├── Depth A
├── History A
├── Uniform A
└── Composite A
```

因此 Iris 必须设计成独立 Backend。

---

# 27. Shader Compatibility Mode

默认模式：

```text
Player
    ↓
Iris + Shader

Camera
    ↓
Vanilla-compatible rendering
```

Camera 提供：

- Terrain
- Entity
- Block Entity
- Particle
- Weather
- Sky
- Depth

优点：

- 稳定
- 成本低
- 高兼容性

---

# 28. Shader Multi-View Mode

高级模式：

```text
Main View
   ↓
Iris

Camera A
   ↓
Iris Camera Pipeline

Camera B
   ↓
Iris Camera Pipeline
```

需要为每个 Camera 管理独立：

- GBuffer
- Depth
- History
- Uniform
- Composite
- View State

但尽可能共享：

- Shader Program
- Texture
- Material
- Static Resource

---

# 29. Embedded Camera 的 FOV / Projection 解决方案

每个 View 维护：

```kotlin
data class CameraProjection(
    val fov: Float,
    val aspect: Float,
    val nearPlane: Float,
    val farPlane: Float
)
```

GPU 逻辑概念：

```glsl
Main:
    gl_Position =
        mainProjection *
        mainView *
        model *
        position;

Camera A:
    gl_Position =
        cameraProjection[A] *
        cameraView[A] *
        model *
        position;
```

未来可以进一步使用：

```text
View ID
```

统一处理多个 Camera。

---

# 30. Multi-View Rendering

高级目标：

```text
View 0 = Main
View 1 = Camera A
View 2 = Camera B
View 3 = Camera C
```

共享：

```text
Geometry
Texture
Material
RenderState
```

独立：

```text
View Matrix
Projection Matrix
Depth
Visibility
History
Viewport
```

概念：

```text
One Geometry
     ↓
Multiple Views
```

---

# 31. Camera Visibility

Geometry 可以共享，但 Visibility 不一定。

```text
Main Visible Chunks
        ≠
Camera A Visible Chunks
```

因为两个 View 的 Frustum 不同。

因此：

```kotlin
data class CameraVisibilityCache(
    val visibleChunks: LongSet,
    val visibleEntities: List<EntityRenderState>,
    val lastPosition: Vec3,
    val lastRotation: Quaternionf,
    val valid: Boolean
)
```

---

# 32. Visibility Reuse

当 Camera 没有明显移动：

```text
Position delta < threshold
Rotation delta < threshold
```

可以直接复用：

```text
Visibility Cache
```

只有达到阈值时：

```text
Recalculate Visibility
```

---

# 33. Main Camera Visibility Reuse

如果：

```text
Camera 与 Main View 接近
```

可以部分复用主 Camera 的 Visibility。

例如：

```text
距离很近
+
方向差很小
```

则：

```text
Main Visibility
    ↓
Camera Visibility Candidate
```

但不能假设完全相同。

即使 Camera 很远：

```text
Geometry Buffer
```

依然可以共享。

---

# 34. Path Visibility Cache

Camera 沿固定轨迹：

```text
P0 ─ P1 ─ P2 ─ P3
```

可以预先计算：

```text
Path Bounding Volume
```

并建立：

```text
Potentially Visible Chunks
```

运行时主要改变：

```text
Camera Matrix
```

而不重新建立所有 Geometry。

---

# 35. Camera Scheduler

多个 Camera 不能同时无条件刷新。

每个 Camera：

```text
updateRate
priority
visibility
screenSize
```

由统一 Scheduler 调度。

例如：

```text
Frame 1:
Camera A

Frame 2:
Camera B

Frame 3:
Camera C

Frame 4:
Camera A
```

避免某一帧出现：

```text
10 个 Camera 同时 Render
```

导致 GPU Spike。

---

# 36. Camera Priority

可以根据：

```text
Screen Projected Size
×
Distance
×
Visibility
×
User Priority
```

计算 Camera Priority。

例如：

```text
巨大近距离电视
    ↓
High

远处小监控
    ↓
Low
```

---

# 37. Dynamic Resolution

Camera 分辨率应该根据其在主画面的实际大小决定。

例如：

```text
Screen = 800 px
     ↓
512×288

Screen = 300 px
     ↓
256×144

Screen = 100 px
     ↓
128×72
```

过小的 Screen：

```text
< 40 px
```

可以极低频更新或停止更新。

---

# 38. Screen-aware Rendering

Embedded Camera 的一个巨大优势：

Camera 不需要生成一个完整的：

```text
1920×1080
```

Frame。

如果 Screen 只有：

```text
300×180 px
```

那么 Camera View 只需要覆盖：

```text
Screen Region
```

利用：

- Viewport
- Scissor
- Stencil
- Screen Projection

把 GPU Rasterization 限制在实际需要的区域。

---

# 39. Frame Cache

Camera 不刷新时：

```text
CameraFrame
   ↓
reuse
```

而不是重新 World Render。

每个 Camera：

```kotlin
data class CameraFrameCache(
    val color: Texture,
    val depth: Texture?,
    val timestamp: Long,
    val valid: Boolean
)
```

---

# 40. Static Camera Optimization

固定 Camera：

```text
Camera does not move
```

可以：

```text
Static World → low-frequency render
Dynamic Entities → higher-frequency render
Particles → separate update
```

例如：

```text
Static World   1 FPS
Entities      15 FPS
Particles     30 FPS
Screen        144 FPS
```

最终通过 Composite 得到：

```text
Apparent Camera Display
```

---

# 41. Geometry / Frame / Visibility 三层缓存

整体缓存架构：

```text
World Geometry
      ↓
Geometry Cache
      ↓
Visibility Cache
      ↓
Camera Frame Cache
      ↓
Screen
```

分别解决：

### Geometry Cache

避免重复生成和上传世界几何。

### Visibility Cache

避免重复进行 Camera Culling。

### Frame Cache

避免 Camera 每个主帧都重新渲染。

---

# 42. 性能模型

核心成本：

```text
Camera Cost ≈
Resolution
×
Visible Geometry
×
Render Features
×
Update Frequency
```

因此优化方向：

```text
降低 Resolution
降低 Update Frequency
减少 Visible Geometry
减少 Render Features
复用 Geometry
复用 Extraction
复用 Post Process
```

Screen 数量本身不是最重要的成本。

例如：

```text
1 Camera
20 Screens
```

主要成本仍接近：

```text
1 Camera Render
+
20 Screen Draw
```

而不是 20 次 Camera Render。

---

# 43. Texture Camera vs Embedded Camera

| 项目 | Texture Camera | Embedded Camera |
|---|---|---|
| 实现难度 | 低 | 很高 |
| RenderTarget | 需要 | 可选 |
| Camera FPS | 独立 | 独立 |
| Frame Cache | 很方便 | 较复杂 |
| Geometry Sharing | 支持 | 支持 |
| Main Post Process 复用 | 部分 | 很强 |
| FOV 控制 | 容易 | 容易 |
| 任意 Screen | 容易 | 需要 Stencil/Clip |
| Depth | 简单 | 很复杂 |
| TAA | 独立 | 很复杂 |
| Iris | 较容易隔离 | 极复杂 |
| 性能上限 | 高 | 更高 |
| 开发风险 | 低 | 很高 |

---

# 44. 推荐采用 Hybrid Architecture

最终系统不要强制所有 Camera 使用同一种方式。

```text
Camera
   │
   ├── Texture Mode
   │
   └── Embedded Mode
```

推荐：

### Texture Mode

适合：

- 大型电视
- 远程 Camera
- 高缓存要求
- Iris Compatibility Mode
- 复杂 Camera Effect

### Embedded Mode

适合：

- 小型 Screen
- 低延迟 CCTV
- Screen 数量多
- 希望复用主 Shader
- 希望共享最终 Post Process

---

# 45. Hybrid 自动选择

可以依据：

```text
Screen Size
Screen Count
Camera Distance
Camera Update Rate
Renderer Backend
Shader State
```

自动选择。

例如：

```text
小监控：
Embedded

大型电视：
Texture

Iris Complex Shader：
Texture / Compatibility

大量同 Camera Screen：
Texture

低延迟近距离 Screen：
Embedded
```

---

# 46. 推荐默认策略

```yaml
camera:
  mode: auto

  enabled: true

  resolution:
    default: 256x144

  update-rate: 15

  fov: 70

render:
  particles: true
  clouds: false
  weather: true
  shadows: simplified

  post-process: deferred

screen:
  enabled: true
  disabled-color: "#000000"
  render-back-face: false

cache:
  geometry: true
  visibility: true
  frame: true

scheduler:
  enabled: true
  max-updates-per-frame: 1

iris:
  mode: compatibility
```

---

# 47. 开发阶段

## Phase 1：基础 Camera

实现：

```text
Camera
RenderTarget
Screen
任意位置
任意旋转
FOV
Camera FPS
```

目标：

```text
Camera
 ↓
RenderTarget
 ↓
Screen
```

---

## Phase 2：多 Camera

加入：

```text
Multiple Cameras
Camera → Multiple Screens
Frame Cache
RenderTarget Pool
```

---

## Phase 3：性能优化

加入：

```text
Geometry Cache
RenderState Reuse
Visibility Cache
Scheduler
Dynamic Resolution
```

---

## Phase 4：Camera Path

加入：

```text
Keyframe
Bezier / Catmull-Rom
Quaternion Slerp
FOV Animation
Path Visibility Cache
```

---

## Phase 5：Intermediate Camera Rendering

加入：

```text
Camera Color
Camera Depth
```

并停止要求 Camera 始终输出最终画面。

---

## Phase 6：Main Composite

让：

```text
Camera
 ↓
Screen
 ↓
Main Post Process
```

统一处理：

- Bloom
- Tone Mapping
- Color Grading
- Exposure

---

## Phase 7：Embedded Camera Prototype

验证：

```text
Shared Geometry
+
Different Projection
+
Different View
+
Main Framebuffer
+
Stencil
```

最小测试：

```text
1 Chunk
+
Main Camera
+
Camera A
+
Screen Plane
```

---

## Phase 8：Advanced Multi-View

加入：

```text
View ID
Camera-specific Depth
Camera-specific Visibility
Camera-specific Render State
```

目标：

```text
One Geometry
→
Multiple Views
```

---

## Phase 9：Advanced Effects

加入：

```text
Motion Vector
TAA
Static/Dynamic Layer
Particle Layer
Occlusion
Hi-Z
```

---

## Phase 10：Iris Multi-View

最后才处理：

```text
Iris
Shader Pack
GBuffer
Shadow
Composite
TAA
Multi View
```

---

# 48. 第一阶段 Prototype 的关键验证项目

正式写完整 Mod 前，先做一个最小实验：

```text
1. 创建一个独立 RenderTarget
2. 用非 Player Camera 构造 View Matrix
3. 用独立 FOV 构造 Projection Matrix
4. 绘制一个已存在的 Chunk Geometry
5. 将其输出到 Texture
6. 把 Texture 绘制到旋转 Quad
```

成功后再测试：

```text
7. 同一个 Chunk 使用 Main + Camera A 绘制
8. 两个 View 使用不同 Projection
9. Camera A 使用 Stencil 写入 Screen Region
10. Camera A 与 Main 共享 Geometry
11. Camera A 直接写入 Main Framebuffer
```

这一组实验会决定最终是：

```text
Texture-first
```

还是：

```text
Embedded-first
```

---

# 49. Debug 信息

每个 Camera 必须支持 Debug：

```text
Camera A

Mode: Embedded
Backend: Sodium
FPS: 15
Resolution: 256×144

Visible Chunks: 92
Visible Entities: 18

Geometry Reused: YES
Visibility Cache: HIT
Frame Cache Age: 2

GPU Time: 1.8 ms
CPU Time: 0.4 ms
```

此外显示：

```text
Screen Region
Frustum
Camera Axis
FOV
Path
Visibility Volume
```

---

# 50. 性能测试

必须分别测试：

### 单 Camera

```text
1 × 256×144 @ 15 FPS
```

### 多 Camera

```text
4 × 256×144 @ 15 FPS
```

### 多 Screen

```text
1 Camera
20 Screens
```

### 多 Camera + 多 Screen

```text
8 Cameras
20 Screens
```

### Embedded

```text
4 Embedded Cameras
```

### Path

```text
4 Moving Cameras
```

### Iris

```text
Iris + Shader
+
Multiple Cameras
```

记录：

```text
CPU Render Time
GPU Time
Frame Time
Camera Time
RenderTarget Memory
Visible Chunk Count
Entity Count
Particle Count
```

---

# 51. 最终目标

最终系统希望达到：

```text
                    Shared World
                         │
                ┌────────▼────────┐
                │ Geometry Cache  │
                └────────┬────────┘
                         │
             ┌───────────┼───────────┐
             ↓           ↓           ↓
         Main View    Camera A    Camera B
             │           │           │
         Main View    View A      View B
         Matrix       Matrix      Matrix
             │           │           │
         Main Depth   Depth A     Depth B
             │           │           │
             │       Screen A    Screen B
             │           │           │
             └───────────┼───────────┘
                         ↓
                  Main Composition
                         ↓
              Shared Post Processing
                         ↓
                     Final Frame
```

核心理念：

> **共享 Geometry，独立 View；共享最终后处理，独立 Camera 空间数据。**

最终不是：

```text
N 次完整 Minecraft 渲染
```

而是：

```text
一次共享数据准备
+
N 个相对轻量的 View
+
一次主画面 Final Composite
```

---

# 52. 技术优先级

最高优先级：

```text
① Shared Geometry
② Shared RenderState / Extraction
③ Independent Camera Projection
④ Camera Scheduler
⑤ Dynamic Resolution
```

第二优先级：

```text
⑥ Camera Color + Depth
⑦ Main Post Process Reuse
⑧ Visibility Cache
⑨ Frame Cache
```

高级：

```text
⑩ Embedded Camera
⑪ Multi-View
⑫ Motion Vector
⑬ TAA
⑭ Static/Dynamic Layer
⑮ Hi-Z / Occlusion
```

最后：

```text
⑯ Iris Multi-View
```

---

# 53. 最终架构原则

项目开发过程中必须保持以下约束：

1. **Geometry Once, View Many**
2. **CameraEntity 是持久化权威，CameraSnapshot 是渲染 View**
3. **Screen 与 Camera 解耦**
4. **一个 Camera 可以服务多个 Screen**
5. **Screen 数量不应线性增加 World Render**
6. **Camera FPS 与 Main FPS 分离**
7. **Geometry Cache、Visibility Cache、Frame Cache 分层**
8. **Color 可以尽可能延迟到 Main Composite**
9. **Depth 必须保持 Camera-specific**
10. **主 Camera 后处理尽量只做一次**
11. **Iris 必须作为独立 Rendering Backend**
12. **26.2 渲染实现优先使用 Blaze3D / RenderPipeline 抽象，而非 Raw OpenGL**
13. **Embedded Camera 是性能优化方向，不作为第一版本阻塞条件**
14. **Texture Camera 是稳定的 fallback**
15. **最终目标是 Multi-Viewport Rendering，而非多个独立 Minecraft Render**
16. **Camera/Screen 关闭时黑屏并停止对应 View 工作，背向 Screen 不产生渲染需求**
