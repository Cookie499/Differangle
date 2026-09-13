# Differangle

Fabric / Minecraft 26.2 多视角摄像机项目，使用 Kotlin 和 Java 25。当前画面渲染在客户端完成；计划中的 CameraEntity 与 ScreenBlockEntity 由世界持久化并同步。

当前实现 **Texture-first 核心层**：Camera 与 Screen 数据、独立视图/投影矩阵、刷新调度、帧缓存和 Blaze3D RenderTarget 资源管理。

当前已接入不透明/镂空/半透明地形、普通实体、方块实体、粒子、降雨/降雪和云，以及基础天空颜色与雾。新增内容的使用方式、限制和游戏内测试见[内容渲染说明](docs/render-content.md)。

## 方案选择

按[开发设计文档](docs/Multi-Viewport%20Camera%20——%20开发设计文档.md)提供 Texture Camera 与 Embedded：Texture 为每个 Camera 保存颜色/深度 Target，多个 Screen 共享缓存；Embedded 每帧按 Screen 直接绘制地形、原版实体/方块实体、粒子、雨雪和云，不再因开启这些图层而回退 Texture。Embedded 保留原版材质，并使用独立相机深度和主世界遮挡检测，详见 [接入说明](docs/embedded-native-content.md)。尚不兼容 Iris / Sodium。

实现与约束见[核心层实现说明](docs/core-implementation.md)。

实体摄像机、显示屏基座/支架、UUID 持久化、开关黑屏、单面显示、轨迹插值、Replay 适配和镜子预案见[方案讨论文档](docs/camera-entity-screen-blocks.md)。这些资源尚未实现。

## 构建与测试

设置 `JAVA_HOME` 指向 JDK 25，然后执行：

```powershell
.\gradlew.bat build
.\gradlew.bat test
# 可选：独立世界中的真实客户端/GPU 回归测试，不使用 run/saves
.\gradlew.bat -PcameraGameTest runCameraTest
```

首次构建需要联网下载依赖。产物在 `build/libs/`，测试报告在 `build/reports/tests/test/index.html`。Wrapper 固定使用 Gradle 9.6.1。
