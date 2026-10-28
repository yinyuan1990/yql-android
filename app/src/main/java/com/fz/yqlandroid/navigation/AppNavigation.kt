package com.fz.yqlandroid.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.navigation.compose.currentBackStackEntryAsState
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
import com.fz.yqlandroid.ui.screen.ChangePasswordScreen
import com.fz.yqlandroid.ui.screen.LocalWebViewScreen
import com.fz.yqlandroid.ui.screen.MessageScreen
import com.fz.yqlandroid.ui.screen.BindingListScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument

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
    object ChangePassword : Screen("change_password")
    object AboutUs : Screen("about_us")
    object Message : Screen("message")
    object BindingList : Screen("binding_list")
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
    // 推流页画在 NavHost 底下：从推流页进个人中心 / 改密码 / 关于 / 留言 / 绑定列表时推流页不销毁
    //   （销毁 = 停推流 + 断 WebSocket，PC 那边就掉线了），只是被盖住；去扫码（要用相机）、退出登录才销毁
    val route = navController.currentBackStackEntryAsState().value?.destination?.route
    val streamingKeptPrev = remember { mutableStateOf(false) }
    val streamingKept = route == Screen.Streaming.route ||
            (route != null && route in ROUTES_OVER_STREAMING && streamingKeptPrev.value)
    SideEffect { streamingKeptPrev.value = streamingKept }

    Box(modifier = Modifier.fillMaxSize()) {
    if (streamingKept) {
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
        
        // 推流页：内容画在 NavHost 底下（见上面 streamingKept），这里只占位
        composable(Screen.Streaming.route) { }
        
        // 个人中心页
        composable(Screen.Profile.route) {
            OverStreaming {
            ProfileScreen(
                appViewModel = appViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToChangePassword = {
                    navController.navigate(Screen.ChangePassword.route)
                },
                onNavigateToAboutUs = {
                    navController.navigate(Screen.AboutUs.route)
                },
                onNavigateToMessage = {
                    navController.navigate(Screen.Message.route)
                },
                onNavigateToScan = {
                    // 🔥 从 Profile 进扫码：绑定成功后回到 Profile（而非跳 Streaming）
                    navController.navigate("${Screen.QRScanner.route}?from=profile")
                },
                onNavigateToBindingList = {
                    navController.navigate(Screen.BindingList.route)
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
        }

        // 修改密码页
        composable(Screen.ChangePassword.route) {
            OverStreaming {
            ChangePasswordScreen(
                appViewModel = appViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onLogout = {
                    appViewModel.logout()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Streaming.route) { inclusive = true }
                    }
                }
            )
            }
        }

        // 关于我们（本地隐私政策 HTML，对标 iOS LocalWebView）
        composable(Screen.AboutUs.route) {
            OverStreaming {
            LocalWebViewScreen(
                title = "关于我们",
                assetFileName = "privacy_policy.html",
                onNavigateBack = { navController.popBackStack() }
            )
            }
        }

        // 问题反馈页
        composable(Screen.Message.route) {
            OverStreaming {
            MessageScreen(
                onNavigateBack = { navController.popBackStack() }
            )
            }
        }
        
        // 扫码绑定页（from=login 为登录流程默认跳 Streaming；from=profile 为个人中心入口，成功后回 Profile）
        composable(
            route = "${Screen.QRScanner.route}?from={from}",
            arguments = listOf(navArgument("from") {
                type = NavType.StringType
                defaultValue = "login"
            })
        ) { backStackEntry ->
            val from = backStackEntry.arguments?.getString("from") ?: "login"
            val fromProfile = from == "profile"
            val bindCtx = androidx.compose.ui.platform.LocalContext.current
            QRScannerScreen(
                appViewModel = appViewModel,
                onNavigateBack = {
                    if (fromProfile) {
                        // 🔥 从 Profile 进入：返回上一页（Profile）
                        navController.popBackStack()
                    } else {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.QRScanner.route) { inclusive = true }
                        }
                    }
                },
                onBindSuccess = {
                    if (fromProfile) {
                        // 🔥 从 Profile 进入：绑定成功后回到 Profile
                        navController.popBackStack()
                    } else {
                        // ⭐ 绑定成功后回登录页重新登录（绑定改变了账号状态，重登拉取最新绑定/权限）
                        android.widget.Toast.makeText(bindCtx, "绑定成功，请重新登录", android.widget.Toast.LENGTH_LONG).show()
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.QRScanner.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        // 已绑定控制端列表页（对标 iOS BindingListView + UnbindView）
        composable(Screen.BindingList.route) {
            OverStreaming {
            BindingListScreen(
                onNavigateBack = { navController.popBackStack() }
            )
            }
        }
    }
    }
}

/** 推流页上面这几页打开时推流页留在底下继续推 */
private val ROUTES_OVER_STREAMING = setOf(
    Screen.Profile.route, Screen.ChangePassword.route, Screen.AboutUs.route,
    Screen.Message.route, Screen.BindingList.route,
)

/** 盖在推流页上的页面：吃掉所有触摸，点空白处不会点到底下推流页的按钮 */
@Composable
private fun OverStreaming(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        content()
    }
}
