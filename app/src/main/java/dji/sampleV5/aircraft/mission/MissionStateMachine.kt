package dji.sampleV5.aircraft.mission

import android.util.Log
import dji.sampleV5.aircraft.util.AppLog
import dji.sampleV5.aircraft.BuildConfig
import dji.sampleV5.aircraft.config.AppConfig
import org.json.JSONObject
import dji.sampleV5.aircraft.util.GeoMath
import dji.sampleV5.aircraft.dji.CameraManager
import dji.sampleV5.aircraft.dji.DownloadResult
import dji.sampleV5.aircraft.dji.FlightManager
import dji.sampleV5.aircraft.dji.GimbalManager
import dji.sampleV5.aircraft.dji.MediaDownloadManager
import dji.sampleV5.aircraft.mqtt.AckPayload
import dji.sampleV5.aircraft.mqtt.MqttClientManager
import dji.sampleV5.aircraft.mqtt.MqttTopics
import dji.sampleV5.aircraft.mqtt.MqttConnectionEventBus
import dji.sampleV5.aircraft.mqtt.MqttStatusReporter
import dji.sampleV5.aircraft.mqtt.StopDefinition
import dji.sampleV5.aircraft.safety.SafetyAnomalyRecord
import dji.sampleV5.aircraft.safety.SafetyAnomalyType
import dji.sampleV5.aircraft.safety.SafetyEventLogger
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 任務狀態機，管理任務生命週期（IDLE → 執行中 → RETURNING_HOME → IDLE）。支援 A/B/C/D 四種任務模式與 Multi-Stop 多站任務 */
object MissionStateMachine {

    private const val TAG = "MissionSM"

    // ========== State ==========
    /** 任務狀態列舉 */
    enum class MissionState {
        IDLE, TAKING_OFF, FLYING_TO_TARGET,
        DESCENDING_TO_ORBIT_ALT, ORBITING, SPIRAL_ASCENDING,
        RECORDING_360, SHOOTING_PHOTOS, FLYING_TO_NEXT,
        GIMBAL_PULL_UP,
        RETURNING_HOME, ERROR
    }

    private val _state = MutableStateFlow(MissionState.IDLE)
    val state: StateFlow<MissionState> = _state

    private var missionJob: Job? = null
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val isCancelling = AtomicBoolean(false)
    private val landingHandled = AtomicBoolean(false)
    private var missionStartTime: Long = 0L

    // MQTT ack 追蹤：記錄待回報的指令 msgId 與 action，在落地/完成時發送最終 ack
    private var activeMissionMsgId: String? = null
    private var activeMissionAction: String? = null
    private var pendingCommandMsgId: String? = null
    private var pendingCommandAction: String? = null
    private val isDownloading = AtomicBoolean(false)

    // ========== Multi-Stop Mission ==========
    private data class MissionStop(
        val missionType: String,
        val targetLat: Double,
        val targetLng: Double,
        val targetAlt: Double,
        val orbitRadius: Double,
        val orbitAlt: Double?,
        val orbitGimbalPitch: Double,
        val photoGimbalPitch: Double,
        val maxAlt: Double? = null,
        val turns: Int? = null,
        val radiusEnd: Double? = null,
        val orbitSpeed: Double = 1.0,
        val finalHeading: Double? = null
    )

    private var multiStops: List<MissionStop> = emptyList()
    private var currentStopIndex: Int = 0
    private var isMultiStop: Boolean = false

    /** 多站進度 (current 1-based, total)，null 表示非多站任務 */
    private val _multiStopProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val multiStopProgress: StateFlow<Pair<Int, Int>?> = _multiStopProgress

    // ========== Safety Monitoring ==========
    private val safetyMonitor = MissionSafetyMonitor(
        getScope = { scope },
        getState = { _state.value },
        setState = { s, r -> setState(s, r) },
        onCancelMission = { reason -> cancelMission(reason = reason) },
        onResetToIdle = { resetToIdle() },
        isOnGround = { isDroneOnGround() },
        onCancelMissionJob = {
            missionJob?.cancel()
            missionJob = null
        },
        onDisableVS = {
            scope.launch {
                try { VirtualStickController.safeDisable() } catch (e: Exception) { Log.w(TAG, "Safety: safeDisable failed", e) }
            }
        }
    )

    /** @deprecated 改用 safetyMonitor.safetyEventLogger */
    var safetyEventLogger: SafetyEventLogger?
        get() = safetyMonitor.safetyEventLogger
        set(value) { safetyMonitor.safetyEventLogger = value }

    /**
     * 多重條件確認無人機是否在地面。
     *
     * **安全關鍵：** 任何重置 IDLE 的路徑都必須經過此檢查，
     * 絕對不能在空中停槳。
     *
     * 條件（全部滿足才回傳 true）：
     * 1. [FlightManager.isFlying] = false（SDK 回報）
     * 2. 馬達已停止（[FlightManager.isMotorsOn] = false，快取版）
     * 3. 高度 < 1.0m（防止 isFlying 延遲回報）
     */
    private fun isDroneOnGround(): Boolean {
        val flying = FlightManager.isFlying.value
        val motorsOn = FlightManager.isMotorsOn()
        val alt = FlightManager.altitude.value
        val onGround = !flying && !motorsOn && alt < 1.0
        if (!onGround) {
            AppLog.d(TAG, "isDroneOnGround: NOT on ground — flying=$flying, motorsOn=$motorsOn, alt=${"%.1f".format(alt)}m")
        }
        return onGround
    }

    // ========== Public API ==========

    /** 設定 Motor-Locked Callback（降落後的照片下載與狀態重置） */
    private fun setupPostLandingCallback(
        missionType: String,
        isMulti: Boolean = false,
        stopCount: Int = 1,
        photoCount: Int = AppConfig.MAX_DOWNLOAD_PHOTOS
    ) {
        if (isDownloading.get()) {
            Log.w(TAG, "[Mission] Warning: previous download still running, new mission may conflict")
            MqttClientManager.publish(MqttTopics.STATUS, """{"state":"WARNING","message":"Previous download still in progress"}""")
        }
        val label = if (isMulti) "Multi-stop" else missionType
        AppLog.d(TAG, "Setting motor-locked callback for $label")
        FlightManager.setOnMotorLockedCallback {
            if (!landingHandled.compareAndSet(false, true)) {
                AppLog.d(TAG, "Motor locked callback: already handled, ignoring")
                return@setOnMotorLockedCallback
            }
            scope.launch {
                try {
                    AppLog.d(TAG, "Motor locked callback fired! type=$label, isDownloading=${isDownloading.get()}")
                val shouldDownload = if (isMulti) multiStops.any { it.missionType != "B" && it.missionType != "C" && it.missionType != "D" } else missionType != "B" && missionType != "C" && missionType != "D"
                if (shouldDownload) {
                    isDownloading.set(true)
                    AppLog.d(TAG, "Motor locked, starting photo download...")
                    MqttClientManager.publish(MqttTopics.STATUS, """{"state":"DOWNLOADING","message":"Downloading mission photos..."}""")
                    delay(AppConfig.CAMERA_WRITE_DELAY_MS)
                    AppLog.d(TAG, "Delay complete, starting download...")
                    try {
                        val result = MediaDownloadManager.downloadLastNPhotos(photoCount)
                        when (result) {
                            is DownloadResult.Success -> {
                                AppLog.d(TAG, "Download complete: ${result.count} photos saved")
                                val extraStops = if (isMulti) ",\"stops\":$stopCount" else ""
                                MqttClientManager.publish(MqttTopics.STATUS, """{"state":"COMPLETE","photos":${result.count}$extraStops}""")
                            }
                            is DownloadResult.Failure -> {
                                Log.e(TAG, "Download failed: ${result.reason}")
                                MqttClientManager.publish(MqttTopics.STATUS, """{"state":"DOWNLOAD_ERROR","reason":"${result.reason}"}""")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Download exception: ${e.message}", e)
                        MqttClientManager.publish(MqttTopics.STATUS, """{"state":"DOWNLOAD_ERROR","reason":"${e.message}"}""")
                    }
                    isDownloading.set(false)
                } else {
                    AppLog.d(TAG, "Motor locked, $label complete (no photo download)")
                    val extraStops = if (isMulti) ",\"stops\":$stopCount" else ""
                    MqttClientManager.publish(MqttTopics.STATUS, """{"state":"COMPLETE","result":"orbit_video_done"$extraStops}""")
                }
                setState(MissionState.IDLE)
                // 發送任務完成最終 ack（若有 MQTT msgId）
                activeMissionMsgId?.let { mid ->
                    MqttClientManager.publishAck(AckPayload(
                        msgId = mid, action = activeMissionAction ?: "mission", status = "success"
                    ))
                }
                activeMissionMsgId = null
                activeMissionAction = null
                AppLog.d(TAG, "Mission complete")
                MqttClientManager.publish(MqttTopics.STATUS, """{"state":"IDLE","result":"success"}""")
                MqttStatusReporter.publishCurrentStatus()
                FlightManager.setOnMotorLockedCallback(null)
                if (isMultiStop) {
                    isMultiStop = false
                    _multiStopProgress.value = null
                }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 協程取消必須向上傳播，不可吞掉
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Motor-locked callback crashed: ${e.message}", e)
                    try { setState(MissionState.IDLE) } catch (_: Exception) {}
                    try { FlightManager.setOnMotorLockedCallback(null) } catch (_: Exception) {}
                    try { MqttClientManager.publish(MqttTopics.STATUS, """{"state":"IDLE","result":"error","note":"callback_crash"}""") } catch (_: Exception) {}
                }
            }
        }
    }

    /** 啟主任務流程：自動起飛 → 飛往目標 → 拍攝/環繞 → 返航降落 */
    fun startMission(
        targetLat: Double,
        targetLng: Double,
        targetAlt: Double = AppConfig.getSafeTakeoffHeight().toDouble(),
        missionType: String = "A",
        orbitRadius: Double = 0.0,
        orbitAlt: Double? = null,
        orbitGimbalPitch: Double = -45.0,
        photoGimbalPitch: Double = -45.0,
        photoCount: Int = AppConfig.DEFAULT_PHOTO_COUNT,
        maxAlt: Double? = null,
        turns: Int? = null,
        radiusEnd: Double? = null,
        orbitSpeed: Double = 1.0,
        finalHeading: Double? = null,
        mqttPayload: String? = null,
        msgId: String? = null
    ) {
        if (_state.value != MissionState.IDLE) {
            Log.w(TAG, "Mission already running, ignored")
            return
        }
        // 立即切換到 TAKING_OFF，避免並發的 startMission() 呼叫重複啟動
        setState(MissionState.TAKING_OFF)
        activeMissionMsgId = msgId
        activeMissionAction = "mission_start"
        safetyMonitor.lastMqttPayload = mqttPayload
        missionStartTime = System.currentTimeMillis()
        landingHandled.set(false)
        AppLog.d(TAG, "Mission start time recorded: $missionStartTime")

        setupPostLandingCallback(missionType, photoCount = photoCount)

        missionJob = scope.launch {
            try {
                // 重置 VirtualStick 狀態作為任務前置條件
                safeDisableVirtualStick()
                delay(AppConfig.VS_STATE_RESET_DELAY_MS)
                AppLog.d(TAG, "VirtualStick state reset complete")

                executeMission(targetLat, targetLng, targetAlt, missionType, orbitRadius, orbitAlt, orbitGimbalPitch, photoGimbalPitch, photoCount, maxAlt, turns, radiusEnd, orbitSpeed, finalHeading)
            } catch (e: CancellationException) {
                Log.w(TAG, "Mission coroutine cancelled")
                // 任務被取消：發送 cancelled ack，清除追蹤防止 post-landing 重複發送
                activeMissionMsgId?.let { mid ->
                    MqttClientManager.publishAck(AckPayload(
                        msgId = mid, action = activeMissionAction ?: "mission",
                        status = "failed", errorMsg = "Mission cancelled"
                    ))
                }
                activeMissionMsgId = null
                activeMissionAction = null
            } catch (e: Exception) {
                Log.e(TAG, "[Mission] startMission exception: ${e.message}", e)
                val errorReason = "Mission exception: ${e.message?.take(100)}"
                val errMsgId = activeMissionMsgId ?: "system"
                val errAction = activeMissionAction ?: "mission"
                if (VirtualStickController.getLastVSError() == "RC_NOT_N_MODE") {
                    MqttClientManager.publishAck(AckPayload(
                        msgId = errMsgId,
                        action = errAction,
                        status = "failed",
                        errorMsg = "Please switch RC to N (Normal) mode"
                    ))
                    Log.e(TAG, "Mission aborted: RC not in N mode")
                    setState(MissionState.ERROR)
                } else {
                    MqttClientManager.publishAck(AckPayload(
                        msgId = errMsgId,
                        action = errAction,
                        status = "failed",
                        errorMsg = e.message?.take(100) ?: "Unknown error"
                    ))
                    cancelMission(reason = errorReason)
                }
                // 清除追蹤防止 post-landing callback 重複發送 success ack
                activeMissionMsgId = null
                activeMissionAction = null
            }
        }
    }

    /** 啟動多站任務：起飛 → 依序執行各站任務 → 返航 */
    fun startMultiMission(stops: List<StopDefinition>, mqttPayload: String? = null, msgId: String? = null) {
        if (_state.value != MissionState.IDLE) {
            Log.w(TAG, "Mission already running, multi-stop ignored")
            return
        }
        // 立即切換到 TAKING_OFF，避免並發的 startMultiMission() 呼叫重複啟動
        setState(MissionState.TAKING_OFF)
        activeMissionMsgId = msgId
        activeMissionAction = "multi_start"
        safetyMonitor.lastMqttPayload = mqttPayload
        missionStartTime = System.currentTimeMillis()
        val safeMinAlt = AppConfig.getSafeTakeoffHeight().toDouble()
        multiStops = stops.map { stop ->
            MissionStop(
                missionType = stop.mission,
                targetLat = stop.target.lat,
                targetLng = stop.target.lng,
                targetAlt = max(stop.target.alt, safeMinAlt),
                orbitRadius = stop.orbit?.radius ?: 0.0,
                orbitAlt = stop.orbit?.alt,
                orbitGimbalPitch = stop.orbit?.gimbalPitch ?: -45.0,
                photoGimbalPitch = stop.orbit?.photoGimbalPitch ?: -45.0,
                maxAlt = stop.orbit?.maxAlt,
                turns = stop.orbit?.turns,
                radiusEnd = stop.orbit?.radiusEnd,
                orbitSpeed = stop.orbit?.orbitSpeed ?: 1.0,
                finalHeading = stop.orbit?.finalHeading
            )
        }
        currentStopIndex = 0
        isMultiStop = true
        _multiStopProgress.value = 1 to multiStops.size
        landingHandled.set(false)
        AppLog.d(TAG, "Multi-stop mission: ${multiStops.size} stops")

        // 計算所有拍照站點（非 B/C/D）的總照片數
        val totalPhotoCount = multiStops.count { it.missionType != "B" && it.missionType != "C" && it.missionType != "D" } * AppConfig.DEFAULT_PHOTO_COUNT
        AppLog.d(TAG, "Multi-stop total photo count: $totalPhotoCount (from ${multiStops.count { it.missionType != "B" && it.missionType != "C" && it.missionType != "D" }} photo-taking stops)")

        setupPostLandingCallback(
            missionType = multiStops.first().missionType,
            isMulti = true,
            stopCount = multiStops.size,
            photoCount = if (totalPhotoCount > 0) totalPhotoCount else AppConfig.MAX_DOWNLOAD_PHOTOS
        )

        missionJob = scope.launch {
            try {
                safeDisableVirtualStick()
                delay(AppConfig.VS_STATE_RESET_DELAY_MS)
                executeMultiStop()
            } catch (e: CancellationException) {
                Log.w(TAG, "Multi-stop coroutine cancelled")
                // 任務被取消：發送 cancelled ack，清除追蹤防止 post-landing 重複發送
                activeMissionMsgId?.let { mid ->
                    MqttClientManager.publishAck(AckPayload(
                        msgId = mid, action = activeMissionAction ?: "mission",
                        status = "failed", errorMsg = "Multi-stop mission cancelled"
                    ))
                }
                activeMissionMsgId = null
                activeMissionAction = null
            } catch (e: Exception) {
                Log.e(TAG, "Multi-stop exception: ${e.message}", e)
                // 發送 failed ack 後清除追蹤防止 post-landing 重複發送
                activeMissionMsgId?.let { mid ->
                    MqttClientManager.publishAck(AckPayload(
                        msgId = mid, action = activeMissionAction ?: "mission",
                        status = "failed", errorMsg = e.message?.take(100) ?: "Unknown error"
                    ))
                }
                activeMissionMsgId = null
                activeMissionAction = null
                cancelMission(reason = "Multi-stop exception: ${e.message?.take(100)}")
            }
        }
    }

    /** 取消任務：停止錄影、重置雲台、啟動自動返航 */
    fun cancelMission(reason: String? = null, mqttMsgId: String? = null, mqttAction: String? = null) {
        if (!isCancelling.compareAndSet(false, true)) {
            Log.w(TAG, "cancelMission already in progress, ignored")
            return
        }
        val cancelReason = reason ?: "User or MQTT cancel during ${_state.value}"
        Log.w(TAG, "cancelMission called: $cancelReason")
        // 記錄 pending command ack（來自 MQTT rtl/cancel 指令）
        if (mqttMsgId != null && mqttAction != null) {
            pendingCommandMsgId = mqttMsgId
            pendingCommandAction = mqttAction
        }
        logMissionEvent(SafetyAnomalyType.MISSION_CANCELLED, cancelReason)
        missionJob?.cancel()
        missionJob = null

        // 如果已經在地面（手動降落或 RC 降落），直接回 IDLE
        if (isDroneOnGround()) {
            Log.w(TAG, "cancelMission: drone confirmed on ground, skipping RTH")
            setState(MissionState.IDLE)
            sendPendingCommandAckIfNeeded("success")
            MqttClientManager.publish(
                MqttTopics.STATUS,
                """{"state":"IDLE","result":"already_landed"}"""
            )
            MqttStatusReporter.publishCurrentStatus()
            isCancelling.set(false)
            return
        }

        scope.launch {
            try {
                // 先移除狀態監聽，避免返航期間被再次觸發
                VirtualStickController.removeStateListener()
                suspendCancellableCoroutine<Unit> { cont ->
                    CameraManager.stopVideoRecord(object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() {
                            AppLog.d(TAG, "cancelMission: video recording stopped")
                            if (cont.isActive) cont.resume(Unit) {}
                        }
                        override fun onFailure(error: IDJIError) {
                            Log.e(TAG, "cancelMission: stopVideoRecord failed: $error")
                            if (cont.isActive) cont.resume(Unit) {} // continue cleanup anyway
                        }
                    })
                }
                GimbalManager.setPitchAngle(0.0)
                safeDisableVirtualStick()
                // RTH 含重試，失敗時退回 land() 直接降落
                var rthSuccess = false
                repeat(2) { attempt ->
                    try {
                        suspendCancellableCoroutine<Unit> { cont ->
                            FlightManager.startRTH(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                                override fun onSuccess(value: EmptyMsg) {
                                    AppLog.d(TAG, "cancelMission: RTH started (attempt ${attempt + 1})")
                                    rthSuccess = true
                                    if (cont.isActive) cont.resume(Unit) {}
                                }
                                override fun onFailure(error: IDJIError) {
                                    Log.e(TAG, "cancelMission: RTH failed (attempt ${attempt + 1}): $error")
                                    if (cont.isActive) cont.resume(Unit) {}
                                }
                            })
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "cancelMission: RTH exception: $e")
                    }
                    if (rthSuccess) return@repeat
                    delay(1000)
                }
                if (!rthSuccess) {
                    Log.w(TAG, "cancelMission: RTH failed after retries, falling back to land()")
                    logMissionEvent(SafetyAnomalyType.RTH_FAILED, "RTH retries exhausted, falling back to land()")
                    try {
                        FlightManager.land()
                    } catch (e: Exception) {
                        Log.e(TAG, "cancelMission: land() also failed: $e")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "cancelMission cleanup error: $e")
                isCancelling.set(false)
            }

            setState(MissionState.RETURNING_HOME)

            // 馬達鎖定回調：無人機落地後自動重置為 IDLE
            FlightManager.setOnMotorLockedCallback {
                if (landingHandled.compareAndSet(false, true)) {
                    scope.launch {
                        setState(MissionState.IDLE)
                        sendPendingCommandAckIfNeeded("success")
                        MqttClientManager.publish(
                            MqttTopics.STATUS,
                            """{"state":"IDLE","result":"cancelled"}"""
                        )
                        FlightManager.setOnMotorLockedCallback(null)
                        isCancelling.set(false)
                    }
                }
            }

            // 60 秒逾時：檢查是否已落地，否則持續等待
            scope.launch {
                delay(AppConfig.CANCEL_RTH_TIMEOUT_MS)
                if (landingHandled.compareAndSet(false, true)) {
                    if (isDroneOnGround()) {
                        Log.w(TAG, "cancelMission RTH timeout (60s), drone on ground → IDLE")
                        setState(MissionState.IDLE)
                        sendPendingCommandAckIfNeeded("success")
                        MqttClientManager.publish(
                            MqttTopics.STATUS,
                            """{"state":"IDLE","result":"cancelled"}"""
                        )
                        FlightManager.setOnMotorLockedCallback(null)
                        isCancelling.set(false)
                    } else {
                        // 仍在空中：不設 IDLE，讓 motor-locked callback 或 H9 定期檢查處理
                        Log.w(TAG, "cancelMission RTH timeout (60s), drone still airborne — keeping RETURNING_HOME")
                        landingHandled.set(false)  // 重置，讓後續檢查可以觸發
                        isCancelling.set(false)
                    }
                }
            }

            // P0-18: 在所有取消後處理邏輯完成後釋放守衛
            isCancelling.set(false)

            Log.w(TAG, "Mission cancelled, RTH initiated")
        }
    }

    /** 重置狀態機為 IDLE */
    fun resetToIdle() {
        val currentState = _state.value
        when {
            currentState == MissionState.IDLE -> {
                // 已經是 IDLE，不做事
            }
            currentState == MissionState.ERROR -> {
                // ERROR 狀態：如果在地面才允許重置
                if (!isDroneOnGround()) {
                    Log.w(TAG, "[Mission] resetToIdle blocked: ERROR state but drone still in air")
                    return
                }
                AppLog.d(TAG, "[Mission] resetToIdle from ERROR state (drone on ground)")
            }
            currentState == MissionState.RETURNING_HOME && isDroneOnGround() -> {
                // 卡在返航中但已在地面 → 強制重置
                Log.w(TAG, "[Mission] resetToIdle: stuck in RETURNING_HOME but drone confirmed on ground, forcing IDLE")
            }
            else -> {
                Log.w(TAG, "[Mission] resetToIdle ignored, mission in progress state=$currentState")
                return
            }
        }
        _state.value = MissionState.IDLE
        isCancelling.set(false)
        landingHandled.set(false)
        FlightManager.setOnMotorLockedCallback(null)
        // 清除所有 pending ack 狀態，防止 stale ack
        activeMissionMsgId = null
        activeMissionAction = null
        pendingCommandMsgId = null
        pendingCommandAction = null
        MqttClientManager.publish(
            MqttTopics.STATUS,
            """{"state":"IDLE","result":"reset"}"""
        )
        MqttStatusReporter.publishCurrentStatus()
        AppLog.d(TAG, "[Mission] resetToIdle complete")
    }

    /** 發送暫存的指令最終 ack（來自 rtl/cancel），成功後清除 */
    private fun sendPendingCommandAckIfNeeded(status: String) {
        val msgId = pendingCommandMsgId ?: return
        val action = pendingCommandAction ?: return
        MqttClientManager.publishAck(AckPayload(
            msgId = msgId, action = action, status = status
        ))
        pendingCommandMsgId = null
        pendingCommandAction = null
    }

    /**
     * MQTT 連線狀態變更通知（由 MqttClientManager 呼叫）。
     *
     * @deprecated 改用 MqttConnectionEventBus 收集事件，保留以向後相容。
     * 外部應使用 MqttConnectionEventBus.onConnectionChanged() 而非直接呼叫此方法。
     */
    @Deprecated("Use MqttConnectionEventBus instead", ReplaceWith("MqttConnectionEventBus.onConnectionChanged(connected)"))
    fun onMqttConnectionChanged(connected: Boolean) {
        MqttConnectionEventBus.onConnectionChanged(connected)
    }

    // ========== Segmented Test Commands ==========

    /** 測試用：自動起飛至安全高度 */
    fun testTakeoff() {
        if (!BuildConfig.DEBUG) return
        if (_state.value != MissionState.IDLE) {
            Log.w(TAG, "Mission not IDLE, TEST_TAKEOFF ignored")
            return
        }
        missionJob = scope.launch {
            try {
                setState(MissionState.TAKING_OFF)
                performTakeoff(maxRetries = 1)
                AppLog.d(TAG, "TEST_TAKEOFF auto-takeoff complete, climbing to safe height")
                climbToAltitude(AppConfig.getSafeTakeoffHeight().toDouble())
                AppLog.d(TAG, "TEST_TAKEOFF: hovering 10s...")
                MqttClientManager.publish(MqttTopics.STATUS, """{"state":"TEST_TAKEOFF","phase":"hovering"}""")
                delay(AppConfig.HOVER_TEST_DURATION_MS)
                AppLog.d(TAG, "TEST_TAKEOFF: hover complete, RTH")
                setState(MissionState.RETURNING_HOME)
                safeDisableVirtualStick()
                // P0-02 修正：等待馬達鎖定後才設為 IDLE
                FlightManager.setOnMotorLockedCallback {
                    scope.launch {
                        setState(MissionState.IDLE)
                        AppLog.d(TAG, "TEST_TAKEOFF complete, drone landed")
                        MqttClientManager.publish(MqttTopics.STATUS, """{"state":"IDLE","result":"TEST_TAKEOFF complete"}""")
                        FlightManager.setOnMotorLockedCallback(null)
                    }
                }
                FlightManager.startRTH()
                // 安全逾時
                scope.launch {
                    delay(AppConfig.RTH_TIMEOUT_MS)
                    if (_state.value == MissionState.RETURNING_HOME) {
                        if (isDroneOnGround()) {
                            Log.w(TAG, "TEST_TAKEOFF RTH timeout, drone on ground → IDLE")
                            setState(MissionState.IDLE)
                            FlightManager.setOnMotorLockedCallback(null)
                        } else {
                            Log.w(TAG, "TEST_TAKEOFF RTH timeout, drone still airborne — keeping RETURNING_HOME")
                        }
                    }
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "TEST_TAKEOFF cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "TEST_TAKEOFF failed: ${e.message}", e)
                cancelMission()
            }
        }
    }

    /** 測試用：原地 360° yaw 旋轉後返航 */
    fun testYaw() {
        if (!BuildConfig.DEBUG) return
        if (_state.value != MissionState.IDLE) {
            Log.w(TAG, "Mission not IDLE, TEST_YAW ignored")
            return
        }
        missionJob = scope.launch {
            try {
                setState(MissionState.FLYING_TO_TARGET)
                if (!enableVirtualStickWithRetry()) {
                    throw Exception("Cannot enable VirtualStick for TEST_YAW")
                }
                val param = VirtualStickParams.groundVelocityParams()
                AppLog.d(TAG, "TEST_YAW: rotating 360°...")
                MqttClientManager.publish(MqttTopics.STATUS, """{"state":"TEST_YAW","phase":"rotating"}""")
                var totalYaw = 0.0
                var lastYaw = FlightManager.getCurrentYaw()
                val yawStartTime = System.currentTimeMillis()
                while (coroutineContext.isActive && totalYaw < AppConfig.YAW_ROTATION_TARGET_DEG) {
                    if (System.currentTimeMillis() - yawStartTime > AppConfig.YAW_ROTATION_TIMEOUT_MS) {
                        Log.e(TAG, "TEST_YAW: Yaw rotation timeout! totalYaw=$totalYaw, forcing exit")
                        break
                    }
                    param.yaw = AppConfig.YAW_SPEED_DEG_PER_SEC.toDouble()
                    param.pitch = 0.0; param.roll = 0.0; param.verticalThrottle = 0.0
                    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                    delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
                    val currentYaw = FlightManager.getCurrentYaw()
                    var delta = currentYaw - lastYaw
                    if (delta > 180) delta -= 360
                    if (delta < -180) delta += 360
                    totalYaw += abs(delta)
                    lastYaw = currentYaw
                    AppLog.d(TAG, "Yaw loop: current=$currentYaw, delta=${"%.2f".format(delta)}, total=${"%.1f".format(totalYaw)}")
                }
                sendZeroCommand(param)
                delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
                AppLog.d(TAG, "TEST_YAW: rotation complete, RTH")
                setState(MissionState.RETURNING_HOME)
                safeDisableVirtualStick()
                // P0-02 修正：等待馬達鎖定後才設為 IDLE
                FlightManager.setOnMotorLockedCallback {
                    scope.launch {
                        setState(MissionState.IDLE)
                        AppLog.d(TAG, "TEST_YAW complete, drone landed")
                        MqttClientManager.publish(MqttTopics.STATUS, """{"state":"IDLE","result":"TEST_YAW complete"}""")
                        FlightManager.setOnMotorLockedCallback(null)
                    }
                }
                FlightManager.startRTH()
                scope.launch {
                    delay(AppConfig.RTH_TIMEOUT_MS)
                    if (_state.value == MissionState.RETURNING_HOME) {
                        if (isDroneOnGround()) {
                            Log.w(TAG, "TEST_YAW RTH timeout, drone on ground → IDLE")
                            setState(MissionState.IDLE)
                            FlightManager.setOnMotorLockedCallback(null)
                        } else {
                            Log.w(TAG, "TEST_YAW RTH timeout, drone still airborne — keeping RETURNING_HOME")
                        }
                    }
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "TEST_YAW cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "TEST_YAW failed: ${e.message}", e)
                cancelMission()
            }
        }
    }

    /** 測試用：每旋轉 ~60° 拍一張照片後返航 */
    fun testPhoto() {
        if (!BuildConfig.DEBUG) return
        if (_state.value != MissionState.IDLE) {
            Log.w(TAG, "Mission not IDLE, TEST_PHOTO ignored")
            return
        }
        missionJob = scope.launch {
            try {
                setState(MissionState.SHOOTING_PHOTOS)
                if (!enableVirtualStickWithRetry()) {
                    throw Exception("Cannot enable VirtualStick for TEST_PHOTO")
                }
                CameraManager.setCameraMode(true)
                delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
                val param = VirtualStickParams.groundVelocityParams()
                repeat(AppConfig.TEST_PHOTO_COUNT) { i ->
                    // 旋轉 60 度
                    var rotated = 0.0
                    var lastY = FlightManager.getCurrentYaw()
                    val rotStartTime = System.currentTimeMillis()
                    while (coroutineContext.isActive && rotated < AppConfig.PHOTO_ROTATION_TARGET_DEG) {
                        if (System.currentTimeMillis() - rotStartTime > AppConfig.TAKEOFF_TIMEOUT_MS) {
                            Log.e(TAG, "TEST_PHOTO photo${i + 1}: Rotation timeout! rotated=${"%.1f".format(rotated)}°, forcing exit")
                            break
                        }
                        param.yaw = AppConfig.YAW_SPEED_DEG_PER_SEC.toDouble()
                        param.pitch = 0.0; param.roll = 0.0; param.verticalThrottle = 0.0
                        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                        delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
                        val y = FlightManager.getCurrentYaw()
                        var d = y - lastY
                        if (d > 180) d -= 360
                        if (d < -180) d += 360
                        rotated += abs(d)
                        lastY = y
                        AppLog.d(TAG, "TEST_PHOTO photo${i + 1} yaw: current=$y, delta=${"%.2f".format(d)}, rotated=${"%.1f".format(rotated)}°")
                    }
                    sendZeroCommand(param)
                    delay(AppConfig.PHOTO_PRE_SHOT_DELAY_MS)
                    suspendCancellableCoroutine<Unit> { cont ->
                        CameraManager.takePhoto(object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {
                                AppLog.d(TAG, "TEST_PHOTO: photo ${i + 1}/${AppConfig.TEST_PHOTO_COUNT} taken")
                                if (cont.isActive) cont.resume(Unit) {}
                            }
                            override fun onFailure(error: IDJIError) {
                                Log.e(TAG, "TEST_PHOTO: photo ${i + 1}/${AppConfig.TEST_PHOTO_COUNT} failed: $error")
                                if (cont.isActive) cont.resume(Unit) {}
                            }
                        })
                    }
                    delay(AppConfig.PHOTO_INTER_SHOT_DELAY_MS)
                    MqttClientManager.publish(MqttTopics.STATUS, """{"state":"TEST_PHOTO","photo":${i + 1},"total":${AppConfig.TEST_PHOTO_COUNT}}""")
                }
                AppLog.d(TAG, "TEST_PHOTO: ${AppConfig.TEST_PHOTO_COUNT} photos complete, RTH")
                setState(MissionState.RETURNING_HOME)
                safeDisableVirtualStick()
                // P0-02 修正：等待馬達鎖定後才設為 IDLE
                FlightManager.setOnMotorLockedCallback {
                    scope.launch {
                        setState(MissionState.IDLE)
                        AppLog.d(TAG, "TEST_PHOTO complete, drone landed")
                        MqttClientManager.publish(MqttTopics.STATUS, """{"state":"IDLE","result":"TEST_PHOTO complete"}""")
                        FlightManager.setOnMotorLockedCallback(null)
                    }
                }
                FlightManager.startRTH()
                scope.launch {
                    delay(AppConfig.RTH_TIMEOUT_MS)
                    if (_state.value == MissionState.RETURNING_HOME) {
                        if (isDroneOnGround()) {
                            Log.w(TAG, "TEST_PHOTO RTH timeout, drone on ground → IDLE")
                            setState(MissionState.IDLE)
                            FlightManager.setOnMotorLockedCallback(null)
                        } else {
                            Log.w(TAG, "TEST_PHOTO RTH timeout, drone still airborne — keeping RETURNING_HOME")
                        }
                    }
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "TEST_PHOTO cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "TEST_PHOTO failed: ${e.message}", e)
                cancelMission()
            }
        }
    }

    // ========== Mission Execution ==========

    /** 執行任務主流程：起飛 → 飛往目標 → 拍攝/環繞 → 返航 */
    private suspend fun executeMission(
        targetLat: Double,
        targetLng: Double,
        targetAlt: Double,
        missionType: String,
        orbitRadius: Double,
        orbitAlt: Double?,
        orbitGimbalPitch: Double,
        photoGimbalPitch: Double,
        photoCount: Int = AppConfig.DEFAULT_PHOTO_COUNT,
        maxAlt: Double? = null,
        turns: Int? = null,
        radiusEnd: Double? = null,
        orbitSpeed: Double = 1.0,
        finalHeading: Double? = null
    ) {
        // GPS 檢查
        if (!FlightManager.isGpsReadyForMission()) {
            Log.e(TAG, "GPS not ready, mission aborted")
            logMissionEvent(SafetyAnomalyType.MISSION_ERROR, "GPS signal too weak")
            MqttClientManager.publish(MqttTopics.STATUS, """{"state":"ERROR","reason":"GPS signal too weak"}""")
            setState(MissionState.ERROR)
            return
        }

        // 記錄起飛點（Home）位置
        val homeLoc = FlightManager.getCurrentLocation()
        if (homeLoc == null) {
            Log.e(TAG, "Cannot get home location, mission aborted")
            logMissionEvent(SafetyAnomalyType.MISSION_ERROR, "No GPS location")
            MqttClientManager.publish(MqttTopics.STATUS, """{"state":"ERROR","reason":"No GPS location"}""")
            setState(MissionState.ERROR)
            return
        }
        AppLog.d(TAG, "Home location: lat=${homeLoc.latitude}, lng=${homeLoc.longitude}")

        // 距離檢查
        val distToTarget = GeoMath.haversine(homeLoc.latitude, homeLoc.longitude, targetLat, targetLng)
        val maxRadius = AppConfig.getMaxFlightRadius().toDouble()
        if (distToTarget > maxRadius) {
            Log.e(TAG, "Target too far: ${"%.0f".format(distToTarget)}m > ${"%.0f".format(maxRadius)}m, aborted")
            logMissionEvent(SafetyAnomalyType.MISSION_ERROR, "Target too far: ${"%.0f".format(distToTarget)}m > ${"%.0f".format(maxRadius)}m")
            MqttClientManager.publish(MqttTopics.STATUS, """{"state":"ERROR","reason":"Target exceeds safe radius"}""")
            setState(MissionState.ERROR)
            return
        }

        // 電池預檢：估算往返所需電量（僅發布警告，不阻擋執行）
        val estimatedBattery = FlightPhaseExecutor.estimateRemainingFlightBattery(
            homeLoc.latitude, homeLoc.longitude, targetLat, targetLng, targetAlt
        )
        val currentBattery = FlightManager.batteryPercent.value
        val effectiveThreshold = FlightManager.effectiveThreshold.value
        if (currentBattery < estimatedBattery + AppConfig.BATTERY_SAFETY_MARGIN) {
            Log.w(TAG, "Battery warning: current=$currentBattery%, estimated=$estimatedBattery% + margin=${AppConfig.BATTERY_SAFETY_MARGIN}% — proceeding anyway")
            logMissionEvent(SafetyAnomalyType.LOW_BATTERY_RTH, "Battery risk: $currentBattery% < ${estimatedBattery + AppConfig.BATTERY_SAFETY_MARGIN}% estimated, continuing")
            MqttClientManager.publish(MqttTopics.STATUS, """{"state":"WARNING","reason":"Battery risk: $currentBattery% < ${estimatedBattery + AppConfig.BATTERY_SAFETY_MARGIN}% estimated"}""")
        }
        if (currentBattery <= effectiveThreshold) {
            Log.e(TAG, "Battery below effective threshold: $currentBattery% <= $effectiveThreshold%")
            logMissionEvent(SafetyAnomalyType.MISSION_ERROR, "Battery below RTH threshold: $currentBattery% <= $effectiveThreshold%")
            MqttClientManager.publish(MqttTopics.STATUS, """{"state":"ERROR","reason":"Battery below RTH threshold: $currentBattery% <= $effectiveThreshold%"}""")
            setState(MissionState.ERROR)
            return
        }
        AppLog.d(TAG, "Battery OK: $currentBattery%, threshold=$effectiveThreshold%, estimated=$estimatedBattery%")

        // 起飛階段（TAKING_OFF）
        setState(MissionState.TAKING_OFF)
        performTakeoff()
        AppLog.d(TAG, "Takeoff complete, climbing to target altitude")
        climbToAltitude(targetAlt)
        AppLog.d(TAG, "Climb complete, altitude reached")
        val climbParam = VirtualStickParams.groundVelocityParams()

        // 第二階段：機頭轉向目標（爬升完成，原地懸停）
        val yawStartTime = System.currentTimeMillis()
        while (coroutineContext.isActive) {
            val loc = FlightManager.getCurrentLocation() ?: break
            val bearingToTarget = GeoMath.calculateBearing(loc.latitude, loc.longitude, targetLat, targetLng)
            val heading = FlightManager.heading.value
            var yawErr = bearingToTarget - heading
            if (yawErr > 180) yawErr -= 360
            if (yawErr < -180) yawErr += 360
            AppLog.d(TAG, "YawToTarget: bearing=${"%.1f".format(bearingToTarget)}°, heading=${"%.1f".format(heading)}°, err=${"%.1f".format(yawErr)}°")
            if (abs(yawErr) < AppConfig.YAW_ALIGNMENT_TOLERANCE_DEG) break
            if (System.currentTimeMillis() - yawStartTime > AppConfig.TAKEOFF_TIMEOUT_MS) {
                Log.w(TAG, "Yaw to target timeout, proceeding anyway")
                break
            }
            climbParam.pitch = 0.0
            climbParam.roll = 0.0
            climbParam.verticalThrottle = 0.0
            climbParam.yaw = (yawErr * AppConfig.YAW_ALIGN_PROPORTIONAL_GAIN).coerceIn(-AppConfig.YAW_SPEED_DEG_PER_SEC.toDouble(), AppConfig.YAW_SPEED_DEG_PER_SEC.toDouble())
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(climbParam)
            delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
        }
        sendZeroCommand(climbParam)
        delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
        AppLog.d(TAG, "Yaw alignment complete")
        // VirtualStick 保持啟用，供 FLYING_TO_TARGET 階段使用

        // 飛往目標階段（FLYING_TO_TARGET）
        setState(MissionState.FLYING_TO_TARGET)

        val param = VirtualStickParams.groundVelocityParams()

        while (coroutineContext.isActive) {
            val current = FlightManager.getFilteredLocation()
            if (current == null) {
                Log.w(TAG, "Cannot get current location, retrying...")
                delay(AppConfig.VS_DISABLE_SETTLE_DELAY_MS)
                continue
            }
            val distance = GeoMath.haversine(current.latitude, current.longitude, targetLat, targetLng)
            val bearing = GeoMath.calculateBearing(current.latitude, current.longitude, targetLat, targetLng)
            val bearingRad = Math.toRadians(bearing)

            if (distance < AppConfig.ARRIVAL_THRESHOLD_M) break

            // 比例速度：接近目標時減速，防止過衝
            val speed = max(min((distance * AppConfig.getSpeedGain()).toFloat(), AppConfig.getMaxFlightSpeed()), AppConfig.MIN_FLIGHT_SPEED)
            val pitch = (speed * sin(bearingRad)).toFloat()   // 東向分量 → pitch(+) = 向東
            val roll = (speed * cos(bearingRad)).toFloat()    // 北向分量 → roll(+) = 向北

            // Yaw：機頭旋轉朝向目標方向
            val currentHeading = FlightManager.heading.value
            var yawError = bearing - currentHeading
            if (yawError > 180) yawError -= 360
            if (yawError < -180) yawError += 360
            val yawSpeed = when {
                abs(yawError) < AppConfig.YAW_SMALL_ERROR_THRESHOLD_DEG -> 0.0
                abs(yawError) < AppConfig.YAW_MEDIUM_ERROR_THRESHOLD_DEG -> yawError * AppConfig.YAW_ALIGN_PROPORTIONAL_GAIN
                else -> AppConfig.YAW_SPEED_DEG_PER_SEC * if (yawError > 0) 1.0 else -1.0
            }
            AppLog.d(TAG, "FlyingToTarget: dist=${"%.1f".format(distance)}m, bearing=${"%.1f".format(bearing)}°, heading=${"%.1f".format(currentHeading)}°, yawErr=${"%.1f".format(yawError)}°, yawCmd=${"%.1f".format(yawSpeed)}°/s")

            param.pitch = pitch.toDouble()
            param.roll = roll.toDouble()
            param.yaw = yawSpeed
            param.verticalThrottle = 0.0
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
            delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
        }

        // 停止移動（發送零指令）
        sendZeroCommand(param)
        delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
        AppLog.d(TAG, "Arrived at target")

        // 計算環繞高度（實際下降由 OrbitController 在飛到圓周起點後執行，避免在圓心撞擊障礙物）
        val effectiveOrbitAlt = orbitAlt ?: targetAlt

        // 執行該站任務邏輯（錄影 + 拍照）
        executeStopCore(targetLat, targetLng, effectiveOrbitAlt, missionType, orbitRadius, orbitGimbalPitch, photoGimbalPitch, param, photoCount, maxAlt, turns, radiusEnd, orbitSpeed, finalHeading)

        // 返航前爬升回 target.alt（若 orbit.alt 較低）
        val altBeforeRTH = FlightManager.getCurrentAltitude() ?: targetAlt
        if (altBeforeRTH < targetAlt - 0.5) {
            AppLog.d(TAG, "Climbing from ${"%.1f".format(altBeforeRTH)}m to target.alt ${"%.1f".format(targetAlt)}m before RTH")
            if (!enableVirtualStickWithRetry()) {
                throw Exception("Cannot enable VirtualStick for pre-RTH climb")
            }
            val climbParam = VirtualStickParams.groundVelocityParams()
            val climbStart = System.currentTimeMillis()
            while (coroutineContext.isActive) {
                val alt = FlightManager.getCurrentAltitude() ?: 0.0
                if (alt >= targetAlt) break
                if (System.currentTimeMillis() - climbStart > AppConfig.CLIMB_TIMEOUT_MS) {
                    Log.w(TAG, "Pre-RTH climb timeout, proceeding with RTH at current altitude")
                    break
                }
                climbParam.pitch = 0.0; climbParam.roll = 0.0; climbParam.yaw = 0.0
                climbParam.verticalThrottle = AppConfig.CLIMB_THROTTLE.toDouble()
                VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(climbParam)
                delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
            }
            sendZeroCommand(climbParam)
            delay(AppConfig.VS_ZERO_SEND_DELAY_MS)
            safeDisableVirtualStick()
            AppLog.d(TAG, "Pre-RTH climb complete")
        }

        // 返航階段（RETURNING_HOME）
        setState(MissionState.RETURNING_HOME)
        safeDisableVirtualStick()
        suspendCancellableCoroutine<Unit> { cont ->
            FlightManager.startRTH(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(value: EmptyMsg) {
                    AppLog.d(TAG, "RTH started")
                    if (cont.isActive) cont.resume(Unit) {}
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "RTH failed: $error")
                    if (cont.isActive) cont.resumeWith(Result.failure(RuntimeException("RTH failed: $error")))
                }
            })
        }

        AppLog.d(TAG, "RTH command accepted, waiting for drone to land...")

        // P0-01 修正：此處不得設為 IDLE —— 無人機仍在空中。
        // 設為 IDLE 的時機是馬達鎖定回調（於 startMission 設定）在無人機實際落地、馬達停止時觸發。

        // 安全逾時：若馬達鎖定回調一直未觸發，檢查落地狀態後補做下載並設為 IDLE
        scope.launch {
            delay(AppConfig.RTH_TIMEOUT_MS)
            if (_state.value == MissionState.RETURNING_HOME) {
                if (!landingHandled.compareAndSet(false, true)) {
                    AppLog.d(TAG, "RTH timeout skipped — already handled by motor-locked callback")
                    return@launch
                }
                if (!isDroneOnGround()) {
                    // 仍在空中：不設 IDLE，讓 motor-locked callback 或 H9 定期檢查處理
                    Log.w(TAG, "RTH timeout (120s), drone still airborne — keeping RETURNING_HOME")
                    landingHandled.set(false)  // 重置，讓後續檢查可以觸發
                    return@launch
                }
                Log.w(TAG, "RTH timeout (120s), drone on ground, motor-locked callback did not fire")
                FlightManager.setOnMotorLockedCallback(null)
                if (missionType != "B" && missionType != "C" && missionType != "D" && !isDownloading.get()) {
                    Log.w(TAG, "Fallback: starting photo download from timeout path")
                    isDownloading.set(true)
                    try {
                        val result = MediaDownloadManager.downloadLastNPhotos(photoCount)
                        when (result) {
                            is DownloadResult.Success -> AppLog.d(TAG, "Fallback download: ${result.count} photos")
                            is DownloadResult.Failure -> Log.e(TAG, "Fallback download failed: ${result.reason}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Fallback download exception: ${e.message}", e)
                    }
                    isDownloading.set(false)
                }
                setState(MissionState.IDLE)
                MqttClientManager.publish(MqttTopics.STATUS, """{"state":"IDLE","result":"success","note":"timeout"}""")
                MqttStatusReporter.publishCurrentStatus()
            }
        }
    }

    // ========== Multi-Stop Execution ==========

    /** 執行多站任務：起飛 → 依序執行各站 → 返航 */
    private suspend fun executeMultiStop() {
        val first = multiStops[0]
        val homeLoc = FlightManager.getCurrentLocation()
            ?: throw Exception("Cannot get home location")

        if (!FlightManager.isGpsReadyForMission()) {
            logMissionEvent(SafetyAnomalyType.MISSION_ERROR, "GPS signal too weak (multi-stop)")
            setState(MissionState.ERROR)
            return
        }

        setState(MissionState.TAKING_OFF)
        performTakeoff()
        climbToAltitude(first.targetAlt)
        flyToTarget(first.targetLat, first.targetLng, first.targetAlt)

        for (i in multiStops.indices) {
            currentStopIndex = i
            _multiStopProgress.value = (i + 1) to multiStops.size
            val stop = multiStops[i]
            AppLog.d(TAG, "Multi-stop: executing stop ${i + 1}/${multiStops.size} type=${stop.missionType}")
            MqttClientManager.publish(MqttTopics.STATUS, """{"state":"STOP_${i + 1}_OF_${multiStops.size}","mission":"${stop.missionType}"}""")

            try {
                val effectiveOrbitAlt = stop.orbitAlt ?: stop.targetAlt
                val param = VirtualStickParams.groundVelocityParams()

                // 上升需求在此處理（安全，往上飛不會撞到地面物體）
                // 下降由 OrbitController 在飛到圓周起點後執行，避免在圓心撞擊障礙物
                val currentAlt = FlightManager.getCurrentAltitude() ?: stop.targetAlt
                if (effectiveOrbitAlt > currentAlt + 0.5) {
                    climbToAltitude(effectiveOrbitAlt)
                }

                executeStopCore(stop.targetLat, stop.targetLng, effectiveOrbitAlt,
                    stop.missionType, stop.orbitRadius, stop.orbitGimbalPitch, stop.photoGimbalPitch, param,
                    maxAlt = stop.maxAlt, turns = stop.turns, radiusEnd = stop.radiusEnd, orbitSpeed = stop.orbitSpeed,
                    finalHeading = stop.finalHeading)
            } catch (e: Exception) {
                Log.e(TAG, "Stop ${i + 1} failed: ${e.message}", e)
                logMissionEvent(SafetyAnomalyType.MISSION_ERROR, "Stop ${i + 1} failed: ${e.message?.take(100)}")
                MqttClientManager.publish(MqttTopics.STATUS, """{"state":"STOP_FAILED","stop":${i + 1},"error":"${e.message?.take(100)}"}""")
                try { CameraManager.stopVideoRecord() } catch (e: Exception) { Log.w(TAG, "Stop ${i + 1}: stopVideoRecord cleanup failed", e) }
                try { VirtualStickController.safeDisable() } catch (e: Exception) { Log.w(TAG, "Stop ${i + 1}: safeDisable cleanup failed", e) }
                break
            }

            if (i < multiStops.size - 1) {
                if (!enableVirtualStickWithRetry()) {
                    Log.e(TAG, "Cannot re-enable VirtualStick after stop ${i + 1}, RTH")
                    break
                }
            }

            if (i == multiStops.size - 1) {
                AppLog.d(TAG, "Multi-stop: all stops complete, RTH")
                break
            }

            val nextStop = multiStops[i + 1]
            flyToNextStop(nextStop.targetLat, nextStop.targetLng, nextStop.targetAlt)
        }

        setState(MissionState.RETURNING_HOME)
        safeDisableVirtualStick()
        suspendCancellableCoroutine<Unit> { cont ->
            FlightManager.startRTH(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(value: EmptyMsg) {
                    AppLog.d(TAG, "Multi-stop RTH started")
                    if (cont.isActive) cont.resume(Unit) {}
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Multi-stop RTH failed: $error")
                    if (cont.isActive) cont.resumeWith(Result.failure(RuntimeException("RTH failed: $error")))
                }
            })
        }

        scope.launch {
            delay(AppConfig.RTH_TIMEOUT_MS)
            if (_state.value == MissionState.RETURNING_HOME) {
                if (!landingHandled.compareAndSet(false, true)) {
                    AppLog.d(TAG, "Multi-stop RTH timeout skipped — already handled by motor-locked callback")
                    return@launch
                }
                if (!isDroneOnGround()) {
                    Log.w(TAG, "Multi-stop RTH timeout, drone still airborne — keeping RETURNING_HOME")
                    landingHandled.set(false)
                    return@launch
                }
                Log.w(TAG, "Multi-stop RTH timeout, drone on ground, motor-locked callback did not fire")
                FlightManager.setOnMotorLockedCallback(null)
                // 補齊 fallback 照片下載（與單站路徑對稱）
                val shouldDownload = multiStops.any { it.missionType != "B" && it.missionType != "C" && it.missionType != "D" }
                if (shouldDownload && !isDownloading.get()) {
                    Log.w(TAG, "Fallback: starting photo download from multi-stop timeout path")
                    isDownloading.set(true)
                    try {
                        val result = MediaDownloadManager.downloadLastNPhotos(AppConfig.MAX_DOWNLOAD_PHOTOS)
                        when (result) {
                            is DownloadResult.Success -> AppLog.d(TAG, "Fallback download: ${result.count} photos")
                            is DownloadResult.Failure -> Log.e(TAG, "Fallback download failed: ${result.reason}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Fallback download exception: ${e.message}", e)
                    }
                    isDownloading.set(false)
                }
                if (isMultiStop) {
                    isMultiStop = false
                    _multiStopProgress.value = null
                }
                setState(MissionState.IDLE)
                MqttClientManager.publish(MqttTopics.STATUS, """{"state":"IDLE","result":"success","note":"timeout","stops":${multiStops.size}}""")
                MqttStatusReporter.publishCurrentStatus()
            }
        }
    }

    /** 單站任務核心邏輯：下降 → 錄影/環繞 → 拍照（共用於單站與多站任務） */
    private suspend fun executeStopCore(
        targetLat: Double,
        targetLng: Double,
        effectiveOrbitAlt: Double,
        missionType: String,
        orbitRadius: Double,
        orbitGimbalPitch: Double,
        photoGimbalPitch: Double,
        param: VirtualStickFlightControlParam,
        photoCount: Int = AppConfig.DEFAULT_PHOTO_COUNT,
        maxAlt: Double? = null,
        turns: Int? = null,
        radiusEnd: Double? = null,
        orbitSpeed: Double = 1.0,
        finalHeading: Double? = null
    ) {
        if (missionType == "D" && orbitRadius > 0.0 && maxAlt != null && turns != null && radiusEnd != null) {
            OrbitController.executeExpandingSpiral(targetLat, targetLng, effectiveOrbitAlt, maxAlt, orbitRadius, radiusEnd, turns, param, orbitSpeed, finalHeading)
        } else if (missionType == "C" && orbitRadius > 0.0 && maxAlt != null && turns != null) {
            OrbitController.executeSpiralAscent(targetLat, targetLng, effectiveOrbitAlt, maxAlt, orbitRadius, turns, param, orbitSpeed, finalHeading)
        } else if (missionType == "B" && orbitRadius > 0.0) {
            OrbitController.executeOrbitPhase(targetLat, targetLng, effectiveOrbitAlt, orbitRadius, orbitGimbalPitch, param, orbitSpeed)
        } else {
            OrbitController.executeInPlaceRotation(orbitGimbalPitch, param, effectiveOrbitAlt, orbitSpeed)
        }

        if (missionType != "B" && missionType != "C" && missionType != "D") {
            PhotoSequenceController.executePhotoSequence(targetLat, targetLng, photoGimbalPitch, photoCount)
            CameraManager.setCameraMode(false)  // 拍照完切回錄影模式，避免卡在 PHOTO 導致後續設定失敗
        }
    }

    // ========== Delegated Flight Helpers ==========

    private suspend fun performTakeoff(maxRetries: Int = 3) = FlightPhaseExecutor.performTakeoff(maxRetries)
    private suspend fun climbToAltitude(targetAlt: Double) = FlightPhaseExecutor.climbToAltitude(targetAlt)
    private suspend fun descendToAltitude(targetAlt: Double, param: VirtualStickFlightControlParam) = FlightPhaseExecutor.descendToAltitude(targetAlt, param)
    private suspend fun flyToTarget(targetLat: Double, targetLng: Double, targetAlt: Double) = FlightPhaseExecutor.flyToTarget(targetLat, targetLng, targetAlt)
    private suspend fun flyToNextStop(targetLat: Double, targetLng: Double, targetAlt: Double) = FlightPhaseExecutor.flyToNextStop(targetLat, targetLng, targetAlt)
    private fun estimateRemainingFlightBattery(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, missionTargetAlt: Double) = FlightPhaseExecutor.estimateRemainingFlightBattery(fromLat, fromLng, toLat, toLng, missionTargetAlt)
    private suspend fun enableVirtualStickWithRetry(): Boolean = VirtualStickController.enableWithRetry()
    private suspend fun safeDisableVirtualStick() {
        if (!VirtualStickController.safeDisable()) {
            Log.w(TAG, "safeDisableVirtualStick failed, VS may still be enabled")
        }
    }
    private suspend fun sendZeroCommand(param: VirtualStickFlightControlParam) = VirtualStickController.sendZeroCommand(param)

    // ========== State Management ==========

    /** 更新任務狀態並透過 MQTT 即時通知後端 */
    internal fun setState(newState: MissionState, reason: String? = null) {
        _state.value = newState
        // 狀態回到 IDLE 時統一清除多站進度，確保任何路徑（完成/取消/錯誤恢復）都會刷新 HUD
        if (newState == MissionState.IDLE && isMultiStop) {
            isMultiStop = false
            _multiStopProgress.value = null
        }
        AppLog.d(TAG, "State -> $newState" + (if (reason != null) " (reason=$reason)" else ""))
        val json = JSONObject().apply {
            put("state", newState.name)
            reason?.let { put("reason", it) }
            put("time", System.currentTimeMillis())
        }
        MqttClientManager.publish(MqttTopics.STATUS, json.toString())
        // 只在關鍵時刻發送完整狀態
        if (newState == MissionState.TAKING_OFF ||
            newState == MissionState.RETURNING_HOME ||
            newState == MissionState.IDLE ||
            newState == MissionState.ERROR ||
            newState == MissionState.ORBITING ||
            newState == MissionState.SPIRAL_ASCENDING ||
            newState == MissionState.FLYING_TO_NEXT ||
            newState == MissionState.GIMBAL_PULL_UP) {
            MqttStatusReporter.publishCurrentStatus()
        }

        // ERROR 狀態處理：嘗試安全返航，90 秒後強制重置 IDLE
        if (newState == MissionState.ERROR) {
            scope.launch {
                // 嘗試取消任務並返航（若飛機仍在空中）
                try {
                    if (!isCancelling.get()) {
                        AppLog.d(TAG, "ERROR state: attempting cancelMission for safe RTH")
                        cancelMission(reason = "ERROR state auto-RTH (reason=$reason)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ERROR state: cancelMission failed: $e")
                }
                // 無論 cancelMission 成功與否，等待後強制重置
                delay(AppConfig.getErrorAutoResetMs())
                if (_state.value == MissionState.ERROR) {
                    if (FlightManager.isFlying.value) {
                        Log.w(TAG, "ERROR timeout but still flying — attempting RTH instead of resetToIdle")
                        try { cancelMission(reason = "ERROR state timeout, still flying") } catch (_: Exception) {}
                    } else {
                        Log.w(TAG, "ERROR state timeout (${AppConfig.getErrorAutoResetMs() / 1000}s), auto-resetting to IDLE")
                        resetToIdle()
                    }
                }
            }
        }
    }

    // ========== Safety Monitoring ==========

    /** 初始化安全監控：委派給 [MissionSafetyMonitor.setup] */
    fun setupSafetyMonitoring(logger: SafetyEventLogger) {
        safetyMonitor.setup(logger)
    }

    /** 記錄任務異常事件（供外部呼叫，委派給 [MissionSafetyMonitor.logEvent]） */
    fun logMissionEvent(anomalyType: SafetyAnomalyType, reason: String, mqttPayload: String? = null) {
        safetyMonitor.logEvent(anomalyType, reason, mqttPayload)
    }

    /** GPS 訊號恢復時重置弱訊號計時器（委派給 [MissionSafetyMonitor.resetGpsWeakTimer]） */
    fun resetGpsWeakTimer() {
        safetyMonitor.resetGpsWeakTimer()
    }

    /** 取消所有協程並重建 scope（斷線時呼叫，防止舊協程向新連線發送指令） */
    fun dispose() {
        val wasFlying = FlightManager.isFlying.value

        // 安全關鍵：取消前先發送零指令，避免最後的非零指令持續生效
        try {
            val param = VirtualStickParams.groundVelocityParams()
            param.pitch = 0.0; param.roll = 0.0; param.yaw = 0.0; param.verticalThrottle = 0.0
            repeat(3) {
                VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                Thread.sleep(50)
            }
        } catch (e: Exception) {
            Log.w(TAG, "dispose: zero-command send failed: ${e.message}")
        }

        scope.cancel()
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        missionJob = null
        isCancelling.set(false)
        landingHandled.set(false)
        if (wasFlying) {
            _state.value = MissionState.RETURNING_HOME
            AppLog.w(TAG, "dispose() called while flying — state set to RETURNING_HOME (firmware failsafe RTH expected)")
        } else {
            _state.value = MissionState.IDLE
            AppLog.d(TAG, "MissionStateMachine disposed, scope recreated")
        }
    }
}
