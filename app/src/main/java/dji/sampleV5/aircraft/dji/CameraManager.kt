package dji.sampleV5.aircraft.dji

import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.value.camera.CameraFocusMode
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.set
import dji.v5.utils.common.LogUtils
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object CameraManager {

    private const val TAG = "CameraManager"

    /** 切換相機模式（true=拍照, false=錄影） */
    fun setCameraMode(isPhoto: Boolean) {
        val mode = if (isPhoto) CameraMode.PHOTO_NORMAL else CameraMode.VIDEO_NORMAL
        CameraKey.KeyCameraMode.create().set(mode)
        LogUtils.i(TAG, "Camera mode set to $mode")
    }

    /** 拍攝單張照片（需先切換到拍照模式） */
    fun takePhoto(callback: CommonCallbacks.CompletionCallback? = null) {
        CameraKey.KeyStartShootPhoto.create().action({
            LogUtils.i(TAG, "Photo taken successfully")
            callback?.onSuccess()
        }, { e: IDJIError ->
            LogUtils.e(TAG, "Take photo failed: $e")
            callback?.onFailure(e)
        })
    }

    /** 開始錄影（需先切換到錄影模式） */
    fun startVideoRecord(callback: CommonCallbacks.CompletionCallback? = null) {
        CameraKey.KeyStartRecord.create().action({
            LogUtils.i(TAG, "Video recording started")
            callback?.onSuccess()
        }, { e: IDJIError ->
            LogUtils.e(TAG, "Start recording failed: $e")
            callback?.onFailure(e)
        })
    }

    /** 觸發自動對焦（設定 AFC 模式並關閉 AE 鎖定） */
    suspend fun triggerAutoFocus() {
        suspendCancellableCoroutine<Unit> { cont ->
            CameraKey.KeyCameraFocusMode.create().set(CameraFocusMode.AFC, {
                LogUtils.i(TAG, "Focus mode set to AFC")
                CameraKey.KeyAELockEnabled.create().set(false, {
                    LogUtils.i(TAG, "AE lock disabled")
                    if (cont.isActive) cont.resume(Unit)
                }, { e: IDJIError ->
                    LogUtils.e(TAG, "Disable AE lock failed: $e")
                    if (cont.isActive) cont.resume(Unit)
                })
            }, { e: IDJIError ->
                LogUtils.e(TAG, "Set focus mode failed: $e")
                if (cont.isActive) cont.resume(Unit)
            })
        }
    }

    /** 停止錄影 */
    fun stopVideoRecord(callback: CommonCallbacks.CompletionCallback? = null) {
        CameraKey.KeyStopRecord.create().action({
            LogUtils.i(TAG, "Video recording stopped")
            callback?.onSuccess()
        }, { e: IDJIError ->
            LogUtils.e(TAG, "Stop recording failed: $e")
            callback?.onFailure(e)
        })
    }
}
