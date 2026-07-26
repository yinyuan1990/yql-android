package com.fz.yqlandroid.manager.uvc

import kotlin.math.roundToInt

/**
 * ⭐ 第五十章：OTG 码率上限的唯一计算处。
 *
 * 为什么要单独算：OTG 的分辨率是设备枚举出来的（这台最大 1920x1080、那台只有 1280x720），
 * 而自带摄像头那套 `currentLadder` 是按 Camera2 枚举建的，跟 UVC 毫无关系——
 * 之前 OTG 的码率百分比落在 `currentLadder[currentProfile].maxKbps` 上，
 * 等于不管实际在跑 640x480 还是 1080p，天花板都是同一个无关的值（默认档 3000kbps）：
 * 小分辨率白占带宽，大分辨率喂不饱。
 *
 * 算法（与用户定的口径一致）：
 *   · 锚点 = 该设备**枚举出的最大分辨率**，码率取现有最高档的 [ANCHOR_KBPS]（P4K 档口径，保持与现网调校同一家族）
 *   · 其余档位按**像素率** `w × h × fps` 与锚点等比缩放
 *   · clamp 到 [MIN_KBPS, ANCHOR_KBPS]，避免 160x120 算出几十 kbps 这种没法看的值
 *   · min 码率 = max 的 60%（沿用 ladder 里 maxKbps/minKbps 的既有比例）
 *
 * 结果随能力快照一起上报 PC，面板上能直接看到"50% ≈ 多少 kbps"，不用两边各写一份公式。
 */
object OtgBitratePlan {

    /** 最大分辨率档的码率上限（= 现有 ladder 最高档 P4K 的 maxKbps） */
    const val ANCHOR_KBPS = 4000

    /** 再小的分辨率也不低于这个值，否则画面糊到没意义 */
    const val MIN_KBPS = 300

    /** min/max 比例，与 ladder 各档一致 */
    private const val MIN_RATIO = 0.6

    /** 帧率缺省值：设备没报 fps 上限时按 30 算 */
    private const val DEFAULT_FPS = 30

    private fun pixelRate(width: Int, height: Int, fps: Int): Long =
        width.toLong() * height * (if (fps > 0) fps else DEFAULT_FPS)

    /**
     * 按整份分辨率列表算出每档的码率上限。
     * @param sizes 设备枚举出的全部分辨率（锚点自动取其中像素率最大的一档）
     */
    fun ceilingFor(sizes: List<UvcCapabilityStore.SizeOption>, width: Int, height: Int, fps: Int): Int {
        if (sizes.isEmpty()) return ANCHOR_KBPS
        val anchor = sizes.maxByOrNull { pixelRate(it.width, it.height, it.maxFps) } ?: return ANCHOR_KBPS
        val anchorRate = pixelRate(anchor.width, anchor.height, anchor.maxFps)
        if (anchorRate <= 0) return ANCHOR_KBPS
        val rate = pixelRate(width, height, fps)
        val kbps = (ANCHOR_KBPS * (rate.toDouble() / anchorRate)).roundToInt()
        return kbps.coerceIn(MIN_KBPS, ANCHOR_KBPS)
    }

    /** 给能力快照里的每一档分辨率填上码率上限（PC 面板据此显示实际 kbps） */
    fun annotate(sizes: List<UvcCapabilityStore.SizeOption>): List<UvcCapabilityStore.SizeOption> =
        sizes.map { it.copy(maxKbps = ceilingFor(sizes, it.width, it.height, it.maxFps)) }

    fun minKbpsOf(maxKbps: Int): Int = (maxKbps * MIN_RATIO).roundToInt().coerceAtLeast(MIN_KBPS)
}
