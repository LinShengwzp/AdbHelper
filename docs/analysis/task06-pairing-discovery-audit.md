# Task06 审计报告 - 首次配对与后续发现链路

> 日期：2026-09-03
> 分支：main (486d0ed)
> 范围：只读审计，不修改 `app/src/main` / Gradle / Manifest / 依赖

## 1. 当前真实调用链

> 搜索方式：`rg` 全仓正则，逐文件核验定义与调用点。0 命中表示源码无该符号。

### 1.1 `ADB.pair`

| 位置 | 摘要 |
|---|---|
| `app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt:279` | `fun pair(port: String, pairingCode: String): Boolean {` 定义 |
| `AdbManager.kt:280` | `val pairShell = adb(false, listOf("pair", "localhost:$port"))` |
| `AdbManager.kt:286` | `PrintStream(pairShell.outputStream).apply { println(pairingCode)` |
| `AdbManager.kt:292` | `pairShell.waitFor(10, TimeUnit.SECONDS)` |
| `AdbManager.kt:295` | `adb(false, listOf("kill-server"))` |
| `AdbManager.kt:299` | `return pairShell.exitValue() == 0` |

调用点：全仓 `grep "ADB\.pair\|\.pair("` 源码 0 命中。属有定义零调用。

### 1.2 `needsToPair`

| 位置 | 摘要 |
|---|---|
| `app/src/main/java/com/draco/ladb/viewmodels/AdbViewModel.kt:109` | `fun needsToPair(): Boolean {` |
| `AdbViewModel.kt:110` | `return !sharedPreferences.getBoolean(context.getString(R.string.paired_key), false) &&` |
| `AdbViewModel.kt:111` | `(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)` |

调用点：0。

### 1.3 `setPairedBefore`

| 位置 | 摘要 |
|---|---|
| `AdbViewModel.kt:114` | `fun setPairedBefore(value: Boolean) {` |
| `AdbViewModel.kt:115` | `sharedPreferences.edit { putBoolean(context.getString(R.string.paired_key), value)` |

调用点：0。

### 1.4 `paired_key` / `paired`

| 位置 | 摘要 |
|---|---|
| `app/src/main/res/values/strings.xml:8` | `<string name="paired_key">paired</string>` 定义 |
| `AdbViewModel.kt:110` | `getBoolean(context.getString(R.string.paired_key), false)` |
| `AdbViewModel.kt:116` | `putBoolean(context.getString(R.string.paired_key), value)` |

调用点：仅上述两处读写，无外部调用者。

### 1.5 `autoConnect`

| 位置 | 摘要 |
|---|---|
| `app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt:310` | `manager.autoConnect(context, 5000) // 自动连接本机adbd` (在 `ListApps()`) |
| `AdbView.kt:336` | `manager.autoConnect(context, 5000)` (在顶层 `execute()`) |
| `AdbManager.kt:25` | `class AdbManager : AbsAdbConnectionManager()` 继承 |
| `AdbManager.kt:13` | `import io.github.muntashirakon.adb.AbsAdbConnectionManager` 定义来源为 `libadb-android` |

全仓无其他调用。两处均位于死代码中（见 §5）。

### 1.6 `start-server`

| 位置 | 摘要 |
|---|---|
| `AdbManager.kt:201` | `adb(false, listOf("start-server")).waitFor()` 唯一调用点，在 `ADB.initServer()` 内 |

### 1.7 `wait-for-device`

| 位置 | 摘要 |
|---|---|
| `AdbManager.kt:204` | `val waitProcess = adb(false, listOf("wait-for-device")).waitFor(1, TimeUnit.MINUTES)` |
| `AdbManager.kt:210` | `diagLog("... reason=wait-for-device timeout")` |

仅 `initServer()` 内。

### 1.8 `connect` / `adb connect`

源码不存在字面量 `adb connect`。宽松 `connect` 命中均为变量名 `connectSuccess` 或日志 `Waiting for device to connect...`：
`AdbManager.kt:202` / `AdbView.kt:73,109,125,174,217,310,336` / `ProcessView.kt:74,133,149,191,296`。无 `adb connect <ip:port>` 逻辑。

### 1.9 `adb_wifi_enabled`

| 位置 | 摘要 |
|---|---|
| `AdbManager.kt:166` | `Settings.Global.putInt(..., "adb_wifi_enabled", 1)` |
| `AdbManager.kt:254` | `Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1` (`isWirelessDebuggingEnabled()`) |
| `AdbManager.kt:163` | `Build.VERSION_CODES.R && !isWirelessDebuggingEnabled()` 分支 |
| `AdbManager.kt:183,188` | `while (!isWirelessDebuggingEnabled()) { Thread.sleep(1_000) }` 轮询 |

仅 `initServer()` 读写。

### 1.10 `_adb-tls-pairing._tcp`

源码 0 命中。仅 `docs/plan/task06-audit-pairing-discovery.md:52,88` 提及。

### 1.11 `_adb-tls-connect._tcp`

源码 0 命中。仅 `docs/plan/task06-audit-pairing-discovery.md:53,76,88` 提及。

### 1.12 `NsdManager`

源码 0 命中（无 import、无使用）。仅文档提及。

### 1.13 `Mdns` / `mDNS`

源码 0 命中。`rg "(?i)mdns"` 返回 0 文件；所有命中均为 `docs/plan/*.md`。

### 1.14 其他广义

- `(?i)pair`：除上述外仅 `KeyPair` 密钥生成 (`AdbManager.kt:17,37,44,46,54` / `SelfSignedCertificateGenerator.kt:22,33,41`) 与 `tryingToPair` 标志 (`AdbManager.kt:84,147,149,153,209,247,249,264,269`)。
- `(?i)nsd` / `discovery` / `pairing`：源码 0 命中有效逻辑。

## 2. 首次配对能力

**当前主 UI 是否存在首次配对入口？否。**

- `Router.kt:77-103` 定义 `AppRoute` 仅 3 个：`AdbCommandScreen` / `ProcessScreen` / `TerminalScreen`，`Router()` 中 `NavHost` 仅注册这三页 (`Router.kt:185-193`)。无配对页、无对话框、无导航入口。
- `ShellView.kt:49 TerminalScreenView`、`ProcessView.kt:70 ProcessScreenView`、`AdbView.kt:67 AdbScreenView` 三个主线页面均无输入 `port/pairingCode` 的 UI。
- `AdbControlScreen()` (`AdbView.kt:353`) 含“连接”按钮但仅调用 `viewModel.startADBServer { viewModel.adb.adb(true, listOf("devices")) }`，未涉及 `pair`，且该 Composable 未被 `Router` 挂载（死代码）。

**是否真的调用 `ADB.pair()`？否。** 全仓无调用者，`ADB.pair()` 属于未接入的遗留能力。证据见 §1.1。

**`needsToPair()` / `setPairedBefore()` 是否有实际调用者？否。** 定义存在于 `AdbViewModel.kt:109,114`，`rg` 全仓 0 调用；`isPairing: MutableLiveData` (`AdbViewModel.kt:27`) 同样声明后无读写。

**如何获得 pairing port / 6 位 pairing code？根本没有实现。** 无 mDNS、手输、固定值、扫码、Intent 等任何获取路径；无 `EditText` / `Dialog` / `Settings` 读取；`ADB.pair(port, pairingCode)` 参数来源缺失。当前代码无法完成 Android 11+ 首次配对流程。

## 3. 后续连接能力

**App 重开后如何找到无线调试的 connect port？当前未实现发现，仅依赖内置 adb server 的自发现与 `wait-for-device` 轮询。**

- `ADB.initServer():143-251` 是唯一重连路径：`Router.kt:131 LaunchedEffect { viewModel.startADBServer() }` -> `AdbViewModel.kt:42 startADBServer()` -> `ADB.initServer()`。
- `initServer()` 逻辑：检查 `adb_wifi_enabled`/`ADB_ENABLED` -> `adb start-server` (`AdbManager.kt:201`) -> `adb wait-for-device` 阻塞 60s (`AdbManager.kt:204`) -> 若成功则 `adb shell` / `sh -l` 取得常驻 `shellProcess` (`AdbManager.kt:215`)。无 `NsdManager.discoverServices("_adb-tls-connect._tcp")`、无 `_adb-tls-pairing._tcp`、无 `adb connect <ip:port>`。
- 三个主页面 `AdbScreenView` / `ProcessScreenView` 的 `refreshDevices()` (`AdbView.kt:108, ProcessView.kt:132`) 仅轮询 `adb(false, listOf("devices"))` 并解析 `\tdevice`，属于被动发现已连接的 transport，非主动 connect。
- `adb_wifi_enabled` 仅用于“是否已开启无线调试”的轮询等待 (`AdbManager.kt:183-190`)，不用于端口发现。

**是否实现 `_adb-tls-connect._tcp` 发现？否。** 证据：源码 0 命中，见 §1.11。

**是否只是依赖内置 adb server 自己发现 transport？是。** `start-server` + `wait-for-device` 依赖 `libadb.so` 内置的 `adbd` 自连接（同一设备的 loopback / 本地 transport）。这在 Shizuku/LADB 模式下常见，但前提是系统 `adbd` 已处于可用状态且授权已保留。

**`adb wait-for-device` 在自连接场景下依赖什么前提？**

1. `WRITE_SECURE_SETTINGS` 已授予或用户已手动开启“无线调试”（否则 `initServer()` 会在 `while (!isWirelessDebuggingEnabled()) Thread.sleep(1_000)` 死等，`AdbManager.kt:188`）。
2. `adbd` 已在本地监听（`start-server` 后）。
3. `~/.android/adbkey` 密钥对已存在且已被设备端授权（`AdbManager.kt:91-107` 日志显示密钥位于 `filesDir/.android/adbkey`）。若授权被撤销或密钥轮换，`wait-for-device` 会 60s 超时返回 `false` (`AdbManager.kt:209`)。

**若设备端保留授权但无线调试动态端口变化，现有逻辑是否仍能连接？理论上能，但仅限“自连接”场景；跨设备/重开后端口变化则不能，证据如下：**

- 能：自连接（`adb shell` 在本机）不依赖 `adb connect <ip:port>`，而是 `libadb.so` 通过本地 socket 直连本机 `adbd`。端口由 `adbd` 内部管理，`adbkey` 授权保留时 `wait-for-device` 可再次成功，无需 mDNS。这是当前主线唯一能工作的重连路径（`initServer()` + `waitForDeathAndReset()` 循环 `AdbManager.kt:262-273`）。
- 不能：若走“无线调试对端”语义（需 `adb pair` + `adb connect <ip:connectPort>`），当前无任何 `connect` 实现，端口变化后无 mDNS 重发现，`devices` 列表将为空，`refreshDevices()` 永远 `connectSuccess=false`。代码证据：全仓无 `connect` 命令、无 `NsdManager`、无端口缓存，无重连重试除 `waitForDeathAndReset()` 的 `kill-server -> initServer()` 循环。

## 4. 依赖能力审计

**`libadb-android` 当前承担什么角色？**

- 版本 `3.0.0` (`gradle/libs.versions.toml:20` / `app/build.gradle.kts:81`)。提供两类能力：
  1. Native `libadb.so` 二进制（`AdbManager.kt:75 adbPath = nativeLibraryDir/libadb.so`），被 `ADB.adb()` / `ADB.shell()` 通过 `ProcessBuilder` 直接执行 `start-server/wait-for-device/shell/pair/devices` 等命令。为主线唯一 ADB 实现。
  2. Java `AbsAdbConnectionManager` 抽象类（`AdbManager.kt:13 import io.github.muntashirakon.adb.AbsAdbConnectionManager`），被实验性 `AdbManager` 继承，仅提供 `autoConnect()` / `openStream()` 高层 API。

**内置 `libadb.so` 与 `AdbManager : AbsAdbConnectionManager` 是否是两套完全独立的 ADB identity / transport 实现？是。**

- `ADB`：identity 为 `filesDir/.android/adbkey(.pub)` 文件对 (`AdbManager.kt:91,92`)，由 `libadb.so` 自行生成/管理；transport 为子进程 `adb shell` 的 `Process` + `outputBufferFile` 重定向 (`AdbManager.kt:118,343`)；每次 `adb(false, listOf(...))` 新起 `ProcessBuilder` 进程。
- `AdbManager`：identity 为内存 `KeyPair(RSA 2048)` + `SelfSignedCertificateGenerator` 生成的 `X509Certificate` (`AdbManager.kt:37,44,50`)，每次进程启动重新生成，`getPrivateKey()/getCertificate()/getDeviceName()="AnMiAdbHelperApplication"` (`AdbManager.kt:54-56`)；transport 为 `AbsAdbConnectionManager` 的纯 Java ADB 协议栈 (`autoConnect/openStream`)，与 `libadb.so` 子进程无关。两者密钥对、证书、连接通道完全隔离，互不共享 `adbkey`。

**当前依赖中是否已包含可直接使用的 mDNS / NSD 发现能力？否。**

- `gradle/libs.versions.toml` 与 `app/build.gradle.kts:49-93` 均无 `jmDNS`、 `NsdManager` 封装库、 `BouncyCastle` mDNS 等。现有三方仅 `libadb-android / conscrypt-android / sun-security-android / piracychecker / XXPermissions / glidebitmappool`。

**若没有，Android SDK 自带 `NsdManager` 是否足够？是。**

- `minSdk=26` (`app/build.gradle.kts:17`)，`NsdManager` 自 API 16 存在，API 26+ 已稳定支持 `discoverServices("_adb-tls-pairing._tcp"/"_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD)`。系统自带 `android.net.nsd.NsdManager` 无需新增依赖即可实现 `_adb-tls-pairing._tcp`（首次配对）与 `_adb-tls-connect._tcp`（后续 connect 端口发现）。`conscrypt` / `sun-security` 仅服务于 `AdbManager` 的证书生成，与 mDNS 无关。

## 5. 活代码 / 死代码 / 半成品 分类

| 模块 | 分类 | 证据 |
|---|---|---|
| `ADB` (`AdbManager.kt:60 class ADB`) | `LIVE` | 主线唯一 ADB 实现。`Router.kt:131 viewModel.startADBServer()` -> `AdbViewModel.kt:47 adb.initServer()` -> `ADB.initServer():143`；`ShellView.kt:68 adb(true,...)` / `78 sendToShellProcess`；`AdbView.kt:110,242 devices/shell`；`ProcessView.kt:91,117` 同。`initServer/start-server/wait-for-device/shell/outputBufferFile/sendToShellProcess/waitForDeathAndReset` 全链被调用。 |
| `AdbManager` (`AdbManager.kt:25 : AbsAdbConnectionManager`) | `EXPERIMENTAL` | 可调用但未进入主线。仅 `AdbView.kt:309 ListApps` 与 `334 execute()` 调用 `autoConnect/openStream`，而这两处本身为死代码；无任何主线页面使用。密钥每次启动重生成，未持久化。 |
| `SelfSignedCertificateGenerator` (`SelfSignedCertificateGenerator.kt:21`) | `EXPERIMENTAL` | 仅被 `AdbManager.kt:50` 调用，服务于实验性 `AdbManager` 分支。主线 `ADB` 不使用。 |
| `AdbViewModel` (`AdbViewModel.kt:23`) | `LIVE` (部分半成品) | `startADBServer/startOutputThread/startShellDeathThread/readOutputFile` 为 LIVE（`Router.kt:111 remember { AdbViewModel }` 单例贯穿三页）。`needsToPair/setPairedBefore/isPairing` 为 `DEAD_OR_ORPHANED`：有定义零调用。 |
| `ListApps()` (`AdbView.kt:296`) | `DEAD_OR_ORPHANED` | 顶层 `@Composable fun ListApps()` 全仓无调用者（`rg ListApps` 仅定义）。 |
| `execute()` (`AdbView.kt:334 fun execute`) | `DEAD_OR_ORPHANED` | 顶层函数 `execute(context, command)` 0 调用点。 |
| `AdbControlScreen()` (`AdbView.kt:353`) | `DEAD_OR_ORPHANED` | 定义后未注册到 `Router.NavHost`，`rg AdbControlScreen` 仅定义 1 处。内部自建 `AdbViewModel` 非路由单例。 |
| Router 三主页面 | `LIVE` | `Router.kt:185 TerminalScreenView` / `188 ProcessScreenView` / `191 AdbScreenView` 均已注册为 `NavHost` destination，`startDestination = AdbCommandScreen`。`TerminalScreenView` (`ShellView.kt:49`)、`ProcessScreenView` (`ProcessView.kt:70`)、`AdbScreenView` (`AdbView.kt:67`) 均为 LIVE。 |

> 补充：`AdbView.kt:524 ShellScreenView()` 同为未挂载的遗留 Composable，`DEAD_OR_ORPHANED`。

## 6. 结论与后续小任务建议

**当前结论：**

- 首次配对未接通：`ADB.pair()` 存在但零 UI、零调用、端口与 pairing code 无来源；`needsToPair` 亦未接入。
- connect mDNS 未实现：无 `NsdManager`、无 `_adb-tls-*`、无 `adb connect`。
- 两套身份体系独立：`ADB(libadb.so + adbkey 文件)` 与 `AdbManager(AbsAdbConnectionManager + 内存 RSA 证书)` 完全隔离。
- 现有重连仅靠 `libadb.so` 自发现 + `wait-for-device` + `waitForDeathAndReset` 循环，端口变化的跨端场景不可用。

**推荐后续小任务（每项单点，按优先级排序）：**

1. **Task07 - 首次配对入口（手输版）** - 在 `TerminalScreenView` 或独立 `PairingScreen` 新增“配对端口 + 6位配对码”输入框与“配对”按钮，直连 `ADB.pair(port, code)` 并持久化 `paired_key` + 展示 `exitCode/exitValue`。不含 mDNS，仅打通现有 `pair` 能力可测性。验收：输入系统“无线调试->使用配对码配对”信息可完成配对。

2. **Task08 - `_adb-tls-pairing._tcp` 的 `NsdManager` 发现** - 用系统 `NsdManager.discoverServices("_adb-tls-pairing._tcp")` 自动填充配对服务 `host/port` 列表，点击填充到 Task07 的输入框。无需新依赖。验收：开启无线调试配对后列表出现对应服务。

3. **Task09 - `_adb-tls-connect._tcp` 的 `NsdManager` 发现 + `adb connect`** - 同理发现 connect 服务，解析 `ip:port` 后执行 `adb connect <ip:port>` 并纳入 `refreshDevices` 前置。验收：App 重开后无需手输即可重连已授权设备，`adb devices` 出现 `ip:port device`。

4. **Task10 - 已授权后的稳定重连与状态机** - 统一 `initServer / waitForDeathAndReset / NsdManager 监听 / adb connect` 的状态机与退避策略，处理授权保留但端口变化、无线调试开关切换、`adbkey` 轮换等场景；补充 `NsdManager` 生命周期与权限/开关监听。验收：开关无线调试、重启 App、端口变化后自动重连可预期。

> 顺序原因：先让现有 `pair` 可用（最小闭环），再补两种 mDNS 发现，最后收敛重连状态机。顺序与任务计划一致，证据表明当前最缺的是“可触达的配对入口”，mDNS 仅在此之后有价值。
