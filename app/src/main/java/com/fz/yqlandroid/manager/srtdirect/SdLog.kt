package com.fz.yqlandroid.manager.srtdirect

import android.util.Log

/**
 * §134 SRT直推统一日志：`meidui` 标签 + `[SRT直推]` 前缀。
 * §143 同时直接进 [SrtLogReporter]（总后台「SRT管理 → SRT日志」开关打开时上报，文件 Android-srt.log）。
 */
internal object SdLog {
    private const val TAG = "meidui"

    fun i(msg: String) {
        Log.d(TAG, "📡 [SRT直推] $msg")
        SrtLogReporter.line(msg)
    }

    fun w(msg: String) {
        Log.d(TAG, "⚠️ [SRT直推] $msg")
        SrtLogReporter.line("⚠️ $msg")
    }

    /** streamid 末段是一次性推流 token / 拉流 JWT，不进日志 */
    fun maskStreamId(streamId: String): String {
        val parts = streamId.split(":")
        return if (parts.size >= 4 && parts[3].isNotEmpty()) {
            (parts.take(3) + "***").joinToString(":")
        } else streamId
    }
}

/**
 * SRS（WebRTC）推 1920×1440 / 2K / 4K 的诊断：进这三档才启动 [SrtLogReporter]（同一个 Android-srt.log，受「SRT日志」开关控制），
 * 行首「[SRS高清]」；只有这里启动的 reporter 才由这里在停推流时停（SRT直推的不碰）。
 */
internal object SrsHdLog {
    private const val TAG = "meidui"
    @Volatile private var streamId = ""
    @Volatile private var account = ""
    @Volatile private var startedByUs = false
    @Volatile private var lastTickMs = 0L

    fun sessionBegin(newStreamId: String, user: String) {
        streamId = newStreamId
        account = user
        lastTickMs = 0
    }

    fun sessionEnd() {
        val was = startedByUs
        startedByUs = false
        streamId = ""
        if (was) SrtLogReporter.stop()
    }

    @Synchronized
    fun line(msg: String) {
        Log.d(TAG, "📐 [SRS高清] $msg")
        if (streamId.isEmpty()) return
        if (!startedByUs) {
            startedByUs = true
            SrtLogReporter.start(streamId, account)
        }
        SrtLogReporter.line("[SRS高清] $msg")
    }

    /** 每秒统计里调用：到了间隔才返回 true */
    fun shouldTick(everyMs: Long = 5_000L): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTickMs < everyMs) return false
        lastTickMs = now
        return true
    }
}
