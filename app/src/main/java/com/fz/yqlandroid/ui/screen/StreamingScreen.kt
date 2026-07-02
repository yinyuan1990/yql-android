package com.fz.yqlandroid.ui.screen

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fz.yqlandroid.manager.WebRTCManager
import com.fz.yqlandroid.manager.WebSocketManager
import com.fz.yqlandroid.navigation.AppViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import org.webrtc.SurfaceViewRenderer

/**
 * 推流页面
 * 与iOS ContentView 保持一致
 * 
 * UI面板（曝光/焦距/亮度/清晰度）仅做数据同步显示，不主动操作
 * 所有参数由后端STOMP下发，iOS端只负责显示
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun StreamingScreen(
    appViewModel: AppViewModel,
    onLogout: () -> Unit,
    onNavigateToProfile: () -> Unit = {}
) {
    val context = LocalContext.current
    
    // 权限（Android 13+ 需要通知权限，否则前台服务保活通知不显示）
    val permissionsState = rememberMultiplePermissionsState(
        permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    )
    
    // WebRTC管理器
    val webRTCManager = remember { WebRTCManager(context) }

    // 🔊 后台保活（对标 iOS BackgroundAudioManager）：无声音频 + WakeLock，防止息屏后推流被冻结
    val keepAliveManager = remember { com.fz.yqlandroid.manager.KeepAliveManager(context) }

    // 🖥️ 前台常亮（对标 iOS isIdleTimerDisabled）：进入推流页时禁止自动息屏，离开时恢复
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keepAliveManager.stop()
        }
    }

    // 🔄 回前台自动恢复采集：前台服务是主保障，这里是双保险——
    //    部分 OEM 电池优化仍可能在后台断相机，ON_RESUME 时若推流中但采集已死则自动重启相机会话
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                webRTCManager.recoverCaptureIfNeeded("onResume")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    
    // UI状态
    var showControls by remember { mutableStateOf(true) }
    var isStreaming by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf("未连接") }
    var currentFps by remember { mutableIntStateOf(0) }
    var currentKbps by remember { mutableIntStateOf(0) }
    var currentCapFps by remember { mutableIntStateOf(0) }  // ⭐ 相机实际采集帧率（左上角显示）
    
    // 右侧面板 - 显示同步数据（后端下发 + WebRTC实际值）
    var zoomValue by remember { mutableFloatStateOf(1.0f) }
    
    // 底部面板 - 仅显示同步数据（后端下发，不操作）
    var exposureValue by remember { mutableFloatStateOf(240f) }   // 曝光 60~600
    var focusValue by remember { mutableFloatStateOf(0.5f) }      // 焦距 0~1
    var fluencyValue by remember { mutableFloatStateOf(100f) }    // 流畅 0~100
    var brightnessValue by remember { mutableFloatStateOf(0f) }   // 亮度 -2~8
    var selectedProfile by remember { mutableStateOf("高清") }     // 清晰度档位
    
    // WebSocket配置同步回调
    DisposableEffect(webRTCManager) {
        webRTCManager.onConnectionStateChanged = { status ->
            connectionStatus = status
        }
        webRTCManager.onStatsUpdate = { kbps, fps, rtt ->
            currentKbps = kbps
            currentFps = fps
        }
        // ⭐ 采集帧率每秒回调（不依赖观看端连接，P2P 等待期也有值）
        webRTCManager.onCapFpsUpdate = { capFps ->
            currentCapFps = capFps
        }
        
        // 🔥 监听后端配置下发 → 同步UI数据 + 实际执行控制
        WebSocketManager.instance.onConfigUpdate = { config ->
            val ptype = config["ptype"] as? String ?: ""
            
            // 1️⃣ 同步UI数据（显示用）
            when (ptype) {
                "zoom" -> (config["zoom"] as? Number)?.let { zoomValue = it.toFloat() }
                "cjfps" -> (config["cjfps"] as? Number)?.let { exposureValue = it.toFloat() }
                "focus" -> (config["focus"] as? Number)?.let { focusValue = it.toFloat() }
                "brightness" -> (config["brightness"] as? Number)?.let { brightnessValue = it.toFloat() }
                "type" -> {
                    val type = config["type"] as? String ?: ""
                    selectedProfile = when (type.lowercase()) {
                        "p4k", "4k" -> "超高清"
                        "ultra" -> "超高帧"
                        "high" -> "超清"
                        "standard" -> "高清"
                        else -> selectedProfile
                    }
                }
                "bitrate" -> (config["bitrate"] as? Number)?.let { fluencyValue = it.toFloat() }
                "fps" -> (config["fps"] as? Number)?.let { /* FPS不需要UI同步 */ }
                "direction" -> { /* 摄像头切换不需要UI同步 */ }
            }
            
            // 2️⃣ 实际执行控制（调用WebRTC）
            webRTCManager.applyRemoteConfig(config)
        }
        
        // 🔥 监听特殊消息（RESET_PUBLISH/睡眠/唤醒/试用断开）
        WebSocketManager.instance.onSpecialMessage = { type, messageDict ->
            webRTCManager.handleSpecialMessage(type, messageDict)
            when (type) {
                "shuimian" -> {
                    isStreaming = false
                    keepAliveManager.stop()   // 🔊 睡眠：停止保活
                }
                "gongzuo" -> {
                    isStreaming = true
                    keepAliveManager.start()  // 🔊 唤醒工作：恢复保活
                }
                "TryDisconnect" -> {
                    val needDisconnect = messageDict["needDisconnect"] as? Boolean ?: false
                    if (needDisconnect) {
                        // 强制退出推流
                        webRTCManager.stopPublish()
                        keepAliveManager.stop()  // 🔊 断开推流：停止保活
                        isStreaming = false
                    }
                }
            }
        }
        
        // 🔥 监听 PC端 set_fps 指令（与iOS applyRemoteFps一致）
        WebSocketManager.instance.onSetFpsCommand = { fps, urgency, bitrate, reason ->
            Log.d("StreamingScreen", "🎯 [set_fps] fps=$fps, urgency=$urgency")
            webRTCManager.setTargetFps(fps)
            // 发送确认
            WebSocketManager.instance.sendSetFpsAck(fps)
        }
        
        onDispose {
            webRTCManager.destroy()
            WebSocketManager.instance.disconnect()
        }
    }
    
    // 🔥 初始化WebRTC（必须在预览之前完成）
    LaunchedEffect(Unit) {
        webRTCManager.setContext(context)
        webRTCManager.initialize()
        
        // 🔥 加载设备初始配置（与iOS一致）
        val config = com.fz.yqlandroid.manager.ConfigManager.currentConfig
            ?: com.fz.yqlandroid.manager.ConfigManager.loadCachedConfig(context)
            ?: com.fz.yqlandroid.manager.ConfigManager.getDefaultConfig()
        
        // 同步初始UI数据
        zoomValue = config.zoom
        config.cjfps?.let { exposureValue = it.toFloat() }
        config.focus?.let { focusValue = it }
        config.bitrate?.let { fluencyValue = it.toFloat() }
        config.brightness?.let { brightnessValue = it }
        selectedProfile = when (config.type.lowercase()) {
            "p4k", "4k" -> "超高清"
            "ultra" -> "超高帧"
            "high" -> "超清"
            else -> "高清"
        }
        
        // 🔥 应用全部初始配置到WebRTC（对标 iOS applyThinRemoteConfigInit）：
        //    档位/方向/变焦/快门(cjfps)/对焦/亮度/码率/FPS 一次性挂上。
        //    此前只应用了 type+direction，导致 cjfps 等“滤镜/图像参数”启动时没生效。
        //    延迟到预览就绪后应用，确保 controlCapturer 已创建（采集器对下发状态另有缓存+重放兜底）。
        kotlinx.coroutines.delay(800)
        webRTCManager.applyInitialConfig(config)

        Log.d("StreamingScreen", "📋 初始配置已加载并全量应用: type=${config.type}, direction=${config.direction}, zoom=${config.zoom}, fps=${config.fps}, cjfps=${config.cjfps}, focus=${config.focus}, brightness=${config.brightness}, bitrate=${config.bitrate}")
    }
    
    // 自动推流
    LaunchedEffect(Unit) {
        
        // 连接WebSocket
        val tokenPrefs = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
        val jwtToken = tokenPrefs.getString("jwt_token", "") ?: ""
        val deviceId = tokenPrefs.getString("device_id", "") ?: ""
        val streamPushIp = tokenPrefs.getString("stream_push_ip", "") ?: ""
        val permanentToken = tokenPrefs.getString("permanent_token", "") ?: ""
        
        if (jwtToken.isNotEmpty() && deviceId.isNotEmpty()) {
            WebSocketManager.instance.connect(deviceId, jwtToken, context)
        }
        
        // 自动推流（与iOS一致：相机就绪后自动开始推流）
        println("jfh [Streaming] 推流参数: streamPushIp='$streamPushIp', permanentToken='${permanentToken.take(10)}...', jwtToken='${jwtToken.take(10)}...'")
        
        if (streamPushIp.isNotEmpty() && permanentToken.isNotEmpty()) {
            // 等权限 + 预览就绪后自动推
            println("jfh [Streaming] ⏳ 等待2秒后自动推流...")
            kotlinx.coroutines.delay(2000)
            if (!isStreaming) {
                println("jfh [Streaming] 🚀 开始自动推流: IP=$streamPushIp, key=$permanentToken")
                webRTCManager.startPublish(streamPushIp, "tenantA", permanentToken)  // 🔥 app与iOS一致
                keepAliveManager.start()  // 🔊 推流即启动后台保活，防止息屏后被冻结
                isStreaming = true
            } else {
                println("jfh [Streaming] ⏭️ 已在推流中，跳过")
            }
        } else {
            println("jfh [Streaming] ❌ 推流参数缺失! streamPushIp='$streamPushIp', permanentToken='$permanentToken'")
        }
    }
    
    // 权限检查
    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }
    
    // ========== 主界面 ==========
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
            }
    ) {
        // 相机预览
        if (permissionsState.allPermissionsGranted) {
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).also { renderer ->
                        try {
                            webRTCManager.setupRenderer(renderer)
                            webRTCManager.startPreview()
                        } catch (e: Exception) {
                            Log.e("StreamingScreen", "预览启动失败: ${e.message}")
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // 无权限提示
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Warning, null, tint = Color.White, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("需要相机和麦克风权限", color = Color.White, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                        Text("授权")
                    }
                }
            }
        }
        
        // ===== 顶部导航栏（与iOS一致） =====
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            Column {
                // 状态栏占位
                Spacer(modifier = Modifier.statusBarsPadding())
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(Color(0xFFFAFAFA))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左 - 关闭按钮
                    IconButton(
                        onClick = {
                            if (isStreaming) webRTCManager.stopPublish()
                            keepAliveManager.stop()  // 🔊 退出推流页：停止保活
                            WebSocketManager.instance.disconnect()
                            onLogout()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = Color(0xFF1A1A1A),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // 中 - 标题
                    Text(
                        text = "监控端",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1A1A1A)
                    )
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // 右 - 我的
                    IconButton(
                        onClick = onNavigateToProfile,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "我的",
                            tint = Color(0xFF1A1A1A),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        
        // ===== 右侧操作面板（Zoom + 摄像头切换） =====
        AnimatedVisibility(
            visible = showControls,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Column(
                modifier = Modifier
                    .padding(end = 16.dp)
                    .padding(bottom = 100.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Zoom 缩放控制器（仅显示，数据从后端同步）
                Column(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.24f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // + 放大
                    IconButton(onClick = { }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    
                    // 分隔点
                    repeat(3) {
                        Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(Color.White))
                    }
                    
                    // Zoom值
                    Text(
                        text = String.format("%.1fX", zoomValue),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    
                    // 分隔点
                    repeat(3) {
                        Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(Color.White))
                    }
                    
                    // - 缩小
                    IconButton(onClick = { }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Remove, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                
                // 摄像头切换按钮
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .clickable { webRTCManager.switchCamera() },
                    contentAlignment = Alignment.Center
                ) {
                    // 外圈
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .then(Modifier.padding(2.dp))
                    )
                    // 内圈
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1A74CA)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "切换摄像头",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
        
        // ===== 底部设置面板（仅显示同步数据，不操作） =====
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(16.dp)
            ) {
                // 灰色卡片内容
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF4F4F8), RoundedCornerShape(12.dp))
                        .padding(vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 曝光
                    SliderRow(
                        label = "曝光",
                        value = exposureValue,
                        range = 60f..600f,
                        displayText = String.format("%.0f", exposureValue)
                    )
                    
                    Divider(color = Color.White, thickness = 1.dp)
                    
                    // 焦距
                    SliderRow(
                        label = "焦距",
                        value = focusValue,
                        range = 0f..1f,
                        displayText = String.format("%.2f", focusValue)
                    )
                    
                    Divider(color = Color.White, thickness = 1.dp)
                    
                    // 流畅
                    SliderRow(
                        label = "流畅",
                        value = fluencyValue,
                        range = 0f..100f,
                        displayText = "清晰"
                    )
                    
                    Divider(color = Color.White, thickness = 1.dp)
                    
                    // 亮度
                    SliderRow(
                        label = "亮度",
                        value = brightnessValue,
                        range = -2f..8f,
                        displayText = String.format("%.2f", brightnessValue)
                    )
                    
                    Divider(color = Color.White, thickness = 1.dp)
                    
                    // 清晰度档位选择（仅显示，数据从后端同步）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        listOf("高清", "超清", "超高清", "超高帧").forEach { name ->
                            QualityRadioItem(
                                name = name,
                                isSelected = selectedProfile == name,
                                onClick = { } // 仅显示，不操作
                            )
                        }
                    }
                }
            }
        }
        
        // ===== 推流状态指示（左上角，controls隐藏时也显示） =====
        if (!showControls) {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isStreaming) Color(0xFF4CAF50) else Color(0xFFFF5722))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isStreaming) "采集${currentCapFps} · 推${currentFps}fps · ${currentKbps}kbps"
                           else connectionStatus,
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ===== 滑块行组件（仅显示，与iOS CustomSlider一致） =====
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    displayText: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 标签
        Text(
            text = label,
            fontSize = 14.sp,
            color = Color(0xFF1A1A1A),
            modifier = Modifier.width(42.dp)
        )
        
        Spacer(modifier = Modifier.width(20.dp))
        
        // 滑块（只读显示）
        Box(modifier = Modifier.weight(1f).height(16.dp)) {
            // 背景轨道
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .align(Alignment.Center)
                    .background(Color(0xFFDBDBE0), RoundedCornerShape(24.dp))
            )
            
            // 进度填充
            val progress = if (range.endInclusive > range.start) {
                ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            } else 0f
            
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(4.dp)
                    .align(Alignment.CenterStart)
                    .background(Color(0xFF008BFF), RoundedCornerShape(24.dp))
            )
            
            // 黄色圆形拖块
            Box(
                modifier = Modifier
                    .offset(
                        x = (progress * 200).dp.coerceAtMost(200.dp) // 近似位置
                    )
                    .size(16.dp)
                    .align(Alignment.CenterStart)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFFFFD65B), Color(0xFFFBAC00))
                        )
                    )
            )
        }
        
        Spacer(modifier = Modifier.width(10.dp))
        
        // 值显示
        Text(
            text = displayText,
            fontSize = 14.sp,
            color = Color(0xFF1A1A1A),
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.End
        )
    }
}

// ===== 清晰度单选项（与iOS QualityRadioButton一致） =====
@Composable
private fun QualityRadioItem(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 圆圈
        Box(
            modifier = Modifier
                .size(15.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
                .then(
                    Modifier.padding(0.dp) // placeholder for border
                ),
            contentAlignment = Alignment.Center
        ) {
            // 外圈
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
            )
            // 选中内圈
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1197D6))
                )
            }
        }
        
        Text(
            text = name,
            fontSize = 10.sp,
            color = Color(0xFF1A1A1A),
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
