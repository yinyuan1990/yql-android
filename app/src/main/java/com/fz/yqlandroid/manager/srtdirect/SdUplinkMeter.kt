package com.fz.yqlandroid.manager.srtdirect

/**
 * 手机上行带宽估算（给 PC「SRT设置」弹框和日志看）。
 *
 * 不额外测速（会挤占推流），只从 SRT 每秒统计反推：
 * - 发送缓冲在涨 / 有迟到丢包 = 已顶到上行上限 → 这几秒实际发出去的速率 ≈ 上行真实带宽；
 * - 没堵 = 上行至少有当前发送速率那么多（只是下限，真实带宽可能大得多）。
 * SRT 自带的 mbpsBandwidth（包对估计）在 4G 下常给出 100Mbps 以上，不可用。
 */
internal class SdUplinkMeter {
    private data class Sec(val sendKbps: Int, val congested: Boolean)

    private val recent = ArrayDeque<Sec>()

    /** 估计上行（kbps）：[limited]=true 时是实测上限，false 时是下限（≥） */
    @Volatile var estKbps = 0
        private set
    /** 最近 3 秒里至少 2 秒堵着 = 码率已顶满上行 */
    @Volatile var limited = false
        private set

    val desc: String
        get() = when {
            estKbps <= 0 -> "-"
            limited -> "≈${estKbps}kbps(已顶满)"
            else -> "≥${estKbps}kbps"
        }

    @Synchronized
    fun tick(sendKbps: Int, sndBufMs: Int, lateDrops: Long, latencyMs: Int) {
        val congested = lateDrops > 0 || sndBufMs > maxOf(150, latencyMs * 3 / 10)
        recent.addLast(Sec(sendKbps.coerceAtLeast(0), congested))
        while (recent.size > 10) recent.removeFirst()
        limited = recent.takeLast(3).count { it.congested } >= 2
        val cong = recent.filter { it.congested }
        estKbps = if (limited && cong.isNotEmpty()) cong.sumOf { it.sendKbps } / cong.size
                  else recent.maxOf { it.sendKbps }
    }

    @Synchronized
    fun reset() {
        recent.clear()
        estKbps = 0
        limited = false
    }
}
