# 网络媒体屏幕设计与实施计划

## 范围

首期只支持三类网络内容：

1. Bilibili 普通投稿视频 URL，包括 `bilibili.com/video/BV...` 和 `b23.tv` 短链接。
2. 可由 FFmpeg 读取的 HTTP/HTTPS MP4 URL。
3. HTTP/HTTPS PNG、JPEG 图片 URL。WebP 将在 FFmpeg 图片解码接入后补充。

不嵌入浏览器，不执行网页 JavaScript。首期不保证直播、互动视频、DRM、地区限制内容和会员番剧可用。需要登录的 Bilibili 内容后续通过客户端本地 Cookie 支持；Cookie 不进入世界存档、方块 NBT 或网络同步。

## 总体结构

```text
ScreenBlockEntity / ScreenConfig（服务端持久化并同步）
                      │
                      ▼
              MediaRuntime（客户端）
                      │
        ┌─────────────┼──────────────┐
        │             │              │
   Bilibili URL     MP4 URL        图片 URL
        │             │              │
        └──────┬──────┘          HTTP 下载与检查
               ▼                     │
        WaterMedia MRL                │
       平台解析 / FFmpeg               │
          ┌────┴────┐                │
          │         │                │
       AWT 视频帧  OpenAL 声源          │
          │         │                │
          ▼         ▼                ▼
       DynamicTexture            静态纹理
               │                     │
               └──────────┬──────────┘
                          ▼
               CameraCompositor.surface
```

世界摄像机和媒体使用相同的最终屏幕合成路径。媒体不进入地形、实体或天气渲染流程，也不受 Texture/Embedded 摄像机模式影响。

## 持久化模型

`ScreenConfig.media` 保存公开、可同步的播放设置：

| 字段 | 默认值 | 说明 |
|---|---:|---|
| `sourceType` | `camera` | `camera`、`bilibili`、`video`、`image` |
| `sourceUrl` | 空 | 原始用户 URL；最长 2048 字符，只允许 HTTP/HTTPS |
| `playing` | `true` | 是否播放 |
| `loop` | `false` | 播放结束后是否循环 |
| `positionSeconds` | `0` | 同步用的播放基准位置 |
| `audioEnabled` | `true` | 是否产生声音 |
| `volume` | `1` | `0..1` |
| `attenuation` | `linear` | `none` 或 `linear` |
| `audibleDistance` | `32` | 线性衰减的最大方块距离 |
| `maxVideoHeight` | `720` | Bilibili 格式选择上限 |

旧存档没有这些字段时按 `camera` 加载。媒体解析得到的临时地址、请求头、Cookie 和缓存路径只属于客户端内存或客户端私有配置。

当前提供以下管理命令：

```text
/differangle screen source <x> <y> <z> camera
/differangle screen source <x> <y> <z> bilibili <url>
/differangle screen source <x> <y> <z> video <url>
/differangle screen source <x> <y> <z> image <url>
/differangle screen audio <x> <y> <z> <enabled> <volume> <none|linear> <distance>
/differangle screen playback <x> <y> <z> <playing> <loop> <positionSeconds>
```

屏幕 GUI 现有三页。第三页可以选择来源类型、填写 URL，并编辑播放、循环、音量、衰减、可听距离、播放位置和 Bilibili 最大画质。前两页继续编辑摄像机与几何参数。

## WaterMedia 依赖与 Bilibili 解析

视频由外部模组 `WaterMedia 3.0.0.23` 和 `WaterMedia Binaries 3.0.0.6` 提供。Differangle 不把它们合并进自己的 JAR；客户端需要同时安装两个依赖。Binaries 包含并按当前系统提取、校验 FFmpeg 原生库，当前支持：

- Windows x86-64；
- Linux x86-64、ARM64；
- macOS x86-64、ARM64。

WaterMedia 的 `BiliBiliPlatform` 直接调用 Bilibili API，当前能解析普通投稿、分P、番剧、直播和 `b23.tv` 短链接，并生成带请求头的 DASH 视频/音频来源。Differangle 首期界面仍只承诺普通投稿 URL。WaterMedia 解析器取得：

- 标题、时长和直播标记；
- 不超过 `maxVideoHeight` 的最佳视频格式；
- 最佳兼容音频格式；
- 两个临时 URL 各自的 HTTP 请求头；
- 可用于恢复播放的时间信息。

解析或解码失败会记录可诊断状态并停止当前会话。MRL 自身带有效期和重新加载能力；正在播放的临时地址失效后能否无缝恢复仍需长时间回归。

公开内容不要求 Cookie。WaterMedia 支持在其客户端配置中设置 Bilibili Cookie；Cookie 不进入 Differangle 世界存档、方块 NBT 或网络同步。

## 解码与同步

当前使用 WaterMedia 的 JavaCPP/FFmpeg 播放器：

- 直连 MP4 和 Bilibili DASH 由同一套 WaterMedia 播放器管理；
- Bilibili DASH 使用独立视频和音频输入，共用一个播放时钟；
- WaterMedia 负责解码、音频时钟、丢帧、暂停、跳转与循环；
- Differangle 使用 WaterMedia 的 `AWTEngine` 取得软件帧，自己的上传队列最多保留两帧；
- Bilibili 软件帧在上传时自动做上下翻转，等价于 Z 轴旋转 180° 后再左右翻转；该修正不作用于直连 MP4、图片或 Camera；
- 画面落后时丢帧，画面领先时等待；
- 暂停、跳转、换源和循环都会清空帧队列及音频队列。

解码线程不能调用 Blaze3D。它只产出带时间戳的 CPU 像素帧；渲染线程负责创建、更新和销毁 GPU 纹理。分辨率变化时在渲染线程重建纹理。

## 图片加载

图片先下载到有大小上限的内存或客户端缓存，再解码一次并上传纹理。首期限制：

- 响应体最大 20 MiB；
- 解码尺寸最大 8192×8192；
- 最多跟随 5 次重定向；
- 连接和读取均有超时；
- 校验实际解码结果，不只相信扩展名或 `Content-Type`。

动图首期只显示第一帧。

## 声音

WaterMedia 的 `ALEngine` 在 Minecraft 已创建的 OpenAL 上下文中输出音频。Differangle 配置该播放器公开的声源句柄，使每块有声屏幕拥有一个空间声源：

- `attenuation=none`：监听者位置变化不改变音量；
- `attenuation=linear`：以屏幕中心为声源，到 `audibleDistance` 线性衰减至静音；
- 最终增益为媒体 `volume` 乘以游戏对应音量分类的增益；
- 屏幕禁用、换维度、区块卸载或会话关闭时立即停止并释放缓冲。

每块屏幕当前拥有独立的解码会话、画面纹理和空间声源。跨屏幕共享解码与纹理属于后续性能优化。

## 多人和安全边界

服务器同步原始 URL 和播放状态，客户端各自解析、下载和解码。服务器不代理媒体字节。后续播放同步使用服务端游戏时间加基准位置；目标是观看一致，不追求逐采样同步。

由于服务器可以向客户端同步 URL，网络加载前必须实施客户端信任策略。当前 URL 校验会拒绝凭据、回环、链路本地和私有入口地址；图片加载还会重新校验每一次重定向。FFmpeg 内部的视频重定向无法由现有校验层逐次检查，因此在服务器信任确认完成前，只应在可信服务器使用媒体 URL。

## 当前实现状态

- **已完成**：媒体配置、NBT 兼容、命令、三页编辑界面和客户端会话生命周期。
- **已完成**：PNG/JPEG 下载、限制检查、解码、GPU 上传和资源释放。
- **已完成**：WaterMedia MP4/Bilibili 播放适配、暂停、跳转基准、循环、画质选择和最多两帧的上传队列。
- **已完成**：WaterMedia OpenAL 声源、Minecraft 音量分类、不衰减和线性距离衰减。
- **已完成**：以外部依赖方式接入 WaterMedia 的全平台 FFmpeg 原生包和 Bilibili 平台解析。
- **已验证**：27 项测试、正式构建、依赖未混入 Differangle JAR，以及真实客户端中的 Binaries 提取、Bilibili 平台注册、FFmpeg 初始化和 Minecraft OpenAL 启动。
- **待回归**：在游戏世界中分别播放真实图片、MP4 和 Bilibili 地址，并验证多屏幕卸载、换维度和长时间播放。
- **待实现**：WebP、临时 URL 无缝恢复、服务端时间同步、客户端服务器信任确认、跨屏幕资源共享和原生 GPU 纹理零拷贝。

WaterMedia 与 WaterMedia Binaries 使用 PolyForm Strict 1.0.0。Differangle 只通过其公开 API 建立外部依赖，不复制源码、不修改依赖，也不把依赖重新打包进自己的产物。
