package com.fz.yqlandroid.manager.srtdirect

import android.util.Log
import com.fz.yqlandroid.config.APIConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * §143 SRT直推诊断日志上报（总后台「SRT管理 → SRT日志」开关；与 P2P/OTG 日志开关相互独立）
 *
 * - 内容：[SdLog] 的全部行（引擎每秒统计：采集/编码/SRT 发送/RTT/丢包/峰值控制、曝光、重连…），
 *   不走 logcat，直接进缓冲，华为等丢 Log.d 的 ROM 也能拿到。
 * - 前缀 "Android-srt"；streamId = 推流ID（与 PC 拉流端 pc-srt 同目录）
 * - 开关：GET /api/srtlog/config（推流期间每 60s 复查）；关 → 不缓冲、不上传
 * - 上报：POST /api/srtlog/upload（每 10s 一批，单批上限 256KB）
 * - 生命周期：WebRTCManager SRT直推开始推流 start，停止推流 stop
 */
internal object SrtLogReporter {
    private const val TAG = "SrtLogReporter"
    private const val PREFIX = "Android-srt"
    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val CONFIG_INTERVAL_MS = 60_000L
    private const val MAX_BATCH_BYTES = 256 * 1024
    private const val MAX_BUFFER_CHARS = 1_000_000      // 网络长时间不通时防内存膨胀

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tsFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    private var configJob: Job? = null
    private var flushJob: Job? = null
    private val buffer = StringBuilder()
    private val lock = Any()

    @Volatile private var active = false
    @Volatile private var enabled = false
    @Volatile private var configKnown = false
    @Volatile private var streamId = ""
    @Volatile private var username = ""

    /** 开始推流（SRT直推）时调用 */
    fun start(newStreamId: String, account: String) {
        streamId = newStreamId
        username = account
        if (active) return
        active = true
        configKnown = false
        line("==== 推流开始 流=$newStreamId 账号=$account 机型=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}" +
                " Android${android.os.Build.VERSION.RELEASE} App=${com.fz.yqlandroid.BuildConfig.VERSION_NAME} ====")
        SdCameraSource.lensDesc?.let { line(it) }
        // 上次 App 闪退留下的堆栈（SdCrashLog）：闪退那次的日志来不及上传，放到这次开头
        SdCrashLog.takePending()?.let { crash ->
            line("💥 上次 App 闪退现场（下面是堆栈，最多 40 行）：")
            crash.lineSequence().filter { it.isNotBlank() }.take(40).forEach { line("💥   ${it.trim()}") }
        }
        configJob = scope.launch {
            while (isActive && active) {
                checkConfig()
                delay(CONFIG_INTERVAL_MS)
            }
        }
        flushJob = scope.launch {
            while (isActive && active) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    /** 停止推流时调用：最后一批冲刷后停 */
    fun stop() {
        if (!active) return
        line("==== 推流结束 ====")
        active = false
        configJob?.cancel(); configJob = null
        flushJob?.cancel(); flushJob = null
        scope.launch { flush(force = true) }
    }

    /** 线程安全：缓冲一行（未激活 / 已确认开关关 → 直接丢） */
    fun line(msg: String) {
        if (!active) return
        if (configKnown && !enabled) return
        synchronized(lock) {
            if (buffer.length > MAX_BUFFER_CHARS) return
            buffer.append('[').append(tsFmt.get()!!.format(Date())).append("] ").append(msg).append('\n')
        }
    }

    private fun checkConfig() {
        try {
            val req = Request.Builder().url("${APIConfig.BASE_URL}/api/srtlog/config").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string() ?: return
                val newEnabled = JSONObject(body).optBoolean("enabled", false)
                if (newEnabled != enabled || !configKnown) Log.d(TAG, "SRT日志开关: ${if (newEnabled) "开" else "关"}")
                enabled = newEnabled
                configKnown = true
                if (!newEnabled) synchronized(lock) { buffer.setLength(0) }
            }
        } catch (_: Exception) {
            // 网络异常保持现状，下轮再查
        }
    }

    private fun flush(force: Boolean = false) {
        if (!enabled || (!active && !force)) return
        var content: String
        synchronized(lock) {
            if (buffer.isEmpty()) return
            content = buffer.toString()
            buffer.setLength(0)
        }
        if (content.toByteArray().size > MAX_BATCH_BYTES) {
            content = content.substring(content.length - MAX_BATCH_BYTES / 3)   // 超限只留最新
        }
        try {
            val json = JSONObject().apply {
                put("prefix", PREFIX)
                put("streamId", streamId.ifEmpty { "unknown" })
                put("username", username)
                put("content", content)
            }
            val req = Request.Builder()
                .url("${APIConfig.BASE_URL}/api/srtlog/upload")
                .post(json.toString().toRequestBody(jsonType))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (body != null && !JSONObject(body).optBoolean("enabled", true)) enabled = false
            }
        } catch (_: Exception) {
            // 失败丢弃本批，不重试防堆积
        }
    }
}
