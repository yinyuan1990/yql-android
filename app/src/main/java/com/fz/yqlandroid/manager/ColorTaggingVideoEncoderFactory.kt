package com.fz.yqlandroid.manager

import android.util.Log
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoFrame
import java.nio.ByteBuffer

/**
 * 🎨 颜色管线对标 iOS —— 给 H264 码流补回 BT.709 + full-range 色彩描述（VUI）
 *
 * iOS(NV12MetalProcessor) 在编码前给 CVPixelBuffer 打上
 *   YCbCrMatrix / ColorPrimaries / TransferFunction = ITU_R_709_2 + full-range，
 * 使 H264 携带正确的 colour_description，PC 端才能按满范围 BT.709 还原（否则红色发暗、整体偏色）。
 *
 * Android 的 stream-webrtc-android 硬编码器默认**不写 VUI 色彩描述**，PC 端多按 BT.601 有限范围猜测，
 * 于是与 iOS 出现色差。本包装器在**编码之后、仅关键帧**上改写 SPS 的 VUI：
 *   video_signal_type_present_flag=1, video_full_range_flag=1,
 *   colour_description_present_flag=1, primaries=transfer=matrix=1(BT.709)。
 *
 * ⭐ 低发热设计：只处理关键帧（SPS 只出现在关键帧），P 帧原样透传，几乎零额外功耗。
 * ⭐ 安全兜底：任何解析异常一律返回原始帧，绝不影响推流。
 */
class ColorTaggingVideoEncoderFactory(
    private val delegate: VideoEncoderFactory
) : VideoEncoderFactory {

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = delegate.supportedCodecs

    override fun createEncoder(info: VideoCodecInfo?): VideoEncoder? {
        val inner = info?.let { delegate.createEncoder(it) } ?: return null
        // 仅包装 H264；VP8/VP9/AV1 不涉及本次色彩对齐，原样返回
        return if (info.name.equals("H264", ignoreCase = true)) {
            Log.d(TAG, "🎨 包装 H264 编码器以补 BT.709+full-range VUI")
            H264ColorTagEncoder(inner)
        } else {
            inner
        }
    }

    companion object { private const val TAG = "ColorTagEncoder" }
}

/** 包装单个 H264 VideoEncoder：拦截回调，在关键帧改写 SPS 的 VUI。 */
private class H264ColorTagEncoder(private val inner: VideoEncoder) : VideoEncoder {

    override fun initEncode(settings: VideoEncoder.Settings?, cb: VideoEncoder.Callback?): VideoCodecStatus {
        val wrapped = if (cb == null) null else VideoEncoder.Callback { frame, info ->
            cb.onEncodedFrame(maybeTag(frame), info)
        }
        return inner.initEncode(settings, wrapped)
    }

    override fun release(): VideoCodecStatus = inner.release()

    override fun encode(frame: VideoFrame?, info: VideoEncoder.EncodeInfo?): VideoCodecStatus =
        inner.encode(frame, info)

    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation?, framerate: Int): VideoCodecStatus =
        inner.setRateAllocation(allocation, framerate)

    override fun setRates(rcParameters: VideoEncoder.RateControlParameters?): VideoCodecStatus =
        inner.setRates(rcParameters)

    override fun getScalingSettings(): VideoEncoder.ScalingSettings = inner.scalingSettings

    override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> =
        inner.resolutionBitrateLimits

    override fun getImplementationName(): String = inner.implementationName

    override fun getEncoderInfo(): VideoEncoder.EncoderInfo = inner.encoderInfo

    override fun isHardwareEncoder(): Boolean = inner.isHardwareEncoder

    private fun maybeTag(frame: EncodedImage): EncodedImage {
        return try {
            if (frame.frameType != EncodedImage.FrameType.VideoFrameKey) return frame
            val src = frame.buffer ?: return frame
            val data = ByteArray(src.remaining())
            // duplicate 避免扰动原 buffer 的 position（原帧仍由内部编码器负责释放）
            src.duplicate().get(data)

            val modified = H264VuiEditor.setBt709FullRange(data) ?: return frame
            if (modified.contentEquals(data)) return frame

            val out = ByteBuffer.allocateDirect(modified.size)
            out.put(modified)
            out.rewind()

            EncodedImage.builder()
                .setBuffer(out, null)
                .setEncodedWidth(frame.encodedWidth)
                .setEncodedHeight(frame.encodedHeight)
                .setCaptureTimeNs(frame.captureTimeNs)
                .setFrameType(frame.frameType)
                .setRotation(frame.rotation)
                .setQp(frame.qp)
                .createEncodedImage()
        } catch (e: Throwable) {
            Log.w(TAG, "VUI 改写失败，透传原帧: ${e.message}")
            frame
        }
    }

    companion object { private const val TAG = "H264ColorTag" }
}
