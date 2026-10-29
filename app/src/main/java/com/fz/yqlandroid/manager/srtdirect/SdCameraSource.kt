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
        /** 指定朝向的第一个摄像头 id（后置一般是逻辑主摄，ZOOM_RATIO 可 <1）；没有就用第一个 */
        fun cameraId(ctx: Context, front: Boolean): String? {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            return cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want
            } ?: cm.cameraIdList.firstOrNull()
        }

        /** 编码器可用的输出尺寸里与目标最接近的（优先同宽高比） */
        fun chooseSize(ctx: Context, cameraId: String, w: Int, h: Int): Size {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val map = cm.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(MediaCodec::class.java) ?: return Size(w, h)
            sizes.firstOrNull { it.width == w && it.height == h }?.let { return it }
            val ratio = w.toFloat() / h
            return sizes.sortedWith(compareBy<Size>(
                { Math.abs(it.width.toFloat() / it.height - ratio) > 0.01f },
                { Math.abs(it.width * it.height - w * h) },
            )).first()
        }

        /**
         * 屏幕预览尺寸：同宽高比、不超过 1080p 的最大 SurfaceHolder 尺寸。
         * Camera2 规定 SurfaceView 预览最大 1080p，推 1440p/4K 时预览若用推流尺寸会话会配置失败。
         */
        fun choosePreviewSize(ctx: Context, cameraId: String, w: Int, h: Int): Size {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val map = cm.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(android.view.SurfaceHolder::class.java)
                ?.filter { it.width <= 1920 && it.height <= 1080 }
            if (sizes.isNullOrEmpty()) return Size(minOf(w, 1920), minOf(h, 1080))
            val ratio = w.toFloat() / h
            return sizes.sortedWith(compareBy<Size>(
                { Math.abs(it.width.toFloat() / it.height - ratio) > 0.01f },
                { -(it.width * it.height) },
            )).first()
        }
    }

    private val thread = HandlerThread("sd-camera").apply { start() }
    private val handler = Handler(thread.looper)
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
                                "exp=${"%.1f".format(curExposureNs / 1_000_000.0)}ms iso=$curIso")
                    }
                }
                lastPhysId = phys
            }
            if (n % 150 == 0L) {
                val durMs = curFrameDurNs / 1_000_000.0
                SdLog.i("cam exp=${"%.1f".format(curExposureNs / 1_000_000.0)}ms frameDur=${"%.1f".format(durMs)}ms" +
                        "(≈${if (durMs > 0) (1000.0 / durMs).toInt() else -1}fps) aeRange=${request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)}" +
                        " iso=$curIso aeMode=${result.get(CaptureResult.CONTROL_AE_MODE)}" +
                        " af=${result.get(CaptureResult.CONTROL_AF_MODE)}/${result.get(CaptureResult.CONTROL_AF_STATE)}" +
                        " 焦距=${result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { "%.2f".format(it) }}" +
                        " 镜头=${result.get(CaptureResult.LENS_STATE)} 物理=${phys ?: "-"}（切$physSwitches）" +
                        " 预览=$previewDesc")
            }
        }
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
                    camera.close()
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
            submitRepeating()
        }
    }

    val isRunning: Boolean get() = device != null && session != null

    private fun submitRepeating() {
        val camera = device ?: return
        val s = session ?: return
        val c = chars ?: return
        try {
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            targets.forEach { req.addTarget(it) }
            req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            Camera2ParamApplier.applyToBuilder(req, c, params)
            applySrtExposure(req, c, params)
            s.setRepeatingRequest(req.build(), captureCallback, handler)
        } catch (t: Throwable) {
            SdLog.w("下发采集参数失败: ${t.message}")
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
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(t, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed || t !== targets) { s.close(); return }
                    session = s
                    submitRepeating()
                    SdLog.i("摄像头会话就绪 输出=编码器+$previewDesc")
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (preview != null) {
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
            }, handler)
        } catch (t2: Throwable) {
            listener.onCameraError("创建摄像头会话失败: ${t2.message}")
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
