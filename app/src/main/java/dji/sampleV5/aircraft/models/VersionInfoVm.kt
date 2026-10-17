package dji.sampleV5.aircraft.models

import android.annotation.SuppressLint
import dji.sampleV5.aircraft.data.source.VersionInfo
import dji.sampleV5.aircraft.data.source.VersionInfoRepository
import dji.v5.utils.inner.SDKConfig
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.subjects.BehaviorSubject

/**
 * 該 VM 負責磁碟快取邏輯。
 */
class VersionInfoVm : DJIViewModel() {

    private val repository = VersionInfoRepository()

    private var latestVersionInfo: VersionInfo? = null

    /**
     * Pair 中的 Bool 表示最新版本是否大於目前版本。
     */
    private val latestVersionInfoSubject: BehaviorSubject<Pair<VersionInfo, Boolean>> =
        BehaviorSubject.create()

    /**
     * 已經截取版本號中的字母，僅保留數字與分隔符號[.]。
     */
    private val currentBuildVersion: String

    init {
        val version = cutoffLetterUntilDigit(SDKConfig.getInstance().registrationSDKVersion)
        val lastIndexOf = version.indexOf('-')
        currentBuildVersion = if (lastIndexOf > 0) {
            version.subSequence(0, lastIndexOf).toString()
        } else {
            version
        }
    }

    /** 取得目前版本的版本資訊。 */
    fun getCurrentVersionInfo(): Single<VersionInfo> {
        return repository.fetchCurrentVersionInfo(currentBuildVersion)
    }

    /** 更新最新版本資訊，並在有快取時直接通知。 */
    @SuppressLint("CheckResult")
    fun refreshLatestVersionInfo() {
        latestVersionInfo?.let {
            notifyLatestVersion(it)
            return
        }
        repository.fetchLatestVersionInfo(currentBuildVersion)
            .subscribe({
                notifyLatestVersion(it)
            }, {})
    }

    /** 訂閱最新版本資訊的更新事件。 */
    fun listenLatestVersionInfo(): Observable<Pair<VersionInfo, Boolean>> {
        return latestVersionInfoSubject.hide()
    }

    private fun notifyLatestVersion(versionInfo: VersionInfo) {
        val currentVersion = repository.getCurrentVersionExTest().ifEmpty { currentBuildVersion }
        if (currentVersion == versionInfo.versionName) {
            // 最新版本與目前版本一致則只顯示目前版本
            // 不一致時不論目前版本大小都顯示出來提醒使用者 - 楠哥
            return
        }
        latestVersionInfoSubject.onNext(Pair(versionInfo, compareVersion(versionInfo.versionName, currentVersion) > 0))
    }

    private fun compareVersion(version1: String, version2: String): Int {
        val arr1 = version1.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val arr2 = version2.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val maxLen = arr1.size.coerceAtLeast(arr2.size)
        for (i in 0 until maxLen) {
            try {
                val num1 = if (i < arr1.size) arr1[i].toInt() else 0
                val num2 = if (i < arr2.size) arr2[i].toInt() else 0
                if (num1 == num2) {
                    continue
                }
                return num1 - num2
            } catch (_: NumberFormatException) {
                // 不做任何動作
            }
        }
        return 1
    }

    /**
     * 移除字串尾部的字母，直到遇到數字或[.]。
     */
    private fun cutoffLetterUntilDigit(text: String): String {
        val subIndex = text.toCharArray().reversed().run {
            forEachIndexed { index, c ->
                if (c.isDigit() || c == '.') {
                    return@run index
                }
            }
            return@run 0
        }
        return text.substring(0, (text.length - subIndex))
    }
}