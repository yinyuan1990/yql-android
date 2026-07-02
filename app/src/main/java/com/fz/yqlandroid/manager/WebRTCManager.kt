package com.fz.yqlandroid.manager

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import com.fz.yqlandroid.config.APIConfig
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.*
import java.util.concurrent.TimeUnit

// ===== 档位定义（与iOS LadderProfile一致，共4档：3个4:3 + 1个16:9） =====
enum class LadderProfile {
    STANDARD,   // 高清   (4:3)
    HIGH,       // 超清   (4:3)
    ULTRA,      // 超高帧 (16:9)
    P4K         // 超高清 (4:3)
}

// ===== 档位预设 =====
// 🔥 与iOS LadderPreset一致：width/height 为“目标输出分辨率”，实际采集分辨率由程序按设备能力就近决策，
//    通过 scaleDown 从采集分辨率缩放到目标输出。码率使用 min~max 区间（不再锁死CBR）。
data class LadderPreset(
    val width: Int,         // 目标输出宽度
    val height: Int,        // 目标输出高度
    val fps: Int,           // 采集FPS
    val maxKbps: Int,       // 最大码率
    val minKbps: Int,       // 最小码率（约max的60%，允许WebRTC向下自适应）
    val maxPushFps: Int,    // 最大推流FPS
    val scaleDown: Double,  // 缩放比例（1.0=不缩放）= 采集高 / 目标输出高
    val is16x9: Boolean     // 该档是否为16:9采集（仅ultra为true）
)

/**
 * WebRTC推流管理器
 * 与iOS WebRTCManager 保持一致
 * 
 * 🔥 动态分辨率适配：
 * - 启动时查询设备摄像头实际支持的分辨率
 * - 选择最接近目标的分辨率，避免崩溃
 * - 与iOS一致的4档配置：高清/超清/超高清/超高帧
 * - 超高帧(ultra)使用16:9，其他用4:3
 */
class WebRTCManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WebRTCManager"
        private const val VIDEO_TRACK_ID = "video0"
        private const val STREAM_ID = "s0"

        // 🔥 关键帧节奏参数（2026-07-02 起仅按需触发，周期/快速窗口策略已删，见「关键帧」小节）
        private const val REQUEST_KEYFRAME_MIN_INTERVAL_MS = 1000L  // 观看端 request_keyframe 节流
    }
    
    // WebRTC 核心组件
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoSender: RtpSender? = null
    
    // EGL上下文
    private var eglBase: EglBase? = null
    
    // 推流参数
    private var srsIP: String = ""
    private var app: String = "tenantA"        // 🔥 与iOS一致（PC按此app段拉流）
    private var baseStreamKey: String = ""      // 基础流名（permanentToken）
    private var streamKey: String = ""          // 实际流名（基础流名_时间戳）
    
    // 状态
    var isPublishing: Boolean = false
        private set
    var isPreviewRunning: Boolean = false
        private set
    var isFrontCamera: Boolean = false
        private set
    
    // 🔥 动态适配的采集参数（启动时根据设备能力计算）
    var currentFps: Int = 30
        private set
    var currentBitrateKbps: Int = 2000
        private set
    var currentMinBitrateKbps: Int = 1200
        private set
    // 🔥 采集分辨率（由程序按设备能力就近决策，不硬编码）
    var currentWidth: Int = 1280
        private set
    var currentHeight: Int = 720
        private set
    
    // 🔥 档位配置（动态计算，前后置分别设置）
    var currentProfile: LadderProfile = LadderProfile.STANDARD
        private set
    var currentLadder: Map<LadderProfile, LadderPreset> = emptyMap()
        private set
    
    // 🔥 设备摄像头能力缓存
    private var backCameraFormats: List<Size> = emptyList()
    private var frontCameraFormats: List<Size> = emptyList()
    private var backMaxFps: Int = 30
    private var frontMaxFps: Int = 30
    // 🔥 每个分辨率各自支持的最大采集帧率（key=分辨率, value=该分辨率下的最高fps）
    //    用于“相同/接近分辨率时 60fps 优先”的选档策略：只有当没有 60fps 选项时才退回 30fps。
    private var backSizeMaxFps: Map<Size, Int> = emptyMap()
    private var frontSizeMaxFps: Map<Size, Int> = emptyMap()
    
    // 预览渲染器
    private var localRenderer: SurfaceViewRenderer? = null
    
    // 回调
    var onStatsUpdate: ((Int, Int, Int) -> Unit)? = null
    var onConnectionStateChanged: ((String) -> Unit)? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    // 关键帧定时器
    private var keyframeJob: Job? = null
    private var statsJob: Job? = null

    // 🌡️ 设备热状态管理（对标 iOS thermalState 主动降档）
    private val thermalManager = ThermalManager(context)
    // 当前热档位对采集/推流的约束：fps 上限 + 码率缩放系数（1.0=不降）
    private var thermalFpsCap: Int = Int.MAX_VALUE
    private var thermalBitrateScale: Double = 1.0
    
    // MARK: - 初始化
    
    @Volatile private var isInitialized = false
    
    fun initialize() {
        // 🔥 幂等：EGL/工厂只创建一次，避免重复 initialize 造出第二套 EglBase/Factory
        //    导致预览轨道(工厂1)与推流PC(工厂2)不一致 → 黑屏/推流黑帧
        if (isInitialized && eglBase != null && peerConnectionFactory != null) {
            Log.d(TAG, "⏭️ WebRTC已初始化，跳过重复initialize")
            return
        }
        Log.d(TAG, "🔧 初始化WebRTC...")
        
        // 🔥 Step 1: 查询设备摄像头能力
        queryCameraCapabilities()
        
        // 🔥 Step 2: 根据摄像头能力计算档位
        calculateLadder(isFrontCamera)
        
        // Step 3: 初始化EGL
        eglBase = EglBase.create()
        
        // Step 4: 初始化PeerConnectionFactory
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        
        val baseEncoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true,  // 启用硬件编码
            true   // 启用H264高Profile
        )
        // 🎨 颜色管线对标 iOS：包一层，仅在关键帧给 H264 SPS 补 BT.709+full-range 的 VUI
        // 🔥 同时接入“运动突增”回调：大范围拖动导致 P 帧字节数突增时，进入快速关键帧窗口（0.1~0.5s）修复花屏
        val encoderFactory = ColorTaggingVideoEncoderFactory(baseEncoderFactory) {
            onEncoderMotionSurge()
        }
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
        
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        
        // 🌡️ 启动设备热状态监听（对标 iOS thermalState），升温时自动降帧降码
        thermalManager.onLevelChanged = { level -> applyThermalPolicy(level) }
        thermalManager.start()

        isInitialized = true
        Log.d(TAG, "✅ WebRTC初始化完成")
    }

    // MARK: - 🌡️ 热控降档（对标 iOS thermalState 处理）

    /**
     * 根据设备热档位调整采集/推流参数，抑制发热。
     * - fps 上限：降低采集与推流帧率（采集降帧同时减轻 ISP/传感器发热）
     * - 码率缩放：降低编码码率，减少编码器/调制解调器发热
     * 档位越高约束越强；回落到 NOMINAL 时恢复档位原始参数。
     */
    private fun applyThermalPolicy(level: ThermalManager.Level) {
        val preset = currentLadder[currentProfile]
        val baseFps = preset?.fps ?: currentFps
        val baseMaxKbps = preset?.maxKbps ?: currentBitrateKbps

        when (level) {
            ThermalManager.Level.NOMINAL -> { thermalFpsCap = Int.MAX_VALUE; thermalBitrateScale = 1.0 }
            ThermalManager.Level.FAIR -> { thermalFpsCap = 30; thermalBitrateScale = 0.8 }
            ThermalManager.Level.SERIOUS -> { thermalFpsCap = 20; thermalBitrateScale = 0.6 }
            ThermalManager.Level.CRITICAL -> { thermalFpsCap = 12; thermalBitrateScale = 0.4 }
        }

        val targetFps = minOf(baseFps, thermalFpsCap)
        val targetKbps = maxOf(300, (baseMaxKbps * thermalBitrateScale).toInt())
        Log.d(TAG, "🌡️ [热控] $level → fps≤$targetFps, 码率≤${targetKbps}kbps (base ${baseFps}fps/${baseMaxKbps}kbps)")
        Log.d("meidui", "⚠️ fps修改源=热控 $level → fps≤$targetFps")

        // 1) 编码参数（帧率 + 码率）立即生效，平滑无重建
        currentFps = targetFps
        // 🔥 同步自适应基准：否则 adaptiveFps 仍停留在旧值(如30)，下次网络好触发升帧分支
        //    minOf(上限20, 30+2)=20 反而比旧值小，打出「自适应升帧 30→20fps」的假升帧日志
        if (adaptiveFps > targetFps) adaptiveFps = targetFps
        currentBitrateKbps = targetKbps
        currentMinBitrateKbps = maxOf(200, (targetKbps * 0.6).toInt())
        videoSender?.let { sender ->
            val params = sender.parameters
            if (params.encodings.isNotEmpty()) {
                params.encodings[0].maxFramerate = targetFps
                params.encodings[0].maxBitrateBps = targetKbps * 1000
                params.encodings[0].minBitrateBps = currentMinBitrateKbps * 1000
                sender.parameters = params
            }
        }

        // 2) 采集帧率降档（重建会话）——真正减轻传感器/ISP 发热；仅在明显变化时执行
        if (isPreviewRunning) {
            val capFps = minOf(baseFps, thermalFpsCap)
            try {
                videoCapturer?.changeCaptureFormat(currentWidth, currentHeight, capFps)
            } catch (e: Exception) {
                Log.e(TAG, "热控降采集帧率失败: ${e.message}")
            }
        }
    }
    
    // MARK: - 🔥 动态查询摄像头能力
    
    private fun queryCameraCapabilities() {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                
                // 获取支持的分辨率列表
                val imageFormat = android.graphics.ImageFormat.YUV_420_888
                val sizes = map.getOutputSizes(imageFormat)
                    ?.toList()
                    ?.sortedByDescending { it.width * it.height }
                    ?: emptyList()
                
                // 摄像头整机最大FPS（AE 帧率区间上界）
                val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val aeMaxFps = fpsRanges?.maxOfOrNull { it.upper } ?: 30
                
                // 🔥 逐分辨率计算“该分辨率支持的最大帧率”：
                //    fps = 1e9 / getOutputMinFrameDuration(nanos)。再与 AE 区间上界取 min，避免超过传感器实际可达帧率。
                val sizeMaxFps = HashMap<Size, Int>()
                for (s in sizes) {
                    val perSize = try {
                        val minDurNs = map.getOutputMinFrameDuration(imageFormat, s)
                        if (minDurNs > 0) (1_000_000_000.0 / minDurNs).toInt() else aeMaxFps
                    } catch (_: Exception) { aeMaxFps }
                    // 与整机 AE 上界取 min（部分机型 minFrameDuration 给出的理论值高于实际可用 AE 帧率）
                    sizeMaxFps[s] = minOf(perSize, aeMaxFps).coerceAtLeast(1)
                }
                
                when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> {
                        backCameraFormats = sizes
                        backMaxFps = aeMaxFps
                        backSizeMaxFps = sizeMaxFps
                        Log.d(TAG, "📷 后置摄像头: ${sizes.size}种分辨率, 整机最大${aeMaxFps}fps")
                        sizes.take(8).forEach { Log.d(TAG, "   ${it.width}x${it.height} @最大${sizeMaxFps[it]}fps") }
                    }
                    CameraCharacteristics.LENS_FACING_FRONT -> {
                        frontCameraFormats = sizes
                        frontMaxFps = aeMaxFps
                        frontSizeMaxFps = sizeMaxFps
                        Log.d(TAG, "📷 前置摄像头: ${sizes.size}种分辨率, 整机最大${aeMaxFps}fps")
                        sizes.take(8).forEach { Log.d(TAG, "   ${it.width}x${it.height} @最大${sizeMaxFps[it]}fps") }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ 查询摄像头能力失败: ${e.message}")
        }
    }
    
    /**
     * 🔥 从摄像头支持列表中选择最接近目标的分辨率
     * 避免请求不支持的分辨率导致崩溃
     *
     * 选档策略（用户要求：相同/接近分辨率时高帧率优先）：
     *   1. 先在“能达到 desiredFps（默认 60）的分辨率”里，选面积最接近目标的；
     *   2. 只有当没有任何分辨率支持 desiredFps 时，才在全体候选里选面积最接近的（此时可能只有 30fps）。
     * 这样避免了“选到最接近但只支持 30fps 的分辨率、而旁边有个能跑 60fps 的近似分辨率被忽略”的问题。
     */
    private fun findBestResolution(
        formats: List<Size>,
        targetWidth: Int,
        targetHeight: Int,
        aspectRatio: Double? = null,  // null=不限，16/9=16:9，4/3=4:3
        sizeMaxFps: Map<Size, Int> = emptyMap(),
        desiredFps: Int = 60
    ): Size {
        if (formats.isEmpty()) return Size(targetWidth, targetHeight)
        
        // 过滤比例（允许5%误差）
        val candidates = if (aspectRatio != null) {
            formats.filter {
                val ratio = it.width.toDouble() / it.height.toDouble()
                Math.abs(ratio - aspectRatio) < 0.05
            }.ifEmpty { formats }
        } else {
            formats
        }
        
        val targetArea = targetWidth * targetHeight
        val nearestByArea: (List<Size>) -> Size? = { list ->
            list.minByOrNull { Math.abs(it.width * it.height - targetArea) }
        }
        
        // 🔥 第一优先级：能跑 desiredFps 的分辨率里，选面积最接近目标的
        val highFpsCandidates = candidates.filter { (sizeMaxFps[it] ?: 30) >= desiredFps }
        nearestByArea(highFpsCandidates)?.let { return it }
        
        // 回退：没有任何分辨率支持 desiredFps，则在全体候选里选面积最接近的
        return nearestByArea(candidates) ?: Size(targetWidth, targetHeight)
    }
    
    // MARK: - 🔥 动态计算档位配置
    
    // 🔥 与iOS一致的4档“目标分辨率”（3个4:3 + 1个16:9）
    //    Android 分辨率与iOS不同，按“就近原则”选设备最接近的分辨率直接采集（不缩放，与iOS getCaptureResolutionForProfile一致）
    private val iosTargets = mapOf(
        LadderProfile.P4K to Triple(1920, 1440, false),      // 超高清 4:3
        LadderProfile.HIGH to Triple(1440, 1080, false),     // 超清   4:3
        LadderProfile.STANDARD to Triple(1024, 768, false),  // 高清   4:3
        LadderProfile.ULTRA to Triple(1280, 720, true)       // 超高帧 16:9
    )

    private fun calculateLadder(front: Boolean) {
        val formats = if (front) frontCameraFormats else backCameraFormats
        val maxFps = if (front) frontMaxFps else backMaxFps
        val sizeMaxFps = if (front) frontSizeMaxFps else backSizeMaxFps
        
        // 🔥 采集目标帧率：60fps 标准。每档实际采集帧率 = min(60, 该分辨率支持的最大fps, 整机maxFps)。
        //    发热优化：采集帧率不超过推流帧率上限(maxPushFps=60)。此前 ultra 档按设备能力采集到 240/120fps，
        //    但推流最高只有 60fps，多出的帧在 ISP/传感器/纹理管线里“空转”后被直接丢弃，是 Android 端主要发热来源之一。
        //    注：慢门/快门(cjfps)由 SENSOR_EXPOSURE_TIME 单独控制，不依赖高采集帧率。
        val desiredFps = 60
        
        // 每档独立按 iOS 目标分辨率就近选取设备实际采集分辨率（直接采集，scaleDown=1.0）
        // 🔥 选档时“60fps 优先”：优先在能跑 60fps 的分辨率里就近选，没有 60 才退回 30。
        fun nearest(profile: LadderProfile): Size {
            val (tw, th, is169) = iosTargets[profile]!!
            return findBestResolution(
                formats, tw, th,
                if (is169) 16.0 / 9.0 else 4.0 / 3.0,
                sizeMaxFps, desiredFps
            )
        }
        // 🔥 该分辨率实际可用采集帧率：min(60, 分辨率支持fps, 整机maxFps)
        fun fpsFor(size: Size): Int =
            minOf(desiredFps, sizeMaxFps[size] ?: maxFps, maxFps).coerceAtLeast(1)
        
        val p4kCap = nearest(LadderProfile.P4K)
        val highCap = nearest(LadderProfile.HIGH)
        val stdCap = nearest(LadderProfile.STANDARD)
        val ultraCap = nearest(LadderProfile.ULTRA)
        
        // 🔥 4档预设：width/height=就近采集分辨率，fps=该分辨率实际可用帧率(60优先)，scaleDown=1.0（直接采集不缩放）
        currentLadder = mapOf(
            LadderProfile.P4K to LadderPreset(
                width = p4kCap.width, height = p4kCap.height,
                fps = fpsFor(p4kCap), maxKbps = 7500, minKbps = 4500,
                maxPushFps = 60, scaleDown = 1.0, is16x9 = false
            ),
            LadderProfile.HIGH to LadderPreset(
                width = highCap.width, height = highCap.height,
                fps = fpsFor(highCap), maxKbps = 5500, minKbps = 3300,
                maxPushFps = 60, scaleDown = 1.0, is16x9 = false
            ),
            LadderProfile.STANDARD to LadderPreset(
                width = stdCap.width, height = stdCap.height,
                fps = fpsFor(stdCap), maxKbps = 4500, minKbps = 2700,
                maxPushFps = 60, scaleDown = 1.0, is16x9 = false
            ),
            LadderProfile.ULTRA to LadderPreset(
                width = ultraCap.width, height = ultraCap.height,
                fps = fpsFor(ultraCap), maxKbps = 5500, minKbps = 3300,
                maxPushFps = 60, scaleDown = 1.0, is16x9 = true
            )
        )
        
        // 默认高清档
        applyProfile(LadderProfile.STANDARD)
        
        val cameraType = if (front) "前置" else "后置"
        Log.d(TAG, "═══════════════════════════════════════════")
        Log.d(TAG, "📐 $cameraType 4档（iOS目标 → 设备就近采集[60fps优先]，整机最大FPS=$maxFps）：")
        currentLadder.forEach { (profile, preset) ->
            val (tw, th, is169) = iosTargets[profile]!!
            val ratio = if (is169) "16:9" else "4:3"
            val capMax = sizeMaxFps[Size(preset.width, preset.height)] ?: maxFps
            Log.d(TAG, "   ${profileName(profile)}[$ratio] iOS目标${tw}x${th} → 实采${preset.width}x${preset.height} @${preset.fps}fps(该分辨率上限${capMax}fps) → ${preset.minKbps}-${preset.maxKbps}kbps")
        }
        Log.d(TAG, "═══════════════════════════════════════════")
    }
    
    /**
     * 切换档位（更新采集参数）
     */
    fun applyProfile(profile: LadderProfile) {
        val preset = currentLadder[profile] ?: return
        currentProfile = profile
        
        // 🔥 采集分辨率 = 该档位就近选定的设备分辨率（每档独立，直接采集不缩放，与iOS一致）
        val captureChanged = (preset.width != currentWidth || preset.height != currentHeight)
        currentWidth = preset.width
        currentHeight = preset.height
        
        // 🌡️ 档位重置帧率也要受热控上限约束（否则热控降到20后一次切档/切摄像头就弹回30，
        //    相机满帧跑 → 降温失败升级 CRITICAL，见 2026-07-01 20:25 日志「目标30fps → [30,30]」）
        currentFps = minOf(preset.fps, if (isFrontCamera) frontMaxFps else backMaxFps, thermalFpsCap)
        currentBitrateKbps = preset.maxKbps
        currentMinBitrateKbps = preset.minKbps
        
        // 🔥 若正在采集且采集分辨率变化，就近切换采集格式（重建会话）
        if (isPreviewRunning && captureChanged) {
            try {
                videoCapturer?.changeCaptureFormat(currentWidth, currentHeight, currentFps)
                Log.d(TAG, "🔧 采集格式切换 → ${currentWidth}x${currentHeight}@${currentFps}fps")
                // 🔥 会话重建后重放硬件参数（曝光/对焦/变焦/快门/白平衡）
                scope.launch { delay(300); applyCameraParams() }
            } catch (e: Exception) {
                Log.e(TAG, "切换采集格式失败: ${e.message}")
            }
        }
        
        Log.d(TAG, "🎯 档位切换: ${profileName(profile)} → 采集${currentWidth}x${currentHeight}@${currentFps}fps, 码率${currentMinBitrateKbps}-${currentBitrateKbps}kbps, scale=${"%.2f".format(preset.scaleDown)}")
    }
    
    private fun profileName(profile: LadderProfile): String = when (profile) {
        LadderProfile.P4K -> "超高清(p4k)"
        LadderProfile.ULTRA -> "超高帧(ultra)"
        LadderProfile.HIGH -> "超清(high)"
        LadderProfile.STANDARD -> "高清(standard)"
    }
    
    // MARK: - 预览
    
    fun setupRenderer(renderer: SurfaceViewRenderer) {
        localRenderer = renderer
        val egl = eglBase ?: run {
            Log.w(TAG, "⚠️ eglBase未初始化，先执行initialize()")
            initialize()
            eglBase
        } ?: return
        renderer.init(egl.eglBaseContext, null)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        renderer.setMirror(isFrontCamera)
        renderer.setEnableHardwareScaler(true)
    }
    
    fun startPreview() {
        if (isPreviewRunning) return
        if (peerConnectionFactory == null) {
            Log.w(TAG, "⚠️ PeerConnectionFactory未初始化，先执行initialize()")
            initialize()
        }
        
        Log.d(TAG, "🎬 启动预览...")
        
        videoSource = peerConnectionFactory!!.createVideoSource(false)
        videoCapturer = createCameraCapturer(isFrontCamera)
        Log.d(TAG, "🎬 startPreview: capturer=${videoCapturer?.javaClass?.simpleName}, 目标采集=${currentWidth}x${currentHeight}@${currentFps}, front=$isFrontCamera")
        
        videoCapturer?.let { capturer ->
            val surfaceTextureHelper = SurfaceTextureHelper.create(
                "CaptureThread",
                eglBase!!.eglBaseContext
            )
            // ⭐ [meidui 诊断] 包一层 CapturerObserver 统计「相机实际吐帧率」(capFps)：
            //    capFps=15 且 encFps=15 → 相机侧降帧（低光AE/HAL）；capFps=30 且 encFps=15 → 帧在采集之后被丢（适配器/编码器）
            val realObserver = videoSource!!.capturerObserver
            val countingObserver = object : CapturerObserver {
                override fun onCapturerStarted(success: Boolean) = realObserver.onCapturerStarted(success)
                override fun onCapturerStopped() = realObserver.onCapturerStopped()
                override fun onFrameCaptured(frame: VideoFrame) {
                    capFrameCount++
                    realObserver.onFrameCaptured(frame)
                }
            }
            capturer.initialize(surfaceTextureHelper, context, countingObserver)
            capturer.startCapture(currentWidth, currentHeight, currentFps)
            
            localVideoTrack = peerConnectionFactory!!.createVideoTrack(VIDEO_TRACK_ID, videoSource)
            localVideoTrack?.setEnabled(true)
            
            localRenderer?.let { renderer ->
                localVideoTrack?.addSink(renderer)
            }
            
            isPreviewRunning = true
            Log.d(TAG, "✅ 预览已启动: ${currentWidth}x${currentHeight}@${currentFps}fps (${profileName(currentProfile)})")
            
            // 🔥 采集会话就绪后注入一次硬件参数：核心是钉死 AE 帧率区间 [fps,fps]，
            //    否则暗光下相机自动把帧率砍半（30→15，logcat/应用层完全无感知）
            scope.launch { delay(500); applyCameraParams() }
        }
    }
    
    fun stopPreview() {
        Log.d(TAG, "🔴 停止预览...")
        
        try { localVideoTrack?.removeSink(localRenderer!!) } catch (_: Exception) {}
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        videoCapturer = null
        
        try { localVideoTrack?.dispose() } catch (_: Exception) {}
        localVideoTrack = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        
        isPreviewRunning = false
    }
    
    // MARK: - 推流
    
    fun startPublish(serverIP: String, appName: String, key: String) {
        if (isPublishing) {
            println("jfh [推流] ⚠️ 已在推流中，跳过")
            return
        }
        
        srsIP = serverIP
        app = appName
        baseStreamKey = key
        // 🔥 与iOS一致：streamKey = 基础流名_时间戳（每次推流唯一，避免SRS缓存冲突）
        streamKey = "${key}_${System.currentTimeMillis() / 1000}"
        // 🔥 立即上报给WebSocket，PC据此拉流
        WebSocketManager.publishingStreamKey = streamKey
        
        println("jfh [推流] ═══════════════════════════════════════")
        println("jfh [推流] 🚀 开始推流")
        println("jfh [推流]    SRS地址: webrtc://$srsIP/$app/$streamKey")
        println("jfh [推流]    档位: ${profileName(currentProfile)}")
        println("jfh [推流]    采集: ${currentWidth}x${currentHeight}@${currentFps}fps")
        println("jfh [推流]    码率: ${currentBitrateKbps}kbps")
        println("jfh [推流]    预览状态: isPreviewRunning=$isPreviewRunning")
        println("jfh [推流] ═══════════════════════════════════════")
        onConnectionStateChanged?.invoke("连接中...")
        
        scope.launch {
            try {
                // Step 1: 确保预览已启动
                if (!isPreviewRunning) {
                    println("jfh [推流] Step1: 预览未启动，先启动预览...")
                    withContext(Dispatchers.Main) { startPreview() }
                    println("jfh [推流] Step1: ✅ 预览已启动")
                } else {
                    println("jfh [推流] Step1: ✅ 预览已就绪")
                }
                
                // Step 2: 创建PeerConnection
                println("jfh [推流] Step2: 创建PeerConnection...")
                createPeerConnection()
                println("jfh [推流] Step2: ✅ PeerConnection已创建")
                
                // Step 3: 添加视频轨道
                println("jfh [推流] Step3: 添加视频轨道... localVideoTrack=${localVideoTrack != null}")
                localVideoTrack?.let { track ->
                    videoSender = peerConnection?.addTrack(track, listOf(STREAM_ID))
                    println("jfh [推流] Step3: ✅ 视频轨道已添加, videoSender=${videoSender != null}")
                } ?: println("jfh [推流] Step3: ❌ localVideoTrack为空!")
                
                // Step 4: 设置编码参数
                println("jfh [推流] Step4: 设置编码参数...")
                setEncodingParameters()
                println("jfh [推流] Step4: ✅ CBR ${currentBitrateKbps}kbps, FPS=$currentFps")
                
                // Step 5: 创建Offer
                println("jfh [推流] Step5: 创建Offer...")
                val offer = createOffer()
                if (offer != null) {
                    println("jfh [推流] Step5: ✅ Offer已创建, SDP长度=${offer.description.length}")
                    peerConnection?.setLocalDescription(SdpObserverAdapter(), offer)
                    
                    // Step 6: 发送到SRS
                    println("jfh [推流] Step6: POST到SRS https://$srsIP/rtc/v1/publish/ ...")
                    val answer = postOfferToSRS(offer.description)
                    if (answer != null) {
                        println("jfh [推流] Step6: ✅ SRS返回Answer, 长度=${answer.length}")
                        
                        // Step 7: 设置Answer
                        println("jfh [推流] Step7: 设置RemoteDescription...")
                        val answerSdp = SessionDescription(SessionDescription.Type.ANSWER, answer)
                        peerConnection?.setRemoteDescription(SdpObserverAdapter(), answerSdp)
                        println("jfh [推流] Step7: ✅ RemoteDescription已设置")
                        
                        withContext(Dispatchers.Main) {
                            isPublishing = true
                            WebSocketManager.isPublishingFlag = 1
                            WebSocketManager.publishingStreamKey = streamKey
                            onConnectionStateChanged?.invoke("推流中")
                        }
                        
                        // Step 8: 启动统计定时器
                        // 🔥 2026-07-02 卡顿根因修复：不再启动周期关键帧定时器（原每 1s 强刷一个 IDR）。
                        //    libwebrtc 会自动响应观看端 RTCP PLI 出关键帧，SRS gop_cache 负责新进观众；
                        //    周期大 IDR + 运动突增 0.3s 连发会瞬间打满上行 → 后续帧攒批 → PC 端 mediaGap 尖峰（nh.txt 坐实）。
                        startStats()
                        
                        println("jfh [推流] ✅✅✅ 推流成功! ✅✅✅")
                    } else {
                        println("jfh [推流] Step6: ❌ SRS返回空Answer!")
                        withContext(Dispatchers.Main) {
                            onConnectionStateChanged?.invoke("连接失败")
                        }
                    }
                } else {
                    println("jfh [推流] Step5: ❌ 创建Offer失败!")
                }
            } catch (e: Exception) {
                println("jfh [推流] ❌ 推流异常: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onConnectionStateChanged?.invoke("推流失败: ${e.message}")
                }
            }
        }
    }
    
    fun stopPublish() {
        Log.d(TAG, "🔴 停止推流...")
        
        keyframeJob?.cancel()
        statsJob?.cancel()
        
        val key = streamKey
        peerConnection?.close()
        peerConnection = null
        videoSender = null
        
        isPublishing = false
        WebSocketManager.isPublishingFlag = 0
        onConnectionStateChanged?.invoke("未连接")
        
        // 🔥 与iOS SRSManager.stop() 一致：通知SRS删除该推流
        if (key.isNotEmpty() && srsIP.isNotEmpty()) {
            deleteStream(key)
        }
        
        Log.d(TAG, "✅ 推流已停止")
    }
    
    /**
     * 🔥 通知SRS删除推流（与iOS deleteStream一致）
     * POST http://{srsIP}:1985/rtc/v1/unpublish/
     */
    private fun deleteStream(key: String) {
        scope.launch {
            try {
                val apiUrl = "http://$srsIP:1985/rtc/v1/unpublish/"
                val body = mapOf(
                    "api" to apiUrl,
                    "streamurl" to "webrtc://$srsIP/$app/$key"
                )
                val request = Request.Builder()
                    .url(apiUrl)
                    .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    println("jfh [推流] 🗑️ deleteStream: HTTP${resp.code}")
                }
            } catch (e: Exception) {
                println("jfh [推流] 🗑️ deleteStream 失败(忽略): ${e.message}")
            }
        }
    }
    
    // MARK: - PeerConnection
    
    private fun createPeerConnection() {
        val config = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.miwifi.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.qq.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        ))
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        
        peerConnection = peerConnectionFactory?.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "📡 ICE状态: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> onConnectionStateChanged?.invoke("已连接")
                        PeerConnection.IceConnectionState.DISCONNECTED -> onConnectionStateChanged?.invoke("连接断开")
                        PeerConnection.IceConnectionState.FAILED -> onConnectionStateChanged?.invoke("连接失败")
                        else -> {}
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )
    }
    
    private suspend fun createOffer(): SessionDescription? = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (cont.isActive) cont.resume(sdp) {}
            }
            override fun onCreateFailure(error: String?) {
                if (cont.isActive) cont.resume(null) {}
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }
    
    // MARK: - SRS WHIP
    
    /**
     * 🔥 POST Offer到SRS（与iOS postOfferToSRS完全一致）
     * URL: http://{srsIP}:1985/rtc/v1/publish/
     * streamurl: webrtc://{srsIP}/{app}/{streamKey}?token=xxx&username=xxx
     */
    private suspend fun postOfferToSRS(offerSdp: String): String? = withContext(Dispatchers.IO) {
        try {
            // 🔥 与iOS一致：使用 http + 端口1985
            val apiUrl = "http://$srsIP:1985/rtc/v1/publish/"
            var streamUrl = "webrtc://$srsIP/$app/$streamKey"
            
            // 🔥 获取推流Token（与iOS一致：POST username + streamName）
            val tokenPrefs = appContext?.getSharedPreferences("token_prefs", android.content.Context.MODE_PRIVATE)
            val username = tokenPrefs?.getString("username", "") ?: ""
            val jwtToken = tokenPrefs?.getString("jwt_token", "") ?: ""
            println("jfh [推流] 🔑 读取username='$username', jwtToken长度=${jwtToken.length}")
            
            if (jwtToken.isNotEmpty()) {
                try {
                    val tokenResult = com.fz.yqlandroid.network.NetworkService.getStreamToken(jwtToken, streamKey, username)
                    tokenResult.fold(
                        onSuccess = { tokenResponse ->
                            if (tokenResponse.token.isNotEmpty()) {
                                streamUrl = "$streamUrl?token=${tokenResponse.token}&username=$username"
                                println("jfh [推流] 🔑 推流Token获取成功")
                            }
                        },
                        onFailure = {
                            println("jfh [推流] ⚠️ 获取推流Token失败: ${it.message}，使用无Token推流")
                        }
                    )
                } catch (e: Exception) {
                    println("jfh [推流] ⚠️ 获取推流Token异常: ${e.message}")
                }
            }
            
            val body = mapOf(
                "api" to apiUrl,
                "streamurl" to streamUrl,
                "sdp" to offerSdp
            )
            val json = gson.toJson(body)
            
            println("jfh [推流] 📤 SRS请求:")
            println("jfh [推流]    URL: $apiUrl")
            println("jfh [推流]    streamurl: $streamUrl")
            
            val request = Request.Builder()
                .url(apiUrl)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            
            println("jfh [推流] 📥 SRS响应: HTTP${response.code}, body长度=${responseBody?.length ?: 0}")
            
            if (response.isSuccessful && responseBody != null) {
                val result = gson.fromJson(responseBody, Map::class.java)
                val code = (result["code"] as? Double)?.toInt() ?: -1
                if (code == 0) {
                    println("jfh [推流] ✅ SRS返回code=0, Answer获取成功")
                    result["sdp"] as? String
                } else {
                    println("jfh [推流] ❌ SRS错误码: $code")
                    null
                }
            } else {
                println("jfh [推流] ❌ SRS HTTP失败: ${response.code}, body=$responseBody")
                null
            }
        } catch (e: Exception) {
            println("jfh [推流] ❌ SRS异常: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    // 🔥 保存appContext（用于读取SharedPreferences）
    private var appContext: android.content.Context? = null
    
    fun setContext(context: android.content.Context) {
        appContext = context.applicationContext
    }
    
    // MARK: - 编码参数
    
    private fun setEncodingParameters() {
        val sender = videoSender ?: return
        val params = sender.parameters
        if (params.encodings.isEmpty()) return
        
        // 🔥 与iOS一致：使用 min~max 码率区间（允许WebRTC向下自适应），并锁定分辨率靠降帧对抗拥塞
        // 🌡️ 叠加热控约束：码率乘缩放系数、帧率不超过热档位上限
        val thermalMaxKbps = maxOf(300, (currentBitrateKbps * thermalBitrateScale).toInt())
        params.encodings[0].maxBitrateBps = thermalMaxKbps * 1000
        params.encodings[0].minBitrateBps = minOf(currentMinBitrateKbps, thermalMaxKbps) * 1000
        params.encodings[0].maxFramerate = minOf(currentFps, currentLadder[currentProfile]?.maxPushFps ?: 60, thermalFpsCap)
        
        // scaleDown：从采集分辨率缩放到目标输出（与iOS一致）
        val preset = currentLadder[currentProfile]
        params.encodings[0].scaleResolutionDownBy = if (preset != null && preset.scaleDown > 1.0) preset.scaleDown else 1.0
        
        // 🔥 MAINTAIN_RESOLUTION：拥塞时降帧不降分辨率
        params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        sender.parameters = params
        
        Log.d(TAG, "🔒 码率区间: ${currentMinBitrateKbps}-${currentBitrateKbps}kbps, FPS≤${params.encodings[0].maxFramerate}, scale: ${"%.2f".format(preset?.scaleDown ?: 1.0)}")
    }
    
    fun setMaxBitrateKbps(kbps: Int) {
        currentBitrateKbps = kbps
        currentMinBitrateKbps = maxOf(100, (kbps * 0.6).toInt())  // 🔥 min≈60%max，允许向下自适应
        val sender = videoSender ?: return
        val params = sender.parameters
        if (params.encodings.isEmpty()) return
        params.encodings[0].maxBitrateBps = currentBitrateKbps * 1000
        params.encodings[0].minBitrateBps = currentMinBitrateKbps * 1000
        sender.parameters = params
    }
    
    // MARK: - 关键帧
    
    // 🔥 2026-07-02 卡顿根因修复：删除「平稳 1s 一个 IDR + 运动突增 0.1~0.5s 连发」的周期关键帧策略。
    //    该策略正是「每 5~15 秒卡 1~2 秒」的根因：大 IDR（P 帧 5~10 倍大）风暴瞬间打满上行
    //    → 发送端攒帧 → 批量到达（PC nh.txt 中 gap≈mediaGap 的 PRESENT GAP 尖峰）。
    //    关键帧改为纯按需：libwebrtc 自动响应观看端 RTCP PLI；PC WS 兜底走 applyRemoteConfig("request_keyframe")。
    @Volatile private var fastKeyframeUntilMs: Long = 0L   // 已停用（保留供 meidui 日志 fastKF 字段，恒 false）
    @Volatile private var lastKeyframeAtMs: Long = 0L      // 上次按需关键帧时间戳(ms)，用于 WS 请求节流

    /**
     * 🔥 编码器检测到运动突增：仅记日志观测频率，不再触发快速关键帧窗口（已证实为攒帧元凶）。
     */
    private fun onEncoderMotionSurge() {
        Log.d("meidui", "MOTION_SURGE（快速关键帧窗口已停用，仅观测）")
    }

    fun forceKeyframe() {
        val sender = videoSender ?: return
        val params = sender.parameters
        if (params.encodings.isEmpty()) return
        
        val currentMax = params.encodings[0].maxBitrateBps ?: (currentBitrateKbps * 1000)
        params.encodings[0].maxBitrateBps = currentMax + 1000
        sender.parameters = params
        
        scope.launch {
            delay(20)
            val s = videoSender ?: return@launch
            val p = s.parameters
            if (p.encodings.isNotEmpty()) {
                p.encodings[0].maxBitrateBps = currentMax
                s.parameters = p
            }
        }
    }
    
    // MARK: - 🔥 v2.1 自适应FPS（与iOS完全一致的算法）
    
    private var adaptiveFpsEnabled: Boolean = true
    private var adaptiveFps: Int = 30
    
    // 参数（与iOS一致）
    private val minAdaptiveFps: Int = 10          // 最低10fps
    private val lossRateDownThreshold: Double = 0.03  // 3秒均值>3%降帧
    private val lossRateUpThreshold: Double = 0.005   // 3秒均值<0.5%升帧
    private val rttDownThreshold: Int = 300       // RTT>300ms差
    private val rttUpThreshold: Int = 150         // RTT<150ms好
    private val downgradeHoldSec: Int = 3         // 连续3秒差→降
    private val upgradeHoldSec: Int = 8           // 连续8秒好→升
    private val cooldownMs: Long = 3000           // 冷却3秒
    private val fpsDownStep: Int = 5              // 降5fps
    private val fpsUpStep: Int = 2                // 升2fps
    
    // 状态
    private val lossRateHistory = mutableListOf<Double>()
    private var highLossCounter: Int = 0
    private var lowLossCounter: Int = 0
    private var lastFpsChangeTime: Long = 0
    private var lastAdaptiveProcessTime: Long = 0
    private var lastRemoteFpsTime: Long = 0
    private var lastNotifiedFps: Int = 0
    
    /**
     * 🔥 v2.1 自适应FPS处理（每秒执行一次）
     */
    private fun processAdaptiveFps(instantLossRate: Double, rttMs: Int) {
        val now = System.currentTimeMillis()
        
        // 每秒只执行一次
        if (now - lastAdaptiveProcessTime < 900) return
        lastAdaptiveProcessTime = now
        
        // 后端指令生效中，暂停
        if (now - lastRemoteFpsTime < 1000) return
        
        // 冷却期检查
        if (now - lastFpsChangeTime < cooldownMs) return
        
        // 3秒移动平均丢包率
        lossRateHistory.add(instantLossRate)
        if (lossRateHistory.size > 3) lossRateHistory.removeAt(0)
        val avgLoss = lossRateHistory.average()
        
        // 🌡️ 自适应升帧上限同时受热控约束，避免降温前又升回高帧
        val maxFps = minOf(currentLadder[currentProfile]?.maxPushFps ?: 60, thermalFpsCap)
        // 上限被收紧（热控/切档）时先静默对齐基准：编码器帧率已由收紧方写入，
        // 这里只同步 adaptiveFps，防止升帧分支打出「30→20fps」的假升帧
        if (adaptiveFps > maxFps) adaptiveFps = maxFps
        
        // 网络状态判断（RTT + 丢包率，不用码率）
        val isRttBad = rttMs > rttDownThreshold && rttMs > 0
        val isRttGood = rttMs in 1 until rttUpThreshold
        val isLossBad = avgLoss > lossRateDownThreshold
        val isLossGood = avgLoss < lossRateUpThreshold
        
        val isNetworkBad = isRttBad || isLossBad
        val isNetworkGood = isRttGood && isLossGood
        
        val status = if (isNetworkBad) "🔴差" else if (isNetworkGood) "🟢好" else "🟡中"
        Log.d(TAG, "📊 [自适应] fps=$adaptiveFps/$maxFps RTT=${rttMs}ms 丢包=${String.format("%.1f", avgLoss * 100)}% $status ↓$highLossCounter/$downgradeHoldSec ↑$lowLossCounter/$upgradeHoldSec")
        
        var fpsChanged = false
        val oldFps = adaptiveFps
        
        if (isNetworkBad) {
            highLossCounter++
            lowLossCounter = 0
            if (highLossCounter >= downgradeHoldSec) {
                val newFps = maxOf(minAdaptiveFps, adaptiveFps - fpsDownStep)
                if (newFps != adaptiveFps) {
                    adaptiveFps = newFps
                    fpsChanged = true
                    lastFpsChangeTime = now
                    Log.d(TAG, "⬇️ [降帧] $oldFps→${adaptiveFps}fps (RTT=${rttMs}ms 丢包=${String.format("%.1f", avgLoss * 100)}%)")
                    Log.d("meidui", "⚠️ fps修改源=自适应降帧 $oldFps→${adaptiveFps}fps")
                }
                highLossCounter = 0
            }
        } else if (isNetworkGood) {
            lowLossCounter++
            highLossCounter = 0
            if (lowLossCounter >= upgradeHoldSec) {
                val newFps = minOf(maxFps, adaptiveFps + fpsUpStep)
                if (newFps != adaptiveFps) {
                    adaptiveFps = newFps
                    fpsChanged = true
                    lastFpsChangeTime = now
                    Log.d(TAG, "⬆️ [升帧] $oldFps→${adaptiveFps}fps (上限${maxFps}fps, RTT=${rttMs}ms)")
                    Log.d("meidui", "⚠️ fps修改源=自适应升帧 $oldFps→${adaptiveFps}fps")
                }
                lowLossCounter = 0
            }
        } else {
            highLossCounter = maxOf(0, highLossCounter - 1)
            lowLossCounter = maxOf(0, lowLossCounter - 1)
        }
        
        if (fpsChanged) {
            // 更新编码参数
            val sender = videoSender ?: return
            val params = sender.parameters
            if (params.encodings.isNotEmpty()) {
                params.encodings[0].maxFramerate = adaptiveFps
                sender.parameters = params
            }
            currentFps = adaptiveFps
            
            // 通知PC端
            if (adaptiveFps != lastNotifiedFps) {
                lastNotifiedFps = adaptiveFps
                WebSocketManager.instance.sendFpsUpdate(adaptiveFps)
            }
        }
    }
    
    // MARK: - 统计
    
    private var lastBytesSent: Long = 0
    private var lastPacketsSent: Long = 0
    private var lastPacketsLost: Long = 0
    private var lastStatsTime: Long = 0
    // ⭐ [meidui 诊断] 发送侧攒帧排查：编码/发送帧增量 + 上次日志时刻（每秒打一次）
    private var lastFramesEncoded: Long = 0
    private var lastFramesSent: Long = 0
    private var lastMeiduiLogMs: Long = 0
    private var lastNackCount: Long = 0
    // ⭐ [meidui 诊断] 相机实际吐帧计数（CountingObserver 在采集线程递增，统计线程读增量）
    @Volatile private var capFrameCount: Long = 0
    private var lastCapFrameCount: Long = 0
    
    private fun startStats() {
        statsJob?.cancel()
        lastBytesSent = 0; lastPacketsSent = 0; lastPacketsLost = 0; lastStatsTime = 0
        lastFramesEncoded = 0; lastFramesSent = 0; lastMeiduiLogMs = 0; lastNackCount = 0
        lastCapFrameCount = capFrameCount
        
        statsJob = scope.launch {
            while (isActive) {
                delay(200) // 200ms采集一次（与iOS一致），自适应逻辑内部每秒执行一次
                peerConnection?.getStats { report ->
                    var bytesSent: Long = 0
                    var fps = 0
                    var packetsSent: Long = 0
                    var packetsLost: Long = 0
                    var roundTripTime: Double = 0.0
                    // ⭐ [meidui 诊断] 发送侧攒帧关键字段
                    var framesEncoded: Long = 0     // 累计编码帧数
                    var framesSent: Long = 0        // 累计发送帧数
                    var nackCount: Long = 0         // 收到的 NACK（对端要求重传）次数
                    var qualityLimit = "-"          // 质量受限原因：none/bandwidth/cpu（=WebRTC 为什么降质）
                    var totalPacketSendDelay = 0.0  // 累计发包排队延迟（秒）——攒帧时会飙升
                    
                    report.statsMap.values.forEach { stats ->
                        if (stats.type == "outbound-rtp") {
                            (stats.members["bytesSent"] as? Number)?.let { bytesSent = it.toLong() }
                            (stats.members["framesPerSecond"] as? Number)?.let { fps = it.toInt() }
                            (stats.members["packetsSent"] as? Number)?.let { packetsSent = it.toLong() }
                            (stats.members["framesEncoded"] as? Number)?.let { framesEncoded = it.toLong() }
                            (stats.members["framesSent"] as? Number)?.let { framesSent = it.toLong() }
                            (stats.members["nackCount"] as? Number)?.let { nackCount = it.toLong() }
                            (stats.members["totalPacketSendDelay"] as? Number)?.let { totalPacketSendDelay = it.toDouble() }
                            (stats.members["qualityLimitationReason"] as? String)?.let { qualityLimit = it }
                        }
                        if (stats.type == "remote-inbound-rtp") {
                            (stats.members["packetsLost"] as? Number)?.let { packetsLost = it.toLong() }
                            (stats.members["roundTripTime"] as? Number)?.let { roundTripTime = it.toDouble() }
                        }
                    }
                    
                    val rttMs = (roundTripTime * 1000).toInt()
                    
                    // 计算瞬时丢包率
                    var instantLoss = 0.0
                    if (lastStatsTime > 0) {
                        val sentDelta = packetsSent - lastPacketsSent
                        val lostDelta = packetsLost - lastPacketsLost
                        if (sentDelta > 0) {
                            instantLoss = lostDelta.toDouble() / (sentDelta + lostDelta).toDouble()
                        }
                        
                        // 计算码率
                        val bytesDelta = bytesSent - lastBytesSent
                        val timeDelta = (System.currentTimeMillis() - lastStatsTime).toDouble() / 1000.0
                        if (timeDelta > 0) {
                            WebSocketManager.publishingKbps = ((bytesDelta * 8) / (timeDelta * 1000)).toInt()
                        }
                    }
                    
                    // ⭐ [meidui 诊断] 每秒打一行发送侧节奏：encFps=每秒编码帧、sentFps=每秒发送帧。
                    //   若 sentFps 周期性掉到 0 再暴涨 = 发送端攒帧批量发（坐实卡顿源）；
                    //   sendDelay 飙升 + qLimit=bandwidth = 上行带宽不足；nack 增量高 = 对端在要重传。
                    run {
                        val nowMs = System.currentTimeMillis()
                        if (lastMeiduiLogMs > 0 && nowMs - lastMeiduiLogMs >= 1000) {
                            val dt = (nowMs - lastMeiduiLogMs).toDouble() / 1000.0
                            val encFps = if (dt > 0) ((framesEncoded - lastFramesEncoded) / dt).toInt() else 0
                            val sentFps = if (dt > 0) ((framesSent - lastFramesSent) / dt).toInt() else 0
                            // ⭐ capFps=相机实际吐帧率；encMaxFps=编码器当前帧率上限（谁把它改成15一眼可见）
                            val capNow = capFrameCount
                            val capFps = if (dt > 0) ((capNow - lastCapFrameCount) / dt).toInt() else 0
                            lastCapFrameCount = capNow
                            val encMaxFps = try {
                                videoSender?.parameters?.encodings?.firstOrNull()?.maxFramerate ?: -1
                            } catch (_: Exception) { -1 }
                            val nackDelta = nackCount - lastNackCount
                            val kbps = WebSocketManager.publishingKbps
                            Log.d("meidui", "capFps=$capFps encFps=$encFps sentFps=$sentFps encMaxFps=$encMaxFps " +
                                    "kbps=$kbps fpsStat=$fps " +
                                    "sendDelay=${"%.2f".format(totalPacketSendDelay)}s qLimit=$qualityLimit " +
                                    "nack+=$nackDelta rtt=${rttMs}ms loss=${"%.2f".format(instantLoss * 100)}% " +
                                    "fastKF=${System.currentTimeMillis() < fastKeyframeUntilMs}")
                            lastFramesEncoded = framesEncoded
                            lastFramesSent = framesSent
                            lastNackCount = nackCount
                            lastMeiduiLogMs = nowMs
                        } else if (lastMeiduiLogMs == 0L) {
                            lastFramesEncoded = framesEncoded
                            lastFramesSent = framesSent
                            lastNackCount = nackCount
                            lastMeiduiLogMs = nowMs
                        }
                    }
                    
                    lastBytesSent = bytesSent
                    lastPacketsSent = packetsSent
                    lastPacketsLost = packetsLost
                    lastStatsTime = System.currentTimeMillis()
                    
                    // 更新WebSocket状态
                    WebSocketManager.publishingFps = fps
                    WebSocketManager.publishingSendFps = fps
                    WebSocketManager.networkQuality = when {
                        instantLoss <= 0.01 && rttMs in 1..100 -> "excellent"
                        instantLoss <= 0.03 && rttMs in 1..200 -> "good"
                        instantLoss <= 0.05 && rttMs in 1..400 -> "fair"
                        else -> "poor"
                    }
                    WebSocketManager.packetLoss = instantLoss
                    WebSocketManager.rtt = rttMs
                    
                    onStatsUpdate?.invoke(WebSocketManager.publishingKbps, fps, rttMs)
                    
                    // 🔥 自适应FPS
                    if (adaptiveFpsEnabled) {
                        processAdaptiveFps(instantLoss, rttMs)
                    }
                }
            }
        }
    }
    
    // MARK: - 摄像头切换
    
    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                isFrontCamera = isFront
                localRenderer?.setMirror(isFront)
                
                // 🔥 切换后重新计算档位（前后置能力不同）
                calculateLadder(isFront)
                
                Log.d(TAG, "🔄 切换到${if (isFront) "前置" else "后置"}摄像头")
                
                scope.launch {
                    // 🔥 原生采集器切换后，反射注入的曝光/对焦/变焦/快门/白平衡需重放（新会话）
                    delay(300); applyCameraParams()
                    delay(100); forceKeyframe()
                    delay(100); forceKeyframe()
                }
            }
            
            override fun onCameraSwitchError(error: String?) {
                Log.e(TAG, "❌ 切换摄像头失败: $error")
            }
        })
    }
    
    // MARK: - 辅助方法
    
    private fun createCameraCapturer(useFront: Boolean): CameraVideoCapturer {
        // 🔥 使用 WebRTC 原生 Camera2 采集器（低发热，走 WebRTC 优化纹理管线）。
        //    曝光/对焦/变焦/快门/白平衡改由 Camera2ParamApplier 反射原生 session 按需注入（见 docs 十五）。
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        // 选择目标朝向的摄像头；找不到则回退第一个
        val target = names.firstOrNull { name ->
            if (useFront) enumerator.isFrontFacing(name) else enumerator.isBackFacing(name)
        } ?: names.firstOrNull()
        ?: throw IllegalStateException("找不到可用摄像头")
        Log.d(TAG, "🎥 原生采集器: camera=$target front=$useFront")
        return enumerator.createCapturer(target, null)
    }
    
    // MARK: - 🔥 后端配置下发处理（与iOS applyThinRemoteConfig一致）
    
    /**
     * 处理后端STOMP下发的配置
     * ptype: type/direction/zoom/fps/cjfps/bitrate/focus
     */
    fun applyRemoteConfig(config: Map<String, Any>) {
        val ptype = config["ptype"] as? String ?: ""
        Log.d(TAG, "📋 [后端配置] ptype=$ptype, config=$config")
        
        when (ptype) {
            // 档位切换
            "type" -> {
                val type = config["type"] as? String ?: ""
                val profile = when (type.lowercase()) {
                    "p4k", "4k" -> LadderProfile.P4K
                    "ultra" -> LadderProfile.ULTRA
                    "high" -> LadderProfile.HIGH
                    else -> LadderProfile.STANDARD
                }
                if (currentProfile != profile) {
                    applyProfile(profile)
                    // 切换档位后需要更新编码参数
                    setEncodingParameters()
                    forceKeyframe()
                    Log.d(TAG, "✅ 档位切换: $type → ${profileName(profile)}")
                }
                // 同时应用FPS（如果有）
                (config["fps"] as? Number)?.let { f ->
                    setTargetFps(f.toInt())
                }
            }
            
            // 前后摄像头切换: "1"=前置, "-1"=后置
            "direction" -> {
                val direction = config["direction"] as? String
                    ?: (config["direction"] as? Number)?.toString()
                    ?: ""
                val wantFront = (direction == "1")
                if (wantFront != isFrontCamera) {
                    switchCamera()
                    Log.d(TAG, "✅ 摄像头切换: ${if (wantFront) "前置" else "后置"}")
                }
            }
            
            // 变焦: 1.0~max
            "zoom" -> {
                val zoom = (config["zoom"] as? Number)?.toFloat() ?: 1.0f
                setZoom(zoom)
            }
            
            // 推送FPS
            "fps" -> {
                (config["fps"] as? Number)?.let { f ->
                    setTargetFps(f.toInt())
                }
            }
            
            // 快门速度（采集帧率）: 60~600
            "cjfps" -> {
                (config["cjfps"] as? Number)?.let { cj ->
                    setShutterSpeed(cj.toInt())
                    Log.d(TAG, "📸 [快门] cjfps=${cj.toInt()} → 1/${cj.toInt()}s")
                }
            }
            
            // 码率/清晰度（百分比 0~100）
            "bitrate" -> {
                (config["bitrate"] as? Number)?.let { pct ->
                    setQualityPercentage(pct.toInt())
                }
            }
            
            // 对焦距离: 0.0~1.0
            "focus" -> {
                (config["focus"] as? Number)?.let { f ->
                    setFocus(f.toFloat())
                }
            }
            
            // 🔥 曝光/亮度（与iOS test_brightness/exposure/brightness一致）: EV 补偿
            //    后端/UI 可能以 "brightness" 下发亮度调节，这里与曝光统一走 AE 补偿。
            "exposure", "test_brightness", "brightness" -> {
                val ev = (config["exposure"] as? Number)
                    ?: (config["testBrightness"] as? Number)
                    ?: (config["brightness"] as? Number)
                if (ev != null) setExposure(ev.toFloat())
                else Log.w(TAG, "⚠️ ptype=$ptype 缺少曝光/亮度值，忽略")
            }
            
            // 🔥 白平衡（与iOS white_balance/applyWhiteBalance一致）
            "white_balance" -> {
                val wb = (config["testWhiteBalance"] as? Number)
                    ?: (config["white_balance"] as? Number)
                    ?: (config["wb"] as? Number)
                setWhiteBalance(wb?.toInt())   // 有值=手动色温，无值=锁定
            }
            "applyWhiteBalance" -> {
                val wb = (config["testWhiteBalance"] as? Number)
                setWhiteBalance(wb?.toInt())   // 无值=锁定当前白平衡
            }
            
            // 🔥 观看端(PC)兜底关键帧请求（CONFIG_UPDATE ptype=request_keyframe，与 iOS P0-1 一致）
            //    这是周期 IDR 删除后的唯一应用层补帧入口，1s 节流防风暴。
            "request_keyframe" -> {
                val now = System.currentTimeMillis()
                if (now - lastKeyframeAtMs >= REQUEST_KEYFRAME_MIN_INTERVAL_MS) {
                    lastKeyframeAtMs = now
                    forceKeyframe()
                    Log.d(TAG, "🔑 [按需关键帧] 响应观看端 request_keyframe")
                } else {
                    Log.d(TAG, "⏭️ [按需关键帧] 距上次不足 ${REQUEST_KEYFRAME_MIN_INTERVAL_MS}ms，节流跳过")
                }
            }
            
            else -> Log.w(TAG, "⚠️ 未知 ptype=$ptype，忽略")
        }
    }

    /**
     * 🔥 启动时一次性应用全部初始配置（对标 iOS applyThinRemoteConfigInit）
     *
     * 修复：此前启动只应用了 type/direction，导致 cjfps(快门)/zoom/focus/brightness/bitrate/fps
     * 这些“滤镜/图像参数”在启动时没有挂上。现在这里逐项下发到硬件与编码器。
     *
     * 需在 startPreview() 之后调用（原生 session 就绪后，Camera2ParamApplier 才能反射注入）。
     */
    fun applyInitialConfig(config: ThinRemoteConfig) {
        Log.d(TAG, "📋 [初始配置] 应用全部初始参数: type=${config.type}, dir=${config.direction}, zoom=${config.zoom}, fps=${config.fps}, cjfps=${config.cjfps}, bitrate=${config.bitrate}, focus=${config.focus}, brightness=${config.brightness}")

        // 1) 档位（会更新采集分辨率/编码参数）
        applyRemoteConfig(mapOf("ptype" to "type", "type" to config.type))

        // 2) 摄像头方向
        if (config.direction == "1" && !isFrontCamera) {
            applyRemoteConfig(mapOf("ptype" to "direction", "direction" to "1"))
        }

        // 3) 变焦
        applyRemoteConfig(mapOf("ptype" to "zoom", "zoom" to config.zoom))

        // 4) 快门(cjfps) —— 用户反馈“启动时没挂上”，这里补齐
        config.cjfps?.let { applyRemoteConfig(mapOf("ptype" to "cjfps", "cjfps" to it)) }

        // 5) 对焦
        config.focus?.let { applyRemoteConfig(mapOf("ptype" to "focus", "focus" to it)) }

        // 6) 亮度/曝光
        config.brightness?.let { applyRemoteConfig(mapOf("ptype" to "brightness", "brightness" to it)) }

        // 7) 码率/清晰度百分比
        config.bitrate?.let { applyRemoteConfig(mapOf("ptype" to "bitrate", "bitrate" to it)) }

        // 8) 推送FPS
        config.fps?.let { applyRemoteConfig(mapOf("ptype" to "fps", "fps" to it)) }
    }

    /**
     * 处理特殊消息类型
     */
    fun handleSpecialMessage(type: String, messageDict: Map<String, Any>) {
        when (type) {
            "RESET_PUBLISH" -> {
                Log.d(TAG, "🔄 收到RESET_PUBLISH，重置推流")
                scope.launch {
                    withContext(Dispatchers.Main) { stopPublish() }
                    delay(500)
                    withContext(Dispatchers.Main) {
                        if (srsIP.isNotEmpty() && baseStreamKey.isNotEmpty()) {
                            startPublish(srsIP, app, baseStreamKey)  // 🔥 用基础流名，内部重新生成时间戳
                        }
                    }
                }
            }
            "shuimian" -> {
                Log.d(TAG, "💤 收到睡眠指令，停止采集")
                scope.launch {
                    withContext(Dispatchers.Main) {
                        stopPublish()
                        stopPreview()
                    }
                }
            }
            "gongzuo" -> {
                Log.d(TAG, "☀️ 收到唤醒指令，重新推流")
                scope.launch {
                    withContext(Dispatchers.Main) { startPreview() }
                    delay(500)
                    withContext(Dispatchers.Main) {
                        if (srsIP.isNotEmpty() && baseStreamKey.isNotEmpty()) {
                            startPublish(srsIP, app, baseStreamKey)  // 🔥 用基础流名，内部重新生成时间戳
                        }
                    }
                }
            }
        }
    }
    
    // MARK: - 🔥 相机控制方法（原生采集器 + Camera2ParamApplier 反射按需注入，低发热）

    // 硬件控制缓存状态（切档/切摄像头后可重放）
    private var _currentZoom: Float = 1.0f
    val currentZoom: Float get() = _currentZoom
    private var _currentFocus: Float = 0.5f
    val currentFocus: Float get() = _currentFocus
    private var _currentShutterSpeed: Int = 240
    val currentShutterSpeed: Int get() = _currentShutterSpeed
    private var _shutterEnabled: Boolean = false     // 是否启用手动快门(cjfps)；false=自动曝光
    private var _currentExposure: Float = 0f
    val currentExposure: Float get() = _currentExposure
    private var _currentWhiteBalance: Int = 50
    val currentWhiteBalance: Int get() = _currentWhiteBalance
    private var _whiteBalanceLocked: Boolean = false
    private var _whiteBalanceManual: Boolean = false // 是否手动色温

    /**
     * 🔥 把当前缓存的全部硬件参数一次性反射注入原生 session（不常驻、不每帧）。
     * 供各 setter 与切档/切摄像头后重放调用。
     */
    fun applyCameraParams() {
        val params = Camera2ParamApplier.Params(
            exposureEv = if (_shutterEnabled) null else _currentExposure,
            focus = _currentFocus,
            zoom = _currentZoom,
            shutterCjfps = if (_shutterEnabled) _currentShutterSpeed else null,
            manualIso = null,
            whiteBalanceSlider = if (_whiteBalanceManual) _currentWhiteBalance else null,
            whiteBalanceLocked = _whiteBalanceLocked,
            // 🔥 钉死 AE 帧率区间，防低光时相机自动 30→15（iOS 无此坑，Android Camera2 经典问题）
            targetFps = currentFps
        )
        Camera2ParamApplier.apply(videoCapturer, params)
    }

    /** 设置变焦 (1.0 ~ maxZoom) */
    fun setZoom(zoom: Float) {
        _currentZoom = zoom.coerceIn(1.0f, 10.0f)
        applyCameraParams()
        Log.d(TAG, "🔍 Zoom设置: ${_currentZoom}x")
    }

    /** 设置对焦距离 (0.0 ~ 1.0)；0.5=连续自动对焦，其余=手动 */
    fun setFocus(distance: Float) {
        _currentFocus = distance.coerceIn(0f, 1f)
        applyCameraParams()
        Log.d(TAG, "🎯 对焦距离: $_currentFocus")
    }

    /**
     * 设置快门速度（cjfps 60~600，保留手动快门能力）。
     * 值越大 = 快门越快 = 曝光越短。启用后走 AE OFF + SENSOR_EXPOSURE_TIME=1/cjfps。
     */
    fun setShutterSpeed(cjfps: Int) {
        _currentShutterSpeed = cjfps.coerceIn(60, 600)
        _shutterEnabled = true
        applyCameraParams()
        Log.d(TAG, "📸 快门: 1/${_currentShutterSpeed}s (手动快门)")
    }

    /**
     * 🔥 曝光（自动AE下的曝光补偿EV）。设置曝光=切回自动曝光模式（关闭手动快门）。
     */
    fun setExposure(ev: Float) {
        _currentExposure = ev
        _shutterEnabled = false   // 曝光补偿仅在自动AE下有效
        applyCameraParams()
        Log.d(TAG, "☀️ 曝光补偿: EV=$ev (自动曝光)")
    }

    /**
     * 🔥 白平衡：slider=null → 锁定当前白平衡；否则 0~100 手动色温(0冷100暖)。
     */
    fun setWhiteBalance(slider: Int?) {
        if (slider == null) {
            _whiteBalanceLocked = true
            _whiteBalanceManual = false
            Log.d(TAG, "⚪️ 白平衡: 锁定当前")
        } else {
            _currentWhiteBalance = slider.coerceIn(0, 100)
            _whiteBalanceManual = true
            _whiteBalanceLocked = true
            Log.d(TAG, "⚪️ 白平衡: 手动色温 $_currentWhiteBalance/100")
        }
        applyCameraParams()
    }
    
    /**
     * 设置目标推送FPS
     * 后端下发的FPS需要除以4（与iOS一致）
     */
    fun setTargetFps(backendFps: Int) {
        val pushFps = backendFps / 4
        val maxFps = currentLadder[currentProfile]?.maxPushFps ?: 60
        // 🌡️ 热控上限一并生效
        val targetFps = minOf(pushFps, maxFps, thermalFpsCap).coerceAtLeast(1)
        val fpsChanged = (targetFps != currentFps)
        currentFps = targetFps
        // 🔥 后端显式设帧率 = 新基准：同步 adaptiveFps 并盖时间戳。
        //    此前 lastRemoteFpsTime 只读从未写入 →「后端指令生效中暂停自适应」的门是死代码，
        //    自适应下一秒就用旧基准把后端指令顶掉
        adaptiveFps = targetFps
        lastRemoteFpsTime = System.currentTimeMillis()

        // 1) 更新编码器目标帧率（videoSender 可能在预览未推流时为 null，此时不 return，
        //    仍继续更新采集侧，保证“fps 不反应”问题在预览阶段也能生效）
        val sender = videoSender
        if (sender != null) {
            val params = sender.parameters
            if (params.encodings.isNotEmpty()) {
                params.encodings[0].maxFramerate = targetFps
                sender.parameters = params
            }
        } else {
            Log.d(TAG, "🎬 [FPS] videoSender 尚未就绪(预览中)，仅更新采集帧率")
        }

        // 2) 🔥 同步采集侧帧率（此前只改编码器 maxFramerate，采集帧率不变 → 表现为“fps 不反应”）。
        //    仅在帧率确有变化时下发 changeCaptureFormat（其内部会重开会话），避免相同值反复重开相机造成闪烁。
        if (isPreviewRunning && fpsChanged) {
            try {
                videoCapturer?.changeCaptureFormat(currentWidth, currentHeight, targetFps)
                // 🔥 会话重建后重放硬件参数
                scope.launch { delay(300); applyCameraParams() }
            } catch (e: Exception) {
                Log.e(TAG, "同步采集帧率失败: ${e.message}")
            }
        }

        Log.d(TAG, "🎬 推送FPS: 后端${backendFps}/4=${pushFps} → 实际${targetFps}fps(采集+编码已同步, changed=$fpsChanged)")
        Log.d("meidui", "⚠️ fps修改源=后端set_fps 后端${backendFps}→实际${targetFps}fps")
    }
    
    /**
     * 设置码率/清晰度百分比 (0~100)
     * 与iOS setQualityPercentage 一致
     */
    fun setQualityPercentage(percentage: Int) {
        val pct = percentage.coerceIn(10, 100)
        val maxKbps = currentLadder[currentProfile]?.maxKbps ?: 2000
        val targetKbps = (maxKbps * pct / 100).coerceAtLeast(200)
        
        setMaxBitrateKbps(targetKbps)
        _currentQualityPercent = pct
        Log.d(TAG, "🎨 码率: ${pct}% → ${targetKbps}kbps")
    }
    
    private var _currentQualityPercent: Int = 100
    val currentQualityPercent: Int get() = _currentQualityPercent
    
    // MARK: - 清理
    
    fun destroy() {
        scope.cancel()
        try { thermalManager.stop() } catch (_: Exception) {}
        stopPublish()
        stopPreview()
        
        try { localRenderer?.release() } catch (_: Exception) {}
        try { eglBase?.release() } catch (_: Exception) {}
        try { peerConnectionFactory?.dispose() } catch (_: Exception) {}
        
        Log.d(TAG, "🗑️ WebRTC资源已释放")
    }
    
    private class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { Log.e(TAG, "SDP创建失败: $error") }
        override fun onSetFailure(error: String?) { Log.e(TAG, "SDP设置失败: $error") }
    }
}
