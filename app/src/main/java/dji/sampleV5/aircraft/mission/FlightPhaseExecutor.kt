package dji.sampleV5.aircraft.mission

import android.util.Log
import dji.sampleV5.aircraft.util.AppLog
import dji.sampleV5.aircraft.config.AppConfig
import dji.sampleV5.aircraft.dji.FlightManager
import dji.sampleV5.aircraft.util.GeoMath
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 飛行階段執行：起飛、爬升、下降、飛行、旋轉、電量預估 */
internal object FlightPhaseExecutor {

    private const val TAG = "FlightPhase"

    /** 執行自動起飛流程（含重試 + 高度穩定等待 + VS 啟用） */
    suspend fun performTakeoff(maxRetries: Int = 3) {
        var takeoffSuccess = false
        for (attempt in 0 until maxRetries) {
            if (attempt > 0) {
                AppLog.d(TAG, "Takeoff retry attempt ${attempt + 1}, waiting 3s...")
                delay(AppConfig.DJI_STABILIZE_DELAY_MS)
            }
            suspendCancellableCoroutine<Unit> { cont ->
                FlightManager.takeOff(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(value: EmptyMsg) {
                        AppLog.d(TAG, "Takeoff command accepted")
                        takeoffSuccess = true
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                    override fun onFailure(error: IDJIError) {
                        Log.e(TAG, "Takeoff attempt ${attempt + 1} failed: $error")
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                })
            }
            if (takeoffSuccess) break
        }
        if (!takeoffSuccess) throw Exception("Takeoff failed after $maxRetries attempts")

        var lastAlt = 0.0
        var stableCount = 0
        val takeoffWaitStart = System.currentTimeMillis()
        while (coroutineContext.isActive) {
            val alt = FlightManager.getCurrentAltitude() ?: 0.0
            if (alt > AppConfig.TAKEOFF_ALTITUDE_STABLE_THRESHOLD_M && abs(alt - lastAlt) < AppConfig.TAKEOFF_ALTITUDE_STABILITY_TOLERANCE_M) {
                stableCount++
                if (stableCount >= AppConfig.TAKEOFF_STABLE_READINGS_REQUIRED) break
            } else {
                stableCount = 0
            }
            lastAlt = alt
            if (System.currentTimeMillis() - takeoffWaitStart > AppConfig.TAKEOFF_TIMEOUT_MS) {
                throw Exception("Auto-takeoff timeout (15s)")
            }
            delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
        }
        if (!VirtualStickController.enableWithRetry()) {
            throw Exception("Cannot enable VirtualStick for climb")
        }
        delay(AppConfig.DJI_STABILIZE_DELAY_MS)
    }

    /** 爬升到指定高度（含減速區，避免慣性衝高） */
    suspend fun climbToAltitude(targetAlt: Double) {
        val climbParam = VirtualStickParams.groundVelocityParams()
        val climbStart = System.currentTimeMillis()
        while (coroutineContext.isActive) {
            if (!VirtualStickController.shouldSendCommand()) break
            val alt = FlightManager.getCurrentAltitude() ?: 0.0
            if (alt >= targetAlt) break
            if (System.currentTimeMillis() - climbStart > AppConfig.CLIMB_TIMEOUT_MS) {
                throw Exception("Climb timeout (30s)")
            }
            climbParam.pitch = 0.0; climbParam.roll = 0.0; climbParam.yaw = 0.0
            // 距離目標 3m 以內時減速，避免慣性衝高
            val remaining = targetAlt - alt
            val throttle = if (remaining < 3.0) {
                max(remaining / 3.0, 0.3) * AppConfig.CLIMB_THROTTLE
            } else {
                AppConfig.CLIMB_THROTTLE.toDouble()
            }
            climbParam.verticalThrottle = throttle
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(climbParam)
            delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
        }
        VirtualStickController.sendZeroCommand(climbParam)
        delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
    }

    /** 下降到指定高度（含減速區，避免慣性衝低） */
    suspend fun descendToAltitude(targetAlt: Double, param: VirtualStickFlightControlParam) {
        val descStart = System.currentTimeMillis()
        while (coroutineContext.isActive) {
            if (!VirtualStickController.shouldSendCommand()) break
            val alt = FlightManager.getCurrentAltitude() ?: 0.0
            if (alt <= targetAlt + AppConfig.ALTITUDE_DESCENT_TOLERANCE_M) break
            if (System.currentTimeMillis() - descStart > AppConfig.CLIMB_TIMEOUT_MS) {
                Log.w(TAG, "Descent timeout, proceeding at current altitude")
                break
            }
            param.pitch = 0.0; param.roll = 0.0; param.yaw = 0.0
            // 距離目標 3m 以內時減速，避免慣性衝低
            val remaining = alt - targetAlt
            val throttle = if (remaining < 3.0) {
                max(remaining / 3.0, 0.3) * AppConfig.CLIMB_THROTTLE
            } else {
                AppConfig.CLIMB_THROTTLE.toDouble()
            }
            param.verticalThrottle = -throttle
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
            delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
        }
        VirtualStickController.sendZeroCommand(param)
        delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
    }

    /** VirtualStick 飛往目標座標 */
    suspend fun flyToTarget(targetLat: Double, targetLng: Double, targetAlt: Double) {
        MissionStateMachine.setState(MissionStateMachine.MissionState.FLYING_TO_TARGET)
        val param = VirtualStickParams.groundVelocityParams()
        val flyStartTime = System.currentTimeMillis()
        while (coroutineContext.isActive) {
            if (!VirtualStickController.shouldSendCommand()) break
            if (System.currentTimeMillis() - flyStartTime > AppConfig.FLY_TO_TARGET_TIMEOUT_MS) {
                throw Exception("Fly to target timeout (${AppConfig.FLY_TO_TARGET_TIMEOUT_MS / 1000}s)")
            }
            // GPS 訊號檢查：訊號過弱時暫停飛行改為懸停
            val gpsLevel = FlightManager.gpsSignalLevel.value
            if (gpsLevel != null && gpsLevel < 3) {
                Log.w(TAG, "GPS signal weak (level=$gpsLevel), hovering in place")
                VirtualStickController.sendZeroCommand(param)
                delay(1000)
                continue
            }
            val current = FlightManager.getFilteredLocation()
            if (current == null) { delay(AppConfig.VS_DISABLE_SETTLE_DELAY_MS); continue }
            val distance = GeoMath.haversine(current.latitude, current.longitude, targetLat, targetLng)
            if (distance < AppConfig.ARRIVAL_THRESHOLD_M) break
            val bearing = GeoMath.calculateBearing(current.latitude, current.longitude, targetLat, targetLng)
            val bearingRad = Math.toRadians(bearing)
            val speed = max(min((distance * AppConfig.getSpeedGain()).toFloat(), AppConfig.getMaxFlightSpeed()), AppConfig.MIN_FLIGHT_SPEED)
            val currentHeading = FlightManager.heading.value
            var yawError = bearing - currentHeading
            if (yawError > 180) yawError -= 360
            if (yawError < -180) yawError += 360
            val yawSpeed = when {
                abs(yawError) < AppConfig.YAW_SMALL_ERROR_THRESHOLD_DEG -> 0.0
                abs(yawError) < AppConfig.YAW_MEDIUM_ERROR_THRESHOLD_DEG -> yawError * AppConfig.YAW_ALIGN_PROPORTIONAL_GAIN
                else -> AppConfig.YAW_SPEED_DEG_PER_SEC * if (yawError > 0) 1.0 else -1.0
            }
            param.pitch = speed * sin(bearingRad)
            param.roll = speed * cos(bearingRad)
            param.yaw = yawSpeed
            // 主動高度保持：偏差超過 0.5m 時修正
            val currentAlt = FlightManager.getCurrentAltitude() ?: 0.0
            val altError = targetAlt - currentAlt
            param.verticalThrottle = when {
                abs(altError) < 0.5 -> 0.0
                else -> (altError * 0.5).coerceIn(-2.0, 2.0)  // P 控制，限速 ±2 m/s
            }
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
            delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
        }
        VirtualStickController.sendZeroCommand(param)
        delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
        AppLog.d(TAG, "Arrived at target")
    }

    /** 飛往下一個站點（多站任務中途不降落） */
    suspend fun flyToNextStop(targetLat: Double, targetLng: Double, targetAlt: Double) {
        MissionStateMachine.setState(MissionStateMachine.MissionState.FLYING_TO_NEXT)
        AppLog.d(TAG, "Flying to next stop: lat=$targetLat, lng=$targetLng, alt=$targetAlt")
        val currentAlt = FlightManager.getCurrentAltitude() ?: targetAlt
        if (targetAlt > currentAlt + AppConfig.ALTITUDE_COMPARISON_TOLERANCE_M) {
            climbToAltitude(targetAlt)
        } else if (targetAlt < currentAlt - AppConfig.ALTITUDE_COMPARISON_TOLERANCE_M) {
            val param = VirtualStickParams.groundVelocityParams()
            descendToAltitude(targetAlt, param)
        }
        flyToTarget(targetLat, targetLng, targetAlt)
    }

    /** 拍照階段：旋轉指定角度（VS 必須已啟用，呼叫端負責 VS 生命週期） */
    suspend fun rotateYawForPhoto(degrees: Double) {
        val param = VirtualStickParams.groundVelocityParams()

        var rotated = 0.0
        var lastYaw = FlightManager.getCurrentYaw()
        val startTime = System.currentTimeMillis()
        while (coroutineContext.isActive && rotated < degrees - AppConfig.YAW_ALIGNMENT_TOLERANCE_DEG) {
            if (System.currentTimeMillis() - startTime > AppConfig.PHOTO_ROTATION_TIMEOUT_MS) {
                Log.e(TAG, "rotateYawForPhoto: Timeout! rotated=${"%.1f".format(rotated)}°")
                break
            }
            param.yaw = AppConfig.YAW_SPEED_DEG_PER_SEC.toDouble()
            param.pitch = 0.0; param.roll = 0.0; param.verticalThrottle = 0.0
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
            delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
            val yaw = FlightManager.getCurrentYaw()
            var delta = yaw - lastYaw
            if (delta > 180) delta -= 360
            if (delta < -180) delta += 360
            rotated += abs(delta)
            lastYaw = yaw
            AppLog.d(TAG, "rotateYawForPhoto: rotated=${"%.1f".format(rotated)}° / ${degrees}°")
        }

        VirtualStickController.sendZeroCommand(param)
        delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
        AppLog.d(TAG, "rotateYawForPhoto: rotation done, VS kept enabled for next photo")
    }

    /** 預估從當前位置飛到目標再返航所需的電量百分比 */
    fun estimateRemainingFlightBattery(
        fromLat: Double, fromLng: Double,
        toLat: Double, toLng: Double,
        missionTargetAlt: Double
    ): Int {
        val homeLoc = FlightManager.getCurrentLocation()
        val distToNext = GeoMath.haversine(fromLat, fromLng, toLat, toLng)
        val distNextToHome = homeLoc?.let {
            GeoMath.haversine(toLat, toLng, it.latitude, it.longitude)
        } ?: distToNext
        val totalHorizontalDist = distToNext + distNextToHome

        // 高度耗能估算：爬升約為水平飛行的 2 倍耗能、下降約 0.5 倍
        val currentAlt = FlightManager.altitude.value
        val altDiff = max(0.0, missionTargetAlt - currentAlt)  // only count climb cost
        val altEnergyDist = altDiff * 2.0  // climbing equivalent horizontal distance

        val totalEffectiveDist = totalHorizontalDist + altEnergyDist

        // 參考基準：往返最大飛行半徑視為 100% 電量預算
        val maxRadius = AppConfig.getMaxFlightRadius().toDouble()
        val referenceDist = maxRadius * 2.0
        val basePercent = (totalEffectiveDist / referenceDist * 100).toInt()

        // 限制在 [20%, 60%] — Mini 4 Pro 電池容量較小，需預留返航電量
        return min(max(basePercent, 20), 60)
    }
}
