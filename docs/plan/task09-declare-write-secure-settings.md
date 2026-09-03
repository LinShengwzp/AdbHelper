# Task 09 — 声明 WRITE_SECURE_SETTINGS 权限

> **For agentic workers:** 这是一个严格限定范围的小任务。只实现本文要求，不顺带重构 ADB 生命周期、配对 UI、常用命令或设置页面。

## Goal

在应用 Manifest 中声明 `android.permission.WRITE_SECURE_SETTINGS`，使现有 `ADB.initServer()` 在取得真正的 ADB shell 后执行的：

```sh
pm grant com.anmi.adbhelper android.permission.WRITE_SECURE_SETTINGS
```

具备可被系统授予的前提。

## Current state

当前 `app/src/main/AndroidManifest.xml` 没有任何 `WRITE_SECURE_SETTINGS` 声明。

现有 `ADB.initServer()` 已经包含以下逻辑，本任务必须复用，不要重写：

```kotlin
val secureSettingsGranted =
    context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

// ... 成功取得 adb shell 后 ...
if (!secureSettingsGranted) {
    sendToShellProcess("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS &> /dev/null")
}
```

Task 07 真机已经验证终端可取得 `uid=2000(shell)`，因此本任务只补 Manifest 声明。

## Scope

**Only modify:**

- `app/src/main/AndroidManifest.xml`

**Do not modify:**

- `AdbManager.kt`
- `AdbViewModel.kt`
- `AdbView.kt`
- `ProcessView.kt`
- `ShellView.kt`
- Gradle / dependencies
- pairing / mDNS / reconnect / watcher 逻辑
- 常用命令新增、编辑、删除逻辑
- Task 07 配对 UI 健壮性

不要新增权限请求弹窗。`WRITE_SECURE_SETTINGS` 不是普通运行时权限，当前设计由已获得的 ADB shell 执行 `pm grant`。

---

## Implementation

### Step 1 — 修改 Manifest

在 `<manifest ...>` 根节点下、`<application>` 之前加入：

```xml
<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" />
```

预期结构：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" />

    <application
        ...
```

不要加入 `tools:ignore`，不要增加其他权限。

### Step 2 — 静态检查修改范围

运行：

```powershell
git status --short
git diff -- app/src/main/AndroidManifest.xml
```

要求：

- 只有 `app/src/main/AndroidManifest.xml` 被修改。
- diff 只新增一条 `uses-permission`。

### Step 3 — 验证 Manifest 处理

运行：

```powershell
.\gradlew.bat :app:processDebugMainManifest
```

预期：`BUILD SUCCESSFUL`。

如果该任务因当前机器已有的 JDK/Android Gradle 环境问题失败，必须记录完整错误，并确认不是 XML/Manifest merge 错误后才能继续。

### Step 4 — Kotlin 编译验证

运行：

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

预期：`BUILD SUCCESSFUL`。

### Step 5 — 保留现有 assembleDebug 基线检查

运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

- 若成功，记录成功。
- 若仍然是此前已确认的 `JdkImageTransform/jlink.exe` 基线环境错误，可以记录后继续。
- 若出现新的 Manifest、资源或 Kotlin 错误，停止，不提交。

### Step 6 — 可选真机验证

如果当前环境方便安装/运行应用，再做以下验证；不能做时不要扩大任务范围。

1. 安装包含本次 Manifest 修改的版本。
2. 确保无线调试已开启并让 AdbHelper 成功取得 `uid=2000(shell)`。
3. 强制停止并重新打开 App，让 `initServer()` 再走一次。
4. 在 ADB shell/终端执行：

```sh
dumpsys package com.anmi.adbhelper | grep WRITE_SECURE_SETTINGS
```

预期能看到该权限已声明，并在系统允许 `pm grant` 后显示为已授予状态（不同 Android ROM 的 `dumpsys` 格式可能不同）。

不要为了验证本任务去修改 `initServer()`、加入新日志或做自动化权限流程。

---

## Commit and push

再次检查：

```powershell
git status --short
git diff --check
git diff -- app/src/main/AndroidManifest.xml
```

确认只有目标 Manifest 文件后提交：

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "fix: declare write secure settings permission"
git push
```

禁止：

- `git push --force` / `--force-with-lease`
- 修改 remote
- 切换分支
- 提交计划范围外文件

如果 push 失败，只报告原因，不自行改变 remote 或分支策略。

## Final report

完成后报告：

1. 修改文件。
2. Manifest 新增的权限。
3. `processDebugMainManifest` 结果。
4. `compileDebugKotlin` 结果。
5. `assembleDebug` 结果；若失败说明是否仍为既有 `JdkImageTransform/jlink.exe` 基线错误。
6. 若做了真机验证，报告 `WRITE_SECURE_SETTINGS` 是否已授予。
7. 完整 commit SHA。
8. `git push` 结果。
