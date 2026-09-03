# Task 05 — Default to ADB shell

## Goal

Make AdbHelper use the real ADB shell by default when the `auto_shell` preference has never been explicitly stored.

Current behavior in `ADB.initServer()` reads:

```kotlin
sharedPrefs.getBoolean(context.getString(R.string.auto_shell_key), false)
```

With no settings UI currently persisting this value, a fresh/clean install therefore falls back to local app shell:

```kotlin
shell(true, listOf("sh", "-l"))
```

That shell runs under the app UID and is not equivalent to `adb shell`.

This task is intentionally minimal. Do not redesign ADB startup or remove the legacy preference.

## Files

Modify only:

- `app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt`

Do not modify UI files, ViewModel files, resources, Gradle files, or native libraries.

## Required change

In `ADB.initServer()`, change the default value used when reading `auto_shell` from `false` to `true`.

Current:

```kotlin
val autoShellPref = sharedPrefs.getBoolean(
    context.getString(R.string.auto_shell_key),
    false
)
```

Target:

```kotlin
val autoShellPref = sharedPrefs.getBoolean(
    context.getString(R.string.auto_shell_key),
    true
)
```

If the existing code is formatted on one line, preserve the surrounding style unless formatting is required by the compiler.

## Behavioral requirements

- A fresh install or an install with no saved `auto_shell` preference must enter the existing ADB startup branch.
- If a value has already been explicitly stored for `auto_shell`, keep honoring that stored value.
- Do not remove `auto_shell`.
- Do not add or modify a settings screen.
- Do not change `shellProcess` construction except for the default preference value.
- Do not change pairing, mDNS, `adb devices`, `waitForDeathAndReset()`, `WRITE_SECURE_SETTINGS`, or diagnostic logging.
- Do not modify the experimental `AdbManager : AbsAdbConnectionManager` implementation.
- Do not add dependencies.
- Do not perform unrelated cleanup or refactoring.

## Verification

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Required result: `BUILD SUCCESSFUL`.

Then run:

```powershell
.\gradlew.bat :app:assembleDebug
```

If this succeeds, report success.

If it fails with the already-established baseline environment error involving `JdkImageTransform` / `jlink.exe`, verify that the same failure still occurs on the unmodified baseline if necessary, record it as an environment issue, and continue. Do not modify project code to work around that known environment error in this task.

Before committing, run:

```bash
git status --short
git diff -- app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt
```

Confirm that the implementation change is limited to the intended default value. The plan document itself may already be present from the planning commit and must not be rewritten by this task.

## Commit and push

After verification succeeds as defined above:

```bash
git add app/src/main/java/com/anmi/adbhelper/commons/AdbManager.kt
git commit -m "fix: use adb shell by default"
git push
```

Rules:

- Push the current branch only.
- Do not use `--force` or `--force-with-lease`.
- Do not modify remotes.
- Do not switch branches.
- Do not commit unrelated files.
- If commit or push fails, stop and report the exact error instead of changing Git configuration.

## Completion report

Report only:

- modified files
- exact behavior change
- `compileDebugKotlin` result
- `assembleDebug` result
- commit SHA
- `git push` result
