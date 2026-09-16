# Differangle

Fabric / Minecraft 26.2 多视角摄像机项目，使用 Kotlin 和 Java 25。画面渲染在客户端完成；CameraEntity 与 ScreenBlockEntity 由服务端世界持久化并同步。

当前实现 **Texture-first 核心层**：Camera 与 Screen 数据、独立视图/投影矩阵、刷新调度、帧缓存和 Blaze3D RenderTarget 资源管理。

当前已接入不透明/镂空/半透明地形、普通实体、方块实体、粒子、降雨/降雪和云，以及基础天空颜色与雾。新增内容的使用方式、限制和游戏内测试见[内容渲染说明](docs/render-content.md)。

## 方案选择

按[开发设计文档](docs/Multi-Viewport%20Camera%20——%20开发设计文档.md)提供 Texture Camera 与 Embedded：Texture 为每个 Camera 保存颜色/深度 Target，多个 Screen 共享缓存；Embedded 每帧按 Screen 直接绘制地形、原版实体/方块实体、粒子、雨雪和云，不再因开启这些图层而回退 Texture。Embedded 保留原版材质，并使用独立相机深度和主世界遮挡检测，详见 [接入说明](docs/embedded-native-content.md)。尚不兼容 Iris / Sodium。

实现与约束见[核心层实现说明](docs/core-implementation.md)。

实体摄像机与显示屏已接入持久化、UUID 绑定、右键设置、支架几何、黑屏、单面显示与两点插值，见[使用说明](docs/world-resources.md)。完整轨迹、Replay 适配和镜子仍属后续阶段，设计见[方案文档](docs/camera-entity-screen-blocks.md)。带 NBT 的屏幕复制保留摄像机绑定，仅生成新的屏幕 UUID。

网络媒体屏幕将优先支持 Bilibili 视频 URL、MP4 URL 和图片 URL，并提供声音开关、音量与距离衰减配置。范围、数据模型、解码管线和实施顺序见[网络媒体屏幕设计](docs/media-screens.md)。

## 构建与测试

设置 `JAVA_HOME` 指向 JDK 25，然后执行：

```powershell
.\gradlew.bat build
.\gradlew.bat test
# 可选：独立世界中的真实客户端/GPU 回归测试，不使用 run/saves
.\gradlew.bat -PcameraGameTest runCameraTest
```

首次构建需要联网下载依赖。产物在 `build/libs/`，测试报告在 `build/reports/tests/test/index.html`。Wrapper 固定使用 Gradle 9.6.1。
