package dji.sampleV5.aircraft.dji

import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.value.camera.CameraFocusMode
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.VideoResolutionFrameRate
import dji.sdk.keyvalue.value.camera.VideoFileFormat
import dji.sdk.keyvalue.value.camera.VideoStandard
import dji.sdk.keyvalue.value.common.DoublePoint2D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.DJICommonError
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.set
import dji.v5.utils.common.LogUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** 相機控制單例（模式切換、拍照、錄影、對焦） */
object CameraManager {

    private const val TAG = "CameraManager"
    private val isBusy = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var busyResetJob: Job? = null

    /** 切換相機模式（true=拍照, false=錄影） */
    fun setCameraMode(isPhoto: Boolean, callback: CommonCallbacks.CompletionCallback? = null) {
        val mode = if (isPhoto) CameraMode.PHOTO_NORMAL else CameraMode.VIDEO_NORMAL
        CameraKey.KeyCameraMode.create().set(mode, {
            LogUtils.i(TAG, "Camera mode set to $mode")
            callback?.onSuccess()
        }, { e: IDJIError ->
            LogUtils.e(TAG, "Set camera mode failed: $e")
            callback?.onFailure(e)
        })
    }

    /** 拍攝單張照片（需先切換到拍照模式） */
    fun takePhoto(callback: CommonCallbacks.CompletionCallback? = null) {
        if (!isBusy.compareAndSet(false, true)) {
            LogUtils.w(TAG, "Camera busy, takePhoto rejected")
            callback?.onFailure(DJICommonError.FACTORY.build("Camera busy"))
            return
        }
        busyResetJob?.cancel()
        busyResetJob = scope.launch {
            delay(10_000L)
            if (isBusy.compareAndSet(true, false)) {
                LogUtils.w(TAG, "isBusy timeout reset (10s) — SDK callback may have been lost")
            }
        }
        CameraKey.KeyStartShootPhoto.create().action({
            busyResetJob?.cancel()
            isBusy.set(false)
            LogUtils.i(TAG, "Photo taken successfully")
            callback?.onSuccess()
        }, { e: IDJIError ->
            busyResetJob?.cancel()
            isBusy.set(false)
            LogUtils.e(TAG, "Take photo failed: $e")
            callback?.onFailure(e)
        })
    }

    /** 開始錄影（需先切換到錄影模式） */
    fun startVideoRecord(callback: CommonCallbacks.CompletionCallback? = null) {
        if (!isBusy.compareAndSet(false, true)) {
            LogUtils.w(TAG, "Camera busy, startVideoRecord rejected")
            callback?.onFailure(DJICommonError.FACTORY.build("Camera busy"))
            return
        }
        busyResetJob?.cancel()
        busyResetJob = scope.launch {
            delay(10_000L)
            if (isBusy.compareAndSet(true, false)) {
                LogUtils.w(TAG, "isBusy timeout reset (10s) — SDK callback may have been lost")
            }
        }
        CameraKey.KeyStartRecord.create().action({
            busyResetJob?.cancel()
            isBusy.set(false)
            LogUtils.i(TAG, "Video recording started")
            callback?.onSuccess()
        }, { e: IDJIError ->
            busyResetJob?.cancel()
            isBusy.set(false)
            LogUtils.e(TAG, "Start recording failed: $e")
            callback?.onFailure(e)
        })
    }

    /** 觸發自動對焦：設定 AFC 模式 → 關閉 AE 鎖定 → 觸發中央對焦點，5 秒超時 */
    suspend fun triggerAutoFocus() {
        withTimeout(5000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                CameraKey.KeyCameraFocusMode.create().set(CameraFocusMode.AFC, {
                    LogUtils.i(TAG, "Focus mode set to AFC")
                    CameraKey.KeyAELockEnabled.create().set(false, {
                        LogUtils.i(TAG, "AE lock disabled")
                        // 觸發中央對焦點 (0.5, 0.5)
                        CameraKey.KeyCameraFocusTarget.create().set(DoublePoint2D(0.5, 0.5), {
                            LogUtils.i(TAG, "Focus target set to center")
                            if (cont.isActive) cont.resume(Unit)
                        }, { e: IDJIError ->
                            LogUtils.e(TAG, "Set focus target failed: $e")
                            if (cont.isActive) cont.resumeWith(Result.failure(Exception("Set focus target failed: $e")))
                        })
                    }, { e: IDJIError ->
                        LogUtils.e(TAG, "Disable AE lock failed: $e")
                        if (cont.isActive) cont.resumeWith(Result.failure(Exception("Disable AE lock failed: $e")))
                    })
                }, { e: IDJIError ->
                    LogUtils.e(TAG, "Set focus mode failed: $e")
                    if (cont.isActive) cont.resumeWith(Result.failure(Exception("Set focus mode failed: $e")))
                })
            }
        }
    }

    // ========== 錄影格式設定 ==========

    /** 取得當前錄影解析度與幀率 */
    fun getVideoResolutionFrameRate(onSuccess: (VideoResolutionFrameRate?) -> Unit, onFailure: (IDJIError) -> Unit) {
        CameraKey.KeyVideoResolutionFrameRate.create().get(onSuccess, onFailure)
    }

    /** 設定錄影解析度與幀率 */
    fun setVideoResolutionFrameRate(resolutionFrameRate: VideoResolutionFrameRate, callback: CommonCallbacks.CompletionCallback? = null) {
        CameraKey.KeyVideoResolutionFrameRate.create().set(resolutionFrameRate, {
            LogUtils.i(TAG, "Video resolution/frame rate set to $resolutionFrameRate")
            callback?.onSuccess()
        }, { e: IDJIError ->
            LogUtils.e(TAG, "Set video resolution/frame rate failed: $e")
            callback?.onFailure(e)
        })
    }

    /** 取得當前錄影檔格式（MP4/MOV） */
    fun getVideoFileFormat(onSuccess: (VideoFileFormat?) -> Unit, onFailure: (IDJIError) -> Unit) {
        CameraKey.KeyVideoFileFormat.create().get(onSuccess, onFailure)
    }

    /** 設定錄影檔格式 */
    fun setVideoFileFormat(format: VideoFileFormat, callback: CommonCallbacks.CompletionCallback? = null) {
        CameraKey.KeyVideoFileFormat.create().set(format, {
            LogUtils.i(TAG, "Video file format set to $format")
            callback?.onSuccess()
        }, { e: IDJIError ->
            LogUtils.e(TAG, "Set video file format failed: $e")
            callback?.onFailure(e)
        })
    }

    /** 取得當前錄影標準（PAL/NTSC） */
    fun getVideoStandard(onSuccess: (VideoStandard?) -> Unit, onFailure: (IDJIError) -> Unit) {
        CameraKey.KeyVideoStandard.create().get(onSuccess, onFailure)
    }

    /** 設定錄影標準（PAL/NTSC） */
    fun setVideoStandard(standard: VideoStandard, callback: CommonCallbacks.CompletionCallback? = null) {
        CameraKey.KeyVideoStandard.create().set(standard, {
            LogUtils.i(TAG, "Video standard set to $standard")
            callback?.onSuccess()
        }, { e: IDJIError ->
            LogUtils.e(TAG, "Set video standard failed: $e")
            callback?.onFailure(e)
        })
    }

    /** 停止錄影 */
    fun stopVideoRecord(callback: CommonCallbacks.CompletionCallback? = null) {
        CameraKey.KeyStopRecord.create().action({
            isBusy.set(false)
            LogUtils.i(TAG, "Video recording stopped")
            callback?.onSuccess()
        }, { e: IDJIError ->
            isBusy.set(false)
            LogUtils.e(TAG, "Stop recording failed: $e")
            callback?.onFailure(e)
        })
    }
}
