# Task12 Remove Default TCP/IP Command Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the built-in `tcpip 5555` quick command from AdbHelper and migrate the exact legacy built-in entry out of existing persisted command lists once, without changing the device's current ADB transport state.

**Architecture:** Keep `CommandStore` and the common-command UI structure unchanged. Update only `AdbScreenView` so new installs no longer seed the `tcpip 5555` command, while existing installs run one exact, one-time migration that removes only the old built-in `AdbCommand("tcpip", listOf("tcpip", "5555"))`. Record the migration in the existing `app_config` SharedPreferences so a user who later manually adds the same command is not repeatedly deleted.

**Tech Stack:** Kotlin, Jetpack Compose, Android SharedPreferences, existing DataStore-backed `CommandStore`.

**Spec:** Approved bounded Task 12 design from the Task 11.1 review: remove the dangerous in-app default `tcpip 5555` entry while preserving the user's separate Windows/IDEA → OpenVPN → device `adb tcpip 5555` development connection.

## Global Constraints

- Modify only `app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt`.
- Do not modify `CommandStore`, `AdbCommand`, `AppConfigModel.kt`, `AdbManager.kt`, `AdbViewModel.kt`, Manifest, Gradle, pairing, authorization, watcher, or reconnect code.
- Do not execute or add any `adb usb`, `adb tcpip`, `kill-server`, `connect`, `disconnect`, `pair`, or transport-reconfiguration command as part of this migration.
- Do not change the device's current TCP 5555 state. The user's Windows/IDEA → OpenVPN → device `adb tcpip 5555` path must remain untouched.
- Do not remove arbitrary commands named `tcpip`. Remove only the exact legacy built-in entry whose `name == "tcpip"` and `command == listOf("tcpip", "5555")`.
- The migration must run at most once per app data set. After the migration marker is set, a user may manually add the same command again and it must not be silently deleted on later launches.
- Preserve all other persisted commands and their order.
- Do not add edit/delete command UI in this task.
- Do not use force push, change remotes, switch branches, rebase, or merge unrelated work.
- If runtime verification would require disabling the user's remote TCP ADB transport, skip that part and report it; do not run `adb usb`.

---

### Task 1: Remove `tcpip 5555` from new-install defaults

**Files:**
- Modify: `app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt`

**Interfaces:**
- Consumes: existing local `defaultCommands` list in `AdbScreenView`.
- Produces: the same default command list minus the legacy `tcpip 5555` entry.

- [ ] **Step 1: Inspect the current default command list**

Confirm it still contains exactly this legacy built-in entry:

```kotlin
AdbCommand("tcpip", listOf("tcpip", "5555")),
```

If unrelated changes materially altered the command initialization flow, stop and report the deviation rather than broadening the task.

- [ ] **Step 2: Remove only the built-in TCP/IP entry**

Delete exactly this line from `defaultCommands`:

```kotlin
AdbCommand("tcpip", listOf("tcpip", "5555")),
```

Do not change the remaining default commands or their relative order.

Expected remaining defaults include the existing entries such as `devices`, `screencap`, `apps`, `ps`, and `logcat`.

---

### Task 2: Migrate the exact persisted legacy entry once

**Files:**
- Modify: `app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt`

**Interfaces:**
- Consumes: existing `CommandStore.loadCommands(context)` / `CommandStore.saveCommands(context, ...)` and `context.getSharedPreferences(...)`.
- Produces: one-time migration marker `remove_legacy_tcpip_default_v1` in the existing `app_config` SharedPreferences.

- [ ] **Step 1: Replace the command-loading `LaunchedEffect(Unit)` with one-time migration logic**

The current structure is equivalent to:

```kotlin
LaunchedEffect(Unit) {
    val stored = CommandStore.loadCommands(context)
    if (stored.isEmpty()) {
        commandList.addAll(defaultCommands)
        CommandStore.saveCommands(context, defaultCommands)
    } else {
        commandList.addAll(stored)
    }
}
```

Replace it with logic equivalent to the following:

```kotlin
LaunchedEffect(Unit) {
    val migrationPrefs = context.getSharedPreferences("app_config", Context.MODE_PRIVATE)
    val migrationKey = "remove_legacy_tcpip_default_v1"
    val migrationDone = migrationPrefs.getBoolean(migrationKey, false)

    val stored = CommandStore.loadCommands(context)
    val migratedStored = if (!migrationDone) {
        stored.filterNot { command ->
            command.name == "tcpip" && command.command == listOf("tcpip", "5555")
        }
    } else {
        stored
    }

    if (!migrationDone) {
        if (migratedStored != stored) {
            CommandStore.saveCommands(context, migratedStored)
        }
        migrationPrefs.edit().putBoolean(migrationKey, true).apply()
    }

    if (stored.isEmpty()) {
        commandList.addAll(defaultCommands)
        CommandStore.saveCommands(context, defaultCommands)
    } else {
        commandList.addAll(migratedStored)
    }
}
```

Required semantics:

- On a fresh install / empty command store, seed the new defaults without `tcpip 5555`.
- On an existing install before migration, remove every persisted entry that is **exactly** `name="tcpip"` and `command=["tcpip", "5555"]`, preserving every other command and order.
- Persist the filtered list only when it actually differs from the stored list.
- Set `remove_legacy_tcpip_default_v1=true` after the first migration check, whether or not the exact entry was found.
- On later launches, do not filter again.
- If a user manually re-adds `tcpip 5555` after the marker is set, leave it alone.
- If an existing non-empty store contains only the legacy `tcpip 5555` entry, migration may legitimately leave the command list empty. Do not repopulate defaults in that case because that would overwrite the user's persisted command-set intent.

Do not move this migration into `CommandStore` or create a new persistence abstraction.

- [ ] **Step 2: Inspect the resulting source for transport side effects**

Confirm the migration code only manipulates the persisted command list and SharedPreferences.

The changed source must not add calls equivalent to any of these:

```text
adb usb
adb tcpip 5555
kill-server
connect
disconnect
pair
```

The literal strings `"tcpip"` and `"5555"` are allowed only for identifying/removing the legacy persisted command.

---

### Task 3: Compile, verify migration scope, and commit

**Files:**
- Verify only `app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt` changed.

- [ ] **Step 1: Compile and assemble**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

Expected:

- `compileDebugKotlin`: `BUILD SUCCESSFUL`
- `assembleDebug`: `BUILD SUCCESSFUL`

If either fails, investigate the current failure. Do not broaden scope to unrelated fixes.

- [ ] **Step 2: Verify diff scope and migration semantics**

Run:

```bash
git status --short
git diff -- app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt
```

Confirm all of the following:

- Only `AdbView.kt` is modified.
- `defaultCommands` no longer contains `AdbCommand("tcpip", listOf("tcpip", "5555"))`.
- The one-time marker key is exactly `remove_legacy_tcpip_default_v1`.
- Filtering is exact on both command name and command list.
- Other commands are preserved.
- No ADB transport command is executed by the migration.

- [ ] **Step 3: Safe runtime verification when possible**

If the executor can launch the app without disrupting the user's remote OpenVPN/TCP ADB development path, verify:

1. Existing stored legacy `tcpip 5555` quick command disappears from the common-command list after the first launch with this build.
2. Other commands remain present and in the same order.
3. Relaunching the app does not perform another removal pass that affects newly added commands.
4. The device's external TCP ADB connection remains usable.

Do **not** run `adb usb` or otherwise disable TCP 5555 for this verification.

If safe runtime verification is not possible, report:

```text
runtime verification: not performed; preserving user's OpenVPN adb tcpip development connection
```

- [ ] **Step 4: Commit and push**

Run:

```bash
git add app/src/main/java/com/anmi/adbhelper/ui/views/AdbView.kt
git commit -m "fix: remove legacy tcpip quick command"
git push
```

Do not use `--force` or `--force-with-lease`.

If push fails, report the error and stop. Do not modify remotes, credentials, branch names, or history.

## Completion report

Report all of the following:

1. Modified files.
2. Confirmation that new defaults no longer include `tcpip 5555`.
3. Exact one-time migration predicate and marker key.
4. Whether an existing persisted legacy entry was removed during runtime verification, if runtime verification was performed.
5. `compileDebugKotlin` result.
6. `assembleDebug` result.
7. Runtime verification result.
8. Confirmation that no `adb usb` or TCP transport reconfiguration command was executed.
9. Full commit SHA.
10. Push result.
