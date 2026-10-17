package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RadarKey
import dji.v5.common.callback.CommonCallbacks
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.perception.*
import dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType
import dji.v5.manager.aircraft.perception.data.ObstacleData
import dji.v5.manager.aircraft.perception.data.PerceptionDirection
import dji.v5.manager.aircraft.perception.data.PerceptionInfo
import dji.v5.manager.aircraft.perception.radar.RadarInformation
import dji.v5.utils.common.LogUtils

/**
 * 感知模組的 ViewModel：管理感知資訊、雷達資訊與障礙物資料監聽，並提供各項避障設定。
 *
 * @author: Byte.Cai
 *  date : 2022/6/7
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class PerceptionVM : DJIViewModel() {
    private val TAG = LogUtils.getTag("PerceptionVM")
    private val perceptionManager = PerceptionManager.getInstance()
    val perceptionInfo = MutableLiveData<PerceptionInfo>()
    val radarInformation = MutableLiveData<RadarInformation>()
    val obstacleData = MutableLiveData<ObstacleData>()
    val obstacleDataForRadar = MutableLiveData<ObstacleData>()
    val radarConnect = MutableLiveData<Boolean>()


    /**
     * 加入感知資訊、障礙物與雷達資料監聽器，並監聽雷達連線狀態。
     */
    fun addPerceptionInfoListener() {
        perceptionManager.addPerceptionInformationListener {
            perceptionInfo.postValue(it)
        }
        perceptionManager.addObstacleDataListener {
            obstacleData.postValue(it)
        }
        perceptionManager.radarManager.addObstacleDataListener {
            obstacleDataForRadar.postValue(it)
        }
        perceptionManager.radarManager.addRadarInformationListener {
            radarInformation.postValue(it)
        }

        KeyManager.getInstance().listen(
            KeyTools.createKey(
                RadarKey.KeyConnection
            ), this
        ) { _, newValue ->
            // 雷達拔掉時會返回 null 值
            if (newValue == null) {
                radarConnect.postValue(false)
            } else {
                radarConnect.postValue(true)
            }
        }
    }

    override fun onCleared() {
        KeyManager.getInstance().cancelListen(this)
    }

    /**
     * 設定指定方向的避障功能是否啟用。
     */
    fun setObstacleAvoidanceEnabled(
        isEnabled: Boolean,
        direction: PerceptionDirection,
        callback: CommonCallbacks.CompletionCallback
    ) {
        perceptionManager.setObstacleAvoidanceEnabled(isEnabled, direction, callback)
    }

    /**
     * 設定雷達避障功能是否啟用。
     */
    fun setRadarObstacleAvoidanceEnabled(
        isEnabled: Boolean,
        direction: PerceptionDirection,
        callback: CommonCallbacks.CompletionCallback
    ) {
        perceptionManager.radarManager.setObstacleAvoidanceEnabled(isEnabled, direction, callback)
    }

    /**
     * 設定避障類型。
     */
    fun setObstacleAvoidanceType(
        type: ObstacleAvoidanceType,
        callback: CommonCallbacks.CompletionCallback
    ) {
        perceptionManager.setObstacleAvoidanceType(type, callback)
    }

    /**
     * 設定指定方向的避障告警距離。
     */
    fun setObstacleAvoidanceWarningDistance(
        distance: Double,
        direction: PerceptionDirection,
        callback: CommonCallbacks.CompletionCallback
    ) {
        perceptionManager.setObstacleAvoidanceWarningDistance(distance, direction, callback)
    }


    /**
     * 設定指定方向的避障煞車距離。
     */
    fun setObstacleAvoidanceBrakingDistance(
        distance: Double,
        direction: PerceptionDirection,
        callback: CommonCallbacks.CompletionCallback
    ) {
        perceptionManager.setObstacleAvoidanceBrakingDistance(distance, direction, callback)
    }


    /**
     * 設定視覺定位功能是否啟用。
     */
    fun setVisionPositioningEnabled(
        isEnabled: Boolean,
        callback: CommonCallbacks.CompletionCallback
    ) {
        perceptionManager.setVisionPositioningEnabled(isEnabled, callback)
    }


    /**
     * 設定精準降落功能是否啟用。
     */
    fun setPrecisionLandingEnabled(
        isEnabled: Boolean,
        callback: CommonCallbacks.CompletionCallback
    ) {
        perceptionManager.setPrecisionLandingEnabled(isEnabled, callback)
    }


}