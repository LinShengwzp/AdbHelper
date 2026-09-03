# Task 04: 隔离设备发现输出

## 目标

只解决一个问题：后台 `adb devices` 轮询不得再占用共享 `outputBufferFile`，也不得再通过 `expectedCommand = "devices"` 与用户手动命令争抢输出归属。

Task 03 已经把轮询降频并改成串行等待。本任务继续在此基础上，把“后台设备发现”改成直接读取本次 `adb devices` Process 的标准输出并在当前协程内解析。

## 修改范围

只允许修改：

- `app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt`
- `app/src/main/java/com/anmi/adbhelper/ui/views/ProcessView.kt`

禁止修改：

- `AdbManager.kt`
- `AdbViewModel.kt`
- `Router.kt`
- `ShellView.kt`
- ADB server 生命周期
- death watcher
- 用户手动命令执行模型
- `outputBufferFile` 的现有实现
- 依赖、Gradle、Manifest

不要顺手重构重复代码。

## 已确认问题

当前两个页面的 `refreshDevices()` 都会：

```kotlin
expectedCommand = "devices"
viewModel.adb.adb(true, listOf("devices")).waitFor()
```

其中 `true` 会把 `adb devices` 输出写入共享输出缓冲区。与此同时，用户可能点击 `apps`、`ps`、`tcpip` 等命令并修改 `expectedCommand`。

因此后台轮询输出与用户命令输出可能发生错配。

## 实现要求

### 1. `refreshDevices()` 使用独立 Process 输出

两个页面中的 `refreshDevices()` 都改为：

- 调用 `viewModel.adb.adb(false, listOf("devices"))`
- 不再设置 `expectedCommand = "devices"`
- 直接读取该 Process 的 `inputStream`
- 等待 Process 结束
- 只有退出码为 `0` 时才解析设备列表
- 解析完成后直接更新本页面的 `devices`、`selectedDevice`、`connectSuccess`

推荐保持当前协程结构，例如：

```kotlin
val output = withContext(Dispatchers.IO) {
    val process = viewModel.adb.adb(false, listOf("devices"))
    val text = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (exitCode == 0) text else ""
}

val lines = output.lines()
    .drop(1)
    .filter { it.contains("\tdevice") }
    .map { it.split("\t")[0] }

devices = lines
if (selectedDevice == null && lines.isNotEmpty()) {
    selectedDevice = lines.first()
    connectSuccess = true
}
```

可根据当前代码做最小调整，但不得增加新的抽象层或 helper 文件。

### 2. 保留手动 `devices` 命令行为

`AdbScreenView` 中默认命令按钮仍包含：

```kotlin
AdbCommand("devices", listOf("devices"))
```

用户手动点击该按钮时，原有：

```kotlin
expectedCommand = cmd.name
viewModel.adb.adb(true, command)
```

以及 `LaunchedEffect(outputText)` 中对 `"devices"` 的处理保持不变。

本任务只隔离“后台自动发现”，不要破坏手动执行 `devices` 后在输出区展示结果的行为。

### 3. Process 页面连接后的后续行为保持不变

`ProcessScreenView` 中首次发现设备后，仍需保持当前逻辑：

```kotlin
selectedDevice = lines.first()
connectSuccess = true
loadApplications()
loadProcesses()
```

不要改变 `loadApplications()`、`loadProcesses()`、`killAppProcess()`。

### 4. 保持 Task 03 的轮询约束

必须继续满足：

- `refreshDevices()` 为 `suspend`
- 使用 `currentCoroutineContext().isActive`
- 每次只允许一个 `adb devices` Process
- 未发现设备时 `delay(1_000)`
- 页面离开后协程可取消
- 不恢复 `delay(100)`

## 验收检查

静态检查：

1. 两个 `refreshDevices()` 中都不存在：

```kotlin
expectedCommand = "devices"
```

2. 两个 `refreshDevices()` 调用都应为：

```kotlin
adb(false, listOf("devices"))
```

3. 用户手动命令路径仍使用原有 `adb(true, ...)`。

4. `ProcessScreenView` 首次找到设备后仍调用 `loadApplications()` 和 `loadProcesses()`。

## 验证命令

依次执行：

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

要求：

- `compileDebugKotlin` 必须 `BUILD SUCCESSFUL`。
- 如果 `assembleDebug` 仍仅因已确认的 `JdkImageTransform/jlink.exe` 基线环境问题失败，记录完整结论后允许继续；不要为此修改项目代码或 Gradle 配置。

然后执行：

```powershell
git diff --check
git status --short
```

确认只有本任务允许的两个源码文件发生修改（计划文档本身已经由上游提交，不要修改它）。

## 提交与推送

验证完成后直接提交并推送当前分支：

```powershell
git add app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt app/src/main/java/com/anmi/adbhelper/ui/views/ProcessView.kt
git commit -m "fix: isolate adb device discovery output"
git push
```

约束：

- 禁止 `--force`
- 禁止修改 remote
- 禁止切换分支
- push 失败时停止并汇报错误，不要自行尝试危险修复

## 完成后只汇报

- 修改文件
- 后台 `adb devices` 输出如何与共享输出解耦
- `compileDebugKotlin` 结果
- `assembleDebug` 结果
- commit 完整 SHA
- `git push` 结果
