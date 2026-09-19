# Embedded 原版实体与环境内容接入

当前优先级：低。保留实验功能与必要修复；Texture 为主要支持路径，镜子仅在 Texture 提供，见 [镜子](mirrors.md)。

状态：2026-09-14 已实现首版并通过独立客户端测试。地形、原版实体/方块实体、普通粒子、雨雪和云可直接 Embedded，不再按内容图层回退 Texture。下方保留接入设计与待完成项。

## 首版实现范围

- `EmbeddedNativePipelines` 在客户端初始化时登记原版管线变体，完整保留原 defines、资源布局、顶点格式、混合、深度状态和剔除设置。资源重载由原版 ShaderManager 重新编译这些已登记的管线。
- `shaders/core/native/` 保存 Minecraft 26.2 对应 shader 的明确适配版本，原版着色完成后由公共 include 处理屏幕映射与双深度。覆盖 entity、item、block、particle、clouds、text、text background、glint、shadow、leash、lightning、beacon、end portal、water mask、crumbling 家族。登记和编译成功不等同于每个特殊材质都已视觉验收。
- 三个局部 Mixin 分别接入 PreparedRenderType、QuadParticleFeatureRenderer 和天气/云的管线选择；仅在 NativeCameraScope 的 Embedded 作用域中替换。Texture 和主视图继续使用原管线。
- `BorrowedCameraTarget` 为原版绘制入口提供主颜色与独立相机深度的只借用附件，不分配相机颜色纹理，不释放借用资源。
- 主投影由作用域进入前保存；原版内容使用虚拟投影，夹在原版阶段之间的半透明地形显式取回主投影，避免倾斜屏幕二次映射错误。
- 尚未实现绘制前能力预检和自动整视图回退。遇到未知管线时使用现有错误隔离暂停副视图，并显示具体管线和 `/differangle mode texture` 提示；主视图状态通过 finally 恢复。资源包自定义 core shader、第三方管线、极端近平面穿越和复杂透明交叠仍需专项适配/验收。

## 结论与现有基础

可以支持真正的 Embedded 实体、方块实体、粒子、天气和云。此前限制来自原版绘制管线未适配 Embedded，不是这些内容必须先渲染为纹理。

继续复用 `CameraNativeFeatures` 的独立状态提取、模型提交、粒子快照、雨雪列与云网格，以及独立 FeatureRenderDispatcher / RenderBuffers。新增工作集中在 GPU 管线、着色器和附件绑定，避免重写实体模型或再次 tick 世界。

不能仅去掉此前的 `CameraLayers.needsTexture` 或替换投影矩阵。首版已将图层查询改为 `hasNativeContent`，并让原版阶段接受借用的 Embedded Target；同时改造着色器，表达 Embedded 所需的两套遮挡关系。

## 统一 Embedded 深度与投影协议

每个绘制阶段都必须共享同一屏幕的相机深度附件，并区分：

- 主世界深度：屏幕表面是否被墙、玩家等主视图内容挡住。
- 相机深度：屏幕内地形、实体、粒子等彼此之间的遮挡。

建议抽取现有地形的 Embedded GLSL 为公共协议。顶点阶段先完成原版模型、光照和虚拟视图计算，保存原始相机裁剪坐标 `cameraClip`，再将其 XY/W 映射到屏幕平面，通过主视图投影得到最终位置。片元阶段检查虚拟视锥范围、对比主世界深度，并把相机反向深度写入 `gl_FragDepth`。颜色直接写主颜色附件，相机深度只写独立附件。

原版 `Projection` 在原版模型计算中仍表示虚拟相机投影；为屏幕映射增加独立的主投影与屏幕变换 Uniform，不能让两个阶段误用同一个 `ProjMat`。雾、光照和粒子朝向继续基于虚拟相机。

保留每种材质的 Alpha discard、混合方式、深度比较及深度写入设置；半透明材质不能统一改成写深度。云等管线也应按本版本原版状态适配，不能仅凭透明外观推断是否写深度。

正式接入前需验证穿越虚拟相机近平面的大三角形。平面映射丢掉原始裁剪 Z，片元 discard 不能未经验证便视为完整替代硬件裁剪；如出现缺面或翻转，应增加原相机裁剪面的裁剪支持。旋转屏幕、正反面绕序和 `gl_FrontFacing` 也要一起验收。

## 本地 Minecraft 26.2 源码确认的接入点

| 内容 | 可复用部分 | 需适配部分 |
| --- | --- | --- |
| 普通实体与常见方块实体 | 原版 extract / submit、模型与纹理 | `PreparedRenderType.drawFromBuffer` 中选择 Embedded 管线并绑定额外 Uniform / SceneDepth；实体大量材质共用 `core/entity`，按原 defines 生成对应变体 |
| 物品、文字与特殊实体材质 | 原版几何与提交 | `core/item`、text、glint、shadow、leash、lightning、end portal 等家族逐个审计；不能把所有 RenderType 换成 entity shader |
| 普通 Quad 粒子 | 现有独立 QuadParticleRenderState，原版灯光与纹理 | `QuadParticleFeatureRenderer` 自己创建 RenderPass、逐 layer 设置 pipeline，不经过上述统一入口；适配 opaque / translucent particle |
| 天气 | 独立 WeatherEffectRenderer 提取的雨雪列 | 独立 RenderPass；本版本 WEATHER_SNIPPET 继承 PARTICLE_SNIPPET，共用 `core/particle`，可复用粒子着色器适配，保留两种深度写入状态 |
| 云 | 独立 CloudRenderer 的 CloudInfo / CloudFaces、云纹理与网格更新 | 独立 RenderPass；`core/rendertype_clouds` 用 gl_VertexID 和 CloudFaces 生成顶点，需专用变体；同时覆盖 fancy 与 flat 管线 |

物品拾取、远古守卫者等特殊粒子需按实际提交的 Feature 管线归类，普通 Quad 支持不代表所有粒子支持。第三方自定义直接绘制入口也不能由 PreparedRenderType 一处自动覆盖。

源码依据位于本地 `.gradle/vanilla-source/net/minecraft/client/renderer/` 的 `rendertype/PreparedRenderType.java`、`feature/QuadParticleFeatureRenderer.java`、`WeatherEffectRenderer.java`、`CloudRenderer.java` 和 `RenderPipelines.java`；着色器来自本地 26.2 client jar 的 `assets/minecraft/shaders/core/`。

## 管线管理与回退设计（自动回退待实现）

新增 Embedded 管线注册表，以原 pipeline 标识、着色器家族和 defines 对应明确支持的变体。复制必要的顶点布局、资源布局、混合与深度状态，追加 Embedded 绑定。资源重载时使派生缓存失效。首版采用明确维护的着色器变体，不做任意资源包 GLSL 的字符串替换。

仅在副视图作用域中替换管线与输出附件；主视图保持原有执行路径。继续沿用独立缓冲与 try/finally 状态恢复，不能复用主视图尚在执行的 PreparedFrame。

逐步把“开启某一类内容就回退”改为“当前绘制需要未支持的管线才回退”。常见 Feature 可在准备阶段收集所需 pipeline；直接绘制入口需提供自己的能力声明。不能保证预检的扩展渲染器暂按不支持处理。

回退必须在向主颜色附件写入之前决定，并让整张视图走 Texture，状态信息报告具体原因。不要已经画完一半 Embedded 再覆盖 Texture，也不要简单把不支持的透明实体单独渲染成纹理叠上去：后者缺少与地形共享的深度和透明顺序。

保持现有阶段顺序：天空 → 不透明地形 → 实体 Feature → 半透明地形 → after-terrain Feature / 粒子 → 云 → 天气。该方案不会自动获得 Fabulous 透明后处理或无序透明支持。

## 原建议实施顺序与剩余验收

1. 抽取 Embedded 公共投影、裁剪、双深度协议，先让现有地形使用它，验证倾斜屏幕、主世界遮挡和近平面。
2. 接入普通 Quad 粒子与雨雪：共用 particle shader，材质范围小，适合验证原版内容直接输出。
3. 接入 entity 家族，验证猪、玩家、箱子、透明与发光材质；再补 item、text、glint 与特殊管线。
4. 接入 fancy / flat 云，验证云上、云内、云下以及高空摄像机。
5. 完成能力预检、回退原因、资源重载和多屏测试，再按已覆盖能力取消现有图层级回退。

最终目标是让未覆盖内容在绘制前明确整视图回退 Texture；首版当前采用明确报错并提示手动切换，尚不具备自动回退能力。

## 性能与验收

Embedded 省去相机颜色纹理及最终采样，但仍需要独立深度附件。多个屏幕显示同一相机时仍需分别栅格化；Texture 则可以共享相机画面。片元深度写入、主深度采样和屏幕实际像素面积也会影响成本，因此不能预先承诺 Embedded 更快。

运行时每帧只让画面占比最大的两块 Camera 屏幕直接走 Embedded。其余可见屏幕自动进入普通 Texture 调度，共享 Camera 缓存且每帧最多更新一个 Camera；单块 Embedded 屏幕失败时进入 5 秒冷却并临时使用 Texture，而不是暂停整个副视图系统。这是负载保护，不改变用户保存的渲染模式或屏幕配置。

直接写主画面的内容每个主帧都需要重画，不能直接沿用 Texture 的低 FPS 跳帧缓存；可降低状态提取频率，但要保留并重绘有效快照。屏幕分辨率在直接模式下不是离屏纹理尺寸，而是这块屏幕自己的画面宽高比：每块屏幕按自己的 `resolution` 投影，屏幕的世界尺寸只决定屏幕平面与外框形状，因此同一 Camera 的多块屏幕可以各自设置内部拉伸。Cam / Screen 关闭后的黑屏与停止提交规则沿用持久化设计。

验收使用同一相机姿态的 Texture 作为对照：旋转/平移/缩放屏幕、主世界遮挡、玻璃后实体与烟雾、近面穿越、粒子 billboard 与相机 roll、雨雪、云上下内、多个重叠屏幕、资源重载、维度切换、主视图不重影及状态恢复。记录每阶段 CPU / GPU 成本、相机数和屏幕像素覆盖率。

2026-09-14 已执行独立客户端/GPU 测试，查看直接 Embedded 猪、箱子、烟雾、雨、透明地形和倾斜屏幕截图。测试输出位于 `build/camera-gametest/screenshots`。其他尚未验证的边界不视为已支持保证。
