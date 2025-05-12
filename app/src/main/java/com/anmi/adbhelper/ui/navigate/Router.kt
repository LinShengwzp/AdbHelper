package com.anmi.adbhelper.ui.navigate

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.anmi.adbhelper.models.AppSettingConfigViewModel
import com.anmi.adbhelper.models.LocalAppSettingConfig
import com.anmi.adbhelper.models.LocalAppSettingConfigViewModel
import com.anmi.adbhelper.ui.views.AdbScreenView
import com.anmi.adbhelper.ui.views.DrawerItem
import com.anmi.adbhelper.ui.views.ProcessScreenView
import com.anmi.adbhelper.ui.views.TerminalScreenView
import com.draco.ladb.viewmodels.AdbViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/** App Navigation Global Controller **/
val LocalAppNavController = staticCompositionLocalOf<NavHostController> {
    error("NavController not provided")
}

object RouterManager {
    private var navControllerRef: WeakReference<NavHostController>? = null

    fun setNavController(controller: NavHostController) {
        navControllerRef = WeakReference(controller)
    }

    fun get(): NavHostController {
        return navControllerRef?.get() ?: error("NavController not available")
    }

    fun clear() {
        navControllerRef?.clear()
        navControllerRef = null
    }
}


sealed class AppRoute(val route: String, val label: String = "") {
    open fun navigate(popUp: Boolean = false) {
        val nav = RouterManager.get()
        CoroutineScope(Dispatchers.Main).launch {
            if (popUp) {
                nav.navigate(route) {
                    popUpTo(0)
                }
            } else {
                nav.navigate(route)
            }
        }
    }

    object AdbCommandScreen : AppRoute("adb_screen", "常用命令")
    object ProcessScreen : AppRoute("process_screen", "进程管理")
    object TerminalScreen : AppRoute("terminal_screen", "终端模拟器")

    // navigate with params
    data class UserInfo(val username: String) : AppRoute("user_info/${Uri.encode(username)}") {
        companion object {
            const val pattern = "user_info/{username}"
        }
    }
}

@Composable
fun Router() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var topBarTitle by remember { mutableStateOf("") }
    val navController = rememberNavController()
    val vm: AppSettingConfigViewModel = hiltViewModel()
    val drawerState: DrawerState = rememberDrawerState(DrawerValue.Closed)
    val viewModel = remember { AdbViewModel(context) }

    val appConfigFlow = vm.state
    val topBar = @Composable {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                Icon(Icons.Default.Menu, contentDescription = "菜单", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(topBarTitle, color = Color.White, fontSize = 16.sp)
        }
    }

    LaunchedEffect(Unit) {
        RouterManager.setNavController(navController)
    }


    // Provide the user config to the app
    CompositionLocalProvider(
        // Inject the global providers
        LocalAppNavController provides navController,
        LocalAppSettingConfig provides appConfigFlow,
        LocalAppSettingConfigViewModel provides vm
    ) {
        viewModel.startADBServer()
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(240.dp)
                        .background(Color.DarkGray)
                        .padding(16.dp)
                ) {
                    Text(
                        "ADB助手",
                        color = Color.White,
                        fontSize = 20.sp,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    listOf<AppRoute>(
                        AppRoute.TerminalScreen,
                        AppRoute.ProcessScreen,
                        AppRoute.AdbCommandScreen
                    ).forEach {
                        DrawerItem(it.label) {
                            topBarTitle = it.label
                            it.navigate()
                            scope.launch {
                                drawerState.close()
                            }
                        }
                    }

                    DrawerItem("设置") {}
                    DrawerItem("命令历史") {}
                }
            }
        ) {
            val defaultRoute = AppRoute.AdbCommandScreen
            topBarTitle = defaultRoute.label
            NavHost(
                navController = navController,
                startDestination = defaultRoute.route,
                route = "root"
            ) {
                composable(AppRoute.TerminalScreen.route) {
                    TerminalScreenView(topBar, viewModel)
                }
                composable(AppRoute.ProcessScreen.route) {
                    ProcessScreenView(topBar, viewModel)
                }
                composable(AppRoute.AdbCommandScreen.route) {
                    AdbScreenView(topBar, viewModel)
                }
            }
        }
    }
}