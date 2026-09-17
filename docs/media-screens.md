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
     yt-dlp       FFmpeg probe     HTTP 下载
        │             │              │
   临时视频/音频 URL  │          图片解码与尺寸检查
        └──────┬──────┘              │
               ▼                     │
          FFmpeg 解码                 │
          ┌────┴────┐                │
          │         │                │
       视频帧     PCM 音频            │
          │         │                │
          └────┬────┘                │
               ▼                     ▼
          GpuTextureView          静态纹理
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

当前搭建阶段提供以下管理命令：

```text
/differangle screen source <x> <y> <z> camera
/differangle screen source <x> <y> <z> bilibili <url>
/differangle screen source <x> <y> <z> video <url>
/differangle screen source <x> <y> <z> image <url>
/differangle screen audio <x> <y> <z> <enabled> <volume> <none|linear> <distance>
/differangle screen playback <x> <y> <z> <playing> <loop> <positionSeconds>
```

屏幕 GUI 在媒体页完成前仍编辑摄像机和几何参数；提交旧页面时必须保留现有媒体配置。

## Bilibili 解析

客户端以子进程调用固定兼容版本的 `yt-dlp`。使用结构化 JSON 输出，不解析普通日志。解析器需要取得：

- 标题、时长和直播标记；
- 不超过 `maxVideoHeight` 的最佳视频格式；
- 最佳兼容音频格式；
- 两个临时 URL 各自的 HTTP 请求头；
- 可用于恢复播放的时间信息。

解析失败必须显示可诊断状态。临时流返回 401/403 或在播放中断开时，只自动重新解析一次，并从当前媒体时钟恢复；连续失败进入错误状态，避免请求循环。

短链接应先由 yt-dlp 解析，不在本地手写重定向规则。公开内容不要求 Cookie。登录内容使用用户主动配置的客户端 Cookie 文件，绝不从服务器接收 Cookie 路径或内容。

## 解码与同步

计划使用 JavaCV 的 FFmpeg 绑定：

- 直连 MP4 使用一个 demux/decode 会话读取视频和音频；
- Bilibili DASH 使用独立视频和音频输入，共用一个播放时钟；
- 视频队列最多保留 2～3 帧，满时丢弃最旧帧；
- 音频开启时以实际音频播放位置为主时钟，静音时使用单调时钟；
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

音频输出接入 Minecraft 使用的 OpenAL 上下文和声音执行线程。每块有声屏幕拥有一个空间声源：

- `attenuation=none`：监听者位置变化不改变音量；
- `attenuation=linear`：以屏幕中心为声源，到 `audibleDistance` 线性衰减至静音；
- 最终增益为媒体 `volume` 乘以游戏对应音量分类的增益；
- 屏幕禁用、换维度、区块卸载或会话关闭时立即停止并释放缓冲。

多个屏幕可以共享同一个解码会话和画面纹理，但空间声源按屏幕保留。是否共享以规范化 URL、画质、播放基准和循环设置共同决定。

## 多人和安全边界

服务器同步原始 URL 和播放状态，客户端各自解析、下载和解码。服务器不代理媒体字节。后续播放同步使用服务端游戏时间加基准位置；目标是观看一致，不追求逐采样同步。

由于服务器可以向客户端同步 URL，网络加载前必须实施客户端信任策略：默认只加载玩家自己确认过的服务器媒体，拒绝包含凭据的 URL，并阻止回环、链路本地和私有地址，防止服务器利用客户端访问本地网络。重定向后的每个地址都重新检查。

## 实施阶段

1. **核心模型与生命周期**：媒体配置、NBT 兼容、URL 校验、会话创建/替换/释放。（已搭建）
2. **图片闭环**：下载限制、解码、GPU 上传、屏幕显示、资源释放。（代码已接入，待真实客户端/GPU 回归）
3. **MP4 闭环**：FFmpeg 探测、视频帧、播放时钟、暂停和循环。
4. **声音闭环**：PCM 队列、音量、不衰减/线性衰减。
5. **Bilibili**：yt-dlp 工具发现、结构化解析、DASH 双流和过期重试。
6. **编辑与同步**：媒体 GUI、错误状态、服务端时间基准、客户端信任提示。

每阶段都先完成单个屏幕，再验证多个屏幕的资源共享和卸载清理。
