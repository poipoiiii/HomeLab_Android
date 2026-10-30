# Anywear / Decart 虚拟试衣（规划中，尚未接入）

> 状态：只留设计与 demo 代码，**未改 gradle、未加依赖、未编译、未上手机**。
> 结论：技术上可接（走 Decart 官方 Android SDK），但按秒计费、摄像头画面出内网，是否真做待定。

## 1. Anywear 是什么

`anywear.decart.ai` 是 Decart 的实时虚拟试衣产品，底层模型 `lucy-vton-3.6`：

- 输入：摄像头实时画面 + 一张服装参考图（+ 文字描述）
- 输出：WebRTC 实时视频，衣服"穿"在人身上，随动作变化（不是贴图）
- 三种形态，只有第三种能进原生 App

| 形态 | 形态 | 能否用于本 App |
| --- | --- | --- |
| 网页 widget | 商家网页一行 `<script src="https://tryanywear.ai/widget/latest/anywear.js?domain=...">` | 不能，Web 组件 |
| Chrome 扩展 | 桌面上对任意网站的衣服试用 | 不能，手机不装扩展 |
| Decart API / SDK | REST 发临时 token + WebRTC 连 `lucy-vton-3.6` | 能，有官方 Android SDK |

- 官方仓库：<https://github.com/DecartAI/decart-android>（包名 `ai.decart.sdk`）
- 文档：<https://docs.platform.decart.ai/sdks/android>、<https://docs.platform.decart.ai/sdks/android-realtime>
- 试穿示例（六种场景源码）：<https://github.com/DecartAI/tryon-examples>

## 2. 与本项目的适配情况

| 约束 | Decart 要求 | 本项目现状 | 结论 |
| --- | --- | --- | --- |
| minSdk | API 24+ | 26 | 满足 |
| Kotlin | 2.1+ | 2.3.21 | 满足 |
| Java | 17 | 17 | 满足 |
| 设备 | 需真机（模拟器无 WebRTC 相机） | 一直是真机 | 满足 |
| 依赖源 | JitPack | 只有阿里云 / google / mavenCentral | **需在 settings 加 jitpack** |
| 离线构建 | 需联网拉包 | 脚本走 `--offline` | **首次必须联网一次**，JitPack 现编译可能慢或失败 |

## 3. 代价与风险（决定要不要做的关键）

1. **按秒计费**：`lucy-vton-3.6` 720p 为 **$0.02/秒**，fast 模式 $0.04/秒。30 秒约 $0.6，1 分钟约 $1.2（<https://docs.platform.decart.ai/getting-started/pricing>）。新账号送免费额度，无免费档。SDK 有 `generationTicks`，可以实时算钱、加时长上限。
2. **画面出内网**：摄像头帧经 WebRTC 推到 Decart 云端和 LiveKit 房间，隐私边界与 homelab "数据都在自己内网" 的定位冲突。家里网络还要能出站访问 `api.decart.ai` 与媒体端口。
3. **Key 管理**：官方明确禁止把永久 Key 打进 APK（可被反编译）。本项目按 AGENTS 规则也只能存本机 DataStore 或自建取临时 token 的小服务。
4. **定位**：纯消费级玩法，与 AI / 账单 / 照片 / 自动化主线无关，属于独立 `feature/tryon/`。
5. **SDK 是 0.7 代**：Android 侧 publisher 只有视频，麦克风相关参数被忽略（不需要，试衣本来不用音频）。

## 4. 接入方案（选定 B：原生 SDK）

### 4.1 gradle 改动（尚未执行）

`HomelabApp/settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 里加：

```kotlin
maven { url = uri("https://jitpack.io") }
```

`HomelabApp/app/build.gradle.kts` 加依赖（版本以 Changelog 的 0.8.1 为准；文档安装页写的是 0.7.10，两者都可先试，未验证）：

```kotlin
implementation("com.github.DecartAI:decart-android:0.8.1")
```

`AndroidManifest.xml` 加权限（`INTERNET` 已有则跳过；音频用不到，先不要 `RECORD_AUDIO`）：

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

### 4.2 会话封装 demo

```kotlin
package com.poi.homelab.feature.tryon

import ai.decart.sdk.DecartClient
import ai.decart.sdk.DecartClientConfig
import ai.decart.sdk.RealtimeModels
import ai.decart.sdk.realtime.ConnectOptions
import ai.decart.sdk.realtime.FacingMode
import ai.decart.sdk.realtime.InitialPrompt
import ai.decart.sdk.realtime.MirrorMode
import ai.decart.sdk.realtime.RealtimeMediaStream
import android.content.Context
import android.util.Base64

/** 一次试衣会话：一个 client 对应一段实时连接，结束时必须 release()。 */
class TryOnSession(context: Context, apiKey: String) {

    private val client = DecartClient(
        context = context.applicationContext,
        config = DecartClientConfig(apiKey = apiKey),
    )

    val realtime = client.realtime

    /** 首次连上就把第一帧换成穿好衣服的样子，避免闪一下原画面。 */
    fun start(garmentJpeg: ByteArray, prompt: String, onStream: (RealtimeMediaStream) -> Unit) {
        realtime.connect(
            ConnectOptions(
                model = RealtimeModels.LUCY_VTON_LATEST, // 3.6
                initialImage = garmentJpeg.toBase64(),
                initialPrompt = InitialPrompt(text = prompt, enhance = true),
                facing = FacingMode.FRONT,
                mirror = MirrorMode.AUTO,
                publishCamera = true, // SDK 自己开相机并发布
                onRemoteStream = onStream,
            ),
        )
    }

    /** 换下一件：不重连，直接替换参考图，suspends 到服务端 ack。 */
    suspend fun swap(garmentJpeg: ByteArray, prompt: String) {
        realtime.setImage(
            imageBase64 = garmentJpeg.toBase64(),
            prompt = prompt,
            enhance = true,
        )
    }

    fun stop() = realtime.disconnect()

    fun release() = client.release()

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
}
```

### 4.3 渲染 demo（Compose）

```kotlin
@Composable
fun TryOnSurface(stream: RealtimeMediaStream?, modifier: Modifier = Modifier) {
    val room = stream?.room ?: return
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(room.lkObjects.eglBase.eglBaseContext, null)
                stream.videoTrack?.addRenderer(this) // 注意不要额外开镜像，mirror 已在采集端做过
            }
        },
        onRelease = { it.release() },
    )
}
```

- 自动重连后服务端会给新的 `RealtimeMediaStream`，要收 `realtime.remoteStreamUpdates` 把渲染器重新挂上，否则断线回来是黑屏。
- 状态收 `realtime.connectionState`（`CONNECTING / CONNECTED / GENERATING / RECONNECTING / DISCONNECTED`）和 `realtime.errors`（`INVALID_API_KEY`、`WEBRTC_ICE_ERROR` 等）做 UI。
- 计费展示用 `realtime.generationTicks`，累加秒数 × $0.02 显示，并设一个硬上限（例如 120 秒自动 `stop()`）。

### 4.4 prompt 写法（影响成败）

参考图最好是**平铺白底商品图**，prompt 描述衣服本身，不要描述人：

- `Substitute the current top with a navy blue hoodie with a white cross logo on the chest`
- `Substitute the current outfit with a beige linen shirt and matching trousers`
- 只换上半身就写 `top`，全身写 `outfit`；配饰写 `Replace the glasses with ...`

`enhance = true` 会让 Decart 自动扩写 prompt，效果更稳；要精确控制时设 `false`。

### 4.5 Key 获取（二选一）

- **自用最简**：设置页填 Key，存 DataStore（沿用现有设置体系），请求时直接构造 client。缺点：Key 在设备上，root/备份可能泄露。
- **更稳**：自建一个小接口转发 `POST /v1/client/tokens`（官方 JS/Python SDK 里的 `client.tokens.create()`），App 每次连接前换 60 秒临时 token。家里机器可放，但要写服务端 + 鉴权。

## 5. 真要做时的落地清单

1. `settings.gradle.kts` 加 JitPack，`app/build.gradle.kts` 加 SDK 依赖，联网跑一次 `:app:compileDebugKotlin`（去掉 `--offline`）。
2. `AndroidManifest.xml` 加 `CAMERA`，运行时申请。
3. 新建 `feature/tryon/`：`TryOnScreen.kt`（全屏渲染 + 底部服装列表 + 计时/费用 + 停止）、`TryOnViewModel.kt`（会话生命周期、StateFlow）。
4. 服装图片来源：相册选图（`PickVisualMedia`），或复用 `feature/photos/` 里已上传的衣物图。
5. 设置页加入口与 Key/额度提示；导航抽屉加一项。
6. 退出页面必须 `release()`，避免持续计费；后台/息屏也要断。

## 6. 待验证

- JitPack 上 `decart-android` 实际可用版本（0.8.1 未实测）。
- 家中网络到 `api.decart.ai` 与 LiveKit 媒体端口的可达性、延迟。
- 试穿效果在真实商品图上的稳定性（官方说并非所有服装都能渲染好，线上只展示命中的款）。
