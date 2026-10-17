package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.v5.inner.analytics.crash.CrashReport
import dji.v5.utils.common.ContextUtil
import dji.v5.utils.common.DJIExecutor
import dji.v5.utils.common.DiskUtil
import dji.v5.utils.common.FileUtils
import dji.v5.utils.common.LogUtils
import java.io.File

/**
 * ClassName : MSDKCrashLogVM
 * Description : MSDK 崩潰 Log 展示 ViewModel。提供更新與顯示崩潰 Log 的功能，並可觸發 Java/Native 崩潰測試。
 * Author : daniel.chen
 * CreateDate : 2022/5/7 12:17 下午
 * Copyright : ©2022 DJI All Rights Reserved.
 */
class MSDKCrashLogVM : DJIViewModel() {
    val logInfo = MutableLiveData<String>()
    val logMsg = MutableLiveData<String>()

    /** 更新並載入最新的崩潰 Log。 */
    fun updateLogInfo() {
        loadLatestLog()
    }

    /** 觸發 Java 崩潰測試。 */
    fun testJavaCrash(runInNewThread: Boolean) {
        CrashReport.testJavaCrash(runInNewThread)
    }

    /** 觸發 Native 崩潰測試。 */
    fun testNativeCrash(runInNewThread: Boolean) {
        CrashReport.testNativeCrash(runInNewThread)
    }

    private fun loadLatestLog() {
        DJIExecutor.getExecutorFor(DJIExecutor.Purpose.URGENT).execute {
            var log = "N/A"
            val logDir = LogUtils.getCrashLogPath()
            val file = File(logDir)
            val list = FileUtils.getAllFile(file)
            logMsg.postValue("get ${list.size} crash log,show recent one.pls wait, reading!")
            if (list.size >= 1) {
                list.sortWith { o1, o2 ->
                    val diff = o1.lastModified() - o2.lastModified()
                    -diff.toInt()
                }
                val f = list[0]
                val stringBuilder = StringBuilder()
                stringBuilder.append("-----------------Crash Info: ${f.name}}----------------------")
                stringBuilder.append("\n")
                stringBuilder.append(f.absolutePath)
                stringBuilder.append("\n")
                stringBuilder.append(FileUtils.readFile(f.absolutePath, "\n", 200))
                stringBuilder.append("\n")
                stringBuilder.append("\n")
                log = stringBuilder.toString()
            }
            logInfo.postValue(log)
        }
    }


}