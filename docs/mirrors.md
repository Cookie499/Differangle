# 镜子（仅 Texture）

放置显示屏基座，右键打开编辑器，打开“镜子”并应用；也可以执行 `/differangle screen mirror <x> <y> <z> true`。不需要创建或绑定摄像机。关闭镜子后恢复原来的绑定数据。

使用 `/differangle mode texture`。镜面反射主视点的位置，以镜面四角建立非对称投影，水平反转采样，并在镜面平面裁剪后方物体。镜子的世界尺寸决定反射区域，分辨率决定画面精细度；每块镜子独立使用屏幕 FPS、分辨率和渲染目标。镜面旋转、偏移沿用屏幕编辑器。

开启 Iris 光影包且 `/differangle shaders true` 时镜子使用 Texture 的实验性光影后端；支持范围及限制见 [渲染兼容](render-compatibility.md)。仅绘制客户端已加载内容，不加载额外区块，不递归绘制其他镜子或屏幕内部画面。每台镜子的反射与光影会增加渲染和显存成本。

背面与距镜面不足 0.01 格时不绘制反射。Embedded 为低优先级实验模式，在该模式镜子显示黑屏，保留镜子配置。镜子开关随屏幕 NBT 保存、同步和复制。

专项回归：`gradlew -PcameraGameTest -PcameraMirrorTest build runCameraTest`，使用独立的 `build/camera-gametest` 世界。覆盖未绑定摄像机、玩家位置变化、旋转镜面、模式切换及复制 NBT；数学测试检查偏轴投影四角和两种深度约定。
