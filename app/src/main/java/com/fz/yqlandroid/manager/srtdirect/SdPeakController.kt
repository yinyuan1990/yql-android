package com.fz.yqlandroid.manager.srtdirect

/**
 * §134 峰值控制器（§133.1 第 4 条）：每秒看编码实际 kbps 与 SRT 发送缓冲。
 *
 * - **超标**：实际码率 > peakKbps，或发送缓冲 > 45% latency，连续 holdSec 秒 →
 *   先 P 帧 QP 上限 += qpStep（≤ qpCeil，Android 12+ 运行中 setParameters）；
 *   QP 到顶或系统不支持运行中改 QP → 目标码率降 10%/档（最多 5 档到 50%）。
 * - **拥塞保护**：上面两样到底后发送缓冲仍持续超标（上行真不够，不是单纯码率超峰值）→
 *   QP 上限一步放到 [JAM_QP]（I 帧一起放，引擎处理），码率再降到最低 [JAM_RATE_MIN]%。
 *   上行被视频挤满时 WebSocket 心跳/指令也过不去（PC 显示码率 0、调不了参数），拥塞久了 SRT 也会断。
 * - **平稳**：实际码率 ≤ 80% 峰值且发送缓冲 < 15% latency，连续 recoverSec 秒 → 撤销最近一步（后做的先撤）。
 * - 11-12：上面的「发送缓冲」先扣掉 RTT 基线再比（`peakCtrl.rttComp`，缺省开；同 iOS 8952ce7）。libsrt 的 msSndBuf 是
 *   「还没被对端确认的数据时长」，上行再空也有 0.7~0.9 个 RTT，和码率无关；原来直接拿它和 latency 比，延迟 120ms 的档位
 *   门槛是 54 / 18ms，RTT 高一点就一直算超标、被压过一次就永远不算平稳（近 3 天安卓低档位 68% 的时间目标被压到 ≤50%）。
 *   基线 = 本次连接见过的最小 RTT（断线重学）：真拥塞时 RTT 自己也会涨到几秒，用当前 RTT 会把拥塞一起扣掉。
 * - 注意：运行中 setParameters 改 QP 上限在高通 c2.qti.avc.encoder 上不生效（7 个机型 22 万秒：上限抬到 45 后编码 QP 仍 25~26），
 *   这里的「抬 QP」「拥塞保护 QP 放到 45」对高通是空操作，真正起作用的是引擎里的码率失控保护（重建编码器）。
 */
internal class SdPeakController {
    companion object {
        const val JAM_QP = 45
        const val JAM_RATE_MIN = 20
    }

    sealed class Step {
        data class Qp(val from: Int, val to: Int) : Step()
        data class Rate(val fromPct: Int, val toPct: Int) : Step()
    }

    data class Result(val step: Step?, val raised: Boolean, val reason: String)

    private var plan: SrtDirectParams.Plan? = null
    private var qpRuntime = false
    private val steps = ArrayDeque<Step>()
    private var overSec = 0
    private var calmSec = 0
    /** RTT 基线 = 本次连接见过的最小 RTT（ms，0 = 还没读到）；断线清零，换编码器 / 换参数不清（网络没变） */
    private var rttBase = 0

    /** 发送缓冲扣掉 RTT 基线后的部分（基线不超过 latency；rttComp 关 = 原值）。码率失控保护也用它 */
    fun bufExcess(sndBufMs: Int, latencyMs: Int, rttComp: Boolean): Int =
        if (rttComp) maxOf(0, sndBufMs - minOf(rttBase, latencyMs)) else sndBufMs

    /** 当前 P 帧 QP 上限（含控制器抬高量） */
    var curPMax = 0
        private set
    /** 当前码率系数（百分比） */
    var ratePct = 100
        private set

    val levelDesc: String
        get() = if (steps.isEmpty()) "平稳" else "已介入${steps.size}步(P≤$curPMax 码率${ratePct}%)"

    /** 新编码器/新参数：清零 */
    fun reset(p: SrtDirectParams.Plan, qpRuntimeSupported: Boolean) {
        plan = p
        qpRuntime = qpRuntimeSupported
        steps.clear()
        overSec = 0
        calmSec = 0
        curPMax = p.qpPMax
        ratePct = 100
    }

    fun tick(encKbps: Int, sndBufMs: Int, rttMs: Int, connected: Boolean): Result? {
        val p = plan ?: return null
        // RTT 基线：只取最小值（拥塞时 RTT 会涨，不能跟着涨）；断线后下一条连接重新学
        if (!connected) {
            rttBase = 0
        } else if (rttMs > 0) {
            rttBase = if (rttBase == 0) rttMs else minOf(rttBase, rttMs)
        }
        if (!p.peakEnable || !connected) {
            overSec = 0; calmSec = 0
            return null
        }
        // 发送缓冲里天生就有约一个 RTT 的「已发出、等确认」的数据，扣掉基线后才是真正堆住的
        val base = if (p.peakRttComp) minOf(rttBase, p.latencyMs) else 0
        val bufEx = bufExcess(sndBufMs, p.latencyMs, p.peakRttComp)
        val calmPct = minOf(p.peakBufCalmPct, p.peakBufOverPct)
        val peakOver = p.peakKbps > 0 && encKbps > p.peakKbps
        val bufOver = bufEx > p.latencyMs * p.peakBufOverPct / 100
        val calm = (p.peakKbps <= 0 || encKbps <= p.peakKbps * 8 / 10) && bufEx < p.latencyMs * calmPct / 100
        val bufDesc = "发送缓冲${sndBufMs}ms" + (if (base > 0) "(扣RTT基线${base}ms后${bufEx}ms)" else "")
        when {
            peakOver || bufOver -> { overSec++; calmSec = 0 }
            calm -> { calmSec++; overSec = 0 }
            else -> { overSec = 0; calmSec = 0 }
        }
        val why = buildString {
            if (peakOver) append("实际${encKbps}kbps>峰值${p.peakKbps}")
            if (bufOver) { if (isNotEmpty()) append(" "); append("$bufDesc>${p.peakBufOverPct}%延迟") }
        }
        if (overSec >= p.holdSec) {
            overSec = 0
            val s: Step? = when {
                qpRuntime && curPMax < p.qpCeil -> Step.Qp(curPMax, minOf(p.qpCeil, curPMax + p.qpStep))
                ratePct > 50 -> Step.Rate(ratePct, ratePct - 10)
                bufOver && qpRuntime && curPMax < maxOf(JAM_QP, p.qpCeil) -> Step.Qp(curPMax, maxOf(JAM_QP, p.qpCeil))
                bufOver && ratePct > JAM_RATE_MIN -> Step.Rate(ratePct, ratePct - 10)
                else -> null
            }
            if (s == null) {
                val floor = if (bufOver) "QP 上限 ${maxOf(JAM_QP, p.qpCeil)} 且码率 ${JAM_RATE_MIN}%" else "QP 上限 ${p.qpCeil} 且码率 50%"
                return Result(null, true, "$why（已到 $floor，无可再降）")
            }
            val jam = (s is Step.Rate && ratePct <= 50) || (s is Step.Qp && s.to > p.qpCeil)
            apply(s)
            steps.addLast(s)
            return Result(s, true, "$why 连续${p.holdSec}s" + (if (jam) "，上行不够 → 拥塞保护" else ""))
        }
        if (calmSec >= p.recoverSec && steps.isNotEmpty()) {
            calmSec = 0
            val last = steps.removeLast()
            val back = when (last) {
                is Step.Qp -> Step.Qp(last.to, last.from)
                is Step.Rate -> Step.Rate(last.toPct, last.fromPct)
            }
            apply(back)
            return Result(back, false, "平稳${p.recoverSec}s（实际${encKbps}kbps $bufDesc）")
        }
        return null
    }

    private fun apply(s: Step) {
        when (s) {
            is Step.Qp -> curPMax = s.to
            is Step.Rate -> ratePct = s.toPct
        }
    }
}
