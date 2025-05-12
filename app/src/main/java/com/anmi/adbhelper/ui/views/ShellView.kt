package com.anmi.adbhelper.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anmi.adbhelper.commons.log
import com.draco.ladb.viewmodels.AdbViewModel

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun TerminalScreenView(topBar: @Composable () -> Unit = {}, viewModel: AdbViewModel) {
    val terminalOutput = remember { mutableStateListOf<String>() }
    var currentInput by remember { mutableStateOf(TextFieldValue("")) }
    var outputFontSize by remember { mutableStateOf(10) }
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val outputText by viewModel.outputText.observeAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    val commandHistory = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }

    viewModel.startADBServer()

    fun mockCommandOutput(input: String): String {
        viewModel.adb.debug("$ > $input")
        return when (input.trim()) {
            "help" -> "支持的命令：help, echo, clear"
            else -> {
                if (input.trim().startsWith("echo ")) {
                    return input.trim().substringAfter("echo ")
                } else if (input.trim().startsWith("adb ")) {
                    viewModel.adb.adb(true, input.trim().replace("adb ", "").split(" "))
                } else if (input.trim().startsWith("size ")) {
                    val size = input.trim().substringAfter("size ")
                    if (size.toIntOrNull() != null) {
                        outputFontSize = size.toInt()
                    } else {
                        return "请输入数字"
                    }
                } else {
                    viewModel.adb.sendToShellProcess(input.trim())
                }
                return ""
            }
        }
    }

    fun updateCurrentInput(input: String) {
        currentInput = TextFieldValue(
            text = input,
            selection = TextRange((input).length)
        )
    }

    val bottomBar = @Composable {
        Column(
            modifier = Modifier
                .imePadding()
                .navigationBarsPadding()
                .background(Color.Red)
                .padding(8.dp)
        ) {

            Column {
                val buttonRows = listOf(
                    listOf("~", "Shift", "Ctrl", "Alt", "Tab", "-", "_"),
                    listOf("\\", "/", "↑", "↓", "←", "→")
                )
                buttonRows.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { key ->
                            Text(
                                text = key,
                                color = Color.LightGray,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .clickable {
                                        val cursor = currentInput.selection.start
                                        when (key) {
                                            "↑" -> {
                                                if (commandHistory.isNotEmpty() && historyIndex > 0) {
                                                    historyIndex--
                                                    updateCurrentInput(commandHistory[historyIndex])
                                                }
                                            }

                                            "↓" -> {
                                                if (commandHistory.isNotEmpty() && historyIndex < commandHistory.lastIndex) {
                                                    historyIndex++
                                                    updateCurrentInput(commandHistory[historyIndex])
                                                } else {
                                                    updateCurrentInput("")
                                                }
                                            }

                                            "←" -> {
                                                val newPos =
                                                    (currentInput.selection.start - 1).coerceAtLeast(
                                                        0
                                                    )
                                                currentInput = currentInput.copy(
                                                    selection = TextRange(newPos)
                                                )
                                            }

                                            "→" -> {
                                                val newPos =
                                                    (currentInput.selection.start + 1).coerceAtMost(
                                                        currentInput.text.length
                                                    )
                                                currentInput = currentInput.copy(
                                                    selection = TextRange(newPos)
                                                )
                                            }

                                            else -> {
                                                val currentInput = currentInput.text
                                                updateCurrentInput(currentInput + if (key == "Tab") "\t" else key)
                                            }
                                        }
                                    }
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$ >",
                    color = Color.Green,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
                TextField(
                    value = currentInput,
                    onValueChange = { currentInput = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    textStyle = LocalTextStyle.current.copy(
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = Color.White
                    ),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val currentInputText = currentInput.text
                            if (currentInputText.isNotBlank()) {
                                viewModel.adb.debug("$ $currentInputText")
                                if (currentInputText.trim() == "clear" || currentInputText.trim() == "cls") {
                                    viewModel.clearOutputText()
                                    terminalOutput.clear()
                                } else {
                                    viewModel.clearOutputText()
                                    viewModel.adb.debug(mockCommandOutput(currentInputText))
                                    log("执行命令：$currentInputText, RES: $outputText")
                                }
                                commandHistory.add(currentInputText)
                                historyIndex = commandHistory.size
                                updateCurrentInput("")
                                keyboardController?.show()
                            }
                        }
                    ),
                    singleLine = true
                )
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = topBar,
        bottomBar = bottomBar
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            for (line in terminalOutput) {
                Text(
                    text = line,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = outputFontSize.sp
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(outputText) {
        outputText?.let {
            if (it.isNotBlank()) {
                terminalOutput.add(it)
            }
        }
    }

    LaunchedEffect(terminalOutput.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
}

@Composable
fun DrawerItem(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onClick() }
    )
}
