# Task11.1 Authorization Probe Result Semantics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `ADB.requestAuthorizationPrompt()` so it returns `true` only when `adb wait-for-device` both finishes within the timeout and exits successfully, eliminating the confirmed false-positive case where `waitFor(timeout)` returned `true` while the adb process itself exited with code `1`.

**Architecture:** Keep the Task 11 short-lived authorization probe and UI unchanged. Refine only the probe result semantics in `AdbManager.kt`: distinguish process completion from command success, record the adb exit code in `AdbDiag`, and return success only for `completed && exitCode == 0`. Timeout cleanup remains exactly the same.

**Tech Stack:** Kotlin, Android, bundled native `libadb.so`, `ProcessBuilder`, Java `Process.waitFor(timeout, TimeUnit)`.

**Spec:** Approved bounded follow-up to Task 11 after true-device evidence showed `ADB_ADB_AFTER ... wait-for-device exitCode=1` followed by `ADB_AUTH_REQUEST_DONE ... completed=true`. `Process.waitFor(timeout, unit)` reports whether the process terminated in time; it does not report whether the command succeeded.

## Global Constraints

- Modify only `app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt`.
- Do not modify `AdbView.kt`, `AdbViewModel.kt`, pairing UI, command UI, Manifest, Gradle files, or dependencies.
- Do not modify `waitForDeathAndReset()`.
- Do not add or remove `kill-server`, `shell`, `pair`, `connect`, `start-server`, NSD, mDNS, or discovery behavior.
- Do not change the 15-second default timeout.
- Do not change timeout cleanup: a still-alive probe process must still be forcibly destroyed and waited for in `finally`.
- Do not change the Task 11 UI messages in this task.
- Do not remove or alter the default `tcpip` command in this task; that is intentionally deferred to Task 12.
- The user's IDEA workstation reaches the physical test phone over OpenVPN and currently depends on host-side `adb tcpip 5555`. **Do not run `adb usb`, disable TCP ADB, reset adbd transport mode, or otherwise break that remote development connection during this task.**
- A phone-local `emulator-5554` transport may reappear while host-side `adb tcpip 5555` is intentionally enabled. Treat that as a known development-environment artifact, not as Task 11.1 failure.
- Keep existing Task01 `AdbDiag` instrumentation.
- Do not use force push, change remotes, switch branches, rebase, or merge unrelated work.
- If the executor cannot see the user's device via its own `adb devices`, report `executor environment cannot see device`; do not infer that the user has no physical device.

---

### Task 1: Make authorization-probe success depend on adb exit code

**Files:**
- Modify: `app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt`

**Interfaces:**
- Consumes: existing `fun requestAuthorizationPrompt(timeoutSeconds: Long = 15): Boolean` and native `adb(false, listOf("wait-for-device"))` process launcher.
- Produces: same public method signature, but `true` now means the probe process completed within the timeout **and** exited with status `0`.

- [ ] **Step 1: Inspect the current method and confirm the reproduced bug still exists**

The current implementation should still contain this semantic bug:

```kotlin
val completed = waitProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS)
...
completed
```

Confirm that the method currently logs only `completed=$completed` and does not inspect `waitProcess.exitValue()` when the process has completed.

Do not proceed if unrelated changes have materially rewritten this method; report the deviation rather than broadening scope.

- [ ] **Step 2: Replace completion-only result handling with completion + exit-code handling**

Inside the existing `try` block, change the result logic to the following shape:

```kotlin
val completed = waitProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS)
val exitCode = if (completed) waitProcess.exitValue() else null
val success = completed && exitCode == 0

diagLog(
    "ADB_AUTH_REQUEST_DONE invocationId=$invocationId " +
        "thread=${Thread.currentThread().name} completed=$completed " +
        "exitCode=${exitCode ?: "timeout"} success=$success"
)

success
```

Required semantics:

- If the process is still running after 15 seconds:
  - `completed=false`
  - `exitCode=timeout` in the diagnostic log
  - method returns `false`
  - existing `finally` destroys and waits for the still-alive process.
- If `wait-for-device` exits `0` within 15 seconds:
  - `completed=true`
  - `exitCode=0`
  - `success=true`
  - method returns `true`.
- If `wait-for-device` exits nonzero within 15 seconds, including the already observed `exitCode=1` case:
  - `completed=true`
  - diagnostic log records the real nonzero exit code
  - `success=false`
  - method returns `false`.
- Do not catch or suppress new exceptions merely to force a Boolean result. Preserve the existing caller-side exception handling from Task 11.

- [ ] **Step 3: Preserve timeout cleanup exactly**

Keep the existing `finally` behavior equivalent to:

```kotlin
finally {
    if (waitProcess.isAlive) {
        waitProcess.destroyForcibly()
        waitProcess.waitFor()
    }
}
```

Do not move `destroyForcibly()` into the normal success/failure path and do not kill the adb server.

- [ ] **Step 4: Verify scope before building**

Run:

```bash
git status --short
git diff -- app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt
```

Required result:

- Only `AdbManager.kt` is modified.
- `requestAuthorizationPrompt()` keeps the same signature and 15-second default.
- `waitForDeathAndReset()` is behaviorally unchanged.
- No UI / pairing / command-store / Manifest / Gradle change exists.
- No `adb usb` or TCP transport reconfiguration is introduced anywhere.

- [ ] **Step 5: Compile and assemble**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

Expected:

- `compileDebugKotlin`: `BUILD SUCCESSFUL`
- `assembleDebug`: `BUILD SUCCESSFUL`

If either build fails, investigate the actual current failure. Do not automatically classify it as the historical `JdkImageTransform/jlink.exe` issue without current evidence.

- [ ] **Step 6: Perform safe runtime verification only if the executor can access the device without breaking OpenVPN ADB**

The user intentionally depends on host-side `adb tcpip 5555` for remote IDEA debugging. Runtime verification must therefore be non-destructive.

If the executor can launch the current APK and inspect `AdbDiag` without running `adb usb` or changing adbd transport mode:

1. Tap `请求授权` once.
2. Find the matching `ADB_AUTH_REQUEST_START` and `ADB_AUTH_REQUEST_DONE` lines.
3. Verify the DONE line now includes all three fields:

```text
completed=...
exitCode=...
success=...
```

4. If the underlying `ADB_ADB_AFTER ... wait-for-device` line reports `exitCode=0`, verify `success=true`.
5. If the underlying adb process reports any nonzero exit code, verify `success=false` even though `completed=true`.
6. If the probe times out, verify `completed=false exitCode=timeout success=false`, and the later forcibly terminated adb child may separately log an OS-level termination exit code such as `143`.
7. Confirm the button itself adds no `ADB_SERVER_KILL` and no new `ADB_WATCHER_START`.

Do **not** manufacture a failure by running `adb usb`, disabling the user's remote TCP ADB connection, revoking unrelated host access, or changing OpenVPN configuration.

If safe runtime verification cannot be performed, report exactly:

```text
runtime verification: not performed; preserving user's OpenVPN adb tcpip development connection
```

or, if the executor cannot see the device at all:

```text
runtime verification: not performed; executor environment cannot see device
```

- [ ] **Step 7: Final diff check, commit, and push**

Run:

```bash
git status --short
git diff --check
git diff -- app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt
git add app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt
git commit -m "fix: validate adb authorization probe result"
git push
```

Do not use `--force` or `--force-with-lease`.

If push fails, report the error and stop. Do not modify remotes, credentials, branch names, or repository history.

## Completion report

Report all of the following:

1. Modified files.
2. Exact new Boolean semantics of `requestAuthorizationPrompt()`.
3. Exact `ADB_AUTH_REQUEST_DONE` fields now logged.
4. `compileDebugKotlin` result.
5. `assembleDebug` result.
6. Safe runtime verification result, including the observed `completed / exitCode / success` tuple when available.
7. Confirmation that no `adb usb` / TCP transport reconfiguration was performed.
8. Full commit SHA.
9. Push result.
