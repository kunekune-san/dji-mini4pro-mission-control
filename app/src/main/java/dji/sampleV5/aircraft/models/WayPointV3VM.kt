package dji.sampleV5.aircraft.models

import android.R
import android.content.Context
import android.widget.ArrayAdapter
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dji.sampleV5.aircraft.data.FlightControlState
import dji.sampleV5.aircraft.data.MissionUploadStateInfo
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.wpmz.value.mission.WaylineExitOnRCLostAction
import dji.sdk.wpmz.value.mission.WaylineFinishedAction
import dji.sdk.keyvalue.value.flightcontroller.FlightMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.utils.RxUtil
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.waypoint3.WaylineExecutingInfoListener
import dji.v5.manager.aircraft.waypoint3.WaypointActionListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionExecuteStateListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import dji.v5.manager.aircraft.waypoint3.model.BreakPointInfo
import dji.v5.manager.areacode.AreaCode
import dji.v5.manager.areacode.AreaCodeManager
import dji.v5.utils.common.ContextUtil
import dji.v5.utils.common.DjiSharedPreferencesManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.disposables.Disposable


/**
 * @author feel.feng
 * @time 2022/02/27 10:10 上午
 * @description: 航點飛行（Waypoint V3）ViewModel。提供 KMZ/WPMZ 航線推送、任務開始/暫停/繼續/停止與飛行狀態監聽等功能。
 */
class WayPointV3VM : DJIViewModel() {
    val RadToDeg = 57.295779513082321
    val missionUploadState = MutableLiveData<MissionUploadStateInfo>()

    val flightControlState = MutableLiveData<FlightControlState>()
    var compassHeadKey: DJIKey<Double> = FlightControllerKey.KeyCompassHeading.create()
    var altitudeKey: DJIKey<Double> = FlightControllerKey.KeyAltitude.create()
    var flightSpeed: DJIKey<Velocity3D> = FlightControllerKey.KeyAircraftVelocity.create()
    var flightMode : DJIKey<FlightMode> = FlightControllerKey.KeyFlightMode.create()


    /** 將 KMZ/WPMZ 航線檔案推送到飛行器。 */
    fun pushKMZFileToAircraft(missionPath: String) {
        WaypointMissionManager.getInstance().pushKMZFileToAircraft(missionPath, object :
            CommonCallbacks.CompletionCallbackWithProgress<Double> {
            override fun onProgressUpdate(progress: Double) {
                missionUploadState.value = MissionUploadStateInfo(updateProgress = progress)
                refreshMissionState()
            }

            override fun onSuccess() {
                missionUploadState.value = MissionUploadStateInfo(tips = "Mission Upload Success")
                refreshMissionState()
            }

            override fun onFailure(error: IDJIError) {
                missionUploadState.value = MissionUploadStateInfo(error = error)
                refreshMissionState()
            }

        })
    }

    private fun refreshMissionState() {
        missionUploadState.postValue(missionUploadState.value)
    }

    /** 依指定的航線 ID 清單開始任務。 */
    fun startMission(
        missionId: String,
        waylineIDs: List<Int>,
        callback: CommonCallbacks.CompletionCallback
    ) {
        WaypointMissionManager.getInstance().startMission(missionId, waylineIDs, callback)
    }

    /** 從斷點資訊開始任務。 */
    fun startMission(
        missionId: String,
        breakPointInfo: BreakPointInfo,
        callback: CommonCallbacks.CompletionCallback
    ) {
        WaypointMissionManager.getInstance().startMission(missionId, breakPointInfo, callback)
    }

    /** 暫停目前任務。 */
    fun pauseMission(callback: CommonCallbacks.CompletionCallback) {
        WaypointMissionManager.getInstance().pauseMission(callback)
    }

    /** 繼續目前任務。 */
    fun resumeMission(callback: CommonCallbacks.CompletionCallback) {
        WaypointMissionManager.getInstance().resumeMission(callback)
    }

    /** 從斷點資訊繼續任務。 */
    fun resumeMission(
        breakPointInfo: BreakPointInfo,
        callback: CommonCallbacks.CompletionCallback
    ) {
        WaypointMissionManager.getInstance().resumeMission(breakPointInfo, callback)
    }

    /** 停止指定任務。 */
    fun stopMission(missionID: String, callback: CommonCallbacks.CompletionCallback) {
        WaypointMissionManager.getInstance().stopMission(missionID, callback)
    }

    /** 加入任務執行狀態監聽器。 */
    fun addMissionStateListener(listener: WaypointMissionExecuteStateListener) {
        WaypointMissionManager.getInstance().addWaypointMissionExecuteStateListener(listener)
    }

    /** 移除任務執行狀態監聽器。 */
    fun removeMissionStateListener(listener: WaypointMissionExecuteStateListener) {
        WaypointMissionManager.getInstance().removeWaypointMissionExecuteStateListener(listener)
    }

    /** 移除所有任務執行狀態監聽器。 */
    fun removeAllMissionStateListener() {
        WaypointMissionManager.getInstance().clearAllWaypointMissionExecuteStateListener()
    }

    /** 加入航線執行資訊監聽器。 */
    fun addWaylineExecutingInfoListener(listener: WaylineExecutingInfoListener) {
        WaypointMissionManager.getInstance().addWaylineExecutingInfoListener(listener)
    }

    /** 移除航線執行資訊監聽器。 */
    fun removeWaylineExecutingInfoListener(listener: WaylineExecutingInfoListener) {
        WaypointMissionManager.getInstance().removeWaylineExecutingInfoListener(listener)
    }

    /** 移除所有航線執行資訊監聽器。 */
    fun clearAllWaylineExecutingInfoListener() {
        WaypointMissionManager.getInstance().clearAllWaylineExecutingInfoListener()
    }

    /** 加入航點動作監聽器。 */
    fun addWaypointActionListener(listener: WaypointActionListener) {
        WaypointMissionManager.getInstance().addWaypointActionListener(listener)
    }

    /** 移除所有航點動作監聽器。 */
    fun clearAllWaypointActionListener() {
        WaypointMissionManager.getInstance().clearAllWaypointActionListener()
    }

    /** 訂閱飛行控制狀態（Home 位置、飛行器位置、高度、航向與速度）並計算距離。 */
    fun listenFlightControlState(): Disposable {
        return Flowable.combineLatest(
            RxUtil
                .addListener(KeyTools.createKey(FlightControllerKey.KeyHomeLocation), this)
                .observeOn(AndroidSchedulers.mainThread()),
            RxUtil
                .addListener(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation), this)
                .observeOn(AndroidSchedulers.mainThread())
        ) { homelocation: LocationCoordinate2D?, aircraftLocation: LocationCoordinate2D? ->
            if (homelocation == null || aircraftLocation == null) {
                return@combineLatest
            }
            val height = getHeight()
            val distance = calculateDistance(
                homelocation.latitude,
                homelocation.longitude,
                aircraftLocation.latitude,
                aircraftLocation.longitude
            )
            val heading = getHeading()
            val speed3D = getSpeed()
            val x = speed3D.getX()
            val y = speed3D.getY()
            val speed = Math.sqrt((x * x + y * y))
            flightControlState.value = FlightControlState(
                aircraftLocation.longitude,
                aircraftLocation.latitude,
                distance = distance,
                height = height,
                head = heading,
                speed = speed,
                homeLocation = homelocation
            )
            refreshFlightControlState()
        }.subscribe()
    }

    /** 檢查經緯度座標是否在有效範圍內。 */
    fun isLocationValid(latitude: Double, longitude: Double): Boolean {
        return latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 && latitude != 0.0 && longitude != 0.0
    }

    /** 取消訂閱飛行控制狀態。 */
    fun cancelListenFlightControlState() {
        KeyManager.getInstance().cancelListen(this)
    }

    /** 取得航線檔案中可用的航線 ID 清單。 */
    fun getAvailableWaylineIDs(missionPath: String): List<Int> {
        return WaypointMissionManager.getInstance().getAvailableWaylineIDs(missionPath)
    }

    private fun refreshFlightControlState() {
        flightControlState.postValue(flightControlState.value)
    }


    /** 以球面距離公式計算兩個經緯度座標之間的距離（公尺）。 */
    fun calculateDistance(
        latA: Double,
        lngA: Double,
        latB: Double,
        lngB: Double,
    ): Double {
        val earthR = 6371000.0
        val x =
            Math.cos(latA * Math.PI / 180) * Math.cos(
                latB * Math.PI / 180
            ) * Math.cos((lngA - lngB) * Math.PI / 180)
        val y =
            Math.sin(latA * Math.PI / 180) * Math.sin(
                latB * Math.PI / 180
            )
        var s = x + y
        if (s > 1) {
            s = 1.0
        }
        if (s < -1) {
            s = -1.0
        }
        val alpha = Math.acos(s)
        return alpha * earthR
    }

    private fun getHeading() = (compassHeadKey.get(0.0)).toFloat()

    private fun getHeight(): Double = (altitudeKey.get(0.0))

    private fun getSpeed(): Velocity3D = flightSpeed.get(Velocity3D(0.0, 0.0, 0.0))

    fun getFlightMode() :FlightMode = flightMode.get(FlightMode.UNKNOWN)

    fun isInMainlandChina(): Boolean {
        return AreaCodeManager.getInstance().areaCode.areaCodeEnum == AreaCode.CHINA
    }

    fun isMacau(): Boolean {
        return AreaCodeManager.getInstance().areaCode.areaCodeEnum == AreaCode.MACAU
    }

    fun isHongKong(): Boolean {
        return AreaCodeManager.getInstance().areaCode.areaCodeEnum == AreaCode.HONG_KONG

    }

    fun getMapType(context: Context?): Int {
        return DjiSharedPreferencesManager.getInt(context, "map_selection", 0)
    }

    fun saveMapType(context: Context?, pos: Int) {
        DjiSharedPreferencesManager.putInt(context, "map_selection", pos)
    }

    /** 取得地圖選擇用的下拉選單 Adapter（依是否支援 Google Maps 決定選項）。 */
    fun getMapSpinnerAdapter(): ArrayAdapter<String> {
        return if (isGoogleMapsSupported()) {
            ArrayAdapter<String>(
                ContextUtil.getContext(),
                R.layout.simple_spinner_dropdown_item,
                ContextUtil.getContext().resources.getStringArray(
                    dji.sampleV5.aircraft.R.array.maps_array_all
                )
            )
        } else {
            ArrayAdapter<String>(
                ContextUtil.getContext(),
                R.layout.simple_spinner_dropdown_item,
                ContextUtil.getContext().resources.getStringArray(
                    dji.sampleV5.aircraft.R.array.maps_array
                )
            )
        }
    }

    /** 檢查裝置是否支援 Google Maps（Google Play 服務是否可用）。 */
    fun isGoogleMapsSupported(): Boolean {
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val resultCode =
            googleApiAvailability.isGooglePlayServicesAvailable(ContextUtil.getContext())
        return resultCode == ConnectionResult.SUCCESS
    }

}
/** 任務全域設定資料模型（速度、結束動作與失聯動作）。 */
data class MissionGlobalModel( var globalSpeed:Double = 5.0,
                               var finishAction: WaylineFinishedAction = WaylineFinishedAction.GO_HOME,
                                var lostAction: WaylineExitOnRCLostAction = WaylineExitOnRCLostAction.GO_BACK) {
    companion object {
        fun transStringToFinishAction(textStr: String): WaylineFinishedAction {
            WaylineFinishedAction.values().forEach {
                if (it.name == textStr) {
                    return it
                }
            }
            return WaylineFinishedAction.UNKNOWN
        }

        fun transStringToLostAction(textStr: String): WaylineExitOnRCLostAction {
            WaylineExitOnRCLostAction.values().forEach {
                if (it.name == textStr) {
                    return it
                }
            }
            return WaylineExitOnRCLostAction.UNKNOWN
        }

        fun transFinishActionToIndex(finishAction: WaylineFinishedAction): Int {
            return finishAction.value()
        }

    }

}