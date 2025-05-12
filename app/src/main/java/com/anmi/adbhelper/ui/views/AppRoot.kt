package com.anmi.adbhelper.ui.views

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.anmi.adbhelper.ui.navigate.Router
import com.anmi.adbhelper.ui.navigate.RouterManager
import com.anmi.adbhelper.ui.theme.AdbHelperTheme

@Composable
fun AppRoot() {
    AdbHelperTheme {
        Router()
    }
    DisposableEffect(Unit) {
        onDispose {
            RouterManager.clear()
        }
    }

}