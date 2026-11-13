package com.fz.yqlandroid.manager.srtdirect

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SRT直推闪退现场：App 原来没有任何崩溃记录，闪退后只能从「日志突然断了 + 又重新登录」猜
 * （cd88888888 vivo V2547A 11-12：切到 SRT 后 6~9 秒重启一次，拿不到堆栈）。
 *
 * - [install]：接管未捕获异常，把线程名 + 堆栈写到 `files/sd_crash.txt`，再交回原来的处理（照常闪退）。
 * - 下次进 SRT 推流时 [SrtLogReporter.start] 调 [takePending]，把上次的堆栈写进 SRT 日志上传。
 * - [caught]：引擎 / 相机线程里被拦住没闪退的异常，直接写进 SRT 日志（同一条 10 秒内只写一次）。
 * 只管 Java / Kotlin 异常；native 崩溃（libsrt 等）抓不到。
 */
internal object SdCrashLog {
    private const val FILE = "sd_crash.txt"
    private const val MAX_CHARS = 6000

    @Volatile private var installed = false
    @Volatile private var pending: String? = null
    @Volatile private var lastCaughtKey = ""
    @Volatile private var lastCaughtMs = 0L

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val file = try { File(ctx.applicationContext.filesDir, FILE) } catch (_: Throwable) { return }
        try {
            if (file.exists()) {
                pending = file.readText().take(MAX_CHARS)
                file.delete()
            }
        } catch (_: Throwable) {}
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
                file.writeText("$time 线程=${t.name}\n" + stack(e).take(MAX_CHARS))
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** 上次闪退的堆栈（取一次就清；没有 = null） */
    fun takePending(): String? {
        val p = pending
        pending = null
        return p
    }

    /** 被拦住没闪退的异常：写进 SRT 日志（堆栈前几行） */
    fun caught(where: String, t: Throwable) {
        val key = where + t.javaClass.name + (t.stackTrace.firstOrNull()?.toString() ?: "")
        val now = System.currentTimeMillis()
        if (key == lastCaughtKey && now - lastCaughtMs < 10_000) return
        lastCaughtKey = key
        lastCaughtMs = now
        SdLog.w("⚠️ $where 异常（已拦住，没闪退）: " + stack(t).lineSequence().take(8).joinToString(" ← ") { it.trim() })
    }

    private fun stack(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
