package com.fz.yqlandroid.network

import com.fz.yqlandroid.config.APIConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 网络服务
 * 与iOS APIService 保持一致
 * 
 * 🔥 所有接口返回扁平JSON（无code/data包装），与iOS一致
 */
object NetworkService {
    
    private val gson = Gson()
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(APIConfig.REQUEST_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(APIConfig.REQUEST_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(APIConfig.REQUEST_TIMEOUT, TimeUnit.SECONDS)
        .build()
    
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    
    // ========== 登录 ==========
    
    suspend fun login(request: LoginRequest): Result<LoginResponse> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Auth.LOGIN)

            // 🔐 与iOS完全一致：AES加密 "username,password" → Base64，请求体 {data, deviceId}
            val encrypted = AESUtils.encryptLoginData(request.username, request.password)
                ?: return@withContext Result.failure(Exception("加密失败"))
            val bodyMap = mapOf(
                "data" to encrypted,
                "deviceId" to request.deviceId
            )
            val json = gson.toJson(bodyMap)
            
            println("jfh [Login] 接口: ${APIConfig.Auth.LOGIN}")
            println("jfh [Login] URL=$url")
            println("jfh [Login] deviceId=${request.deviceId}")
            
            val httpRequest = Request.Builder()
                .url(url)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                // 🔥 后端要求的 User-Agent（与iOS登录一致），header() 覆盖默认 UA 避免重复
                .header("Content-Type", "application/json")
                .header("User-Agent", "iPhone/iOS")
                .build()
            
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()
            
            println("jfh [Login] Status=${response.code}")
            println("jfh [Login] 完整Body=$body")
            
            if (response.isSuccessful && body != null) {
                val loginResponse = gson.fromJson(body, LoginResponse::class.java)
                if (!loginResponse.token.isNullOrEmpty()) {
                    println("jfh [Login] ✅ 成功: username=${loginResponse.username}, boundControlCount=${loginResponse.boundControlCount}, streamPushIp=${loginResponse.streamPushIp}")
                    Result.success(loginResponse)
                } else {
                    val msg = parseErrorMessage(body) ?: "登录失败"
                    println("jfh [Login] ❌ token为空: $msg")
                    Result.failure(Exception(msg))
                }
            } else {
                val msg = parseErrorMessage(body) ?: "请求失败: ${response.code}"
                println("jfh [Login] ❌ HTTP${response.code}: $msg")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            println("jfh [Login] ❌ 异常: ${e.message}")
            Result.failure(e)
        }
    }
    
    // ========== 注册 ==========
    
    suspend fun registerDevice(request: RegisterRequest): Result<RegisterResponse> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Auth.REGISTER_DEVICE)
            val json = gson.toJson(request)
            
            println("jfh [Register] URL=$url")
            println("jfh [Register] 请求体=$json")
            
            val httpRequest = Request.Builder()
                .url(url)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .apply { APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) } }
                .build()
            
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()
            
            println("jfh [Register] Status=${response.code}")
            println("jfh [Register] 完整Body=$body")
            
            if (response.isSuccessful && body != null) {
                // 🔥 服务器直接返回扁平JSON（无code/data包装）
                val registerResponse = gson.fromJson(body, RegisterResponse::class.java)
                if (!registerResponse.username.isNullOrEmpty()) {
                    println("jfh [Register] ✅ 成功: username=${registerResponse.username}, deviceId=${registerResponse.deviceId}")
                    Result.success(registerResponse)
                } else {
                    val msg = parseErrorMessage(body) ?: "注册失败"
                    println("jfh [Register] ❌ 失败: $msg")
                    Result.failure(Exception(msg))
                }
            } else {
                val msg = parseErrorMessage(body) ?: "请求失败: ${response.code}"
                println("jfh [Register] ❌ HTTP${response.code}: $msg")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            println("jfh [Register] ❌ 异常: ${e.message}")
            Result.failure(e)
        }
    }
    
    // ========== 获取推流Token ==========
    
    /**
     * 获取推流Token（与iOS getStreamToken完全一致）
     * POST /api/auth/stream/token/simple
     * Body: { "username": "xxx", "streamName": "xxx" }
     */
    /**
     * 获取推流Token（与iOS getStreamToken完全一致）
     * POST /api/auth/stream/token/simple
     * Body: { "username": "xxx", "streamName": "xxx" }
     */
    suspend fun getStreamToken(jwtToken: String, streamName: String, username: String = ""): Result<StreamTokenResponse> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Auth.STREAM_TOKEN)
            
            val bodyMap = mapOf(
                "username" to username,
                "streamName" to streamName
            )
            val json = gson.toJson(bodyMap)
            
            println("jfh [StreamToken] URL=$url")
            println("jfh [StreamToken] 请求体=$json")
            
            val httpRequest = Request.Builder()
                .url(url)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))  // 🔥 POST不是GET
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()
            
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string()
            
            println("jfh [StreamToken] Status=${response.code}")
            println("jfh [StreamToken] 完整Body=$body")
            
            if (response.isSuccessful && body != null) {
                val streamResponse = gson.fromJson(body, StreamTokenResponse::class.java)
                println("jfh [StreamToken] ✅ 成功: token=${streamResponse.token.take(10)}...")
                Result.success(streamResponse)
            } else {
                val msg = parseErrorMessage(body) ?: "请求失败: ${response.code}"
                println("jfh [StreamToken] ❌ $msg")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            println("jfh [StreamToken] ❌ 异常: ${e.message}")
            Result.failure(e)
        }
    }
    
    // ========== 创建绑定 ==========
    
    suspend fun createBinding(deviceUsername: String, controlUsername: String, jwtToken: String = ""): Result<CreateBindingResponse> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Binding.CREATE)
            val body = mapOf("deviceUsername" to deviceUsername, "controlUsername" to controlUsername)
            val json = gson.toJson(body)
            
            println("jfh [Binding] URL=$url")
            println("jfh [Binding] 请求体=$json")
            println("jfh [Binding] jwtToken=${if (jwtToken.isNotEmpty()) "${jwtToken.take(20)}..." else "空"}")
            
            val httpRequest = Request.Builder()
                .url(url)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    if (jwtToken.isNotEmpty()) addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()
            
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string()
            
            println("jfh [Binding] Status=${response.code}")
            println("jfh [Binding] 完整Body=$responseBody")
            
            if (response.isSuccessful && responseBody != null) {
                val result = gson.fromJson(responseBody, CreateBindingResponse::class.java)
                println("jfh [Binding] ✅ 创建成功: bindingId=${result.bindingId}, deviceUsername=${result.deviceUsername}, controlUsername=${result.controlUsername}, status=${result.status}, message=${result.message}")
                Result.success(result)
            } else {
                val msg = parseErrorMessage(responseBody) ?: "创建绑定失败: ${response.code}"
                println("jfh [Binding] ❌ 失败: $msg")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            println("jfh [Binding] ❌ 异常: ${e.message}")
            Result.failure(e)
        }
    }
    
    // ========== 验证设备绑定 ==========
    
    suspend fun verifyDeviceBinding(bindingId: Int, secondaryPassword: String, jwtToken: String): Result<VerifyDeviceResponse> = withContext(Dispatchers.IO) {
        try {
            val url = APIConfig.fullURL(APIConfig.Binding.VERIFY_DEVICE)
            val body = mapOf("bindingId" to bindingId, "secondaryPassword" to secondaryPassword)
            val json = gson.toJson(body)
            
            println("jfh [Verify] 验证绑定: bindingId=$bindingId")
            
            val httpRequest = Request.Builder()
                .url(url)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    APIConfig.defaultHeaders.forEach { (k, v) -> addHeader(k, v) }
                    addHeader("Authorization", "Bearer $jwtToken")
                }
                .build()
            
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string()
            
            println("jfh [Verify] Status=${response.code}, Body=$responseBody")
            
            if (response.isSuccessful && responseBody != null) {
                val result = gson.fromJson(responseBody, VerifyDeviceResponse::class.java)
                println("jfh [Verify] ✅ 验证成功: deviceVerified=${result.deviceVerified}")
                Result.success(result)
            } else {
                val msg = parseErrorMessage(responseBody) ?: "验证失败: ${response.code}"
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            println("jfh [Verify] ❌ 异常: ${e.message}")
            Result.failure(e)
        }
    }
    
    // ========== 工具方法 ==========
    
    /**
     * 从错误响应body提取message或error字段
     */
    private fun parseErrorMessage(body: String?): String? {
        return try {
            body?.let {
                val json = gson.fromJson(it, Map::class.java)
                json["message"] as? String ?: json["error"] as? String
            }
        } catch (_: Exception) { null }
    }
}

// ========== 请求/响应数据类 ==========

/**
 * 登录请求
 */
data class LoginRequest(
    val username: String,
    val password: String,
    val deviceId: String,
    val userType: String = "device"
)

/**
 * 登录响应（扁平结构，与iOS LoginResponse一致）
 * 服务器直接返回，无code/data包装
 */
data class LoginResponse(
    val token: String = "",
    val permanentToken: String = "",
    val username: String = "",
    val deviceId: String = "",
    val userType: String? = null,
    val userId: Int? = null,
    val nickname: String? = null,
    val streamPushIp: String? = null,
    val streamPushPort: Int? = null,
    val boundControlCount: Int? = null,
    val membershipType: String? = null,
    val status: String? = null,
    val message: String? = null,
    val banned: Int? = null,
    val trialInfo: TrialInfo? = null,
    // 🔥 连接方式（与iOS一致）："srs" | "p2p"，缺省按 srs 走
    val connectMode: String? = null,
    // 🔥 是否需要跳转扫码绑定：1=需要，0=不需要
    val scan: Int? = null
)

/**
 * 试用/激活信息（与iOS TrialInfo完全一致）
 */
data class TrialInfo(
    val trialRequired: Boolean = false,          // 是否需要试用限制
    val activated: Boolean? = null,              // 是否已激活
    val activationLevel: Int? = null,            // 激活等级 (1=标清, 2=高清, 3=超清, 4=4K)
    val activationLevelName: String? = null,     // 等级名称
    val activationExpireAt: String? = null,      // 激活到期时间
    val qualityAccess: List<String>? = null,     // 可用画质列表
    // 日试用相关
    val isDailyTrial: Boolean? = null,           // 是否日试用码激活
    val activationRemainingSeconds: Int? = null,  // 剩余有效秒数
    // 未激活时的试用状态
    val trialEnded: Boolean? = null,             // 当天试用是否已全部结束
    val currentStage: Int? = null,               // 当前试用阶段 (1-6)
    val totalStages: Int? = null,                // 总阶段数
    val stageSeconds: Int? = null,               // 当前阶段总秒数
    val remainingSeconds: Int? = null,            // 当前阶段剩余秒数
    val usedSeconds: Int? = null,                // 当前阶段已用秒数
    val message: String? = null                  // 提示信息
)

/**
 * 注册请求（与iOS DeviceRegisterRequest一致）
 */
data class RegisterRequest(
    val username: String,
    val nickname: String,
    val deviceId: String,
    val password: String,
    val secondaryPassword: String,
    val securityQuestion1: String,
    val securityAnswer1: String,
    val securityQuestion2: String,
    val securityAnswer2: String,
    val securityQuestion3: String,
    val securityAnswer3: String
)

/**
 * 注册响应（扁平结构，与iOS DeviceRegisterResponse一致）
 * 服务器返回: { "username", "deviceId", "message", "permanentToken", "userType" }
 */
data class RegisterResponse(
    val username: String? = null,
    val deviceId: String? = null,
    val message: String? = null,
    val permanentToken: String? = null,
    val userType: String? = null
)

/**
 * 推流Token响应
 */
data class StreamTokenResponse(
    val token: String = "",
    val streamKey: String? = null,
    val streamUrl: String? = null,
    val whipUrl: String? = null,
    val iceServers: List<IceServer>? = null
)

/**
 * ICE服务器配置
 */
data class IceServer(
    val urls: List<String> = emptyList(),
    val username: String? = null,
    val credential: String? = null
)

/**
 * 创建绑定响应（与iOS CreateBindingResponse一致）
 */
data class CreateBindingResponse(
    val success: Boolean = false,
    val bindingId: Int = 0,
    val deviceUsername: String = "",
    val controlUsername: String = "",
    val status: String = "",
    val deviceVerified: Boolean = false,
    val controlVerified: Boolean = false,
    val message: String = ""
)

/**
 * 验证设备绑定响应（与iOS VerifyDeviceResponse一致）
 */
data class VerifyDeviceResponse(
    val success: Boolean = false,
    val bindingId: Int = 0,
    val deviceVerified: Boolean = false,
    val controlVerified: Boolean = false,
    val status: String = "",
    val message: String = ""
)
