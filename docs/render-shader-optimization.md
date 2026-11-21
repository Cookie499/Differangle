# 摄像机光影降本与渲染优化 —— 深度方案文档

日期：2026-10-11。范围：Texture 主路径 + Iris 1.11.2 光影后端的单台/多台摄像机成本优化设计。
本文档是纯设计方案，不包含代码改动；所有 file:line 引用基于撰写时工作树。
证据来源：本仓库源码、docs/render-performance-review.md、Iris 1.11.2 与 Blaze3D 26.2 的只读 API 验证（见第 10 节索引）。

## 0. 结论摘要

1. 单台摄像机一次光影刷新 = 一整条 Iris 管线（阴影 → G-buffer → 地形固体/镂空 → 实体/方块实体/粒子 → 半透明 → composite/final）。当前每项都按画面频率全量执行，没有任何跨刷新缓存。
2. 最贵的可降本项是 renderShadows（每刷新全量阴影，IrisShaderCameras.kt:121），其次是地形双 pass 每 pass 清一次全部区域批次缓存（:225/:239），以及每次刷新全半径区块扫描（:187-201）。
3. Iris 1.11.2 证据：阴影纹理除首帧/分辨率变更外**每帧不清空**（ShadowRenderTargets.fullClearRequired 生命周期）；ShadowRenderer.renderShadows 自包含保存/恢复全部全局状态；ShadowRenderer 静态矩阵停留在上一帧值就是复用阴影贴图的前提。因此"阴影分频"在 API 层面可行。
4. 本方案采用双轨：**无损轨**（默认）只做不改变输出像素的复用与合并；**高性能轨**（显式开关）以阴影分频为主，质量代价与失效规则写清楚。
5. 先建 GPU 分段计时，再做优化，最后用固定场景 A/B 帧时间与既有回归验收；不承诺未经测量的具体 FPS 数字。

## 1. 范围、基线与非目标

- 主线：降低**每台摄像机**的光影刷新成本。
- 基线场景：光影开启、三个屏幕绑定同一台摄像机、分辨率/FPS 按用户配置（用户读数：约 114 FPS @15FPS 配置、约 45 FPS @60FPS 配置）。同一摄像机多屏共享已成立，本方案不重复讨论。
- 双轨立场：默认保持任意支持光影包的完整语义（无损兼容轨）；另设显式可选的高性能轨，允许阴影以低于画面频率更新（质量代价明确）。
- 非目标：SSBO 光影包支持、Distant Horizons、光线追踪光影包、跨维度摄像机；这些留在路线图。Embedded 模式不使用 Iris 后端，本文档只在路线图涉及。

## 2. 成本模型（证据版）

调用链：DifferangleClient.kt:21（AFTER_SOLID_FEATURES）→ CameraRuntime.render:163（Iris 激活时延迟）→ CameraGameRendererMixin.java:17-19（主世界完成后 renderAfterLevel）→ CameraRuntime.renderNow:177 → CameraSystem.renderFrame（CameraSystem.kt:74-101）→ CameraRuntime.drawTexture:359 → CameraWorldRenderer.prepare/draw → IrisShaderCameras.draw:59 → CameraNativeFeatures.draw（CameraNativeFeatures.kt:64-161）。

单台摄像机一次光影刷新（Texture 模式）的主要成本：

| # | 阶段 | 成本类型 | 证据 | 可缓存/降频 |
| --- | --- | --- | --- | --- |
| A4 | 外壳/调试/底座 solid，每面一次 RenderPass | GPU 提交 | CameraRuntime.kt:203-207 → WorldGeometry.cube 六面各一次 solid | 合批（路线图） |
| B4 | Visibility 全半径区块扫描 (2R+1)²×高度 | CPU | IrisShaderCameras.kt:187-201 | 静止时缓存（5.1） |
| B6 | 动画精灵逐 section 标记 | CPU | IrisShaderCameras.kt:194 | 随 B4 复用 |
| C1 | 20+ 全局状态保存/恢复 | CPU | IrisShaderCameras.kt:66-82, 138-151 | 不可省（隔离必需） |
| C4 | Iris 管线 Entry 复用/首建 | GPU | IrisShaderCameras.kt:91-99 | 已缓存；仅目标变化重建 |
| D5 | renderShadows 全量阴影 | **GPU 最大项** | IrisShaderCameras.kt:121 | 分频（6.1） |
| D7 | onBeginClear G-buffer 清屏 | GPU | IrisShaderCameras.kt:126 | 不可省 |
| D8 | 天空渲染 | GPU | IrisShaderCameras.kt:127 | 不可省（像素量小） |
| D9 | 地形固体+镂空两 pass | GPU | IrisShaderCameras.kt:129 → CameraTerrain.draw:224 | 不可省 |
| D15 | 半透明地形 pass | GPU | IrisShaderCameras.kt:116 → CameraTerrain.draw:224 | 不可省 |
| D5a | 批次缓存清理，每次刷新两次全区域 | CPU | IrisShaderCameras.kt:225 与 :239 | 合并为两次必要点（5.2） |
| D10-D12 | 实体/方块实体/粒子重新提取 | CPU | CameraNativeFeatures.kt:88-131 | 静止时可选复用（5.4） |
| D19 | finalizeLevelRendering 合成 | GPU | IrisShaderCameras.kt:135 | 不可省 |
| E2 | 屏幕表面合成（每可见屏一次） | GPU | CameraSystem.kt:95-100 → CameraCompositor.surface | 已复用缓存纹理，廉价 |

关键结论：

- 每刷新两次 clearAllCachedBatches（:225 进入 draw 时、:239 close 时，固体与半透明两次 draw 各触发一次 :225，实际共三次区域级清理），可减到两次（5.2）。
- Visibility（:172-207）与 CameraTerrain（:216-239）对象每刷新新建；区块扫描、列表构建、距离排序全部重做，静止摄像机时纯浪费（5.1）。
- 环境探针每游戏 tick 对每台摄像机预热 16 项属性，即使摄像机隐藏或低频刷新（CameraEnvironmentSampler.kt:41-64）；可改为仅对即将 due 的摄像机预热（5.3）。
- renderShadows 每刷新全量执行，是最大 GPU 候选；阴影纹理生命周期证据支持分频（6.1）。

## 3. 测量方案（实施前先建立）

### 3.1 现有计量的局限

CameraRuntime.cpuMillis 是 renderNow 起止的墙钟时间，含提交与可能的驱动等待，不代表 GPU 完成时间；drawCalls 只统计地形（CameraRuntime.kt:341-344、CameraWorldRenderer.terrainDraws）。无法定位阴影/合成/屏幕各占多少。

### 3.2 GPU 分段计时设计（CameraGpuTimings）

Blaze3D 26.2 提供完整公开 API（.gradle/vanilla-source 下）：

- GpuQueryPool.java:5-14：size() / getValue(i) / getValues(i,count) 返回 OptionalLong[]，未就绪元素为 OptionalLong.empty()。
- GpuDevice.java:185-187：public GpuQueryPool createTimestampQueryPool(int size)。
- CommandEncoder.java:742-748 与 RenderPass.java:72-78：public void writeTimestamp(GpuQueryPool, int)。
- TimerQuery.java:6-75：官方封装（6 槽 3 组旋转，get() 平均），可参考周期与状态管理。
- DeviceInfo.java:5-17 第 11 组件 float timestampPeriod：差值乘它以得纳秒。

设计（新类 client/render/CameraGpuTimings.kt）：

    class CameraGpuTimings(val segments: List<String>) : AutoCloseable {
        private val pool = RenderSystem.getDevice().createTimestampQueryPool(segments.size * 2 + 2)
        private val period = RenderSystem.getDevice().deviceInfo.timestampPeriod
        private var generation = 0
        fun begin(encoder: CommandEncoder, i: Int) { encoder.writeTimestamp(pool, i * 2) }
        fun end(encoder: CommandEncoder, i: Int) { encoder.writeTimestamp(pool, i * 2 + 1) }
        fun collectIfReady(): Map<String, Double>?  // 非阻塞：任一 OptionalLong 为空则返回 null，下帧再收
    }

规则：

- 独立池，绝不与 TimerQuery/TracyGpuProfiler 共用；成对打点必须落在同一 CommandEncoder（或 render pass 内的 RenderPass.writeTimestamp）。
- 收数非阻塞：getValues 返回空值即跳过本帧采样；保持 2-3 帧旋转避免覆盖未完成查询。
- index 必须 < pool.size()（writeTimestamp 边界检查是 <=，但 getValues 按 index+count>size 抛越界）。
- 统计至少 30 秒预热后采样，报告中位数/P95/P99。

段位划分（与成本表对应）：housing/surfaces、prepare(Visibility+uniform)、stateSwitch、shadows、sky、terrainSolid、entities、blockEntities、particles、terrainTranslucent、weatherClouds、finalize、screenComposite。

### 3.3 固定场景测试矩阵

| 对比 | 目的 |
| --- | --- |
| 0/1/3 屏，同一摄像机、固定最高规格 | 分离显示合成与摄像机世界更新 |
| 光影开/关（摄像机），主视角光影不变 | 摄像机光影增量成本 |
| 阴影分频 1/2/4（高性能轨开关前后） | 分频收益与质量 |
| 256×144 / 1280×720 / 1920×1080 同一实际刷新率 | 像素成本 vs 固定 CPU/阴影成本 |
| 镜头静止 / 连续移动 | 缓存命中 vs 失效路径 |

固定窗口、天气、实体与光影包；先完成区块加载与光影预热；关闭垂直同步/帧率上限。

## 4. 双轨总览与配置

新增配置（ClientConfig，写 config/differangle.json，默认无损轨全开、高性能轨全关）：

| 配置键 | 默认 | 说明 |
| --- | --- | --- |
| cameraVisibilityCache | true | 无损：静止摄像机复用区块候选与排序（5.1） |
| cameraBatchClearMerge | true | 无损：合并批次缓存清理（5.2） |
| cameraProbeLazy | true | 无损：探针仅对即将 due 的摄像机预热（5.3） |
| cameraEntityReuse | false | 可选：静止时复用实体/粒子提取（5.4，实验性） |
| cameraShadowUpdateRate | 1 | 高性能：每 N 次刷新渲一次阴影；1=每刷新（等价关） |
| cameraShadowAdaptive | true | 高性能：时间/配置变化时强制渲阴影，而非只按计数 |
| cameraGpuTimings | false | 测量：启用 CameraGpuTimings 并进入 /differangle status |

两轨正交：无损轨开启与否不改变输出像素；高性能轨开启后阴影更新频率降低，质量代价见 6.1。

## 5. 无损优化设计（默认启用）

### 5.1 Visibility 跨刷新缓存（静止摄像机）

现状：IrisShaderCameras.Visibility（:172-207）每次 prepare（:53-57）重建：全半径三重循环 + 每 section getCurrent + isBuilt/isDisposed + AABB 视锥测试 + ChunkRenderList 构建 + 距离排序。

设计：新类 CameraVisibilityCache，按 camera.id 缓存：

    data class VisibilityKey(position, rotation, fov, near, far, aspect, radius, minSectionY, maxSectionY)
    class CameraVisibilityCache {
        private val entries = mutableMapOf<String, CachedVisibility>()
        fun obtain(definition, level, storage, radius): Visibility  // 命中则复用骨架，未命中则重建
    }

命中路径每刷新只做：遍历缓存的 section 坐标列表 → getCurrent 重新取 section（内容总是现读）→ 复用缓存的 region 顺序与 section→region 映射 → 重建 ChunkRenderList 并 add(index)（便宜）→ 重新收集 animatedSprites 与 blockEntities（数据保持新鲜）。跳过：三重循环半径扫描、AABB 视锥测试、距离排序。

失效规则（必须全部满足才是无损）：

- VisibilityKey 变化（位置/旋转/FOV/裁剪面/宽高比/视距/世界高度范围变化）。
- 区块加载/卸载：订阅 Fabric ClientChunkEvents.CHUNK_LOAD/CHUNK_UNLOAD，只对受影响 section 标记脏；脏位命中时本轮重建。
- 世界切换、资源重载、光影包切换（管线代际变化）：清空整个 cache。
- section 变为 disposed/未 built：命中路径里 getCurrent 取不到或状态异常时，自动转重建（安全网）。

不需要因网格重建失效：网格内容每帧从 region 缓冲现读，section 索引不变。

验收：摄像机静止时 prepare 阶段不再出现全半径扫描（可用 3.2 计时或计数器验证）；移动/区块加载瞬间恢复正确画面；CameraShaderGameTest 全绿。

### 5.2 合并批次缓存清理（clearAllCachedBatches）

现状：CameraTerrain.draw 在每次调用开头清全部区域批次缓存（:225），close 再清一次（:239）。一次刷新走固体（:129）与半透明（:116）两次 draw，再加 close，共三次 O(区域数) 清理。清理目的是防止主视角复用摄像机批次（render-compatibility.md 已记录该隔离约束）。

设计：保留两处清理，去掉中间冗余：

    fun draw(translucent: Boolean) {
        if (!batchesClearedForRefresh) {
            visibility.lists.keys.forEach { it.clearAllCachedBatches() }
            batchesClearedForRefresh = true
        }
        ...
    }

即：一次刷新内首次 draw 前清一次（隔离主视角批次），close 时再清一次（隔离摄像机批次给主视角）。固体→半透明之间没有主视角插入，中间那次清理纯浪费。

验收：区域级清理次数从每刷新 3 次降为 2 次（计数器或采样验证）；主视角与摄像机画面的批次污染不回归（现有 CameraTransparencyGameTest 与 BSL 截图检查覆盖）。

### 5.3 环境探针按需预热

现状：CameraRuntime.tick:109 每游戏 tick 对全部摄像机调 environments.tick；CameraEnvironmentSampler.tick:41-64 对每台预热 16 项属性，注释明确"即使隐藏或低频刷新也每 tick"。探针 entry() 本身已有惰性补 tick 的安全网（CameraEnvironmentSampler.kt:50-53）。

设计：tick 时只对"即将 due"的摄像机预热。CameraRuntime.tick 计算 due-soon 集合：

    val now = System.nanoTime()
    val dueSoon = system.cameras().filter { cam ->
        val frame = system.frame(cam.id)
        frame == null || now - frame.renderedAtNanos >= cam.intervalNanos - ONE_TICK_NANOS
    }
    environments.tick(level, dueSoon)

未预热的摄像机在真正 due 时由 sample() 的 entry() 安全网按需补 tick（现有逻辑），只影响极端边界的一次插值起点，不改变已预热摄像机的任何数值。

验收：隐藏/低频摄像机的每 tick 预热次数降为 0（计数器验证）；due 摄像机的环境数值与改动前逐项一致（CameraShaderGameTest 像素对比）。

### 5.4 原生内容提取复用（可选，实验性，默认关）

现状：CameraNativeFeatures.draw 每刷新重新遍历 level.entitiesForRendering()（:88-95）、按 Visibility.blockEntities 提取方块实体（:97-111）、从粒子组提取粒子（:112-131）。这些刻意独立于主帧快照（注释 :113-114），正确性优先。

设计（仅静止且 key 命中时复用，默认关闭）：

- key = (camera.id, 位置, 旋转, partialTick 分桶(如 1/20s 桶), 渲染距离)。
- 缓存 SubmitNodeStorage 构建结果；命中则跳过实体/方块实体/粒子的 extract+submit 循环，直接执行 executeSolid/Translucent。
- 失效：key 变化、区块 BE 集合变化（与 5.1 的 CHUNK_LOAD/UNLOAD 及 BE 增删联动，但 BE 增删无事件 → 保守地以固定 TTL 兜底）、天气/粒子组变化（粒子组无事件 → TTL 或默认不缓存粒子）。

风险：动画实体姿态、粒子运动、BE 动画在 TTL 内会静止一瞬。因此默认关，仅作为监控室等静态场景的后续选项。

验收（若开启）：静态场景像素对比与关缓存时一致；动态场景无可见冻结。

### 5.5 明确不做的无损项

- 状态保存/恢复瘦身（IrisShaderCameras.kt:66-82, 138-151）：省的是几十次字段读写，但任何一处漏恢复都会污染主视角，风险远大于收益，不做。
- 复用主视角阴影：不同视角的阴影投影与曝光历史不可直接借用（render-performance-review.md 已结论），不做。

## 6. 高性能模式设计（显式开关，质量代价明确）

### 6.1 阴影分频（核心）

现状：beforeWorld 回调内每刷新调 entry.pipeline.renderShadows（IrisShaderCameras.kt:121），随后恢复本摄像机 gbuffer 矩阵（:124-125）再走 onBeginClear/天空/地形。

API 事实（Iris 1.11.2 javap 证据）：

- IrisRenderingPipeline.renderShadows = (a) shadowRenderer.renderShadows(...)（非空时）+ (b) prepareRenderer.renderAll()（无条件）。
- ShadowRenderer.renderShadows 自包含：进入保存并换入自有 RenderBuffers，出口恢复 buffers、CameraRenderState 矩阵、modelViewStack、CullingDataCache、Sodium 矩阵、viewport、ACTIVE=false、visibleBlockEntities=null。
- 阴影贴图每帧**不清空**：beginLevelRendering 仅在 ShadowRenderTargets.isFullClearRequired()==true 时执行 shadowClearPasses 并 onFullClear()；fullClearRequired 只在构造期与 flip(分辨率变更) 置 true。
- 因此跳过整个 renderShadows 后：ShadowRenderer.MODELVIEW/PROJECTION/FRUSTUM/RESOLUTION/renderDistance 停留在上一帧值（正是复用阴影贴图的前提），阴影纹理保持上一帧内容，地形/半透明/finalize 继续按上一帧阴影采样——输出为"冻结一帧的阴影"，无撕裂。

设计：新类 CameraShadowGate，包住 :121 的调用：

    class CameraShadowGate(private val updateEvery: Int, private val adaptive: Boolean) {
        var lastShadowRenderRefresh = -1
        var lastSunAngle = Float.NaN
        var lastShadowDistance = -1
        var lastShadowResolution = -1
        fun shouldRender(refreshIndex: Int, environment: CameraEnvironment,
                         shadowDistance: Int, shadowResolution: Int): Boolean {
            if (updateEvery <= 1) return true
            if (refreshIndex == 0) return true                                   // 首帧必须
            if (shadowDistance != lastShadowDistance) return true                 // 距离变化触发 flip 清空
            if (shadowResolution != lastShadowResolution) return true
            if (adaptive && abs(environment.sunAngle - lastSunAngle) > SUN_EPS) return true
            return refreshIndex - lastShadowRenderRefresh >= updateEvery
        }
    }

接入点（IrisShaderCameras.draw 的 beforeWorld，:118-131）：

    if (shadowGate.shouldRender(...)) {
        entry.pipeline.renderShadows(...)        // 原 :121
        captured.setGbufferModelView(state.viewRotationMatrix)   // 原 :124
        captured.setGbufferProjection(state.projectionMatrix)    // 原 :125
    }
    entry.pipeline.onBeginClear()               // 原 :126，无条件

跳过帧不进入 renderShadows，因此无需恢复阴影相关状态（没被污染）；:124-125 的矩阵恢复在跳过帧也不需要（renderShadows 未改写 gbuffer）。

强制渲染规则（必须全部放行，否则空贴图/陈旧贴图错误）：

- 首帧、分辨率变化、shadowDistance 变化、光影包切换（管线 Entry 已重建，refreshIndex 归零）。
- adaptive 模式下太阳角变化超过阈值 SUN_EPS（建议 0.5°，约 33 游戏 tick；验证后按光影包调整）。
- 摄像机 FOV/近远裁剪变化（阴影投影依赖视锥）——保守起见也放行。

已知质量代价（写进状态提示）：阴影延迟 = (updateEvery-1)/实际刷新率 秒；移动实体、动态光源的阴影会以该频率跳变。adaptive 只救时间/配置变化，不救移动投影物——这是显式代价，由用户选择。

变体 B（若 V1 画面异常）：B 的证据显示 prepareRenderer.renderAll() 无条件执行，部分光影包的 prepare 合成可能依赖本帧阴影输出。若 V1 跳过后画面错误，改为反射拆分：每刷新调用 prepareRenderer.renderAll()，仅对 shadowRenderer.renderShadows 分频。需要反射访问 IrisRenderingPipeline.shadowRenderer / prepareRenderer 私有字段（沿用现有 pipelineField 反射模式，IrisShaderCameras.kt:50-51）。实施顺序：先 V1，BSL/Complementary 回归不通过再 V2。

验收：

- cameraShadowUpdateRate=2/4 时 GPU 计时中 shadows 段平均耗时显著下降（以测量为准）。
- 静止场景截图：阴影与 rate=1 逐像素一致（太阳未跨阈值时）。
- 时间流逝场景：阴影每 N 次刷新跳变一次，画面无撕裂、无空阴影。
- CameraShaderGameTest、主视角恢复、资源重载全部通过。

### 6.2 阴影分辨率档位（路线图实验项）

事实：IrisRenderingPipeline.shadowMapResolution 是 private final int，无法事后改；ShadowRenderer 实例 resolution 从构造参数来。可行路径：构造摄像机自己的 IrisRenderingPipeline 前临时降 IrisVideoSettings 的分辨率/距离并恢复，或验证 ProgramSet/PackDirectives 是否允许低分辨率重建。风险高、收益不确定，列入路线图，不在首轮实施。

### 6.3 自动曝光与 TAA

自动曝光、TAA 与反射质量仍受摄像机更新频率影响（render-compatibility.md 已记录）。阴影分频不改变这一行为；若未来需要稳定曝光，属于"摄像机光影质量档位"的独立设计，不在本文档。

### 6.4 与调度/多机位的交互

分频基于**刷新序号**而非墙钟：三台摄像机各刷新各的序号，互不影响。统一截止时间调度（路线图）落地后，序号仍按每台摄像机实际执行刷新递增，避免"分频帧刚好全是同一台摄像机"的抖动。

## 7. 次要路线图（按触发条件收录，不在首轮实施）

| 项 | 触发条件 | 目标与风险 |
| --- | --- | --- |
| 辅助几何合批（WorldGeometry.cube 六面六 RenderPass） | 三屏+调试几何时外壳通道数达到 306 次/帧 | 用真实立方体网格批量绘制并做视锥剔除；保留颜色/深度/凹入量，外观不变 |
| 统一截止时间调度 | 需要多台摄像机真实高刷新率时 | 按预算分配每帧更新数，报告每台实际 FPS/画面年龄；不做则三台 60FPS 只是配置值 |
| 摄像机姿态插值 | 高 FPS 时镜头轨迹呈阶梯（20 TPS 采样） | tick 姿态样本 + 渲染前按 partial tick 插值，复用 Target/管线，瞬移断点策略 |
| 摄像机遮挡剔除 | 室内/地下摄像机仍全半径提交 | 先 region 粗剔除，再视实测决定独立遮挡遍历；历史结果需保守失效 |
| SSBO 光影包 | 部分光影包报"不支持 SSBO" | 需要光影后端重设计，不在当前双轨内 |
| vanilla 半透明索引缓冲复用 | 无 Sodium 时每刷新建/关 GPU 缓冲 | 按摄像机+区块版本缓存索引缓冲 |

## 8. 实施顺序与回滚

1. 测量：CameraGpuTimings + 固定场景基线（第 3 节）。产出每段中位数/P95/P99。
2. 无损 5.2（批次清理合并）：最小改动，先落地。
3. 无损 5.1（Visibility 缓存）：CPU 主要收益，失效规则是重点。
4. 无损 5.3（探针按需）：改动小，收益在低频/隐藏摄像机。
5. 高性能 6.1（阴影分频）：核心 GPU 收益；先 V1，回归不过转 V2。
6. 每步跑：./gradlew.bat build、./gradlew.bat test、-PcameraGameTest build runCameraTest（含 -PcameraShaderTest），外加固定场景 A/B 帧时间与截图对比。
7. 回滚：所有开关默认值即当前行为（无损轨全开=与现状像素一致，高性能轨 rate=1=关）；任何一步不达标只回退该开关，不影响其他。

## 9. 验收标准汇总

- 全局：build + 38 项单元测试 + CameraShaderGameTest / CameraTransparencyGameTest 全绿；主视角光影开启/关闭、资源重载、世界切换后画面与资源生命周期正确。
- 每项验收见 5.x / 6.1 末尾；必须同时满足"画面不通过漏画提速"（保留透明、水、阴影、远程实体、相邻边框、主视角恢复、连续移动的截图检查）。
- 性能结论必须以 3.2/3.3 的测量输出为依据，报告中位数/P95/P99 与各段 GPU 时间，不报未经测量的 FPS 承诺。

## 10. 证据索引

源码（file:line，撰写时工作树）：

| 文件 | 行 | 内容 |
| --- | --- | --- |
| client/DifferangleClient.kt | 21 | AFTER_SOLID_FEATURES 入口 |
| mixin/client/CameraGameRendererMixin.java | 17-19 / 21-30 | 主世界后触发 renderAfterLevel；mainRenderTarget/mainCamera 劫持 |
| client/CameraRuntime.kt | 163-168 / 177 | Iris 激活时延迟；renderNow |
| client/CameraRuntime.kt | 201 / 209 | beginFrame；system.renderFrame |
| client/CameraRuntime.kt | 336 / 341-344 | endFrame；prepared 清空；unlock；统计 |
| client/CameraRuntime.kt | 351-357 / 359-363 | prepare 帧内去重；drawTexture |
| camera/CameraSystem.kt | 74-79 / 82-85 / 95-100 | due 判定与 take(1)；目标复用；屏幕合成 |
| render/CameraWorldRenderer.kt | 50-60 / 62-81 | prepare；draw 与 shaderBackend 分支 |
| render/compat/IrisShaderCameras.kt | 53-57 | 光影 prepare 只产出原生列表 |
| render/compat/IrisShaderCameras.kt | 66-82 / 91-99 / 100-111 | 状态快照；Entry 复用；管线切换与 captured 写入 |
| render/compat/IrisShaderCameras.kt | 118-131 / 121 / 132 / 135 | beforeWorld；renderShadows；beginTranslucents；finalize |
| render/compat/IrisShaderCameras.kt | 172-207 | Visibility 全半径扫描 |
| render/compat/IrisShaderCameras.kt | 224-239 / 225 / 239 | CameraTerrain.draw；两次 clearAllCachedBatches |
| render/CameraNativeFeatures.kt | 88-95 / 97-111 / 112-131 / 132-138 | 实体/方块实体/粒子提取；prepareFrame 五阶段 |
| render/CameraEnvironmentSampler.kt | 41-64 / 50-53 | 每 tick 预热；entry 惰性补 tick 安全网 |
| render/CameraCompositor.kt | 58-71 / 73-83 | surface；solid 每面一次 RenderPass |
| world/WorldGeometry.kt | 50-54 | cube 六面循环 |

外部 API（Iris 1.11.2 jar = run/mods/iris-fabric-1.11.2+mc26.2.jar；Blaze3D = .gradle/vanilla-source）：

| API | 结论 |
| --- | --- |
| GpuQueryPool / createTimestampQueryPool / writeTimestamp / TimerQuery | 存在且公开，签名见 3.2 |
| IrisRenderingPipeline.renderShadows | = shadowRenderer.renderShadows + prepareRenderer.renderAll() |
| ShadowRenderer.renderShadows | 自包含保存/恢复全部全局状态；静态矩阵停在上一帧即复用前提 |
| ShadowRenderTargets.fullClearRequired | 首帧/分辨率变更后置 false；阴影纹理每帧不清空 |
| ShadowRenderTargets.getDepthTexture() | public，但实例字段 private → 反射/mixin 可得 |
| 阴影更新频率/分辨率设置接口 | 不存在，需自建 gate |

