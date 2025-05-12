package com.anmi.adbhelper.ui.views

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anmi.adbhelper.commons.AdbManager
import com.anmi.adbhelper.commons.log
import com.anmi.adbhelper.models.AdbCommand
import com.anmi.adbhelper.models.CommandStore
import com.anmi.adbhelper.ui.navigate.AppRoute
import com.draco.ladb.viewmodels.AdbViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
fun AdbScreenView(topBar: @Composable () -> Unit = {}, viewModel: AdbViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val outputText by viewModel.outputText.observeAsState("")
    var outputLog by remember { mutableStateOf("") }
    var connectSuccess by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(listOf<String>()) }
    val commandList = remember { mutableStateListOf<AdbCommand>() }
    var selectedDevice by remember { mutableStateOf<String?>(null) }

    var expectedCommand by remember { mutableStateOf<String?>(null) }

    val defaultCommands = remember {
        mutableStateListOf(
            AdbCommand("devices", listOf("devices")),
            AdbCommand("tcpip", listOf("tcpip", "5555")),
            AdbCommand(
                "screencap",
                listOf(
                    "shell",
                    "screencap",
                    "-p",
                    "sdcard/Pictures/Screenshots/Screenshot_${System.currentTimeMillis()}_${
                        Random.nextInt(
                            1000,
                            9999
                        )
                    }.png"
                )
            ),
            AdbCommand("apps", listOf("shell", "pm", "list", "packages")),
            AdbCommand("ps", listOf("shell", "ps")),
            AdbCommand("logcat", listOf("logcat"))
        )
    }

    var showAddCommandDialog by remember { mutableStateOf(false) }
    var newCommandName by remember { mutableStateOf("") }
    var newCommandValue by remember { mutableStateOf("") }

    fun refreshDevices() {
        scope.launch(Dispatchers.IO) {
            while (!connectSuccess) {
                expectedCommand = "devices"
                viewModel.adb.adb(true, listOf("devices"))
                delay(100)
            }
        }
    }

    fun addNewCommand(name: String, commands: List<String>) {
        val newCommand = AdbCommand(name, commands)
        commandList.add(newCommand)

        newCommandName = ""
        newCommandValue = ""
        showAddCommandDialog = false

        scope.launch {
            CommandStore.saveCommands(context, commandList)
        }
    }

    LaunchedEffect(Unit) {
        refreshDevices()
        val stored = CommandStore.loadCommands(context)
        if (stored.isEmpty()) {
            commandList.addAll(defaultCommands)
            CommandStore.saveCommands(context, defaultCommands)
        } else {
            commandList.addAll(stored)
        }
    }

    LaunchedEffect(outputText) {
        log("outputText: $outputText")
        outputLog += outputText
        outputText.let { output ->
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
                        }
                    }
                }
            }
        }
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
                IconButton(onClick = {
                    viewModel.clearOutputText()
                    outputLog = ""
                }) {
                    Icon(Icons.Default.Clear, tint = Color.White, contentDescription = "清除")
                }
            }
        }
    ) { paddings ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddings)
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
                }
            }

            Spacer(Modifier.height(12.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                commandList.forEach { cmd ->
                    Button(onClick = {
                        scope.launch(Dispatchers.IO) {
                            expectedCommand = cmd.name
                            var command = cmd.command.filter { it.isNotBlank() && it != "adb" }
                                .toMutableList()

                            if (selectedDevice != null) {
                                command.add(0, "-s")
                                command.add(1, selectedDevice!!)
                            }

                            log("执行命令：${cmd.name}: $command")
                            outputLog += ("-> Exec ${command.joinToString(" ")}\r\n")
                            viewModel.adb.adb(true, command)
                        }
                    }) {
                        Text(cmd.name)
                    }
                }
                IconButton(onClick = { showAddCommandDialog = true }) {
                    Icon(Icons.Default.Add, tint = Color.White, contentDescription = "添加")
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth()) {
                Text(outputLog, color = Color.White, fontSize = 10.sp)
            }
        }
    }

    if (showAddCommandDialog) {
        AlertDialog(
            onDismissRequest = { showAddCommandDialog = false },
            title = { Text("添加自定义命令") },
            text = {
                Column {
                    OutlinedTextField(
                        newCommandName,
                        onValueChange = { newCommandName = it },
                        label = { Text("名称") })
                    OutlinedTextField(
                        newCommandValue,
                        onValueChange = { newCommandValue = it },
                        label = { Text("命令内容") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newCommandName.isNotBlank() && newCommandValue.isNotBlank()) {
                        addNewCommand(newCommandName, newCommandValue.split(" "))
                    }
                }) {
                    Text("确认")
                }
            },
            dismissButton = {
                Button(onClick = { showAddCommandDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun ListApps() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf("尚未连接") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(onClick = {
            scope.launch(Dispatchers.IO) {
                try {
                    val manager = AdbManager.get()
                    manager.autoConnect(context, 5000) // 自动连接本机adbd
                    val stream = manager.openStream("shell:pm list packages")

                    output = try {
                        stream.openInputStream().bufferedReader().use { it.readText() }
                    } catch (e: Exception) {
                        Log.e("ADB", "读取失败：${e.message}", e)
                        "读取失败：${e.message}"
                    }
                } catch (e: Exception) {
                    output = "失败：${e.message}"
                    Log.e("ADB", "连接失败", e)
                }
            }
        }) {
            Text("连接并列出应用包")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = output, modifier = Modifier.verticalScroll(rememberScrollState()))
    }
}

fun execute(context: Context, command: String): String {
    val manager = AdbManager.get()
    manager.autoConnect(context, 5000)
//    val stream = manager.openStream("shell:pm list packages")
    val stream = manager.openStream(command)
    log("执行命令：$command")

    val output = try {
        stream.openInputStream().bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        log("ADB", "读取失败：${e.message}", level = 3)
        "读取失败：${e.message}"
    }
    log("执行结果：$output")
    return output
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdbControlScreen() {
    val context = LocalContext.current.applicationContext
    val viewModel = remember { AdbViewModel(context) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    val outputText by viewModel.outputText.observeAsState("")
    val deviceList = remember { mutableStateListOf("127.0.0.1") }
    var selectedDevice by remember { mutableStateOf("127.0.0.1") }
    var customIp by remember { mutableStateOf(deviceList[0]) }

    data class AdbCommand(val name: String, val command: String)

    val defaultCommands = remember {
        mutableStateListOf(
            AdbCommand("shell", "shell"),
            AdbCommand("devices", "devices"),
            AdbCommand("tcpip", "tcpip 5555"),
            AdbCommand("screencap", "shell screencap -p /sdcard/screen.png"),
            AdbCommand("apps", "shell pm list packages"),
            AdbCommand("ps", "shell ps"),
            // AdbCommand("logcat", "shell logcat")
        )
    }

    var showAddCommandDialog by remember { mutableStateOf(false) }
    var newCommandName by remember { mutableStateOf("") }
    var newCommandValue by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DropdownMenuWithSelected(deviceList, selectedDevice) { selectedDevice = it }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = customIp,
                onValueChange = { customIp = it },
                label = { Text("输入IP") },
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
//                if (customIp.isNotBlank() && !deviceList.contains(customIp)) {
//                    deviceList += customIp
//                    selectedDevice = customIp
//
//                }

                viewModel.startADBServer {
                    if (it) {
                        log("连接成功")
                        val res = viewModel.adb.adb(true, listOf("devices"))
                        log("连接成功")
                        log("执行命令成功：$res")
                        log("执行命令成功：$res")
                    } else {
                        log("连接失败")
                    }
                }
            }) { Text("连接") }
        }

        Spacer(Modifier.height(16.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            defaultCommands.forEach { cmd ->
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        if (cmd.name == "shell") {
                            AppRoute.TerminalScreen.navigate()
                            return@launch
                        }
                        val fullCommand =
                            if (selectedDevice != "127.0.0.1") "-s $selectedDevice ${cmd.command}" else cmd.command
                        // adb 外部命令
                        viewModel.adb.adb(true, fullCommand.split(" "))
                        // shell 内部命令
//                        viewModel.adb.sendToShellProcess(fullCommand)
                    }
                }) {
                    Text(cmd.name)
                }
            }
            IconButton(onClick = { showAddCommandDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        }
        Spacer(Modifier.height(16.dp))

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            Text(outputText)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { viewModel.clearOutputText() }) {
                Icon(Icons.Default.Clear, contentDescription = "清除")
            }
        }
    }

    if (showAddCommandDialog) {
        AlertDialog(
            onDismissRequest = { showAddCommandDialog = false },
            title = { Text("添加自定义命令") },
            text = {
                Column {
                    OutlinedTextField(
                        newCommandName,
                        onValueChange = { newCommandName = it },
                        label = { Text("名称") })
                    OutlinedTextField(
                        newCommandValue,
                        onValueChange = { newCommandValue = it },
                        label = { Text("命令内容") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newCommandName.isNotBlank() && newCommandValue.isNotBlank()) {
                        defaultCommands.add(AdbCommand(newCommandName, newCommandValue))
                        newCommandName = ""
                        newCommandValue = ""
                        showAddCommandDialog = false
                    }
                }) {
                    Text("确认")
                }
            },
            dismissButton = {
                Button(onClick = { showAddCommandDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun DropdownMenuWithSelected(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.wrapContentSize(Alignment.TopStart)) {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { label ->
                DropdownMenuItem(
                    onClick = {
                        onSelect(label)
                        expanded = false
                    },
                    text = { Text(label) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) }
                )
            }
        }
    }
}

@Composable
fun ShellScreenView() {
    val context = LocalContext.current.applicationContext
    val viewModel = remember { AdbViewModel(context) }
    val scope = rememberCoroutineScope()
    var inputCommand by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val outputText by viewModel.outputText.observeAsState("")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(4.dp)
        ) {
            Text(
                text = outputText,
                color = Color.White,
                fontSize = 14.sp
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = inputCommand,
                onValueChange = { inputCommand = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(8.dp)
                    .background(Color.DarkGray)
                    .padding(8.dp),
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp)
            )
            Button(onClick = {
                if (inputCommand.isNotBlank()) {
                    scope.launch(Dispatchers.IO) {
                        viewModel.adb.sendToShellProcess(inputCommand)
                        inputCommand = ""
                    }
                }
            }) {
                Text("执行")
            }
        }
    }
}

