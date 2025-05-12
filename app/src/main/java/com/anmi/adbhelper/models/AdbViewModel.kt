package com.draco.ladb.viewmodels

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anmi.adbhelper.R
import com.anmi.adbhelper.commons.ADB
import com.anmi.adbhelper.commons.log
import com.github.javiersantos.piracychecker.PiracyChecker
import com.github.javiersantos.piracychecker.piracyChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class AdbViewModel(private val context: Context) : ViewModel() {
    private val _outputText = MutableLiveData<String>()
    val outputText: LiveData<String> = _outputText

    val isPairing = MutableLiveData<Boolean>()

    private var checker: PiracyChecker? = null

    private val sharedPreferences = context.getSharedPreferences("app_config", Context.MODE_PRIVATE)
    val adb = ADB.getInstance(context.applicationContext)

    init {
        startOutputThread()
    }

    fun startADBServer(callback: ((Boolean) -> (Unit))? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            log("Start adb server ...")
            val success = adb.initServer()
            if (success)
                startShellDeathThread()
            callback?.invoke(success)
        }
    }

    /**
     * Start the piracy checker if it is not setup yet (release builds only)
     *
     * @param activity Activity to use when showing the error
     */
    fun piracyCheck(activity: Activity) {
        if (checker != null) return

        checker = activity.piracyChecker {
            enableGooglePlayLicensing("...key...")
            saveResultToSharedPreferences(
                sharedPreferences,
                context.getString(R.string.pref_key_verified)
            )
        }

        val verified =
            sharedPreferences.getBoolean(context.getString(R.string.pref_key_verified), false)
        if (!verified) checker?.start()
    }

    /**
     * Continuously update shell output
     */
    private fun startOutputThread() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val out = readOutputFile(adb.outputBufferFile)
                val currentText = _outputText.value
                if (out != currentText)
                    _outputText.postValue(out)
                Thread.sleep(ADB.OUTPUT_BUFFER_DELAY_MS)
            }
        }
    }

    /**
     * Start a death listener to restart the shell once it dies
     */
    private fun startShellDeathThread() {
        viewModelScope.launch(Dispatchers.IO) {
            adb.waitForDeathAndReset()
        }
    }

    /**
     * Erase all shell text
     */
    fun clearOutputText() {
        adb.outputBufferFile.writeText("")
    }

    /**
     * Check if the user should be prompted to pair
     */
    fun needsToPair(): Boolean {
        return !sharedPreferences.getBoolean(context.getString(R.string.paired_key), false) &&
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
    }

    fun setPairedBefore(value: Boolean) {
        sharedPreferences.edit {
            putBoolean(context.getString(R.string.paired_key), value)
        }
    }

    /**
     * Read the content of the ABD output file
     */
    private fun readOutputFile(file: File): String {
        val out = ByteArray(adb.getOutputBufferSize())

        synchronized(file) {
            if (!file.exists())
                return ""

            file.inputStream().use {
                val size = it.channel.size()

                if (size <= out.size)
                    return String(it.readBytes())

                val newPos = (it.channel.size() - out.size)
                it.channel.position(newPos)
                it.read(out)
            }
        }

        return String(out)
    }
}