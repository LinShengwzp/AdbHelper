package com.anmi.adbhelper.commons

import android.util.Log

fun log(vararg msg: String, level: Int = 0, tag: String = "BLE_UTIL") {
    when (level) {
        0 ->  Log.d(tag, msg.joinToString(" "))
        2 -> Log.w(tag, msg.joinToString(" "))
        3 -> Log.e(tag, msg.joinToString(" "))
        else -> Log.i(tag, msg.joinToString(" "))
    }
}