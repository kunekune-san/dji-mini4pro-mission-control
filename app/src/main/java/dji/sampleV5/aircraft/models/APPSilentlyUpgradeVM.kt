package dji.sampleV5.aircraft.models

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dji.sampleV5.aircraft.data.DJIToastResult
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.utils.common.ContextUtil
import dji.v5.utils.common.DeviceInfoUtil
import dji.v5.utils.common.FileUtils
import java.io.File

/**
 * ClassName : APPSilentlyUpgradeVM
 * Description : APP 靜默升級 ViewModel。設定遙控器對指定包名的 APK 進行靜默安裝，並提供透過 FileProvider 安裝測試 APK 的功能。
 * Author : daniel.chen
 * CreateDate : 2022/5/7 12:17 下午
 * Copyright : ©2022 DJI All Rights Reserved.
 */
class APPSilentlyUpgradeVM : DJIViewModel() {

    private val testPackageName = "com.dji.test"
    private val testApkName = "app-debug.apk"

    /**
     * 只適配了 M350 和 Mavic 3 行業版本的遙控器。
     * 設定以後，對應包名的 APK，透過程式碼安裝時，遙控器不再需要使用者操作。
     * 重啟遙控器後設定清空。
     */
    fun setAPPSilentlyUpgrade(context: Context) {
        RemoteControllerKey.KeyAPPSilentlyUpgrade.create().action(testPackageName, {
            toastResult?.postValue(DJIToastResult.success(testPackageName))
        }) {
            toastResult?.postValue(DJIToastResult.failed("$it $testPackageName"))
        }
    }

    // 本範例，安裝包路徑: /sdcard/Android/data/你的 app 的包名/files/你的 apk 檔名.apk
    // 實際路徑請依自身需求設定，透過 FileProvider 安裝 APK
    // 測試 apk（app-debug.apk）在 sample 的 assets 目錄下
    /** 將 assets 目錄中的測試 APK 複製到 App 外部檔案目錄，並透過 FileProvider 觸發系統安裝畫面。 */
    fun installApkWithOutNotice(context: Context) {
        FileUtils.copyAssetsFileIfNeed(context, "apk/$testApkName", File(context.getExternalFilesDir("/"), testApkName))
        val intent = Intent(Intent.ACTION_VIEW)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val file = File(context.getExternalFilesDir("/"), testApkName)
        val uri = FileProvider.getUriForFile(context, DeviceInfoUtil.getPackageName() + ".fileProvider", file)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        context.startActivity(intent)
    }
}