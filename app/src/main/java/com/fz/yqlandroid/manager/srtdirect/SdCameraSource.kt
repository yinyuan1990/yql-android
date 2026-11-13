package com.fz.yqlandroid.manager.srtdirect

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.fz.yqlandroid.manager.Camera2ParamApplier

/**
 * §134 Camera2 采集（拷自 test-android `CameraSource`，照 silu `Camera2MediaStreamer.createSession`）：
 * 录像模板，会话输出 = **编码器 Surface（必选）+ SurfaceView 预览（界面可见时）/ 后台替身（接过预览后不可见时）**，
 * 摄像头直写编码器，不经 GPU 重绘/CPU 拷贝。不接预览则整个推流期间仅编码器（silu CameraLiveService 同款）。
 *
 * 与 test-android 的差别：前后摄可选；PC 下发的硬件参数（AE 帧率区间=推送帧率、快门/ISO、对焦、变焦含 0.5x、
 * 白平衡、50Hz 防闪、关防抖）走 yql-android 既有算法 [Camera2ParamApplier.applyToBuilder]，直接写自己的 CaptureRequest。
 * 输出方向 = 传感器原生（横向，与 3840 版一致），不旋转。
 */
internal class SdCameraSource(
    private val context: Context,
    private val listener: Listener,
) : PreviewRegistry.Listener {

    interface Listener {
        /** 摄像头出错/被断开（已关闭设备），引擎负责延迟重开 */
        fun onCameraError(msg: String)
        /** 会话首帧到达（采集已恢复） */
        fun onCameraFirstFrame() {}
    }

    companion object {
        private const val PREFS = "srt_direct_prefs"
        private const val KEY_CROP_CAP = "main_crop_cap_"
        /** 绑主摄把相机 HAL 弄崩过的摄像头 id（存本机，以后这台不再绑主摄） */
        private const val KEY_LOCK_CRASH = "main_lock_crash_"

        private const val ZOOM_UNBOUND = 0
        private const val ZOOM_BOUND_1X = 1
        private const val ZOOM_PHYS_CROP = 2
        private const val ZOOM_BOUND_LOGICAL = 3

        /** 最近一次「📷 逻辑多摄」行：相机在推流日志开始前就打开了，推流开始时由 [SrtLogReporter] 补打 */
        @Volatile var lensDesc: String? = null

        // 下面三个都要问相机服务：相机服务挂掉 / 正在重启时（HAL 崩了之后几秒）会抛异常，
        // 调用方在引擎 / 相机线程上，原来没人接 = 闪退。这里统一接住，按「查不到」处理。

        /** 指定朝向的第一个摄像头 id（后置一般是逻辑主摄，ZOOM_RATIO 可 <1）；没有就用第一个 */
        fun cameraId(ctx: Context, front: Boolean): String? = try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val ids = cm.cameraIdList
            ids.firstOrNull {
                try { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want } catch (_: Throwable) { false }
            } ?: ids.firstOrNull()
        } catch (t: Throwable) {
            SdLog.w("查摄像头列表失败（相机服务可能在重启）: ${t.message}")
            null
        }

        /** 编码器可用的输出尺寸里与目标最接近的（优先同宽高比） */
        fun chooseSize(ctx: Context, cameraId: String, w: Int, h: Int): Size = try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val map = cm.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes: Array<Size> = map?.getOutputSizes(MediaCodec::class.java) ?: emptyArray()
            val ratio = w.toFloat() / h
            if (sizes.isEmpty()) Size(w, h)
            else sizes.firstOrNull { it.width == w && it.height == h } ?: sizes.sortedWith(compareBy<Size>(
                { Math.abs(it.width.toFloat() / it.height - ratio) > 0.01f },
                { Math.abs(it.width * it.height - w * h) },
            )).first()
        } catch (t: Throwable) {
            SdLog.w("查摄像头 $cameraId 输出尺寸失败（相机服务可能在重启），按 ${w}x$h: ${t.message}")
            Size(w, h)
        }

        /**
         * 屏幕预览尺寸：同宽高比、不超过 1080p 的最大 SurfaceHolder 尺寸。
         * Camera2 规定 SurfaceView 预览最大 1080p，推 1440p/4K 时预览若用推流尺寸会话会配置失败。
         */
        fun choosePreviewSize(ctx: Context, cameraId: String, w: Int, h: Int): Size {
            val fallback = Size(minOf(w, 1920), minOf(h, 1080))
            return try {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val map = cm.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes: List<Size> = map?.getOutputSizes(android.view.SurfaceHolder::class.java)
                    ?.filter { it.width <= 1920 && it.height <= 1080 } ?: emptyList()
                val ratio = w.toFloat() / h
                if (sizes.isEmpty()) fallback
                else sizes.sortedWith(compareBy<Size>(
                    { Math.abs(it.width.toFloat() / it.height - ratio) > 0.01f },
                    { -(it.width * it.height) },
                )).first()
            } catch (t: Throwable) {
                SdLog.w("查摄像头 $cameraId 预览尺寸失败（相机服务可能在重启）: ${t.message}")
                fallback
            }
        }
    }

    private val thread = HandlerThread("sd-camera").apply { start() }
    /** 相机线程：所有相机回调 / 自己 post 的任务都从这里过，抛出的异常拦住写日志，不让整个 App 闪退 */
    private val handler = object : Handler(thread.looper) {
        override fun dispatchMessage(msg: android.os.Message) {
            try { super.dispatchMessage(msg) } catch (t: Throwable) { SdCrashLog.caught("相机线程", t) }
        }
    }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    @Volatile var chars: CameraCharacteristics? = null
        private set
    @Volatile var cameraId: String = ""
        private set
    @Volatile var isFront = false
        private set
    private var encoderSurface: Surface? = null
    @Volatile private var closed = false
    @Volatile private var previewUnsupported = false

    /**
     * 接过屏幕预览后、界面不可见时顶替预览的看不见的输出（尺寸 = 预览尺寸，引擎设置）。
     * 三星 S25U 上推流中途从「编码器+预览」重建成「仅编码器」后，编码器收到的画面无法压缩
     * （QP33 下 4K P 帧 2.8MB），帧率掉到 2~4、PC 黑屏。会话布局在整个推流期间保持不变即正常：
     * 从没接过预览的（silu 做法）始终仅编码器；接过预览的用替身保持两路输出。
     */
    @Volatile var standbySize: Size? = null
    private var standbyReader: ImageReader? = null
    @Volatile private var standbyUnsupported = false
    private var hadPreview = false
    private var targets: List<Surface> = emptyList()
    @Volatile private var params = Camera2ParamApplier.Params()
    private var firstFrameSeen = false

    /** 会话实际出帧计数（采集 fps 统计用） */
    @Volatile var frameCount = 0L
        private set
    private var lastPhysId: String? = null
    private var physSwitches = 0
    private var lastPhysLogMs = 0L
    /** 当前会话输出绑定的物理主摄（null = 走逻辑摄像头，由 HAL 自己选镜头） */
    private var lockedPhysId: String? = null
    /** 绑主摄的会话配置失败过（换编码器尺寸时再试） */
    @Volatile private var physLockUnsupported = false
    /** 给主摄下物理裁切失败过：这台以后主摄放大交给 HAL，至少变倍 / 对焦等参数能下去 */
    @Volatile private var physCropBroken = false
    /** 绑主摄时手动对焦 / 手动曝光相机不照做：这台本次推流不再绑主摄 */
    @Volatile private var physLockBroken = false
    /** 请求手动对焦 / 手动曝光、相机回报的却不是手动的连续帧数（见 [checkManualApplied]） */
    private var notAppliedFrames = 0
    /**
     * 绑主摄把相机 HAL 弄崩过（会话配置成功，还没出一帧相机就报 CAMERA_DEVICE / CAMERA_SERVICE）：这台以后不绑主摄，存本机。
     * cd88888888 vivo V2547A 11-12：每次「绑主摄=物理3」就绪后 0.5~1.5 秒相机报错、连摄像头列表都空了，5 秒自愈重开又绑又崩，
     * SRT 一帧出不来、App 反复重启；同机 SRS（不绑主摄）正常。原来的兜底只管「会话配置失败」。
     */
    @Volatile private var physLockCrashed = false
    /** 当前会话是绑主摄的，且从它就绪到现在还没出过帧（出帧后清掉） */
    private var lockSessionNoFrame = false
    /** 绑主摄 + 逻辑变焦（[Camera2ParamApplier.Params.boundZoom]）读回没生效 / 下参数失败：本次推流主摄放大改回交给 HAL */
    @Volatile private var boundZoomBroken = false
    private var boundProbeZoom = 0f
    private var boundProbeFrames = 0
    private var lastBoundZoom = 0f
    private var boundResultMissingLogged = false
    private var lastCropZoom = 0f
    /**
     * 自动测出的主摄裁切上限（0 = 没测出）：有的 HAL 对物理主摄的 SCALER_CROP_REGION 有上限、超了悄悄钳住不报错
     * （4186652825 三星 SM-W9026：请求 3.7x 画面停在约 1.6x）。按摄像头 id 存本机，超过它的放大交给 HAL。
     */
    private var learnedCropCap = 0f
    private var probeZoom = 0f
    private var probeFrames = 0
    private var cropResultMissingLogged = false
    /**
     * 逻辑多摄的主摄：id、长焦起始倍数（焦距 ≥ 主摄 1.5 倍的最近一颗 / 主摄焦距，null = 没有长焦）、
     * 主摄感光区与最大数码变焦（物理裁切用）、HAL 是否支持给物理镜头单独下 SCALER_CROP_REGION
     * （支持才能在主摄上做 1x 以上的数码变焦并保持绑主摄）
     */
    private class MainPhys(val id: String?, val teleRatio: Float?, val active: android.graphics.Rect?,
                           val maxZoom: Float, val cropOk: Boolean)
    private var mainPhysCache: Pair<String, MainPhys>? = null
    @Volatile var previewDesc: String = "-"
        private set
    @Volatile var curExposureNs = 0L
        private set
    @Volatile var curIso = 0
        private set
    @Volatile var curFrameDurNs = 0L
        private set

    // —— §141 曝光（照 test-android CameraSource：快门/ISO 0=自动，只手动一项时另一项按测光折算）——
    private var lastAutoExposureNs = 0L
    private var lastAutoIso = 0
    @Volatile var aeAuto = true
        private set
    @Volatile var exposureDesc: String = "自动"
        private set

    val manualSupported: Boolean
        get() = chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
    val exposureRangeNs: android.util.Range<Long>?
        get() = chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val isoRange: android.util.Range<Int>?
        get() = chars?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    /** 自动曝光补偿可调范围（EV） */
    val evRange: Pair<Float, Float>?
        get() {
            val c = chars ?: return null
            val step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: return null
            val r = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return null
            if (step.toFloat() <= 0f || (r.lower == 0 && r.upper == 0)) return null
            return r.lower * step.toFloat() to r.upper * step.toFloat()
        }
    /** 变焦倍率范围：Android 11+ 用 CONTROL_ZOOM_RATIO_RANGE（多摄可 <1x），否则 1x~最大数码变焦 */
    val zoomRange: Pair<Float, Float>?
        get() {
            val c = chars ?: return null
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { return it.lower to it.upper }
            }
            val max = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: return null
            return if (max > 1f) 1f to max else null
        }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val n = frameCount++
            lockSessionNoFrame = false
            if (!firstFrameSeen) {
                firstFrameSeen = true
                try { listener.onCameraFirstFrame() } catch (_: Throwable) {}
            }
            curExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: curExposureNs
            curIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: curIso
            curFrameDurNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: curFrameDurNs
            // §141 自动曝光期间记下测光结果：之后只手动一项时，另一项按它折算（test-android 同款）
            if (aeAuto && curExposureNs > 0 && curIso > 0) {
                lastAutoExposureNs = curExposureNs
                lastAutoIso = curIso
            }
            // 逻辑多摄（三星等）由 HAL 自己在主摄 / 超广角之间切：切的时候记一笔，每秒最多一行
            val phys = if (android.os.Build.VERSION.SDK_INT >= 29)
                result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null
            if (phys != null) {
                val prev = lastPhysId
                if (prev != null && prev != phys) {
                    physSwitches++
                    val now = System.currentTimeMillis()
                    if (now - lastPhysLogMs >= 1000) {
                        lastPhysLogMs = now
                        SdLog.i("📷 物理摄像头切换 $prev→$phys（累计 $physSwitches 次） 焦距=${result.get(CaptureResult.LENS_FOCUS_DISTANCE)} " +
                                "exp=${"%.1f".format(curExposureNs / 1_000_000.0)}ms iso=$curIso" +
                                (lockedPhysId?.let { "（画面已绑主摄 $it，不受影响）" } ?: ""))
                    }
                }
                lastPhysId = phys
            }
            checkPhysCrop(request, result)
            checkBoundZoom(request, result)
            checkManualApplied(request, result)
            if (n % 150 == 0L) {
                val durMs = curFrameDurNs / 1_000_000.0
                SdLog.i("cam exp=${"%.1f".format(curExposureNs / 1_000_000.0)}ms frameDur=${"%.1f".format(durMs)}ms" +
                        "(≈${if (durMs > 0) (1000.0 / durMs).toInt() else -1}fps) aeRange=${request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)}" +
                        " iso=$curIso aeMode=${result.get(CaptureResult.CONTROL_AE_MODE)}" +
                        " af=${result.get(CaptureResult.CONTROL_AF_MODE)}/${result.get(CaptureResult.CONTROL_AF_STATE)}" +
                        " 焦距=${result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { "%.2f".format(it) }}" +
                        " 镜头=${result.get(CaptureResult.LENS_STATE)} 物理=${phys ?: "-"}（切$physSwitches）" +
                        " 降噪=${Camera2ParamApplier.nrName(result.get(CaptureResult.NOISE_REDUCTION_MODE))}" +
                        " 防频闪=${if (params.antiBanding50Hz) "开" else "关"}(相机防闪=${antibandingName(result.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE))})" +
                        " 预览=$previewDesc")
            }
        }
    }

    private fun antibandingName(m: Int?): String = when (m) {
        android.hardware.camera2.CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF -> "关"
        android.hardware.camera2.CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ -> "50Hz"
        android.hardware.camera2.CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ -> "60Hz"
        android.hardware.camera2.CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO -> "自动"
        else -> "-"
    }

    @SuppressLint("MissingPermission")
    fun open(front: Boolean, encoderSurface: Surface, initial: Camera2ParamApplier.Params) {
        val id = cameraId(context, front) ?: run {
            listener.onCameraError("没有可用的摄像头")
            return
        }
        this.encoderSurface = encoderSurface
        this.params = initial
        handler.post { openDevice(id, front) }
        PreviewRegistry.addListener(this)
    }

    @SuppressLint("MissingPermission")
    private fun openDevice(id: String, front: Boolean) {
        if (closed) return
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = id
        isFront = front
        firstFrameSeen = false
        lockSessionNoFrame = false
        try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            learnedCropCap = sp.getFloat(KEY_CROP_CAP + id, 0f)
            if (sp.getBoolean(KEY_LOCK_CRASH + id, false)) physLockCrashed = true
        } catch (_: Throwable) {}
        try {
            chars = cm.getCameraCharacteristics(id)
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) { camera.close(); return }
                    device = camera
                    SdLog.i("摄像头已打开 id=$id ${if (front) "前置" else "后置"}")
                    createSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) device = null
                    session = null
                    listener.onCameraError("摄像头被系统断开（后台被收回/被其它应用占用）")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    try { camera.close() } catch (_: Throwable) {}
                    if (device === camera) device = null
                    session = null
                    val name = when (error) {
                        ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
                        ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
                        ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
                        ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
                        ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
                        else -> error.toString()
                    }
                    // 绑主摄的会话就绪后一帧没出、相机自己崩了（不是被别的 App 抢 / 被系统禁用）→ 这台以后不绑主摄
                    val lock = lockedPhysId
                    if (lock != null && lockSessionNoFrame && !physLockCrashed &&
                        (error == ERROR_CAMERA_DEVICE || error == ERROR_CAMERA_SERVICE)) {
                        physLockCrashed = true
                        try {
                            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putBoolean(KEY_LOCK_CRASH + id, true).apply()
                        } catch (_: Throwable) {}
                        SdLog.w("📷 绑主摄 $lock 的会话还没出画面相机就报 $name → 这台以后不再绑主摄（已记到本机；HAL 自己选镜头），等自愈重开")
                        lensDesc = lensDesc?.let { "$it 绑主摄=已停用（这台绑主摄把相机弄崩过）" }
                    } else if (lock != null && curZoomPath == ZOOM_BOUND_LOGICAL && !boundZoomBroken &&
                        (error == ERROR_CAMERA_DEVICE || error == ERROR_CAMERA_SERVICE)) {
                        // 绑主摄 + 逻辑变焦期间相机自己崩了：这条路本次推流不再走，免得自愈重开后又崩
                        boundZoomBroken = true
                        SdLog.w("📷 绑主摄 $lock 逻辑变焦期间相机报 $name → 本次推流主摄放大改回交给 HAL")
                    }
                    lockSessionNoFrame = false
                    curZoomPath = ZOOM_UNBOUND
                    listener.onCameraError("摄像头错误 $name")
                }
            }, handler)
        } catch (t: Throwable) {
            listener.onCameraError("打开摄像头失败: ${t.message}")
        }
    }

    /** 预览出现/消失：只重建会话，不重开摄像头 */
    override fun onPreviewChanged(surface: Surface?) {
        handler.post {
            previewUnsupported = false
            if (device != null && !closed) createSession()
        }
    }

    /** 帧收到即丢；Android 10+ 声明 GPU 采样用途，HAL 按预览类流处理 */
    private fun standbySurface(): Surface? {
        val size = standbySize ?: return null
        standbyReader?.let { if (it.width == size.width && it.height == size.height) return it.surface }
        releaseStandby()
        return try {
            val reader = if (android.os.Build.VERSION.SDK_INT >= 29) {
                ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, 2,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            } else {
                ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            }
            reader.setOnImageAvailableListener({ r ->
                try { r.acquireLatestImage()?.close() } catch (_: Throwable) {}
            }, handler)
            standbyReader = reader
            reader.surface
        } catch (t: Throwable) {
            SdLog.w("后台替身预览创建失败: ${t.message}")
            null
        }
    }

    private fun releaseStandby() {
        try { standbyReader?.close() } catch (_: Throwable) {}
        standbyReader = null
    }

    /**
     * 换编码器（分辨率/帧率/GOP/QP 变化）：不重开摄像头，停旧会话 → [releaseOld] → 用新 Surface 建会话。
     * 旧编码器必须在旧会话停掉之后再停，否则摄像头还往已释放的 Surface 写。
     */
    fun switchEncoder(newSurface: Surface, newParams: Camera2ParamApplier.Params, releaseOld: () -> Unit) {
        handler.post {
            try { session?.stopRepeating() } catch (_: Throwable) {}
            try { session?.close() } catch (_: Throwable) {}
            session = null
            releaseOld()
            encoderSurface = newSurface
            params = newParams
            previewUnsupported = false
            physLockUnsupported = false
            if (device != null && !closed) createSession()
        }
    }

    /** 切前后摄 / 相机自愈：关设备重开（编码器 Surface 不变，SRT 不断） */
    fun reopen(front: Boolean) {
        handler.post {
            try { session?.close() } catch (_: Throwable) {}
            session = null
            try { device?.close() } catch (_: Throwable) {}
            device = null
            val id = cameraId(context, front)
            if (id == null) {
                listener.onCameraError("没有${if (front) "前置" else "后置"}摄像头")
                return@post
            }
            // 给 CameraService 一拍收尾，防 CAMERA_IN_USE（§相机自愈 同一坑）
            handler.postDelayed({ openDevice(id, front) }, 300)
        }
    }

    /** PC 硬件参数变化：只换 repeating request，不重建会话 */
    fun setControls(p: Camera2ParamApplier.Params) {
        handler.post {
            params = p
            val lock = wantPhysLock(p)
            if (lock != lockedPhysId && device != null && !closed) {
                SdLog.i("📷 ${if (lock != null) "绑主摄 $lock（${p.zoom ?: 1f}x）" else "解除绑主摄（${p.zoom ?: 1f}x：广角 / 长焦 / 不支持物理裁切 / 超过主摄裁切上限 / 已关闭）"} → 重建会话")
                createSession()
            } else {
                submitRepeating()
            }
        }
    }

    /**
     * 逻辑多摄的主摄物理 id：焦距最接近逻辑摄像头标称焦距的那个。
     * 三星等逻辑多摄近距离会自己在主摄 / 超广角之间来回切（4186652825：1.3 秒一次、画面一亮一暗），绑定后输出只来自主摄。
     */
    private fun mainPhysicalId(): String? = mainPhys().id

    private fun mainPhys(): MainPhys {
        val none = MainPhys(null, null, null, 1f, false)
        if (android.os.Build.VERSION.SDK_INT < 28) return none
        mainPhysCache?.let { if (it.first == cameraId) return it.second }
        val c = chars ?: return none
        val logical = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
        val info = if (!logical) none else try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val target = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val phys = c.physicalCameraIds.toList()
            val focal = phys.associateWith {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            }
            val pick = if (target == null) null
                else phys.filter { focal[it] != null }.minByOrNull { Math.abs(focal[it]!! - target) }
            val main = pick ?: phys.firstOrNull()
            val mainF = main?.let { focal[it] }
            val tele = if (mainF == null || mainF <= 0f) null
                else focal.values.filterNotNull().filter { it >= mainF * 1.5f }.minOrNull()?.let { it / mainF }
            val physKeys = try { c.availablePhysicalCameraRequestKeys } catch (_: Throwable) { null }
            val cropOk = physKeys?.contains(CaptureRequest.SCALER_CROP_REGION) == true
            val mc = main?.let { cm.getCameraCharacteristics(it) }
            val m = MainPhys(main, tele, mc?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                mc?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f, cropOk)
            val desc = "📷 逻辑多摄 id=$cameraId 物理=${phys.joinToString(" ") { p -> "$p(f=${focal[p]})" }} 标称f=$target → 主摄=$main" +
                    " 长焦起=${tele?.let { r -> "%.1fx".format(r) } ?: "无"}" +
                    " 物理裁切=${if (cropOk && m.active != null) "支持（主摄放大仍绑主摄）" else "不支持（主摄放大交给 HAL）"}" +
                    (if (physLockCrashed) " 绑主摄=已停用（这台绑主摄把相机弄崩过）" else "") +
                    " 主摄感光区=${m.active?.let { "${it.width()}x${it.height()}" } ?: "-"} 主摄最大数码变焦=${"%.1f".format(m.maxZoom)}x" +
                    " 变焦范围=${zoomRange?.let { "%.2f~%.1fx".format(it.first, it.second) } ?: "-"}" +
                    " 已测裁切上限=${if (learnedCropCap > 0f) "%.2fx".format(learnedCropCap) else "无"}" +
                    " 物理镜头可单独下参数=${physKeys?.size ?: 0}项（对焦${if (physKeys?.contains(CaptureRequest.CONTROL_AF_MODE) == true) "有" else "无"}" +
                    " 曝光${if (physKeys?.contains(CaptureRequest.SENSOR_EXPOSURE_TIME) == true) "有" else "无"}）"
            SdLog.i(desc)
            lensDesc = desc
            m
        } catch (t: Throwable) {
            SdLog.w("读物理摄像头失败（不绑主摄）: ${t.message}")
            none
        }
        mainPhysCache = cameraId to info
        return info
    }

    /**
     * 绑主摄：PC 选的是主摄（≥1x）就绑——1x 直接绑；放大且 HAL 支持物理裁切、还没到长焦倍数时也绑，
     * 放大由 [applyPhysZoom] 在主摄上裁（当时按「物理流不吃逻辑摄像头的 ZOOM_RATIO」设计，没实测过；
     * 11-13 加的 boundZoom 就是在物理裁切用不了的机型上验证这一点，见 [zoomPath]）。
     * 0.5x 广角、到了长焦倍数、不支持物理裁切 → 交给 HAL 选镜头（facai1688：主摄 1.2 / 1.3x 解绑后 HAL 在超广角 / 主摄间来回切）。
     */
    private fun wantPhysLock(p: Camera2ParamApplier.Params): String? =
        if (zoomPath(p) == ZOOM_UNBOUND) null else mainPhys().id

    /**
     * 这组参数下画面怎么出：
     * - [ZOOM_UNBOUND]：不绑主摄，HAL 自己选镜头（广角 / 长焦 / 关了绑主摄 / 绑不了）；
     * - [ZOOM_BOUND_1X]：1x，绑主摄，普通请求；
     * - [ZOOM_PHYS_CROP]：主摄放大，绑主摄，给物理主摄单独下裁切（HAL 支持且没到本机测出的上限）；
     * - [ZOOM_BOUND_LOGICAL]：主摄放大但物理裁切用不了（三星 S9480 不支持；F9660 / W9026 声明支持但请求 1.10x 实际 1.00x），
     *   `boundZoom` 开了就保持绑主摄、逻辑变焦下在绑定的流上（[applyBoundZoom]），读回没生效再退回 [ZOOM_UNBOUND]。
     *   116116116 三星 SM-S9480 11-13：1.1x 解绑后 HAL 每 4 秒在主摄 5 / 超广角 2 间切一次。
     */
    private fun zoomPath(p: Camera2ParamApplier.Params): Int {
        if (!p.lockMainLens || physLockUnsupported || physLockBroken || physLockCrashed) return ZOOM_UNBOUND
        val z = p.zoom ?: 1f
        if (z < 0.99f) return ZOOM_UNBOUND
        val m = mainPhys()
        if (m.id == null) return ZOOM_UNBOUND
        if (z <= 1.01f) return ZOOM_BOUND_1X
        if (m.teleRatio != null && z >= m.teleRatio - 0.01f) return ZOOM_UNBOUND
        if (m.cropOk && m.active != null && !physCropBroken && z <= cropCap(p) + 0.01f) return ZOOM_PHYS_CROP
        if (p.boundZoom > 0 && !boundZoomBroken) return ZOOM_BOUND_LOGICAL
        return ZOOM_UNBOUND
    }

    /** 主摄裁切放大上限：后台 srtParams.mainCropMax >0 固定、<0 不限；0 = 用本机测出的（没测出 = 不限） */
    private fun cropCap(p: Camera2ParamApplier.Params): Float = when {
        p.mainCropMax > 0f -> p.mainCropMax.coerceAtLeast(1f)
        p.mainCropMax < 0f -> Float.MAX_VALUE
        learnedCropCap > 0f -> learnedCropCap
        else -> Float.MAX_VALUE
    }

    /**
     * 读回相机实际用的主摄裁切区：倍数稳定 6 帧后记「请求 → 实际」；自动模式下实际比请求小 0.1x 以上 =
     * HAL 钳住了，记下上限（只降不升）存本机，重建会话让超过上限的放大交给 HAL。
     */
    private fun checkPhysCrop(request: CaptureRequest, result: TotalCaptureResult) {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        val lock = lockedPhysId ?: return
        val want = request.tag as? Float ?: return
        if (want != probeZoom) {
            probeZoom = want
            probeFrames = 0
            return
        }
        if (++probeFrames != 6) return
        val active = mainPhys().active ?: return
        @Suppress("DEPRECATION")
        val crop = result.physicalCameraResults[lock]?.get(CaptureResult.SCALER_CROP_REGION)
        if (crop == null || crop.width() <= 0) {
            if (!cropResultMissingLogged) {
                cropResultMissingLogged = true
                SdLog.i("📷 主摄 $lock 实际裁切区相机没回报（看不出是否到顶，需要时用 srtParams.mainCropMax 手动限）")
            }
            return
        }
        val actual = active.width().toFloat() / crop.width()
        SdLog.i("📷 主摄 $lock 裁切 请求 ${"%.2f".format(want)}x → 实际 ${"%.2f".format(actual)}x（实际裁切区 ${crop.width()}x${crop.height()}）")
        if (crop.width() > active.width() + 2 || crop.height() > active.height() + 2) return
        if (params.mainCropMax != 0f || want - actual < 0.1f) return
        val cap = (Math.floor(actual * 20.0) / 20.0).toFloat().coerceAtLeast(1f)
        if (learnedCropCap > 0f && learnedCropCap <= cap) return
        learnedCropCap = cap
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_CROP_CAP + cameraId, cap).apply()
        SdLog.w("📷 主摄 $lock 裁切到顶 ≈${"%.2f".format(cap)}x（请求 ${"%.2f".format(want)}x）→ 这台超过 ${"%.2f".format(cap)}x 的放大交给 HAL，重建会话")
        handler.post { if (device != null && !closed && wantPhysLock(params) != lockedPhysId) createSession() }
    }

    /**
     * 兜底：PC 下的是手动对焦 / 手动曝光（请求里 AF / AE 关），相机回报的却还是自动，连续 30 帧 = 没照做。
     * - 正在用带物理 id 的请求（主摄裁切放大）→ 这台主摄放大交给 HAL；
     * - 只是绑主摄（1x）→ 这台本次不再绑主摄；
     * - 都不是 → 只记日志（每 30 秒一行）。
     * 起因：shiji034p 三星 S25U（SM-S938B）43.0 PC 拉对焦 / 快门全不生效，回报一直 af=3（连续对焦），41.0 正常。
     */
    private fun checkManualApplied(request: CaptureRequest, result: TotalCaptureResult) {
        val reqAf = request.get(CaptureRequest.CONTROL_AF_MODE)
        val resAf = result.get(CaptureResult.CONTROL_AF_MODE)
        val reqAe = request.get(CaptureRequest.CONTROL_AE_MODE)
        val resAe = result.get(CaptureResult.CONTROL_AE_MODE)
        val afBad = reqAf == CaptureRequest.CONTROL_AF_MODE_OFF && resAf != null && resAf != CaptureRequest.CONTROL_AF_MODE_OFF
        val aeBad = reqAe == CaptureRequest.CONTROL_AE_MODE_OFF && resAe != null && resAe != CaptureRequest.CONTROL_AE_MODE_OFF
        if (!afBad && !aeBad) {
            notAppliedFrames = 0
            return
        }
        if (++notAppliedFrames % 900 != 30) return
        val what = (if (afBad) "手动对焦（回报 af=$resAf）" else "") + (if (afBad && aeBad) " / " else "") +
                (if (aeBad) "手动曝光（回报 aeMode=$resAe）" else "")
        val lock = lockedPhysId
        when {
            lock != null && request.tag is Float && !physCropBroken -> {
                physCropBroken = true
                SdLog.w("📷 主摄 $lock 裁切放大时相机没照做 $what → 这台以后主摄放大交给 HAL，重建会话")
                handler.post { if (device != null && !closed) createSession() }
            }
            lock != null && !physLockBroken -> {
                physLockBroken = true
                SdLog.w("📷 绑主摄 $lock 时相机没照做 $what → 这台本次不再绑主摄，重建会话")
                handler.post { if (device != null && !closed) createSession() }
            }
            else -> SdLog.w("相机没照做 $what（没绑主摄，普通请求）")
        }
    }

    /**
     * 带物理 id 建的请求里，物理镜头有自己的一份参数——建请求时从模板抄的默认值（连续对焦、自动曝光…），
     * `req.set()` 只改逻辑摄像头那份。有的 HAL（三星 S25U）给物理镜头就按它自己那份走，手动对焦 / 快门 / ISO 全被盖掉。
     * 所以把逻辑请求里最终的值，逐项抄给物理镜头（只抄 HAL 允许单独下给物理镜头的项；裁切区由 [applyPhysZoom] 单独给）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun mirrorToPhysical(req: CaptureRequest.Builder, c: CameraCharacteristics, physId: String) {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        val keys = (try { c.availablePhysicalCameraRequestKeys } catch (_: Throwable) { null }) ?: return
        for (k in keys) {
            if (k == CaptureRequest.SCALER_CROP_REGION) continue
            try {
                val key = k as CaptureRequest.Key<Any>
                val v = req.get(key) ?: continue
                req.setPhysicalCameraKey(key, v, physId)
            } catch (_: Throwable) {}
        }
    }

    /** 绑主摄时的变焦：逻辑请求的 ZOOM_RATIO 回 1x（HAL 不因倍数换镜头），倍数改成主摄物理裁切 */
    private fun applyPhysZoom(req: CaptureRequest.Builder, lock: String, p: Camera2ParamApplier.Params) {
        val z = p.zoom ?: 1f
        if (android.os.Build.VERSION.SDK_INT >= 30) req.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
        if (android.os.Build.VERSION.SDK_INT < 28) return
        if (z <= 1.01f) return
        val m = mainPhys()
        val active = m.active ?: return
        val zc = z.coerceIn(1f, m.maxZoom.coerceAtLeast(1f))
        val w = (active.width() / zc).toInt()
        val h = (active.height() / zc).toInt()
        val x = active.left + (active.width() - w) / 2
        val y = active.top + (active.height() - h) / 2
        req.setPhysicalCameraKey(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(x, y, x + w, y + h), lock)
        req.setTag(zc)
        if (Math.abs(zc - lastCropZoom) >= 0.05f) {
            lastCropZoom = zc
            SdLog.i("📷 主摄 $lock 裁切放大 ${"%.2f".format(zc)}x（裁切区 ${w}x$h）")
        }
    }

    /** 绑主摄 + 逻辑变焦的请求标记（[checkBoundZoom] 读回用；物理裁切那条路的标记是 Float） */
    private class BoundZoomTag(val z: Float, val mode: Int)

    /**
     * 绑主摄 + 逻辑变焦（[ZOOM_BOUND_LOGICAL]）：输出还是只来自主摄，倍数走逻辑摄像头的参数，普通请求、不带物理 id。
     * 模式 1 = 逻辑 CONTROL_ZOOM_RATIO（Android 11+；更低的系统自动用模式 2）；模式 2 = ZOOM_RATIO 回 1x，改下逻辑 SCALER_CROP_REGION。
     * 绑定的物理流认不认逻辑变焦各家 HAL 不一样，所以做成后台开关，[checkBoundZoom] 读回验证。
     */
    private fun applyBoundZoom(req: CaptureRequest.Builder, c: CameraCharacteristics, lock: String, p: Camera2ParamApplier.Params) {
        val m = mainPhys()
        val z = (p.zoom ?: 1f).coerceIn(1f, m.maxZoom.coerceAtLeast(1f))
        val mode = if (p.boundZoom == 2 || android.os.Build.VERSION.SDK_INT < 30) 2 else 1
        if (mode == 1) {
            req.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
        } else {
            if (android.os.Build.VERSION.SDK_INT >= 30) req.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (active != null) {
                val w = (active.width() / z).toInt()
                val h = (active.height() / z).toInt()
                val x = active.left + (active.width() - w) / 2
                val y = active.top + (active.height() - h) / 2
                req.set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(x, y, x + w, y + h))
            }
        }
        req.setTag(BoundZoomTag(z, mode))
        if (Math.abs(z - lastBoundZoom) >= 0.05f) {
            lastBoundZoom = z
            SdLog.i("📷 绑主摄 $lock 逻辑变焦 ${"%.2f".format(z)}x（模式$mode ${if (mode == 1) "ZOOM_RATIO" else "逻辑裁切区"}，不解绑）")
        }
    }

    /**
     * 读回绑主摄 + 逻辑变焦到底生效没有：倍数稳定 6 帧后看物理主摄那份回报（zoomRatio × 裁切比例）。
     * 比请求小 0.08x 以上 = 物理流没跟着放大 → 本次推流主摄放大改回交给 HAL（`boundZoomForce` 开着就只记日志不退回）。
     * 物理镜头没回报这两项 = 看不出来，保持绑主摄，只记一行。
     */
    private fun checkBoundZoom(request: CaptureRequest, result: TotalCaptureResult) {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        val lock = lockedPhysId ?: return
        val tag = request.tag as? BoundZoomTag ?: return
        if (tag.z != boundProbeZoom) {
            boundProbeZoom = tag.z
            boundProbeFrames = 0
            return
        }
        if (++boundProbeFrames != 6) return
        @Suppress("DEPRECATION")
        val phys = result.physicalCameraResults[lock]
        val api30 = android.os.Build.VERSION.SDK_INT >= 30
        val physZr = if (api30) phys?.get(CaptureResult.CONTROL_ZOOM_RATIO) else null
        @Suppress("DEPRECATION")
        val physCrop = phys?.get(CaptureResult.SCALER_CROP_REGION)
        val logZr = if (api30) result.get(CaptureResult.CONTROL_ZOOM_RATIO) else null
        val logCrop = result.get(CaptureResult.SCALER_CROP_REGION)
        val active = mainPhys().active
        val cropX = if (physCrop != null && physCrop.width() > 0 && active != null && physCrop.width() <= active.width() + 2)
            active.width().toFloat() / physCrop.width() else null
        val desc = "物理镜头回报 zoomRatio=${physZr ?: "-"} 裁切区=${physCrop?.let { "${it.width()}x${it.height()}" } ?: "-"}；" +
                "逻辑回报 zoomRatio=${logZr ?: "-"} 裁切区=${logCrop?.let { "${it.width()}x${it.height()}" } ?: "-"}"
        val want = "%.2f".format(tag.z)
        if (physZr == null && cropX == null) {
            if (!boundResultMissingLogged) {
                boundResultMissingLogged = true
                SdLog.i("📷 绑主摄 $lock 逻辑变焦（模式${tag.mode}）请求 ${want}x：物理镜头没回报倍数，看不出是否生效，保持绑主摄（$desc）")
            }
            return
        }
        val actual = (physZr ?: 1f) * (cropX ?: 1f)
        SdLog.i("📷 绑主摄 $lock 逻辑变焦（模式${tag.mode}）请求 ${want}x → 物理镜头实际 ≈${"%.2f".format(actual)}x（$desc）")
        if (tag.z - actual < 0.08f) return
        if (params.boundZoomForce) {
            SdLog.w("📷 绑主摄 $lock 逻辑变焦读回没生效，boundZoomForce 开着 → 不退回，继续绑主摄")
            return
        }
        boundZoomBroken = true
        SdLog.w("📷 绑主摄 $lock 逻辑变焦（模式${tag.mode}）没生效 → 本次推流主摄放大改回交给 HAL，重建会话")
        handler.post { if (device != null && !closed && wantPhysLock(params) != lockedPhysId) createSession() }
    }

    val isRunning: Boolean get() = device != null && session != null

    /** 当前 repeating request 走的是哪条变焦路（相机线程读写） */
    private var curZoomPath = ZOOM_UNBOUND

    private fun submitRepeating() {
        val camera = device ?: return
        val s = session ?: return
        val c = chars ?: return
        val lock = lockedPhysId
        val path = if (lock == null) ZOOM_UNBOUND else zoomPath(params)
        try {
            // 只有真要给物理主摄下裁切（绑主摄且放大 >1x）才带物理 id 建请求（不带会 "Physical camera id: N is not valid!"）。
            // 1x 不需要给物理镜头下任何东西 → 用普通请求（41.0 及以前的做法，输出照旧绑主摄）：带物理 id 的请求里
            // 物理镜头另有一份模板默认参数，S25U 按那份走，PC 的手动对焦 / 快门全不生效（43.0 的问题）。
            // 绑主摄 + 逻辑变焦（boundZoom）也是普通请求。
            val cropLock = if (android.os.Build.VERSION.SDK_INT >= 28 && (params.zoom ?: 1f) > 1.01f &&
                path != ZOOM_BOUND_LOGICAL) lock else null
            val req = if (cropLock != null && android.os.Build.VERSION.SDK_INT >= 28)
                camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD, setOf(cropLock))
            else camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            targets.forEach { req.addTarget(it) }
            req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            Camera2ParamApplier.applyToBuilder(req, c, params)
            if (lock != null) {
                if (path == ZOOM_BOUND_LOGICAL) applyBoundZoom(req, c, lock, params) else applyPhysZoom(req, lock, params)
            }
            applySrtExposure(req, c, params)
            // 放大时：对焦 / 曝光 / 帧率等最终值同步给物理主摄，不让它用模板默认值
            cropLock?.let { mirrorToPhysical(req, c, it) }
            s.setRepeatingRequest(req.build(), captureCallback, handler)
            curZoomPath = path
        } catch (t: Throwable) {
            if (path == ZOOM_BOUND_LOGICAL && !boundZoomBroken) {
                boundZoomBroken = true
                SdLog.w("📷 绑主摄 $lock 逻辑变焦下参数失败（${t.message}）→ 本次推流主摄放大改回交给 HAL，重建会话")
                handler.post { createSession() }
            } else if (lock != null && (params.zoom ?: 1f) > 1.01f && !physCropBroken) {
                physCropBroken = true
                SdLog.w("📷 主摄 $lock 裁切放大下参数失败（${t.message}）→ 这台以后主摄放大交给 HAL，重建会话")
                handler.post { createSession() }
            } else {
                SdLog.w("下发采集参数失败: ${t.message}")
            }
        }
    }

    /**
     * §141 SRT直推曝光，逐行照 test-android `CameraSource.applyExposure`：
     * 快门/ISO 任一手动 → AE 关闭，曝光时间 + ISO + 帧间隔全部手动给；另一项为自动时按切换前的
     * 测光结果折算（曝光×ISO 不变，亮度不跳）。快门超过一帧时间钳到 1/fps，保证帧率不掉。
     * 两项都自动 → 保持 [Camera2ParamApplier] 写好的自动曝光（含曝光补偿/50Hz 防闪）。
     */
    private fun applySrtExposure(req: CaptureRequest.Builder, c: CameraCharacteristics, p: Camera2ParamApplier.Params) {
        val reqShutterNs = p.srtShutterNs
        val reqIso = p.srtIso
        if ((reqShutterNs <= 0 && reqIso <= 0) || !manualSupported) {
            aeAuto = true
            exposureDesc = if (reqShutterNs > 0 || reqIso > 0) "自动（该摄像头不支持手动曝光）" else "自动"
            return
        }
        val fps = (p.targetFps ?: 30).coerceAtLeast(1)
        val frameNs = 1_000_000_000L / fps
        val expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoR = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val baseExp = if (lastAutoExposureNs > 0) lastAutoExposureNs else frameNs / 2
        val baseIso = if (lastAutoIso > 0) lastAutoIso else 400
        var exp = if (reqShutterNs > 0) reqShutterNs
            else if (reqIso > 0) baseExp * baseIso / reqIso else baseExp
        exp = exp.coerceAtMost(frameNs)
        if (expRange != null) exp = exp.coerceIn(expRange.lower, minOf(expRange.upper, frameNs).coerceAtLeast(expRange.lower))
        val isoWant = if (reqIso > 0) reqIso else (baseExp.toDouble() * baseIso / exp).toInt()
        var iso = isoWant
        if (isoR != null) iso = iso.coerceIn(isoR.lower, isoR.upper)
        aeAuto = false
        req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        req.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
        req.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        req.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
        // 与 test-android 一致：不加数码增益。需要的 ISO 超过传感器上限时只能变暗，状态里写明，便于对照。
        val capDesc = if (isoR != null && isoWant > isoR.upper) "（ISO已到上限${isoR.upper}，需要≈$isoWant）" else ""
        exposureDesc = "手动 快门 1/${1_000_000_000L / exp.coerceAtLeast(1)}s ISO $iso$capDesc" +
                (if (reqShutterNs <= 0) "（快门按测光折算）" else "") +
                (if (reqIso <= 0) "（ISO 按测光折算）" else "")
        SdLog.i("§141 曝光 $exposureDesc（帧间隔 ${frameNs / 1_000_000}ms）")
    }

    private fun createSession() {
        val camera = device ?: return
        val enc = encoderSurface ?: return
        try { session?.close() } catch (_: Throwable) {}
        session = null
        val preview = if (previewUnsupported) null else PreviewRegistry.current()
        if (preview != null) hadPreview = true
        val standby = if (preview == null && hadPreview && !standbyUnsupported) standbySurface() else null
        val second = preview ?: standby
        val t = if (second != null) listOf(enc, second) else listOf(enc)
        targets = t
        previewDesc = when {
            preview != null -> "屏幕预览"
            standby != null -> "后台替身${standbySize?.width}x${standbySize?.height}"
            previewUnsupported -> "无(带预览会话配置失败,只写编码器)"
            else -> "无(界面不可见,只写编码器)"
        }
        val lock = wantPhysLock(params)
        lockedPhysId = lock
        probeZoom = 0f
        boundProbeZoom = 0f
        lastBoundZoom = 0f
        notAppliedFrames = 0
        lockSessionNoFrame = false
        curZoomPath = ZOOM_UNBOUND
        val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed || t !== targets) { s.close(); return }
                    session = s
                    // 绑主摄的会话：出第一帧前相机就崩的话，onError 里据此判定是绑主摄弄崩的
                    lockSessionNoFrame = lock != null
                    submitRepeating()
                    SdLog.i("摄像头会话就绪 输出=编码器+$previewDesc" + (lock?.let { " 绑主摄=物理$it" } ?: ""))
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (lock != null) {
                        SdLog.w("绑主摄 $lock 的会话配置失败 → 改回逻辑摄像头（HAL 自己选镜头）")
                        physLockUnsupported = true
                        handler.post { createSession() }
                    } else if (preview != null) {
                        SdLog.w("带预览的会话配置失败 → 改为只写编码器（预览尺寸可能不匹配）")
                        previewUnsupported = true
                        handler.post { createSession() }
                    } else if (standby != null) {
                        SdLog.w("带后台替身的会话配置失败 → 改为只写编码器")
                        standbyUnsupported = true
                        handler.post { createSession() }
                    } else {
                        listener.onCameraError("摄像头会话配置失败（该分辨率可能不被支持）")
                    }
                }
            }
        try {
            if (lock != null && android.os.Build.VERSION.SDK_INT >= 28) {
                val outs = t.map { android.hardware.camera2.params.OutputConfiguration(it).apply { setPhysicalCameraId(lock) } }
                @Suppress("DEPRECATION")
                camera.createCaptureSessionByOutputConfigurations(outs, callback, handler)
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(t, callback, handler)
            }
        } catch (t2: Throwable) {
            if (lock != null) {
                SdLog.w("绑主摄 $lock 建会话异常 → 改回逻辑摄像头: ${t2.message}")
                physLockUnsupported = true
                handler.post { createSession() }
            } else {
                listener.onCameraError("创建摄像头会话失败: ${t2.message}")
            }
        }
    }

    fun close() {
        closed = true
        PreviewRegistry.removeListener(this)
        handler.post {
            try { session?.close() } catch (_: Throwable) {}
            session = null
            try { device?.close() } catch (_: Throwable) {}
            device = null
            releaseStandby()
            thread.quitSafely()
        }
    }
}
