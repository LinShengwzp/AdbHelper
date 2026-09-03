# Task 02：收敛 Compose 层 ADB 启动入口

> 这是一个小任务。只修复 Compose 重组/页面切换导致 `startADBServer()` 被重复调用的问题，不处理 `adb devices` 轮询，不重构 ADB 状态机。

## 背景证据

Task 01 的 `AdbDiag` 日志已经确认：

- 同一 App 进程中 `START_ADB_SERVER_REQUEST` 出现多次。
- 第一次启动成功后，后续 `ADB_INIT_SERVER` 都因为 `_started=true` 提前返回。
- 但 `AdbViewModel.startADBServer()` 仍会在每次返回 `true` 后调用 `startShellDeathThread()`，因此产生多个 `ADB_WATCHER_START`。
- 当前主导航的 `Router()` 在 Composable 函数体中直接调用 `viewModel.startADBServer()`。
- `TerminalScreenView()` 也在 Composable 函数体中直接调用 `viewModel.startADBServer()`。

本任务只消除这两个 Compose 调用点造成的重复启动。

## 目标

在一次 App 进程生命周期内：

1. `Router()` 只触发一次 `viewModel.startADBServer()`。
2. 页面重组不会再次启动 ADB。
3. 切换到“终端模拟器”不会再次启动 ADB。
4. 现有 ADB 初始化、death watcher、命令执行逻辑保持不变。

## 修改文件

只允许修改：

- `app/src/main/java/com/anmi/adbhelper/ui/navigate/Router.kt`
- `app/src/main/java/com/anmi/adbhelper/ui/views/ShellView.kt`

不要修改其他文件。

## 实现要求

### 1. Router.kt

当前 `Router()` 中已有：

```kotlin
LaunchedEffect(Unit) {
    RouterManager.setNavController(navController)
}
```

将 ADB 启动也放入这个一次性 `LaunchedEffect(Unit)`：

```kotlin
LaunchedEffect(Unit) {
    RouterManager.setNavController(navController)
    viewModel.startADBServer()
}
```

然后删除 `CompositionLocalProvider` 内部当前直接执行的：

```kotlin
viewModel.startADBServer()
```

不要新增第二个用于 ADB 的 `LaunchedEffect`，直接复用已有的即可。

### 2. ShellView.kt

从 `TerminalScreenView()` 的 Composable 函数体中删除：

```kotlin
viewModel.startADBServer()
```

终端页面只使用 Router 已经创建并启动的同一个 `AdbViewModel`，不要自行负责 ADB 服务启动。

## 严格禁止

- 不修改 `AdbViewModel.startADBServer()`。
- 不修改 `ADB.initServer()`。
- 不修改 `waitForDeathAndReset()`。
- 不修改 `startShellDeathThread()`。
- 不修改 `adb devices` 的 100ms 轮询。
- 不修改 `AdbManager` 实验代码。
- 不修改 Task 01 的诊断日志。
- 不增加依赖。
- 不做 UI 重构。
- 不顺手清理其他旧代码。

## 验证

### 编译验证

依次执行：

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

两项必须通过。

### 代码检查

确认：

1. `Router.kt` 中 `viewModel.startADBServer()` 只存在于一次性 `LaunchedEffect(Unit)` 中。
2. `TerminalScreenView()` 中不再调用 `startADBServer()`。
3. 本任务只修改上述两个源码文件。

### 可选真机验证

如果方便运行真机，过滤：

```text
tag:AdbDiag
```

启动 App 后依次切换：

```text
常用命令 -> 终端模拟器 -> 进程管理 -> 常用命令 -> 终端模拟器
```

在同一个 App PID 下，预期只出现：

```text
START_ADB_SERVER_REQUEST requestId=1
ADB_WATCHER_START watcherId=...
```

不应因页面切换继续出现 requestId=2、3、4……

如果真机验证仍出现重复请求，不要扩大修改范围，保留日志并汇报。

## 提交

验证通过后提交：

```bash
git add app/src/main/java/com/anmi/adbhelper/ui/navigate/Router.kt app/src/main/java/com/anmi/adbhelper/ui/views/ShellView.kt
git commit -m "fix: start adb once from compose root"
```

完成后只汇报：

1. 修改文件。
2. `compileDebugKotlin` 结果。
3. `assembleDebug` 结果。
4. 如执行真机验证，汇报同一 PID 下 `START_ADB_SERVER_REQUEST` 和 `ADB_WATCHER_START` 的数量。
5. Commit SHA。
