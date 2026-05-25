package dji.sampleV5.aircraft.dji

import android.util.Log
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.gimbal.CtrlInfo
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalSpeedRotation
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

object GimbalManager {

    private const val TAG = "GimbalManager"
    private const val ANGLE_TOLERANCE = 3.0       // degrees (slightly relaxed for hardware tolerance)
    private const val TIMEOUT_MS = 10000L
    private const val POLL_INTERVAL_MS = 100L
    private const val ROTATION_SPEED = 30.0        // degrees/sec for pitch (used in speed mode)

    private var currentPitch: Double = 0.0
    private var listenerRegistered = false

    /** 註冊雲台角度監聽器 */
    fun startListening() {
        if (listenerRegistered) return
        GimbalKey.KeyGimbalAttitude.create().listen(this) { attitude ->
            attitude?.let {
                currentPitch = it.pitch
            }
        }
        listenerRegistered = true
        Log.d(TAG, "Gimbal attitude listener registered")
    }

    /** 移除雲台角度監聽器 */
    fun stopListening() {
        KeyManager.getInstance().cancelListen(this)
        listenerRegistered = false
        Log.d(TAG, "Gimbal attitude listener removed")
    }

    /** 設定雲台俯仰角（絕對角度模式），回傳是否到達目標角度 */
    suspend fun setPitchAngle(angle: Double): Boolean {
        val clampedAngle = angle.coerceIn(-90.0, 60.0) // Mini 4 Pro actual range
        if (clampedAngle != angle) {
            Log.w(TAG, "Angle ${angle}° clamped to ${clampedAngle}° (valid: -90° to 60°)")
        }
        Log.d(TAG, "Sending pitch command: ${clampedAngle}° (absolute mode)")

        // Read current pitch
        val startPitch = try {
            GimbalKey.KeyGimbalAttitude.create().get(Attitude()).pitch
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get initial pitch, using cached: $currentPitch")
            currentPitch
        }
        Log.d(TAG, "Current pitch before command: ${startPitch}°")

        if (abs(startPitch - clampedAngle) < ANGLE_TOLERANCE) {
            Log.d(TAG, "Already at ${clampedAngle}° (current=${startPitch}°), skipping")
            return true
        }

        // Use KeyRotateByAngle with absolute mode
        val rotationParam = GimbalAngleRotation().apply {
            mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
            pitch = clampedAngle
            yaw = 0.0
            roll = 0.0
            duration = 2.0 // seconds
        }

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                GimbalKey.KeyRotateByAngle.create().action(rotationParam, {
                    Log.d(TAG, "Angle rotation command sent: target=${clampedAngle}° (absolute)")
                    if (cont.isActive) cont.resume(Unit) {}
                }, { e: IDJIError ->
                    Log.e(TAG, "Angle rotation failed: $e")
                    if (cont.isActive) cont.resume(Unit) {}
                })
            }
        }

        // Poll attitude until target reached
        val startTime = System.currentTimeMillis()
        while (coroutineContext.isActive) {
            val pitch = try {
                GimbalKey.KeyGimbalAttitude.create().get(Attitude()).pitch
            } catch (e: Exception) {
                currentPitch
            }
            currentPitch = pitch

            Log.d(TAG, "Polling pitch: current=${"%.1f".format(pitch)}°, target=${clampedAngle}°, diff=${"%.1f".format(abs(pitch - clampedAngle))}°")

            if (abs(pitch - clampedAngle) < ANGLE_TOLERANCE) {
                Log.d(TAG, "Pitch reached: ${"%.1f".format(pitch)}° (target=${clampedAngle}°)")
                return true
            }

            if (System.currentTimeMillis() - startTime > TIMEOUT_MS) {
                Log.e(TAG, "Pitch timeout! final=${"%.1f".format(pitch)}°, target=${clampedAngle}°")
                return false
            }

            delay(POLL_INTERVAL_MS)
        }

        return false
    }

    /** 等待雲台俯仰角到達目標角度（輪詢模式） */
    suspend fun waitForPitchReady(targetAngle: Double): Boolean {
        val clampedTarget = targetAngle.coerceIn(-90.0, 60.0) // Mini 4 Pro actual range
        Log.d(TAG, "Waiting pitch: target=${clampedTarget}°")
        val startTime = System.currentTimeMillis()
        while (coroutineContext.isActive) {
            val pitch = try {
                GimbalKey.KeyGimbalAttitude.create().get(Attitude()).pitch
            } catch (e: Exception) {
                currentPitch
            }

            Log.d(TAG, "Waiting pitch: current=${"%.1f".format(pitch)}°, target=${clampedTarget}°")

            if (abs(pitch - clampedTarget) < ANGLE_TOLERANCE) {
                Log.d(TAG, "Pitch reached: ${"%.1f".format(pitch)}°")
                return true
            }

            if (System.currentTimeMillis() - startTime > TIMEOUT_MS) {
                Log.e(TAG, "Pitch timeout! final=${"%.1f".format(pitch)}°, target=${clampedTarget}°")
                return false
            }

            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    /** 取得當前雲台俯仰角（度） */
    fun getCurrentPitch(): Double {
        return currentPitch
    }

    /** 停止雲台旋轉（速度模式歸零） */
    fun stopRotation() {
        val zeroParam = GimbalSpeedRotation(0.0, 0.0, 0.0, CtrlInfo())
        GimbalKey.KeyRotateBySpeed.create().action(zeroParam, {
            Log.d(TAG, "Gimbal rotation stopped")
        }, { e: IDJIError ->
            Log.e(TAG, "Failed to stop gimbal rotation: $e")
        })
    }
}
