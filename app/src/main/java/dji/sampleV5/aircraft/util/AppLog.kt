package dji.sampleV5.aircraft.util

import android.util.Log
import dji.sampleV5.aircraft.BuildConfig

/** Log wrapper：debug/info 級別在 release build 靜默，warning/error 始終輸出 */
object AppLog {

    /** Debug 級別 log（僅 debug build 輸出） */
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    /** Info 級別 log（僅 debug build 輸出） */
    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i(tag, msg)
    }

    /** Warning 級別 log（始終輸出） */
    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }

    /** Error 級別 log（始終輸出） */
    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
    }

    /** Error 級別 log（附帶例外堆疊，始終輸出） */
    fun e(tag: String, msg: String, throwable: Throwable) {
        Log.e(tag, msg, throwable)
    }
}
