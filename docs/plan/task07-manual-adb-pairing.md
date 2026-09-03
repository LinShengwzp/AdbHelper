# Task07 手动输入端口和配对码接通首次 ADB 配对

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` or等价的小步执行方式完成本任务。任务范围必须严格受控，不要顺手重构配对、mDNS、ADB 生命周期或 UI 架构。

**Goal:** 在“常用命令”页 `AdbScreenView` 增加一个手动首次配对入口，让用户输入 Android 11+ 无线调试页面显示的配对端口和 6 位配对码，并调用现有 `viewModel.adb.pair(port, code)` 完成配对。

**Architecture:** 本任务只接通现有能力，不实现 mDNS、自动端口发现或 `adb connect`。UI 状态全部放在 `AdbScreenView` 内，配对调用放到 `Dispatchers.IO`，成功后调用现有 `viewModel.setPairedBefore(true)`。手动命令、后台 `refreshDevices()`、`ADB.pair()` 实现本身均保持不变。

**Tech Stack:** Kotlin、Jetpack Compose、现有 `ADB` / `AdbViewModel`。

**Spec / Context:** `docs/analysis/task06-pairing-discovery-audit.md`

## Global Constraints

- 只允许修改：`app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt`
- 不修改 `AdbManager.kt`、`AdbViewModel.kt`、Gradle、Manifest、资源文件和依赖。
- 不实现 `NsdManager`、`_adb-tls-pairing._tcp`、`_adb-tls-connect._tcp`、`adb connect`。
- 不修改现有 `ADB.pair(port, pairingCode)` 的内部实现。
- 不修改 Task03/Task04 已完成的后台 `adb devices` 发现逻辑。
- 不修改现有手动命令按钮的 `adb(true, ...) / outputText / expectedCommand` 流程。
- 不引入新的页面、ViewModel、Repository 或公共抽象。
- 保持现有项目风格，代码注释如有新增使用中文。

---

### Task 1: 在常用命令页增加手动配对入口

**Files:**
- Modify: `app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt`

**Existing interfaces:**

```kotlin
viewModel.adb.pair(port: String, pairingCode: String): Boolean
viewModel.setPairedBefore(value: Boolean)
```

当前 `AdbScreenView` 已存在 `context`、`scope = rememberCoroutineScope()`、`AlertDialog`、`OutlinedTextField`、`Button` 和 `Dispatchers.IO`，优先复用现有依赖，不新增库。

- [ ] **Step 1: 增加最小 UI 状态**

在 `AdbScreenView` 内、现有 `showAddCommandDialog/newCommand...` 状态附近增加以下局部状态，名称可以保持如下以便审查：

```kotlin
var showPairDialog by remember { mutableStateOf(false) }
var pairingPort by remember { mutableStateOf("") }
var pairingCode by remember { mutableStateOf("") }
var pairingInProgress by remember { mutableStateOf(false) }
var pairingMessage by remember { mutableStateOf<String?>(null) }
```

不要把这些状态移入 `AdbViewModel`。

- [ ] **Step 2: 增加“配对设备”按钮**

在常用命令页设备选择区域之后、命令按钮 `FlowRow` 之前增加一个明显的按钮：

```kotlin
Button(
    onClick = {
        pairingMessage = null
        showPairDialog = true
    }
) {
    Text("配对设备")
}
```

可按现有布局增加一个 `Spacer`，但不要重排整页 UI。

- [ ] **Step 3: 增加配对输入对话框**

当 `showPairDialog == true` 时渲染一个 `AlertDialog`。

对话框要求：

```text
标题：配对无线调试
输入1：配对端口
输入2：6位配对码
确认：开始配对
取消：取消
```

两个输入框使用现有 `OutlinedTextField`。输入值只保留用户原始字符串，不做自动端口发现。

对话框正文补一行简短说明，例如：

```text
请在“开发者选项 → 无线调试 → 使用配对码配对设备”中查看端口和配对码。
```

如果 `pairingMessage != null`，在对话框内显示该文本。

当 `pairingInProgress == true` 时：

- 禁用“开始配对”按钮；
- 不允许重复启动第二个配对协程；
- 按钮文本可显示“配对中…”。

不需要增加进度条依赖。

- [ ] **Step 4: 做最小输入校验**

点击确认时先在主线程校验：

```kotlin
val port = pairingPort.trim()
val code = pairingCode.trim()
val portNumber = port.toIntOrNull()
val validPort = portNumber != null && portNumber in 1..65535
val validCode = code.length == 6 && code.all { it.isDigit() }
```

校验失败时不要调用 `ADB.pair()`，只设置明确提示：

```text
端口必须是 1-65535 的数字
```

或：

```text
配对码必须是 6 位数字
```

不要使用正则之外的复杂校验，不要读取系统无线调试设置中的端口。

- [ ] **Step 5: 在 IO 协程调用现有 ADB.pair**

校验通过后：

```kotlin
pairingInProgress = true
pairingMessage = null

scope.launch {
    val success = withContext(Dispatchers.IO) {
        viewModel.adb.pair(port, code)
    }

    pairingInProgress = false
    if (success) {
        viewModel.setPairedBefore(true)
        pairingMessage = "配对成功。如未自动建立 ADB 连接，请重新打开应用。"
    } else {
        pairingMessage = "配对失败，请确认端口和配对码仍然有效后重试。"
    }
}
```

要求：

- `ADB.pair()` 必须在 `Dispatchers.IO` 执行。
- 只有 `success == true` 时才调用 `setPairedBefore(true)`。
- 失败时不写 `paired=true`。
- 不在成功后调用 `viewModel.startADBServer()`，避免重新制造 watcher/lifecycle 问题。
- 不在成功后调用 `adb connect`，Task07 不负责连接发现。
- 不主动关闭成功对话框，先让用户能看到结果；用户点“取消/关闭”时再关闭即可。

- [ ] **Step 6: 检查修改范围**

运行：

```bash
git status --short
git diff -- app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt
```

必须确认只有 `AdbView.kt` 被业务代码修改。不要提交 IDE 配置、构建产物或其他文件。

- [ ] **Step 7: 编译验证**

Windows 环境运行：

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

要求：`BUILD SUCCESSFUL`。

然后运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

若成功，记录成功。

若仍然失败且错误与当前已知基线完全一致，为 `JdkImageTransform/jlink.exe`，允许继续提交，但必须明确记录“assembleDebug 为已知环境基线错误”。如果出现任何新的 Kotlin/Compose/资源错误，必须停止，不得提交，不得猜测式修改其他文件。

- [ ] **Step 8: 静态自检**

确认以下条件全部成立：

```text
[ ] 常用命令页出现“配对设备”入口
[ ] 配对端口只允许 1..65535
[ ] 配对码只允许 6 位数字
[ ] 配对调用为 viewModel.adb.pair(port, code)
[ ] pair 调用运行在 Dispatchers.IO
[ ] success=true 才 setPairedBefore(true)
[ ] 配对进行中不能重复点击启动第二次
[ ] 未新增 NsdManager / adb connect / mDNS
[ ] 未修改 ADB.pair() 实现
[ ] 未调用 startADBServer() 作为配对成功后的动作
```

- [ ] **Step 9: Commit 并 Push**

仅暂存本任务允许修改的文件：

```bash
git add app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt
git commit -m "feat: add manual adb pairing entry"
git push
```

禁止：

```text
git push --force
git push -f
修改 remote
切换分支
rebase/merge 其他无关提交
提交 docs/plan 本身以外的意外文件（计划文档已在上游）
```

Push 失败时只汇报失败原因，不要自行改变 remote、认证配置或分支历史。

## 最终汇报格式

执行结束后只需汇报：

```text
1. 修改文件
2. UI/行为实现摘要
3. compileDebugKotlin 结果
4. assembleDebug 结果；若为 JdkImageTransform/jlink 基线错误需注明
5. 完整 commit SHA
6. git push 结果
```

不要在本任务继续实现 Task08 或其他功能。
