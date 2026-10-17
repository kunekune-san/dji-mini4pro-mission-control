package dji.sampleV5.aircraft.mission

import android.util.Log
import dji.sampleV5.aircraft.util.AppLog
import dji.sampleV5.aircraft.config.AppConfig
import dji.sampleV5.aircraft.dji.CameraManager
import dji.sampleV5.aircraft.dji.FlightManager
import dji.sampleV5.aircraft.mqtt.MqttClientManager
import dji.sampleV5.aircraft.mqtt.MqttTopics
import dji.sampleV5.aircraft.dji.GimbalManager
import dji.sampleV5.aircraft.util.GeoMath
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.abs
import dji.sampleV5.aircraft.safety.SafetyAnomalyType
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.atan
import kotlin.coroutines.coroutineContext


/** B 模式：以指定半徑順時針環繞目標 360° 錄影。從 OrbitController.kt 提取。 */
internal object OrbitModeB {
    private const val TAG = OrbitHelpers.TAG

        /** B 模式：以指定半徑順時針環繞目標 360° 錄影。注意：此方法假設獨佔 VirtualStick 使用權。
         *  @param orbitSpeedMultiplier 環繞速度倍率（1.0=標準 60s/圈, 1.5=快 40s/圈, 2.0=極快 30s/圈），預設 1.0 */
        suspend fun execute(
            centerLat: Double,
            centerLng: Double,
            orbitAlt: Double,
            orbitRadius: Double,
            gimbalPitch: Double,
            param: VirtualStickFlightControlParam,
            orbitSpeedMultiplier: Double = 1.0
        ) {
            MissionStateMachine.setState(MissionStateMachine.MissionState.ORBITING)
            AppLog.d(TAG, "Starting orbit: radius=${orbitRadius}m, alt=${orbitAlt}m, gimbal=${gimbalPitch}°, speed=${orbitSpeedMultiplier}x")
    
            AppLog.d(TAG, "Setting gimbal to ${gimbalPitch}°")
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
    
            CameraManager.setCameraMode(false)
            delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
    
            AppLog.d(TAG, "Triggering autofocus before orbit recording")
            CameraManager.triggerAutoFocus()
            delay(AppConfig.CAMERA_FOCUS_WAIT_MS)
    
            // 環繞起點：位於目標北方 orbitRadius 處
            val startLat = centerLat + (orbitRadius / 6371000.0) * Math.toDegrees(1.0)
            val startLng = centerLng
    
            // 從圓心飛往環繞起點
            if (orbitRadius > 0.5) {
                AppLog.d(TAG, "Flying to orbit start: ${"%.2f".format(startLat)}, ${"%.2f".format(startLng)} (${orbitRadius}m N of center)")
                while (coroutineContext.isActive) {
                    val current = FlightManager.getFilteredLocation() ?: break
                    val dist = GeoMath.haversine(current.latitude, current.longitude, startLat, startLng)
                    if (dist < AppConfig.ORBIT_POSITION_HOLD_TOLERANCE_M) break
                    val bearing = GeoMath.calculateBearing(current.latitude, current.longitude, startLat, startLng)
                    val bearingRad = Math.toRadians(bearing)
                    val speed = max(min((dist * AppConfig.getSpeedGain()).toFloat(), AppConfig.getMaxFlightSpeed()), AppConfig.MIN_FLIGHT_SPEED)
                    param.pitch = (speed * sin(bearingRad)).toDouble()
                    param.roll = (speed * cos(bearingRad)).toDouble()
                    val headingToCenter = GeoMath.calculateBearing(current.latitude, current.longitude, centerLat, centerLng)
                    val currentHeading = FlightManager.heading.value
                    var yawErr = headingToCenter - currentHeading
                    if (yawErr > 180) yawErr -= 360
                    if (yawErr < -180) yawErr += 360
                    param.yaw = (yawErr * 0.5).coerceIn(-30.0, 30.0)
                    param.verticalThrottle = 0.0
                    param.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
                    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                    delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
                }
                VirtualStickController.sendZeroCommand(param)
                delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
                AppLog.d(TAG, "Arrived at orbit starting position")
            }
    
            // ★ 視覺預選取：在外圍偏移點讓使用者選取目標後，再飛到環繞起始點
            OrbitHelpers.visualPreSelection(centerLat, centerLng, orbitRadius, gimbalPitch, param)
    
            // 在圓周起點下降至 orbitAlt（避免在圓心下降時撞擊障礙物）
            val currentAltAtOrbitStart = FlightManager.getCurrentAltitude() ?: orbitAlt
            if (currentAltAtOrbitStart > orbitAlt + 0.5) {
                MissionStateMachine.setState(MissionStateMachine.MissionState.DESCENDING_TO_ORBIT_ALT)
                AppLog.d(TAG, "Orbit: descending from ${"%.1f".format(currentAltAtOrbitStart)}m to orbit alt ${"%.1f".format(orbitAlt)}m at orbit start position")
                val descStart = System.currentTimeMillis()
                while (coroutineContext.isActive) {
                    val alt = FlightManager.getCurrentAltitude() ?: 0.0
                    if (alt <= orbitAlt + 0.3) break
                    if (System.currentTimeMillis() - descStart > AppConfig.CLIMB_TIMEOUT_MS) {
                        Log.w(TAG, "Orbit descent timeout, proceeding at current altitude")
                        break
                    }
                    param.pitch = 0.0; param.roll = 0.0; param.yaw = 0.0
                    param.verticalThrottle = -AppConfig.CLIMB_THROTTLE.toDouble()
                    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                    delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
                }
                VirtualStickController.sendZeroCommand(param)
                delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
                AppLog.d(TAG, "Orbit: descent complete at orbit start position")
            } else if (currentAltAtOrbitStart < orbitAlt - 0.5) {
                // 需要上升（少見，但防禦性處理）
                MissionStateMachine.setState(MissionStateMachine.MissionState.DESCENDING_TO_ORBIT_ALT)
                AppLog.d(TAG, "Orbit: climbing from ${"%.1f".format(currentAltAtOrbitStart)}m to orbit alt ${"%.1f".format(orbitAlt)}m at orbit start position")
                val climbStart = System.currentTimeMillis()
                while (coroutineContext.isActive) {
                    val alt = FlightManager.getCurrentAltitude() ?: 0.0
                    if (alt >= orbitAlt - 0.3) break
                    if (System.currentTimeMillis() - climbStart > AppConfig.CLIMB_TIMEOUT_MS) {
                        Log.w(TAG, "Orbit climb timeout, proceeding at current altitude")
                        break
                    }
                    param.pitch = 0.0; param.roll = 0.0; param.yaw = 0.0
                    param.verticalThrottle = AppConfig.CLIMB_THROTTLE.toDouble()
                    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                    delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
                }
                VirtualStickController.sendZeroCommand(param)
                delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
                AppLog.d(TAG, "Orbit: climb complete at orbit start position")
            }
    
            // 視覺預選取已在 OrbitHelpers.visualPreSelection() 中完成，此處不重複選取
    
            suspendCancellableCoroutine<Unit> { cont ->
                CameraManager.startVideoRecord(object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {
                        AppLog.d(TAG, "Orbit recording started")
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                            override fun onFailure(error: IDJIError) {
                        Log.e(TAG, "Orbit recording start failed: $error")
                        if (cont.isActive) cont.resumeWith(Result.failure(Exception("Orbit recording start failed: $error")))
                    }
                })
            }
    
            // 環繞速度：由 MQTT orbit_speed 倍率 + 半徑決定，僅受 DJI 硬體上限限制
            val effectiveMultiplier = orbitSpeedMultiplier.coerceIn(AppConfig.ORBIT_SPEED_MIN, AppConfig.ORBIT_SPEED_MAX)
            val effectiveOrbitTimeSec = AppConfig.MIN_ORBIT_TIME_SEC / effectiveMultiplier
            val maxAngularVelRad = Math.toRadians(360.0 / effectiveOrbitTimeSec)
            val minLinearSpeed = 2 * Math.PI * orbitRadius / effectiveOrbitTimeSec
            val orbitSpeed = max(orbitRadius * maxAngularVelRad, minLinearSpeed)
                .coerceIn(AppConfig.MIN_FLIGHT_SPEED.toDouble(), AppConfig.MAX_FLIGHT_SPEED_MAX.toDouble())
            var prevBearingFromCenter: Double? = null
            var totalAngle = 0.0
            var yawIntegral = 0.0
    
            // Yaw 穩定
            AppLog.d(TAG, "Orbit: stabilizing yaw toward center before starting orbit loop")
            val yawStabilizeStart = System.currentTimeMillis()
            while (coroutineContext.isActive && System.currentTimeMillis() - yawStabilizeStart < AppConfig.ORBIT_YAW_STABILIZE_TIMEOUT_MS) {
                val loc = FlightManager.getFilteredLocation() ?: break
                val bearToCenter = GeoMath.calculateBearing(loc.latitude, loc.longitude, centerLat, centerLng)
                val hdg = FlightManager.heading.value
                var yErr = bearToCenter - hdg
                if (yErr > 180) yErr -= 360
                if (yErr < -180) yErr += 360
                AppLog.d(TAG, "Orbit stabilize: bearToCenter=${"%.1f".format(bearToCenter)}° heading=${"%.1f".format(hdg)}° yawErr=${"%.1f".format(yErr)}°")
                if (abs(yErr) < AppConfig.YAW_STABILIZE_TOLERANCE_DEG) {
                    AppLog.d(TAG, "Orbit: yaw stabilized (err=${"%.1f".format(yErr)}°)")
                    break
                }
                val distToStart = GeoMath.haversine(loc.latitude, loc.longitude, startLat, startLng)
                if (distToStart > 0.5) {
                    val bearingToStart = GeoMath.calculateBearing(loc.latitude, loc.longitude, startLat, startLng)
                    val bearingRad = Math.toRadians(bearingToStart)
                    val posSpeed = min((distToStart * 0.5).toFloat(), 1.0f)
                    param.pitch = (posSpeed * sin(bearingRad)).toDouble()
                    param.roll = (posSpeed * cos(bearingRad)).toDouble()
                } else {
                    param.pitch = 0.0
                    param.roll = 0.0
                }
                param.yaw = (yErr * 0.5).coerceIn(-15.0, 15.0)
                param.verticalThrottle = 0.0
                param.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
                VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
            }
            VirtualStickController.sendZeroCommand(param)
            delay(AppConfig.VS_DISABLE_DELAY_MS)
    
            val orbitStartTime = System.currentTimeMillis()
            var loopIteration = 0
            var lastBatteryCheckAngle = 0.0
            var cmdSmooth = OrbitHelpers.SmoothState()
            var prevRadialError: Double? = null
            var smoothedBearingRate: Double? = null
            val dtSec = AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS / 1000.0
            AppLog.d(TAG, "Orbit loop starting, speed=${"%.2f".format(orbitSpeed)}m/s")
    
            while (coroutineContext.isActive && totalAngle < AppConfig.ORBIT_ANGLE_THRESHOLD) {
                if (System.currentTimeMillis() - orbitStartTime > AppConfig.ORBIT_TIMEOUT_MS) {
                    Log.e(TAG, "Orbit timeout, totalAngle=${"%.1f".format(totalAngle)}°")
                    break
                }
    
                // C3: 飛離保護 — 定期檢查與圓心距離
                loopIteration++
                if (loopIteration % AppConfig.ORBIT_FLYAWAY_CHECK_INTERVAL == 0) {
                    val currentDist = FlightManager.getFilteredLocation()?.let {
                        GeoMath.haversine(it.latitude, it.longitude, centerLat, centerLng)
                    }
                    if (currentDist != null && currentDist > orbitRadius * AppConfig.ORBIT_FLYAWAY_FACTOR) {
                        Log.e(TAG, "Orbit flyaway detected: dist=${"%.1f".format(currentDist)}m > ${"%.1f".format(orbitRadius * AppConfig.ORBIT_FLYAWAY_FACTOR)}m threshold")
                        MqttClientManager.publish(MqttTopics.STATUS, """{"state":"ORBIT_FLYAWAY","dist":${"%.1f".format(currentDist)}}""")
                        MissionStateMachine.logMissionEvent(SafetyAnomalyType.ORBIT_FLYAWAY, "Orbit B flyaway: dist=${"%.1f".format(currentDist)}m > ${"%.1f".format(orbitRadius * AppConfig.ORBIT_FLYAWAY_FACTOR)}m threshold")
                        break
                    }
                }
    
                // C4: 每 90° 軌跡檢查一次電池
                if (totalAngle - lastBatteryCheckAngle >= AppConfig.ORBIT_BATTERY_CHECK_INTERVAL_DEG) {
                    lastBatteryCheckAngle = totalAngle
                    val battery = FlightManager.batteryPercent.value
                    val threshold = FlightManager.effectiveThreshold.value
                    if (battery <= threshold) {
                        Log.e(TAG, "Orbit: battery critical ($battery% <= $threshold%), aborting → RTH")
                        MissionStateMachine.logMissionEvent(SafetyAnomalyType.BATTERY_ABORT, "Orbit B battery abort: $battery% <= $threshold%")
                        MissionStateMachine.cancelMission(reason = "Battery critical: $battery% <= $threshold%")
                        return
                    }
                }
    
                val current = FlightManager.getFilteredLocation()
                if (current == null) {
                    Log.w(TAG, "Orbit: cannot get location, retrying...")
                    delay(AppConfig.VS_DISABLE_SETTLE_DELAY_MS)
                    continue
                }
    
                val distFromCenter = GeoMath.haversine(current.latitude, current.longitude, centerLat, centerLng)
                val bearingFromCenter = GeoMath.calculateBearing(centerLat, centerLng, current.latitude, current.longitude)
    
                var angleDelta = 0.0
                if (prevBearingFromCenter != null) {
                    angleDelta = bearingFromCenter - prevBearingFromCenter!!
                    if (angleDelta > 180) angleDelta -= 360
                    if (angleDelta < -180) angleDelta += 360
                    totalAngle += abs(angleDelta)
                }
                prevBearingFromCenter = bearingFromCenter
    
                val radialError = distFromCenter - orbitRadius
                val radialDerivative = if (prevRadialError != null) (radialError - prevRadialError!!) / dtSec else 0.0
                prevRadialError = radialError
                val radialSpeed = (-(radialError * AppConfig.ORBIT_KP) - (radialDerivative * AppConfig.ORBIT_RADIAL_KD))
                    .coerceIn(-orbitSpeed * 0.5, orbitSpeed * 0.5)
    
                val bearingRad = Math.toRadians(bearingFromCenter)
                val tangentialNorth = -sin(bearingRad) * orbitSpeed
                val tangentialEast = cos(bearingRad) * orbitSpeed
                val radialNorth = cos(bearingRad) * radialSpeed
                val radialEast = sin(bearingRad) * radialSpeed
    
                // 歸一化：保持恆定 orbitSpeed 大小，只調整方向
                // 徑向修正最多 ±50% × orbitSpeed，未歸一化會導致速度脈衝 → 錄影抖動
                val combinedNorthRaw = tangentialNorth + radialNorth
                val combinedEastRaw = tangentialEast + radialEast
                val combinedSpeed = sqrt(combinedNorthRaw * combinedNorthRaw + combinedEastRaw * combinedEastRaw)
                val scale = if (combinedSpeed > AppConfig.MAX_FLIGHT_SPEED_MAX) AppConfig.MAX_FLIGHT_SPEED_MAX / combinedSpeed else 1.0
                val combinedNorth = combinedNorthRaw * scale
                val combinedEast = combinedEastRaw * scale
    
                param.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
                val maxSpd = AppConfig.MAX_FLIGHT_SPEED_MAX.toDouble()
                param.pitch = combinedEast.coerceIn(-maxSpd, maxSpd)
                param.roll = combinedNorth.coerceIn(-maxSpd, maxSpd)
    
                // Yaw：閉迴路控制，用實際 GPS 位置計算 bearingToCenter（不再用理論推算）
                val bearingToCenter = (bearingFromCenter + 180.0) % 360.0
                val currentHeading = FlightManager.heading.value
                var yawError = bearingToCenter - currentHeading
                if (yawError > 180) yawError -= 360
                if (yawError < -180) yawError += 360
                yawIntegral = (yawIntegral + yawError * AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS / 1000.0).coerceIn(-AppConfig.YAW_INTEGRAL_CLAMP, AppConfig.YAW_INTEGRAL_CLAMP)
                val rawBearingRate = angleDelta / dtSec
                val aBr = AppConfig.ORBIT_BEARING_RATE_ALPHA
                smoothedBearingRate = if (smoothedBearingRate != null) aBr * rawBearingRate + (1 - aBr) * smoothedBearingRate!! else rawBearingRate
                val ffGain = AppConfig.ORBIT_YAW_FEEDFORWARD_GAIN
                val feedforward = smoothedBearingRate!! * AppConfig.ORBIT_YAW_PREDICTION_SEC * ffGain
                // 視覺輔助 Yaw 修正：使用者選取目標時，將畫面偏移量加入 yaw 控制（沒選目標時回傳 0.0）
                val visualYawCorrection = VisualYawAssist.getCorrection()
                val manualYawOffset = AppConfig.getVisualYawOffset()
                param.yaw = (yawError * AppConfig.ORBIT_YAW_KP + yawIntegral * AppConfig.ORBIT_YAW_KI + feedforward + visualYawCorrection + manualYawOffset).coerceIn(-AppConfig.YAW_OUTPUT_CLAMP, AppConfig.YAW_OUTPUT_CLAMP)
                param.verticalThrottle = 0.0
    
                if (System.currentTimeMillis() % 1000 < 60) {
                    AppLog.d(TAG, "Orbit: bear=${"%.1f".format(bearingFromCenter)}° dist=${"%.1f".format(distFromCenter)}m rErr=${"%.1f".format(radialError)}m " +
                        "tanN=${"%.2f".format(tangentialNorth)} tanE=${"%.2f".format(tangentialEast)} " +
                        "p=${"%.2f".format(param.pitch)} r=${"%.2f".format(param.roll)} yErr=${"%.1f".format(yawError)}° y=${"%.1f".format(param.yaw)}°/s")
                }
    
                // 指令低通濾波：平滑 pitch/roll/yaw 輸出，減少電機高頻震動
                // yaw 使用較高 alpha（0.8），避免 EMA 導致 PI 積分偏高
                OrbitHelpers.applySmoothing(cmdSmooth, param.pitch, param.roll, param.yaw, AppConfig.CMD_SMOOTH_YAW_ALPHA)
                param.pitch = cmdSmooth.pitch; param.roll = cmdSmooth.roll; param.yaw = cmdSmooth.yaw
    
                VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
            }
    
            VirtualStickController.sendZeroCommand(param)
            delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
    
            suspendCancellableCoroutine<Unit> { cont ->
                CameraManager.stopVideoRecord(object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {
                        AppLog.d(TAG, "Orbit recording stopped")
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                            override fun onFailure(error: IDJIError) {
                        Log.e(TAG, "Orbit recording stop failed: $error")
                        if (cont.isActive) cont.resumeWith(Result.failure(Exception("Orbit recording stop failed: $error")))
                    }
                })
            }
    
            GimbalManager.setPitchAngle(0.0)
            GimbalManager.waitForPitchReady(0.0)
            AppLog.d(TAG, "Gimbal reset to 0°")
    
            param.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
            AppLog.d(TAG, "Orbit phase complete, totalAngle=${"%.1f".format(totalAngle)}°")
        }
}
