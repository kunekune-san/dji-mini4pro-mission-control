package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.flightcontroller.*
import dji.v5.common.callback.CommonCallbacks
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener

/**
 * VirtualStick（虛擬搖桿）ViewModel。控制虛擬搖桿的啟用/停用、速度等級與進階模式，並監聽搖桿狀態與 RC 搖桿輸入。
 *
 * @author Hoker
 * @date 2021/6/18
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
class VirtualStickVM : DJIViewModel() {

    val currentSpeedLevel = MutableLiveData(0.0)
    var useRcStick = MutableLiveData(false)
    val currentVirtualStickStateInfo = MutableLiveData(VirtualStickStateInfo())

    val virtualStickAdvancedParam = MutableLiveData(VirtualStickFlightControlParam()).apply {
        value?.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
        value?.verticalControlMode = VerticalControlMode.VELOCITY
        value?.yawControlMode = YawControlMode.ANGULAR_VELOCITY
        value?.rollPitchControlMode = RollPitchControlMode.ANGLE
    }

    // RC 搖桿值
    var stickValue = MutableLiveData(RCStickValue(0, 0, 0, 0))

    init {
        currentSpeedLevel.value = VirtualStickManager.getInstance().speedLevel
        VirtualStickManager.getInstance().setVirtualStickStateListener(object :
            VirtualStickStateListener {
            override fun onVirtualStickStateUpdate(stickState: VirtualStickState) {
                currentVirtualStickStateInfo.postValue(currentVirtualStickStateInfo.value?.apply {
                    this.state = stickState
                })
            }

            override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
                currentVirtualStickStateInfo.postValue(currentVirtualStickStateInfo.value?.apply {
                    this.reason = reason
                })
            }
        })
    }

    /** 啟用虛擬搖桿。 */
    fun enableVirtualStick(callback: CommonCallbacks.CompletionCallback) {
        VirtualStickManager.getInstance().enableVirtualStick(callback)
    }

    /** 停用虛擬搖桿。 */
    fun disableVirtualStick(callback: CommonCallbacks.CompletionCallback) {
        VirtualStickManager.getInstance().disableVirtualStick(callback)
    }

    /** 設定虛擬搖桿的速度等級。 */
    fun setSpeedLevel(speedLevel: Double) {
        VirtualStickManager.getInstance().speedLevel = speedLevel
        currentSpeedLevel.value = speedLevel
    }

    /** 設定左搖桿的水平與垂直位置。 */
    fun setLeftPosition(horizontal: Int, vertical: Int) {
        VirtualStickManager.getInstance().leftStick.horizontalPosition = horizontal
        VirtualStickManager.getInstance().leftStick.verticalPosition = vertical
    }

    /** 設定右搖桿的水平與垂直位置。 */
    fun setRightPosition(horizontal: Int, vertical: Int) {
        VirtualStickManager.getInstance().rightStick.horizontalPosition = horizontal
        VirtualStickManager.getInstance().rightStick.verticalPosition = vertical
    }

    /** 傳送進階虛擬搖桿控制參數。 */
    fun sendVirtualStickAdvancedParam(param: VirtualStickFlightControlParam) {
        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
    }

    /** 停用虛擬搖桿進階模式。 */
    fun disableVirtualStickAdvancedMode() {
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
    }

    /** 啟用虛擬搖桿進階模式。 */
    fun enableVirtualStickAdvancedMode() {
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
    }

    /** 訂閱 RC 遙控器搖桿的輸入值，並在啟用時同步到虛擬搖桿。 */
    fun listenRCStick() {
        RemoteControllerKey.KeyStickLeftHorizontal.create().listen(this) {
            it?.let {
                stickValue.value?.leftHorizontal = it
            }
            tryUpdateVirtualStickByRc()
        }
        RemoteControllerKey.KeyStickLeftVertical.create().listen(this) {
            it?.let {
                stickValue.value?.leftVertical = it
            }
            tryUpdateVirtualStickByRc()
        }
        RemoteControllerKey.KeyStickRightHorizontal.create().listen(this) {
            it?.let {
                stickValue.value?.rightHorizontal = it
            }
            tryUpdateVirtualStickByRc()
        }
        RemoteControllerKey.KeyStickRightVertical.create().listen(this) {
            it?.let {
                stickValue.value?.rightVertical = it
            }
            tryUpdateVirtualStickByRc()
        }
    }

    private fun tryUpdateVirtualStickByRc() {
        stickValue.postValue(stickValue.value)
        if (useRcStick.value == true) {
            stickValue.value?.apply {
                setLeftPosition(leftHorizontal, leftVertical)
                setRightPosition(rightHorizontal, rightVertical)
            }
        }
    }

    override fun onCleared() {
        KeyManager.getInstance().cancelListen(this)
        VirtualStickManager.getInstance().clearAllVirtualStickStateListener()
    }

    /** 虛擬搖桿狀態資訊資料模型（狀態與控制權變更原因）。 */
    data class VirtualStickStateInfo(
        var state: VirtualStickState = VirtualStickState(false, FlightControlAuthority.UNKNOWN, false),
        var reason: FlightControlAuthorityChangeReason = FlightControlAuthorityChangeReason.UNKNOWN
    )

    /** RC 搖桿值資料模型（左右搖桿的水平與垂直位置）。 */
    data class RCStickValue(
        var leftHorizontal: Int, var leftVertical:
        Int, var rightHorizontal: Int, var rightVertical: Int
    ) {
        override fun toString(): String {
            return "leftHorizontal=$leftHorizontal,leftVertical=$leftVertical,\n" +
                    "rightHorizontal=$rightHorizontal,rightVertical=$rightVertical"
        }
    }
}