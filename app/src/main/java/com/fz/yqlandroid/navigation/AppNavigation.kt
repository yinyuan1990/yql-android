package com.fz.yqlandroid.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fz.yqlandroid.ui.screen.LoginScreen
import com.fz.yqlandroid.ui.screen.SplashScreen
import com.fz.yqlandroid.ui.screen.StreamingScreen
import com.fz.yqlandroid.ui.screen.QRScannerScreen
import com.fz.yqlandroid.ui.screen.RegisterScreen
import com.fz.yqlandroid.ui.screen.ProfileScreen

/**
 * 导航路由定义
 * 与iOS AppView 保持一致
 */
sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Login : Screen("login")
    object Register : Screen("register")
    object Streaming : Screen("streaming")
    object QRScanner : Screen("qr_scanner")
    object Profile : Screen("profile")
}

/**
 * 应用状态管理
 * 与iOS AppState 保持一致
 */
class AppViewModel : ViewModel() {
    var isLoggedIn by mutableStateOf(false)
        private set
    
    var permanentToken by mutableStateOf("")
        private set
    
    var jwtToken by mutableStateOf("")
        private set
    
    var deviceId by mutableStateOf("")
        private set
    
    var username by mutableStateOf("")
        private set
    
    var boundControlCount by mutableStateOf(0)
        private set
    
    /**
     * 登录成功处理
     */
    fun loginSuccess(
        token: String,
        permanent: String,
        device: String,
        user: String,
        controlCount: Int
    ) {
        jwtToken = token
        permanentToken = permanent
        deviceId = device
        username = user
        boundControlCount = controlCount
        isLoggedIn = true
    }
    
    /**
     * 登出处理
     */
    fun logout() {
        isLoggedIn = false
        jwtToken = ""
        permanentToken = ""
        deviceId = ""
        username = ""
        boundControlCount = 0
    }
    
    /**
     * 更新绑定数量
     */
    fun updateBoundControlCount(count: Int) {
        boundControlCount = count
    }
}

/**
 * 应用导航Host
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    appViewModel: AppViewModel = viewModel()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route
    ) {
        // 启动页
        composable(Screen.Splash.route) {
            SplashScreen(
                onNavigateToLogin = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }
        
        // 登录页
        composable(Screen.Login.route) {
            LoginScreen(
                appViewModel = appViewModel,
                onNavigateToRegister = {
                    navController.navigate(Screen.Register.route)
                },
                onLoginSuccess = { boundCount ->
                    if (boundCount <= 0) {
                        // 没有绑定控制端，跳转到扫码页
                        navController.navigate(Screen.QRScanner.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    } else {
                        // 已绑定，跳转到推流页
                        navController.navigate(Screen.Streaming.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    }
                }
            )
        }
        
        // 注册页
        composable(Screen.Register.route) {
            RegisterScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onRegisterSuccess = {
                    // 🔥 popUpTo Login inclusive=true，确保创建全新Login实例
                    // 这样 LaunchedEffect(Unit) 会重新执行，读取保存的账号密码
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }
        
        // 推流页
        composable(Screen.Streaming.route) {
            StreamingScreen(
                appViewModel = appViewModel,
                onLogout = {
                    appViewModel.logout()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Streaming.route) { inclusive = true }
                    }
                },
                onNavigateToProfile = {
                    navController.navigate(Screen.Profile.route)
                }
            )
        }
        
        // 个人中心页
        composable(Screen.Profile.route) {
            ProfileScreen(
                appViewModel = appViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onLogout = {
                    appViewModel.logout()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Profile.route) { inclusive = true }
                        popUpTo(Screen.Streaming.route) { inclusive = true }
                    }
                }
            )
        }
        
        // 扫码绑定页
        composable(Screen.QRScanner.route) {
            QRScannerScreen(
                appViewModel = appViewModel,
                onNavigateBack = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.QRScanner.route) { inclusive = true }
                    }
                },
                onBindSuccess = {
                    navController.navigate(Screen.Streaming.route) {
                        popUpTo(Screen.QRScanner.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
