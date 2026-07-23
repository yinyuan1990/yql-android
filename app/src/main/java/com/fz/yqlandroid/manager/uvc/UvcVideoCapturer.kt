package com.fz.yqlandroid.manager.uvc

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.IFrameCallback
import com.jiangdg.uvc.UVCCamera
import org.webrtc.CapturerObserver
import org.webrtc.NV21Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ⭐ 外接 OTG 摄像头（UVC）采集器 —— 第四十八章，与自带摄像头链路完全分离。
 *
 * 实现 [org.webrtc.VideoCapturer] 接口：WebRTCManager 只需在创建 capturer 处二选一，
 * SurfaceTextureHelper/CountingObserver/videoSource/编码器/SRS/P2P 推流链路全部原样复用。
 *
 * 帧路径（CPU NV21，参考 saki/OpenCVwithUVC 同款）：
 *   libuvc 协商 MJPEG（USB2.0 带宽下 1080p30 必须 MJPEG，libuvc 内置 jpeg-turbo 解码）
 *   → IFrameCallback(NV21 直存 ByteBuffer) → 复制进池化 byte[]
 *   → [NV21Buffer] → capturerObserver.onFrameCaptured()（libwebrtc 编码前自动转 I420）。
 *
 * 生命周期：startCapture 启动 [UvcDeviceMonitor]，设备授权后自动开流；
 * 推流中拔线 → 停帧（左上角「采集0」可见），插回自动恢复；stopCapture/dispose 反向拆除。
 */
class UvcVideoCapturer(context: Context) : VideoCapturer, UvcDeviceMonitor.Listener {

    companion object {
        private const val TAG = "UvcVideoCapturer"
        // libuvc setPreviewSize 的帧率协商区间下限（上限取 max(30, 请求fps)）
        private const val MIN_FPS = 1
    }

    private val appContext = context.applicationContext

    private var observer: CapturerObserver? = null

    // 打开/关闭 UVC 相机的专用线程（native open/negotiate 可阻塞数百 ms，不能占主线程）
    private var uvcThread: HandlerThread? = null
    private var uvcHandler: Handler? = null

    private var uvcCamera: UVCCamera? = null

    // WebRTC 请求的目标格式（UVC 侧就近协商）
    @Volatile private var requestedWidth = 1280
    @Volatile private var requestedHeight = 720
    @Volatile private var requestedFps = 30

    // UVC 实际协商出的帧格式（NV21Buffer 必须用这个尺寸）
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0

    @Volatile private var capturing = false        // startCapture ~ stopCapture 区间
    @Volatile private var streamRunning = false    // UVC 相机已开流

    // NV21 字节数组复用池（1080p30 每秒 ~45MB，若每帧新分配 GC 压力巨大）
    private val bufferPool = ArrayBlockingQueue<ByteArray>(4)
    @Volatile private var badFrameLogged = false

    // MARK: - VideoCapturer 接口

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: Context?,
        capturerObserver: CapturerObserver?
    ) {
        // CPU NV21 路径不使用 SurfaceTextureHelper（保留参数以兼容 WebRTCManager 现有调用）
        this.observer = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        requestedWidth = width
        requestedHeight = height
        requestedFps = framerate
        capturing = true
        if (uvcThread == null) {
            uvcThread = HandlerThread("UvcCaptureThread").apply { start() }
            uvcHandler = Handler(uvcThread!!.looper)
        }
        Log.d("meidui", "🔌 [OTG] startCapture 请求=${width}x${height}@${framerate}fps")
        observer?.onCapturerStarted(true)
        UvcDeviceMonitor.addListener(this)
        UvcDeviceMonitor.start(appContext)
        // 已有授权设备（重启采集/切档场景）直接开流，无需等广播
        UvcDeviceMonitor.currentReady()?.let { (device, ctrl) ->
            uvcHandler?.post { openCameraLocked(device, ctrl) }
        }
    }

    override fun stopCapture() {
        capturing = false
        UvcDeviceMonitor.removeListener(this)
        runOnUvcThreadBlocking { closeCameraLocked() }
        observer?.onCapturerStopped()
        Log.d("meidui", "🔌 [OTG] stopCapture 完成")
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        requestedWidth = width
        requestedHeight = height
        requestedFps = framerate
        if (!capturing) return
        Log.d("meidui", "🔌 [OTG] changeCaptureFormat → ${width}x${height}@${framerate}fps（重开UVC流就近协商）")
        uvcHandler?.post {
            val cam = uvcCamera ?: return@post
            try {
                stopStreamLocked(cam)
                startStreamLocked(cam)
            } catch (e: Exception) {
                Log.e(TAG, "changeCaptureFormat失败: ${e.message}")
                Log.d("meidui", "🔌 [OTG] ❌ changeCaptureFormat失败: ${e.message}")
            }
        }
    }

    override fun dispose() {
        capturing = false
        UvcDeviceMonitor.removeListener(this)
        runOnUvcThreadBlocking { closeCameraLocked() }
        UvcDeviceMonitor.stop()
        uvcThread?.quitSafely()
        uvcThread = null
        uvcHandler = null
        observer = null
    }

    override fun isScreencast(): Boolean = false

    // MARK: - UvcDeviceMonitor.Listener

    override fun onUvcDeviceReady(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        if (!capturing) return
        uvcHandler?.post { openCameraLocked(device, ctrlBlock) }
    }

    override fun onUvcDeviceGone(device: UsbDevice) {
        // 拔线：停帧关相机（monitor 仍注册，插回 onUvcDeviceReady 自动恢复）
        uvcHandler?.post { closeCameraLocked() }
    }

    // MARK: - UVC 相机开关（一律在 uvcThread 上执行）

    private fun openCameraLocked(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        if (!capturing) return
        if (uvcCamera != null) {
            Log.d(TAG, "UVC相机已打开，忽略重复open")
            return
        }
        try {
            val camera = UVCCamera()
            camera.open(ctrlBlock)
            uvcCamera = camera
            startStreamLocked(camera)
            Log.d("meidui", "🔌 [OTG] ✅ UVC相机已开流: ${device.productName ?: device.deviceName} → ${frameWidth}x${frameHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "打开UVC相机失败: ${e.message}")
            Log.d("meidui", "🔌 [OTG] ❌ 打开UVC相机失败: ${e.message}")
            try { uvcCamera?.destroy() } catch (_: Exception) {}
            uvcCamera = null
        }
    }

    /** 就近选一个 UVC 支持的分辨率并开流（优先 MJPEG，失败回退 YUYV） */
    private fun startStreamLocked(camera: UVCCamera) {
        val negotiated = try {
            negotiateAndStart(camera, UVCCamera.FRAME_FORMAT_MJPEG)
        } catch (e: Exception) {
            Log.w(TAG, "MJPEG 开流失败(${e.message})，回退 YUYV")
            Log.d("meidui", "🔌 [OTG] MJPEG开流失败(${e.message})，回退YUYV（USB2.0下YUYV高分辨率帧率会很低）")
            negotiateAndStart(camera, UVCCamera.FRAME_FORMAT_YUYV)
        }
        frameWidth = negotiated.first
        frameHeight = negotiated.second
        badFrameLogged = false
        bufferPool.clear()
        streamRunning = true
    }

    private fun negotiateAndStart(camera: UVCCamera, frameFormat: Int): Pair<Int, Int> {
        // 该格式支持的分辨率里选与请求值最接近的（按面积差 + 宽高比差）
        val sizes = try {
            camera.getSupportedSizeList(frameFormat)?.filterIsInstance<com.jiangdg.utils.Size>()
        } catch (_: Exception) { null }
        val target = sizes
            ?.filter { it.width > 0 && it.height > 0 }
            ?.minByOrNull { s ->
                val areaDiff = Math.abs(s.width.toLong() * s.height - requestedWidth.toLong() * requestedHeight)
                val ratioDiff = Math.abs(s.width.toFloat() / s.height - requestedWidth.toFloat() / requestedHeight)
                areaDiff + (ratioDiff * 1_000_000).toLong()
            }
        val w = target?.width ?: requestedWidth
        val h = target?.height ?: requestedHeight
        camera.setFrameCallback(null, 0)
        try { camera.stopPreview() } catch (_: Exception) {}
        camera.setPreviewSize(w, h, MIN_FPS, maxOf(30, requestedFps), frameFormat, UVCCamera.DEFAULT_BANDWIDTH)
        camera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
        camera.startPreview()
        Log.d(TAG, "UVC开流: 请求${requestedWidth}x${requestedHeight}@${requestedFps} → 协商${w}x${h} format=${if (frameFormat == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUYV"}")
        return w to h
    }

    private fun stopStreamLocked(camera: UVCCamera) {
        streamRunning = false
        try { camera.setFrameCallback(null, 0) } catch (_: Exception) {}
        try { camera.stopPreview() } catch (_: Exception) {}
    }

    private fun closeCameraLocked() {
        val camera = uvcCamera ?: return
        stopStreamLocked(camera)
        try { camera.destroy() } catch (e: Exception) {
            Log.w(TAG, "destroy UVC camera failed: ${e.message}")
        }
        uvcCamera = null
        Log.d("meidui", "🔌 [OTG] UVC相机已关闭")
    }

    /** 在 uvc 线程同步执行（stopCapture/dispose 要求返回前帧已停） */
    private fun runOnUvcThreadBlocking(block: () -> Unit) {
        val handler = uvcHandler ?: run { block(); return }
        if (Looper.myLooper() == handler.looper) { block(); return }
        val latch = CountDownLatch(1)
        handler.post {
            try { block() } finally { latch.countDown() }
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    // MARK: - 帧回调（native 采集线程调用）

    private val frameCallback = IFrameCallback { frame ->
        val obs = observer ?: return@IFrameCallback
        if (!capturing || !streamRunning) return@IFrameCallback
        val w = frameWidth
        val h = frameHeight
        val expected = w * h * 3 / 2
        if (w <= 0 || h <= 0 || frame.remaining() < expected) {
            if (!badFrameLogged) {
                badFrameLogged = true
                Log.d("meidui", "🔌 [OTG] ⚠️ 帧尺寸异常: remaining=${frame.remaining()} expected=$expected (${w}x${h})，丢弃")
            }
            return@IFrameCallback
        }
        // 池化复用：NV21Buffer 零拷贝持有 byte[]，webrtc 用完（编码/预览后）release 归还
        val pooled = bufferPool.poll()
        val data = if (pooled != null && pooled.size == expected) pooled else ByteArray(expected)
        frame.get(data, 0, expected)
        val nv21 = NV21Buffer(data, w, h) { bufferPool.offer(data) }
        val ts = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime())
        val videoFrame = VideoFrame(nv21, 0, ts)
        obs.onFrameCaptured(videoFrame)
        videoFrame.release()
    }
}
