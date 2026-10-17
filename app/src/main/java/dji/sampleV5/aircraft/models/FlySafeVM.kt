package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.data.DJIToastResult
import dji.sampleV5.aircraft.util.Util
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.flysafe.FlySafeDatabaseComponent
import dji.v5.manager.aircraft.flysafe.FlySafeDatabaseListener
import dji.v5.manager.aircraft.flysafe.FlySafeDatabaseState
import dji.v5.manager.aircraft.flysafe.FlySafeDatabaseUpgradeMode
import dji.v5.manager.aircraft.flysafe.FlySafeNotificationListener
import dji.v5.manager.aircraft.flysafe.FlyZoneManager
import dji.v5.manager.aircraft.flysafe.info.FlySafeDatabaseInfo
import dji.v5.manager.aircraft.flysafe.info.FlySafeReturnToHomeInformation
import dji.v5.manager.aircraft.flysafe.info.FlySafeSeriousWarningInformation
import dji.v5.manager.aircraft.flysafe.info.FlySafeTipInformation
import dji.v5.manager.aircraft.flysafe.info.FlySafeWarningInformation
import dji.v5.manager.aircraft.flysafe.info.FlyZoneInformation
import dji.v5.manager.aircraft.flysafe.info.FlyZoneLicenseInfo
import dji.v5.utils.common.ContextUtil
import dji.v5.utils.common.FileUtils
import dji.v5.utils.common.LogUtils
import java.io.File
import java.text.SimpleDateFormat
import kotlin.math.roundToInt

/**
 * 飛行安全(FlySafe)相關 ViewModel。負責監聽飛安警告、限飛區資訊，以及管理限飛區授權與動態資料庫的同步。
 *
 * @author Hoker
 * @date 2022/8/12
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class FlySafeVM : DJIViewModel() {

    val flySafeWarningInformation = MutableLiveData<FlySafeWarningInformation>()
    val flySafeSeriousWarningInformation = MutableLiveData<FlySafeSeriousWarningInformation>()
    val flySafeReturnToHomeInformation = MutableLiveData<FlySafeReturnToHomeInformation>()
    val flySafeTipInformation = MutableLiveData<FlySafeTipInformation>()
    val flyZoneInformation = MutableLiveData<MutableList<FlyZoneInformation>>()
    val serverFlyZoneLicenseInfo = MutableLiveData<MutableList<FlyZoneLicenseInfo>>()
    val aircraftFlyZoneLicenseInfo = MutableLiveData<MutableList<FlyZoneLicenseInfo>>()

    val importAndSyncState = MutableLiveData<ImportAndSyncState>()
    val dataBaseInfo = MutableLiveData<DataBaseInfo>()
    val dataUpgradeState = MutableLiveData<FlySafeDatabaseState>()

    private val availableTestFlySafeDynamicDatabaseName = "de.geojson"
    private val unAvailableTestFlySafeDynamicDatabaseName = "France.json"

    private val flySafeNotificationListener = object : FlySafeNotificationListener {

        override fun onWarningNotificationUpdate(info: FlySafeWarningInformation) {
            flySafeWarningInformation.postValue(info)
        }

        override fun onSeriousWarningNotificationUpdate(info: FlySafeSeriousWarningInformation) {
            flySafeSeriousWarningInformation.postValue(info)
        }

        override fun onReturnToHomeNotificationUpdate(info: FlySafeReturnToHomeInformation) {
            flySafeReturnToHomeInformation.postValue(info)
        }

        override fun onTipNotificationUpdate(info: FlySafeTipInformation) {
            flySafeTipInformation.postValue(info)
        }

        override fun onSurroundingFlyZonesUpdate(infos: MutableList<FlyZoneInformation>) {
            flyZoneInformation.postValue(infos)
        }
    }

    /** 初始化監聽器：註冊飛安通知監聽與飛安資料庫監聽。 */
    fun initListener() {
        FlyZoneManager.getInstance().addFlySafeNotificationListener(flySafeNotificationListener)
        addFlySafeDatabaseListener()

    }

    override fun onCleared() {
        KeyManager.getInstance().cancelListen(this)
        FlyZoneManager.getInstance().removeFlySafeNotificationListener(flySafeNotificationListener)
        removeFlySafeDatabaseListener()
    }

    fun getAircraftLocation(): LocationCoordinate3D = FlightControllerKey.KeyAircraftLocation3D.create().get(LocationCoordinate3D(0.0, 0.0,0.0))

    /** 取得指定位置周邊的限飛區資訊。 */
    fun getFlyZonesInSurroundingArea(location: LocationCoordinate2D) {
        FlyZoneManager.getInstance().getFlyZonesInSurroundingArea(location, object :
            CommonCallbacks.CompletionCallbackWithParam<MutableList<FlyZoneInformation>> {

            override fun onSuccess(infos: MutableList<FlyZoneInformation>?) {
                toastResult?.postValue(DJIToastResult.success())
                flyZoneInformation.postValue(infos ?: arrayListOf())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    /** 從伺服器下載限飛區授權資訊。 */
    fun downloadFlyZoneLicensesFromServer() {
        FlyZoneManager.getInstance().downloadFlyZoneLicensesFromServer(object :
            CommonCallbacks.CompletionCallbackWithParam<MutableList<FlyZoneLicenseInfo>> {

            override fun onSuccess(infos: MutableList<FlyZoneLicenseInfo>?) {
                toastResult?.postValue(DJIToastResult.success())
                serverFlyZoneLicenseInfo.postValue(infos ?: arrayListOf())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    /** 將限飛區授權資訊推送到飛行器，並於成功後從飛行器拉取最新狀態。 */
    fun pushFlyZoneLicensesToAircraft() {
        FlyZoneManager.getInstance().pushFlyZoneLicensesToAircraft(object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                pullFlyZoneLicensesFromAircraft()
                toastResult?.postValue(DJIToastResult.success())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    /** 從飛行器拉取目前的限飛區授權資訊。 */
    fun pullFlyZoneLicensesFromAircraft() {
        FlyZoneManager.getInstance().pullFlyZoneLicensesFromAircraft(object :
            CommonCallbacks.CompletionCallbackWithParam<MutableList<FlyZoneLicenseInfo>> {

            override fun onSuccess(infos: MutableList<FlyZoneLicenseInfo>?) {
                toastResult?.postValue(DJIToastResult.success())

                aircraftFlyZoneLicenseInfo.postValue(infos ?: arrayListOf())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    /** 刪除飛行器上的限飛區授權資訊。 */
    fun deleteFlyZoneLicensesFromAircraft() {
        FlyZoneManager.getInstance().deleteFlyZoneLicensesFromAircraft(object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                toastResult?.postValue(DJIToastResult.success())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    /** 啟用或停用指定的限飛區授權資訊。 */
    fun setFlyZoneLicensesEnabled(info: FlyZoneLicenseInfo, isEnable: Boolean) {
        FlyZoneManager.getInstance().setFlyZoneLicensesEnabled(info, isEnable, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                pullFlyZoneLicensesFromAircraft()
                toastResult?.postValue(DJIToastResult.success())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    /** 解鎖指定的授權限飛區。 */
    fun unlockAuthorizationFlyZone(flyZoneID: Int) {
        FlyZoneManager.getInstance().unlockAuthorizationFlyZone(flyZoneID, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                toastResult?.postValue(DJIToastResult.success())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    /** 解鎖所有增強警告限飛區。 */
    fun unlockAllEnhancedWarningFlyZone() {
        FlyZoneManager.getInstance().unlockAllEnhancedWarningFlyZone(object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                toastResult?.postValue(DJIToastResult.success())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    /** 將指定檔案名稱的飛安動態資料庫匯入 MSDK（App 與飛行器共用）。 */
    fun pushFlySafeDynamicDatabaseToAircraftAndApp(fileName: String) {
        FlyZoneManager.getInstance().importFlySafeDynamicDatabaseToMSDK(fileName, object :
            CommonCallbacks.CompletionCallbackWithProgress<Double> {
            override fun onProgressUpdate(progress: Double?) {
                importAndSyncState.postValue(ImportAndSyncState(progress!!.toFloat().roundToInt()))
            }

            override fun onSuccess() {
                importAndSyncState.postValue(ImportAndSyncState(100))
            }

            override fun onFailure(error: IDJIError) {
                importAndSyncState.postValue(ImportAndSyncState(-1, error))
            }

        })
    }

    /** 將 MSDK 中的飛安動態資料庫同步推送到飛行器。 */
    fun syncFlySafeMSDKDatabaseToAircraft() {
        FlyZoneManager.getInstance().pushFlySafeDynamicDatabaseToAircraft(object :
            CommonCallbacks.CompletionCallbackWithProgress<Double> {
            override fun onProgressUpdate(progress: Double?) {
                importAndSyncState.postValue(ImportAndSyncState(Math.round(progress!!.toFloat())))
            }

            override fun onSuccess() {
                importAndSyncState.postValue(ImportAndSyncState(100))
            }

            override fun onFailure(error: IDJIError) {
                importAndSyncState.postValue(ImportAndSyncState(-1, error))
            }

        })
    }

    /** 設定飛安動態資料庫的升級模式。 */
    fun setFlySafeDynamicDatabaseUpgradeMode(flySafeDynamicDatabaseUpgradeMode: FlySafeDatabaseUpgradeMode) {
        FlyZoneManager.getInstance().setFlySafeDynamicDatabaseUpgradeMode(flySafeDynamicDatabaseUpgradeMode, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                importAndSyncState.postValue(ImportAndSyncState(100))
            }

            override fun onFailure(error: IDJIError) {
                importAndSyncState.postValue(ImportAndSyncState(-1, error))
            }

        })
    }

    /** 註冊飛安資料庫監聽，用於接收資料庫資訊與升級狀態更新。 */
    fun addFlySafeDatabaseListener() {
        FlyZoneManager.getInstance().addFlySafeDatabaseListener(object : FlySafeDatabaseListener {
            override fun onFlySafeDatabaseInfoUpdate(flySafeDatabaseInfo: FlySafeDatabaseInfo) {
                LogUtils.i("testFly", "dataName :" + flySafeDatabaseInfo.databaseName + " compnent :" + flySafeDatabaseInfo.component)
                dataBaseInfo.postValue(
                    DataBaseInfo(
                        flySafeDatabaseInfo.databaseName,
                        formatCEDBTime(flySafeDatabaseInfo.databaseTimeStamp * 1000),
                        Util.byte2AdaptiveUnitStrDefault(flySafeDatabaseInfo.databaseSize),
                        flySafeDatabaseInfo.component, flySafeDatabaseInfo.flySafeDatabaseUpgradeMode
                    )
                )
            }

            override fun onFlySafeDatabaseStateUpdate(flySafeDatabaseState: FlySafeDatabaseState) {
                dataUpgradeState.postValue(flySafeDatabaseState)
            }

        })
    }

    /** 將 assets 中可用的測試飛安動態資料庫複製到本機並匯入。 */
    fun pushAvailableTestFlySafeDynamicDatabaseToApp() {
        val file = File(ContextUtil.getContext().getExternalFilesDir("/"), availableTestFlySafeDynamicDatabaseName)
        FileUtils.copyAssetsFileIfNeed(ContextUtil.getContext(), "flysafe/$availableTestFlySafeDynamicDatabaseName", file)
        pushFlySafeDynamicDatabaseToAircraftAndApp(file.path)
    }

    /** 將 assets 中不可用的測試飛安動態資料庫複製到本機並匯入（用於測試失敗情境）。 */
    fun pushUnAvailableTestFlySafeDynamicDatabaseToApp() {
        val file = File(ContextUtil.getContext().getExternalFilesDir("/"), unAvailableTestFlySafeDynamicDatabaseName)
        FileUtils.copyAssetsFileIfNeed(ContextUtil.getContext(), "flysafe/$unAvailableTestFlySafeDynamicDatabaseName", file)
        pushFlySafeDynamicDatabaseToAircraftAndApp(file.path)
    }

    private fun formatCEDBTime(timestamp: Long): String {
        val format = SimpleDateFormat("yyyy/MM/dd")
        return format.format(timestamp)
    }

    private fun removeFlySafeDatabaseListener() {
        FlyZoneManager.getInstance().clearAllFlySafeDatabaseListener()
    }

    /** 飛安資料庫的資訊資料模型（名稱、時間、大小、元件與升級模式）。 */
    data class DataBaseInfo(
        var dataBaseName: String,
        var dataBaseTime: String,
        var dataBaseSize: String,
        var component: FlySafeDatabaseComponent,
        var upgradeMode: FlySafeDatabaseUpgradeMode
    )

    /** 匯入與同步的進度狀態資料模型（進度百分比與錯誤資訊）。 */
    data class ImportAndSyncState(
        var importAndSyncProgress: Int,
        var error: IDJIError? = null,
    )
}