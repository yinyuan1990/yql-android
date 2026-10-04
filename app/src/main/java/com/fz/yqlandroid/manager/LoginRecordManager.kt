package com.fz.yqlandroid.manager

import android.content.Context
import android.util.Log
import com.fz.yqlandroid.config.APIConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 登录记录（§114）——排查「设备id 在变化」。
 *
 * 每次登录成功在本地累积追加一行：登录时间 | 机型 | 账号 | 设备id | rawAndroidId | installId | restricted。
 * 登录页点「设备id 下方空白处」把整份本地日志上传到后端，总后台「Android登录记录」页下载分析。
 *
 * - rawAndroidId / installId / restricted 一并记录：设备id = android + SHA256(ANDROID_ID+包名+盐)，
 *   ANDROID_ID 被系统限制(全零等)时回退到 SharedPreferences 随机号(重装/清数据会变)——这些字段能直接
 *   看出 id 变化是「ANDROID_ID 本身变」还是「落到回退随机号」。
 * - 本地文件累积保留全部历史（超 1MB 时裁掉最旧一半，防失控）。
 */
object LoginRecordManager {

    private const val TAG = "LoginRecordManager"
    private const val FILE_NAME = "login_records.log"
    private const val PREFIX = "Android-login"
    private const val MAX_FILE_BYTES = 1024 * 1024L   // 1MB 封顶，超了裁掉最旧一半

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun logFile(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 登录成功后追加一条记录（deviceId 传本机计算值，即 DeviceIDManager.getDeviceID 的结果） */
    @Synchronized
    fun appendRecord(context: Context, username: String, deviceId: String) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val model = "${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL} Android${android.os.Build.VERSION.RELEASE}"
            val rawAndroidId = DeviceIDManager.getRawAndroidID(context)
            val installId = DeviceIDManager.getInstallId(context)
            val restricted = DeviceIDManager.isAndroidIdRestricted(context)
            val appVer = try { com.fz.yqlandroid.BuildConfig.VERSION_NAME } catch (e: Exception) { "?" }
            val line = "$ts | 机型=$model | 账号=$username | 设备id=$deviceId | " +
                    "rawAndroidId=$rawAndroidId | installId=$installId | restricted=$restricted | appVer=$appVer\n"

            val f = logFile(context)
            f.appendText(line)
            trimIfTooLarge(f)
            Log.d(TAG, "📝 登录记录已追加: 账号=$username 设备id=${deviceId.take(12)}…")
        } catch (e: Exception) {
            Log.w(TAG, "追加登录记录失败: ${e.message}")
        }
    }

    /** 文件超上限时只保留最新一半（按行裁） */
    private fun trimIfTooLarge(f: File) {
        try {
            if (f.length() <= MAX_FILE_BYTES) return
            val lines = f.readLines()
            val keep = lines.subList(lines.size / 2, lines.size)
            f.writeText(keep.joinToString("\n", postfix = "\n"))
        } catch (e: Exception) {
            Log.w(TAG, "裁剪登录记录失败: ${e.message}")
        }
    }

    /**
     * 把整份本地登录记录上传到后端。登录页点击上传入口调用（在 IO 线程）。
     * @return true=上传成功；false=无记录或上传失败
     */
    fun uploadRecords(context: Context): Boolean {
        return try {
            val f = logFile(context)
            if (!f.exists() || f.length() == 0L) {
                Log.d(TAG, "暂无本地登录记录，不上传")
                return false
            }
            val content = f.readText()
            // deviceId / username 从最近一次登录取（token_prefs），没有就用当前计算值
            val tokenPrefs = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
            val deviceId = DeviceIDManager.getDeviceID(context)
            val username = tokenPrefs.getString("username", "") ?: ""

            val json = JSONObject().apply {
                put("prefix", PREFIX)
                put("streamId", deviceId)
                put("username", username)
                put("content", content)
            }
            val req = Request.Builder()
                .url("${APIConfig.BASE_URL}/api/loginlog/upload")
                .post(json.toString().toRequestBody(jsonType))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                val ok = resp.isSuccessful && body != null && JSONObject(body).optBoolean("success", false)
                Log.d(TAG, "上传登录记录 ${if (ok) "成功" else "失败"}: code=${resp.code}")
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "上传登录记录异常: ${e.message}")
            false
        }
    }
}
