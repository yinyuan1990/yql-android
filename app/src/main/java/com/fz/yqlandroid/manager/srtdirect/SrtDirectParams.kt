package com.fz.yqlandroid.manager.srtdirect

import android.content.Context
import org.json.JSONObject

/**
 * §134 SRT直推编码/传输参数（契约 `srtParams`）。
 *
 * 生效值 = 内置默认 ⊕ 登录下发（整份覆盖）⊕ STOMP `ptype=srt_params`（只带改动项，增量合并）。
 * 结构：
 * ```
 * { presets: { low|standard|high|ultra|p4k: {width,height,fps,bitrateKbps, …可选覆盖任意全局键} },
 *   bitrateMode:1(VBR)/2(CBR), qpIMin, qpIMax, qpPMin, qpPMax, gopSec, latencyMs, maxBwKbps(0=不限),
 *   peakKbps, peakCtrl:{enable,qpStep,qpCeil,holdSec,recoverSec}, disableStabilization,
 *   sharpness(边缘锐化 0~1，可不填 = 相机默认) }
 * ```
 * 档位里可再带 `peakKbps/latencyMs/gopSec/qp*` 等，覆盖全局值（如 4K 档峰值 24000、延迟 1000）。
 */
object SrtDirectParams {
    private const val PREFS = "srt_direct_prefs"
    private const val KEY_OVERRIDE = "override_json"
    const val DEFAULT_HOST = "118.178.33.144"
    const val DEFAULT_PORT = 8890

    val PROFILES = listOf("low", "standard", "high", "ultra", "p4k", "srt2k", "srt4k")

    /** 内置默认（§133.1 起步参数） */
    private val DEFAULT_JSON = """
        {
          "presets": {
            "low":      {"width":640, "height":480, "fps":25,"bitrateKbps":1500},
            "standard": {"width":1024,"height":768, "fps":25,"bitrateKbps":2500},
            "high":     {"width":1440,"height":1080,"fps":25,"bitrateKbps":4500},
            "ultra":    {"width":1280,"height":720, "fps":50,"bitrateKbps":4000},
            "p4k":      {"width":1920,"height":1440,"fps":25,"bitrateKbps":6000},
            "srt2k":    {"width":2560,"height":1440,"fps":25,"bitrateKbps":10000,"peakKbps":16000},
            "srt4k":    {"width":3840,"height":2160,"fps":25,"bitrateKbps":16000,
                         "peakKbps":24000,"latencyMs":1000,"gopSec":3,"qpPMin":25,"qpPMax":34}
          },
          "bitrateMode": 1,
          "qpIMin": 22, "qpIMax": 30, "qpPMin": 24, "qpPMax": 33,
          "gopSec": 2, "latencyMs": 800, "maxBwKbps": 0, "peakKbps": 14000,
          "peakCtrl": {"enable": true, "qpStep": 2, "qpCeil": 38, "holdSec": 2, "recoverSec": 5},
          "disableStabilization": true
        }
    """.trimIndent()

    /** 解析后的一套参数（档位已与全局合并） */
    data class Plan(
        val profile: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateKbps: Int,
        val bitrateMode: Int,
        val qpIMin: Int,
        val qpIMax: Int,
        val qpPMin: Int,
        val qpPMax: Int,
        val gopSec: Int,
        val latencyMs: Int,
        val maxBwKbps: Int,
        val peakKbps: Int,
        val peakEnable: Boolean,
        val qpStep: Int,
        val qpCeil: Int,
        val holdSec: Int,
        val recoverSec: Int,
        val disableStabilization: Boolean,
    ) {
        fun describe(): String =
            "$profile ${width}x${height}@$fps ${bitrateKbps}kbps ${if (bitrateMode == 2) "CBR" else "VBR"}" +
                    " QP I$qpIMin~$qpIMax P$qpPMin~$qpPMax GOP${gopSec}s 延迟${latencyMs}ms" +
                    " 限速${if (maxBwKbps > 0) "${maxBwKbps}kbps" else "不限"} 峰值${peakKbps}kbps" +
                    " 峰值控制${if (peakEnable) "开(步长$qpStep 封顶$qpCeil 持续${holdSec}s 回落${recoverSec}s)" else "关"}" +
                    " 关防抖=$disableStabilization"
    }

    @Volatile private var override: JSONObject = JSONObject()
    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_OVERRIDE, null)
        override = try { if (s.isNullOrBlank()) JSONObject() else JSONObject(s) } catch (_: Exception) { JSONObject() }
        loaded = true
    }

    /** 登录下发：整份替换覆盖项（null/空 = 回内置默认） */
    fun saveFromLogin(ctx: Context, json: String?) {
        override = try { if (json.isNullOrBlank() || json == "null") JSONObject() else JSONObject(json) } catch (e: Exception) {
            SdLog.w("登录下发 srtParams 解析失败（用内置默认）: ${e.message}")
            JSONObject()
        }
        loaded = true
        persist(ctx)
        SdLog.i("登录下发 srtParams ${if (override.length() == 0) "无（用内置默认）" else "已保存 ${override.toString().take(300)}"}")
    }

    /** STOMP `ptype=srt_params`：增量合并（只带改动项）。返回合并后的覆盖项 */
    fun mergeRemote(ctx: Context, patch: JSONObject) {
        load(ctx)
        val merged = JSONObject(override.toString())
        deepMerge(merged, patch)
        override = merged
        persist(ctx)
    }

    /** 总后台「SRT管理→参数设置」的边缘锐化 0~1（没配 = null，用相机默认） */
    fun sharpness(ctx: Context): Float? {
        load(ctx)
        if (!override.has("sharpness") || override.isNull("sharpness")) return null
        val v = override.optDouble("sharpness", Double.NaN)
        return if (v.isNaN()) null else v.toFloat().coerceIn(0f, 1f)
    }

    /** 登录下发的 srtServer {host, port} */
    fun saveServer(ctx: Context, host: String?, port: Int?) {
        val p = ctx.getSharedPreferences("token_prefs", Context.MODE_PRIVATE).edit()
        if (host.isNullOrBlank()) p.remove("srt_direct_host") else p.putString("srt_direct_host", host.trim())
        if (port == null || port <= 0) p.remove("srt_direct_port") else p.putInt("srt_direct_port", port)
        p.apply()
    }

    fun serverHost(ctx: Context): String =
        ctx.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
            .getString("srt_direct_host", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST

    fun serverPort(ctx: Context): Int =
        ctx.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
            .getInt("srt_direct_port", DEFAULT_PORT).takeIf { it in 1..65535 } ?: DEFAULT_PORT

    /** 某档位的生效参数 */
    fun plan(ctx: Context, profile: String): Plan {
        load(ctx)
        val eff = JSONObject(DEFAULT_JSON)
        deepMerge(eff, override)
        val presets = eff.optJSONObject("presets") ?: JSONObject()
        val key = if (presets.has(profile)) profile else "standard"
        val pre = presets.optJSONObject(key) ?: JSONObject()
        // 档位里的同名键覆盖全局
        fun int(name: String, def: Int): Int = if (pre.has(name)) pre.optInt(name, def) else eff.optInt(name, def)
        fun bool(name: String, def: Boolean): Boolean =
            if (pre.has(name)) pre.optBoolean(name, def) else eff.optBoolean(name, def)
        val pcGlobal = eff.optJSONObject("peakCtrl") ?: JSONObject()
        val pc = JSONObject(pcGlobal.toString()).also { o -> pre.optJSONObject("peakCtrl")?.let { deepMerge(o, it) } }
        val qpIMin = int("qpIMin", 22).coerceIn(0, 51)
        val qpPMin = int("qpPMin", 24).coerceIn(0, 51)
        return Plan(
            profile = key,
            width = pre.optInt("width", 1920).coerceIn(160, 7680),
            height = pre.optInt("height", 1080).coerceIn(120, 4320),
            fps = pre.optInt("fps", 25).coerceIn(5, 60),
            bitrateKbps = pre.optInt("bitrateKbps", 8000).coerceIn(300, 80000),
            bitrateMode = if (int("bitrateMode", 1) == 2) 2 else 1,
            qpIMin = qpIMin,
            qpIMax = int("qpIMax", 30).coerceIn(qpIMin, 51),
            qpPMin = qpPMin,
            qpPMax = int("qpPMax", 33).coerceIn(qpPMin, 51),
            gopSec = int("gopSec", 2).coerceIn(1, 10),
            latencyMs = int("latencyMs", 800).coerceIn(80, 5000),
            maxBwKbps = int("maxBwKbps", 0).coerceIn(0, 200000),
            peakKbps = int("peakKbps", 14000).coerceIn(0, 200000),
            peakEnable = pc.optBoolean("enable", true),
            qpStep = pc.optInt("qpStep", 2).coerceIn(1, 10),
            qpCeil = pc.optInt("qpCeil", 38).coerceIn(0, 51),
            holdSec = pc.optInt("holdSec", 2).coerceIn(1, 30),
            recoverSec = pc.optInt("recoverSec", 5).coerceIn(1, 120),
            disableStabilization = bool("disableStabilization", true),
        )
    }

    private fun persist(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_OVERRIDE, override.toString()).apply()
    }

    /** 对象逐键递归合并；非对象值直接覆盖 */
    private fun deepMerge(base: JSONObject, patch: JSONObject) {
        val it = patch.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = patch.opt(k)
            val cur = base.opt(k)
            if (v is JSONObject && cur is JSONObject) deepMerge(cur, v) else base.put(k, v)
        }
    }
}
