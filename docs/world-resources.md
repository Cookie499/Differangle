# 持久化摄像机与显示屏

Camera 和 Screen 由服务端保存并同步，单人与多人采用同一路径。所有人共享屏幕绑定；创建、配置和拆除需要命令权限等级 2。

## 快速使用

1. `/give @s differangle:screen_base`，将基座放在墙面、地板或天花板上。
2. 站到拍摄位置，执行 `/differangle camera create lobby`。初始位置为眼睛位置，朝向沿玩家视线。
3. 执行 `/differangle camera list` 查看 UUID，右键基座，在界面中填写 UUID 并应用。
4. 界面可调整尺寸、分辨率、FPS、开关、偏移、三轴旋转和边框厚度。留空 UUID 即明确解绑。

默认画面为 2 × 1.125 格、256 × 144、15 FPS、FOV 70°。局部 +Z 是画面正面；屏幕与杆没有碰撞或选择框，仅基座可选取。偏移在安装面的局部坐标中计算，旋转按 Y、X、Z 顺序组合成规范化四元数。世界画面使用现有合成渲染事件；基座使用普通方块模型，外壳和杆由同一客户端几何模块绘制，不产生额外 Camera 渲染。

## 服务端指令

```text
/differangle camera create <name> [invisible]
/differangle camera list
/differangle camera status|remove <uuid或名称>
/differangle camera enabled|invisible <uuid或名称> <true|false>
/differangle camera fov|fps <uuid或名称> <value>
/differangle camera pose <id> <x> <y> <z> <yaw> <pitch> <roll> [ticks] [easing]
/differangle camera move <id> <x> <y> <z> [ticks] [easing]
/differangle camera look <id> <yaw> <pitch> <roll> [ticks] [easing]
/differangle screen bind <x> <y> <z> <camera-uuid|none>
/differangle screen configure <x> <y> <z> <width> <height> <resX> <resY> <fps>
/differangle screen transform <x> <y> <z> <offsetX> <offsetY> <offsetZ> <yaw> <pitch> <roll>
/differangle screen enabled <x> <y> <z> <true|false>
/differangle screen status <x> <y> <z>
```

指令坐标为绝对坐标，名称不含空格；重名时必须使用 UUID。`ticks` 默认 0，即立即设置。`easing` 支持 `step`、`linear`、`smoothstep`；旋转采用 shortest-path quaternion slerp。两点运动与播放进度写入实体存档。

`/differangle camera invisible <id> false` 显示轴线、视锥及 UUID，摄像机仍然无碰撞、无选取、无重力和阴影。

隐形使用原版实体的 `setInvisible` / `isInvisible` 和同步标志，NBT 字段为大小写敏感的 `Invisible`，不另存小写 `invisible`。例如 `/data merge entity <UUID> {Invisible:1b}` 隐藏，`{Invisible:0b}` 显示；移除或缺省该标签时按原版约定为 false。模组 `camera create` 指令默认显式设为 true；原版 `/summon differangle:camera` 未指定标签时可见。隐形仅控制实体调试外观，不关闭摄像机画面。

客户端调试入口 `/differangle mode`、`layer`、`status`、`list` 保留。原有临时创建/编辑指令移至 `/differangle preview demo|camera|screen|clear`；这些预览不保存到世界。持久化命令使用 `/differangle camera` 与 `/differangle screen`。

## 生命周期

- 带 NBT 的物品、Ctrl+选取、`/clone` 和结构复制保留 Camera 绑定及配置，但放置目标生成自己的 Screen UUID。复制屏幕不会复制 Camera 实体。
- 普通世界读档保留 Screen UUID；拆除后掉落带配置和绑定的物品，再次放置生成新 Screen UUID。活塞不能推动基座。
- 删除/卸载 Camera 保留 Screen 的绑定，画面黑屏。Camera 再次同步时自动恢复；`bind ... none` 才会清空绑定。
- Screen 关闭保留外壳和连接杆；Camera 关闭立即释放其帧缓存；同一 Camera 的其他有效 Screen 可以继续共享画面。
- 背面不显示画面、不提出 Camera 更新需求。首次画面完成前为黑屏，黑屏不分配 RenderTarget。
- 不加载额外区块，不支持跨维度画面；Camera 和拍摄内容须已同步到当前客户端。

同一 Camera 多屏共享一份 Target：采用启用屏幕中像素面积最大的分辨率，刷新率取屏幕请求最大值与 Camera 上限的较小值。不同宽高比的屏幕会拉伸同一画面。Embedded 模式没有共享 Target，每块屏幕按自己的分辨率宽高比投影，因此同一 Camera 的多块屏幕可以各自设置内部拉伸。

## 首版范围

已接入方块/实体注册、同步与存档、复制和绑定、右键设置、六面安装、几何变换、开关黑屏、单面显示、两点插值和调试几何。完整路径编辑、Replay、镜子、远程区块订阅、Iris/Sodium 兼容不在本次范围。

`build` 执行核心单元测试。`-PcameraGameTest runCameraTest` 使用 `build/camera-gametest` 中的独立测试世界，覆盖原有内容渲染以及本次资源同步、NBT 复制/恢复、界面、黑屏、恢复、移动和背面过滤；不访问 `run/saves`。
