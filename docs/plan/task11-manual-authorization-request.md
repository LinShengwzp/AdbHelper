# Task11 Manual Authorization Request Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a manual “请求授权” entry next to “配对设备” so the user can explicitly trigger an ADB authorization handshake without re-pairing, creating another shell watcher, or killing the adb server.

**Architecture:** Keep pairing and connection ownership unchanged. Add one short-lived `ADB.requestAuthorizationPrompt()` probe that runs native `adb wait-for-device` for at most 15 seconds, then expose it through a small Compose button in `AdbScreenView`. The probe only attempts to trigger/await the system authorization flow; its Boolean result does **not** mean authorization was granted, because Task 10 confirmed `wait-for-device` may complete before an exact `device` state is reached.

**Tech Stack:** Kotlin, Android, Jetpack Compose, coroutines, bundled native `libadb.so`, `ProcessBuilder`.

**Spec:** Approved bounded design from the Task 10/11 discussion: add a manual authorization-request button beside pairing, reuse the existing persistent native adb identity, and do not duplicate watcher/reconnect logic.

## Global Constraints

- Modify only:
  - `app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt`
  - `app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt`
- Do not modify `AdbViewModel.kt`.
- Do not modify `waitForDeathAndReset()`.
- Do not call `startADBServer()` from the new button.
- Do not create a new shell process from the new button.
- Do not start a new watcher from the new button.
- Do not call `kill-server` from the new authorization flow.
- Do not re-pair, clear adb keys, regenerate adb identity, add `adb connect`, NSD, mDNS, or discovery code.
- Do not restore automatic `pm grant WRITE_SECURE_SETTINGS`.
- Do not alter the existing pairing dialog behavior in this task; Task 08 pairing-dialog hardening remains separate.
- Keep existing Task01 `AdbDiag` instrumentation.
- UI text must not claim “授权成功” solely because `wait-for-device` completed.
- Do not use force push, change remotes, switch branches, rebase, or merge unrelated work.
- If the executor cannot see the user's device via its own `adb devices`, report `executor environment cannot see device`; do not infer that the user has no device.

---

### Task 1: Add a short-lived authorization probe to `ADB`

**Files:**
- Modify: `app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt`

**Interfaces:**
- Consumes: existing `adb(redirect: Boolean, command: List<String>): Process`, `nextInvocationId()`, and `diagLog(...)`.
- Produces: `fun requestAuthorizationPrompt(timeoutSeconds: Long = 15): Boolean`.

- [ ] **Step 1: Inspect the current reconnect code before editing**

Confirm all of the following are still true:

```kotlin
fun waitForDeathAndReset() {
    ...
    adb(false, listOf("kill-server")).waitFor()
    Thread.sleep(3_000)
    initServer()
}
```

and `adb(...)` remains the public native-adb process launcher.

Do not proceed if unrelated changes materially altered these interfaces; report the deviation instead of broadening scope.

- [ ] **Step 2: Add `requestAuthorizationPrompt()`**

Add this public method near the other ADB lifecycle helpers:

```kotlin
fun requestAuthorizationPrompt(timeoutSeconds: Long = 15): Boolean {
    val invocationId = nextInvocationId()
    diagLog(
        "ADB_AUTH_REQUEST_START invocationId=$invocationId " +
            "thread=${Thread.currentThread().name} timeoutSeconds=$timeoutSeconds"
    )

    val waitProcess = adb(false, listOf("wait-for-device"))
    return try {
        val completed = waitProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        diagLog(
            "ADB_AUTH_REQUEST_DONE invocationId=$invocationId " +
                "thread=${Thread.currentThread().name} completed=$completed"
        )
        completed
    } finally {
        if (waitProcess.isAlive) {
            waitProcess.destroyForcibly()
            waitProcess.waitFor()
        }
    }
}
```

Required semantics:

- The method runs only `adb wait-for-device`.
- Do **not** prepend `kill-server`.
- Do **not** call `initServer()`.
- Do **not** call `adb shell`.
- Do **not** call `startADBServer()`.
- Do **not** start or replace a watcher.
- The 15-second timeout prevents a manual button press from leaving a permanent blocking adb subprocess.
- If the process is still alive after timeout, destroy it in `finally`.
- Returning `true` means only that `wait-for-device` completed within the probe window. It must not be treated as proof that the user granted authorization.

- [ ] **Step 3: Verify no watcher/reconnect code changed**

Inspect the diff and confirm `waitForDeathAndReset()` is behaviorally unchanged.

Also confirm the new method contains none of these commands:

```text
kill-server
shell
pair
connect
```

except the method name/context itself where unavoidable.

---

### Task 2: Add the “请求授权” button beside pairing

**Files:**
- Modify: `app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt`

**Interfaces:**
- Consumes: `viewModel.adb.requestAuthorizationPrompt()`.
- Produces: one manual authorization-request button and local UI state only.

- [ ] **Step 1: Add local authorization UI state**

Near the existing pairing state, add:

```kotlin
var authorizationInProgress by remember { mutableStateOf(false) }
var authorizationMessage by remember { mutableStateOf<String?>(null) }
```

Add imports only if actually needed:

```kotlin
import kotlinx.coroutines.CancellationException
```

`OutlinedButton` is already imported. Do not add a new ViewModel field for this temporary UI state.

- [ ] **Step 2: Put pairing and authorization actions in one row**

Replace the standalone “配对设备” button block with a row equivalent to:

```kotlin
Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
) {
    Button(
        onClick = {
            pairingMessage = null
            showPairDialog = true
        }
    ) {
        Text("配对设备")
    }

    OutlinedButton(
        enabled = !authorizationInProgress,
        onClick = {
            if (authorizationInProgress) return@OutlinedButton
            authorizationInProgress = true
            authorizationMessage = null

            scope.launch {
                try {
                    val completed = withContext(Dispatchers.IO) {
                        viewModel.adb.requestAuthorizationPrompt()
                    }

                    authorizationMessage = if (completed) {
                        "已检测到 ADB 连接，请在系统授权弹窗中允许调试。"
                    } else {
                        "授权请求已发起，如未出现弹窗请确认无线调试已开启后重试。"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    authorizationMessage = "授权请求失败，请确认无线调试已开启后重试。"
                } finally {
                    authorizationInProgress = false
                }
            }
        }
    ) {
        Text(if (authorizationInProgress) "请求中…" else "请求授权")
    }
}
```

Important behavior:

- Disable the button while the probe is active so rapid taps cannot start many concurrent `wait-for-device` processes.
- Do not disable or alter the pairing button.
- Do not call `viewModel.startADBServer()`.
- Do not call `viewModel.setPairedBefore(...)`.
- Do not mutate `selectedDevice`, `connectSuccess`, `devices`, or `expectedCommand` from this button.
- Do not claim “授权成功”.

- [ ] **Step 3: Show the authorization status message below the action row**

Immediately below the pairing/authorization row, display the message only when non-null:

```kotlin
authorizationMessage?.let { message ->
    Spacer(Modifier.height(8.dp))
    Text(message, color = Color.White)
}
```

Keep the existing spacing before the common-command `FlowRow` sensible; do not redesign the whole screen.

---

### Task 3: Compile, inspect scope, and perform targeted runtime verification

**Files:**
- Verify only the two allowed source files changed.

- [ ] **Step 1: Compile and assemble**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

Expected:

- `compileDebugKotlin`: `BUILD SUCCESSFUL`
- `assembleDebug`: `BUILD SUCCESSFUL`

If `assembleDebug` fails, investigate the actual current failure. Do not automatically classify it as the previously seen `JdkImageTransform/jlink.exe` issue without reproducing that baseline.

- [ ] **Step 2: Verify diff scope**

Run:

```bash
git status --short
git diff -- app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt
```

Required result:

- Only `AdbManager.kt` and `AdbView.kt` are modified.
- `waitForDeathAndReset()` has no behavioral change.
- Existing pairing implementation is unchanged except for being placed beside the new button.
- No Manifest / Gradle / ViewModel / command-store changes are present.

- [ ] **Step 3: Runtime verification when executor can access a device**

If the executor's own `adb devices` sees a physical device, verify this scenario:

1. Install the new debug APK.
2. Start AdbHelper and confirm the existing terminal shell works.
3. Use a non-permanent authorization state so a later reconnect can require confirmation.
4. Turn Wi-Fi off long enough for the current in-app adb shell to die, then turn Wi-Fi back on.
5. As soon as AdbHelper is visible again, tap “请求授权” once.
6. Confirm the button becomes “请求中…” and cannot be repeatedly triggered during the probe.
7. Confirm `AdbDiag` contains one `ADB_AUTH_REQUEST_START` and one `ADB_AUTH_REQUEST_DONE` for that tap.
8. Confirm the new method itself causes no extra `ADB_SERVER_KILL` and starts no new `ADB_WATCHER_START`.
9. If the system authorization dialog appears, allow it.
10. Confirm the existing reconnect path eventually restores the shell and `id` returns `uid=2000(shell)`.

A single `ADB_SERVER_KILL` from the already-existing watcher after the genuine long-lived shell dies is allowed and is not attributed to the manual button. The requirement is that the manual authorization method must not introduce additional kill/reset cycles.

If the executor cannot access the device, report exactly:

```text
runtime verification: not performed; executor environment cannot see device
```

Do not claim that the user has no physical device.

- [ ] **Step 4: Commit and push**

Run:

```bash
git add app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt
git commit -m "feat: add manual adb authorization request"
git push
```

Do not use `--force` or `--force-with-lease`.

If push fails, report the error and stop. Do not modify remotes, credentials, branch names, or history.

## Completion report

Report all of the following:

1. Modified files.
2. Exact behavior of `requestAuthorizationPrompt()`.
3. UI placement and text of the new button.
4. `compileDebugKotlin` result.
5. `assembleDebug` result.
6. Runtime verification result, including `ADB_AUTH_REQUEST_START/DONE` when available.
7. Full commit SHA.
8. Push result.
