# Task 09.1 Remove Unsupported Automatic WRITE_SECURE_SETTINGS Grant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除启动 ADB shell 后自动执行 `pm grant ... WRITE_SECURE_SETTINGS` 的已确认无效逻辑，同时保留 Manifest 权限声明与已有权限检测兼容路径。

**Architecture:** 当前目标设备已经实测证明 `uid=2000(shell)` 执行 `pm grant com.anmi.adbhelper android.permission.WRITE_SECURE_SETTINGS` 会被 PackageManager 拒绝，错误为调用者缺少 `android.permission.GRANT_RUNTIME_PERMISSIONS`。本任务只移除这条确定失败的自动授权命令；若 App 未来通过其他合法方式已经拥有 `WRITE_SECURE_SETTINGS`，`secureSettingsGranted` 分支仍按原逻辑允许程序控制无线/USB 调试开关。未获得该权限时继续要求用户手动开启无线调试。

**Tech Stack:** Kotlin, Android SDK 35, native adb wrapper

**Spec:** Task 09 真机验证结果：Manifest 声明已生效，但目标 OPPO/Android 15 上 shell 用户执行 `pm grant` 返回 `SecurityException: Neither user 2000 nor current process has android.permission.GRANT_RUNTIME_PERMISSIONS`。

## Global Constraints

- 只允许修改 `app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt`。
- 不修改 `app/src/main/AndroidManifest.xml`，保留 `android.permission.WRITE_SECURE_SETTINGS` 声明。
- 不删除 `secureSettingsGranted` 检测。
- 不修改 `Settings.Global.putInt(...)` 的已有兼容逻辑。
- 不修改 pairing、mDNS、devices 轮询、watcher、shell lifecycle、UI、常用命令或依赖。
- 不尝试 `appops`、root、Shizuku、系统签名、反射或其他绕过方案。
- 不做无关格式化、重命名或清理。
- 代码注释如有新增，使用中文。

---

### Task 1: Remove the automatic pm grant command

**Files:**
- Modify: `app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt`

**Interfaces:**
- Consumes: `secureSettingsGranted: Boolean` in `ADB.initServer()`.
- Produces: unchanged `ADB.initServer(): Boolean`; only removes the unsupported automatic grant side effect.

- [ ] **Step 1: Confirm the current failing block exists**

在 `ADB.initServer()` 中确认当前代码仍包含：

```kotlin
if (!secureSettingsGranted) {
    sendToShellProcess("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS &> /dev/null")
}
```

并确认 `secureSettingsGranted` 仍被前面的无线/USB调试逻辑使用，因此本任务不能删除该变量或相关 imports。

- [ ] **Step 2: Remove only the unsupported automatic grant block**

删除上面的整个 `if (!secureSettingsGranted) { ... }` 块。

删除后，这一段应直接从：

```kotlin
System.loadLibrary("adb")
sendToShellProcess("alias adb=\"$adbPath\"")
```

进入原有的：

```kotlin
if (autoShell)
    sendToShellProcess("echo 'Entered adb shell'")
else
    sendToShellProcess("echo 'Entered non-adb shell'")
```

不要添加替代授权命令，也不要改变 shell 启动顺序。

- [ ] **Step 3: Static scope verification**

运行：

```powershell
rg -n "pm grant|WRITE_SECURE_SETTINGS|secureSettingsGranted" app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt app/src/main/AndroidManifest.xml
```

预期：

- `AdbManager.kt` 中不再存在自动 `pm grant`。
- `AdbManager.kt` 中仍存在 `secureSettingsGranted` 及 `Manifest.permission.WRITE_SECURE_SETTINGS` 检测。
- `AndroidManifest.xml` 中仍存在 `<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" />`。

- [ ] **Step 4: Compile and assemble**

运行：

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

预期：两者 `BUILD SUCCESSFUL`。

若 `assembleDebug` 再次出现历史上的 `JdkImageTransform/jlink.exe` 错误，不要直接认定为基线问题；先 stash 本任务源码修改并用同一命令复现基线。仅当基线同样失败时，记录环境问题并允许继续提交。

- [ ] **Step 5: Optional runtime verification when executor can see a device**

如果执行器自己的 `adb devices` 能看到真机，则安装/启动 Debug 包并确认：

1. 用户手动开启无线调试后，App 仍能进入 `Entered adb shell`。
2. 终端执行 `id` 仍得到 `uid=2000(shell)`。
3. App 不再尝试自动执行 `pm grant ... WRITE_SECURE_SETTINGS`。

如果执行器环境看不到宿主机已连接的设备，只记录 `executor environment cannot see device`；不要据此判断用户没有真机，也不要为此修改代码。

- [ ] **Step 6: Verify changed-file scope**

运行：

```powershell
git status --short
git diff -- app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt
```

提交前必须确认本任务只修改：

```text
app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt
```

若存在任务范围外修改，停止并汇报，不要一起提交。

- [ ] **Step 7: Commit and push**

```bash
git add app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt
git commit -m "fix: remove unsupported secure settings grant"
git push
```

约束：

- 禁止 `git push --force` / `--force-with-lease`。
- 禁止修改 remote。
- 禁止切换分支。
- push 失败时只汇报原始原因，不自行重写历史。

## Completion Report

完成后只需汇报：

1. 实际修改文件。
2. 已删除的自动授权行为。
3. `compileDebugKotlin` 结果。
4. `assembleDebug` 结果。
5. 若能真机验证，汇报 `id` 结果；若执行器不可见设备，明确写 executor 环境不可见设备。
6. 完整 commit SHA。
7. `git push` 结果。
