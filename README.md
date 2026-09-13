# Differangle

Fabric / Minecraft 26.2 纯客户端多视角摄像机项目，使用 Kotlin 和 Java 25。

当前实现 **Texture-first 核心层**：Camera 与 Screen 数据、独立视图/投影矩阵、刷新调度、帧缓存和 Blaze3D RenderTarget 资源管理。

当前版本已接入基础地形画面和客户端指令；实体、方块实体、粒子、半透明地形等内容仍在后续阶段。

## 方案选择

按[开发设计文档](docs/Multi-Viewport%20Camera%20——%20开发设计文档.md)的阶段顺序采用 Texture Camera：每个 Camera 对应一份独立的颜色/深度 Target，多个 Screen 共享该 Camera 的缓存结果。Embedded 需要额外处理裁剪、模板缓冲和深度空间，放到后续实验；当前不加入自动 Hybrid 选择、Iris 或 Sodium 兼容声明。

实现与约束见[核心层实现说明](docs/core-implementation.md)。

实体摄像机、显示屏基座/支架、UUID 持久化、轨迹插值、Replay 适配和镜子预案见[方案讨论文档](docs/camera-entity-screen-blocks.md)。本次只记录设计，不包含这些资源的实现。

## 构建与测试

设置 `JAVA_HOME` 指向 JDK 25，然后执行：

```powershell
.\gradlew.bat build
.\gradlew.bat test
```

首次构建需要联网下载依赖。产物在 `build/libs/`，测试报告在 `build/reports/tests/test/index.html`。Wrapper 固定使用 Gradle 9.6.1。
