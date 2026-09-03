# Task10 Authorized Device Wait Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent `initServer()` from creating the long-lived ADB shell while the local transport is still `unauthorized` / `authorizing` / `offline`, so startup waits for an actually usable `device` transport before the shell death watcher can be involved.

**Architecture:** Keep the existing native `libadb.so` startup path. After `adb wait-for-device` returns, poll `adb devices` sequentially and only continue when at least one row has the exact transport state `device`. Do not modify `waitForDeathAndReset()` in this task; this task is intentionally limited to startup readiness so later runtime reconnect behavior can be evaluated separately.

**Tech Stack:** Kotlin, Android, bundled native `libadb.so`, `ProcessBuilder`, Gradle.

**Spec:** `docs/analysis/task06-pairing-discovery-audit.md` plus the confirmed runtime observation that a transport may pass `wait-for-device` before it is ready for a stable shell.

## Global Constraints

- Modify only `app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt`.
- Do not modify `AdbViewModel.kt`, Compose UI, pairing UI, polling in `AdbView.kt` / `ProcessView.kt`, Manifest, Gradle files, dependencies, or command editing.
- Do not modify the behavior of `waitForDeathAndReset()` in this task.
- Do not add `adb connect`, NSD, mDNS, `_adb-tls-connect._tcp`, or any new discovery implementation.
- Do not restore automatic `pm grant WRITE_SECURE_SETTINGS`.
- Keep Task01 `AdbDiag` instrumentation unless this task explicitly adds a small readiness log.
- Do not use force push, change remotes, or change branches.
- If the executor cannot see the user's device via its own `adb devices`, report `executor environment cannot see device`; do not claim that the user has no physical device.

---

### Task 1: Add an authorized-transport readiness check

**Files:**
- Modify: `app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt`

**Interfaces:**
- Consumes: existing `adb(redirect: Boolean, command: List<String>): Process`.
- Produces: a private readiness helper used only by `initServer()`.

- [ ] **Step 1: Inspect the current startup block before editing**

Confirm that the current order is still:

```kotlin
adb(false, listOf("start-server")).waitFor()
val waitProcess = adb(false, listOf("wait-for-device")).waitFor(1, TimeUnit.MINUTES)
if (!waitProcess) {
    ...
    return false
}

shellProcess = if (autoShell) {
    ...
    adb(true, argList)
} else {
    ...
}
```

Do not proceed if unrelated code has changed this structure materially; report the deviation instead of broadening scope.

- [ ] **Step 2: Add one small private helper for `adb devices` state**

Add a private helper in `ADB` that checks whether `adb devices` contains at least one exact `device` transport state.

Required behavior:

```kotlin
private fun hasAuthorizedDevice(): Boolean {
    val process = adb(false, listOf("devices"))
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (exitCode != 0) return false

    return output.lineSequence()
        .drop(1)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .any { line ->
            val parts = line.split('\t', limit = 2)
            parts.size == 2 && parts[1].trim() == "device"
        }
}
```

Important constraints:

- Read stdout before `waitFor()` so the implementation does not introduce a pipe-buffer deadlock pattern.
- Match the state exactly as `device`; `unauthorized`, `offline`, and `authorizing` must not pass.
- Do not write `adb devices` output into `outputBufferFile`.
- Do not add shared UI state for this helper.

- [ ] **Step 3: Gate shell creation on authorized state**

Keep the existing `adb start-server` and `adb wait-for-device` logic.

Immediately after `wait-for-device` succeeds, wait until `hasAuthorizedDevice()` becomes true before creating `shellProcess`.

Use one overall 60-second readiness window beginning immediately before the existing `wait-for-device` call, so this task does not silently turn the startup timeout into two independent 60-second waits.

A suitable shape is:

```kotlin
val readyDeadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1)
val waitProcess = adb(false, listOf("wait-for-device"))
    .waitFor(1, TimeUnit.MINUTES)

if (!waitProcess) {
    tryingToPair = false
    diagLog(
        "ADB_INIT_SERVER_FAILED invocationId=$invocationId " +
            "thread=${Thread.currentThread().name} reason=wait-for-device timeout"
    )
    return false
}

debug("Waiting for authorized ADB device...")
while (System.currentTimeMillis() < readyDeadline && !hasAuthorizedDevice()) {
    Thread.sleep(1_000)
}

if (!hasAuthorizedDevice()) {
    tryingToPair = false
    diagLog(
        "ADB_INIT_SERVER_FAILED invocationId=$invocationId " +
            "thread=${Thread.currentThread().name} reason=authorized-device timeout"
    )
    return false
}
```

However, avoid executing a redundant final `adb devices` call if the loop already observed success. Prefer a local Boolean such as `authorizedDeviceReady`:

```kotlin
var authorizedDeviceReady = hasAuthorizedDevice()
while (!authorizedDeviceReady && System.currentTimeMillis() < readyDeadline) {
    Thread.sleep(1_000)
    authorizedDeviceReady = hasAuthorizedDevice()
}

if (!authorizedDeviceReady) {
    ...
    return false
}
```

The final implementation must satisfy all of these conditions:

- `shellProcess = ... adb(... "shell")` is reached only after an exact `\tdevice` transport exists.
- `tryingToPair` is reset to `false` on the new timeout path.
- No `kill-server` is added to the readiness loop.
- Poll interval is approximately 1 second.
- The total readiness window remains approximately 60 seconds from the beginning of `wait-for-device`.

- [ ] **Step 4: Keep watcher behavior untouched**

Verify that this method remains behaviorally unchanged:

```kotlin
fun waitForDeathAndReset() {
    ...
}
```

Do not add backoff, synchronization, watcher IDs, new stop conditions, or different `kill-server` behavior here. Any remaining runtime reconnect problem belongs to a later task.

- [ ] **Step 5: Compile and assemble**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

Expected:

- `compileDebugKotlin`: `BUILD SUCCESSFUL`
- `assembleDebug`: `BUILD SUCCESSFUL`

If `assembleDebug` fails, compare with the untouched baseline only if necessary. Do not automatically classify every build failure as the previously seen `JdkImageTransform/jlink.exe` issue.

- [ ] **Step 6: Verify diff scope**

Run:

```bash
git status --short
git diff -- app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt
```

Required result:

- Only `AdbManager.kt` is modified.
- `waitForDeathAndReset()` has no behavioral changes.
- No UI / Manifest / Gradle / pairing changes are present.

- [ ] **Step 7: Runtime verification when the executor can actually access a device**

If the executor's own `adb devices` sees the physical device, perform this targeted verification:

1. Revoke existing debugging authorizations or use a state that forces a fresh authorization prompt.
2. Launch AdbHelper.
3. Leave the system authorization dialog pending for at least 5 seconds.
4. During that pending period, inspect `AdbDiag` logs.
5. Confirm no `ADB_WATCHER_START` and no `ADB_SERVER_KILL` occur before authorization completes.
6. Accept authorization.
7. Confirm startup proceeds to one successful `ADB_INIT_SERVER_DONE` and then one watcher start.
8. Confirm the terminal can execute `id` and returns `uid=2000(shell)`.

If the executor cannot access the device, do not fail the task. Report exactly:

```text
runtime verification: not performed; executor environment cannot see device
```

Do not claim that the user has no device.

- [ ] **Step 8: Commit and push**

Run:

```bash
git add app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt
git commit -m "fix: wait for authorized adb device"
git push
```

Do not use `--force` or `--force-with-lease`.

If push fails, report the error and stop. Do not modify remotes, credentials, branch names, or repository configuration.

## Completion report

Report all of the following:

1. Modified files.
2. Exact readiness behavior added after `wait-for-device`.
3. `compileDebugKotlin` result.
4. `assembleDebug` result.
5. Runtime verification result, or the exact `executor environment cannot see device` message.
6. Full commit SHA.
7. Push result.
