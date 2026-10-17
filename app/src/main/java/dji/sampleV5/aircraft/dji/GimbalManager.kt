package dji.sampleV5.aircraft.dji

import android.util.Log
import dji.sampleV5.aircraft.util.AppLog
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

/** 雲台控制單例（角度設定、俯仰控制、輪詢到位確認） */
object GimbalManager {

    private const val TAG = "GimbalManager"
    private const val ANGLE_TOLERANCE = 3.0       // degrees (slightly relaxed for hardware tolerance)
    private const val TIMEOUT_MS = 10000L
    private const val POLL_INTERVAL_MS = 100L
    @Volatile private var currentPitch: Double = 0.0
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
        AppLog.d(TAG, "Gimbal attitude listener registered")
    }

    /** 移除雲台角度監聽器 */
    fun stopListening() {
        KeyManager.getInstance().cancelListen(this)
        listenerRegistered = false
        AppLog.d(TAG, "Gimbal attitude listener removed")
    }

    /** 設定雲台俯仰角（絕對角度模式，預設 2 秒過渡），回傳是否到達目標角度 */
    suspend fun setPitchAngle(angle: Double): Boolean = setPitchAngle(angle, 2.0)

    /** 設定雲台俯仰角（絕對角度模式，可指定過渡時間），回傳是否到達目標角度 */
    suspend fun setPitchAngle(angle: Double, durationSec: Double): Boolean {
        val clampedAngle = angle.coerceIn(-90.0, 60.0) // Mini 4 Pro 實際可調範圍
        if (clampedAngle != angle) {
            Log.w(TAG, "Angle ${angle}° clamped to ${clampedAngle}° (valid: -90° to 60°)")
        }
        AppLog.d(TAG, "Sending pitch command: ${clampedAngle}° (absolute mode, duration=${durationSec}s)")

        // 讀取目前雲台俯仰角
        val startPitch = try {
            GimbalKey.KeyGimbalAttitude.create().get(Attitude()).pitch
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get initial pitch, using cached: $currentPitch")
            currentPitch
        }
        AppLog.d(TAG, "Current pitch before command: ${startPitch}°")

        if (abs(startPitch - clampedAngle) < ANGLE_TOLERANCE) {
            AppLog.d(TAG, "Already at ${clampedAngle}° (current=${startPitch}°), skipping")
            return true
        }

        // 使用 KeyRotateByAngle 絕對角度模式
        val rotationParam = GimbalAngleRotation().apply {
            mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
            pitch = clampedAngle
            yaw = 0.0
            roll = 0.0
            duration = durationSec
        }

        withTimeout(TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Unit> { cont ->
                    GimbalKey.KeyRotateByAngle.create().action(rotationParam, {
                        AppLog.d(TAG, "Angle rotation command sent: target=${clampedAngle}° (absolute)")
                        if (cont.isActive) cont.resume(Unit) {}
                    }, { e: IDJIError ->
                        Log.e(TAG, "Angle rotation failed: $e")
                        if (cont.isActive) cont.resumeWith(Result.failure(Exception("Gimbal rotation failed: $e")))
                    })
                }
            }
        }

        return pollUntilPitch(clampedAngle)
    }

    /** 等待雲台俯仰角到達目標角度（輪詢模式） */
    suspend fun waitForPitchReady(targetAngle: Double): Boolean {
        val clampedTarget = targetAngle.coerceIn(-90.0, 60.0) // Mini 4 Pro 實際可調範圍
        AppLog.d(TAG, "Waiting pitch: target=${clampedTarget}°")
        return pollUntilPitch(clampedTarget)
    }

    /** 輪詢雲台直到俯仰角到達目標（±ANGLE_TOLERANCE）或超時 */
    private suspend fun pollUntilPitch(targetAngle: Double, timeoutMs: Long = TIMEOUT_MS): Boolean {
        val startTime = System.currentTimeMillis()
        var consecutiveErrors = 0
        while (coroutineContext.isActive) {
            val pitch = try {
                val p = GimbalKey.KeyGimbalAttitude.create().get(Attitude()).pitch
                consecutiveErrors = 0
                p
            } catch (e: Exception) {
                consecutiveErrors++
                if (consecutiveErrors >= 3) {
                    Log.e(TAG, "Poll failed ${consecutiveErrors}x consecutively, aborting")
                    return false
                }
                currentPitch
            }
            currentPitch = pitch

            AppLog.d(TAG, "Polling pitch: current=${"%.1f".format(pitch)}°, target=${targetAngle}°, diff=${"%.1f".format(abs(pitch - targetAngle))}°")

            if (abs(pitch - targetAngle) < ANGLE_TOLERANCE) {
                AppLog.d(TAG, "Pitch reached: ${"%.1f".format(pitch)}° (target=${targetAngle}°)")
                return true
            }

            if (System.currentTimeMillis() - startTime > timeoutMs) {
                Log.e(TAG, "Pitch timeout! final=${"%.1f".format(pitch)}°, target=${targetAngle}°")
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
            AppLog.d(TAG, "Gimbal rotation stopped")
        }, { e: IDJIError ->
            Log.e(TAG, "Failed to stop gimbal rotation: $e")
        })
    }
}
