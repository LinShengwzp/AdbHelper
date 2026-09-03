# Task 06 - 审计首次配对与后续发现链路

## 目标

只读审计 AdbHelper 当前与 Android 11+ 无线调试配对、mDNS 发现、后续自动重连相关的代码和依赖能力，为后续小步实现做依据。

本任务不修改任何运行逻辑，不实现新功能。

## 背景

当前主线已经完成以下修复：

- ADB server 只从 Compose 根节点启动一次。
- 后台 `adb devices` 已降频并串行等待。
- 后台设备发现已与共享 `outputBufferFile / expectedCommand` 解耦。
- `auto_shell` 缺省值已经改为 `true`，新安装默认尝试进入真正的 ADB shell。

当前已知代码中：

- `ADB.initServer()` 会在 `autoShell=true` 时执行 `adb start-server`、`adb wait-for-device`，然后启动 `adb shell`。
- `ADB.pair(port, pairingCode)` 已存在，并调用内置 `libadb.so pair localhost:<port>`。
- `AdbViewModel` 中存在 `needsToPair()` / `setPairedBefore()`。
- 当前主导航中尚未确认是否存在真正可用的首次配对 UI 和 mDNS 发现链。

## 严格约束

1. 不修改 `app/src/main` 下任何源码。
2. 不修改 Gradle、Manifest、依赖版本或资源文件。
3. 不删除旧代码。
4. 不实现配对 UI。
5. 不实现 mDNS。
6. 不修改 `ADB.initServer()`、`ADB.pair()`、`AdbViewModel`。
7. 不增加依赖。
8. 不执行会改变 Git 历史的操作。
9. 本任务唯一允许新增的业务产物是审计报告：`docs/analysis/task06-pairing-discovery-audit.md`。

## 审计内容

### 1. 当前真实调用链

搜索并列出以下符号的所有定义与调用点：

- `ADB.pair`
- `needsToPair`
- `setPairedBefore`
- `paired_key`
- `autoConnect`
- `start-server`
- `wait-for-device`
- `connect`
- `adb_wifi_enabled`
- `_adb-tls-pairing._tcp`
- `_adb-tls-connect._tcp`
- `NsdManager`
- `Mdns`
- `mDNS`

必须按“文件路径 + 行号 + 关键代码摘要”记录。

### 2. 首次配对能力

回答：

- 当前主 UI 是否存在首次配对入口？
- 如果存在，入口在哪里，最终是否真的调用 `ADB.pair()`？
- 如果不存在，`ADB.pair()` 是否属于未接入的遗留能力？
- `needsToPair()` / `setPairedBefore()` 当前是否有实际调用者？
- 当前代码如何获得 pairing port？是手输、固定值、mDNS 还是根本没有实现？
- 当前代码如何获得 6 位 pairing code？

### 3. 后续连接能力

回答：

- App 重开后，当前代码如何找到无线调试的 connect port？
- 是否实现 `_adb-tls-connect._tcp` 的发现？
- 是否只是依赖内置 adb server 自己发现 transport？
- `adb wait-for-device` 在当前自连接场景下依赖什么前提？
- 如果设备端保留授权但无线调试动态端口变化，现有逻辑理论上是否仍能连接？给出代码证据，不要只凭经验判断。

### 4. 依赖能力审计

检查项目实际依赖和源码可见 API，重点确认：

- `libadb-android` 当前在项目里承担什么角色。
- 内置 `libadb.so` 与 Kotlin `AdbManager : AbsAdbConnectionManager` 是否是两套完全独立的 ADB identity / transport 实现。
- 当前依赖中是否已经包含可直接使用的 mDNS / NSD 发现能力。
- 如果没有，Android SDK 自带 `NsdManager` 是否足够实现 `_adb-tls-pairing._tcp` 和 `_adb-tls-connect._tcp` 发现，不需要新依赖。

这里只做能力判断，不写实现代码。

### 5. 输出“活代码 / 死代码 / 半成品”分类

至少分类这些模块：

- `ADB`
- `AdbManager`
- `SelfSignedCertificateGenerator`
- `AdbViewModel`
- `ListApps()`
- `execute()`
- `AdbControlScreen()`
- 当前 Router 中三个主页面

分类只能使用：

- `LIVE`：当前主线明确调用
- `EXPERIMENTAL`：可调用但未进入主线
- `DEAD_OR_ORPHANED`：无调用或明显遗留
- `UNKNOWN`：证据不足

每项都要附证据。

## 报告结论要求

在 `docs/analysis/task06-pairing-discovery-audit.md` 最后给出一个非常小的后续任务建议列表，但不要实现。最多 4 个任务，每个任务只能解决一个问题。

优先按下面方向判断是否成立：

1. 首次配对入口
2. pairing mDNS 发现
3. connect mDNS 发现
4. 已授权后的稳定重连

如果代码证据说明顺序应该不同，可以调整，但必须解释原因。

## 验证

本任务不应修改 Kotlin/Gradle/Manifest，因此无需把 `assembleDebug` 作为通过门槛。

执行：

```bash
git status --short
git diff -- app/src/main app/build.gradle.kts
```

要求：

- `app/src/main` 无修改。
- `app/build.gradle.kts` 无修改。
- 除任务计划本身外，只新增 `docs/analysis/task06-pairing-discovery-audit.md`。

## 提交与推送

完成审计后：

```bash
git status --short
git add docs/analysis/task06-pairing-discovery-audit.md
git commit -m "docs: audit adb pairing and discovery flow"
git push
```

禁止：

- `git push --force`
- 修改 remote
- 切换分支
- rebase
- amend 既有提交
- 提交任务范围外文件

如果 `git push` 失败，停止并汇报错误，不要自行修改 Git 配置。

## 最终汇报格式

只汇报：

1. 新增报告路径
2. 首次配对当前是否真正接通
3. connect mDNS 是否已经实现
4. `ADB` 与 `AdbManager` 是否为两套独立身份体系
5. 推荐的下一小任务
6. commit 完整 SHA
7. push 结果
