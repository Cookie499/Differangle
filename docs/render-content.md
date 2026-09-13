# 摄像头内容渲染：半透明、实体与环境效果

## 当前实现

| 内容 | 实现 | 边界 |
| --- | --- | --- |
| 半透明地形 | 复用原版已编译的 TRANSLUCENT 顶点，按虚拟摄像头位置生成独立索引；区块节和节内 Quad 从远到近绘制；Alpha 混合，不写深度 | 不改写主视角排序；相交透明面仍有传统排序误差，非 OIT |
| 普通实体 | 从客户端已知实体独立做 Frustum 筛选、状态提取和原版 Feature 提交 | 包括主玩家模型；摄像头位于玩家头内时可能看到模型内部，移开玩家即可；不加载远端实体 |
| 方块实体 | 从摄像头可见的编译区块节收集，并补充全局渲染的方块实体；使用原版渲染器 | 不创建新屏幕基座；仍受区块加载、编译状态和原版方块实体视距限制 |
| 粒子 | 独立提取普通 Quad 粒子、物品拾取和远古守卫者效果 | 不重复 tick；不清空或追加到主视图的 Quad 快照；只显示本客户端已有粒子 |
| 天气 | 以摄像头位置提取原版雨雪列，使用独立 WeatherEffectRenderer | 只渲染，不另行生成声音或模拟天气粒子；雷电实体由实体层处理 |
| 云 | 独立 CloudRenderer，复用资源包云纹理，以摄像头位置及环境属性绘制 | 遵守游戏云开关、质量和范围；云雾距独立于已加载地形距离 |

基础天空颜色与雾已接入；太阳、月亮、星星及完整末地天空仍是后续内容。

## 输出路径与顺序

Texture：天空背景 → 不透明/镂空地形 → 实体/方块实体的实心及半透明 Feature → 半透明地形 → 原版 after-terrain Feature（含粒子）→ 云 → 雨雪 → Screen 合成。

Camera Target 统一使用原版反向深度（near=1、far=0，清除为 0），使原版实体深度与地形深度兼容。屏幕最终合成仍使用主视图中 Screen 表面的深度。

Embedded：地形、原版实体/方块实体、粒子、雨雪和云直接绘制到主颜色附件，使用同一屏幕的独立相机深度附件；片元先检查屏幕表面与主世界的遮挡，再写相机反向深度。各阶段保持上述顺序，不再因启用原版内容回退 Texture。每个主帧按 Screen 重画，不使用 Camera FPS 或低分辨率颜色缓存。

## 状态隔离

实现范围与后续工作见 [Embedded 实体与环境接入说明](embedded-native-content.md)。首版按已登记的原版 shader 家族适配；遇到未支持的管线会明确暂停副视图并提示切换 Texture，不静默漏画。自动整视图回退仍是后续工作。

- 主视图的 FeatureRenderDispatcher/PreparedFrame 在回调中尚未结束；副摄像头使用独立 FeatureRenderDispatcher、RenderBuffers 和 SubmitNodeStorage。
- 短作用域重定向原版输出 Target 和主 Camera getter，并保存/恢复投影、ModelView、Fog、Lighting、GlobalSettings、Scissor、输出附件、实体分发器与方块实体分发器的观察位置。
- 每个 Camera 的原版渲染结束后释放临时快照并轮换副视图缓冲；离开世界、模式切换、图层变更和资源重载时关闭所属 GPU 资源。
- 不调用 `LevelRenderer.render()`，不触发第二次世界模拟。递归原版摄像头作用域会直接被拒绝；未来基座/镜子的递归策略仍按方案文档单独接入。
- 复用原版普通混合次序，不执行 Fabulous 多 Target 的透明后处理链；复杂透明交叠和第三方自定义渲染器需另行适配。

## 指令

所有新增图层默认开启，游戏自身的云等选项仍然生效。

```text
/differangle demo
/differangle status
/differangle layer translucent true
/differangle layer entities true
/differangle layer block_entities true
/differangle layer particles true
/differangle layer weather true
/differangle layer clouds true
```

将 `true` 改为 `false` 可逐层排查。`status` 显示本主帧实际更新的实体、方块实体、粒子、降水列和云视图数量；缓存复用帧计数可为 0，并不表示画面缺失。定义和图层配置目前都是客户端会话数据。

## 验证

`./gradlew.bat build` 执行调度、坐标数学、反向深度及原版内容阶段开关测试。

`./gradlew.bat -PcameraGameTest runCameraTest` 启动独立测试模组，在 `build/camera-gametest` 创建专用世界并自动退出，不操作开发世界。场景包含玻璃、水、猪、箱子、营火、显式烟雾粒子和雨。测试检查 Feature 计数、Texture 合成、全图层直接 Embedded 且无颜色缓存、图层开关、资源重建、高空摄像机的 fancy / flat 云、倾斜屏幕和主世界局部遮挡，并保存截图到该目录的 `screenshots`。

2026-09-13 验证结果：18 项单元测试通过，客户端/GPU 测试通过；已查看 Texture 全图层、直接 Embedded 地形和高空云截图。测试使用 OpenGL / NVIDIA RTX 4060 Laptop；降雪及下列复杂场景尚未逐项视觉验收。

2026-09-14 验证结果：更新后的 18 项单元测试与独立客户端/GPU 测试通过；已查看直接 Embedded 的猪、箱子、烟雾、雨、玻璃/水、fancy / flat 云、倾斜屏幕和前景栅栏遮挡截图。全图层直接路径的测试同时要求原版内容计数为正且颜色缓存计数为零。降雪、特殊实体材质、极端近平面和多屏交叠仍需补充视觉验收。

手工补充检查建议：雨雪生物群系切换、玻璃后多实体重叠、箱盖动画、告示牌文字、信标、多个 Camera 不同方向、云上/云内/云下、F3+T、换维度，以及主画面粒子不重影。
