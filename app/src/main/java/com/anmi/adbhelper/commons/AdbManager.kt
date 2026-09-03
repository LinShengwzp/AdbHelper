package com.anmi.adbhelper.commons

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.anmi.adbhelper.R
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.io.PrintStream
import java.security.MessageDigest
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.security.auth.x500.X500Principal

class AdbManager private constructor() : AbsAdbConnectionManager() {
    companion object {
        private var instance: AdbManager? = null

        fun get(): AdbManager {
            if (instance == null) {
                instance = AdbManager()
            }
            return instance!!
        }
    }

    private val keyPair: KeyPair
    private val certificate: Certificate

    init {
        api = Build.VERSION.SDK_INT

        // 生成 KeyPair
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        keyPair = keyGen.generateKeyPair()

        // 生成临时证书
        val x500Name = X500Principal("CN=AnMiAdbHelperApplication")
        val certGen = SelfSignedCertificateGenerator()
        certificate = certGen.generate(x500Name, keyPair)
    }

    override fun getPrivateKey(): PrivateKey = keyPair.private
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "AnMiAdbHelperApplication"

}

class ADB(private val context: Context) {
    companion object {
        const val MAX_OUTPUT_BUFFER_SIZE = 1024 * 16
        const val OUTPUT_BUFFER_DELAY_MS = 100L

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ADB? = null
        fun getInstance(context: Context): ADB = instance ?: synchronized(this) {
            instance ?: ADB(context).also { instance = it }
        }
    }

    private val sharedPrefs = context.getSharedPreferences("adb_helper", Context.MODE_PRIVATE)

    private val adbPath = "${context.applicationInfo.nativeLibraryDir}/libadb.so"
    private val scriptPath = "${context.getExternalFilesDir(null)}/script.sh"

    /**
     * Is the shell ready to handle commands?
     */
    private val _started = MutableLiveData(false)
    val started: LiveData<Boolean> = _started

    private var tryingToPair = false

    private val diagSeq = AtomicLong(0)
    private fun nextInvocationId(): Long = diagSeq.incrementAndGet()
    private fun diagLog(msg: String) {
        Log.d("AdbDiag", msg)
    }
    private fun adbKeyFile(): File = File(File(context.filesDir, ".android"), "adbkey")
    private fun adbPubKeyFile(): File = File(File(context.filesDir, ".android"), "adbkey.pub")
    private fun sha256Of(file: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = file.readBytes()
        md.digest(bytes).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        "ERROR:${e.message}"
    }
    private fun formatAdbKeyDiag(file: File): String = if (!file.exists()) {
        "MISSING path=${file.absolutePath}"
    } else {
        "exists=true path=${file.absolutePath} size=${file.length()} lastModified=${file.lastModified()} sha256=${sha256Of(file)}"
    }
    private fun logAdbKeys(phase: String, invocationId: Long) {
        diagLog("ADB_KEY_${phase} invocationId=$invocationId ${formatAdbKeyDiag(adbKeyFile())} | ${formatAdbKeyDiag(adbPubKeyFile())}")
    }

    /**
     * Is the shell closed for any reason?
     */
    private val _closed = MutableLiveData(false)
    val closed: LiveData<Boolean> = _closed

    /**
     * Where shell output is stored
     */
    val outputBufferFile: File = File.createTempFile("buffer", ".txt").also {
        it.deleteOnExit()
    }

    /**
     * Single shell instance where we can pipe commands to
     */
    private var shellProcess: Process? = null

    /**
     * Returns the user buffer size if valid, else the default
     */
    fun getOutputBufferSize(): Int {
        val userValue =
            sharedPrefs.getString(context.getString(R.string.buffer_size_key), "16384")!!
        return try {
            Integer.parseInt(userValue)
        } catch (_: NumberFormatException) {
            MAX_OUTPUT_BUFFER_SIZE
        }
    }

    /**
     * Start the ADB server
     */
    fun initServer(): Boolean {
        val invocationId = nextInvocationId()
        val autoShellPref = sharedPrefs.getBoolean(context.getString(R.string.auto_shell_key), true)
        diagLog("ADB_INIT_SERVER invocationId=$invocationId thread=${Thread.currentThread().name} _started=${_started.value} tryingToPair=$tryingToPair shellProcessNull=${shellProcess == null} autoShell=$autoShellPref")
        if (_started.value == true || tryingToPair) {
            log("Shell already started")
            diagLog("ADB_INIT_SERVER_EARLY_RETURN invocationId=$invocationId thread=${Thread.currentThread().name} _started=${_started.value} tryingToPair=$tryingToPair shellProcessNull=${shellProcess == null}")
            return true
        }

        tryingToPair = true

        val autoShell = autoShellPref

        val secureSettingsGranted =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        if (autoShell) {
            /* Only do wireless debugging steps on compatible versions */
            if (secureSettingsGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !isWirelessDebuggingEnabled()) {
                    Settings.Global.putInt(
                        context.contentResolver,
                        "adb_wifi_enabled",
                        1
                    )

                    Thread.sleep(2_000)
                } else if (!isUSBDebuggingEnabled()) {
                    Settings.Global.putInt(
                        context.contentResolver,
                        Settings.Global.ADB_ENABLED,
                        1
                    )

                    Thread.sleep(2_000)
                }
            }

            /* Check again... */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !isWirelessDebuggingEnabled()) {
                debug("Wireless debugging is not enabled!")
                debug("Settings -> Developer options -> Wireless debugging")
                debug("Waiting for wireless debugging...")

                while (!isWirelessDebuggingEnabled()) {
                    Thread.sleep(1_000)
                }
            } else if (!isUSBDebuggingEnabled()) {
                debug("USB debugging is not enabled!")
                debug("Settings -> Developer options -> USB debugging")
                debug("Waiting for USB debugging...")

                while (!isUSBDebuggingEnabled()) {
                    Thread.sleep(1_000)
                }
            }

            adb(false, listOf("start-server")).waitFor()
            debug("Waiting for device to connect...")
            debug("This may take a minute")
            val readyDeadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1)
            val waitProcess = adb(false, listOf("wait-for-device")).waitFor(1, TimeUnit.MINUTES)
            if (!waitProcess) {
                debug("Your device didn't connect to LADB")
                debug("If a reboot doesn't work, please contact support")

                tryingToPair = false
                diagLog("ADB_INIT_SERVER_FAILED invocationId=$invocationId thread=${Thread.currentThread().name} reason=wait-for-device timeout")
                return false
            }
            debug("Waiting for authorized ADB device...")
            var authorizedDeviceReady = hasAuthorizedDevice()
            while (!authorizedDeviceReady && System.currentTimeMillis() < readyDeadline) {
                Thread.sleep(1_000)
                authorizedDeviceReady = hasAuthorizedDevice()
            }
            if (!authorizedDeviceReady) {
                tryingToPair = false
                diagLog("ADB_INIT_SERVER_FAILED invocationId=$invocationId thread=${Thread.currentThread().name} reason=authorized-device timeout")
                return false
            }
        }

        shellProcess = if (autoShell) {
            val argList = if (Build.SUPPORTED_ABIS[0] == "arm64-v8a")
                listOf("-t", "1", "shell")
            else
                listOf("shell")

            adb(true, argList)
        } else {
            shell(true, listOf("sh", "-l"))
        }

        System.loadLibrary("adb")
        sendToShellProcess("alias adb=\"$adbPath\"")

        if (autoShell)
            sendToShellProcess("echo 'Entered adb shell'")
        else
            sendToShellProcess("echo 'Entered non-adb shell'")

        val startupCommand =
            sharedPrefs.getString(
                context.getString(R.string.startup_command_key),
                "echo 'Success! ※\\(^o^)/※'"
            )!!
        if (startupCommand.isNotEmpty())
            sendToShellProcess(startupCommand)

        _started.postValue(true)
        tryingToPair = false

        diagLog("ADB_INIT_SERVER_DONE invocationId=$invocationId thread=${Thread.currentThread().name} _started=${_started.value} tryingToPair=$tryingToPair shellProcessNull=${shellProcess == null} autoShell=$autoShell")
        return true
    }

    private fun isWirelessDebuggingEnabled() =
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1

    private fun isUSBDebuggingEnabled() =
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

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

    /**
     * Wait restart the shell once it dies
     */
    fun waitForDeathAndReset() {
        val watcherId = nextInvocationId()
        diagLog("ADB_WATCHER_START watcherId=$watcherId thread=${Thread.currentThread().name} shellProcessNull=${shellProcess == null} _started=${_started.value} tryingToPair=$tryingToPair")
        while (true) {
            shellProcess?.waitFor()
            _started.postValue(false)
            debug("Shell is dead, resetting")
            diagLog("ADB_SERVER_KILL watcherId=$watcherId thread=${Thread.currentThread().name} shellProcessNull=${shellProcess == null} _started=${_started.value} tryingToPair=$tryingToPair")
            adb(false, listOf("kill-server")).waitFor()
            Thread.sleep(3_000)
            initServer()
        }
    }

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

    /**
     * Ask the device to pair on Android 11+ devices
     */
    fun pair(port: String, pairingCode: String): Boolean {
        val pairShell = adb(false, listOf("pair", "localhost:$port"))

        /* Sleep to allow shell to catch up */
        Thread.sleep(5000)

        /* Pipe pairing code */
        PrintStream(pairShell.outputStream).apply {
            println(pairingCode)
            flush()
        }

        /* Continue once finished pairing (or 10s elapses) */
        pairShell.waitFor(10, TimeUnit.SECONDS)
        pairShell.destroyForcibly().waitFor()

        val killShell = adb(false, listOf("kill-server"))
        killShell.waitFor(3, TimeUnit.SECONDS)
        killShell.destroyForcibly()

        return pairShell.exitValue() == 0
    }

    /**
     * Send a raw ADB command
     */
    fun adb(redirect: Boolean, command: List<String>): Process {
        val invocationId = nextInvocationId()
        logAdbKeys("BEFORE", invocationId)
        val commandList = command.toMutableList().also {
            it.add(0, adbPath)
        }
        val process = shell(redirect, commandList)
        val pidStr = processPidForDiag(process)
        val home = context.filesDir.path
        diagLog("ADB_ADB invocationId=$invocationId thread=${Thread.currentThread().name} command=${commandList.joinToString(" ")} pid=$pidStr HOME=$home adbkey=${adbKeyFile().absolutePath} adbkeyPub=${adbPubKeyFile().absolutePath} redirect=$redirect")
        Thread {
            try {
                val exitCode = process.waitFor()
                val pidAfter = processPidForDiag(process)
                diagLog("ADB_ADB_AFTER invocationId=$invocationId pid=$pidAfter exitCode=$exitCode command=${commandList.joinToString(" ")}")
                logAdbKeys("AFTER", invocationId)
            } catch (e: Exception) {
                diagLog("ADB_ADB_AFTER_ERROR invocationId=$invocationId error=${e.message}")
            }
        }.apply { isDaemon = true; start() }
        return process
    }

    /**
     * Send a raw shell command
     */
    private fun processPidForDiag(process: Process): String {
        return runCatching {
            process.javaClass.methods
                .firstOrNull { it.name == "pid" && it.parameterCount == 0 }
                ?.invoke(process)
                ?.toString()
                ?: "unsupported"
        }.getOrElse {
            "unsupported:${it.javaClass.simpleName}"
        }
    }

    fun shell(redirect: Boolean, command: List<String>): Process {
        val processBuilder = ProcessBuilder(command)
            .directory(context.filesDir)
            .apply {
                if (redirect) {
                    redirectErrorStream(true)
                    redirectOutput(outputBufferFile)
                }

                environment().apply {
                    put("HOME", context.filesDir.path)
                    put("TMPDIR", context.cacheDir.path)
                }
            }

        return processBuilder.start()!!
    }

    /**
     * Send commands directly to the shell process
     */
    fun sendToShellProcess(msg: String) {
        if (shellProcess == null || shellProcess?.outputStream == null)
            return
        PrintStream(shellProcess!!.outputStream!!).apply {
            println(msg)
            flush()
        }
    }

    /**
     * Write a debug message to the user
     */
    fun debug(msg: String) {
        synchronized(outputBufferFile) {
            if (outputBufferFile.exists())
                outputBufferFile.appendText(msg + System.lineSeparator())
        }
    }
}
