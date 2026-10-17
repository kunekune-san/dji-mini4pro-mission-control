package dji.sampleV5.aircraft.mission

import android.util.Log
import dji.sampleV5.aircraft.util.AppLog
import dji.sampleV5.aircraft.config.AppConfig
import dji.sampleV5.aircraft.dji.CameraManager
import dji.sampleV5.aircraft.dji.FlightManager
import dji.sampleV5.aircraft.dji.GimbalManager
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * A 模式：原地 360° yaw 旋轉錄影。
 *
 * 從 OrbitController.kt 提取，降低單檔複雜度。
 */
internal object OrbitModeA {

    private const val TAG = OrbitHelpers.TAG

    /** A 模式：原地 360° yaw 旋轉錄影。
     *  @param targetAlt 目標操作高度（公尺），錄影前會先調整至此高度
     *  @param orbitSpeedMultiplier 速度倍率（1.0=標準 12°/s 30s/圈, 2.0=快 24°/s 15s/圈），上限 2.0 */
    suspend fun execute(
        gimbalPitch: Double,
        param: VirtualStickFlightControlParam,
        targetAlt: Double = 0.0,
        orbitSpeedMultiplier: Double = 1.0
    ) {
        val effectiveMultiplier = orbitSpeedMultiplier.coerceIn(AppConfig.ORBIT_SPEED_MIN, AppConfig.ORBIT_SPEED_MAX_A)
        val yawSpeed = (AppConfig.RECORDING_YAW_SPEED_DEG_PER_SEC.toDouble() * effectiveMultiplier)
            .coerceAtMost(AppConfig.YAW_SPEED_DEG_PER_SEC.toDouble())
        MissionStateMachine.setState(MissionStateMachine.MissionState.RECORDING_360)
        AppLog.d(TAG, "Starting 360 rotation: gimbal=${gimbalPitch}°, targetAlt=${"%.1f".format(targetAlt)}m, speed=${effectiveMultiplier}x, yaw=${"%.1f".format(yawSpeed)}°/s")

        if (gimbalPitch != 0.0) {
            AppLog.d(TAG, "Setting gimbal to ${gimbalPitch}° for 360 recording")
            try {
                GimbalManager.setPitchAngle(gimbalPitch)
                GimbalManager.waitForPitchReady(gimbalPitch)
            } catch (e: Exception) {
                Log.w(TAG, "Gimbal set failed, retrying once: ${e.message}")
                delay(500)
                GimbalManager.setPitchAngle(gimbalPitch)
                GimbalManager.waitForPitchReady(gimbalPitch)
            }
            delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
        }

        // 高度調整：A 模式在原地調整，無圓周起點碰撞風險
        if (targetAlt > 0.0) {
            val currentAltBeforeAdjust = FlightManager.getCurrentAltitude() ?: targetAlt
            if (currentAltBeforeAdjust > targetAlt + 0.5) {
                MissionStateMachine.setState(MissionStateMachine.MissionState.DESCENDING_TO_ORBIT_ALT)
                AppLog.d(TAG, "360: descending from ${"%.1f".format(currentAltBeforeAdjust)}m to ${"%.1f".format(targetAlt)}m")
                val descStart = System.currentTimeMillis()
                while (coroutineContext.isActive) {
                    val alt = FlightManager.getCurrentAltitude() ?: 0.0
                    if (alt <= targetAlt + 0.3) break
                    if (System.currentTimeMillis() - descStart > AppConfig.CLIMB_TIMEOUT_MS) {
                        Log.w(TAG, "360 descent timeout, proceeding at current altitude")
                        break
                    }
                    param.pitch = 0.0; param.roll = 0.0; param.yaw = 0.0
                    param.verticalThrottle = -AppConfig.CLIMB_THROTTLE.toDouble()
                    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                    delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
                }
                VirtualStickController.sendZeroCommand(param)
                delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
            } else if (currentAltBeforeAdjust < targetAlt - 0.5) {
                MissionStateMachine.setState(MissionStateMachine.MissionState.DESCENDING_TO_ORBIT_ALT)
                AppLog.d(TAG, "360: climbing from ${"%.1f".format(currentAltBeforeAdjust)}m to ${"%.1f".format(targetAlt)}m")
                val climbStart = System.currentTimeMillis()
                while (coroutineContext.isActive) {
                    val alt = FlightManager.getCurrentAltitude() ?: 0.0
                    if (alt >= targetAlt - 0.3) break
                    if (System.currentTimeMillis() - climbStart > AppConfig.CLIMB_TIMEOUT_MS) {
                        Log.w(TAG, "360 climb timeout, proceeding at current altitude")
                        break
                    }
                    param.pitch = 0.0; param.roll = 0.0; param.yaw = 0.0
                    param.verticalThrottle = AppConfig.CLIMB_THROTTLE.toDouble()
                    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                    delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
                }
                VirtualStickController.sendZeroCommand(param)
                delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
            }
            MissionStateMachine.setState(MissionStateMachine.MissionState.RECORDING_360)
        }

        CameraManager.setCameraMode(false)
        delay(AppConfig.VS_ZERO_SEND_DELAY_MS)

        AppLog.d(TAG, "Triggering autofocus before 360 recording")
        CameraManager.triggerAutoFocus()
        delay(AppConfig.CAMERA_FOCUS_WAIT_MS)

        suspendCancellableCoroutine<Unit> { cont ->
            CameraManager.startVideoRecord(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.d(TAG, "Recording started")
                    if (cont.isActive) cont.resume(Unit) {}
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Start recording failed: $error")
                    if (cont.isActive) cont.resumeWith(Result.failure(Exception("Start recording failed: $error")))
                }
            })
        }

        var totalYaw = 0.0
        var lastYaw = FlightManager.getCurrentYaw()
        val holdAlt = FlightManager.getCurrentAltitude() ?: 0.0
        val yawStartTime360 = System.currentTimeMillis()
        while (coroutineContext.isActive && totalYaw < AppConfig.YAW_ROTATION_TARGET_DEG) {
            if (System.currentTimeMillis() - yawStartTime360 > AppConfig.YAW_ROTATION_TIMEOUT_MS) {
                Log.e(TAG, "RECORDING_360: Yaw rotation timeout! totalYaw=$totalYaw, forcing exit")
                break
            }
            val currentAlt = FlightManager.getCurrentAltitude() ?: holdAlt
            val altError = holdAlt - currentAlt
            val altCorrection = (altError * 2.0).coerceIn(-1.0, 1.0)
            param.yaw = yawSpeed
            param.pitch = 0.0; param.roll = 0.0; param.verticalThrottle = altCorrection
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
            delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)

            val currentYaw = FlightManager.getCurrentYaw()
            var delta = currentYaw - lastYaw
            if (delta > 180) delta -= 360
            if (delta < -180) delta += 360
            totalYaw += abs(delta)
            lastYaw = currentYaw
            AppLog.d(TAG, "360Yaw loop: current=$currentYaw, delta=${"%.2f".format(delta)}, total=${"%.1f".format(totalYaw)}")
        }

        VirtualStickController.sendZeroCommand(param)
        delay(AppConfig.VS_ZERO_SEND_DELAY_MS)

        suspendCancellableCoroutine<Unit> { cont ->
            CameraManager.stopVideoRecord(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.d(TAG, "360 recording stopped")
                    if (cont.isActive) cont.resume(Unit) {}
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Stop recording failed: $error")
                    if (cont.isActive) cont.resumeWith(Result.failure(Exception("Stop recording failed: $error")))
                }
            })
        }
        AppLog.d(TAG, "360 recording complete")

        GimbalManager.setPitchAngle(0.0)
        GimbalManager.waitForPitchReady(0.0)
    }
}
