# Task 03 — 收敛 adb devices 轮询

> 本任务刻意保持很小，只处理 `AdbScreenView` 与 `ProcessScreenView` 中的 `adb devices` 高频轮询。不要顺手重构 ADB 生命周期、设备状态管理或输出模型。

## 背景

Task 01 的诊断日志已经确认：

- `files/.android/adbkey` 与 `adbkey.pub` 在多次调用和 App 重启后 SHA-256 保持不变，当前主线不存在 adbkey 每次重建的问题。
- Task 02 已将 Compose 根节点的 ADB 启动收敛为一次。
- 目前剩余最明显的问题是两个页面都以约 100 ms 的间隔反复执行完整的 `libadb.so devices` 子进程。

当前代码分别位于：

- `app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt`
- `app/src/main/java/com/anmi/adbhelper/ui/views/ProcessView.kt`

两处 `refreshDevices()` 都存在类似逻辑：

```kotlin
while (!connectSuccess) {
    expectedCommand = "devices"
    viewModel.adb.adb(true, listOf("devices"))
    delay(100)
}
```

这会在未发现设备或等待用户授权时持续创建大量 adb 子进程。

## 目标

将设备发现轮询改成**低频、串行、可随 Compose 页面离开而取消**的轮询。

期望行为：

1. 页面首次进入后立即执行一次 `adb devices`。
2. 上一次 `adb devices` 进程结束后，才允许下一次轮询。
3. 未发现设备时最多约每 1 秒执行一次。
4. 发现至少一个 `device` 后保持现有行为，停止轮询并选择第一个设备。
5. 页面离开 Composition 后不继续主动启动新的轮询进程。
6. 不改变用户点击 `devices` 按钮等手工命令的行为。

## 修改范围

仅允许修改：

- `app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt`
- `app/src/main/java/com/anmi/adbhelper/ui/views/ProcessView.kt`

如仅为协程取消检查需要补充 Kotlin 协程标准库 import，可以修改这两个文件的 import。

## 实现要求

### 1. 不再由 `rememberCoroutineScope()` 启动独立的设备轮询 Job

两处 `refreshDevices()` 改为 `suspend` 函数，由现有的 `LaunchedEffect(Unit)` 直接调用。

推荐结构：

```kotlin
suspend fun refreshDevices() {
    while (!connectSuccess && currentCoroutineContext().isActive) {
        expectedCommand = "devices"

        withContext(Dispatchers.IO) {
            viewModel.adb.adb(true, listOf("devices")).waitFor()
        }

        if (!connectSuccess) {
            delay(1_000)
        }
    }
}
```

允许根据现有 import 和 Kotlin 版本做等价写法，但必须同时满足：

- 串行等待当前 `adb devices` 结束；
- 轮询间隔为 `1_000L` 左右，不得继续使用 100 ms；
- 协程取消后不得继续创建新的 adb 子进程。

注意：`waitFor()` 必须放在 `Dispatchers.IO` 上，不要阻塞 Compose 主线程。

### 2. `LaunchedEffect(Unit)` 直接调用 `refreshDevices()`

`AdbView.kt` 现有：

```kotlin
LaunchedEffect(Unit) {
    refreshDevices()
    val stored = CommandStore.loadCommands(context)
    ...
}
```

保持整体功能不变，可以直接调用新的 suspend `refreshDevices()`。

如果这会导致命令列表必须等设备发现后才加载，则应将“加载 CommandStore”与“设备轮询”拆成两个 `LaunchedEffect(Unit)`，或先加载命令再调用 `refreshDevices()`。

要求：**命令按钮列表不能因为没有设备而长期不加载。**

`ProcessView.kt` 现有：

```kotlin
LaunchedEffect(Unit) {
    refreshDevices()
}
```

直接调用 suspend 版本即可。

### 3. 保持设备解析逻辑不变

不要修改以下行为：

```kotlin
.filter { it.contains("\tdevice") }
```

不要在本任务中重新设计 `expectedCommand`、`outputText`、`selectedDevice` 或跨页面共享设备状态。

### 4. 不修改 `ADB.adb()`

本任务禁止修改：

- `commons/AdbManager.kt`
- `models/AdbViewModel.kt`
- `ADB.adb()`
- `waitForDeathAndReset()`
- adbkey 诊断代码

不要为了轮询创建新的全局 DeviceManager、Flow、Service 或 Repository。

## 明确禁止

- 不实现 `adb track-devices`，后续可以单独评估。
- 不改 mDNS / Wireless Debugging / pairing。
- 不修 `autoShell`。
- 不处理 `AdbManager` 实验路线。
- 不删除 Task 01 的 `AdbDiag` 日志。
- 不修改终端页面。
- 不修改常用命令按钮执行逻辑。
- 不增加依赖。
- 不做 UI 美化。
- 不提交无关格式化。

## 验证

### A. 编译

必须执行：

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

要求：`BUILD SUCCESSFUL`。

随后尝试一次：

```powershell
.\gradlew.bat :app:assembleDebug
```

如果仍然是 Task 02 已确认的本机 `JdkImageTransform/jlink.exe` 环境错误，并且 stash/基线代码同样失败，则记录该环境错误即可，不要求为了本任务修 Gradle/JDK。

如果出现新的 Kotlin/Java 编译错误，则本任务不得提交，必须先修复本任务引入的错误。

### B. 静态检查

确认两处设备自动轮询中：

- 不再存在 `delay(100)`；
- 存在约 `delay(1_000)` 的节流；
- 每次自动轮询都等待对应 adb Process 结束；
- `refreshDevices()` 不再内部 `scope.launch` 后立即返回。

### C. 真机日志验收（由用户后续执行）

使用现有 `AdbDiag` 日志观察。

未授权或无设备状态下，预期：

```text
ADB_ADB ... devices
约 1 秒
ADB_ADB ... devices
约 1 秒
ADB_ADB ... devices
```

而不是 Task 01 中约 100 ms 一次。

授权并出现设备后，自动 `devices` 轮询应停止。

## Commit

验证完成后直接提交本任务代码：

```bash
git add app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt app/src/main/java/com/anmi/adbhelper/ui/views/ProcessView.kt
git commit -m "fix: throttle adb device polling"
```

不要把其他文件加入 commit。

## 完成汇报

只汇报：

1. 修改文件。
2. `refreshDevices()` 最终如何保证串行和取消。
3. 自动轮询间隔。
4. `compileDebugKotlin` 结果。
5. `assembleDebug` 结果；如失败，说明是否与已知 `JdkImageTransform/jlink.exe` 基线错误一致。
6. Commit SHA。
