# Android 开发进度（对照 iOS）

> 更新时间：2026-06-30  
> Android 仓库：`yql-android` · iOS 对照：`srs_yql`（分支 `srs-yql-fz`）

---

## 一、仓库对照

| 角色 | 本地路径 | 远程仓库 |
| --- | --- | --- |
| **Android 端** | `/Users/chengyuan/aiqipai/ios/sysandroid` | https://github.com/yinyuan1990/yql-android.git |
| **iOS 端** | `/Users/chengyuan/aiqipai/ios/srs_yql` | https://github.com/yinyuan1990/srs_yql.git |
| **PC 端** | `/Users/chengyuan/aiqipai/ios/yql/aifs` | https://github.com/yinyuan1990/aifs-pc.git |
| **后端** | `/Users/chengyuan/aiqipai/ios/yql-all/houduan-jfh` | https://github.com/yinyuan1990/houduan-jfh.git |

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

---

## 三、进行中 / 待完成 ⏳

| 项 | 说明 | iOS 状态 |
| --- | --- | --- |
| **fbldy 档位日志** | 切换档位时打印当前分辨率/档位前缀 `fbldy` | iOS 有类似日志 |
| **B 类 GPU 滤镜** | captureColor、brightness/contrast/saturation/gamma、LUT | iOS：`NV12MetalProcessor` + `NV12LUTProcessor` |
| **注册 check-device** | 注册前检查设备 | iOS 有调用 |
| **Profile / 绑定 / 激活 / 留言 / 头像** | 部分 REST 未完整落地 | iOS 完整 |
| **P2P 推流** | 暂缓，优先 SRS 稳定 | iOS 已实现 |
| **SRT 推流** | 暂缓 | iOS 已实现 |

---

## 四、核心文件对照

| Android | iOS |
| --- | --- |
| `WebRTCManager.kt` | `WebRTCManager.swift` |
| `Camera2ControlCapturer.kt` | `CustomAVCaptureVideoCapturer.swift` |
| `WebSocketManager.kt` | `WebSocketManager.swift` |
| `NetworkService.kt` | `APIService.swift` |
| `APIConfig.kt` | `APIConfig.swift` |
| `DeviceIDManager.kt` | 设备 ID 生成逻辑 |
| `StreamingScreen.kt` | 推流 UI |
| `LoginScreen.kt` | `MonitorLoginView.swift` |

---

## 五、已知问题与修复记录

| 问题 | 原因 | 修复 |
| --- | --- | --- |
| PC 收不到流 | `app=live`、streamKey 格式错误 | 改为 `tenantA` + `{token}_{ts}` |
| 预览黑屏 | 重复 `initialize()` 创建多套 Factory | 幂等 guard |
| 采集正常但无画面 | 推流参数与 iOS 不一致 | 对齐 SRS publish 字段 |
| 物理旋转导致预览转 | 读取 Display.rotation | 固定竖屏 + rotation=0 |

---

## 六、下一步建议

1. 在真机（如 Galaxy S25）验证竖屏锁定 + SRS 推流 + PC 拉流全链路
2. 补 `fbldy` 档位切换日志，便于与 iOS 对比
3. SRS 稳定后再评估 P2P / SRT 移植优先级
4. 滤镜管线（OpenGL/RenderScript/Vulkan）作为独立里程碑
