package com.fz.yqlandroid.manager.srtdirect

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * §134 硬件 H.264 编码器（Surface 输入，Camera2 直写零拷贝）——拷自 test-android `H264Encoder`，加：
 * - QP 只设一个上限（`video-qp-i/p/b-max` 同一个值 = [Config.qpPMax]），不设下限（和参考 App、iOS MaxAllowedFrameQP 一样）；
 * - GOP 秒数、码率模式由 [Config] 给（后台 srtParams 下发）；
 * - 运行中 [setPMaxQp] 尝试 setParameters 改 P 帧 QP 上限（峰值控制器用），[setBitrate] 改目标码率；
 * - 每秒统计 I/P 帧大小（I 帧最近一帧、P 帧均值/峰值）。
 *
 * 编码器不认某些键时 configure 抛异常 → 按 [TIERS] 逐级去掉可选项重试，[tierName] 记录最终生效档。
 */
internal class SdH264Encoder(
    private val cfg: Config,
    /** 每个编码输出（Annex-B，含起始码）；isKey=IDR 帧。在编码线程回调。 */
    private val onFrame: (data: ByteArray, ptsUs: Long, isKey: Boolean) -> Unit,
    /** 编码器内部错误（MediaCodec.onError），引擎据此重建 */
    private val onFatal: (String) -> Unit,
) {
    data class Config(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateKbps: Int,
        /** MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_*：1=VBR 2=CBR */
        val bitrateMode: Int,
        val qpIMin: Int,
        val qpIMax: Int,
        val qpPMin: Int,
        val qpPMax: Int,
        val gopSec: Int,
    )

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val OPT_QP = 2
        private const val OPT_PROFILE = 4
        private const val OPT_EXTRA = 8   // priority / operating-rate / color-range / prepend-sps-pps / 统计

        /** 逐级降级：名称 → 启用的可选项 */
        private val TIERS = listOf(
            "1 全部(QP上限)" to (OPT_QP or OPT_PROFILE or OPT_EXTRA),
            "2 去掉QP上限" to (OPT_PROFILE or OPT_EXTRA),
            "3 去掉Profile/Level" to OPT_EXTRA,
            "4 最简配置" to 0,
        )
    }

    private val thread = HandlerThread("sd-h264-enc").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var codec: MediaCodec? = null

    /** Camera2 往这里画；[start] 成功后可用 */
    var inputSurface: Surface? = null
        private set
    var codecName: String = ""
        private set
    var tierName: String = ""
        private set
    /** configure 时 QP 键是否随档位设进去了（1/2 档 = 是） */
    var qpConfigured: Boolean = false
        private set
    var outputFormatDesc: String = ""
        private set
    /** 实际设进编码器的码率模式：要 CBR 但编码器不支持时退回 VBR */
    var actualBitrateMode: Int = cfg.bitrateMode
        private set
    val config: Config get() = cfg

    @Volatile private var csd: ByteArray? = null

    // —— 每秒统计（编码线程写，统计线程读快照）——
    private var secBytes = 0L
    private var secFrames = 0
    private var secQpSum = 0L
    private var secQpCount = 0
    private var secPBytes = 0L
    private var secPCount = 0
    private var secPMax = 0
    private var secStartMs = 0L
    @Volatile var statKbps = 0
        private set
    @Volatile var statFps = 0
        private set
    /** -1 = 编码器不报 QP（Android 13 以下或编码器不支持） */
    @Volatile var statQp = -1
        private set
    @Volatile var statPAvgBytes = 0
        private set
    @Volatile var statPMaxBytes = 0
        private set
    /** 最近一个 IDR 帧大小（字节） */
    @Volatile var lastIBytes = 0
        private set
    @Volatile var keyFrames = 0
        private set
    @Volatile var framesTotal = 0L
        private set

    /** @throws IllegalStateException 所有降级档都配置失败 */
    fun start() {
        var lastError: Throwable? = null
        for ((name, opts) in TIERS) {
            val c = try { MediaCodec.createEncoderByType(MIME) } catch (t: Throwable) {
                throw IllegalStateException("创建 H.264 编码器失败: ${t.message}", t)
            }
            actualBitrateMode = supportedBitrateMode(c, cfg.bitrateMode)
            val format = buildFormat(opts)
            try {
                c.setCallback(callback, handler)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = c.createInputSurface()
                c.start()
                codec = c
                codecName = c.name
                tierName = name
                qpConfigured = opts and OPT_QP != 0
                SdLog.i("编码器就绪 ${c.name} 降级档[$name] ${cfg.width}x${cfg.height}@${cfg.fps} 目标${cfg.bitrateKbps}kbps" +
                        " ${if (actualBitrateMode == 2) "CBR" else "VBR"}${if (actualBitrateMode != cfg.bitrateMode) "(编码器不支持CBR，退回VBR)" else ""} GOP${cfg.gopSec}s" +
                        " QP上限 ${if (qpConfigured) "${cfg.qpPMax}（I/P 同一个，不设下限）" else "未设(编码器不认)"}")
                return
            } catch (t: Throwable) {
                lastError = t
                SdLog.w("编码器档位[$name] 配置失败: ${t.message}")
                try { c.release() } catch (_: Throwable) {}
                inputSurface?.release()
                inputSurface = null
            }
        }
        throw IllegalStateException("编码器所有降级档都配置失败: ${lastError?.message}", lastError)
    }

    private fun buildFormat(opts: Int): MediaFormat {
        val f = MediaFormat.createVideoFormat(MIME, cfg.width, cfg.height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrateKbps * 1000)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
        // 相机 AE 区间没有正好 [fps,fps] 时会跑更高的固定档（20→[24,24]、25→[30,30]），输入 Surface 按 fps 均匀丢帧
        if (Build.VERSION.SDK_INT >= 29) f.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, cfg.fps.toFloat())
        if (Build.VERSION.SDK_INT >= 25) {
            f.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, cfg.gopSec.toFloat())
        } else {
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, cfg.gopSec)
        }
        f.setInteger(MediaFormat.KEY_BITRATE_MODE, actualBitrateMode)
        if (opts and OPT_PROFILE != 0) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            f.setInteger(MediaFormat.KEY_LEVEL, avcLevelFor(cfg.width, cfg.height, cfg.fps))
        }
        if (opts and OPT_EXTRA != 0) {
            f.setInteger(MediaFormat.KEY_PRIORITY, 0)
            f.setInteger(MediaFormat.KEY_OPERATING_RATE, cfg.fps)
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
            f.setInteger("prepend-sps-pps-to-idr-frames", 1)
            if (Build.VERSION.SDK_INT >= 33) f.setInteger("video-encoding-statistics-level", 1)
            // §143 编码器最多压 1 帧再出（实测 采集→编码完成 ≈140ms）；不认的机型配置失败会自动降到下一档
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        if (opts and OPT_QP != 0) {
            // 不设下限：画面简单 / 降噪后编码器能把 QP 降下去多花码率换细节（有下限时会顶在下限，码率花不完）
            f.setInteger("video-qp-i-max", cfg.qpPMax)
            f.setInteger("video-qp-p-max", cfg.qpPMax)
            f.setInteger("video-qp-b-max", cfg.qpPMax)
        }
        return f
    }

    /** H.264 附录 A：按宏块数/宏块速率选最低够用的 Level（1080p30=4.0 … 4K30=5.1） */
    private fun avcLevelFor(w: Int, h: Int, fps: Int): Int {
        val mbs = ((w + 15) / 16) * ((h + 15) / 16)
        val mbps = mbs.toLong() * fps
        return when {
            mbs <= 8192 && mbps <= 245_760 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
            mbs <= 8704 && mbps <= 522_240 -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
            mbs <= 22_080 && mbps <= 589_824 -> MediaCodecInfo.CodecProfileLevel.AVCLevel5
            mbs <= 36_864 && mbps <= 983_040 -> MediaCodecInfo.CodecProfileLevel.AVCLevel51
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel52
        }
    }

    private fun supportedBitrateMode(c: MediaCodec, want: Int): Int = try {
        val caps = c.codecInfo.getCapabilitiesForType(MIME).encoderCapabilities
        if (caps == null || caps.isBitrateModeSupported(want)) want else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
    } catch (_: Throwable) { want }

    /** 立即要一个关键帧 */
    fun requestKeyFrame() {
        val c = codec ?: return
        handler.post {
            try {
                c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            } catch (_: Throwable) {}
        }
    }

    /** 运行中改目标码率，不重建编码器 */
    fun setBitrate(kbps: Int) {
        val c = codec ?: return
        handler.post {
            try {
                c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000) })
            } catch (t: Throwable) {
                SdLog.w("改码率失败: ${t.message}")
            }
        }
    }

    /**
     * 运行中改 P 帧 QP 上限（Android 12+ 标准键可在 setParameters 里用）。
     * setParameters 对不认识的键是静默忽略的——**是否真的生效要看统计行的平均 QP**（Android 13+ 才有）。
     * @return false = 系统版本不支持运行中改 QP（调用方改用降码率）
     */
    fun setPMaxQp(qpMax: Int): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val c = codec ?: return false
        handler.post {
            try {
                c.setParameters(Bundle().apply {
                    putInt("video-qp-p-max", qpMax)
                    putInt("video-qp-b-max", qpMax)
                })
            } catch (t: Throwable) {
                SdLog.w("运行中改 P 帧 QP 上限失败: ${t.message}")
            }
        }
        return true
    }

    /** 运行中改 I 帧 QP 上限（拥塞保护用；同 [setPMaxQp]，Android 12+） */
    fun setIMaxQp(qpMax: Int): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val c = codec ?: return false
        handler.post {
            try {
                c.setParameters(Bundle().apply {
                    putInt("video-qp-i-max", qpMax)
                })
            } catch (t: Throwable) {
                SdLog.w("运行中改 I 帧 QP 上限失败: ${t.message}")
            }
        }
        return true
    }

    fun stop() {
        val c = codec
        codec = null
        handler.post {
            try { c?.stop() } catch (_: Throwable) {}
            try { c?.release() } catch (_: Throwable) {}
            inputSurface?.release()
            inputSurface = null
            thread.quitSafely()
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    buf.get(data)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        csd = data
                    } else {
                        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        if (isKey) keyFrames++
                        framesTotal++
                        if (Build.VERSION.SDK_INT >= 33) readQp(codec, index)
                        val out = if (isKey && !SdH264Nal.containsSps(data)) (csd ?: ByteArray(0)) + data else data
                        accountStats(out.size, isKey)
                        onFrame(out, info.presentationTimeUs, isKey)
                    }
                }
            } catch (t: Throwable) {
                SdLog.w("处理编码输出失败: ${t.message}")
            } finally {
                try { codec.releaseOutputBuffer(index, false) } catch (_: Throwable) {}
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            SdLog.w("编码器错误: ${e.diagnosticInfo} recoverable=${e.isRecoverable} transient=${e.isTransient}")
            if (!e.isTransient) onFatal("编码器错误 ${e.diagnosticInfo}")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val p = if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else -1
            val l = if (format.containsKey(MediaFormat.KEY_LEVEL)) format.getInteger(MediaFormat.KEY_LEVEL) else -1
            val desc = "profile=$p level=$l"
            // 三星等 Codec2 编码器每帧都回调一次（带逐帧 QP），内容不变时不打日志
            if (desc == outputFormatDesc) return
            outputFormatDesc = desc
            SdLog.i("编码器输出格式 ${cfg.width}x${cfg.height} $outputFormatDesc")
        }
    }

    private fun readQp(codec: MediaCodec, index: Int) {
        try {
            val f = codec.getOutputFormat(index)
            if (f.containsKey("video-qp-average")) {
                secQpSum += f.getInteger("video-qp-average")
                secQpCount++
            }
        } catch (_: Throwable) {}
    }

    private fun accountStats(bytes: Int, isKey: Boolean) {
        val now = System.currentTimeMillis()
        if (secStartMs == 0L) secStartMs = now
        secBytes += bytes
        secFrames++
        if (isKey) {
            lastIBytes = bytes
        } else {
            secPBytes += bytes
            secPCount++
            if (bytes > secPMax) secPMax = bytes
        }
        val elapsed = now - secStartMs
        if (elapsed >= 1000) {
            statKbps = (secBytes * 8 / elapsed).toInt()
            statFps = (secFrames * 1000 / elapsed).toInt()
            statQp = if (secQpCount > 0) (secQpSum / secQpCount).toInt() else -1
            statPAvgBytes = if (secPCount > 0) (secPBytes / secPCount).toInt() else 0
            statPMaxBytes = secPMax
            secBytes = 0; secFrames = 0; secQpSum = 0; secQpCount = 0
            secPBytes = 0; secPCount = 0; secPMax = 0
            secStartMs = now
        }
    }
}
