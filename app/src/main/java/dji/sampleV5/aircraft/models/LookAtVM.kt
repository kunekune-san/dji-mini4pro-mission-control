package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.data.DJIToastResult
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.camera.TapZoomMode
import dji.sdk.keyvalue.value.camera.ZoomTargetPointInfo
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.flightcontroller.LookAtInfo
import dji.v5.common.error.IDJIError
import dji.v5.common.utils.CallbackUtils
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.createCamera
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.camera.view.PinPoint
import dji.v5.manager.datacenter.camera.view.PinPointInfo
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ClassName : LookAtVM
 * Description : LookAt 相關 ViewModel。提供新增標點、LookAt 注視、點選變焦等功能，並展示標點資訊。
 * Author : daniel.chen
 * CreateDate : 2022/5/7 12:17 下午
 * Copyright : ©2022 DJI All Rights Reserved.
 */
class LookAtVM : DJIViewModel() {
    val pointInfos = MutableLiveData<MutableList<Point>>()
    val currentComponentIndexType = MutableLiveData(ComponentIndexType.UNKNOWN)

    init {
        pointInfos.value = CopyOnWriteArrayList()
    }

    /** 新增一個標點：將 GPS 座標轉換為即時畫面位置後加入標點清單。 */
    fun addNewPinPoint(pointPos: LocationCoordinate3D) {
        pointInfos.value?.add(Point(pointPos, getLiveViewLocationWithGPS(pointPos), currentComponentIndexType.value ?: ComponentIndexType.UNKNOWN))
        pointInfos.postValue(pointInfos.value)
    }

    /** 將 GPS 座標轉換為即時畫面中的位置資訊。 */
    fun getLiveViewLocationWithGPS(pointPos: LocationCoordinate3D): PinPointInfo {
        return MediaDataCenter.getInstance().cameraStreamManager.getLiveViewLocationWithGPS(
            pointPos,
            currentComponentIndexType.value ?: ComponentIndexType.UNKNOWN
        )
    }

    /** 清除所有標點資訊。 */
    fun clearPointInfos() {
        pointInfos.value = CopyOnWriteArrayList()
        pointInfos.postValue(pointInfos.value)
    }

    /** 執行 LookAt 指令，讓飛行器雲台注視指定目標。 */
    fun lookAt(info: LookAtInfo) {
        FlightControllerKey.KeyLookAt.create().action(info, {
            toastResult?.postValue(DJIToastResult.success())
        }, { error: IDJIError ->
            toastResult?.postValue(DJIToastResult.failed(error.toString()))
        })
    }

    /** 在即時畫面的指定位置觸發點選變焦。 */
    fun startTapZoomPoint(x: Double, y: Double) {
        CameraKey.KeyTapZoomAtTarget.createCamera(currentComponentIndexType.value!!, CameraLensType.CAMERA_LENS_ZOOM).action(ZoomTargetPointInfo(x, y, false, TapZoomMode.UNKNOWN), {
            toastResult?.postValue(DJIToastResult.success())
        }, { error: IDJIError ->
            toastResult?.postValue(DJIToastResult.failed(error.toString()))
        })
    }

    /** 標點資料模型：包含 GPS 座標、即時畫面座標與對應的相機索引。 */
    class Point(
        var pos: LocationCoordinate3D = LocationCoordinate3D(),
        var pinPointInfo: PinPointInfo = PinPointInfo(),
        var componentIndexType: ComponentIndexType = ComponentIndexType.UNKNOWN
    ) {
        override fun toString(): String {
            val sb = StringBuilder()
            sb.append("<-------------------Point------------------->\n")
            sb.append("Lng:").append(pos.longitude).append(" ")
            sb.append("Lat:").append(pos.latitude).append(" ")
            sb.append("Alt:").append(pos.altitude).append("\n")
            sb.append("Result:").append(pinPointInfo.result).append("\n")
            for (i in 0 until pinPointInfo.pinPoints.size) {
                val vector2: PinPoint = pinPointInfo.pinPoints[i]
                sb.append("PinPoint index:$i ")
                sb.append("X:").append(String.format("%.2f", vector2.x)).append(" ")
                sb.append("Y:").append(String.format("%.2f", vector2.y)).append(" \n")
            }
            sb.append("PointDirection:").append(pinPointInfo.pointDirection).append("\n")
            sb.append("ComponentIndexType:").append(componentIndexType)
            return sb.toString()
        }
    }

}