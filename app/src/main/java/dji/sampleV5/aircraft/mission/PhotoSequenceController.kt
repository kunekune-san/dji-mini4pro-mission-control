package dji.sampleV5.aircraft.mission

import android.util.Log
import dji.sampleV5.aircraft.util.AppLog
import dji.sampleV5.aircraft.config.AppConfig
import dji.sampleV5.aircraft.dji.CameraManager
import dji.sampleV5.aircraft.dji.GimbalManager
import dji.sampleV5.aircraft.mqtt.MqttClientManager
import dji.sampleV5.aircraft.mqtt.MqttTopics
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/** 拍照序列控制：N 張照片序列（N-1 張環景 + 1 張天底） */
internal object PhotoSequenceController {

    private const val TAG = "PhotoSeqCtrl"

    /** 執行拍照序列（A 模式專用）：photoCount-1 張指定角度 + 1 張天底 */
    suspend fun executePhotoSequence(
        targetLat: Double,
        targetLng: Double,
        photoGimbalPitch: Double,
        photoCount: Int = AppConfig.DEFAULT_PHOTO_COUNT
    ) {
        val totalPhotos = photoCount.coerceAtLeast(2)
        val phase1Count = totalPhotos - 1
        MissionStateMachine.setState(MissionStateMachine.MissionState.SHOOTING_PHOTOS)
        VirtualStickController.safeDisable()
        AppLog.d(TAG, "VirtualStick disabled, waiting for MSDK state to settle...")
        delay(AppConfig.CAMERA_MODE_SWITCH_DELAY_MS)
        CameraManager.setCameraMode(true)
        delay(AppConfig.CAMERA_MODE_SWITCH_DELAY_MS)
        if (!VirtualStickController.enableWithRetry()) {
            throw Exception("Cannot re-enable VirtualStick for photos")
        }
        AppLog.d(TAG, "VirtualStick re-enabled for photo sequence")

        // Phase 1: (photoCount-1) 張照片，使用指定雲台俯仰角
        AppLog.d(TAG, "Phase 1: Moving gimbal to ${photoGimbalPitch}°")
        var phase1Reached = false
        repeat(3) { attempt ->
            GimbalManager.setPitchAngle(photoGimbalPitch)
            if (GimbalManager.waitForPitchReady(photoGimbalPitch)) {
                phase1Reached = true
                AppLog.d(TAG, "Gimbal reached ${photoGimbalPitch}° on attempt ${attempt + 1}")
                return@repeat
            }
            Log.w(TAG, "Gimbal ${photoGimbalPitch}° not reached on attempt ${attempt + 1}, retrying...")
            delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
        }
        if (!phase1Reached) {
            Log.e(TAG, "WARNING: Gimbal could not reach ${photoGimbalPitch}°, proceeding at current angle")
        }
        AppLog.d(TAG, "Starting $phase1Count-photo sequence at ${photoGimbalPitch}°")

        val yawPerPhoto = 360.0 / phase1Count
        repeat(phase1Count) { i ->
            FlightPhaseExecutor.rotateYawForPhoto(yawPerPhoto)
            delay(AppConfig.PHOTO_PRE_SHOT_DELAY_MS)
            suspendCancellableCoroutine<Unit> { cont ->
                CameraManager.takePhoto(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        AppLog.d(TAG, "Photo ${i + 1}/$totalPhotos taken at ${photoGimbalPitch}°")
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                    override fun onFailure(error: IDJIError) {
                        Log.e(TAG, "Photo ${i + 1}/$totalPhotos failed: $error")
                        if (cont.isActive) cont.resumeWith(Result.failure(Exception("Photo ${i + 1}/$totalPhotos failed: $error")))
                    }
                })
            }
            delay(AppConfig.PHOTO_INTER_SHOT_DELAY_MS)
            MqttClientManager.publish(MqttTopics.STATUS, """{"state":"SHOOTING_PHOTOS","phase":"${photoGimbalPitch}deg","photo":${i + 1},"total":$totalPhotos}""")
        }
        AppLog.d(TAG, "Phase 1 complete: $phase1Count photos at ${photoGimbalPitch}°")

        // Phase 2: 1 張正下方（nadir）照片，雲台 -90°
        AppLog.d(TAG, "Phase 2: Moving gimbal to -90° (nadir)")
        var nadirReached = false
        repeat(3) { attempt ->
            GimbalManager.setPitchAngle(-90.0)
            if (GimbalManager.waitForPitchReady(-90.0)) {
                nadirReached = true
                AppLog.d(TAG, "Gimbal reached -90° on attempt ${attempt + 1}")
                return@repeat
            }
            Log.w(TAG, "Gimbal -90° not reached on attempt ${attempt + 1}, retrying...")
            delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
        }
        if (!nadirReached) {
            Log.e(TAG, "WARNING: Gimbal could not reach -90°, taking photo at current angle anyway")
        }
        delay(AppConfig.PHOTO_PRE_SHOT_DELAY_MS)
        suspendCancellableCoroutine<Unit> { cont ->
            CameraManager.takePhoto(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.d(TAG, "Photo $totalPhotos/$totalPhotos taken at -90° (nadir)")
                    if (cont.isActive) cont.resume(Unit) {}
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Photo $totalPhotos/$totalPhotos failed: $error")
                    if (cont.isActive) cont.resumeWith(Result.failure(Exception("Photo $totalPhotos/$totalPhotos failed: $error")))
                }
            })
        }
        delay(AppConfig.PHOTO_INTER_SHOT_DELAY_MS)
        MqttClientManager.publish(MqttTopics.STATUS, """{"state":"SHOOTING_PHOTOS","phase":"nadir","photo":$totalPhotos,"total":$totalPhotos}""")
        AppLog.d(TAG, "Phase 2 complete: nadir photo taken")
        GimbalManager.setPitchAngle(0.0)
        GimbalManager.waitForPitchReady(0.0)
        AppLog.d(TAG, "Gimbal reset to 0°, photos complete")
        VirtualStickController.safeDisable()
        AppLog.d(TAG, "VirtualStick disabled after photo sequence")
    }
}
