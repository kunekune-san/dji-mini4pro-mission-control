package dji.sampleV5.aircraft.mission

import android.util.Log
import dji.sampleV5.aircraft.util.AppLog
import dji.sampleV5.aircraft.config.AppConfig
import dji.sampleV5.aircraft.dji.FlightManager
import dji.sampleV5.aircraft.mqtt.MqttClientManager
import dji.sampleV5.aircraft.mqtt.MqttTopics
import dji.sampleV5.aircraft.safety.SafetyAnomalyType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dji.sdk.keyvalue.value.flightcontroller.FlightControlAuthorityChangeReason
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/** VirtualStick 生命週期管理：啟用、停用、狀態監聽、歸零指令 */
internal object VirtualStickController {

    private const val TAG = "VSController"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Volatile private var vsListenerSet = false
    private var vsListener: VirtualStickStateListener? = null
    @Volatile private var lastVSState: VirtualStickState? = null
    @Volatile private var lastVSError: String? = null

    /** 取得最近一次 VS 錯誤碼（用於 RC 模式判斷） */
    fun getLastVSError(): String? = lastVSError

    /** 啟用 VirtualStick，最多重試 3 次 */
    suspend fun enableWithRetry(): Boolean {
        lastVSError = null
        setupStateListener()
        repeat(3) { attempt ->
            var errorString: String? = null
            var enableFailed = false
            suspendCancellableCoroutine<Unit> { cont ->
                VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        AppLog.d(TAG, "enableVirtualStick callback success")
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                    override fun onFailure(error: IDJIError) {
                        errorString = error.toString()
                        enableFailed = true
                        Log.w(TAG, "enableVirtualStick callback failed: $error")
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                })
            }

            // RC 模式錯誤：立即失敗，不重試
            if (errorString != null && errorString!!.contains("CONTROL_AUTH_RC_NOT_P_MODE")) {
                lastVSError = "RC_NOT_N_MODE"
                Log.e(TAG, "VirtualStick failed: RC not in N mode (C/S mode). Please switch to N mode.")
                return false
            }

            // SDK 回調失敗：不依賴 lastVSState，直接重試
            if (enableFailed) {
                Log.w(TAG, "VirtualStick enable attempt ${attempt + 1} failed (${errorString ?: "unknown"}), retrying...")
                delay(AppConfig.VS_RETRY_DELAY_MS)
                return@repeat
            }

            // SDK 回調成功：等待 listener 更新 lastVSState 後驗證
            delay(AppConfig.VS_RETRY_DELAY_MS)

            val vsEnabled = lastVSState?.isVirtualStickEnable ?: false
            if (vsEnabled) {
                VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
                delay(AppConfig.VS_ADVANCED_MODE_DELAY_MS)
                val advEnabled = lastVSState?.isVirtualStickAdvancedModeEnabled ?: false
                if (advEnabled) {
                    AppLog.d(TAG, "VirtualStick + AdvancedMode enabled on attempt ${attempt + 1}")
                    return true
                }
            }
            Log.w(TAG, "VirtualStick enable attempt ${attempt + 1} failed, retrying...")
        }
        Log.e(TAG, "VirtualStick enable failed after 3 attempts")
        return false
    }

    /** 安全停用 VirtualStick：先歸零所有控制參數再關閉。回傳是否成功禁用 */
    suspend fun safeDisable(): Boolean {
        // 連續發送 3 次零指令，確保飛控接收
        val param = VirtualStickParams.groundVelocityParams()
        param.pitch = 0.0; param.roll = 0.0; param.yaw = 0.0; param.verticalThrottle = 0.0
        repeat(3) {
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
            delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
        }
        delay(AppConfig.VS_DISABLE_DELAY_MS)

        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
        delay(AppConfig.VS_DISABLE_SETTLE_DELAY_MS)

        // 嘗試 disable，最多重試 2 次
        var success = false
        repeat(2) { attempt ->
            try {
                withTimeout(AppConfig.VS_DISABLE_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {
                                AppLog.d(TAG, "VirtualStick disabled safely (attempt ${attempt + 1})")
                                success = true
                                if (cont.isActive) cont.resume(Unit) {}
                            }
                            override fun onFailure(error: IDJIError) {
                                Log.e(TAG, "disableVirtualStick failed (attempt ${attempt + 1}): $error")
                                if (cont.isActive) cont.resume(Unit) {}
                            }
                        })
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "disableVirtualStick timeout (attempt ${attempt + 1}, ${AppConfig.VS_DISABLE_TIMEOUT_MS}ms)")
            }
            if (success) return@repeat
            delay(500)
        }

        // 只在成功 disable 後才移除 listener
        if (success) {
            removeStateListener()
            lastVSState = null
            AppLog.d(TAG, "lastVSState reset to null after disable")
        } else {
            Log.w(TAG, "safeDisable failed after retries — VS may still be enabled, listener kept")
            MissionStateMachine.logMissionEvent(SafetyAnomalyType.VIRTUAL_STICK_FAILED, "safeDisable failed after retries")
        }
        return success
    }

    /**
     * 安全檢查：是否應該發送 VirtualStick 指令。
     *
     * 在每個 VS 控制迴圈中呼叫，如果回傳 false 應立即停止發送。
     * 條件：無人機仍在飛行中。
     */
    fun shouldSendCommand(): Boolean {
        return FlightManager.isFlying.value
    }

    /** 發送歸零控制指令（所有軸速度為 0，連續發送 3 次確保接收） */
    suspend fun sendZeroCommand(param: VirtualStickFlightControlParam) {
        param.pitch = 0.0; param.roll = 0.0; param.yaw = 0.0; param.verticalThrottle = 0.0
        repeat(3) {
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
            delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
        }
    }

    /** 註冊 VirtualStick 狀態監聽器，偵測控制權丟失 */
    private fun setupStateListener() {
        if (vsListenerSet) return
        val listener = object : VirtualStickStateListener {
            override fun onVirtualStickStateUpdate(stickState: VirtualStickState) {
                lastVSState = stickState
                if (!stickState.isVirtualStickEnable) {
                    val currentState = MissionStateMachine.state.value
                    if (currentState == MissionStateMachine.MissionState.IDLE ||
                        currentState == MissionStateMachine.MissionState.ERROR ||
                        currentState == MissionStateMachine.MissionState.RETURNING_HOME ||
                        currentState == MissionStateMachine.MissionState.SHOOTING_PHOTOS ||
                        currentState == MissionStateMachine.MissionState.DESCENDING_TO_ORBIT_ALT ||
                        currentState == MissionStateMachine.MissionState.ORBITING ||
                        currentState == MissionStateMachine.MissionState.SPIRAL_ASCENDING ||
                        currentState == MissionStateMachine.MissionState.FLYING_TO_NEXT) {
                        AppLog.d(TAG, "VirtualStick released during $currentState, normal")
                        return
                    }
                    Log.w(TAG, "VirtualStick control lost during $currentState! Authority: ${stickState.currentFlightControlAuthorityOwner}")
                    MqttClientManager.publish(MqttTopics.STATUS, """{"state":"ERROR","reason":"VirtualStick control lost"}""")
                    scope.launch { MissionStateMachine.cancelMission(reason = "VirtualStick control lost during $currentState") }
                }
            }
            override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
                Log.w(TAG, "Flight control authority changed: $reason")
                if (reason != FlightControlAuthorityChangeReason.MSDK_REQUEST && MissionStateMachine.state.value != MissionStateMachine.MissionState.IDLE) {
                    scope.launch {
                        MqttClientManager.publish(MqttTopics.STATUS, """{"state":"WARNING","reason":"Authority changed: $reason"}""")
                    }
                }
            }
        }
        vsListener = listener
        VirtualStickManager.getInstance().setVirtualStickStateListener(listener)
        vsListenerSet = true
        AppLog.d(TAG, "VirtualStickStateListener registered")
    }

    /** 移除 VirtualStick 狀態監聽器 */
    internal fun removeStateListener() {
        if (vsListenerSet) {
            vsListener?.let { VirtualStickManager.getInstance().removeVirtualStickStateListener(it) }
            vsListener = null
            vsListenerSet = false
            AppLog.d(TAG, "VirtualStickStateListener removed")
        }
    }
}
