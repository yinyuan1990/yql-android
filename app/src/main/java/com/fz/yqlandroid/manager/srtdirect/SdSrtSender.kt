package com.fz.yqlandroid.manager.srtdirect

import io.github.thibaultbee.srtdroid.core.Srt
import io.github.thibaultbee.srtdroid.core.enums.SockOpt
import io.github.thibaultbee.srtdroid.core.enums.SockStatus
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.models.SrtSocket

/**
 * §134 SRT（live）发送 MPEG-TS 到 MediaMTX —— 拷自 test-android / §129 `SrtSender`（照 silu `SrtMpegTsPublisher`）：
 *
 * - 编码线程里同步发送、不排队；网络跟不上时 SRT 按 latency 丢迟到包，峰值由 [SdPeakController] 压。
 * - 每 1316 字节拷成独立小数组再 send（srtdroid 带偏移的 send 每次 Get/Release 整个数组）。
 * - 每次连接都调 [targetProvider] 取地址/延迟/限速/streamid（推流 token 一次性，重连必须重取）。
 * - 断线 2s 自动重连，**流名不变**（MediaMTX 同路径新推流顶掉旧会话，PC 不用跟流名）。
 * - 连上后丢到首个 IDR 为止；首个 IDR 发出回调 [onLive]。
 */
internal class SdSrtSender(
    /** 连接线程里调用（可阻塞取 token）；attempt=第几次连接（0=首次） */
    private val targetProvider: (attempt: Int) -> Target?,
    private val onNeedKeyFrame: () -> Unit,
    /** 某次连接成功后首个关键帧已发出；在编码线程回调 */
    private val onLive: (attempt: Int) -> Unit,
    /** 断线（含原因）；在编码线程或连接线程回调 */
    private val onLost: (reason: String) -> Unit,
) {
    data class Target(
        val host: String,
        val port: Int,
        val streamId: String,
        val latencyMs: Int,
        /** >0 时设 SRTO_MAXBW（绝对上限）；0=libsrt 默认 */
        val maxBwKbps: Int,
    )

    companion object {
        private const val CHUNK = SdTsMuxer.PACKET_SIZE * 7
        private const val RECONNECT_DELAY_MS = 2000L
        private const val SLOW_SEND_MS = 300L
        private const val NO_FRAME_WARN_MS = 2000L
    }

    private val lock = Any()
    @Volatile private var socket: SrtSocket? = null
    @Volatile private var running = false
    @Volatile private var waitKey = true
    private var thread: Thread? = null
    private var attempts = 0
    @Volatile private var currentAttempt = 0
    @Volatile var currentTarget: Target? = null
        private set

    // —— 状态/统计 ——
    @Volatile var state: String = "未开始"
        private set
    val connected: Boolean get() = socket != null
    @Volatile var live = false
        private set
    @Volatile var droppedFrames = 0L
        private set
    @Volatile var reconnects = 0
        private set
    @Volatile var sendMbps = 0.0
        private set
    @Volatile var rttMs = 0.0
        private set
    @Volatile var sentTotal = 0L
        private set
    @Volatile var lossTotal = 0L
        private set
    @Volatile var retransTotal = 0L
        private set
    @Volatile var sndDropTotal = 0L
        private set
    /** SRT 发送缓冲里还没发出去的数据（毫秒）：持续变大 = 上行带宽不够 */
    @Volatile var sndBufMs = 0
        private set
    @Volatile var bandwidthMbps = 0.0
        private set
    @Volatile var flightPkts = 0
        private set
    @Volatile var lastError: String = ""
        private set

    @Volatile private var connectedAtMs = 0L
    @Volatile private var lastOfferMs = 0L
    @Volatile private var noFrameWarned = false
    @Volatile private var closeReason: String? = null
    private var waitKeySinceMs = 0L
    private var waitKeyDrops = 0

    fun start() {
        running = true
        thread = Thread({ connectLoop() }, "sd-srt-conn").apply { start() }
    }

    fun stop() {
        SdLog.i("停止发送（共重连 $reconnects 次，断线丢帧 $droppedFrames）")
        running = false
        thread?.interrupt()
        synchronized(lock) { closeSocketLocked() }
        live = false
        state = "已停止"
    }

    /** 参数变了（延迟/限速/节点）：主动断开，2s 后按新参数重连（流名不变） */
    fun reconnect(reason: String) {
        val s = socket ?: return
        SdLog.i("主动重连：$reason")
        closeReason = "主动重连($reason)"
        try { s.close() } catch (_: Throwable) {}
        onDisconnected(s, "主动重连($reason)", notify = false)
    }

    /** 编码线程调用：同步发送一帧 TS（断线中直接丢弃） */
    fun offer(ts: ByteArray, isKey: Boolean) {
        if (!running) return
        val now = System.currentTimeMillis()
        if (noFrameWarned) {
            SdLog.i("帧恢复送来（中断 ${now - maxOf(lastOfferMs, connectedAtMs)}ms）")
            noFrameWarned = false
        }
        lastOfferMs = now
        var liveAttempt = -1
        synchronized(lock) {
            val s = socket
            if (s == null) {
                droppedFrames++
                return
            }
            if (waitKey && !isKey) {
                droppedFrames++
                waitKeyDrops++
                return
            }
            if (waitKey) {
                SdLog.i("连上后首个关键帧已发：等了 ${now - waitKeySinceMs}ms，期间丢 $waitKeyDrops 帧（IDR ${ts.size / 1024}KB）")
                waitKey = false
                live = true
                state = "推流中"
                liveAttempt = currentAttempt
            }
            val t0 = System.currentTimeMillis()
            try {
                var off = 0
                while (off < ts.size) {
                    val end = minOf(off + CHUNK, ts.size)
                    s.send(ts.copyOfRange(off, end))
                    off = end
                }
                val cost = System.currentTimeMillis() - t0
                if (cost > SLOW_SEND_MS) {
                    SdLog.w("发送阻塞 ${cost}ms（帧 ${ts.size / 1024}KB${if (isKey) " 关键帧" else ""}）" +
                            " 发送缓冲 ${sndBufMs}ms 链路估计 ${"%.1f".format(bandwidthMbps)}Mbps")
                }
            } catch (t: Throwable) {
                val err = t.message ?: t.toString()
                val sock = try { s.sockState.name } catch (_: Throwable) { "?" }
                val cause = closeReason?.let { "$it；" } ?: ""
                onDisconnected(s, "${cause}发送失败: $err（socket=$sock，帧 ${ts.size / 1024}KB）")
                liveAttempt = -1
            }
        }
        if (liveAttempt >= 0) {
            try { onLive(liveAttempt) } catch (_: Throwable) {}
        }
    }

    private fun onDisconnected(s: SrtSocket, reason: String, notify: Boolean = true) {
        synchronized(lock) {
            if (socket !== s) return
            val alive = (System.currentTimeMillis() - connectedAtMs) / 1000.0
            // 走到这里 socket 多半已关/已断，此时 bstats 读出的是垃圾值 → 用连接线程上一秒读的统计
            SdLog.w("断线 $reason；本次连接存活 ${"%.1f".format(alive)}s；断前 ${statsLine()}")
            lastError = reason
            state = "连接断开，重连中…"
            closeSocketLocked()
            live = false
            reconnects++
        }
        if (notify) try { onLost(reason) } catch (_: Throwable) {}
    }

    fun statsLine(): String =
        "发送${"%.2f".format(sendMbps)}Mbps 估计带宽${"%.1f".format(bandwidthMbps)}Mbps RTT${"%.0f".format(rttMs)}ms" +
                " 发送缓冲${sndBufMs}ms 在途${flightPkts}包 累计丢${lossTotal} 重传${retransTotal} 迟到丢弃${sndDropTotal}"

    private fun connectLoop() {
        try { Srt.startUp() } catch (t: Throwable) {
            state = "SRT 库加载失败"
            lastError = t.message ?: t.toString()
            SdLog.w("SRT 库加载失败: $lastError")
            return
        }
        while (running) {
            val s = socket
            if (s == null) {
                if (!connect()) sleepQuietly(RECONNECT_DELAY_MS)
                continue
            }
            readStats(s)
            checkSocket(s)
            sleepQuietly(1000)
        }
    }

    private fun checkSocket(s: SrtSocket) {
        val st = try { s.sockState } catch (_: Throwable) { null }
        if (st == SockStatus.BROKEN || st == SockStatus.CLOSED || st == SockStatus.NONEXIST) {
            val reason = "检测到 socket 状态 $st（服务器关闭连接 / 网络中断 / 被同名新推流顶掉）"
            closeReason = reason
            try { s.close() } catch (_: Throwable) {}
            onDisconnected(s, reason)
            return
        }
        val gap = System.currentTimeMillis() - maxOf(lastOfferMs, connectedAtMs)
        if (!noFrameWarned && gap > NO_FRAME_WARN_MS) {
            noFrameWarned = true
            SdLog.w("已连接但 ${gap}ms 没有帧送来（摄像头/编码器没出帧，或编码线程卡在上一次发送里）")
        }
    }

    private fun connect(): Boolean {
        val attempt = attempts++
        val t0 = System.currentTimeMillis()
        val tgt = try { targetProvider(attempt) } catch (t: Throwable) {
            SdLog.w("生成连接参数失败: ${t.message}")
            null
        } ?: return false
        if (!running) return false
        currentTarget = tgt
        state = "连接 ${tgt.host}:${tgt.port} …"
        val s = SrtSocket()
        return try {
            // TRANSTYPE 最先设：它会把其它选项重置为 live 模式默认值
            s.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)
            s.setSockFlag(SockOpt.STREAMID, tgt.streamId)
            s.setSockFlag(SockOpt.SENDER, true)
            s.setSockFlag(SockOpt.TSBPDMODE, true)
            s.setSockFlag(SockOpt.LATENCY, tgt.latencyMs)
            s.setSockFlag(SockOpt.PEERLATENCY, tgt.latencyMs)
            s.setSockFlag(SockOpt.RCVLATENCY, tgt.latencyMs)
            s.setSockFlag(SockOpt.PAYLOADSIZE, CHUNK)
            s.setSockFlag(SockOpt.CONNTIMEO, maxOf(1000, tgt.latencyMs * 20).coerceAtMost(10_000))
            if (tgt.maxBwKbps > 0) {
                // 字节/秒；绝对上限 —— 尖峰在 latency 窗口内摊平，不一下打满上行
                s.setSockFlag(SockOpt.MAXBW, tgt.maxBwKbps.toLong() * 125L)
            }
            s.connect(tgt.host, tgt.port)
            synchronized(lock) {
                if (!running) {
                    try { s.close() } catch (_: Throwable) {}
                    return false
                }
                socket = s
                currentAttempt = attempt
                waitKey = true
                live = false
                waitKeySinceMs = System.currentTimeMillis()
                waitKeyDrops = 0
                connectedAtMs = waitKeySinceMs
                noFrameWarned = false
                closeReason = null
            }
            onNeedKeyFrame()
            state = "已连接，等关键帧"
            SdLog.i("已连接 ${tgt.host}:${tgt.port} streamid=${SdLog.maskStreamId(tgt.streamId)} latency=${tgt.latencyMs}ms" +
                    " 限速=${if (tgt.maxBwKbps > 0) "${tgt.maxBwKbps}kbps" else "不限"} 握手${System.currentTimeMillis() - t0}ms（第${attempt + 1}次连接）")
            true
        } catch (t: Throwable) {
            val err = t.message ?: t.toString()
            val reject = try { s.rejectReason.toString() } catch (_: Throwable) { "?" }
            lastError = "连接失败: $err 拒绝原因=$reject"
            state = "连接失败：$err"
            SdLog.w("连接失败 ${tgt.host}:${tgt.port} streamid=${SdLog.maskStreamId(tgt.streamId)}: $err 拒绝原因=$reject" +
                    "（耗时${System.currentTimeMillis() - t0}ms；常见：8890/udp 不通(118 需阿里云安全组) / MediaMTX 鉴权拒绝(token 无效、IP 不在免密段) / 流名非法）")
            try { s.close() } catch (_: Throwable) {}
            false
        }
    }

    private fun readStats(s: SrtSocket) {
        try {
            if (s.sockState != SockStatus.CONNECTED) return
            // clear=true：mbpsSendRate 是距上次读取（≈1s）的区间速率；false 时是整条连接的平均值，看不出上行瞬时变化。
            // *Total 计数不受 clear 影响
            val st = s.bstats(true)
            sendMbps = st.mbpsSendRate
            rttMs = st.msRTT
            sentTotal = st.pktSentTotal
            lossTotal = st.pktSndLossTotal.toLong()
            retransTotal = st.pktRetransTotal.toLong()
            sndDropTotal = st.pktSndDropTotal.toLong()
            sndBufMs = st.msSndBuf
            bandwidthMbps = st.mbpsBandwidth
            flightPkts = st.pktFlightSize
        } catch (_: Throwable) {}
    }

    private fun closeSocketLocked() {
        val s = socket
        socket = null
        try { s?.close() } catch (_: Throwable) {}
    }

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
