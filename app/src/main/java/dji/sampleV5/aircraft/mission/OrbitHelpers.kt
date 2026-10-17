package dji.sampleV5.aircraft.mission

import dji.sampleV5.aircraft.util.AppLog
import dji.sampleV5.aircraft.config.AppConfig
import dji.sampleV5.aircraft.dji.FlightManager
import dji.sampleV5.aircraft.dji.GimbalManager
import dji.sampleV5.aircraft.util.GeoMath
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * OrbitController 共用輔助函式。
 *
 * 從 OrbitController.kt 提取，被所有模式（A/B/C/D）共用。
 */
internal object OrbitHelpers {

    const val TAG = "OrbitCtrl"

    /** 指令平滑化狀態：記錄上次實際發送的 pitch/roll/yaw */
    data class SmoothState(var pitch: Double = 0.0, var roll: Double = 0.0, var yaw: Double = 0.0, var initialized: Boolean = false)

    /** 對指令套用 EMA 低通濾波，首幀直接採用目標值 */
    fun applySmoothing(state: SmoothState, rawPitch: Double, rawRoll: Double, rawYaw: Double, yawAlpha: Double = AppConfig.getCmdSmoothAlpha()) {
        if (!state.initialized) {
            state.pitch = rawPitch; state.roll = rawRoll; state.yaw = rawYaw; state.initialized = true
        } else {
            val a = AppConfig.getCmdSmoothAlpha()
            state.pitch = a * rawPitch + (1 - a) * state.pitch
            state.roll = a * rawRoll + (1 - a) * state.roll
            state.yaw = yawAlpha * rawYaw + (1 - yawAlpha) * state.yaw
        }
    }

    /** 視覺輔助選取等待窗 */
    suspend fun waitForVisualTargetSelection() {
        VisualYawAssist.beginSelectionWindow()
        AppLog.d(TAG, "Visual target selection window: waiting ${AppConfig.VISUAL_SELECT_TIMEOUT_MS}ms")
        val selectStart = System.currentTimeMillis()
        while (coroutineContext.isActive &&
            System.currentTimeMillis() - selectStart < AppConfig.VISUAL_SELECT_TIMEOUT_MS) {
            if (VisualYawAssist.hasTarget()) {
                AppLog.d(TAG, "Visual target selected during selection window")
                break
            }
            delay(50)
        }
        if (!VisualYawAssist.hasTarget()) {
            AppLog.d(TAG, "No visual target selected, proceeding with GPS-only control")
        }
        VisualYawAssist.endSelectionWindow()
    }

    /** 視覺輔助 Yaw 居中：選取目標後旋轉直到目標在畫面中央 */
    suspend fun adjustYawToCenterTarget(param: VirtualStickFlightControlParam) {
        if (!VisualYawAssist.hasTarget()) return
        AppLog.d(TAG, "Adjusting yaw to center visual target")
        val adjustStart = System.currentTimeMillis()
        while (coroutineContext.isActive &&
            System.currentTimeMillis() - adjustStart < AppConfig.VISUAL_CENTER_TIMEOUT_MS) {
            val correction = VisualYawAssist.getCorrection()
            if (abs(correction) < AppConfig.VISUAL_CENTER_TOLERANCE * AppConfig.VISUAL_YAW_GAIN) {
                AppLog.d(TAG, "Visual target centered (correction=${"%.2f".format(correction)}°/s)")
                break
            }
            param.pitch = 0.0; param.roll = 0.0
            param.yaw = correction.coerceIn(-AppConfig.YAW_OUTPUT_CLAMP, AppConfig.YAW_OUTPUT_CLAMP)
            param.verticalThrottle = 0.0
            param.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
            delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
        }
        VirtualStickController.sendZeroCommand(param)
        delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
        AppLog.d(TAG, "Visual yaw adjustment complete")
    }

    /**
     * 視覺預選取：在外圍偏移點讓使用者選取目標後，飛到環繞起始點。
     * 被 B/C/D 模式共用。
     */
    suspend fun visualPreSelection(
        centerLat: Double, centerLng: Double, orbitRadius: Double,
        originalGimbalPitch: Double, param: VirtualStickFlightControlParam
    ) {
        val bearingToStart = GeoMath.calculateBearing(centerLat, centerLng,
            centerLat + (orbitRadius / 6371000.0) * Math.toDegrees(1.0), centerLng)

        val offsetDist = orbitRadius + AppConfig.VISUAL_PRESELECT_OFFSET_M
        val (offsetLat, offsetLng) = GeoMath.calculateDestinationPoint(
            centerLat, centerLng, bearingToStart, offsetDist)

        // Step 1: 飛到偏移點
        AppLog.d(TAG, "Visual pre-select: flying to offset point ${"%.6f".format(offsetLat)}, ${"%.6f".format(offsetLng)}")
        while (coroutineContext.isActive) {
            val current = FlightManager.getFilteredLocation() ?: break
            val dist = GeoMath.haversine(current.latitude, current.longitude, offsetLat, offsetLng)
            if (dist < AppConfig.ORBIT_POSITION_HOLD_TOLERANCE_M) break
            val bearing = GeoMath.calculateBearing(current.latitude, current.longitude, offsetLat, offsetLng)
            val bearingRad = Math.toRadians(bearing)
            val speed = maxOf(AppConfig.MIN_FLIGHT_SPEED,
                minOf((dist * AppConfig.getSpeedGain()).toFloat(), AppConfig.getMaxFlightSpeed()))
            param.pitch = (speed * kotlin.math.sin(bearingRad)).toDouble()
            param.roll = (speed * kotlin.math.cos(bearingRad)).toDouble()
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
        AppLog.d(TAG, "Visual pre-select: arrived at offset point")

        // Step 1.5: Yaw 穩定
        AppLog.d(TAG, "Visual pre-select: stabilizing yaw toward center")
        var yawStabilized = false
        val yawStabilizeStart = System.currentTimeMillis()
        while (coroutineContext.isActive &&
            System.currentTimeMillis() - yawStabilizeStart < AppConfig.ORBIT_YAW_STABILIZE_TIMEOUT_MS) {
            val loc = FlightManager.getFilteredLocation() ?: break
            val bearToCenter = GeoMath.calculateBearing(loc.latitude, loc.longitude, centerLat, centerLng)
            val hdg = FlightManager.heading.value
            var yErr = bearToCenter - hdg
            if (yErr > 180) yErr -= 360
            if (yErr < -180) yErr += 360
            if (abs(yErr) < AppConfig.YAW_STABILIZE_TOLERANCE_DEG) {
                AppLog.d(TAG, "Visual pre-select: yaw stabilized (err=${"%.1f".format(yErr)}°)")
                yawStabilized = true
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

        if (yawStabilized) {
            AppLog.d(TAG, "Visual pre-select: setting gimbal to ${AppConfig.VISUAL_PRESELECT_GIMBAL_PITCH}°")
            GimbalManager.setPitchAngle(AppConfig.VISUAL_PRESELECT_GIMBAL_PITCH)
            GimbalManager.waitForPitchReady(AppConfig.VISUAL_PRESELECT_GIMBAL_PITCH)
            delay(AppConfig.GIMBAL_SETTLE_DELAY_MS)
            waitForVisualTargetSelection()
            if (VisualYawAssist.hasTarget()) {
                adjustYawToCenterTarget(param)
            }
            AppLog.d(TAG, "Visual pre-select: restoring gimbal to ${originalGimbalPitch}°")
            GimbalManager.setPitchAngle(originalGimbalPitch)
            GimbalManager.waitForPitchReady(originalGimbalPitch)
            delay(AppConfig.GIMBAL_SETTLE_DELAY_MS)
        } else {
            AppLog.d(TAG, "Visual pre-select: yaw stabilization timeout, skipping visual selection (GPS-only)")
        }

        // Step 6: 飛到環繞起始點
        val startLat = centerLat + (orbitRadius / 6371000.0) * Math.toDegrees(1.0)
        val startLng = centerLng
        AppLog.d(TAG, "Visual pre-select: flying to orbit start ${"%.6f".format(startLat)}, ${"%.6f".format(startLng)}")
        while (coroutineContext.isActive) {
            val current = FlightManager.getFilteredLocation() ?: break
            val dist = GeoMath.haversine(current.latitude, current.longitude, startLat, startLng)
            if (dist < AppConfig.ORBIT_POSITION_HOLD_TOLERANCE_M) break
            val bearing = GeoMath.calculateBearing(current.latitude, current.longitude, startLat, startLng)
            val bearingRad = Math.toRadians(bearing)
            val speed = maxOf(AppConfig.MIN_FLIGHT_SPEED,
                minOf((dist * AppConfig.getSpeedGain()).toFloat(), AppConfig.getMaxFlightSpeed()))
            param.pitch = (speed * kotlin.math.sin(bearingRad)).toDouble()
            param.roll = (speed * kotlin.math.cos(bearingRad)).toDouble()
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
        AppLog.d(TAG, "Visual pre-select: arrived at orbit start, pre-selection complete")
    }
}
