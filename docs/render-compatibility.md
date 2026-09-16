# Sodium / Iris 渲染兼容

开发优先级：Texture 为主要支持路径；Embedded 为低优先级实验模式，保留现有功能及必要修复，新功能优先在 Texture 实现。镜子仅支持 Texture。

适配 Minecraft 26.2，验证版本为 Sodium 0.9.1、Iris 1.11.2。本模组不强制安装它们，生产 jar 不包含它们；Iris 自身依赖 Sodium。验证分为原版、仅 Sodium、Sodium + Iris 三组，最后一组覆盖光影开关。未安装时继续使用原版区块网格。

## 渲染路径

- Sodium：读取当前已上传的 region 顶点、索引缓冲区，按摄像机自己的视锥选择区块，不替换主视角的可见区块列表。支持 Texture、Embedded，以及实体、方块实体、粒子、天气和云。
- 实体：摄像机绘制期间绕过 Sodium 的主视角实体遮挡剔除，仍执行摄像机自身的原版距离、视锥判断。主视角靠近或转向屏幕不应改变远程实体的可见性。
- Embedded：先用真实屏幕四边形与主场景深度生成 R8 可见区域，再让地形和实体采样该区域，深度测试只使用独立的摄像机深度。不再以摄像机三角形插值得到的屏幕深度反复比较同一屏幕平面，避免近距离、侧视时的自遮挡精度冲突。地形背面剔除与 Texture 一致。可见区域与摄像机深度按窗口尺寸重建、逐屏复用；不缓存屏幕颜色。
- Sodium 紧凑顶点格式：解码位置、纹理坐标、顶点颜色、光照和材质透明阈值。Iris 开启光影时读取实际扩展顶点格式，并处理其不同的材质/AO 字节含义。
- Iris：在主世界的光影合成结束、手部渲染清除世界深度之前绘制显示屏。Texture 的实验性光影后端为每台摄像机建立独立 Iris 管线，执行阴影、天空、Sodium 地形、实体与半透明内容、deferred/composite/final；渲染目标和历史帧独立。Embedded 仍使用基础渲染。
- Iris 1.11.2 开启光影后会将原版反向 Z 的深度比较、清屏值转换为正向 Z。因此摄像机投影、天空远平面和 Embedded 主场景遮挡判断同步采用正向深度。不能只关闭 Iris 的程序替换，否则玻璃、水及实体仍会被错误的深度测试挡住。
- 基础后端暂时关闭 Iris 的即时顶点格式扩展；光影后端在自己的管线内启用，完成后恢复主视角管线、矩阵、摄像机、阴影状态和扩展标志。资源重载和世界切换释放本模组资源；借用的 Sodium 缓冲区不由本模组销毁。

## Texture 光影试验版

启用 Iris 光影包后，使用 `/differangle mode texture` 和 `/differangle shaders true`。`cameraShaders` 默认 `true`，开关立即保存到 `config/differangle.json`。`/differangle shaders false` 可退回基础画面，保留主视角光影。

每台摄像机额外承担一套光影管线、阴影贴图与中间缓冲的成本；同一摄像机绑定的屏幕继续共享结果，按 Camera FPS 更新。建议试测从一至两台、较低分辨率与 FPS 开始。此版不支持使用 SSBO 的光影包，会报告明确错误；未验证 Distant Horizons、光线追踪光影包、所有自定义材质扩展和跨维度摄像机。自动曝光、TAA 和反射质量仍受摄像机更新频率影响。

光影地形使用摄像机自己的 `ChunkRenderList`，经 Sodium 的原生 `ChunkRenderer` 交给 Iris。绘制前后清除借用 region 的批次缓存，避免主视角复用摄像机批次。除基础后端的两处 Sodium 反射外，试验后端针对 Iris 1.11 访问 `PipelineManager.pipeline` 和 `IrisRenderingPipeline.initializedBlockIds`，用于临时切换管线并复用主管线已建立的相同材质映射；升级 Iris 时需要检查。

## 范围与限制

只读取客户端已经加载并完成网格编译的区块，不为远程摄像机请求额外区块。半透明区块间按摄像机距离排序；区块内部沿用 Sodium 上传的索引顺序，不改写其主视角排序缓冲区。复杂交叠透明面可能仍存在排序差异。

`SodiumTerrain` 的候选集是摄像机自己的区块半径，再用摄像机自己的 Frustum 剔除；不能改用 Sodium 每帧的 render list，那批列表是主视角做完遮挡剔除后的结果，主视角看不到的地形（背后、地下、被挡住）会从显示屏上整片消失。顶点格式取 `ChunkMeshFormats.getCurrent()`（Iris 开启光影时由 Iris 覆写），而不是渲染器自身的字段；精灵动画通过 `api.texture.SpriteUtil.INSTANCE.markSpriteActive` 上报，内部实现类已标记 `forRemoval`。

基础后端的非公开读取为两处：`SodiumWorldRenderer.renderSectionManager` 与其 `renderSections`，Sodium 0.9.1 都没有提供 getter（`run/mods` 中的构建连 `SodiumWorldRenderer.getRenderLists()` 也没有，上游 tag 才有）。二者集中在 `SodiumTerrain.sectionManager` / `sectionStorage`，缺失时记录一次警告并退化为不绘制 Sodium 地形，不影响主视角。运行时限制为 Sodium 0.9.x / Iris 1.11.x，其他系列需要重新检查格式及调用时序。

## 构建与回归

客户端渲染模式保存在 `config/differangle.json` 的 `renderMode` 字段（`texture` / `embedded`）。`/differangle mode` 切换时立即写入，重启后恢复；配置缺失或模式无效时使用 `texture`。该选项跨世界生效，不写入屏幕 NBT。

编译优先使用 `run/mods` 下上述精确版本的 jar。缺少本地 jar 时，从 Modrinth Maven 获取固定版本；依赖仅加入 `clientCompileOnly`。版本来源：[Sodium 0.9.1](https://modrinth.com/mod/sodium/version/mc26.2-0.9.1-fabric)、[Iris 1.11.2](https://modrinth.com/mod/iris/version/1.11.2%2B26.2-fabric)。

`gradlew -PcameraGameTest build runCameraTest` 在 `build/camera-gametest` 内创建独立世界。测试所需模组放在该目录的 `mods` 中，光影包及配置也放在该目录；不使用 `run/saves`。

`gradlew -PcameraGameTest -PcameraShaderTest build runCameraTest` 选择光影专项测试：双摄像机、光影与基础画面的像素对比、修改分辨率、重建摄像机资源、Iris 开关及清理后主画面恢复。测试目录需同时安装 Sodium + Iris，并启用光影包。

试验版已用 BSL 10.1.5 和 Complementary Reimagined 5.9 验证上述光影流程，并人工检查截图。GUI 编辑参数名修复后，已补测 GUI 写回、`move`/`look` 保留另一部分姿态，以及同一摄像机的 Embedded 屏幕独立宽高比。

`CameraTransparencyGameTest` 对玻璃和水分别测试 Texture / Embedded：切换半透明层，比较显示屏内部的截图像素，避免“提交了 draw call 但像素被深度测试丢弃”的假通过。安装 Iris 时还会关闭、重新开启光影，检查渲染恢复。其他客户端测试覆盖全部内容层、倾斜屏幕、主视角遮挡、玩家渲染、模式切换及世界资源/NBT。

该测试也覆盖默认尺寸屏幕的近距离、75° 和 85° 侧视：连续检查更新帧中的实体可见性，并对比高分辨率 Texture / Embedded 在屏幕多边形内部的像素差异。
