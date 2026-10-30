package com.fz.yqlandroid.manager.srtdirect

import android.content.Context
import android.util.Size
import com.fz.yqlandroid.manager.Camera2ParamApplier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/**
 * §134 SRT直推媒体引擎（test-android 路线）：
 * Camera2 TEMPLATE_RECORD 直写 MediaCodec 输入 Surface（+ 可见时 SurfaceView 预览）→ H.264 → TS → SRT → MediaMTX。
 *
 * 生命周期：[startCamera]（预览/编码就绪，未连 SRT 时编码输出直接丢）→ [startSender]（连 MediaMTX）→
 * [stopSender] / [stopCamera]。换分辨率/帧率/GOP/QP = 新编码器 → 会话换 Surface → 停旧编码器，SRT 不断。
 * 所有改动在单线程 [worker] 上串行执行；[tick] 每秒由统计循环调用。
 */
internal class SrtDirectEngine(private val context: Context, private val host: Host) {

    interface Host {
        /** 连接线程调用：生成本次连接的地址/延迟/streamid（可阻塞取 token）；null=本次不连 */
        fun srtTarget(attempt: Int, plan: SrtDirectParams.Plan): SdSrtSender.Target?
        /** 某次连接的首个关键帧已发出（编码线程） */
        fun onSrtLive(attempt: Int)
        fun onSrtLost(reason: String)
        fun onCameraError(msg: String)
        fun onCameraFirstFrame()
    }

    /** 期望的视频参数（WebRTCManager 按档位/清晰度/推送帧率算好给进来） */
    data class VideoReq(
        val plan: SrtDirectParams.Plan,
        val fps: Int,
        /** 目标码率（档位码率 × 清晰度%），峰值控制器在此基础上再乘系数 */
        val kbps: Int,
    )

    /** 每秒快照（上报 CONFIG_STATE / UI 用） */
    data class Snapshot(
        val connected: Boolean,
        val live: Boolean,
        val encKbps: Int,
        val encFps: Int,
        val capFps: Int,
        val rttMs: Int,
        val lossRate: Double,
        val line: String,
    )

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sd-engine") }

    /** 引擎线程串行执行；release 之后的迟到回调直接丢弃 */
    private fun post(block: () -> Unit) {
        try { worker.execute(block) } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }
    private val muxer = SdTsMuxer()
    private val peak = SdPeakController()

    @Volatile private var camera: SdCameraSource? = null
    @Volatile private var encoder: SdH264Encoder? = null
    @Volatile private var sender: SdSrtSender? = null
    @Volatile private var req: VideoReq? = null
    @Volatile private var controls = Camera2ParamApplier.Params()
    @Volatile private var front = false
    @Volatile var cameraRunning = false
        private set

    private val _previewSize = MutableStateFlow<Size?>(null)
    /** 屏幕预览要固定的 SurfaceHolder 尺寸（横向缓冲；竖屏界面按 高:宽 显示） */
    val previewSize: StateFlow<Size?> = _previewSize

    /** 实际编码尺寸 */
    @Volatile var encodeWidth = 0
        private set
    @Volatile var encodeHeight = 0
        private set
    val encoderDesc: String get() = encoder?.let { "${it.codecName} 降级档${it.tierName}" } ?: "-"

    // ---------- 摄像头 + 编码器 ----------

    fun startCamera(front: Boolean, r: VideoReq, c: Camera2ParamApplier.Params) {
        post {
            if (cameraRunning) return@post
            this.front = front
            this.req = r
            this.controls = c
            val id = SdCameraSource.cameraId(context, front)
            if (id == null) {
                host.onCameraError("没有可用的摄像头")
                return@post
            }
            val enc = buildEncoderWithFallback(id, r) ?: run {
                host.onCameraError("编码器无法启动（所有尺寸/降级档都失败）")
                return@post
            }
            val cam = SdCameraSource(context, cameraListener)
            cam.standbySize = _previewSize.value
            camera = cam
            cameraRunning = true
            cam.open(front, enc.inputSurface!!, effectiveControls())
            SdLog.i("═══ 采集启动 ${if (front) "前置" else "后置"} 编码${encodeWidth}x${encodeHeight}@${r.fps}" +
                    " 目标${r.kbps}kbps 参数[${r.plan.describe()}] 方向=传感器原生(横向) ═══")
        }
    }

    fun stopCamera() {
        post {
            cameraRunning = false
            camera?.close()
            camera = null
            encoder?.stop()
            encoder = null
            _previewSize.value = null
            SdLog.i("采集已停止")
        }
    }

    /** 相机自愈：关设备重开（编码器不动） */
    fun restartCamera(reason: String) {
        post {
            val cam = camera ?: return@post
            SdLog.i("重开摄像头：$reason")
            cam.reopen(front)
        }
    }

    fun switchCamera(front: Boolean) {
        post {
            if (this.front == front) return@post
            this.front = front
            val cam = camera ?: return@post
            val r = req ?: return@post
            // 前后摄可用尺寸不同：按新摄像头重选编码尺寸，变了就换编码器
            val id = SdCameraSource.cameraId(context, front) ?: return@post
            val size = SdCameraSource.chooseSize(context, id, r.plan.width, r.plan.height)
            SdLog.i("切换到${if (front) "前置" else "后置"}摄像头（编码 ${size.width}x${size.height}；前置为传感器原生方向，可能与后置差 180°）")
            if (size.width != encodeWidth || size.height != encodeHeight) {
                rebuildEncoder(id, r, "切前后摄尺寸变化")
            }
            cam.reopen(front)
            encoder?.requestKeyFrame()
        }
    }

    /** 档位/清晰度/帧率/srtParams 变化 */
    fun updateVideo(r: VideoReq, reason: String) {
        post {
            val old = req
            req = r
            if (!cameraRunning) return@post
            val enc = encoder
            val id = camera?.cameraId?.takeIf { it.isNotEmpty() } ?: SdCameraSource.cameraId(context, front) ?: return@post
            val size = SdCameraSource.chooseSize(context, id, r.plan.width, r.plan.height)
            val need = enc == null || old == null ||
                    size.width != encodeWidth || size.height != encodeHeight ||
                    r.fps != enc.config.fps || r.plan.gopSec != enc.config.gopSec ||
                    r.plan.bitrateMode != enc.config.bitrateMode ||
                    r.plan.qpPMax != enc.config.qpPMax
            if (!need && old == r) return@post   // 同一条指令多处重复落地（切档后又同步码率等），无变化不动
            if (need) {
                rebuildEncoder(id, r, reason)
            } else if (enc != null) {
                // 峰值控制参数变了：控制器按新 plan 重置（运行中抬高过的 QP 回到 plan 值）
                if (old != null && old.plan != r.plan) {
                    if (peak.curPMax != r.plan.qpPMax) enc.setPMaxQp(r.plan.qpPMax)
                    if (peak.curPMax > old.plan.qpCeil) enc.setIMaxQp(r.plan.qpIMax)   // 拥塞保护放开过 I 帧上限
                    synchronized(peak) { peak.reset(r.plan, qpRuntimeOk()) }
                }
                val target = r.kbps * peak.ratePct / 100
                enc.setBitrate(target)
                SdLog.i("改码率 → ${target}kbps（$reason，档位码率×清晰度=${r.kbps}，峰值控制${peak.ratePct}%）")
            }
            // 延迟/限速只在连接时生效 → 变了就主动重连（流名不变）
            val tgt = sender?.currentTarget
            if (tgt != null && (tgt.latencyMs != r.plan.latencyMs || tgt.maxBwKbps != r.plan.maxBwKbps)) {
                sender?.reconnect("延迟 ${tgt.latencyMs}→${r.plan.latencyMs}ms / 限速 ${tgt.maxBwKbps}→${r.plan.maxBwKbps}kbps")
            }
        }
    }

    fun setControls(c: Camera2ParamApplier.Params) {
        controls = c
        camera?.setControls(effectiveControls())
    }

    fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    /** §141 PC「SRT设置」回读（照 test-android StreamSession.remoteState）：能力范围 + 实际曝光 + 链路 */
    fun cameraState(): Map<String, Any> {
        val m = LinkedHashMap<String, Any>()
        val r = req
        val s = sender
        m["width"] = encodeWidth
        m["height"] = encodeHeight
        m["fps"] = r?.fps ?: 0
        m["kbps"] = r?.kbps ?: 0
        m["latencyMs"] = s?.currentTarget?.latencyMs ?: (r?.plan?.latencyMs ?: 0)
        // §148 PC「SRT设置」档位 / QP 栏：当前档位 + 编码器实际 QP 上下限（编码器还没建好时用档位参数）；
        //   qpOk = 编码器认 QP 上下限键（降级到「去掉QP上下限」档时不认）；qpAvg = 本秒平均 QP（Android 13+，-1 = 拿不到）；
        //   qpPMaxNow = 峰值控制当前的 P 帧 QP 上限（运行中可能被临时抬高）
        if (r != null) {
            val enc = encoder
            val qc = enc?.config
            m["profile"] = r.plan.profile
            m["qpIMin"] = qc?.qpIMin ?: r.plan.qpIMin
            m["qpIMax"] = qc?.qpIMax ?: r.plan.qpIMax
            m["qpPMin"] = qc?.qpPMin ?: r.plan.qpPMin
            m["qpPMax"] = qc?.qpPMax ?: r.plan.qpPMax
            m["qpOk"] = enc?.qpConfigured ?: true
            m["qpAvg"] = enc?.statQp ?: -1
            m["qpPMaxNow"] = synchronized(peak) { peak.curPMax }
        }
        if (s != null) {
            m["rttMs"] = s.rttMs.toInt()
            m["sndBufMs"] = s.sndBufMs
            m["srtState"] = s.state.toString()
            // 上行：sendKbps = 上一秒 SRT 实际发出（含 TS/SRT 头和重传）；encKbpsNow = 上一秒编码器实际输出；
            //   uplinkKbps + uplinkLimited：已顶满时是实测上限，否则是下限（≥）；lateDrops = 上一秒迟到丢包数
            m["sendKbps"] = lastSendKbps
            m["encKbpsNow"] = lastEncKbps
            m["uplinkKbps"] = uplink.estKbps
            m["uplinkLimited"] = uplink.limited
            m["lateDrops"] = lastLateDrops
        }
        val cam = camera ?: return m
        m["manualSupported"] = cam.manualSupported
        cam.exposureRangeNs?.let { m["expMinNs"] = it.lower; m["expMaxNs"] = it.upper }
        cam.isoRange?.let { m["isoMin"] = it.lower; m["isoMax"] = it.upper }
        cam.evRange?.let { m["evMin"] = it.first; m["evMax"] = it.second }
        cam.zoomRange?.let { m["zoomMin"] = it.first; m["zoomMax"] = it.second }
        m["curExpNs"] = cam.curExposureNs
        m["curIso"] = cam.curIso
        m["aeAuto"] = cam.aeAuto
        m["exposureDesc"] = cam.exposureDesc
        return m
    }

    /** 采集帧率 = 推送帧率（§132 对齐 3840）；防抖按 srtParams */
    private fun effectiveControls(): Camera2ParamApplier.Params {
        val r = req
        return controls.copy(
            targetFps = r?.fps ?: controls.targetFps,
            disableStabilization = r?.plan?.disableStabilization ?: true,
        )
    }

    private fun qpRuntimeOk(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 31 && encoder?.qpConfigured == true

    private fun encConfig(w: Int, h: Int, r: VideoReq) = SdH264Encoder.Config(
        width = w, height = h, fps = r.fps, bitrateKbps = r.kbps,
        bitrateMode = r.plan.bitrateMode,
        qpIMin = r.plan.qpIMin, qpIMax = r.plan.qpIMax,
        qpPMin = r.plan.qpPMin, qpPMax = r.plan.qpPMax,
        gopSec = r.plan.gopSec,
    )

    /** 首次启动：请求尺寸 → 1080p → 720p 逐个试 */
    private fun buildEncoderWithFallback(cameraId: String, r: VideoReq): SdH264Encoder? {
        val wants = listOf(r.plan.width to r.plan.height, 1920 to 1080, 1280 to 720).distinct()
        for ((w, h) in wants) {
            val size = SdCameraSource.chooseSize(context, cameraId, w, h)
            val enc = try { newEncoder(encConfig(size.width, size.height, r)) } catch (t: Throwable) {
                SdLog.w("编码器 ${size.width}x${size.height} 启动失败: ${t.message}（试下一个尺寸）")
                continue
            }
            onEncoderReady(cameraId, enc, r)
            return enc
        }
        return null
    }

    private fun rebuildEncoder(cameraId: String, r: VideoReq, reason: String) {
        val cam = camera ?: return
        val size = SdCameraSource.chooseSize(context, cameraId, r.plan.width, r.plan.height)
        val old = encoder
        val enc = try { newEncoder(encConfig(size.width, size.height, r)) } catch (t: Throwable) {
            SdLog.w("换编码器失败（$reason → ${size.width}x${size.height}@${r.fps}）: ${t.message}，保持原编码器")
            return
        }
        val fromDesc = "${encodeWidth}x${encodeHeight}@${old?.config?.fps}"
        onEncoderReady(cameraId, enc, r)
        cam.switchEncoder(enc.inputSurface!!, effectiveControls()) { old?.stop() }
        SdLog.i("换编码器（$reason）$fromDesc → ${size.width}x${size.height}@${r.fps} 目标${r.kbps}kbps" +
                " GOP${r.plan.gopSec}s QP上限${r.plan.qpPMax}（SRT 不断）")
    }

    private fun onEncoderReady(cameraId: String, enc: SdH264Encoder, r: VideoReq) {
        encoder = enc
        encodeWidth = enc.config.width
        encodeHeight = enc.config.height
        synchronized(peak) { peak.reset(r.plan, android.os.Build.VERSION.SDK_INT >= 31 && enc.qpConfigured) }
        val p = SdCameraSource.choosePreviewSize(context, cameraId, encodeWidth, encodeHeight)
        _previewSize.value = p
        camera?.standbySize = p
    }

    private fun newEncoder(c: SdH264Encoder.Config): SdH264Encoder {
        lateinit var enc: SdH264Encoder
        enc = SdH264Encoder(c, onFrame = { data, ptsUs, isKey ->
            // 换编码器时新旧短暂并存：只收当前编码器的输出（muxer 非线程安全）
            if (encoder === enc) {
                val s = sender
                if (s != null && s.connected) {
                    // §142 每帧打上 采集/编码完成 墙钟时间（SEI），PC 据此算端到端延迟
                    val nowWall = System.currentTimeMillis()
                    val cap = SdLatencySei.captureWallMs(ptsUs, nowWall)
                    val stamped = SdLatencySei.insert(data, if (cap > 0) cap else nowWall, nowWall)
                    s.offer(synchronized(muxer) { muxer.mux(stamped, ptsUs, isKey) }, isKey)
                }
            }
        }, onFatal = { msg ->
            post {
                if (encoder !== enc || !cameraRunning) return@post
                val r = req ?: return@post
                val id = camera?.cameraId ?: return@post
                rebuildEncoder(id, r, "编码器出错重建: $msg")
            }
        })
        enc.start()
        return enc
    }

    private val cameraListener = object : SdCameraSource.Listener {
        override fun onCameraError(msg: String) {
            SdLog.w("摄像头: $msg")
            host.onCameraError(msg)
        }

        override fun onCameraFirstFrame() {
            host.onCameraFirstFrame()
        }
    }

    // ---------- SRT ----------

    fun startSender() {
        post {
            if (sender != null) return@post
            val s = SdSrtSender(
                targetProvider = { attempt -> req?.let { host.srtTarget(attempt, it.plan) } },
                onNeedKeyFrame = { encoder?.requestKeyFrame() },
                onLive = { attempt -> host.onSrtLive(attempt) },
                onLost = { reason -> host.onSrtLost(reason) },
            )
            sender = s
            resetStatBaselines()
            s.start()
        }
    }

    fun stopSender() {
        post {
            sender?.stop()
            sender = null
        }
    }

    val senderConnected: Boolean get() = sender?.connected == true
    val senderLive: Boolean get() = sender?.live == true

    // ---------- 每秒统计 ----------

    private var lastTickMs = 0L
    private var lastCamFrames = 0L
    private var lastEncFrames = 0L
    private var lastSent = 0L
    private var lastLoss = 0L
    private var lastRetrans = 0L
    private var lastDrop = 0L
    private val uplink = SdUplinkMeter()
    @Volatile private var lastSendKbps = 0
    @Volatile private var lastEncKbps = 0
    @Volatile private var lastLateDrops = 0L

    private fun resetStatBaselines() {
        lastSent = 0; lastLoss = 0; lastRetrans = 0; lastDrop = 0
    }

    fun tick(batteryTempC: Float?): Snapshot {
        val now = System.currentTimeMillis()
        val dt = if (lastTickMs == 0L) 1.0 else ((now - lastTickMs).coerceAtLeast(1)) / 1000.0
        lastTickMs = now
        val cam = camera
        val enc = encoder
        val s = sender
        val r = req

        val camFrames = cam?.frameCount ?: 0L
        val capFps = if (camFrames >= lastCamFrames) ((camFrames - lastCamFrames) / dt).toInt() else 0
        lastCamFrames = camFrames
        val encFrames = enc?.framesTotal ?: 0L
        val encAlive = enc != null && encFrames != lastEncFrames
        lastEncFrames = encFrames
        val encKbps = if (encAlive) enc!!.statKbps else 0
        val encFps = if (encAlive) enc!!.statFps else 0

        // SRT 累计值在新 socket 上会归零 → 回退时重置基线
        fun delta(cur: Long, last: Long) = if (cur >= last) cur - last else cur
        val sent = s?.sentTotal ?: 0L
        val loss = s?.lossTotal ?: 0L
        val retrans = s?.retransTotal ?: 0L
        val drop = s?.sndDropTotal ?: 0L
        val dSent = delta(sent, lastSent)
        val dLoss = delta(loss, lastLoss)
        val dRetrans = delta(retrans, lastRetrans)
        val dDrop = delta(drop, lastDrop)
        lastSent = sent; lastLoss = loss; lastRetrans = retrans; lastDrop = drop
        val lossRate = if (dSent > 0) (dLoss.toDouble() / dSent).coerceIn(0.0, 1.0) else 0.0

        val sendKbps = ((s?.sendMbps ?: 0.0) * 1000).toInt()
        if (s != null && s.live) {
            uplink.tick(sendKbps, s.sndBufMs, dDrop, s.currentTarget?.latencyMs ?: (r?.plan?.latencyMs ?: 800))
        } else {
            uplink.reset()
        }
        lastSendKbps = sendKbps
        lastEncKbps = encKbps
        lastLateDrops = dDrop

        // 峰值控制器
        var peakMsg = ""
        if (enc != null && r != null && s != null) {
            val res = synchronized(peak) { peak.tick(encKbps, s.sndBufMs, s.connected) }
            if (res != null) {
                when (val st = res.step) {
                    is SdPeakController.Step.Qp -> {
                        enc.setPMaxQp(st.to)
                        // 拥塞保护那一步越过 qpCeil 时 I 帧上限一起放（I 帧每个 GOP 一个，1440P 上 100KB+，不放码率降不下来），撤回时还原
                        val jam = st.to > r.plan.qpCeil || st.from > r.plan.qpCeil
                        if (jam) enc.setIMaxQp(if (st.to > r.plan.qpCeil) maxOf(st.to, r.plan.qpIMax) else r.plan.qpIMax)
                        peakMsg = "${if (res.raised) "压峰值" else "回落"} P帧QP上限 ${st.from}→${st.to}" +
                                (if (jam) "（I帧上限 ${if (st.to > r.plan.qpCeil) maxOf(st.to, r.plan.qpIMax) else r.plan.qpIMax}）" else "") +
                                "（${res.reason}）"
                    }
                    is SdPeakController.Step.Rate -> {
                        val target = r.kbps * st.toPct / 100
                        enc.setBitrate(target)
                        peakMsg = "${if (res.raised) "压峰值" else "回落"} 目标码率 ${st.fromPct}%→${st.toPct}%=${target}kbps（${res.reason}）"
                    }
                    null -> peakMsg = "峰值超标但无可再降：${res.reason}"
                }
                SdLog.i("峰值控制 $peakMsg")
            }
        }

        val line = buildString {
            append("统计 ${s?.state ?: "未推流"}")
            append(" | 采集${capFps}fps")
            append(" | 编码 ${encodeWidth}x${encodeHeight} ${encKbps}kbps/目标${r?.kbps?.let { it * peak.ratePct / 100 } ?: 0}")
            append(" ${encFps}fps QP${enc?.statQp?.takeIf { it >= 0 }?.toString() ?: "?"}")
            append(" I${(enc?.lastIBytes ?: 0) / 1024}KB P均${(enc?.statPAvgBytes ?: 0) / 1024}KB P峰${(enc?.statPMaxBytes ?: 0) / 1024}KB")
            if (s != null) {
                append(" | SRT 发送${"%.2f".format(s.sendMbps)}Mbps 估计${"%.1f".format(s.bandwidthMbps)}Mbps RTT${s.rttMs.toInt()}ms")
                append(" 发送缓冲${s.sndBufMs}ms 在途${s.flightPkts} 本秒丢${dLoss} 重传${dRetrans} 迟到丢${dDrop} 断线丢帧${s.droppedFrames} 重连${s.reconnects}")
                append(" 上行${uplink.desc}")
            }
            append(" | 峰值控制 ${peak.levelDesc}")
            batteryTempC?.let { append(" | 电池${"%.1f".format(it)}℃") }
        }
        SdLog.i(line)
        return Snapshot(
            connected = s?.connected == true,
            live = s?.live == true,
            encKbps = encKbps,
            encFps = encFps,
            capFps = capFps,
            rttMs = s?.rttMs?.toInt() ?: 0,
            lossRate = lossRate,
            line = line,
        )
    }

    fun release() {
        post {
            sender?.stop(); sender = null
            cameraRunning = false
            camera?.close(); camera = null
            encoder?.stop(); encoder = null
        }
        worker.shutdown()
    }
}
