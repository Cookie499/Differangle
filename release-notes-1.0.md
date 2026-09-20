# Differangle 1.0

在 Minecraft 里放多台摄像机、给它们挂上显示屏，让同一份世界同时从多个视角被看见。所有画面在客户端渲染，摄像机与显示屏由服务端世界持久化并同步到所有玩家。

- **Minecraft**：26.2
- **加载器**：Fabric Loader 0.19.5+
- **Java**：25
- **文件名**：`Differangle-1.0.jar`

---

## 这个模组能做什么

**显示屏基座（`differangle:screen_base`）**
放进世界、贴上墙面，右键打开设置面板，或直接用 `/differangle screen ...` 命令配置。宽度、高度、分辨率、帧率、深度、位置/旋转偏移都能调；配置存在方块实体里，存档、跨维度同步、`/clone` 携带 NBT 复制都会保留。复制出来的屏幕会得到新的 UUID，指向同一台摄像机——也就是说一个摄像机可以同时喂多块屏幕。

**摄像机（`differangle camera`）**
从玩家眼睛位置和朝向创建，默认隐形；把 `invisible` 设为 `false` 后会显示 UUID 名牌，并画出坐标轴与视锥，方便调试。用绑定工具（`differangle:binding_tool`）右键摄像机取 UUID，再右键基座推送绑定；也可以直接执行命令。

**两种渲染模式**

| 模式 | 说明 |
|---|---|
| `texture` | 每台摄像机渲染到独立的颜色/深度 Target，多块屏幕共享缓存。适合屏幕多、摄像机少的场景。 |
| `embedded` | 每块屏幕在自己的视口里直接重绘地形、实体、方块实体、粒子和雨雪云，保留原版材质，使用独立相机深度与主世界遮挡检测。屏幕里的画面不会因为开启了这些图层而退回 Texture。 |

用 `/differangle mode <texture|embedded>` 切换，选择会持久化到配置。

**镜面**
给显示屏开启镜面后，它反射主视点：以镜面四角建立非对称投影、水平反转采样并在镜面平面裁剪后方物体。不需要创建或绑定摄像机，关闭镜面会恢复原来的绑定数据。

**网络媒体屏幕**
支持 Bilibili 普通投稿视频 URL、HTTP/HTTPS 直链 MP4、PNG/JPEG 图片，带播放/循环/进度、声音开关、音量与距离衰减配置。视频解码由 [WaterMedia 3](https://modrinth.com/mod/watermedia) 提供。

**音响（`differangle:speaker`）与 OpenAL 空间音频**
摄像机/音响可以挂到屏幕上，每块屏幕最多两台音响（左右声道），只有一个时混合双声道。音频走 OpenAL 3D 输出：声源挂在显示屏本身的位置，会随玩家移动做距离衰减。绑定工具：右键音响依次记录声道，右键基座推送，Shift 右键清空。

---

## 安装

1. 安装 Fabric Loader 0.19.5+ 的 Minecraft 26.2。
2. 把 `Differangle-1.0.jar` 放进 `mods/`。
3. 同时需要以下依赖（全部放进 `mods/`）：
   - [Fabric API](https://modrinth.com/mod/fabric-api) `0.160.0+26.2`
   - [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) `1.14.1+kotlin.2.4.20`
   - [WaterMedia](https://modrinth.com/mod/watermedia) `3.0.0.23+`
   - [WaterMedia Binaries](https://modrinth.com/mod/watermedia-binaries) `3.0.0.6+`
4. 启动游戏。客户端与服务端都需要安装（多人游戏时服务端负责摄像机/屏幕数据的持久化与同步）。

## 快速上手

```mcfunction
# 创建一台摄像机（默认隐形；加 invisible 显式创建隐形摄像机）
/differangle camera create
/differangle camera list

# 让摄像机显形：会显示 UUID 名牌，并画出坐标轴与视锥
/differangle camera invisible <camera-uuid> false

# 给某块基座绑上摄像机（也可以右键基座在界面里填 UUID）
/differangle screen bind <x> <y> <z> <camera-uuid>

# 尺寸与分辨率：宽度 3、高度 1.6875、1024x576、30fps
/differangle screen configure <x> <y> <z> 3 1.6875 1024 576 30

# 偏移与旋转：dx dy dz yaw pitch roll
/differangle screen transform <x> <y> <z> 0 0 0 0 0 0

# 渲染模式（客户端）
/differangle mode texture
/differangle mode embedded
```

`/differangle screen help` 会列出全部子命令（bind / enabled / configure / transform / status / mirror / source / audio / playback / edit）。

---

## 1.0 修复与改进

- **修复：显示屏基座设置界面在正式客户端上打不开。** 原实现依赖 Fabric `UseBlockCallback` 的客户端回调，而在 Fabric API 0.160.0+26.2 中该事件的客户端接线指向了 26.2 已移除的 `MultiPlayerGameMode.interactBlock`，因此客户端侧永远不触发、只有服务端生效。现在改为挂钩客户端自身的 `Minecraft.startUseItem`，并把原版的右键冷却补回（单击只开一次）。
- **修复：满模组环境下启动即崩（`Missing uniform Globals`）。** 26.2 + Fabric 下初始资源重载可能早于第一帧上传全局 UBO；当其它模组在客户端启动阶段阻塞渲染线程较久时（例如 Xaero 世界地图），第一个 tick 就会在贴图图集动画上崩溃。现在在 UBO 尚未就绪时跳过图集动画上传（画面上什么都还没有，不损失任何可见动画）。
- **新增：OpenAL 空间音频。** 播放音量与声道不再走旧的混音路径，改为按显示屏位置输出的 3D 声源，随距离衰减。
- **新增：音响方块与绑定工具**，屏幕音频支持左右声道与单音响混合。
- **改进：暂停时显示最后一帧；视频播放进度同步、换源时重置进度**，并修复了解码阻塞导致的卡住。
- **改进：兼容性修复若干**——渲染玩家崩溃、靠近屏幕实体闪烁、Embedded 从侧面观察深度精度丢失、屏幕中的地形被错误剔除、Embedded 模式下屏幕内比例与最大屏幕绑定等。

## 已知限制

- **Iris 光影尚未完整支持**（Sodium / Iris 仅做基础兼容）。
- 屏幕内画面来自本模组的两种渲染模式；完整的轨迹编辑与 Replay 适配尚未实现。
- 手持绑定工具右键基座是"推送绑定"，**不会**打开设置界面——想编辑基座请先切到空手。
- 启动崩溃那一条是应对上游竞态的防护，等游戏或渲染模组修复后可以移除。
- 视频播放依赖 WaterMedia 的原生二进制，首次启动会解压到临时目录。

## 反馈

遇到问题时请附上 `logs/latest.log` 与 `crash-reports/` 里的对应文件，并说明是否安装了 Sodium、Iris 以及其它渲染类模组。
