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
import kotlinx.coroutines.launch
import kotlin.math.abs
import dji.sampleV5.aircraft.safety.SafetyAnomalyType
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.coroutines.coroutineContext


/** C 模式：螺旋上升環繞錄影。從 OrbitController.kt 提取。 */
internal object OrbitModeC {
    private const val TAG = OrbitHelpers.TAG

        suspend fun execute(
            centerLat: Double,
            centerLng: Double,
            zMin: Double,
            zMax: Double,
            orbitRadius: Double,
            turns: Int,
            param: VirtualStickFlightControlParam,
            orbitSpeedMultiplier: Double = 1.0,
            finalHeading: Double? = null
        ) {
            MissionStateMachine.setState(MissionStateMachine.MissionState.SPIRAL_ASCENDING)
            AppLog.d(TAG, "Starting spiral ascent: radius=${orbitRadius}m, Z_min=${zMin}m, Z_max=${zMax}m, turns=$turns" +
                (if (finalHeading != null) ", finalHeading=${"%.1f".format(finalHeading)}°" else ""))

            // Phase 1: 初始雲台角度（根據 Z_min 計算）
            val initialGimbalPitch = if (zMin <= AppConfig.SPIRAL_GIMBAL_HEIGHT_THRESHOLD) 0.0
                else -Math.toDegrees(atan((zMin - AppConfig.SPIRAL_GIMBAL_HEIGHT_THRESHOLD) / orbitRadius))
            AppLog.d(TAG, "Spiral: initial gimbal pitch=${"%.1f".format(initialGimbalPitch)}°")
            try {
                GimbalManager.setPitchAngle(initialGimbalPitch)
                GimbalManager.waitForPitchReady(initialGimbalPitch)
            } catch (e: Exception) {
                Log.w(TAG, "Gimbal set failed, retrying: ${e.message}")
                delay(500)
                GimbalManager.setPitchAngle(initialGimbalPitch)
                GimbalManager.waitForPitchReady(initialGimbalPitch)
            }
            delay(AppConfig.VS_ZERO_SEND_DELAY_MS)

            // Phase 2: 相機設定
            CameraManager.setCameraMode(false)
            delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
            AppLog.d(TAG, "Triggering autofocus before spiral recording")
            CameraManager.triggerAutoFocus()
            delay(AppConfig.CAMERA_FOCUS_WAIT_MS)

            // Phase 3: 飛至螺旋起點（中心北方 orbitRadius 處）
            val startLat = centerLat + (orbitRadius / 6371000.0) * Math.toDegrees(1.0)
            val startLng = centerLng
            val currentAltBeforeSpiral = FlightManager.getCurrentAltitude() ?: zMin

            if (orbitRadius > 0.5) {
                AppLog.d(TAG, "Spiral: flying to orbit start at ${"%.2f".format(startLat)}, ${"%.2f".format(startLng)}")
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
                AppLog.d(TAG, "Spiral: arrived at orbit start position")
            }

            // ★ 視覺預選取：在外圍偏移點讓使用者選取目標後，再飛到環繞起始點
            OrbitHelpers.visualPreSelection(centerLat, centerLng, orbitRadius, initialGimbalPitch, param)

            // 下降至 Z_min（若需要）
            if (currentAltBeforeSpiral > zMin + 0.5) {
                AppLog.d(TAG, "Spiral: descending from ${"%.1f".format(currentAltBeforeSpiral)}m to Z_min ${"%.1f".format(zMin)}m")
                val descStart = System.currentTimeMillis()
                while (coroutineContext.isActive) {
                    val alt = FlightManager.getCurrentAltitude() ?: 0.0
                    if (alt <= zMin + 0.3) break
                    if (System.currentTimeMillis() - descStart > AppConfig.CLIMB_TIMEOUT_MS) break
                    param.pitch = 0.0; param.roll = 0.0; param.yaw = 0.0
                    param.verticalThrottle = -AppConfig.CLIMB_THROTTLE.toDouble()
                    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                    delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
                }
                VirtualStickController.sendZeroCommand(param)
                delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
            }

            // Yaw 穩定：等待機頭朝向中心後再開始螺旋，避免前幾圈 yaw 誤差過大
            AppLog.d(TAG, "Spiral: stabilizing yaw toward center before starting spiral")
            val yawStabilizeStart = System.currentTimeMillis()
            while (coroutineContext.isActive && System.currentTimeMillis() - yawStabilizeStart < AppConfig.ORBIT_YAW_STABILIZE_TIMEOUT_MS) {
                val loc = FlightManager.getFilteredLocation() ?: break
                val bearToCenter = GeoMath.calculateBearing(loc.latitude, loc.longitude, centerLat, centerLng)
                val hdg = FlightManager.heading.value
                var yErr = bearToCenter - hdg
                if (yErr > 180) yErr -= 360
                if (yErr < -180) yErr += 360
                if (abs(yErr) < AppConfig.YAW_STABILIZE_TOLERANCE_DEG) {
                    AppLog.d(TAG, "Spiral: yaw stabilized (err=${"%.1f".format(yErr)}°)")
                    break
                }
                param.pitch = 0.0; param.roll = 0.0
                param.yaw = (yErr * 0.5).coerceIn(-15.0, 15.0)
                param.verticalThrottle = 0.0
                param.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
                VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
            }
            VirtualStickController.sendZeroCommand(param)
            delay(AppConfig.VS_ZERO_SEND_DELAY_MS)

            // 視覺預選取已在 visualPreSelection() 中完成，此處不重複選取

            // Phase 4: 開始錄影
            suspendCancellableCoroutine<Unit> { cont ->
                CameraManager.startVideoRecord(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        AppLog.d(TAG, "Spiral recording started")
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                    override fun onFailure(error: IDJIError) {
                        Log.e(TAG, "Spiral recording start failed: $error")
                        if (cont.isActive) cont.resumeWith(Result.failure(Exception("Spiral recording start failed: $error")))
                    }
                })
            }

            // Phase 5: 螺旋上升迴圈
            // 環繞速度獨立於 Settings 飛行速度，僅由 MQTT 倍率 + 半徑決定
            val theoreticalTimeSec = AppConfig.MIN_ORBIT_TIME_SEC / orbitSpeedMultiplier.coerceIn(AppConfig.ORBIT_SPEED_MIN, AppConfig.ORBIT_SPEED_MAX)
            val theoreticalSpeed = max(orbitRadius * Math.toRadians(360.0 / theoreticalTimeSec), 2 * Math.PI * orbitRadius / theoreticalTimeSec)
            val orbitSpeed = theoreticalSpeed.coerceIn(AppConfig.MIN_FLIGHT_SPEED.toDouble(), AppConfig.MAX_FLIGHT_SPEED_MAX.toDouble())
            val effectiveOrbitTimeSec = if (orbitSpeed > 0) 2 * Math.PI * orbitRadius / orbitSpeed else theoreticalTimeSec
            val maxAngularVelRad = Math.toRadians(360.0 / effectiveOrbitTimeSec)
            val altitudeGain = zMax - zMin

            // 爬升率計算：speed=1 為基準，speed=N 就是 ×N
            // baseClimbRate = 高度差 / (MQTT 圈數 × 60 秒)
            // climbRate = baseClimbRate × speed，上限 SPIRAL_MAX_CLIMB_RATE
            // 若爬升率超限 → 自動增加圈數，確保角度與高度同時完成
            val baseClimbRate = if (turns > 0) altitudeGain / (turns * AppConfig.MIN_ORBIT_TIME_SEC) else 0.0
            val directClimbRate = baseClimbRate * orbitSpeedMultiplier
            val maxClimbRate = AppConfig.SPIRAL_MAX_CLIMB_RATE.toDouble()
            val actualTurns = if (directClimbRate > maxClimbRate && effectiveOrbitTimeSec > 0)
                max(turns, ceil(altitudeGain / (maxClimbRate * effectiveOrbitTimeSec)).toInt())
            else turns
            val totalAngleTarget = actualTurns * 360.0
            val climbRate = (altitudeGain / (actualTurns * effectiveOrbitTimeSec)).coerceIn(0.0, maxClimbRate)

            var prevBearingFromCenter: Double? = null
            val angularVelDeg = Math.toDegrees(orbitSpeed / orbitRadius)  // 時間推算角速度（°/s）
            var totalAngle = 0.0
            var yawIntegral = 0.0
            val spiralStartTime = System.currentTimeMillis()
            var lastGimbalUpdateTime = spiralStartTime  // 雲台時間節流（取代高度間隔 + 阻塞旗標）
            var loopIteration = 0
            var lastBatteryCheckAngle = 0.0

            var cmdSmooth = OrbitHelpers.SmoothState()
            var prevRadialError: Double? = null
            var smoothedBearingRate: Double? = null
            val dtSec = AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS / 1000.0
            var lastLoopTime = spiralStartTime  // 實際迴圈 dt 測量（取代固定 dtSec）
            AppLog.d(TAG, "Spiral loop starting: speed=${"%.2f".format(orbitSpeed)}m/s, multiplier=${"%.1f".format(orbitSpeedMultiplier)}x, " +
                "effectiveOrbitTime=${"%.0f".format(effectiveOrbitTimeSec)}s, angularVel=${"%.1f".format(angularVelDeg)}°/s, " +
                "mqttTurns=$turns, actualTurns=$actualTurns, target=$totalAngleTarget°, " +
                "altGain=${"%.1f".format(altitudeGain)}m, climbRate=${"%.2f".format(climbRate)}m/s, " +
                "rampUp=${AppConfig.SPIRAL_RAMP_UP_DURATION_SEC}s")

            // 螺旋迴圈：角度與高度雙條件，兩者都達標才結束
            // 進度追蹤採用迭代累積（配合 ramp 加速），yaw feedforward 用 GPS bearing
            var currentAltForGuard = FlightManager.getCurrentAltitude() ?: zMin
            while (coroutineContext.isActive) {
                val angleDone = totalAngle >= totalAngleTarget
                val altDone = currentAltForGuard >= zMax - AppConfig.ALTITUDE_DESCENT_TOLERANCE_M
                if (angleDone && altDone) break
                // 超時檢查
                if (System.currentTimeMillis() - spiralStartTime > AppConfig.SPIRAL_TIMEOUT_MS) {
                    Log.e(TAG, "Spiral timeout, totalAngle=${"%.1f".format(totalAngle)}°")
                    MissionStateMachine.logMissionEvent(SafetyAnomalyType.SPIRAL_TIMEOUT, "Spiral C timeout: totalAngle=${"%.1f".format(totalAngle)}°")
                    break
                }

                // Flyaway 檢查
                loopIteration++
                // 量測實際迴圈 dt（取代固定 dtSec=0.05 假設）
                val nowMs = System.currentTimeMillis()
                val actualDt = ((nowMs - lastLoopTime) / 1000.0).coerceIn(0.04, 0.2)
                lastLoopTime = nowMs
                if (loopIteration % AppConfig.ORBIT_FLYAWAY_CHECK_INTERVAL == 0) {
                    val currentDist = FlightManager.getFilteredLocation()?.let {
                        GeoMath.haversine(it.latitude, it.longitude, centerLat, centerLng)
                    }
                    if (currentDist != null && currentDist > orbitRadius * AppConfig.ORBIT_FLYAWAY_FACTOR) {
                        Log.e(TAG, "Spiral flyaway detected: dist=${"%.1f".format(currentDist)}m")
                        MqttClientManager.publish(MqttTopics.STATUS, """{"state":"SPIRAL_FLYAWAY","dist":${"%.1f".format(currentDist)}}""")
                        MissionStateMachine.logMissionEvent(SafetyAnomalyType.ORBIT_FLYAWAY, "Spiral C flyaway: dist=${"%.1f".format(currentDist)}m")
                        break
                    }
                }

                // 電池檢查每 90°
                if (totalAngle - lastBatteryCheckAngle >= AppConfig.ORBIT_BATTERY_CHECK_INTERVAL_DEG) {
                    lastBatteryCheckAngle = totalAngle
                    val battery = FlightManager.batteryPercent.value
                    val threshold = FlightManager.effectiveThreshold.value
                    if (battery <= threshold) {
                        Log.e(TAG, "Spiral: battery critical ($battery% <= $threshold%), aborting → RTH")
                        MissionStateMachine.logMissionEvent(SafetyAnomalyType.BATTERY_ABORT, "Spiral C battery abort: $battery% <= $threshold%")
                        MissionStateMachine.cancelMission(reason = "Battery critical: $battery% <= $threshold%")
                        return
                    }
                }

                // 取得當前位置
                val current = FlightManager.getFilteredLocation()
                if (current == null) {
                    Log.w(TAG, "Spiral: cannot get location, retrying...")
                    delay(AppConfig.VS_DISABLE_SETTLE_DELAY_MS)
                    continue
                }

                val distFromCenter = GeoMath.haversine(current.latitude, current.longitude, centerLat, centerLng)
                val bearingFromCenter = GeoMath.calculateBearing(centerLat, centerLng, current.latitude, current.longitude)
                val currentAlt = FlightManager.getCurrentAltitude() ?: zMin
                currentAltForGuard = currentAlt  // 更新迴圈條件用的高度

                // 角度追蹤：牆鐘時間積分（閉式解，免疫 loop dt 抖動）
                // ramp 為 easeInSine，∫₀ᵗ sin(π·τ/(2·T)) dτ = (2T/π)·[1 − cos(π·t/(2T))]
                // ramp 後為等角速度線性段，總角度 = 角速度 × 等效時間
                // yaw feedforward 仍用 GPS bearing
                val elapsedSec = (System.currentTimeMillis() - spiralStartTime) / 1000.0
                val T = AppConfig.SPIRAL_RAMP_UP_DURATION_SEC
                val rampFactor = if (elapsedSec < T) sin(elapsedSec / T * Math.PI / 2) else 1.0
                val effectiveElapsedSec = if (elapsedSec < T) {
                    (2.0 * T / Math.PI) * (1.0 - cos(elapsedSec / T * Math.PI / 2))
                } else {
                    (2.0 * T / Math.PI) + (elapsedSec - T)
                }
                totalAngle = angularVelDeg * effectiveElapsedSec  // 直接賦值，非累加

                // bearing delta 僅用於 yaw feedforward，不影響進度追蹤
                var angleDelta = 0.0
                if (prevBearingFromCenter != null) {
                    angleDelta = bearingFromCenter - prevBearingFromCenter!!
                    if (angleDelta > 180) angleDelta -= 360
                    if (angleDelta < -180) angleDelta += 360
                }
                prevBearingFromCenter = bearingFromCenter

                // 高度追蹤：與角度進度綁定（水平移動 = 上升高度）
                val progress = (totalAngle / totalAngleTarget).coerceIn(0.0, 1.0)
                val targetAlt = zMin + altitudeGain * progress

                // --- 水平控制（PD 徑向修正，含 ramp 啟動加速）---
                val effectiveOrbitSpeed = orbitSpeed * rampFactor
                val radialError = distFromCenter - orbitRadius
                val radialDerivative = if (prevRadialError != null) (radialError - prevRadialError!!) / actualDt else 0.0
                prevRadialError = radialError
                val radialSpeed = (-(radialError * AppConfig.ORBIT_KP) - (radialDerivative * AppConfig.ORBIT_RADIAL_KD))
                    .coerceIn(-effectiveOrbitSpeed * 0.5, effectiveOrbitSpeed * 0.5)

                val bearingRad = Math.toRadians(bearingFromCenter)
                val tangentialNorth = -sin(bearingRad) * effectiveOrbitSpeed
                val tangentialEast = cos(bearingRad) * effectiveOrbitSpeed
                val radialNorth = cos(bearingRad) * radialSpeed
                val radialEast = sin(bearingRad) * radialSpeed

                // 歸一化：保持恆定 effectiveOrbitSpeed 大小，只調整方向（ramp 期間也正確）
                // 徑向修正最多 ±50% × effectiveOrbitSpeed，未歸一化會導致速度脈衝 → 錄影抖動
                val combinedNorthRaw = tangentialNorth + radialNorth
                val combinedEastRaw = tangentialEast + radialEast
                val combinedSpeedRaw = sqrt(combinedNorthRaw * combinedNorthRaw + combinedEastRaw * combinedEastRaw)
                val scale = if (combinedSpeedRaw > AppConfig.MAX_FLIGHT_SPEED_MAX) AppConfig.MAX_FLIGHT_SPEED_MAX / combinedSpeedRaw else 1.0
                val combinedNorth = combinedNorthRaw * scale
                val combinedEast = combinedEastRaw * scale

                param.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
                val maxSpd = AppConfig.MAX_FLIGHT_SPEED_MAX.toDouble()
                param.pitch = combinedEast.coerceIn(-maxSpd, maxSpd)
                param.roll = combinedNorth.coerceIn(-maxSpd, maxSpd)

                // --- Yaw 控制：閉迴路，用實際 GPS 位置計算 bearingToCenter ---
                val bearingToCenter = (bearingFromCenter + 180.0) % 360.0
                val currentHeading = FlightManager.heading.value
                var yawError = bearingToCenter - currentHeading
                if (yawError > 180) yawError -= 360
                if (yawError < -180) yawError += 360
                yawIntegral = (yawIntegral + yawError * actualDt)
                    .coerceIn(-AppConfig.YAW_INTEGRAL_CLAMP, AppConfig.YAW_INTEGRAL_CLAMP)
                val rawBearingRate = angleDelta / actualDt
                val aBr = AppConfig.ORBIT_BEARING_RATE_ALPHA
                smoothedBearingRate = if (smoothedBearingRate != null) aBr * rawBearingRate + (1 - aBr) * smoothedBearingRate!! else rawBearingRate
                val ffGain = AppConfig.ORBIT_YAW_FEEDFORWARD_GAIN
                val feedforward = smoothedBearingRate!! * AppConfig.ORBIT_YAW_PREDICTION_SEC * ffGain
                // 視覺輔助 Yaw 修正（C 模式螺旋上升）
                val visualYawCorrection = VisualYawAssist.getCorrection()
                val manualYawOffset = AppConfig.getVisualYawOffset()
                param.yaw = (yawError * AppConfig.ORBIT_YAW_KP + yawIntegral * AppConfig.ORBIT_YAW_KI + feedforward + visualYawCorrection + manualYawOffset)
                    .coerceIn(-AppConfig.YAW_OUTPUT_CLAMP, AppConfig.YAW_OUTPUT_CLAMP)

                // --- 垂直控制（爬升率 × ramp + P 修正，確保高度與角度同步）---
                val altError = targetAlt - currentAlt
                val correction = altError * AppConfig.SPIRAL_ALT_KP
                param.verticalThrottle = ((climbRate * rampFactor) + correction)
                    .coerceIn(-AppConfig.SPIRAL_MAX_CLIMB_RATE.toDouble(), AppConfig.SPIRAL_MAX_CLIMB_RATE.toDouble())

                // --- 動態雲台俯仰角（時間節流，非阻塞）---
                // 純幾何公式，offset 表暫時註解（三角修正後重新評估）
                val now = System.currentTimeMillis()
                if (now - lastGimbalUpdateTime >= AppConfig.SPIRAL_GIMBAL_UPDATE_INTERVAL_MS) {
                    val h = currentAlt - AppConfig.SPIRAL_GIMBAL_HEIGHT_THRESHOLD
                    val gimbalPitch = if (h <= 0) 0.0 else -Math.toDegrees(atan(h / orbitRadius))
                    lastGimbalUpdateTime = now
                    // 必須 fire-and-forget：GimbalManager.setPitchAngle() 內含 pollUntilPitch()
                    // 會阻塞長達 2-10 秒，若同步呼叫將導致 orbit 控制迴圈停擺，造成飄移/抖動
                    CoroutineScope(coroutineContext).launch {
                        try {
                            GimbalManager.setPitchAngle(gimbalPitch)
                        } catch (e: Exception) {
                            Log.w(TAG, "Gimbal async update failed: ${e.message}")
                        }
                    }
                }

                // 指令低通濾波：平滑 pitch/roll/yaw 輸出，減少電機高頻震動
                // yaw 使用較高 alpha（0.8），避免 EMA 導致 PI 積分偏高
                OrbitHelpers.applySmoothing(cmdSmooth, param.pitch, param.roll, param.yaw, AppConfig.CMD_SMOOTH_YAW_ALPHA)
                param.pitch = cmdSmooth.pitch; param.roll = cmdSmooth.roll; param.yaw = cmdSmooth.yaw

                // 發送指令
                VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)

                // 週期性 log
                if (loopIteration % 100 == 0) {
                    AppLog.d(TAG, "Spiral: angle=${"%.1f".format(totalAngle)}/${"%.0f".format(totalAngleTarget)}° " +
                        "alt=${"%.1f".format(currentAlt)}/${"%.1f".format(zMax)}m " +
                        "progress=${"%.0f".format(progress * 100)}%")
                }
            }

            // Phase 6: 繼續繞行弧段到 final_heading（固定半徑、固定高度 zMax，錄影中）
            if (finalHeading != null) {
                val arcStartTime = System.currentTimeMillis()
                var arcPrevRadialError: Double? = null
                var arcLastLoopTime = arcStartTime
                AppLog.d(TAG, "Arc extension: orbiting at fixed r=${"%.1f".format(orbitRadius)}m, zMax=${"%.1f".format(zMax)}m → bearing ${"%.1f".format(finalHeading)}°")
                while (coroutineContext.isActive) {
                    if (System.currentTimeMillis() - arcStartTime > AppConfig.ORBIT_TIMEOUT_MS) {
                        Log.w(TAG, "Arc extension timeout, proceeding")
                        break
                    }
                    val arcNowMs = System.currentTimeMillis()
                    val arcActualDt = ((arcNowMs - arcLastLoopTime) / 1000.0).coerceIn(0.04, 0.2)
                    arcLastLoopTime = arcNowMs
                    val current = FlightManager.getFilteredLocation()
                    if (current == null) {
                        delay(AppConfig.VS_DISABLE_SETTLE_DELAY_MS)
                        continue
                    }
                    val bearingFromCenter = GeoMath.calculateBearing(centerLat, centerLng, current.latitude, current.longitude)
                    var remaining = finalHeading - bearingFromCenter
                    if (remaining < 0) remaining += 360
                    // 到達目標角度（容差 ~12° = 370°門檻 - 360° + 2°對齊容差）
                    if (remaining < AppConfig.ORBIT_ANGLE_THRESHOLD - 360.0 + AppConfig.YAW_ALIGNMENT_TOLERANCE_DEG) {
                        AppLog.d(TAG, "Arc extension arrived: bearing=${"%.1f".format(bearingFromCenter)}°, target=${"%.1f".format(finalHeading)}°, remaining=${"%.1f".format(remaining)}°")
                        break
                    }

                    val distFromCenter = GeoMath.haversine(current.latitude, current.longitude, centerLat, centerLng)
                    val currentAlt = FlightManager.getCurrentAltitude() ?: zMax

                    // 固定半徑繞行：切線速度 + 徑向 PD 修正（無擴展、無 ramp）
                    val tangentialSpeedCtrl = orbitSpeed.coerceIn(AppConfig.MIN_FLIGHT_SPEED.toDouble(), AppConfig.MAX_FLIGHT_SPEED_MAX.toDouble())
                    val radialError = distFromCenter - orbitRadius
                    val radialDerivative = if (arcPrevRadialError != null) (radialError - arcPrevRadialError!!) / arcActualDt else 0.0
                    arcPrevRadialError = radialError
                    val radialSpeed = (-(radialError * AppConfig.ORBIT_KP) - (radialDerivative * AppConfig.ORBIT_RADIAL_KD))
                        .coerceIn(-tangentialSpeedCtrl * 0.5, tangentialSpeedCtrl * 0.5)

                    val bearingRad = Math.toRadians(bearingFromCenter)
                    val tangentialNorth = -sin(bearingRad) * tangentialSpeedCtrl
                    val tangentialEast = cos(bearingRad) * tangentialSpeedCtrl
                    val radialCorrNorth = cos(bearingRad) * radialSpeed
                    val radialCorrEast = sin(bearingRad) * radialSpeed
                    // 歸一化：保持恆定 tangentialSpeedCtrl 大小，只調整方向
                    val combinedNorthRaw = tangentialNorth + radialCorrNorth
                    val combinedEastRaw = tangentialEast + radialCorrEast
                    val combinedSpeedRaw = sqrt(combinedNorthRaw * combinedNorthRaw + combinedEastRaw * combinedEastRaw)
                    val scale = if (combinedSpeedRaw > 0.01) tangentialSpeedCtrl / combinedSpeedRaw else 1.0
                    val combinedNorth = combinedNorthRaw * scale
                    val combinedEast = combinedEastRaw * scale

                    param.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
                    val maxSpd = AppConfig.MAX_FLIGHT_SPEED_MAX.toDouble()
                    param.pitch = combinedEast.coerceIn(-maxSpd, maxSpd)
                    param.roll = combinedNorth.coerceIn(-maxSpd, maxSpd)

                    // Yaw 控制：完整 PI + feedforward + 視覺 + 手動，鎖定中心（同 spiral）
                    var angleDelta = 0.0
                    if (prevBearingFromCenter != null) {
                        angleDelta = bearingFromCenter - prevBearingFromCenter!!
                        if (angleDelta > 180) angleDelta -= 360
                        if (angleDelta < -180) angleDelta += 360
                    }
                    prevBearingFromCenter = bearingFromCenter

                    val bearingToCenter = (bearingFromCenter + 180.0) % 360.0
                    val currentHeading = FlightManager.heading.value
                    var yawError = bearingToCenter - currentHeading
                    if (yawError > 180) yawError -= 360
                    if (yawError < -180) yawError += 360
                    yawIntegral = (yawIntegral + yawError * arcActualDt)
                        .coerceIn(-AppConfig.YAW_INTEGRAL_CLAMP, AppConfig.YAW_INTEGRAL_CLAMP)
                    val rawBearingRate = angleDelta / arcActualDt
                    val aBr = AppConfig.ORBIT_BEARING_RATE_ALPHA
                    smoothedBearingRate = if (smoothedBearingRate != null) aBr * rawBearingRate + (1 - aBr) * smoothedBearingRate!! else rawBearingRate
                    val feedforward = smoothedBearingRate!! * AppConfig.ORBIT_YAW_PREDICTION_SEC * AppConfig.ORBIT_YAW_FEEDFORWARD_GAIN
                    val visualYawCorrection = VisualYawAssist.getCorrection()
                    val manualYawOffset = AppConfig.getVisualYawOffset()
                    param.yaw = (yawError * AppConfig.ORBIT_YAW_KP + yawIntegral * AppConfig.ORBIT_YAW_KI + feedforward + visualYawCorrection + manualYawOffset)
                        .coerceIn(-AppConfig.YAW_OUTPUT_CLAMP, AppConfig.YAW_OUTPUT_CLAMP)

                    // 垂直：固定 zMax，僅 P 修正維持高度
                    val altError = zMax - currentAlt
                    param.verticalThrottle = (altError * AppConfig.SPIRAL_ALT_KP)
                        .coerceIn(-AppConfig.SPIRAL_MAX_CLIMB_RATE.toDouble(), AppConfig.SPIRAL_MAX_CLIMB_RATE.toDouble())

                    // 指令低通濾波：平滑 pitch/roll/yaw 輸出
                    OrbitHelpers.applySmoothing(cmdSmooth, param.pitch, param.roll, param.yaw, AppConfig.CMD_SMOOTH_YAW_ALPHA)
                    param.pitch = cmdSmooth.pitch; param.roll = cmdSmooth.roll; param.yaw = cmdSmooth.yaw

                    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                    delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
                }
                AppLog.d(TAG, "Arc extension complete")
            }

            // Phase 7: 漸進減速至停止 + 穩定暫停，避免急煞造成錄影抖動
            // 保留最後一幀的 pitch/roll/yaw 作為減速起點（已通過 EMA 平滑）
            val lastPitch = param.pitch
            val lastRoll = param.roll
            val lastYaw = param.yaw
            val decelSteps = (AppConfig.GIMBAL_PULL_UP_DECELERATION_SEC * 1000 / AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS).toInt()
            AppLog.d(TAG, "Spiral: decelerating smoothly over ${AppConfig.GIMBAL_PULL_UP_DECELERATION_SEC}s (${decelSteps} steps) " +
                "from p=${"%.2f".format(lastPitch)} r=${"%.2f".format(lastRoll)} y=${"%.2f".format(lastYaw)}")
            for (i in decelSteps downTo 1) {
                val factor = i.toDouble() / decelSteps
                param.pitch = lastPitch * factor
                param.roll = lastRoll * factor
                param.yaw = lastYaw * factor
                param.verticalThrottle = 0.0
                param.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
                VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
            }
            // 歸零 + 穩定暫停，確保完全靜止後再開始雲台拉平
            VirtualStickController.sendZeroCommand(param)
            delay(AppConfig.GIMBAL_PULL_UP_SETTLE_PAUSE_MS)

            // Phase 8: 雲台緩慢拉平（錄影持續中，電影感過渡）
            MissionStateMachine.setState(MissionStateMachine.MissionState.GIMBAL_PULL_UP)
            val currentPitch = GimbalManager.getCurrentPitch()
            AppLog.d(TAG, "Gimbal pull-up: ${"%.1f".format(currentPitch)}° → 0° over ${AppConfig.GIMBAL_PULL_UP_DURATION_SEC}s (recording)")
            GimbalManager.setPitchAngle(0.0, AppConfig.GIMBAL_PULL_UP_DURATION_SEC)
            GimbalManager.waitForPitchReady(0.0)
            AppLog.d(TAG, "Gimbal pull-up complete")

            // Phase 9: 定格瞬間（錄影中），再停止錄影
            AppLog.d(TAG, "Holding for ${AppConfig.GIMBAL_PULL_UP_HOLD_SEC}s before stopping recording")
            delay((AppConfig.GIMBAL_PULL_UP_HOLD_SEC * 1000).toLong())

            // Phase 10: 停止錄影
            suspendCancellableCoroutine<Unit> { cont ->
                CameraManager.stopVideoRecord(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        AppLog.d(TAG, "Spiral recording stopped")
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                    override fun onFailure(error: IDJIError) {
                        Log.e(TAG, "Spiral recording stop failed: $error")
                        if (cont.isActive) cont.resumeWith(Result.failure(Exception("Spiral recording stop failed: $error")))
                    }
                })
            }

            param.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
            AppLog.d(TAG, "Spiral ascent complete: totalAngle=${"%.1f".format(totalAngle)}°, " +
                "finalAlt=${"%.1f".format(FlightManager.getCurrentAltitude() ?: 0.0)}m")
        }

}
