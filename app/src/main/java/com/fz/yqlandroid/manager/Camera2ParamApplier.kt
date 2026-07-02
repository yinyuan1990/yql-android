package com.fz.yqlandroid.manager

import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Range
import android.view.Surface
import org.webrtc.CameraVideoCapturer

/**
 * 🔥 原生采集器的“按需”硬件控制层（对标参考产品 y60373 CameraActivity.applyCamera2Params）
 *
 * 背景（见 docs/PROGRESS.md 十五）：
 *   原自定义 Camera2ControlCapturer 自建采集管线 + 常驻手动曝光，是 Android 端发热主因。
 *   改用 WebRTC 原生 Camera2Capturer 后，用反射拿到原生 Camera2Session 内部的
 *   captureSession/cameraDevice/cameraCharacteristics/cameraThreadHandler/surface，
 *   在“用户拖动 / 后端下发”时临时新建一次 setRepeatingRequest 注入曝光/对焦/变焦/快门/白平衡。
 *   —— 不常驻、不每帧，兼顾低发热与可控性。
 *
 * ⭐ 快门(cjfps)保留：反射通道同样可写 SENSOR_EXPOSURE_TIME + AE_OFF + 手动 ISO。
 * ⭐ 安全兜底：反射/写入任何异常一律吞掉，绝不影响推流（字段名随库实现可能变化）。
 *
 * 反射字段名与 WebRTC(Camera2Capturer/Camera2Session) 一致；本项目使用
 * io.getstream:stream-webrtc-android:1.1.1（同源 chromium webrtc），字段名沿用。
 */
object Camera2ParamApplier {

    private const val TAG = "Camera2ParamApplier"

    /** 一次控制的完整参数集合（缺省值=不改/自动）。 */
    data class Params(
        val exposureEv: Float? = null,     // AE 曝光补偿(EV)，仅在未启用手动快门时生效
        val focus: Float? = null,          // 0..1；0.5=连续自动对焦，其余=手动对焦距离
        val zoom: Float? = null,           // >=1.0；变焦
        val shutterCjfps: Int? = null,     // 快门 1/cjfps 秒(60~600)，非空=手动曝光(AE OFF)
        val manualIso: Int? = null,        // 手动 ISO（快门模式下）
        val whiteBalanceSlider: Int? = null, // 0..100 手动色温(0冷100暖)
        val whiteBalanceLocked: Boolean = false, // 锁定当前白平衡
        val targetFps: Int? = null         // 🔥 钉死 AE 帧率区间 [fps,fps]，防低光自动砍半(30→15)
    )

    /**
     * 反射取原生 session 句柄并应用参数（一次 setRepeatingRequest）。
     * @return true=已下发；false=反射失败/会话未就绪（已安全忽略）
     */
    fun apply(capturer: CameraVideoCapturer?, p: Params): Boolean {
        val cap = capturer ?: return false
        return try {
            // 1) currentSession 在 CameraCapturer 父类
            val superclass = cap.javaClass.superclass ?: return false
            val sessionField = superclass.getDeclaredField("currentSession").apply { isAccessible = true }
            val session = sessionField.get(cap) ?: return false
            val sc = session.javaClass

            val captureSession = sc.getDeclaredField("captureSession").apply { isAccessible = true }
                .get(session) as? CameraCaptureSession ?: return false
            val cameraDevice = sc.getDeclaredField("cameraDevice").apply { isAccessible = true }
                .get(session) as? CameraDevice ?: return false
            val characteristics = sc.getDeclaredField("cameraCharacteristics").apply { isAccessible = true }
                .get(session) as? CameraCharacteristics ?: return false
            val handler = sc.getDeclaredField("cameraThreadHandler").apply { isAccessible = true }
                .get(session) as? Handler ?: return false
            val surface = sc.getDeclaredField("surface").apply { isAccessible = true }
                .get(session) as? Surface ?: return false

            // 2) 新建 TEMPLATE_RECORD 请求，写入参数
            val builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(surface)

            applyExposureAndShutter(builder, characteristics, p)
            applyFpsRange(builder, characteristics, p)
            applyFocus(builder, characteristics, p)
            applyZoom(builder, characteristics, p)
            applyWhiteBalance(builder, characteristics, p)

            // 3) 一次性下发（不常驻循环）
            captureSession.setRepeatingRequest(builder.build(), null, handler)
            Log.d(TAG, "✅ 参数已注入(原生session): ev=${p.exposureEv} focus=${p.focus} zoom=${p.zoom} cjfps=${p.shutterCjfps} wb=${p.whiteBalanceSlider}/${p.whiteBalanceLocked}")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "⚠️ 反射注入失败(已忽略，不影响推流): ${e.message}")
            false
        }
    }

    // ===== 曝光 / 快门 =====
    private fun applyExposureAndShutter(b: CaptureRequest.Builder, c: CameraCharacteristics, p: Params) {
        try {
            val cj = p.shutterCjfps
            if (cj != null && cj > 0) {
                // 🔥 手动快门(保留 cjfps 能力)：AE OFF + SENSOR_EXPOSURE_TIME + 手动 ISO
                val expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                var expNs = 1_000_000_000L / cj
                if (expRange != null) expNs = expNs.coerceIn(expRange.lower, expRange.upper)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                val iso = p.manualIso ?: isoRange?.let { (it.lower + it.upper) / 2 } ?: 400
                val safeIso = if (isoRange != null) iso.coerceIn(isoRange.lower, isoRange.upper) else iso
                b.set(CaptureRequest.SENSOR_SENSITIVITY, safeIso)
            } else {
                // 自动曝光 + 曝光补偿(EV)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AE_LOCK, false)
                val ev = p.exposureEv
                if (ev != null) {
                    val step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    val range = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    if (step != null && range != null && step.toDouble() > 0) {
                        val steps = Math.round(ev / step.toFloat()).coerceIn(range.lower, range.upper)
                        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "exposure/shutter 写入失败: ${e.message}")
        }
    }

    // ===== AE 帧率区间（防低光降帧） =====
    // WebRTC Camera2Session 选 AE 区间偏好宽区间(如[15,30])，暗光时 AE 拉长曝光把帧率砍半(30→15)。
    // 这里改为钉死：优先选 [fps,fps] 固定区间；没有则选下界最高且能覆盖 fps 的区间。
    private fun applyFpsRange(b: CaptureRequest.Builder, c: CameraCharacteristics, p: Params) {
        try {
            val fps = p.targetFps ?: return
            if (fps <= 0) return
            // 手动快门(AE OFF)时帧率由 SENSOR_EXPOSURE_TIME 决定，AE 区间无效，跳过
            if (p.shutterCjfps != null && p.shutterCjfps > 0) return
            val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return
            val exact = ranges.firstOrNull { it.lower == fps && it.upper == fps }
            val best = exact ?: ranges
                .filter { it.upper >= fps }
                .maxWithOrNull(compareBy({ it.lower }, { -it.upper }))
            if (best != null) {
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(best.lower, best.upper))
                Log.d(TAG, "🎞️ AE帧率区间钉死: 目标${fps}fps → [${best.lower},${best.upper}]")
            }
        } catch (e: Exception) {
            Log.w(TAG, "fpsRange 写入失败: ${e.message}")
        }
    }

    // ===== 对焦 =====
    private fun applyFocus(b: CaptureRequest.Builder, c: CameraCharacteristics, p: Params) {
        try {
            val f = p.focus ?: return
            val minFocusDist = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (f == 0.5f || minFocusDist <= 0f) {
                // 连续自动对焦
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            } else {
                // 手动对焦：1=远(0屈光度) 0=近(minFocusDist)
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, (1f - f).coerceIn(0f, 1f) * minFocusDist)
            }
        } catch (e: Exception) {
            Log.w(TAG, "focus 写入失败: ${e.message}")
        }
    }

    // ===== 变焦 =====
    private fun applyZoom(b: CaptureRequest.Builder, c: CameraCharacteristics, p: Params) {
        try {
            val z = (p.zoom ?: return).coerceAtLeast(1.0f)
            val maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
            val zc = z.coerceIn(1.0f, maxZoom)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (range != null) {
                    b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zc.coerceIn(range.lower, range.upper))
                    return
                }
            }
            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            if (zc > 1.0f) {
                val cropW = (active.width() / zc).toInt()
                val cropH = (active.height() / zc).toInt()
                val x = (active.width() - cropW) / 2
                val y = (active.height() - cropH) / 2
                b.set(CaptureRequest.SCALER_CROP_REGION, Rect(x, y, x + cropW, y + cropH))
            } else {
                b.set(CaptureRequest.SCALER_CROP_REGION, active)
            }
        } catch (e: Exception) {
            Log.w(TAG, "zoom 写入失败: ${e.message}")
        }
    }

    // ===== 白平衡 =====
    private fun applyWhiteBalance(b: CaptureRequest.Builder, c: CameraCharacteristics, p: Params) {
        try {
            val slider = p.whiteBalanceSlider
            when {
                slider != null -> {
                    b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                    b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    val t = slider.coerceIn(0, 100) / 100f
                    val rGain = 1.2f + t * 1.3f
                    val bGain = 2.5f - t * 1.3f
                    b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(rGain, 1.0f, 1.0f, bGain))
                }
                p.whiteBalanceLocked -> b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                else -> {
                    b.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                    b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "whiteBalance 写入失败: ${e.message}")
        }
    }
}
