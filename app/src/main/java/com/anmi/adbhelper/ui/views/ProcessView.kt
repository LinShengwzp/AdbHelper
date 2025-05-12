package com.anmi.adbhelper.ui.views

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.anmi.adbhelper.R
import com.anmi.adbhelper.commons.log
import com.draco.ladb.viewmodels.AdbViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class AppInfo(
    val appName: String,
    val pkgName: String,
    var pid: Int?,
    val appIcon: Drawable?,
    val systemApp: Boolean,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ProcessScreenView(topBar: @Composable () -> Unit = {}, viewModel: AdbViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packageManager = context.packageManager
    var connectSuccess by remember { mutableStateOf(false) }

    var devices by remember { mutableStateOf(listOf<String>()) }
    var selectedDevice by remember { mutableStateOf<String?>(null) }
    var processAppFilter by remember { mutableStateOf<String>("") }
    var processSysAppFilter by remember { mutableStateOf<Boolean>(true) }
    var processList by remember { mutableStateOf(listOf<String>()) }
    var applicationList by remember { mutableStateOf(listOf<AppInfo?>()) }
    var appProcessList by remember { mutableStateOf(listOf<AppInfo>()) }

    val outputText by viewModel.outputText.observeAsState()
    var expectedCommand by remember { mutableStateOf<String?>(null) }

    fun refreshDevices() {
        scope.launch(Dispatchers.IO) {
            while (!connectSuccess) {
                expectedCommand = "devices"
                viewModel.adb.adb(true, listOf("devices"))
                delay(100)
            }
        }
    }

    fun loadProcesses() {
        if (selectedDevice == null) return
        scope.launch(Dispatchers.IO) {
            expectedCommand = "ps"
            viewModel.adb.adb(
                true,
                listOf(
                    "-s",
                    selectedDevice!!,
                    "shell",
                    "ps",
                    "-A",
                    "-o",
                    "NAME,PID",
                    "|",
                    "grep",
                    "-v",
                    "'^\\['",
                    "|",
                    "grep",
                    "com"
                )
            )
        }
    }

    fun loadApplications() {
        if (selectedDevice == null) return
        scope.launch(Dispatchers.IO) {
            expectedCommand = "pm"
            viewModel.adb.adb(
                true,
                listOf(
                    "-s",
                    selectedDevice!!,
                    "shell",
                    "pm",
                    "list",
                    "packages",
                    "-3"
                )
            )
        }
    }

    fun killAppProcess(appPkg: String) {
        if (selectedDevice == null) return
        scope.launch(Dispatchers.IO) {
            expectedCommand = "kill"
            viewModel.adb.adb(
                true,
                listOf(
                    "-s",
                    selectedDevice!!,
                    "shell",
                    "am",
                    "force-stop",
                    appPkg
                )
            )
            delay(100)
            loadProcesses()
        }
    }

    LaunchedEffect(outputText) {
        outputText?.let { output ->
            if (output.isNotBlank() && expectedCommand != null) {
                when (expectedCommand) {
                    "devices" -> {
                        val lines = output.lines().drop(1)
                            .filter { it.contains("\tdevice") }
                            .map { it.split("\t")[0] }
                        devices = lines
                        log("Devices: $lines")
                        if (selectedDevice == null && lines.isNotEmpty()) {
                            selectedDevice = lines.first()
                            connectSuccess = true
                            loadApplications()
                            loadProcesses()
                        }
                    }

                    "ps" -> {
                        processList = output.lines()
                            .drop(1)
                            .filter { it.isNotBlank() && !it.contains("grep") }
                            .map { it.split(" ") }
                            .filter { it.size >= 2 }
                            .map { it.joinToString(" ") }
                        log("Processes: $processList")
                    }

                    "pm" -> {
                        applicationList = output.lines()
                            .drop(1)
                            .filter { it.isNotBlank() && !it.contains("grep") }
                            .map { getAppInfo(packageManager, it) }
                            .filter { it != null }
                        log("Applications: $applicationList")
                    }

                    "kill" -> {
                        log("Killed app res: $output ")
                        loadProcesses()
                    }
                }
                expectedCommand = null
                viewModel.clearOutputText()
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshDevices()
    }

    LaunchedEffect(processList) {
        val appProcessTempList = mutableListOf<AppInfo>()
        processList.map {
            val processInfo = it.split(" ")
            val pkgName = processInfo.first().replace("package:", "")
            val pid = processInfo.last()

            var appInfo = applicationList.find { it?.pkgName == pkgName }

            if (appInfo == null) {
                appInfo = getAppInfo(packageManager, pkgName)
            }

            if (appInfo != null) {
                log("Process Info ====> PID: $pid, pkg: $appInfo")
                appInfo.pid = pid.toIntOrNull()
                if (appInfo.appName.contains(processAppFilter) || appInfo.pkgName.contains(
                        processAppFilter
                    )
                ) {
                    if (!(appInfo.systemApp && processSysAppFilter)) {
                        appProcessTempList.add(appInfo)
                    }
                }
            }
        }
        appProcessList = appProcessTempList
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    topBar()
                }

                Switch(
                    checked = processSysAppFilter,
                    onCheckedChange = {
                        processSysAppFilter = it
                        loadProcesses()
                    })
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选择设备：", color = Color.White)
                Spacer(Modifier.width(8.dp))
                DropdownMenuWithSelectedDevice(
                    options = devices,
                    selected = selectedDevice ?: "无设备"
                ) {
                    selectedDevice = it
                    connectSuccess = true
                    loadProcesses()
                }
            }

            Spacer(Modifier.height(12.dp))

            Row {
                Button(onClick = { loadProcesses() }) {
                    Text("当前进程", color = Color.White)
                }

                TextField(
                    value = processAppFilter,
                    singleLine = true, // 单行
                    onValueChange = { processAppFilter = it },
                    placeholder = { Text("过滤") },
                    modifier = Modifier
                        .weight(0.4f),
                    keyboardActions = KeyboardActions {
                        loadProcesses()
                    },
                )
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(appProcessList) { appInfo ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (appInfo.appIcon == null) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                            )
                        } else {
                            Image(
                                bitmap = appInfo.appIcon.toBitmap().asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${appInfo.appName}(${appInfo.pid})",
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Text(appInfo.pkgName, color = Color.Gray, fontSize = 12.sp)
                        }
                        Button(onClick = { killAppProcess(appInfo.pkgName) }) {
                            Text("结束")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DropdownMenuWithSelectedDevice(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.wrapContentSize(Alignment.TopStart)) {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected, color = Color.White)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { label ->
                DropdownMenuItem(onClick = {
                    onSelect(label)
                    expanded = false
                }, text = { Text(label) })
            }
        }
    }
}

val filterList = mutableListOf<String>(
    "com.qti.phone",
    "com.vivo.biometrics",
    "vendor.qti.hardware.qseecom@1.0-service",
    "com.vivo.sps:rms"
)

fun getAppInfo(packageManager: PackageManager, pkgName: String): AppInfo? {
    val pkgName = pkgName.replace("package:", "")

    if (filterList.contains(pkgName)) {
        return null
    }

    try {
        val applicationInfo = packageManager.getApplicationInfo(pkgName, 0)
        return AppInfo(
            appName = packageManager.getApplicationLabel(applicationInfo).toString(),
            pkgName = applicationInfo.packageName,
            pid = null,
            appIcon = applicationInfo.loadIcon(packageManager),
            systemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 1
        )
    } catch (e: Exception) {
        log("Error getting app info for $pkgName", level = 3)
        return AppInfo(
            appName = pkgName,
            pkgName = pkgName,
            pid = null,
            appIcon = null,
            systemApp = false
        )
    } catch (e: PackageManager.NameNotFoundException) {
        return null
    }
}