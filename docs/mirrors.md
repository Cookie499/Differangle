# 镜子（仅 Texture）

放置显示屏基座，右键打开编辑器，打开“镜子”并应用；也可以执行 `/differangle screen mirror <x> <y> <z> true`。不需要创建或绑定摄像机。关闭镜子后恢复原来的绑定数据。

使用 `/differangle mode texture`。镜面反射主视点的位置，以镜面四角建立非对称投影，水平反转采样，并在镜面平面裁剪后方物体。镜子的世界尺寸决定反射区域，分辨率决定画面精细度；每块镜子使用独立分辨率和渲染目标。镜面旋转、偏移沿用屏幕编辑器。

每帧最多完整刷新两块镜子，按等待时间轮转，等待时间相同时优先刷新画面占比大的镜子。刷新使用当前视点，屏幕 FPS 设置只用于普通摄像机画面。未轮到的镜子继续显示上一张完整画面。客户端最多保留 8 个镜面 Target 和 8388608 个镜面像素，按缩放后的分辨率计入预算。不可见缓存 5 秒后释放。超过常驻预算的低画面占比镜子暂不显示反射。`/differangle status` 的“预算降级、镜面缓存、镜面像素”可用于确认限制是否生效。

开启 Iris 光影包和 `/differangle shaders true` 后，镜子按 `config/differangle.json` 中的 `mirrorShaderPacks` 匹配包名前缀。默认放行 BSL、Complementary 和两个偏轴投影修订版 `Differangle-Mirror-Photon-v1.3b.zip`、`Differangle-Mirror-Bliss-v2.1.2.zip`。在 Iris 中选择带标记的修订版即可使用对应的镜面光影。原版 Photon 和 Bliss 的镜面默认使用基础渲染。光影后端限制见 [渲染兼容](render-compatibility.md)。

镜子只绘制客户端已加载的场景，其他镜子和屏幕内部画面不参与反射。

背面与距镜面不足 0.01 格时不绘制反射。Embedded 为低优先级实验模式，在该模式镜子显示黑屏，保留镜子配置。镜子开关随屏幕 NBT 保存、同步和复制。

专项回归：`gradlew -PcameraGameTest -PcameraMirrorTest build runCameraTest`，使用独立的 `build/camera-gametest` 世界。覆盖未绑定摄像机、玩家位置变化、旋转镜面、模式切换及复制 NBT；数学测试检查偏轴投影四角和两种深度约定。
