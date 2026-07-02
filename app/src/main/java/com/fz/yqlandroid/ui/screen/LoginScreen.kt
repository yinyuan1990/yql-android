package com.fz.yqlandroid.ui.screen

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fz.yqlandroid.R
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import com.fz.yqlandroid.manager.DeviceIDManager
import com.fz.yqlandroid.navigation.AppViewModel
import com.fz.yqlandroid.network.NetworkService
import com.fz.yqlandroid.network.LoginRequest
import kotlinx.coroutines.launch

/**
 * 登录页面
 * 与iOS MonitorLoginView 保持一致
 */
@Composable
fun LoginScreen(
    appViewModel: AppViewModel,
    onNavigateToRegister: () -> Unit,
    onLoginSuccess: (boundControlCount: Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 状态
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // ⭐ 连接方式（与 iOS 登录页一致：用户手选、记住上次选择、覆盖后端下发）："srs" | "p2p"
    var selectedConnectMode by remember { mutableStateOf("srs") }
    
    // 获取设备ID
    val deviceId = remember { DeviceIDManager.getDeviceID(context) }
    
    // 加载保存的账号密码
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
        val savedUsername = prefs.getString("username", "") ?: ""
        val savedPassword = prefs.getString("password", "") ?: ""
        val savedRemember = prefs.getBoolean("remember", false)
        
        if (savedRemember && savedUsername.isNotEmpty()) {
            username = savedUsername
            password = savedPassword
            rememberPassword = true
        }
        // ⭐ 恢复上次选择的连接方式（与 iOS ConnectModeOption.lastSelected 一致，默认 SRS）
        selectedConnectMode = prefs.getString("selected_connect_mode", "srs") ?: "srs"
    }
    
    // 渐变背景
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFB1E8FA),
                        Color(0xFFC6E0FA),
                        Color(0xFFF4F4F9)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(80.dp))
            
            // Logo
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher),
                contentDescription = "Logo",
                modifier = Modifier
                    .size(94.dp)
                    .clip(RoundedCornerShape(9.dp))
            )
            
            Spacer(modifier = Modifier.height(50.dp))
            
            // 登录表单卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    // 账号输入
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 账号图标
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .border(0.6.dp, Color(0xFFB3B3B3), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = Color(0xFF1A1A1A)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(6.dp))
                        
                        TextField(
                            value = username,
                            onValueChange = { username = it },
                            placeholder = { Text("请输入账号", color = Color(0xFFA3A3A3)) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            singleLine = true
                        )
                    }
                    
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 30.dp),
                        color = Color(0xFFF0F0F0)
                    )
                    
                    // 密码输入
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 密码图标
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .border(0.6.dp, Color(0xFFB3B3B3), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = Color(0xFF1A1A1A)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(6.dp))
                        
                        TextField(
                            value = password,
                            onValueChange = { password = it },
                            placeholder = { Text("密码", color = Color(0xFFA3A3A3)) },
                            modifier = Modifier.weight(1f),
                            visualTransformation = if (isPasswordVisible) 
                                VisualTransformation.None 
                            else 
                                PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            singleLine = true
                        )
                        
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) 
                                    Icons.Outlined.Visibility 
                                else 
                                    Icons.Outlined.VisibilityOff,
                                contentDescription = null,
                                tint = Color(0xFFA3A3A3)
                            )
                        }
                    }
                    
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 30.dp),
                        color = Color(0xFFF0F0F0)
                    )
                    
                    // 记住密码
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                            .clickable { rememberPassword = !rememberPassword },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(15.dp)
                                .border(1.dp, Color(0xFF999999), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (rememberPassword) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Color(0xFF65AEF7), CircleShape)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(4.dp))
                        
                        Text(
                            text = "记住密码",
                            fontSize = 16.sp,
                            color = Color(0xFF1A1A1A)
                        )
                    }
                    
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 30.dp),
                        color = Color(0xFFF0F0F0)
                    )
                    
                    // ⭐ 连接方式选择（与 iOS 登录页一致：SRS / P2P 手选，记住上次选择）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "连接方式",
                            fontSize = 16.sp,
                            color = Color(0xFF1A1A1A)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        listOf("srs" to "SRS", "p2p" to "P2P").forEach { (mode, label) ->
                            val selected = selectedConnectMode == mode
                            Box(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) Color(0xFF65AEF7) else Color(0xFFF0F0F0))
                                    .clickable { selectedConnectMode = mode }
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 14.sp,
                                    color = if (selected) Color.White else Color(0xFF666666)
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(30.dp))
            
            // 错误提示
            errorMessage?.let { msg ->
                Text(
                    text = msg,
                    color = Color.Red,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
            
            // 登录按钮
            Button(
                onClick = {
                    if (username.isBlank() || password.isBlank()) {
                        errorMessage = "请输入账号和密码"
                        return@Button
                    }
                    
                    isLoading = true
                    errorMessage = null
                    
                    scope.launch {
                        try {
                            val result = NetworkService.login(
                                LoginRequest(
                                    username = username,
                                    password = password,
                                    deviceId = deviceId,
                                    userType = "device"
                                )
                            )
                            
                            result.fold(
                                onSuccess = { response ->
                                    // 🔥 Step 1: 保存登录凭证（与iOS一致）
                                    val prefs = context.getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
                                    prefs.edit().apply {
                                        putString("username", username)
                                        if (rememberPassword) {
                                            putString("password", password)
                                        } else {
                                            remove("password")
                                        }
                                        putBoolean("remember", rememberPassword)
                                        // ⭐ 记住本次选择的连接方式（下次登录默认）
                                        putString("selected_connect_mode", selectedConnectMode)
                                        apply()
                                    }
                                    
                                    // 🔥 Step 2: 保存所有Token和用户信息（与iOS UserDefaults一致）
                                    val tokenPrefs = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
                                    tokenPrefs.edit().apply {
                                        putString("jwt_token", response.token)
                                        putString("permanent_token", response.permanentToken)
                                        putString("device_id", response.deviceId)
                                        putString("username", response.username)
                                        response.nickname?.let { putString("nickname", it) }
                                        response.userType?.let { putString("user_type", it) }
                                        response.userId?.let { putInt("user_id", it) }
                                        response.streamPushIp?.let { putString("stream_push_ip", it) }
                                        response.streamPushPort?.let { putInt("stream_push_port", it) }
                                        response.membershipType?.let { putString("membership_type", it) }
                                        response.status?.let { putString("status", it) }
                                        putInt("bound_control_count", response.boundControlCount ?: 0)
                                        
                                        // ⭐ 连接方式与 P2P 配置（与iOS一致：以用户在登录页的手动选择为准，覆盖后端下发）
                                        putString("connect_mode", selectedConnectMode)
                                        putBoolean("force_relay", response.forceRelay ?: false)
                                        putInt("max_p2p_viewers", response.maxP2PViewers ?: 4)
                                        response.iceServers?.let {
                                            putString("ice_servers_json", com.google.gson.Gson().toJson(it))
                                        }
                                        println("jfh [Login] ✅ 连接方式(用户选)=$selectedConnectMode, 后端下发=${response.connectMode ?: "nil"}, iceServers=${response.iceServers?.size ?: 0}个, forceRelay=${response.forceRelay ?: false}")
                                        
                                        // 🔥 保存试用/激活信息（与iOS saveTrialInfo完全一致）
                                        response.trialInfo?.let { trial ->
                                            putBoolean("trial_required", trial.trialRequired)
                                            putBoolean("activated", trial.activated ?: false)
                                            trial.activationLevel?.let { putInt("activation_level", it) }
                                            trial.activationLevelName?.let { putString("activation_level_name", it) }
                                            trial.activationExpireAt?.let { putString("activation_expire_at", it) }
                                            // 日试用
                                            putBoolean("is_daily_trial", trial.isDailyTrial ?: false)
                                            trial.activationRemainingSeconds?.let { putInt("activation_remaining_seconds", it) }
                                            // 试用状态
                                            putBoolean("trial_ended", trial.trialEnded ?: false)
                                            trial.currentStage?.let { putInt("current_stage", it) }
                                            trial.totalStages?.let { putInt("total_stages", it) }
                                            trial.stageSeconds?.let { putInt("stage_seconds", it) }
                                            trial.remainingSeconds?.let { putInt("remaining_seconds", it) }
                                            trial.usedSeconds?.let { putInt("used_seconds", it) }
                                            
                                            val isDailyStr = if (trial.isDailyTrial == true) ", 日试用" else ""
                                            println("jfh [Login] ✅ 保存试用信息: trialRequired=${trial.trialRequired}, activated=${trial.activated}, level=${trial.activationLevelName}$isDailyStr")
                                        }
                                        apply()
                                    }
                                    
                                    // 🔥 Step 3: 更新ViewModel
                                    appViewModel.loginSuccess(
                                        token = response.token,
                                        permanent = response.permanentToken,
                                        device = response.deviceId,
                                        user = response.username,
                                        controlCount = response.boundControlCount ?: 0
                                    )
                                    
                                    // 🔥 Step 4: 获取设备初始配置（与iOS一致）
                                    val boundCount = response.boundControlCount ?: 0
                                    scope.launch {
                                        println("jfh [Login] 🔄 获取设备配置...")
                                        val config = com.fz.yqlandroid.manager.ConfigManager.fetchThinConfig(
                                            deviceId = response.deviceId,
                                            jwtToken = response.token
                                        )
                                        if (config != null) {
                                            com.fz.yqlandroid.manager.ConfigManager.cacheConfig(context, config)
                                            println("jfh [Login] ✅ 设备配置获取成功: type=${config.type}, direction=${config.direction}")
                                        } else {
                                            println("jfh [Login] ⚠️ 设备配置获取失败，使用默认值")
                                        }
                                        
                                        // 🔥 Step 5: 连接WebSocket（与iOS一致）
                                        println("jfh [Login] 🔄 连接WebSocket: deviceId=${response.deviceId}")
                                        com.fz.yqlandroid.manager.WebSocketManager.instance.connect(
                                            deviceId = response.deviceId,
                                            token = response.token,
                                            context = context
                                        )
                                        
                                        // 🔥 Step 6: 等WebSocket连接后跳转（最多2秒，超时也跳转）
                                        var waited = 0
                                        while (!com.fz.yqlandroid.manager.WebSocketManager.instance.isConnected && waited < 20) {
                                            kotlinx.coroutines.delay(100)
                                            waited++
                                        }
                                        if (com.fz.yqlandroid.manager.WebSocketManager.instance.isConnected) {
                                            println("jfh [Login] ✅ WebSocket已连接，跳转")
                                        } else {
                                            println("jfh [Login] ⚠️ WebSocket超时，仍然跳转")
                                        }
                                        isLoading = false
                                        onLoginSuccess(boundCount)
                                    }
                                },
                                onFailure = { error ->
                                    isLoading = false
                                    errorMessage = error.message ?: "登录失败"
                                }
                            )
                        } catch (e: Exception) {
                            isLoading = false
                            errorMessage = e.message ?: "网络错误"
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF65AEF7)
                ),
                shape = RoundedCornerShape(25.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "登录",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // 注册入口
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "没有账号？",
                    fontSize = 14.sp,
                    color = Color(0xFF666666)
                )
                TextButton(onClick = onNavigateToRegister) {
                    Text(
                        text = "立即注册",
                        fontSize = 14.sp,
                        color = Color(0xFF65AEF7),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // 设备ID显示（调试用）
            Text(
                text = "设备ID: ${deviceId.take(8)}...",
                fontSize = 12.sp,
                color = Color(0xFF999999),
                modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }
}
