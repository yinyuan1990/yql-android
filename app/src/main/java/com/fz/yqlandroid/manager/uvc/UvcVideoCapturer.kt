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
        // libuvc setPreviewSize 的帧率协商区间下限
        private const val MIN_FPS = 1

        /**
         * ⭐ 设备**没声明**每档 fps 时，向 libuvc 请求的帧率上限（探测用）。
         *
         * 死循环曾经是这样的：jiangdg 的 `getSupportedSizeList()` 在部分设备/版本上不填
         * `Size.fps`（实测日志里全是 `@0`，同一尺寸还会重复出现两条——那其实是两个不同帧率的
         * 帧描述符）→ PC 拿不到 fps 只能兜底 30 → 我们照着只请求 1~30 → libuvc 自然挑 30fps
         * 那个描述符 → 看起来就是"这摄像头只支持 30fps"。同一台设备插 Windows 能看到 120，
         * 因为 Windows 是直接读 UVC 描述符的。
         *
         * 所以设备没声明时就按这个上限往高了要，再用 [measuredFps] 实测拿到多少。
         * 取 60 而不是 120：推流侧编码器最高就 60（见 WebRTCManager.OTG_MAX_CAPTURE_FPS），
         * 采到 120 也推不出去，只是白发热。要放开改这一个数即可。
         */
        private const val PROBE_MAX_FPS = 60
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
    @Volatile private var badFrameCount = 0L

    // ⭐ [诊断] IFrameCallback 原始命中数（在任何 early-return 之前自增）——用于「开流后无帧」看门狗
    //   区分：native 根本没送帧（计数不涨）vs 送了但被我方丢弃（计数涨但 capFps=0）。
    @Volatile private var frameCbCount = 0L

    /** 当前协商档位的**实测**帧率（设备不声明 fps 时唯一的真实来源），随能力快照上报 PC */
    @Volatile private var measuredFps = 0

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

    /**
     * 记下"这个尺寸用 MJPEG 谈不拢"，下次切回来直接从 YUYV 起步。
     *
     * 实测（2026-07-26）：某些尺寸设备虽然列在 MJPEG 支持列表里，协商却必失败
     * （`Failed to set preview size` / `could not negotiate with camera:err=-51`），
     * 要等两次 2.5s 看门狗超时才降到 YUYV —— 切一次档位黑 7 秒。记住之后直接跳过。
     */
    private val mjpegBlacklist = mutableSetOf<String>()

    private fun sizeKey(w: Int, h: Int) = "${w}x$h"

    /** YUYV 在策略表里的起始下标（前面都是 MJPEG 档） */
    private fun firstYuyvIndex(): Int =
        strategies().indexOfFirst { it.format == UVCCamera.FRAME_FORMAT_YUYV }.coerceAtLeast(0)

    /** 按当前重试档位选 格式/带宽 开流；开流后装「无帧看门狗」，2.5s 没帧自动切下一档重开 */
    private fun startStreamLocked(camera: UVCCamera) {
        frameCbCount = 0
        measuredFps = 0        // 换档重测，别把上一档的实测值带过来
        val list = strategies()
        // 该尺寸已知 MJPEG 谈不拢 → 直接从 YUYV 起步，不再白等两轮看门狗
        if (noFrameRetry == 0 && mjpegBlacklist.contains(sizeKey(requestedWidth, requestedHeight))) {
            noFrameRetry = firstYuyvIndex()
            Log.d("meidui", "🔌 [OTG] ${requestedWidth}x${requestedHeight} 已知 MJPEG 谈不拢 → 直接用 YUYV 开流")
        }
        val st = list[minOf(noFrameRetry, list.size - 1)]
        try {
            val (w, h) = negotiateAndStart(camera, st.format, st.bandwidth)
            frameWidth = w
            frameHeight = h
            badFrameCount = 0
            bufferPool.clear()
            streamRunning = true
            Log.d("meidui", "🔌 [OTG] 开流策略=${st.name} 尺寸=${w}x${h}，${NO_FRAME_TIMEOUT_MS}ms后检查有无帧")
        } catch (e: Exception) {
            streamRunning = false
            Log.d("meidui", "🔌 [OTG] ❌ 开流失败(${st.name}): ${e.message}")
            if (st.format == UVCCamera.FRAME_FORMAT_MJPEG) {
                mjpegBlacklist.add(sizeKey(requestedWidth, requestedHeight))
            }
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
                // ⭐ 实测帧率：设备不声明 fps 时，这是唯一能拿到真实值的办法
                measuredFps = (frameCbCount * 1000 / NO_FRAME_TIMEOUT_MS).toInt()
                Log.d("meidui", "🔌 [OTG] ✅ IFrameCallback正常：${NO_FRAME_TIMEOUT_MS}ms内${frameCbCount}帧 " +
                        "${frameWidth}x${frameHeight} → 实测${measuredFps}fps")
                dumpCapabilitiesLocked(camera)   // 出帧确认后刷新能力快照（协商尺寸/当前值已最终定）
                return@Runnable
            }
            noFrameRetry++
            if (noFrameRetry >= strategies().size) {
                Log.d("meidui", "🔌 [OTG] ❌ 所有 格式/带宽 策略均0帧：该UVC设备与本机isoc/MJPEG不兼容，或OTG口供电不足（换带独立供电的OTG口再试）")
                return@Runnable
            }
            // 这一档 MJPEG 起流了却不出帧，同样拉黑：下次切回该尺寸直接走 YUYV
            if (strategies()[minOf(noFrameRetry - 1, strategies().size - 1)].format == UVCCamera.FRAME_FORMAT_MJPEG) {
                mjpegBlacklist.add(sizeKey(requestedWidth, requestedHeight))
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

        // 该档设备声明的 fps 上限（多数设备/版本这里是 null —— 见 PROBE_MAX_FPS 的说明）
        val declaredFps = try { target?.fps?.maxOrNull()?.toInt() ?: 0 } catch (_: Exception) { 0 }
        val wantMaxFps = if (declaredFps > 0) {
            minOf(maxOf(requestedFps, 1), declaredFps)     // 设备报了就按它的上限，别超
        } else {
            maxOf(requestedFps, PROBE_MAX_FPS)             // 设备没报 → 往高了要，实测能到多少算多少
        }

        camera.setFrameCallback(null, 0)
        try { camera.stopPreview() } catch (_: Exception) {}
        camera.setPreviewSize(w, h, MIN_FPS, wantMaxFps, frameFormat, bandwidth)
        // ⭐ 关键修复：挂哑预览窗口，否则 native startPreview 因无窗口不起流线程 → 无帧
        camera.setPreviewDisplay(ensureDummySurface(w, h))
        camera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
        camera.startPreview()
        val fmtName = if (frameFormat == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUYV"
        Log.d(TAG, "UVC开流: 请求${requestedWidth}x${requestedHeight}@${requestedFps} → 协商${w}x${h} format=$fmtName bw=$bandwidth")
        Log.d("meidui", "🔌 [OTG] 帧率协商区间=${MIN_FPS}~${wantMaxFps}fps" +
                (if (declaredFps > 0) "（设备声明该档上限${declaredFps}fps）"
                 else "（设备未声明该档fps → 按${PROBE_MAX_FPS}探测，实际到多少看下面的实测值）"))
        // ⭐ 第五十章：请求值与协商值不一致要看得见 —— PC 面板选了 160x120，
        //   但该格式的支持列表里没有这一档时，这里会就近落到别的尺寸（PC 显示的档位就"没生效"）。
        if (w != requestedWidth || h != requestedHeight) {
            Log.d("meidui", "🔌 [OTG] ⚠️ 协商偏移: 请求${requestedWidth}x${requestedHeight} → 实际${w}x${h}" +
                    "（$fmtName 支持列表里没有请求的那一档，已就近选取）")
        }
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
        defaultControlValues.clear()
        mjpegBlacklist.clear()
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
        val controls = mutableListOf<UvcCapabilityStore.Control>()
        val sizes = mutableListOf<UvcCapabilityStore.SizeOption>()
        try {
            camera.updateCameraParams()   // 读能力位掩码 + 各控制项的绝对 min/max（百分比映射的基础）
            lines += "OTG设备: ${currentDeviceName ?: "?"}"
            lines += "协商: ${frameWidth}x${frameHeight} (请求${requestedWidth}x${requestedHeight}@${requestedFps}fps 策略=${strategies()[minOf(noFrameRetry, strategies().size - 1)].name})" +
                    if (measuredFps > 0) " 实测${measuredFps}fps" else ""

            fun sizesOf(fmt: Int, name: String) {
                val s = try {
                    camera.getSupportedSizeList(fmt)?.filterIsInstance<com.jiangdg.utils.Size>()
                        ?.filter { it.width > 0 && it.height > 0 }
                } catch (_: Exception) { null }
                // 每档分辨率带 fps 上限（Size.fps 由 UVC 帧间隔描述符算出；null=设备没报，记 0）
                s?.forEach { sz ->
                    // 设备声明的 fps；没声明时，若这一档正是当前在跑的档位就用实测值填上
                    val declared = sz.fps?.maxOrNull()?.toInt() ?: 0
                    val maxFps = if (declared > 0) declared
                                 else if (sz.width == frameWidth && sz.height == frameHeight) measuredFps
                                 else 0
                    // 同尺寸两种格式都支持时只保留一条（取 fps 上限更高的），PC 档位列表按尺寸去重
                    val exist = sizes.indexOfFirst { it.width == sz.width && it.height == sz.height }
                    if (exist < 0) {
                        sizes += UvcCapabilityStore.SizeOption(sz.width, sz.height, maxFps)
                    } else if (maxFps > sizes[exist].maxFps) {
                        sizes[exist] = sizes[exist].copy(maxFps = maxFps)
                    }
                }
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
            fun pct(key: String, name: String, flag: Int, get: () -> Int) {
                val ok = sup(flag)
                val cur = if (ok) (try { get() } catch (_: Exception) { -1 }) else -1
                controls += UvcCapabilityStore.Control(key, name, "pct", ok, cur)
                lines += if (ok) "$name($key): ✅ 0~100% 当前=${cur}%" else "$name($key): ✗不支持"
            }
            fun bool(key: String, name: String, flag: Int, get: () -> Boolean?) {
                val ok = sup(flag)
                val cur = if (ok) (try { get() } catch (_: Exception) { null }) else null
                controls += UvcCapabilityStore.Control(key, name, "bool", ok, if (cur == true) 1 else 0, 0, 1)
                lines += if (ok) "$name($key): ✅ 当前=${if (cur == true) "开" else "关"}" else "$name($key): ✗不支持"
            }
            pct("zoom", "变焦", UVCCamera.CTRL_ZOOM_ABS) { camera.zoom }
            bool("autoFocus", "自动对焦AF", UVCCamera.CTRL_FOCUS_AUTO) { camera.autoFocus }
            pct("focus", "手动对焦", UVCCamera.CTRL_FOCUS_ABS) { camera.focus }
            bool("autoWhiteBalance", "自动白平衡AWB", UVCCamera.PU_WB_TEMP_AUTO) { camera.autoWhiteBlance }
            pct("whiteBalance", "白平衡色温", UVCCamera.PU_WB_TEMP) { camera.whiteBlance }
            pct("brightness", "亮度", UVCCamera.PU_BRIGHTNESS) { camera.brightness }
            pct("contrast", "对比度", UVCCamera.PU_CONTRAST) { camera.contrast }
            pct("saturation", "饱和度", UVCCamera.PU_SATURATION) { camera.saturation }
            pct("hue", "色调", UVCCamera.PU_HUE) { camera.hue }
            pct("sharpness", "锐度", UVCCamera.PU_SHARPNESS) { camera.sharpness }
            pct("gamma", "伽马", UVCCamera.PU_GAMMA) { camera.gamma }
            pct("gain", "增益", UVCCamera.PU_GAIN) { camera.gain }
            val plOk = sup(UVCCamera.PU_POWER_LF)
            controls += UvcCapabilityStore.Control(
                "powerline", "抗频闪", "enum", plOk,
                cur = if (plOk) (readPowerline(camera) ?: 0) else 0,
                min = 0, max = 2, options = listOf("关", "50Hz", "60Hz")
            )
            lines += if (plOk) "抗频闪(powerline): ✅ 0=关/1=50Hz/2=60Hz" else "抗频闪(powerline): ✗不支持"
            // 与自带摄像头面板的差异项（PC 面板据此不渲染对应控件）
            lines += if (sup(UVCCamera.CTRL_AE_ABS)) "快门cjfps: ✗设备支持曝光时间但库无接口" else "快门cjfps: ✗不支持"
            lines += "前后摄direction: ✗OTG不适用 | 推送fps/码率/档位(按分辨率): ✅走 otg_ 独立通道"
        } catch (e: Exception) {
            lines += "能力枚举失败: ${e.message}"
        }
        // 每档分辨率的码率上限：以"枚举出的最大分辨率 = 现有最高档码率"为锚，按像素率等比算
        // 同时标出硬件编码器吃不下的档位（低于最小分辨率的选了必黑，PC 面板不给选）
        val codec = com.fz.yqlandroid.manager.H265Support.effectiveCodec
        val sizesWithKbps = OtgBitratePlan.annotate(sizes).map {
            it.copy(encodable = EncoderSizeLimits.isEncodable(codec, it.width, it.height))
        }
        lines += EncoderSizeLimits.describe(codec)
        sizesWithKbps.forEach {
            lines += "  码率上限 ${it.width}x${it.height}@${it.maxFps} → ${it.maxKbps}kbps" +
                    if (it.encodable) "" else "  ⛔编码器不支持此尺寸(选了必黑，已屏蔽)"
        }
        val caps = UvcCapabilityStore.Caps(
            deviceName = currentDeviceName ?: "",
            width = frameWidth,
            height = frameHeight,
            sizes = sizesWithKbps,
            controls = controls,
            version = System.currentTimeMillis()
        )
        // 首次枚举（相机刚打开）时把各项当前值记为出厂缺省，供「还原」回落
        if (defaultControlValues.isEmpty()) {
            controls.filter { it.supported && it.cur >= 0 }.forEach { defaultControlValues[it.key] = it.cur }
            if (defaultControlValues.isNotEmpty()) {
                Log.d("meidui", "🔌 [OTG] 已记录出厂缺省: " +
                        defaultControlValues.entries.joinToString(" ") { "${it.key}=${it.value}" })
            }
        }
        UvcCapabilityStore.set(lines, caps)
        lines.forEach { Log.d("meidui", "🔌 [OTG能力] $it") }
        // 能力一变就主动推给 PC（PC 据此重建面板），不等 PC 来问
        try { onCapsUpdated?.invoke() } catch (_: Exception) {}
    }

    /** 能力快照刷新回调 —— WebRTCManager 挂上去，用于主动上报 PC */
    @Volatile var onCapsUpdated: (() -> Unit)? = null

    /**
     * 相机刚打开时各控制项的原始值 = 该设备的**出厂缺省**，「还原」就回落到这里。
     * 比 PC 侧猜一个"中间值"发下来靠谱：每台 UVC 设备的缺省点位都不一样（有的亮度缺省 50%、
     * 有的 32%），猜错就等于把画面调坏。相机关闭时清空。
     */
    private val defaultControlValues = mutableMapOf<String, Int>()

    /** ⭐ 第五十章：「还原」—— 把所有支持的硬件项回落到开机时记下的出厂缺省，然后重推能力快照 */
    fun resetControlsToDefault() {
        val caps = UvcCapabilityStore.caps.value
        if (uvcCamera == null || caps == null) {
            Log.d("meidui", "🔌 [OTG还原] 忽略：相机未打开或无能力快照")
            return
        }
        var n = 0
        caps.controls.filter { it.supported }.forEach { c ->
            val def = defaultControlValues[c.key] ?: return@forEach
            if (applyControl(c.key, def)) n++
        }
        Log.d("meidui", "🔌 [OTG还原] 已回落 $n 项到出厂缺省")
        // 等下发落地后重新枚举一次，PC 面板的滑条随 controls[].cur 归位
        uvcHandler?.postDelayed({ uvcCamera?.let { dumpCapabilitiesLocked(it) } }, 300)
    }

    /**
     * ⭐ 第五十章：应用 PC 经 `otg_ctrl` 下发的 UVC 硬件控制项。
     *
     * 值域与能力快照一致：pct 类 0~100 百分比（库内部映射到设备绝对 min~max）、
     * bool 类 0/1、powerline 0/1/2。native 调用一律回 uvcThread。
     * 设备不支持的项 PC 面板本就不渲染，这里按能力位再挡一次（双保险）。
     *
     * @return true=已排进下发队列；false=无相机/不支持
     */
    fun applyControl(key: String, value: Int): Boolean {
        val camera = uvcCamera ?: run {
            Log.d("meidui", "🔌 [OTG控制] $key=$value 忽略：UVC相机未打开")
            return false
        }
        val supported = UvcCapabilityStore.caps.value
            ?.controls?.firstOrNull { it.key == key }?.supported ?: false
        if (!supported) {
            Log.d("meidui", "🔌 [OTG控制] $key=$value 忽略：该设备不支持此项")
            return false
        }
        uvcHandler?.post {
            try {
                when (key) {
                    "zoom" -> camera.zoom = value
                    "focus" -> camera.focus = value
                    "autoFocus" -> camera.autoFocus = value != 0
                    "autoWhiteBalance" -> camera.autoWhiteBlance = value != 0
                    "whiteBalance" -> camera.whiteBlance = value
                    "brightness" -> camera.brightness = value
                    "contrast" -> camera.contrast = value
                    "saturation" -> camera.saturation = value
                    "hue" -> camera.hue = value
                    "sharpness" -> camera.sharpness = value
                    "gamma" -> camera.gamma = value
                    "gain" -> camera.gain = value
                    "powerline" -> writePowerline(camera, value)
                    else -> {
                        Log.d("meidui", "🔌 [OTG控制] 未知控制项 $key，忽略")
                        return@post
                    }
                }
                Log.d("meidui", "🔌 [OTG控制] ✅ $key=$value 已下发")
            } catch (e: Exception) {
                Log.d("meidui", "🔌 [OTG控制] ❌ $key=$value 失败: ${e.message}")
            }
        }
        return true
    }

    // 抗频闪读写走反射：该接口在 libuvc 各分支命名/存在与否不统一，用反射避免编译期绑死
    private fun readPowerline(camera: UVCCamera): Int? = try {
        camera.javaClass.getMethod("getPowerlineFrequency").invoke(camera) as? Int
    } catch (_: Throwable) { null }

    private fun writePowerline(camera: UVCCamera, value: Int) {
        try {
            camera.javaClass.getMethod("setPowerlineFrequency", Int::class.javaPrimitiveType)
                .invoke(camera, value)
        } catch (t: Throwable) {
            Log.d("meidui", "🔌 [OTG控制] powerline 该库版本无接口: ${t.message}")
        }
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
            // ⭐ 第五十章：这里原来只打一次日志就永久静默——一旦尺寸持续对不上，
            //   帧就被全部丢掉、画面永远出不来，而日志里只有孤零零一行，没法定位。
            //   现在每 60 帧打一次，并反推 native 实际给的分辨率（按 NV21 1.5 字节/像素猜同宽高比的解），
            //   小分辨率黑屏就是靠这行定性。
            badFrameCount++
            if (badFrameCount % 60 == 1L) {
                val remain = frame.remaining()
                val guessPixels = remain * 2 / 3
                Log.d("meidui", "🔌 [OTG] ⚠️ 帧尺寸不符#$badFrameCount: native给了 ${remain}字节" +
                        "(≈${guessPixels}像素)，我方按协商值 ${w}x${h} 期望 ${expected}字节 → 全部丢弃(画面必黑)")
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
