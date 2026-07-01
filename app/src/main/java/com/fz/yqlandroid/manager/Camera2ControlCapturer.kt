package com.fz.yqlandroid.manager

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.CameraVideoCapturer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * 🔥 自定义 Camera2 采集器（与 iOS CustomAVCaptureVideoCapturer 对齐）
 *
 * 目的：WebRTC 原生 Camera2Capturer 不暴露 CaptureRequest，无法做曝光/白平衡/快门/变焦/对焦控制。
 * 本类基于 Camera2 API 自建采集，通过 SurfaceTextureHelper 把纹理帧交给 WebRTC，
 * 同时完整持有 CaptureRequest.Builder，可实时下发硬件控制参数。
 *
 * 帧路径/旋转与 WebRTC Camera2Session 保持一致：
 *   Camera2 -> SurfaceTexture -> SurfaceTextureHelper.startListening -> CapturerObserver.onFrameCaptured
 *   旋转 = (sensorOrientation ± deviceOrientation) % 360
 *
 * 说明：所有硬件控制均做能力判断，不支持时回退到自动，避免在部分机型上崩溃/黑屏。
 */
class Camera2ControlCapturer(
    context: Context,
    private var isFrontFacing: Boolean,
    private val eventsHandler: CameraVideoCapturer.CameraEventsHandler? = null
) : CameraVideoCapturer, VideoSink {

    companion object {
        private const val TAG = "Camera2Ctrl"
    }

    private val appContext: Context = context.applicationContext
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private lateinit var surfaceTextureHelper: SurfaceTextureHelper
    private lateinit var capturerObserver: CapturerObserver

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private val stateLock = Object()
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var surface: Surface? = null

    private var cameraId: String? = null
    private var characteristics: CameraCharacteristics? = null
    private var sensorOrientation = 0

    private var targetWidth = 1280
    private var targetHeight = 720
    private var targetFps = 30

    @Volatile private var isDisposed = false
    @Volatile private var isRunning = false
    @Volatile private var frameCount = 0L

    // ===== 硬件控制状态（下发后立即缓存，重建会话时自动重放） =====
    private var zoomRatio: Float = 1.0f
    private var focusNormalized: Float? = null    // 0..1；null=自动连续对焦
    private var shutterCjfps: Int? = null         // null=自动AE；否则 1/cjfps 秒
    private var exposureEv: Float? = null          // AE 曝光补偿（EV）；仅自动AE时生效
    private var manualIso: Int? = null             // 手动ISO（快门模式下使用）
    private var whiteBalanceLocked: Boolean = false
    private var whiteBalanceSlider: Int? = null    // 0..100；null=不设手动色温

    // ==================== VideoCapturer ====================

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        context: Context,
        capturerObserver: CapturerObserver
    ) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        if (isDisposed) return
        targetWidth = width
        targetHeight = height
        targetFps = framerate
        Log.d(TAG, "▶️ startCapture ${width}x${height}@${framerate} front=$isFrontFacing")
        cameraThread = HandlerThread("Camera2ControlThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
        cameraHandler!!.post { openCamera() }
    }

    override fun stopCapture() {
        Log.d(TAG, "stopCapture")
        val handler = cameraHandler
        if (handler != null) {
            val latch = java.util.concurrent.CountDownLatch(1)
            handler.post {
                closeCameraInternal()
                latch.countDown()
            }
            try { latch.await(3, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        }
        cameraThread?.quitSafely()
        try { cameraThread?.join(1500) } catch (_: InterruptedException) {}
        cameraThread = null
        cameraHandler = null
        isRunning = false
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        val handler = cameraHandler ?: run {
            targetWidth = width; targetHeight = height; targetFps = framerate
            return
        }
        handler.post {
            targetWidth = width
            targetHeight = height
            targetFps = framerate
            // 重建会话应用新采集尺寸
            reopenSession()
        }
    }

    override fun dispose() {
        isDisposed = true
        stopCapture()
    }

    override fun isScreencast(): Boolean = false

    // ==================== CameraVideoCapturer ====================

    override fun switchCamera(handler: CameraVideoCapturer.CameraSwitchHandler?) {
        val camHandler = cameraHandler
        if (camHandler == null) {
            handler?.onCameraSwitchError("采集未启动")
            return
        }
        camHandler.post {
            try {
                isFrontFacing = !isFrontFacing
                closeCameraInternal()
                openCamera()
                handler?.onCameraSwitchDone(isFrontFacing)
            } catch (e: Exception) {
                handler?.onCameraSwitchError(e.message ?: "切换失败")
            }
        }
    }

    override fun switchCamera(handler: CameraVideoCapturer.CameraSwitchHandler?, cameraName: String?) {
        switchCamera(handler)
    }

    // ==================== 相机开关 ====================

    private fun openCamera() {
        if (isDisposed) return
        try {
            val id = selectCameraId(isFrontFacing) ?: run {
                Log.e(TAG, "❌ 找不到摄像头 (front=$isFrontFacing)")
                eventsHandler?.onCameraError("找不到摄像头")
                return
            }
            cameraId = id
            characteristics = cameraManager.getCameraCharacteristics(id)
            sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            Log.d(TAG, "📷 openCamera id=$id sensorOrientation=$sensorOrientation deviceOrientation=0 (portrait lock) textureSize=${targetWidth}x${targetHeight}")

            surfaceTextureHelper.setTextureSize(targetWidth, targetHeight)
            surface = Surface(surfaceTextureHelper.surfaceTexture)
            frameCount = 0

            capturerObserver.onCapturerStarted(true)

            // 权限已在 UI 层申请；此处调用需具备 CAMERA 权限
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    Log.d(TAG, "✅ CameraDevice.onOpened id=${device.id}")
                    synchronized(stateLock) { cameraDevice = device }
                    createSession(device)
                }

                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "⚠️ camera disconnected")
                    device.close()
                    synchronized(stateLock) { if (cameraDevice == device) cameraDevice = null }
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "❌ camera error: $error")
                    device.close()
                    synchronized(stateLock) { if (cameraDevice == device) cameraDevice = null }
                    eventsHandler?.onCameraError("openCamera error=$error")
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ 缺少相机权限: ${e.message}")
            eventsHandler?.onCameraError("缺少相机权限: ${e.message}")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "❌ openCamera失败: ${e.message}")
            eventsHandler?.onCameraError("openCamera失败: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ openCamera异常: ${e.message}", e)
            eventsHandler?.onCameraError("openCamera异常: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(device: CameraDevice) {
        try {
            val outSurface = surface ?: return
            device.createCaptureSession(listOf(outSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(TAG, "✅ CaptureSession.onConfigured")
                    synchronized(stateLock) { captureSession = session }
                    startRepeating(device, session, outSurface)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "❌ session configure failed")
                    eventsHandler?.onCameraError("createCaptureSession失败")
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "❌ createSession失败: ${e.message}")
            eventsHandler?.onCameraError("createSession失败: ${e.message}")
        }
    }

    private fun startRepeating(device: CameraDevice, session: CameraCaptureSession, outSurface: Surface) {
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(outSurface)
            requestBuilder = builder

            // 帧率范围
            val fpsRange = selectFpsRange(targetFps)
            if (fpsRange != null) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            }
            Log.d(TAG, "🎛️ fpsRange=$fpsRange")

            applyAllControlsLocked(builder)

            val rotation = computeFrameRotation()
            surfaceTextureHelper.setFrameRotation(rotation)
            surfaceTextureHelper.startListening(this)

            session.setRepeatingRequest(builder.build(), null, cameraHandler)

            isRunning = true
            eventsHandler?.onFirstFrameAvailable()
            Log.d(TAG, "✅ setRepeatingRequest 成功, rotation=$rotation, 等待首帧... ${targetWidth}x${targetHeight}@${targetFps} front=$isFrontFacing")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "❌ setRepeatingRequest失败: ${e.message}")
            eventsHandler?.onCameraError("setRepeatingRequest失败: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ startRepeating异常: ${e.message}", e)
            eventsHandler?.onCameraError("startRepeating异常: ${e.message}")
        }
    }

    private fun reopenSession() {
        closeCameraInternal()
        openCamera()
    }

    private fun closeCameraInternal() {
        try { surfaceTextureHelper.stopListening() } catch (_: Exception) {}
        synchronized(stateLock) {
            try { captureSession?.close() } catch (_: Exception) {}
            captureSession = null
            try { cameraDevice?.close() } catch (_: Exception) {}
            cameraDevice = null
        }
        try { surface?.release() } catch (_: Exception) {}
        surface = null
        requestBuilder = null
        try { capturerObserver.onCapturerStopped() } catch (_: Exception) {}
    }

    // ==================== VideoSink：把纹理帧交给 WebRTC ====================

    override fun onFrame(frame: VideoFrame) {
        val n = ++frameCount
        if (n == 1L) {
            Log.d(TAG, "🎉 首帧到达 ${frame.rotatedWidth}x${frame.rotatedHeight} rotation=${frame.rotation}")
        } else if (n % 60L == 0L) {
            Log.d(TAG, "📹 已采集 $n 帧 (${frame.rotatedWidth}x${frame.rotatedHeight} rot=${frame.rotation})")
        }
        capturerObserver.onFrameCaptured(frame)
    }

    // ==================== 旋转/选相机/帧率 ====================

    private fun selectCameraId(front: Boolean): String? {
        val want = if (front) CameraMetadata.LENS_FACING_FRONT else CameraMetadata.LENS_FACING_BACK
        var fallback: String? = null
        for (id in cameraManager.cameraIdList) {
            val c = cameraManager.getCameraCharacteristics(id)
            val facing = c.get(CameraCharacteristics.LENS_FACING)
            if (facing == want) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }

    private fun selectFpsRange(fps: Int): Range<Int>? {
        val ranges = characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        // 优先选上限==fps 的定帧范围，其次选包含 fps 且跨度最小的范围
        return ranges.filter { it.upper == fps }.minByOrNull { it.upper - it.lower }
            ?: ranges.filter { fps in it.lower..it.upper }.minByOrNull { it.upper - it.lower }
            ?: ranges.maxByOrNull { it.upper }
    }

    private fun computeFrameRotation(): Int {
        // 🔥 固定竖屏：UI 锁定 portrait，预览帧旋转不随物理旋转变化
        val deviceOrientation = 0
        var rotation = deviceOrientation
        if (!isFrontFacing) rotation = 360 - rotation
        return (sensorOrientation + rotation) % 360
    }

    // ==================== 硬件控制：对外接口 ====================

    fun setZoom(ratio: Float) = post {
        zoomRatio = ratio.coerceAtLeast(1.0f)
        applyAndCommit()
    }

    fun setFocus(normalized: Float?) = post {
        focusNormalized = normalized?.coerceIn(0f, 1f)
        applyAndCommit()
    }

    /** 快门：cjfps（如 240 → 1/240s）。传 null 恢复自动曝光。 */
    fun setShutter(cjfps: Int?) = post {
        shutterCjfps = cjfps?.takeIf { it > 0 }
        applyAndCommit()
    }

    /** 曝光：自动AE下的曝光补偿（EV）。 */
    fun setExposureEv(ev: Float?) = post {
        exposureEv = ev
        applyAndCommit()
    }

    /** 手动ISO（快门模式下的增益）。 */
    fun setManualIso(iso: Int?) = post {
        manualIso = iso
        applyAndCommit()
    }

    /** 白平衡锁定当前（applyWhiteBalance）。 */
    fun lockWhiteBalance(locked: Boolean) = post {
        whiteBalanceLocked = locked
        whiteBalanceSlider = null
        applyAndCommit()
    }

    /** 白平衡手动色温滑块 0..100（0冷 100暖）。 */
    fun setWhiteBalanceSlider(slider: Int?) = post {
        whiteBalanceSlider = slider?.coerceIn(0, 100)
        whiteBalanceLocked = whiteBalanceSlider != null
        applyAndCommit()
    }

    private fun post(block: () -> Unit) {
        val h = cameraHandler
        if (h != null) h.post(block) else block()
    }

    private fun applyAndCommit() {
        val builder = requestBuilder ?: return
        val session = synchronized(stateLock) { captureSession } ?: return
        try {
            applyAllControlsLocked(builder)
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "applyControls失败: ${e.message}")
        }
    }

    // ==================== 硬件控制：写入 CaptureRequest ====================

    private fun applyAllControlsLocked(b: CaptureRequest.Builder) {
        val c = characteristics ?: return
        applyZoom(b, c)
        applyFocus(b, c)
        applyExposureAndShutter(b, c)
        applyWhiteBalance(b, c)
    }

    private fun applyZoom(b: CaptureRequest.Builder, c: CameraCharacteristics) {
        try {
            val maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
            val z = zoomRatio.coerceIn(1.0f, maxZoom)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (range != null) {
                    b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z.coerceIn(range.lower, range.upper))
                    return
                }
            }
            // 回退：SCALER_CROP_REGION 裁剪实现变焦
            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val cropW = (active.width() / z).toInt()
            val cropH = (active.height() / z).toInt()
            val x = (active.width() - cropW) / 2
            val y = (active.height() - cropH) / 2
            b.set(CaptureRequest.SCALER_CROP_REGION, Rect(x, y, x + cropW, y + cropH))
        } catch (e: Exception) {
            Log.w(TAG, "zoom不支持: ${e.message}")
        }
    }

    private fun applyFocus(b: CaptureRequest.Builder, c: CameraCharacteristics) {
        try {
            val minFocusDist = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val f = focusNormalized
            if (f != null && minFocusDist > 0f) {
                // focus: 1=远(无穷,0屈光度) 0=近(minFocusDist屈光度)，与iOS lensPosition一致
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, (1f - f) * minFocusDist)
            } else {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
        } catch (e: Exception) {
            Log.w(TAG, "focus不支持: ${e.message}")
        }
    }

    private fun applyExposureAndShutter(b: CaptureRequest.Builder, c: CameraCharacteristics) {
        try {
            val cj = shutterCjfps
            if (cj != null) {
                // 手动曝光：AE OFF + 曝光时长 + ISO
                val expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                var expNs = 1_000_000_000L / cj
                if (expRange != null) expNs = expNs.coerceIn(expRange.lower, expRange.upper)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                val iso = manualIso ?: isoRange?.let { (it.lower + it.upper) / 2 } ?: 400
                val safeIso = if (isoRange != null) iso.coerceIn(isoRange.lower, isoRange.upper) else iso
                b.set(CaptureRequest.SENSOR_SENSITIVITY, safeIso)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / targetFps)
            } else {
                // 自动曝光 + 曝光补偿
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                val ev = exposureEv
                if (ev != null) {
                    val step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    val range = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    if (step != null && range != null && step.toDouble() > 0) {
                        val steps = Math.round(ev / step.toFloat()).coerceIn(range.lower, range.upper)
                        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "exposure/shutter不支持: ${e.message}")
        }
    }

    private fun applyWhiteBalance(b: CaptureRequest.Builder, c: CameraCharacteristics) {
        try {
            val slider = whiteBalanceSlider
            if (slider != null) {
                // 手动色温：0冷(偏蓝) 100暖(偏红)，通过 COLOR_CORRECTION_GAINS 近似
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                val t = slider / 100f  // 0..1
                val rGain = 1.2f + t * 1.3f       // 暖->R增益大
                val bGain = 2.5f - t * 1.3f       // 暖->B增益小
                b.set(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    android.hardware.camera2.params.RggbChannelVector(rGain, 1.0f, 1.0f, bGain)
                )
            } else if (whiteBalanceLocked) {
                // 锁定当前白平衡（applyWhiteBalanceOnce）
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            } else {
                b.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            }
        } catch (e: Exception) {
            Log.w(TAG, "whiteBalance不支持: ${e.message}")
        }
    }
}
