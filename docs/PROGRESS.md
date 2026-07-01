# Android 开发进度（对照 iOS）

> 更新时间：2026-07-01（含本轮代码静态审查 + WebRTC API 核实 + 后台保活功能）  
> Android 仓库：`yql-android` · iOS 对照：`srs_yql`（分支 `srs-yql-fz`）  
> **本文档供下一个 AI / 开发者接力使用，请优先阅读「七、2026-07-01 本轮改动」与「九、2026-07-01 代码审查 & API 核实结论」**

---

## 一、仓库对照

| 角色 | 本地路径（Mac 原工程） | 本地路径（Windows 当前） | 远程仓库 |
| --- | --- | --- | --- |
| **Android 端** | `/Users/chengyuan/aiqipai/ios/sysandroid` | `D:\sb\ios-android\yql-android` | https://github.com/yinyuan1990/yql-android.git |
| **iOS 端** | `/Users/chengyuan/aiqipai/ios/srs_yql` | `D:\sb\ios` | https://github.com/yinyuan1990/srs_yql.git |
| **PC 端** | `/Users/chengyuan/aiqipai/ios/yql/aifs` | — | https://github.com/yinyuan1990/aifs-pc.git |
| **后端** | `/Users/chengyuan/aiqipai/ios/yql-all/houduan-jfh` | — | https://github.com/yinyuan1990/houduan-jfh.git |

**拉取说明**：`D:\sb\ios-android\android.txt` 指示与 iOS 同账号拉 Android 代码；Windows 侧已 clone 到 `D:\sb\ios-android\yql-android`（2026-07-01）。

**平台区分方式**：API 与 iOS 完全一致，仅 `deviceId` 带 `android` 前缀（SHA256 派生）。

---

## 二、已完成功能 ✅

### 2.1 鉴权与 API 对齐

| 功能 | Android | iOS 参考 |
| --- | --- | --- |
| 登录/注册接口 | `/auth/login/device`、`/auth/register/device` | `APIService.swift` |
| AES 加密 body | `{ data, deviceId }` | 同 iOS |
| User-Agent | `iPhone/iOS`（与 iOS 一致） | 同 |
| deviceId | `android` + SHA256 前 32 位大写 | iOS 无前缀 |
| 推流 Token | `/auth/stream/token/simple` | 同 |
| 删号路径 | `/user/account/delete` | 同 |

### 2.2 WebSocket / STOMP

| 功能 | Android | iOS 参考 |
| --- | --- | --- |
| 连接地址 | `wss://ws.147258yql.cn/ws?token=&deviceId=` | `WebSocketManager.swift` |
| CONFIG_STATE 心跳 | 含 `connectstype=0`、`connectMode=srs`、`p2pViewerCount=0` | 同 |
| 远程配置订阅 | `/topic/device/{deviceId}/config` | 同 |

### 2.3 SRS WebRTC 推流

| 功能 | Android | iOS 参考 |
| --- | --- | --- |
| 推流 API | `http://{srsIP}:1985/rtc/v1/publish/` | `SRSManager.swift` |
| app 名称 | `tenantA` | 同 |
| streamKey | `{permanentToken}_{unixTimestamp}` | 同 |
| baseStreamKey | 存 permanentToken，重启推流复用 | 同 |
| 仅视频轨 | 无 audio track | 同 |
| stopPublish | SRS unpublish / deleteStream | 同 |
| initialize 幂等 | 防止重复创建 EglBase/Factory（黑屏修复） | — |

### 2.4 分辨率档位（4 档）

与 iOS `getCaptureResolutionForProfile()` 对齐，每档选设备最接近分辨率，**直接采集、不 scaleDown**：

| 档位 | iOS 目标 | 码率范围 (kbps) |
| --- | --- | --- |
| STANDARD | 1024×768 | 2700–4500 |
| HIGH | 1440×1080 | 3300–5500 |
| P4K (4:3) | 1920×1440 | 4500–7500 |
| ULTRA (16:9) | 1280×720 | 3300–5500 |

实现文件：`WebRTCManager.kt` → `findBestResolution()` / `applyProfile()`

### 2.5 自定义 Camera2 采集器

`Camera2ControlCapturer.kt` 替代 WebRTC 内置 `Camera2Capturer`，通过 `CaptureRequest` 做硬件控制：

| 远程配置 key | 硬件实现 | iOS 参考 |
| --- | --- | --- |
| zoom | `CONTROL_ZOOM_RATIO` / `SCALER_CROP_REGION` | 同 |
| focus | `LENS_FOCUS_DISTANCE` + AF OFF | 同 |
| cjfps | `SENSOR_EXPOSURE_TIME` + 手动 AE | 同 |
| exposure / test_brightness | AE 补偿 / ISO | 同 |
| white_balance / applyWhiteBalance | AWB 锁定 / 增益 | 同 |

`WebRTCManager.applyRemoteConfig()` 已路由上述分支。

### 2.6 UI 与系统

| 功能 | 状态 |
| --- | --- |
| 登录 / 注册 / 扫码绑定 | ✅ |
| 推流预览页 `StreamingScreen` | ✅ 自动 publish |
| **固定竖屏** | ✅ Manifest + MainActivity + 帧旋转固定 `deviceOrientation=0` |
| 权限（相机/网络） | ✅ |

### 2.7 🆕 发热优化（2026-07-01，代码已写，**待真机验证**）

| 项 | 说明 | 文件 |
| --- | --- | --- |
| **采集帧率空转修复** | ultra 档原先按设备能力采集到 240/120fps，但 `maxPushFps=60`，多出的帧在 ISP/传感器/纹理管线空转后被丢弃，是主要发热源之一。已改为 `ultraFps = minOf(60, maxFps)`，与推流上限对齐；慢门/快门仍由 `SENSOR_EXPOSURE_TIME` 控制，不依赖高采集帧率 | `WebRTCManager.kt` → `calculateLadder()` |
| **设备热状态监听** | 对标 iOS `ProcessInfo.thermalState`，基于 `PowerManager` 热状态 API（API 29+）。升温时自动降帧降码，回落恢复 | `ThermalManager.kt`（新） |
| **热控降档策略** | FAIR→≤30fps·0.8 码率；SERIOUS→≤20fps·0.6；CRITICAL→≤12fps·0.4；NOMINAL 恢复档位原始参数。约束贯穿 `setEncodingParameters` / `setTargetFps` / 自适应升帧 | `WebRTCManager.kt` → `applyThermalPolicy()` |

**设计原则（用户确认）**：优先低发热，不引入逐帧 GPU 滤镜。

### 2.8 🆕 颜色管线对标 iOS — 方案 A（2026-07-01，代码已写，**待 PC 拉流验证**）

| 项 | 说明 | 文件 |
| --- | --- | --- |
| **问题** | iOS `NV12MetalProcessor.swift` 给 NV12 打 BT.709 + full-range 元数据；Android 硬编码器默认不写 VUI，PC 端多按 BT.601 有限范围猜测 → 红色发暗、整体偏色 | iOS 参考：`srs/Managers/NV12MetalProcessor.swift` |
| **方案选择** | **方案 A**（低发热）：包装 `VideoEncoderFactory`，仅在**关键帧**改写 H264 SPS 的 VUI，写入 `video_full_range_flag=1` + BT.709 primaries/transfer/matrix。P 帧原样透传，几乎零额外功耗 | — |
| **未采用方案 B** | 自定义 `HardwareVideoEncoder` + MediaFormat `KEY_COLOR_STANDARD/RANGE/TRANSFER`（工作量大、回归风险高） | — |
| **未做 GPU 滤镜** | iOS 的曝光/亮度/gamma/对比度/饱和度/锐化/chroma 滤镜（`NV12Filter.metal`）**本轮未移植**，因逐帧 GPU 处理会增加发热 | 后续独立里程碑 |

实现文件：

- `ColorTaggingVideoEncoderFactory.kt` — 包装 H264 编码器，拦截关键帧回调
- `H264VuiEditor.kt` — Annex-B 切分、SPS 解析、VUI 改写、防竞争字节处理
- `WebRTCManager.kt` → `initialize()` 使用 `ColorTaggingVideoEncoderFactory(baseEncoderFactory)`

### 2.9 🆕 后台保活（2026-07-01，代码已写，**待真机验证**）

> 需求：系统**息屏后无法推流**（CPU 休眠 / 后台被限制 → 采集/编码/推流线程被冻结）。对标 iOS 用「无声音频保活」。

| 项 | 说明 | 文件 |
| --- | --- | --- |
| **无声音频保活** | 用 `AudioTrack` 以低采样率(8kHz/单声道/16bit)持续写「全零 PCM」（绝对静音），属性 `USAGE_MEDIA`，系统据此认为 App 在播放媒体，降低息屏/后台被限频、被杀概率。**纯零采样、无任何二进制音频资源、无可听声音** | `KeepAliveManager.kt`（新） |
| **PARTIAL_WAKE_LOCK** | 保持 CPU 运行，防止息屏后编码/推流线程被冻结（无超时，随推流启停 acquire/release） | `KeepAliveManager.kt` |
| **前台常亮** | Activity `FLAG_KEEP_SCREEN_ON`，对标 iOS `isIdleTimerDisabled`，进入推流页禁止自动息屏、离开恢复 | `StreamingScreen.kt` |
| **生命周期接入** | 自动推流 / `gongzuo`(唤醒) → `start()`；关闭按钮 / `shuimian`(睡眠) / `TryDisconnect` / 离开页面 → `stop()`。`start/stop` 幂等 | `StreamingScreen.kt` |

**iOS 对标**：`ContentView.swift` 的 `BackgroundAudioManager.startBackgroundKeepAlive()`（无声音频，因 App Store 审核当前注释隐藏）+ `UIApplication.shared.isIdleTimerDisabled = true`。

**权限**：无需新增——`WAKE_LOCK` 已在 Manifest 声明，`AudioTrack` 播放无需权限。

**设计取舍**：本方案覆盖「息屏但 App 仍在前台/推流」主场景。若需 App 完全退到后台仍长时间保活，Android 还需**前台服务(Foreground Service)** 承载（列为可选增强，见「三」）。

---

## 三、进行中 / 待完成 ⏳

| 项 | 说明 | 优先级 | iOS 状态 |
| --- | --- | --- | --- |
| **真机验证：发热** | Galaxy S25 等真机长时间推流，对比改前/改后机身温度、帧率、码率；确认热控降档是否触发/恢复 | 🔴 高 | iOS 有 thermalState |
| **真机验证：颜色** | PC 端拉 Android 流，对比 iOS 同色场景（尤其红色/肤色），确认 VUI 改写后 PC 不再偏色 | 🔴 高 | iOS 已对齐 |
| **真机验证：保活** | 真机推流后手动息屏 / 锁屏，观察是否持续推流不中断；确认 `KeepAliveManager` 的无声音频 + WakeLock 生效 | 🔴 高 | iOS 无声音频保活 |
| **前台服务保活增强** | App 完全退到后台时的长时间保活。当前仅无声音频+WakeLock，覆盖「息屏但在前台」主场景；如需退后台长稳，加 `ForegroundService`(mediaPlayback/microphone) + 通知 | 中 | iOS 后台音频 |
| **编译验证** | Windows 侧无 Android SDK，未跑 `./gradlew assembleDebug`；需在 Mac/Android Studio 编译 | 🔴 高 | — |
| **fbldy 档位日志** | 切换档位时打印当前分辨率/档位前缀 `fbldy` | 中 | iOS 有类似日志 |
| **B 类 GPU 滤镜** | captureColor、brightness/contrast/saturation/gamma、LUT（OpenGL ES 移植） | 低（发热敏感，暂缓） | iOS：`NV12MetalProcessor` + `NV12LUTProcessor` |
| **注册 check-device** | 注册前检查设备 | 中 | iOS 有调用 |
| **Profile / 绑定 / 激活 / 留言 / 头像** | 部分 REST 未完整落地 | 中 | iOS 完整 |
| **P2P 推流** | 暂缓，优先 SRS 稳定 | 低 | iOS 已实现 |
| **SRT 推流** | 暂缓 | 低 | iOS 已实现 |

---

## 四、核心文件对照

| Android | iOS | 说明 |
| --- | --- | --- |
| `WebRTCManager.kt` | `WebRTCManager.swift` | 推流总控、档位、热控、编码工厂 |
| `Camera2ControlCapturer.kt` | `CustomAVCaptureVideoCapturer.swift` | 自定义采集 + 硬件控制 |
| `ThermalManager.kt` | `ProcessInfo.thermalState` 处理逻辑 | 🆕 热状态监听 |
| `ColorTaggingVideoEncoderFactory.kt` | `NV12MetalProcessor` 色彩元数据部分 | 🆕 H264 VUI 包装 |
| `H264VuiEditor.kt` | — | 🆕 SPS VUI 位级改写 |
| `KeepAliveManager.kt` | `BackgroundAudioManager`(iOS) + `isIdleTimerDisabled` | 🆕 无声音频 + WakeLock 后台保活 |
| `WebSocketManager.kt` | `WebSocketManager.swift` | STOMP 长连接 |
| `NetworkService.kt` | `APIService.swift` | REST API |
| `APIConfig.kt` | `APIConfig.swift` | 配置 |
| `DeviceIDManager.kt` | 设备 ID 生成逻辑 | — |
| `StreamingScreen.kt` | 推流 UI | — |
| `LoginScreen.kt` | `MonitorLoginView.swift` | — |

---

## 五、已知问题与修复记录

| 问题 | 原因 | 修复 | 状态 |
| --- | --- | --- | --- |
| PC 收不到流 | `app=live`、streamKey 格式错误 | 改为 `tenantA` + `{token}_{ts}` | ✅ 已修 |
| 预览黑屏 | 重复 `initialize()` 创建多套 Factory | 幂等 guard | ✅ 已修 |
| 采集正常但无画面 | 推流参数与 iOS 不一致 | 对齐 SRS publish 字段 | ✅ 已修 |
| 物理旋转导致预览转 | 读取 Display.rotation | 固定竖屏 + rotation=0 | ✅ 已修 |
| **Android 发热严重** | ultra 档采集 240fps 但只推 60fps，帧空转；无热控 | 采集帧率钳 60 + `ThermalManager` 热控降档 | 🟡 代码已写，待真机验证 |
| **PC 端 Android 流偏色** | H264 无 BT.709+full-range VUI | `ColorTaggingVideoEncoderFactory` 关键帧改 SPS | 🟡 代码已写，待 PC 验证 |

---

## 六、下一步建议（给接力 AI）

1. **编译**：在 Mac/Android Studio 执行 `./gradlew assembleDebug`，修复可能的编译错误（尤其 `EncodedImage.builder()` API 是否与 `stream-webrtc-android:1.1.1` 匹配）。
2. **真机发热测试**：Galaxy S25 推流 30 分钟，记录改前/改后温度；切换 ultra 档确认采集帧率不再 >60；升温时确认热控日志 `🌡️ [热控]` 出现。
3. **PC 颜色对比**：Android 推流 → PC 拉流，与 iOS 同色场景截图对比；若仍有偏色，考虑方案 B（MediaFormat 注入）或检查机型 MediaCodec 实际色彩转换。
4. **提交代码**：改动未 push，本地在 `D:\sb\ios-android\yql-android`，建议 commit 后 push 到 `yinyuan1990/yql-android`。
5. **暂缓项**：GPU 滤镜移植、P2P/SRT、fbldy 日志 — 等 SRS + 发热 + 颜色验证通过后再做。

---

## 七、2026-07-01 本轮改动详情（接力必读）

### 7.1 背景与目标

用户要求：
1. 按 `D:\sb\ios-android\android.txt` 拉 Android 代码
2. **解决 Android 发热问题**
3. **颜色管线对标 iOS**
4. **优先低发热**（不引入逐帧 GPU 滤镜）

### 7.2 改动文件清单

| 文件 | 操作 | 改动摘要 |
| --- | --- | --- |
| `manager/WebRTCManager.kt` | 修改 | `calculateLadder()` ultra 采集帧率钳 60；新增 `ThermalManager` 集成与 `applyThermalPolicy()`；`initialize()` 使用 `ColorTaggingVideoEncoderFactory`；热控约束贯穿编码/自适应 FPS |
| `manager/ThermalManager.kt` | **新增** | `PowerManager.OnThermalStatusChangedListener`，归一化 NOMINAL/FAIR/SERIOUS/CRITICAL |
| `manager/ColorTaggingVideoEncoderFactory.kt` | **新增** | 包装 H264 `VideoEncoder`，关键帧回调中调用 `H264VuiEditor` |
| `manager/H264VuiEditor.kt` | **新增** | Annex-B NALU 切分、SPS 解析、VUI 写入 BT.709+full-range、防竞争字节 |
| `docs/PROGRESS.md` | 修改 | 本文档 |

### 7.3 关键代码位置（快速跳转）

```
WebRTCManager.kt
  ├── calculateLadder()          ~L253  ultraFps = minOf(60, maxFps)
  ├── initialize()               ~L156  ColorTaggingVideoEncoderFactory 包装
  ├── applyThermalPolicy()       ~L178  热控降帧降码
  ├── setEncodingParameters()    ~L707  热控约束叠加
  └── destroy()                  ~L1258 thermalManager.stop()

ThermalManager.kt              完整新文件
ColorTaggingVideoEncoderFactory.kt  完整新文件
H264VuiEditor.kt               完整新文件
```

### 7.4 iOS 对标参考（Windows 本地路径）

| iOS 文件 | 路径 | 对标内容 |
| --- | --- | --- |
| 色彩元数据 | `D:\sb\ios\srs\Managers\NV12MetalProcessor.swift` L91-98 | BT.709 + full-range 三件套 |
| GPU 滤镜 shader | `D:\sb\ios\srs\Managers\NV12Filter.metal` | 曝光/饱和度/chroma 等（Android 未移植） |
| 采集色彩格式 | `D:\sb\ios\srs\Managers\CustomAVCaptureVideoCapturer.swift` L32 | `kCVPixelFormatType_420YpCbCr8BiPlanarFullRange` |
| 热控 | iOS `ProcessInfo.thermalState` | Android 用 `ThermalManager` 近似 |

### 7.5 未验证 / 潜在风险

| 风险 | 说明 | 建议 |
| --- | --- | --- |
| 未编译 | Windows 环境无 Android SDK | Mac 上 `./gradlew assembleDebug` |
| ~~`EncodedImage.builder()` API~~ | ✅ **已核实通过**（2026-07-01 代码审查）：对照 stream-webrtc-android 官方 API 文档，`EncodedImage.Builder` 的 `setBuffer(ByteBuffer, Runnable?)` / `setEncodedWidth` / `setEncodedHeight` / `setCaptureTimeNs` / `setFrameType` / `setRotation` / `setQp(Integer?)` / `createEncodedImage()` 全部匹配当前 `ColorTaggingVideoEncoderFactory.kt` 的调用；`VideoEncoder.Callback` 为单方法 SAM，Kotlin lambda 转换合法。详见「九」 | 无需改动 |
| VUI 改写兼容性 | 部分机型 SPS 结构复杂，解析失败会透传原帧（安全兜底） | 真机 + PC 拉流确认 |
| 方案 A 局限 | 仅改 VUI 标签，若 MediaCodec 实际按 BT.601 转换像素，个别机型可能仍有轻微偏差 | 偏色仍存在时升级方案 B |
| API < 29 无热控 | `ThermalManager` 在 API 29 以下跳过监听 | 可接受，或后续加 CPU 温度 fallback |
| 改动未 commit/push | 仅本地 `D:\sb\ios-android\yql-android` | 验证通过后提交 |

### 7.6 用户决策记录

- 颜色方案：**A**（SPS VUI 改写，低发热）
- GPU 滤镜：**本轮不做**（发热敏感）
- 文档：**要求写进度文档供下一个 AI 接力**（即本文档）

---

## 八、构建与运行

```bash
cd D:\sb\ios-android\yql-android   # Windows
# 或 cd /Users/chengyuan/aiqipai/ios/sysandroid  # Mac

./gradlew assembleDebug
```

依赖：`io.getstream:stream-webrtc-android:1.1.1`（见 `gradle/libs.versions.toml`）

---

## 九、2026-07-01 代码审查 & API 核实结论（接力必读）

> 本节由接力 AI 在 Windows 环境（无 Android SDK，无法跑 `./gradlew`）完成，采用**静态代码审查 + 官方 API 文档核对**方式，专门验证「六、下一步建议」中的头号风险——本轮新增/修改代码是否与 `stream-webrtc-android:1.1.1` API 匹配、逻辑是否自洽。

### 9.1 结论速览

- ✅ **未发现编译阻断错误**（基于对 stream-webrtc-android 官方 API 文档逐方法核对）
- ✅ **未发现会导致崩溃/黑帧/推流失效的逻辑 bug**
- ✅ 文档原先担心的 `EncodedImage.builder()` API **已核实完全匹配**，无需改动
- ⚠️ 仍需**真机 + PC 拉流**做最终功能验证（发热、颜色），静态审查无法替代

### 9.2 逐文件核对

| 文件 | 核对项 | 结论 |
| --- | --- | --- |
| `ColorTaggingVideoEncoderFactory.kt` | `VideoEncoderFactory` 三方法（`getSupportedCodecs`/`createEncoder`）、`VideoEncoder` 全部 override 方法签名 | ✅ 与官方接口一致。`VideoEncoder` 接口中 `setRates`/`getResolutionBitrateLimits`/`getEncoderInfo`/`isHardwareEncoder` 为 `open`（可覆盖），`initEncode`/`release`/`encode`/`setRateAllocation`/`getScalingSettings`/`getImplementationName` 为 `abstract`（必须覆盖），代码全部正确覆盖 |
| 同上 | `EncodedImage.Builder` 链式调用 | ✅ `setBuffer(out, null)`（stream 版签名为 `setBuffer(ByteBuffer, Runnable?)`）、`setQp(frame.qp)`（`setQp(Integer?)`）等全部匹配 |
| 同上 | 关键帧 buffer 生命周期 | ✅ 安全。`maybeTag` 用 `src.duplicate().get(data)` **拷贝**出字节，不持有原 buffer；返回帧用自建 `allocateDirect` buffer。符合「onEncodedFrame 返回后原 buffer 不可再访问」的约束 |
| 同上 | `VideoEncoder.Callback` SAM 转换 | ✅ 该接口仅一个抽象方法 `onEncodedFrame(EncodedImage, CodecSpecificInfo)`，Kotlin `VideoEncoder.Callback { frame, info -> ... }` lambda 合法 |
| `H264VuiEditor.kt` | Annex-B 切分 / EBSP↔RBSP / SPS 位级解析 / Exp-Golomb 读写 / 最小 VUI 写入 | ✅ 逻辑自洽，纯 Kotlin 无第三方依赖。异常一律返回 null（调用方透传原帧），安全兜底完整 |
| `ThermalManager.kt` | `PowerManager.OnThermalStatusChangedListener`、`addThermalStatusListener`/`removeThermalStatusListener`/`currentThermalStatus`、API 29 门禁 | ✅ 均为标准 Android API，SDK_INT<Q 正确跳过 |
| `WebRTCManager.kt` | 热控约束是否贯穿 | ✅ `thermalFpsCap`/`thermalBitrateScale` 在 `setEncodingParameters()`（帧率 `minOf(currentFps, maxPushFps, thermalFpsCap)`、码率 ×scale）与自适应升帧上限（`maxFps = minOf(maxPushFps, thermalFpsCap)`）均已叠加；`initialize()` 正确用 `ColorTaggingVideoEncoderFactory` 包装 `DefaultVideoEncoderFactory` |
| 同上 | `calculateLadder()` 采集帧率钳制 | ✅ `safeFps60 = minOf(60, maxFps)`、`ultraFps = minOf(60, maxFps)`，四档采集帧率均已对齐推流上限 60 |

### 9.3 核实所用官方 API 依据

- `EncodedImage` / `EncodedImage.Builder`：getstream.github.io/webrtc-android 官方 dokka 文档 + chromium/webrtc 源码，确认 `setBuffer(ByteBuffer, Runnable?)`、`setQp(Integer?)`、`createEncodedImage()` 等签名。
- `VideoEncoder` 接口：官方 dokka 列出全部方法及其 `abstract`/`open` 属性，据此确认包装类 override 无遗漏、无多余。
- `VideoEncoder.Callback`：单抽象方法 `onEncodedFrame(EncodedImage, CodecSpecificInfo)`，支持 SAM。

### 9.4 非阻断的观察项（可后续优化，非必须）

| 观察 | 说明 | 影响 | 建议 |
| --- | --- | --- | --- |
| 热控降帧未即时回写 `adaptiveFps` | `applyThermalPolicy()` 设 `currentFps=targetFps`，但自适应模块的 `adaptiveFps` 仍保留旧值，直到网络触发才下调 | **无实际风险**：`setEncodingParameters()` 下发编码器时用 `minOf(currentFps, maxPushFps, thermalFpsCap)`，最终推流帧率始终受 `thermalFpsCap` 钳制 | 可选：热控降档时同步 `adaptiveFps = minOf(adaptiveFps, thermalFpsCap)`，使日志更直观 |
| `createNative(webrtcEnvRef: Long): Long` 未覆盖 | stream 版 `VideoEncoder` 有此 `open` 方法（默认走 Java encoder 路径） | 无：底层 `HardwareVideoEncoder` 为 Java 实现，走 `initEncode/encode`，包装生效 | 无需改动，仅记录 |

### 9.5 仍未完成 / 交给下一棒

- 🔴 **编译验证**：Windows 无 Android SDK，本节仅静态核对，仍需在 Mac/Android Studio 跑 `./gradlew assembleDebug` 做最终确认。
- 🔴 **真机发热测试** 与 **PC 颜色对比**：见「六、下一步建议」2、3 项，静态审查不可替代。
- ⚪ 代码改动仍未 commit/push（本地 `D:\sb\ios-android\yql-android`）。
