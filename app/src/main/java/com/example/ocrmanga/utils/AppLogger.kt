package com.example.ocrmanga.utils

import android.util.Log
import com.example.ocrmanga.data.constant.AppConfig

object AppLogger {
    fun d(tag: String, msg: String) {
        if (AppConfig.DEBUG_LOGGING) {
            //Log.d(tag, msg)
        }
    }

    fun i(tag: String, msg: String) {
        if (AppConfig.DEBUG_LOGGING) {
            //Log.i(tag, msg)
        }
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (AppConfig.DEBUG_LOGGING) {
            if (tr != null) {
                Log.w(tag, msg, tr)
            } else {
                Log.w(tag, msg)
            }
        }
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (AppConfig.DEBUG_LOGGING) {
            if (tr != null) {
                Log.e(tag, msg, tr)
            } else {
                Log.e(tag, msg)
            }
        }
    }

    fun v(tag: String, msg: String) {
        if (AppConfig.DEBUG_LOGGING) {
            Log.v(tag, msg)
        }
    }

    fun w(tag: String, tr: Throwable) {
        if (AppConfig.DEBUG_LOGGING) {
            Log.w(tag, tr)
        }
    }

    fun wtf(tag: String, msg: String, tr: Throwable? = null) {
        if (AppConfig.DEBUG_LOGGING) {
            if (tr != null) {
                Log.wtf(tag, msg, tr)
            } else {
                Log.wtf(tag, msg)
            }
        }
    }
}
