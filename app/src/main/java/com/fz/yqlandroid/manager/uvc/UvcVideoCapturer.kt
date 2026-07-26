package com.fz.yqlandroid.manager.uvc

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.usb.UsbDevice
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.IFrameCallback
import com.jiangdg.uvc.UVCCamera
import android.view.Surface
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
        // 开流后多久没帧就切下一策略（ms）
        private const val NO_FRAME_TIMEOUT_MS = 2500L
    }

    // ⭐ 开流策略：native「开流成功但无帧」时依次降级重试（不同摄像头/OTG供电下兼容性差异大）
    private data class StreamStrategy(val format: Int, val bandwidth: Float, val name: String)
    private fun strategies() = listOf(
        StreamStrategy(UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.DEFAULT_BANDWIDTH, "MJPEG@1.0"),
        StreamStrategy(UVCCamera.FRAME_FORMAT_MJPEG, 0.5f, "MJPEG@0.5"),
        StreamStrategy(UVCCamera.FRAME_FORMAT_YUYV, UVCCamera.DEFAULT_BANDWIDTH, "YUYV@1.0"),
        StreamStrategy(UVCCamera.FRAME_FORMAT_YUYV, 0.3f, "YUYV@0.3")
    )
    @Volatile private var noFrameRetry = 0
    private var noFrameWatchdog: Runnable? = null

    private val appContext = context.applicationContext

    private var observer: CapturerObserver? = null

    // 打开/关闭 UVC 相机的专用线程（native open/negotiate 可阻塞数百 ms，不能占主线程）
    private var uvcThread: HandlerThread? = null
    private var uvcHandler: Handler? = null

    private var uvcCamera: UVCCamera? = null

    // ⭐ 哑预览窗口：jiangdg libuvc 的 startPreview 必须有预览窗口(Surface)才起取流线程，
    //   否则 native 报 "window does not exist" 且 IFrameCallback 永不触发（capFps=0）。
    //   用 ImageReader 造一个自动排空的窗口占位（我们不读它的内容，帧走 IFrameCallback）。
    private var dummyReader: ImageReader? = null
    private var dummyReaderW = 0
    private var dummyReaderH = 0

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

    // ⭐ [诊断] IFrameCallback 原始命中数（在任何 early-return 之前自增）——用于「开流后无帧」看门狗
    //   区分：native 根本没送帧（计数不涨）vs 送了但被我方丢弃（计数涨但 capFps=0）。
    @Volatile private var frameCbCount = 0L

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
                noFrameRetry = 0   // 新的格式请求：从最优策略重新开始降级
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
            currentDeviceName = device.productName ?: device.deviceName
            startStreamLocked(camera)
            dumpCapabilitiesLocked(camera)   // ⭐ 能力枚举 → 画面层叠显 + OTG日志
            Log.d("meidui", "🔌 [OTG] ✅ UVC相机已开流: ${device.productName ?: device.deviceName} → ${frameWidth}x${frameHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "打开UVC相机失败: ${e.message}")
            Log.d("meidui", "🔌 [OTG] ❌ 打开UVC相机失败: ${e.message}")
            try { uvcCamera?.destroy() } catch (_: Exception) {}
            uvcCamera = null
        }
    }

    /** 按当前重试档位选 格式/带宽 开流；开流后装「无帧看门狗」，2.5s 没帧自动切下一档重开 */
    private fun startStreamLocked(camera: UVCCamera) {
        frameCbCount = 0
        val list = strategies()
        val st = list[minOf(noFrameRetry, list.size - 1)]
        try {
            val (w, h) = negotiateAndStart(camera, st.format, st.bandwidth)
            frameWidth = w
            frameHeight = h
            badFrameLogged = false
            bufferPool.clear()
            streamRunning = true
            Log.d("meidui", "🔌 [OTG] 开流策略=${st.name} 尺寸=${w}x${h}，${NO_FRAME_TIMEOUT_MS}ms后检查有无帧")
        } catch (e: Exception) {
            streamRunning = false
            Log.d("meidui", "🔌 [OTG] ❌ 开流失败(${st.name}): ${e.message}")
        }
        scheduleNoFrameWatchdog(camera)   // 无论成功/失败都装看门狗：失败或0帧都会切下一档
    }

    /** 开流后 2.5s 检查 IFrameCallback 是否真的在吐帧；0 帧则切下一策略重开（有限次） */
    private fun scheduleNoFrameWatchdog(camera: UVCCamera) {
        val handler = uvcHandler ?: return
        noFrameWatchdog?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (!capturing || uvcCamera !== camera) return@Runnable
            if (frameCbCount > 0) {
                noFrameRetry = 0   // 成功出帧：重置档位，后续切档/重开从最优策略开始
                Log.d("meidui", "🔌 [OTG] ✅ IFrameCallback正常：${NO_FRAME_TIMEOUT_MS}ms内${frameCbCount}帧 ${frameWidth}x${frameHeight}")
                dumpCapabilitiesLocked(camera)   // 出帧确认后刷新能力快照（协商尺寸/当前值已最终定）
                return@Runnable
            }
            noFrameRetry++
            if (noFrameRetry >= strategies().size) {
                Log.d("meidui", "🔌 [OTG] ❌ 所有 格式/带宽 策略均0帧：该UVC设备与本机isoc/MJPEG不兼容，或OTG口供电不足（换带独立供电的OTG口再试）")
                return@Runnable
            }
            Log.d("meidui", "🔌 [OTG] ⚠️ 开流后${NO_FRAME_TIMEOUT_MS}ms内0帧(native未回调IFrameCallback) → 切换到策略[${noFrameRetry}]重开")
            try {
                stopStreamLocked(camera)
                startStreamLocked(camera)
            } catch (e: Exception) {
                Log.d("meidui", "🔌 [OTG] 切策略重开失败: ${e.message}")
            }
        }
        noFrameWatchdog = r
        handler.postDelayed(r, NO_FRAME_TIMEOUT_MS)
    }

    /** 造/复用一个自动排空的 ImageReader 哑预览窗口（尺寸变了才重建）。必须持续排空，
     *  否则窗口缓冲队列满 → native 取流线程在绘制处阻塞 → 连带停发 IFrameCallback。 */
    private fun ensureDummySurface(w: Int, h: Int): Surface {
        val cur = dummyReader
        if (cur != null && dummyReaderW == w && dummyReaderH == h) return cur.surface
        releaseDummySurface()
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            try { r.acquireLatestImage()?.close() } catch (_: Exception) {}
        }, uvcHandler)
        dummyReader = reader
        dummyReaderW = w
        dummyReaderH = h
        return reader.surface
    }

    private fun releaseDummySurface() {
        try { dummyReader?.close() } catch (_: Exception) {}
        dummyReader = null
        dummyReaderW = 0
        dummyReaderH = 0
    }

    private fun negotiateAndStart(camera: UVCCamera, frameFormat: Int, bandwidth: Float): Pair<Int, Int> {
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
        camera.setPreviewSize(w, h, MIN_FPS, maxOf(30, requestedFps), frameFormat, bandwidth)
        // ⭐ 关键修复：挂哑预览窗口，否则 native startPreview 因无窗口不起流线程 → 无帧
        camera.setPreviewDisplay(ensureDummySurface(w, h))
        camera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
        camera.startPreview()
        Log.d(TAG, "UVC开流: 请求${requestedWidth}x${requestedHeight}@${requestedFps} → 协商${w}x${h} format=${if (frameFormat == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUYV"} bw=$bandwidth")
        return w to h
    }

    private fun stopStreamLocked(camera: UVCCamera) {
        streamRunning = false
        noFrameWatchdog?.let { uvcHandler?.removeCallbacks(it) }
        noFrameWatchdog = null
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
        releaseDummySurface()
        UvcCapabilityStore.clear()
        Log.d("meidui", "🔌 [OTG] UVC相机已关闭")
    }

    // MARK: - ⭐ 能力枚举（第四十八章：OTG 可调参数/上下限 → 画面层叠显 + OTG 日志，供 PC 调节面板改造对照）

    @Volatile private var currentDeviceName: String? = null

    /**
     * 枚举该 UVC 设备的软/硬件可调能力并发布：
     * - 软件侧：MJPEG/YUYV 各自支持的分辨率列表（PC「档位」= 就近协商到这些尺寸）
     * - 硬件侧：jiangdg libuvc 暴露的控制项，统一 **百分比 0~100**（库内部映射到设备各自的绝对 min~max），
     *   是否支持由 UVC 能力位掩码（checkSupportFlag）决定，逐台设备不同。
     * - 明确不适用项：前后摄 direction、快门 cjfps（库无曝光时间接口）。
     * 必须在 uvcThread 上调（updateCameraParams/getXxx 走 native）。
     */
    private fun dumpCapabilitiesLocked(camera: UVCCamera) {
        val lines = mutableListOf<String>()
        try {
            camera.updateCameraParams()   // 读能力位掩码 + 各控制项的绝对 min/max（百分比映射的基础）
            lines += "OTG设备: ${currentDeviceName ?: "?"}"
            lines += "协商: ${frameWidth}x${frameHeight} (请求${requestedWidth}x${requestedHeight}@${requestedFps}fps 策略=${strategies()[minOf(noFrameRetry, strategies().size - 1)].name})"

            fun sizesOf(fmt: Int, name: String) {
                val s = try {
                    camera.getSupportedSizeList(fmt)?.filterIsInstance<com.jiangdg.utils.Size>()
                        ?.filter { it.width > 0 && it.height > 0 }
                } catch (_: Exception) { null }
                // 每档分辨率带 fps 上限（Size.fps 由 UVC 帧间隔描述符算出；null=设备没报，省略）
                lines += if (!s.isNullOrEmpty())
                    "$name(${s.size}): " + s.joinToString(" ") { sz ->
                        val maxFps = sz.fps?.maxOrNull()?.toInt() ?: 0
                        if (maxFps > 0) "${sz.width}x${sz.height}@$maxFps" else "${sz.width}x${sz.height}"
                    }
                else "$name: 无"
            }
            sizesOf(UVCCamera.FRAME_FORMAT_MJPEG, "MJPEG分辨率")
            sizesOf(UVCCamera.FRAME_FORMAT_YUYV, "YUYV分辨率")

            fun sup(flag: Int): Boolean = try { camera.checkSupportFlag(flag.toLong()) } catch (_: Exception) { false }
            fun pct(name: String, flag: Int, get: () -> Int) {
                lines += if (sup(flag)) {
                    val cur = try { get() } catch (_: Exception) { -1 }
                    "$name: ✅ 0~100% 当前=${cur}%"
                } else "$name: ✗不支持"
            }
            pct("变焦zoom", UVCCamera.CTRL_ZOOM_ABS) { camera.zoom }
            lines += if (sup(UVCCamera.CTRL_FOCUS_AUTO)) {
                val af = try { camera.autoFocus } catch (_: Exception) { null }
                "自动对焦AF: ✅ 当前=${if (af == true) "开" else "关"}"
            } else "自动对焦AF: ✗不支持"
            pct("手动对焦focus", UVCCamera.CTRL_FOCUS_ABS) { camera.focus }
            lines += if (sup(UVCCamera.PU_WB_TEMP_AUTO)) {
                val awb = try { camera.autoWhiteBlance } catch (_: Exception) { null }
                "自动白平衡AWB: ✅ 当前=${if (awb == true) "开" else "关"}"
            } else "自动白平衡AWB: ✗不支持"
            pct("白平衡色温wb", UVCCamera.PU_WB_TEMP) { camera.whiteBlance }
            pct("亮度brightness", UVCCamera.PU_BRIGHTNESS) { camera.brightness }
            pct("对比度contrast", UVCCamera.PU_CONTRAST) { camera.contrast }
            pct("饱和度saturation", UVCCamera.PU_SATURATION) { camera.saturation }
            pct("色调hue", UVCCamera.PU_HUE) { camera.hue }
            pct("锐度sharpness", UVCCamera.PU_SHARPNESS) { camera.sharpness }
            pct("伽马gamma", UVCCamera.PU_GAMMA) { camera.gamma }
            pct("增益gain", UVCCamera.PU_GAIN) { camera.gain }
            lines += if (sup(UVCCamera.PU_POWER_LF)) "抗频闪powerline: ✅ 0=关/1=50Hz/2=60Hz" else "抗频闪powerline: ✗不支持"
            // 与自带摄像头面板的差异项（PC 面板改造要点）
            lines += if (sup(UVCCamera.CTRL_AE_ABS)) "快门cjfps: ✗设备支持曝光时间但库无接口" else "快门cjfps: ✗不支持"
            lines += "前后摄direction: ✗OTG不适用 | 推送fps/码率bitrate/档位type: ✅软件侧照常"
        } catch (e: Exception) {
            lines += "能力枚举失败: ${e.message}"
        }
        UvcCapabilityStore.set(lines)
        lines.forEach { Log.d("meidui", "🔌 [OTG能力] $it") }
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
        frameCbCount++   // ⭐ 在任何 early-return 之前自增：看门狗据此判断 native 是否真在吐帧
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
