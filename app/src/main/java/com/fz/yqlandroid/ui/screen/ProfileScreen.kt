package com.fz.yqlandroid.ui.screen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fz.yqlandroid.manager.DeviceIDManager
import com.fz.yqlandroid.manager.WebSocketManager
import com.fz.yqlandroid.navigation.AppViewModel

/**
 * 个人中心页面
 * 与iOS ProfileView 保持一致
 *
 * 功能：
 * 1. 用户头像 + 昵称 + 等级
 * 2. 注册时间
 * 3. 扫一扫（绑定设备）
 * 4. 修改密码
 * 5. 注销账号
 * 6. 版本号
 * 7. 关于我们
 * 8. 问题反馈
 * 9. 退出登录
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    appViewModel: AppViewModel,
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    // 从SharedPreferences读取用户信息
    val tokenPrefs = context.getSharedPreferences("token_prefs", Context.MODE_PRIVATE)
    val username = tokenPrefs.getString("username", "") ?: ""
    val deviceId = remember { DeviceIDManager.getDeviceID(context) }
    
    // 对话框状态
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }
    
    // 退出登录确认
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("确定要退出登录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    // 清理
                    WebSocketManager.instance.disconnect()
                    tokenPrefs.edit().clear().apply()
                    context.getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
                        .edit().remove("password").apply()
                    appViewModel.logout()
                    onLogout()
                }) {
                    Text("确定", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
    
    // 注销账号确认
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("注销账号") },
            text = {
                Column {
                    Text("注销后账号将无法恢复，所有数据将被删除。请输入管理密码确认注销。")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = deletePassword,
                        onValueChange = { deletePassword = it },
                        label = { Text("管理密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // TODO: 调用注销API
                    showDeleteDialog = false
                    deletePassword = ""
                }) {
                    Text("确认注销", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    deletePassword = ""
                }) {
                    Text("取消")
                }
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "个人中心",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.Close, "关闭")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .background(Color(0xFFF2F2F7))
        ) {
            // ===== 头部：头像 + 昵称 + 等级 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 头像
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE0E0E0)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = Color.Gray
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column {
                    // 昵称
                    Text(
                        text = username.ifEmpty { "--" },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1A1A1A)
                    )
                    
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    // 等级标签
                    Row(
                        modifier = Modifier
                            .background(
                                Color(0xFF808080).copy(alpha = 0.15f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = Color(0xFF808080)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "试用用户",
                            fontSize = 12.sp,
                            color = Color(0xFF1A1A1A)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // ===== 设置项第一组 =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
            ) {
                ProfileRow(
                    icon = Icons.Default.DateRange,
                    title = "注册时间",
                    subtitle = "—",
                    onClick = { }
                )
                
                HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp),
                    color = Color(0xFFF0F0F0)
                )
                
                ProfileRow(
                    icon = Icons.Default.Search,
                    title = "扫一扫",
                    subtitle = "扫描控制端二维码进行绑定",
                    onClick = { /* TODO: 扫码 */ }
                )
                
                HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp),
                    color = Color(0xFFF0F0F0)
                )
                
                ProfileRow(
                    icon = Icons.Default.Lock,
                    title = "修改密码",
                    onClick = { /* TODO: 修改密码 */ }
                )
                
                HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp),
                    color = Color(0xFFF0F0F0)
                )
                
                ProfileRow(
                    icon = Icons.Default.PersonRemove,
                    title = "注销账号",
                    onClick = { showDeleteDialog = true }
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // ===== 设置项第二组 =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
            ) {
                ProfileRow(
                    icon = Icons.Default.Info,
                    title = "版本号",
                    subtitle = "试用版(1.0.0)",
                    onClick = { }
                )
                
                HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp),
                    color = Color(0xFFF0F0F0)
                )
                
                ProfileRow(
                    icon = Icons.Default.Info,
                    title = "关于我们",
                    onClick = { /* TODO: 关于我们 */ }
                )
                
                HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp),
                    color = Color(0xFFF0F0F0)
                )
                
                ProfileRow(
                    icon = Icons.Default.Email,
                    title = "问题反馈",
                    onClick = { /* TODO: 问题反馈 */ }
                )
            }
            
            Spacer(modifier = Modifier.height(30.dp))
            
            // ===== 退出按钮 =====
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = { showLogoutDialog = true },
                    modifier = Modifier
                        .width(160.dp)
                        .height(46.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFCCCCCC)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "退出",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFAFAFA)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 设备ID
            Text(
                text = "设备ID: ${deviceId.take(8)}...",
                fontSize = 11.sp,
                color = Color(0xFF999999),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 40.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/**
 * 设置行组件
 * 与iOS ProfileRowView 保持一致
 */
@Composable
private fun ProfileRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = Color(0xFF1A1A1A)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // 标题 + 副标题
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                color = Color(0xFF1A1A1A)
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = Color(0xFF999999)
                )
            }
        }
        
        // 箭头
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = Color(0xFF999999)
        )
    }
}
