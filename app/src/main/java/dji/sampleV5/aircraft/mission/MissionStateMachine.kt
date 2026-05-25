package dji.sampleV5.aircraft.mission

import android.util.Log
import dji.sampleV5.aircraft.config.AppConfig
import dji.sampleV5.aircraft.dji.CameraManager
import dji.sampleV5.aircraft.dji.DownloadResult
import dji.sampleV5.aircraft.dji.FlightManager
import dji.sampleV5.aircraft.dji.GimbalManager
import dji.sampleV5.aircraft.dji.MediaDownloadManager
import dji.sampleV5.aircraft.mqtt.AckPayload
import dji.sampleV5.aircraft.mqtt.DroneStatusPayload
import dji.sampleV5.aircraft.mqtt.MqttClientManager
import dji.sampleV5.aircraft.mqtt.MqttStatusReporter
import dji.sampleV5.aircraft.safety.SafetyAnomalyRecord
import dji.sampleV5.aircraft.safety.SafetyAnomalyType
import dji.sampleV5.aircraft.safety.SafetyEventLogger
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.FlightControlAuthorityChangeReason
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.set
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object MissionStateMachine {

    private const val TAG = "MissionSM"

    // ========== State ==========
    enum class MissionState {
        IDLE, TAKING_OFF, FLYING_TO_TARGET,
        DESCENDING_TO_ORBIT_ALT, ORBITING,
        RECORDING_360, SHOOTING_PHOTOS, RETURNING_HOME, ERROR
    }

    private val _state = MutableStateFlow(MissionState.IDLE)
    val state: StateFlow<MissionState> = _state

    private var missionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var vsListenerSet = false
    private var lastVSState: VirtualStickState? = null
    private var lastVSError: String? = null
    private val isCancelling = AtomicBoolean(false)
    private var missionStartTime: Long = 0L
    private var isDownloading = false

    // ========== Safety Monitoring ==========
    var safetyEventLogger: SafetyEventLogger? = null
    private var gpsWeakStartTime: Long = 0L

    // ========== Public API ==========

    /** 啟主任務流程：自動起飛 → 飛往目標 → 拍攝/環繞 → 返航降落 */
    fun startMission(
        targetLat: Double,
        targetLng: Double,
        targetAlt: Double = AppConfig.getSafeTakeoffHeight().toDouble(),
        missionType: String = "A",
        orbitRadius: Double = 0.0,
        orbitAlt: Double? = null,
        orbitGimbalPitch: Double = -45.0,
        photoGimbalPitch: Double = -45.0
    ) {
        if (_state.value != MissionState.IDLE) {
            Log.w(TAG, "Mission already running, ignored")
            return
        }
        missionStartTime = System.currentTimeMillis()
        Log.d(TAG, "Mission start time recorded: $missionStartTime")

        // Set motor-locked callback for post-landing actions
        if (isDownloading) {
            Log.w(TAG, "[Mission] Warning: previous download still running, new mission may conflict")
            MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"WARNING","message":"Previous download still in progress"}""")
        }
        Log.d(TAG, "Setting motor-locked callback for missionType=$missionType")
        FlightManager.setOnMotorLockedCallback {
            scope.launch {
                Log.d(TAG, "Motor locked callback fired! missionType=$missionType, isDownloading=$isDownloading")
                // Mission A: download photos; Mission B: skip (video only, no photos)
                if (missionType != "B") {
                    isDownloading = true
                    Log.d(TAG, "Motor locked, starting photo download...")
                    MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"DOWNLOADING","message":"Downloading mission photos..."}""")

                    delay(6000L)  // Wait 6s for camera to finish writing to SD card
                    Log.d(TAG, "Delay complete, starting download...")

                    try {
                        val result = MediaDownloadManager.downloadLastNPhotos(AppConfig.MAX_DOWNLOAD_PHOTOS)
                        when (result) {
                            is DownloadResult.Success -> {
                                Log.d(TAG, "Download complete: ${result.count} photos saved")
                                MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"COMPLETE","photos":${result.count}}""")
                            }
                            is DownloadResult.Failure -> {
                                Log.e(TAG, "Download failed: ${result.reason}")
                                MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"DOWNLOAD_ERROR","reason":"${result.reason}"}""")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Download exception: ${e.message}", e)
                        MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"DOWNLOAD_ERROR","reason":"${e.message}"}""")
                    }
                    isDownloading = false
                } else {
                    Log.d(TAG, "Motor locked, Mission B complete (no photo download)")
                    MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"COMPLETE","result":"orbit_video_done"}""")
                }

                // Set IDLE after drone actually landed (motor locked)
                setState(MissionState.IDLE)
                Log.d(TAG, "Mission complete")
                MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"IDLE","result":"success"}""")
                MqttStatusReporter.publishCurrentStatus()
                FlightManager.setOnMotorLockedCallback(null)
            }
        }

        missionJob = scope.launch {
            try {
                // Reset VS state as mission prerequisite
                lastVSState = null
                safeDisableVirtualStick()
                delay(1000)
                Log.d(TAG, "VirtualStick state reset complete")

                executeMission(targetLat, targetLng, targetAlt, missionType, orbitRadius, orbitAlt, orbitGimbalPitch, photoGimbalPitch)
            } catch (e: CancellationException) {
                Log.w(TAG, "Mission coroutine cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "[Mission] startMission exception: ${e.message}")
                if (lastVSError == "RC_NOT_N_MODE") {
                    MqttClientManager.publishAck(AckPayload(
                        msgId = "system",
                        action = "mission",
                        status = "failed",
                        errorMsg = "Please switch RC to N (Normal) mode"
                    ))
                    Log.e(TAG, "Mission aborted: RC not in N mode")
                    setState(MissionState.ERROR)
                } else {
                    cancelMission()
                }
            }
        }
    }

    /** 取消任務：停止錄影、重置雲台、啟動自動返航 */
    fun cancelMission() {
        if (!isCancelling.compareAndSet(false, true)) {
            Log.w(TAG, "cancelMission already in progress, ignored")
            return
        }
        Log.w(TAG, "cancelMission called, initiating safe shutdown")
        if (!isDownloading) {
            FlightManager.setOnMotorLockedCallback(null)
        } else {
            Log.w(TAG, "[Mission] Download in progress, keeping callback alive")
        }
        missionJob?.cancel()
        missionJob = null
        scope.launch {
            try {
                // Remove listener first to prevent re-trigger during RTH
                removeVirtualStickStateListener()
                CameraManager.stopVideoRecord()
                GimbalManager.setPitchAngle(0.0)
                safeDisableVirtualStick()
                FlightManager.startRTH()
            } catch (e: Exception) {
                Log.e(TAG, "cancelMission cleanup error: $e")
            } finally {
                isCancelling.set(false)
            }

            setState(MissionState.RETURNING_HOME)

            // Motor locked callback: auto-reset to IDLE when drone lands
            FlightManager.setOnMotorLockedCallback {
                scope.launch {
                    setState(MissionState.IDLE)
                    MqttClientManager.publish(
                        "${AppConfig.getTopicPrefix()}/status",
                        """{"state":"IDLE","result":"cancelled"}"""
                    )
                    FlightManager.setOnMotorLockedCallback(null)
                }
            }

            // 60s timeout: force IDLE if motor lock never fires
            scope.launch {
                delay(60_000L)
                if (_state.value == MissionState.RETURNING_HOME) {
                    Log.w(TAG, "cancelMission RTH timeout (60s), force IDLE")
                    setState(MissionState.IDLE)
                    MqttClientManager.publish(
                        "${AppConfig.getTopicPrefix()}/status",
                        """{"state":"IDLE","result":"cancelled"}"""
                    )
                    FlightManager.setOnMotorLockedCallback(null)
                }
            }

            Log.w(TAG, "Mission cancelled, RTH initiated")
        }
    }

    /** 重置狀態機為 IDLE，僅在 ERROR 或 IDLE 狀態下有效 */
    fun resetToIdle() {
        if (_state.value == MissionState.ERROR || _state.value == MissionState.IDLE) {
            Log.d(TAG, "[Mission] resetToIdle called, state=${_state.value}")
            _state.value = MissionState.IDLE
            MqttStatusReporter.publishCurrentStatus()
        } else {
            Log.w(TAG, "[Mission] resetToIdle ignored, mission in progress state=${_state.value}")
        }
    }

    // ========== Segmented Test Commands ==========

    /** 測試用：自動起飛至安全高度 */
    fun testTakeoff() {
        if (_state.value != MissionState.IDLE) {
            Log.w(TAG, "Mission not IDLE, TEST_TAKEOFF ignored")
            return
        }
        missionJob = scope.launch {
            try {
                setState(MissionState.TAKING_OFF)
                suspendCancellableCoroutine<Unit> { cont ->
                    FlightManager.takeOff(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                        override fun onSuccess(value: EmptyMsg) {
                            if (cont.isActive) cont.resume(Unit) {}
                        }
                        override fun onFailure(error: IDJIError) {
                            if (cont.isActive) cont.resumeWith(Result.failure(RuntimeException("Takeoff failed: $error")))
                        }
                    })
                }

                // Wait for auto-takeoff to complete (altitude stable > 1.0m)
                var lastAlt = 0.0
                var stableCount = 0
                val takeoffWaitStart = System.currentTimeMillis()
                while (coroutineContext.isActive) {
                    val alt = FlightManager.getCurrentAltitude() ?: 0.0
                    Log.d(TAG, "TEST_TAKEOFF waiting auto-takeoff: alt=${"%.1f".format(alt)}m")
                    if (alt > 1.0 && abs(alt - lastAlt) < 0.1) {
                        stableCount++
                        if (stableCount >= 3) break
                    } else {
                        stableCount = 0
                    }
                    lastAlt = alt
                    if (System.currentTimeMillis() - takeoffWaitStart > 15000L) {
                        throw Exception("Auto-takeoff timeout (15s)")
                    }
                    delay(500)
                }
                Log.d(TAG, "TEST_TAKEOFF auto-takeoff complete, now enabling VirtualStick")

                // Enable VirtualStick for controlled climb
                if (!enableVirtualStickWithRetry()) {
                    throw Exception("Cannot enable VirtualStick for climb")
                }

                // Wait for DJI internal hover protection to release
                Log.d(TAG, "Waiting 3s for DJI internal state to stabilize...")
                delay(3000)

                val climbParam = VirtualStickFlightControlParam().apply {
                    rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
                    rollPitchControlMode = RollPitchControlMode.VELOCITY
                    yawControlMode = YawControlMode.ANGULAR_VELOCITY
                    verticalControlMode = VerticalControlMode.VELOCITY
                }

                val climbStartTime = System.currentTimeMillis()
                while (coroutineContext.isActive) {
                    val alt = FlightManager.getCurrentAltitude() ?: 0.0
                    val takeoffH = AppConfig.getSafeTakeoffHeight()
                    Log.d(TAG, "TEST_TAKEOFF climbing: alt=${"%.1f".format(alt)}m, target=${takeoffH}m, sending throttle=${AppConfig.CLIMB_THROTTLE}")
                    if (alt >= takeoffH) break
                    if (System.currentTimeMillis() - climbStartTime > 30000L) {
                        throw Exception("Climb timeout (30s)")
                    }
                    climbParam.pitch = 0.0
                    climbParam.roll = 0.0
                    climbParam.yaw = 0.0
                    climbParam.verticalThrottle = AppConfig.CLIMB_THROTTLE.toDouble()
                    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(climbParam)
                    delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
                }

                sendZeroCommand(climbParam)
                delay(500)
                Log.d(TAG, "TEST_TAKEOFF: hovering 10s...")
                MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"TEST_TAKEOFF","phase":"hovering"}""")
                delay(10000)
                Log.d(TAG, "TEST_TAKEOFF: hover complete, RTH")
                setState(MissionState.RETURNING_HOME)
                safeDisableVirtualStick()
                // P0-02 fix: wait for motor lock before setting IDLE
                FlightManager.setOnMotorLockedCallback {
                    scope.launch {
                        setState(MissionState.IDLE)
                        Log.d(TAG, "TEST_TAKEOFF complete, drone landed")
                        MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"IDLE","result":"TEST_TAKEOFF complete"}""")
                        FlightManager.setOnMotorLockedCallback(null)
                    }
                }
                FlightManager.startRTH()
                // Safety timeout
                scope.launch {
                    delay(120_000L)
                    if (_state.value == MissionState.RETURNING_HOME) {
                        Log.w(TAG, "TEST_TAKEOFF RTH timeout, force IDLE")
                        setState(MissionState.IDLE)
                        FlightManager.setOnMotorLockedCallback(null)
                    }
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "TEST_TAKEOFF cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "TEST_TAKEOFF failed: ${e.message}", e)
                cancelMission()
                setState(MissionState.ERROR)
            }
        }
    }

    /** 測試用：原地 360° yaw 旋轉後返航 */
    fun testYaw() {
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
                val param = VirtualStickFlightControlParam().apply {
                    rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
                    rollPitchControlMode = RollPitchControlMode.VELOCITY
                    yawControlMode = YawControlMode.ANGULAR_VELOCITY
                    verticalControlMode = VerticalControlMode.VELOCITY
                }
                Log.d(TAG, "TEST_YAW: rotating 360°...")
                MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"TEST_YAW","phase":"rotating"}""")
                var totalYaw = 0.0
                var lastYaw = FlightManager.getCurrentYaw()
                val yawStartTime = System.currentTimeMillis()
                while (coroutineContext.isActive && totalYaw < 355.0) {
                    if (System.currentTimeMillis() - yawStartTime > 60000L) {
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
                    Log.d(TAG, "Yaw loop: current=$currentYaw, delta=${"%.2f".format(delta)}, total=${"%.1f".format(totalYaw)}")
                }
                sendZeroCommand(param)
                delay(500)
                Log.d(TAG, "TEST_YAW: rotation complete, RTH")
                setState(MissionState.RETURNING_HOME)
                safeDisableVirtualStick()
                // P0-02 fix: wait for motor lock before setting IDLE
                FlightManager.setOnMotorLockedCallback {
                    scope.launch {
                        setState(MissionState.IDLE)
                        Log.d(TAG, "TEST_YAW complete, drone landed")
                        MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"IDLE","result":"TEST_YAW complete"}""")
                        FlightManager.setOnMotorLockedCallback(null)
                    }
                }
                FlightManager.startRTH()
                scope.launch {
                    delay(120_000L)
                    if (_state.value == MissionState.RETURNING_HOME) {
                        Log.w(TAG, "TEST_YAW RTH timeout, force IDLE")
                        setState(MissionState.IDLE)
                        FlightManager.setOnMotorLockedCallback(null)
                    }
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "TEST_YAW cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "TEST_YAW failed: ${e.message}", e)
                cancelMission()
                setState(MissionState.ERROR)
            }
        }
    }

    /** 測試用：每旋轉 60° 拍一張照片後返航 */
    fun testPhoto() {
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
                delay(500)
                val param = VirtualStickFlightControlParam().apply {
                    rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
                    rollPitchControlMode = RollPitchControlMode.VELOCITY
                    yawControlMode = YawControlMode.ANGULAR_VELOCITY
                    verticalControlMode = VerticalControlMode.VELOCITY
                }
                repeat(AppConfig.TEST_PHOTO_COUNT) { i ->
                    // Rotate 60 degrees
                    var rotated = 0.0
                    var lastY = FlightManager.getCurrentYaw()
                    val rotStartTime = System.currentTimeMillis()
                    while (coroutineContext.isActive && rotated < 58.0) {
                        if (System.currentTimeMillis() - rotStartTime > 15000L) {
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
                        Log.d(TAG, "TEST_PHOTO photo${i + 1} yaw: current=$y, delta=${"%.2f".format(d)}, rotated=${"%.1f".format(rotated)}°")
                    }
                    sendZeroCommand(param)
                    delay(1000)
                    suspendCancellableCoroutine<Unit> { cont ->
                        CameraManager.takePhoto(object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {
                                Log.d(TAG, "TEST_PHOTO: photo ${i + 1}/${AppConfig.TEST_PHOTO_COUNT} taken")
                                if (cont.isActive) cont.resume(Unit) {}
                            }
                            override fun onFailure(error: IDJIError) {
                                Log.e(TAG, "TEST_PHOTO: photo ${i + 1}/${AppConfig.TEST_PHOTO_COUNT} failed: $error")
                                if (cont.isActive) cont.resume(Unit) {}
                            }
                        })
                    }
                    delay(2000)
                    MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"TEST_PHOTO","photo":${i + 1},"total":${AppConfig.TEST_PHOTO_COUNT}}""")
                }
                Log.d(TAG, "TEST_PHOTO: ${AppConfig.TEST_PHOTO_COUNT} photos complete, RTH")
                setState(MissionState.RETURNING_HOME)
                safeDisableVirtualStick()
                // P0-02 fix: wait for motor lock before setting IDLE
                FlightManager.setOnMotorLockedCallback {
                    scope.launch {
                        setState(MissionState.IDLE)
                        Log.d(TAG, "TEST_PHOTO complete, drone landed")
                        MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"IDLE","result":"TEST_PHOTO complete"}""")
                        FlightManager.setOnMotorLockedCallback(null)
                    }
                }
                FlightManager.startRTH()
                scope.launch {
                    delay(120_000L)
                    if (_state.value == MissionState.RETURNING_HOME) {
                        Log.w(TAG, "TEST_PHOTO RTH timeout, force IDLE")
                        setState(MissionState.IDLE)
                        FlightManager.setOnMotorLockedCallback(null)
                    }
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "TEST_PHOTO cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "TEST_PHOTO failed: ${e.message}", e)
                cancelMission()
                setState(MissionState.ERROR)
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
        photoGimbalPitch: Double
    ) {
        // GPS check
        if (!FlightManager.isGpsReadyForMission()) {
            Log.e(TAG, "GPS not ready, mission aborted")
            MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"ERROR","reason":"GPS signal too weak"}""")
            setState(MissionState.ERROR)
            return
        }

        // Record home position
        val homeLoc = FlightManager.getCurrentLocation()
        if (homeLoc == null) {
            Log.e(TAG, "Cannot get home location, mission aborted")
            MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"ERROR","reason":"No GPS location"}""")
            setState(MissionState.ERROR)
            return
        }
        Log.d(TAG, "Home location: lat=${homeLoc.latitude}, lng=${homeLoc.longitude}")

        // Distance check
        val distToTarget = haversine(homeLoc.latitude, homeLoc.longitude, targetLat, targetLng)
        val maxRadius = AppConfig.getMaxFlightRadius().toDouble()
        if (distToTarget > maxRadius) {
            Log.e(TAG, "Target too far: ${"%.0f".format(distToTarget)}m > ${"%.0f".format(maxRadius)}m, aborted")
            MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"ERROR","reason":"Target exceeds safe radius"}""")
            setState(MissionState.ERROR)
            return
        }

        // TAKING_OFF
        setState(MissionState.TAKING_OFF)
        Log.d(TAG, "Taking off...")
        suspendCancellableCoroutine<Unit> { cont ->
            FlightManager.takeOff(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(value: EmptyMsg) {
                    Log.d(TAG, "Takeoff command accepted")
                    if (cont.isActive) cont.resume(Unit) {}
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Takeoff failed: $error")
                    if (cont.isActive) cont.resumeWith(Result.failure(RuntimeException("Takeoff failed: $error")))
                }
            })
        }

        // Wait for auto-takeoff to complete (altitude stable > 1.0m)
        var lastAlt = 0.0
        var stableCount = 0
        val takeoffWaitStart = System.currentTimeMillis()
        while (coroutineContext.isActive) {
            val alt = FlightManager.getCurrentAltitude() ?: 0.0
            Log.d(TAG, "Waiting auto-takeoff: alt=${"%.1f".format(alt)}m")
            if (alt > 1.0 && abs(alt - lastAlt) < 0.1) {
                stableCount++
                if (stableCount >= 3) break
            } else {
                stableCount = 0
            }
            lastAlt = alt
            if (System.currentTimeMillis() - takeoffWaitStart > 15000L) {
                throw Exception("Auto-takeoff timeout (15s)")
            }
            delay(500)
        }
        Log.d(TAG, "Auto-takeoff complete, now enabling VirtualStick")

        // Enable VirtualStick for controlled climb
        if (!enableVirtualStickWithRetry()) {
            throw Exception("Cannot enable VirtualStick for climb")
        }
        Log.d(TAG, "Waiting 3s for DJI internal state to stabilize...")
        delay(3000)
        Log.d(TAG, "VirtualStick enabled, climbing to target altitude")

        val climbParam = VirtualStickFlightControlParam().apply {
            rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
        }

        // Phase 1: Climb to target altitude (no yaw)
        val climbStartTime = System.currentTimeMillis()
        while (coroutineContext.isActive) {
            val alt = FlightManager.getCurrentAltitude() ?: 0.0
            Log.d(TAG, "Climbing: alt=${"%.1f".format(alt)}m, target=${"%.1f".format(targetAlt)}m")
            if (alt >= targetAlt) break
            if (System.currentTimeMillis() - climbStartTime > 30000L) {
                throw Exception("Climb timeout (30s)")
            }
            climbParam.pitch = 0.0
            climbParam.roll = 0.0
            climbParam.yaw = 0.0
            climbParam.verticalThrottle = AppConfig.CLIMB_THROTTLE.toDouble()
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(climbParam)
            delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
        }
        sendZeroCommand(climbParam)
        delay(500)
        Log.d(TAG, "Climb complete, altitude reached")

        // Phase 2: Rotate nose to face target (climb done, hover in place)
        val yawStartTime = System.currentTimeMillis()
        while (coroutineContext.isActive) {
            val loc = FlightManager.getCurrentLocation() ?: break
            val bearingToTarget = calculateBearing(loc.latitude, loc.longitude, targetLat, targetLng)
            val heading = FlightManager.heading.value
            var yawErr = bearingToTarget - heading
            if (yawErr > 180) yawErr -= 360
            if (yawErr < -180) yawErr += 360
            Log.d(TAG, "YawToTarget: bearing=${"%.1f".format(bearingToTarget)}°, heading=${"%.1f".format(heading)}°, err=${"%.1f".format(yawErr)}°")
            if (abs(yawErr) < 3.0) break
            if (System.currentTimeMillis() - yawStartTime > 15000L) {
                Log.w(TAG, "Yaw to target timeout, proceeding anyway")
                break
            }
            climbParam.pitch = 0.0
            climbParam.roll = 0.0
            climbParam.verticalThrottle = 0.0
            climbParam.yaw = (yawErr * 0.8).coerceIn(-AppConfig.YAW_SPEED_DEG_PER_SEC.toDouble(), AppConfig.YAW_SPEED_DEG_PER_SEC.toDouble())
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(climbParam)
            delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
        }
        sendZeroCommand(climbParam)
        delay(500)
        Log.d(TAG, "Yaw alignment complete")
        // VirtualStick stays enabled for FLYING_TO_TARGET

        // FLYING_TO_TARGET
        setState(MissionState.FLYING_TO_TARGET)

        val param = VirtualStickFlightControlParam().apply {
            rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
        }

        while (coroutineContext.isActive) {
            val current = FlightManager.getCurrentLocation()
            if (current == null) {
                Log.w(TAG, "Cannot get current location, retrying...")
                delay(100)
                continue
            }
            val distance = haversine(current.latitude, current.longitude, targetLat, targetLng)
            val bearing = calculateBearing(current.latitude, current.longitude, targetLat, targetLng)
            val bearingRad = Math.toRadians(bearing)

            if (distance < AppConfig.ARRIVAL_THRESHOLD_M) break

            // Proportional speed: slow down near target to prevent overshoot
            val speed = min((distance * 0.5).toFloat(), AppConfig.VIRTUAL_STICK_MAX_SPEED)
            val pitch = (speed * sin(bearingRad)).toFloat()   // east component → pitch(+) = go east
            val roll = (speed * cos(bearingRad)).toFloat()    // north component → roll(+) = go north

            // Yaw: rotate nose to face target direction
            val currentHeading = FlightManager.heading.value
            var yawError = bearing - currentHeading
            if (yawError > 180) yawError -= 360
            if (yawError < -180) yawError += 360
            val yawSpeed = when {
                abs(yawError) < 2.0 -> 0.0
                abs(yawError) < 15.0 -> yawError * 0.8
                else -> AppConfig.YAW_SPEED_DEG_PER_SEC * if (yawError > 0) 1.0 else -1.0
            }
            Log.d(TAG, "FlyingToTarget: dist=${"%.1f".format(distance)}m, bearing=${"%.1f".format(bearing)}°, heading=${"%.1f".format(currentHeading)}°, yawErr=${"%.1f".format(yawError)}°, yawCmd=${"%.1f".format(yawSpeed)}°/s")

            param.pitch = pitch.toDouble()
            param.roll = roll.toDouble()
            param.yaw = yawSpeed
            param.verticalThrottle = 0.0
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
            delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
        }

        // Stop movement
        sendZeroCommand(param)
        delay(500)
        Log.d(TAG, "Arrived at target")

        // DESCENDING_TO_ORBIT_ALT (if orbit.alt is set and lower than current)
        val effectiveOrbitAlt = orbitAlt ?: targetAlt
        val currentAltBeforeOrbit = FlightManager.getCurrentAltitude() ?: targetAlt
        if (effectiveOrbitAlt < currentAltBeforeOrbit - 0.5) {
            setState(MissionState.DESCENDING_TO_ORBIT_ALT)
            Log.d(TAG, "Descending from ${"%.1f".format(currentAltBeforeOrbit)}m to orbit alt ${"%.1f".format(effectiveOrbitAlt)}m")
            val descStartTime = System.currentTimeMillis()
            while (coroutineContext.isActive) {
                val alt = FlightManager.getCurrentAltitude() ?: 0.0
                if (alt <= effectiveOrbitAlt + 0.3) break
                if (System.currentTimeMillis() - descStartTime > 30000L) {
                    throw Exception("Descent timeout (30s)")
                }
                param.pitch = 0.0; param.roll = 0.0; param.yaw = 0.0
                param.verticalThrottle = -AppConfig.CLIMB_THROTTLE.toDouble()
                VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
            }
            sendZeroCommand(param)
            delay(500)
            Log.d(TAG, "Descent complete, now at orbit altitude")
        }

        // Recording phase: B mode = orbit, A mode = in-place rotation
        if (missionType == "B" && orbitRadius > 0.0) {
            executeOrbitPhase(targetLat, targetLng, effectiveOrbitAlt, orbitRadius, orbitGimbalPitch, param)
        } else {
            executeInPlaceRotation(orbitGimbalPitch, param)
        }

        // SHOOTING_PHOTOS (A mode only — B mode skips photos)
        if (missionType != "B") {
            setState(MissionState.SHOOTING_PHOTOS)

            // 1. Disable VirtualStick first — MSDK may reclaim control after stopVideoRecord
            safeDisableVirtualStick()
            Log.d(TAG, "VirtualStick disabled, waiting for MSDK state to settle...")
            delay(2000)

            // 2. Switch camera to photo mode (VS disabled)
            CameraManager.setCameraMode(true)
            delay(2000)

            // 3. Re-enable VirtualStick for yaw rotation during photo sequence
            if (!enableVirtualStickWithRetry()) {
                throw Exception("Cannot re-enable VirtualStick for photos")
            }
            Log.d(TAG, "VirtualStick re-enabled for photo sequence")

            // Phase 1: Gimbal to photo angle, rotate and take 6 photos
            Log.d(TAG, "Phase 1: Moving gimbal to ${photoGimbalPitch}°")
            var phase1Reached = false
            repeat(3) { attempt ->
                GimbalManager.setPitchAngle(photoGimbalPitch)
                if (GimbalManager.waitForPitchReady(photoGimbalPitch)) {
                    phase1Reached = true
                    Log.d(TAG, "Gimbal reached ${photoGimbalPitch}° on attempt ${attempt + 1}")
                    return@repeat
                }
                Log.w(TAG, "Gimbal ${photoGimbalPitch}° not reached on attempt ${attempt + 1}, retrying...")
                delay(500)
            }
            if (!phase1Reached) {
                Log.e(TAG, "WARNING: Gimbal could not reach ${photoGimbalPitch}°, proceeding at current angle")
            }
            Log.d(TAG, "Starting 6-photo sequence")

            repeat(6) { i ->
                // Rotate yaw 60° (VS re-enabled only for rotation)
                rotateYawForPhoto(60.0)
                delay(1000)

                // Take photo (VS disabled, voltage stable)
                suspendCancellableCoroutine<Unit> { cont ->
                    CameraManager.takePhoto(object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() {
                            Log.d(TAG, "Photo ${i + 1}/6 taken at ${photoGimbalPitch}°")
                            if (cont.isActive) cont.resume(Unit) {}
                        }
                        override fun onFailure(error: IDJIError) {
                            Log.e(TAG, "Photo ${i + 1}/6 failed: $error")
                            if (cont.isActive) cont.resume(Unit) {}
                        }
                    })
                }
                delay(2000)
                MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"SHOOTING_PHOTOS","phase":"${photoGimbalPitch}deg","photo":${i + 1},"total":7}""")
            }
            Log.d(TAG, "Phase 1 complete: 6 photos at ${photoGimbalPitch}°")

            // Phase 2: Gimbal -90° (nadir), take 1 photo (VS already disabled)
            Log.d(TAG, "Phase 2: Moving gimbal to -90° (nadir)")
            var nadirReached = false
            repeat(3) { attempt ->
                GimbalManager.setPitchAngle(-90.0)
                if (GimbalManager.waitForPitchReady(-90.0)) {
                    nadirReached = true
                    Log.d(TAG, "Gimbal reached -90° on attempt ${attempt + 1}")
                    return@repeat
                }
                Log.w(TAG, "Gimbal -90° not reached on attempt ${attempt + 1}, retrying...")
                delay(500)
            }
            if (!nadirReached) {
                Log.e(TAG, "WARNING: Gimbal could not reach -90°, taking photo at current angle anyway")
            }
            delay(1000)

            suspendCancellableCoroutine<Unit> { cont ->
                CameraManager.takePhoto(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.d(TAG, "Photo 7/7 taken at -90° (nadir)")
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                    override fun onFailure(error: IDJIError) {
                        Log.e(TAG, "Photo 7/7 failed: $error")
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                })
            }
            delay(2000)
            MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"SHOOTING_PHOTOS","phase":"nadir","photo":7,"total":7}""")
            Log.d(TAG, "Phase 2 complete: nadir photo taken")

            // Reset gimbal to forward position
            GimbalManager.setPitchAngle(0.0)
            GimbalManager.waitForPitchReady(0.0)
            Log.d(TAG, "Gimbal reset to 0°, photos complete")
        }

        // Climb back to target.alt before RTH (if orbit.alt was lower)
        val altBeforeRTH = FlightManager.getCurrentAltitude() ?: targetAlt
        if (altBeforeRTH < targetAlt - 0.5) {
            Log.d(TAG, "Climbing from ${"%.1f".format(altBeforeRTH)}m to target.alt ${"%.1f".format(targetAlt)}m before RTH")
            if (!enableVirtualStickWithRetry()) {
                throw Exception("Cannot enable VirtualStick for pre-RTH climb")
            }
            val climbParam = VirtualStickFlightControlParam().apply {
                rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
                rollPitchControlMode = RollPitchControlMode.VELOCITY
                yawControlMode = YawControlMode.ANGULAR_VELOCITY
                verticalControlMode = VerticalControlMode.VELOCITY
            }
            val climbStart = System.currentTimeMillis()
            while (coroutineContext.isActive) {
                val alt = FlightManager.getCurrentAltitude() ?: 0.0
                if (alt >= targetAlt) break
                if (System.currentTimeMillis() - climbStart > 30000L) {
                    Log.w(TAG, "Pre-RTH climb timeout, proceeding with RTH at current altitude")
                    break
                }
                climbParam.pitch = 0.0; climbParam.roll = 0.0; climbParam.yaw = 0.0
                climbParam.verticalThrottle = AppConfig.CLIMB_THROTTLE.toDouble()
                VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(climbParam)
                delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
            }
            sendZeroCommand(climbParam)
            delay(500)
            safeDisableVirtualStick()
            Log.d(TAG, "Pre-RTH climb complete")
        }

        // RETURNING_HOME
        setState(MissionState.RETURNING_HOME)
        safeDisableVirtualStick()
        suspendCancellableCoroutine<Unit> { cont ->
            FlightManager.startRTH(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(value: EmptyMsg) {
                    Log.d(TAG, "RTH started")
                    if (cont.isActive) cont.resume(Unit) {}
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "RTH failed: $error")
                    if (cont.isActive) cont.resumeWith(Result.failure(RuntimeException("RTH failed: $error")))
                }
            })
        }

        Log.d(TAG, "RTH command accepted, waiting for drone to land...")

        // P0-01 fix: Do NOT set IDLE here — drone is still airborne.
        // State transition to IDLE happens via motor-locked callback (set in startMission)
        // when the drone actually lands and motors stop.

        // Safety timeout: if motor-locked callback never fires, force download + IDLE
        scope.launch {
            delay(120_000L)
            if (_state.value == MissionState.RETURNING_HOME) {
                Log.w(TAG, "RTH timeout (120s), motor-locked callback did not fire")
                FlightManager.setOnMotorLockedCallback(null)
                if (missionType != "B" && !isDownloading) {
                    Log.w(TAG, "Fallback: starting photo download from timeout path")
                    isDownloading = true
                    try {
                        val result = MediaDownloadManager.downloadLastNPhotos(AppConfig.MAX_DOWNLOAD_PHOTOS)
                        when (result) {
                            is DownloadResult.Success -> Log.d(TAG, "Fallback download: ${result.count} photos")
                            is DownloadResult.Failure -> Log.e(TAG, "Fallback download failed: ${result.reason}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Fallback download exception: ${e.message}", e)
                    }
                    isDownloading = false
                }
                setState(MissionState.IDLE)
                MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"IDLE","result":"success","note":"timeout"}""")
                MqttStatusReporter.publishCurrentStatus()
            }
        }
    }

    // ========== Orbit / Rotation Phases ==========

    /** A 模式：原地 360° yaw 旋轉錄影 */
    private suspend fun executeInPlaceRotation(gimbalPitch: Double, param: VirtualStickFlightControlParam) {
        setState(MissionState.RECORDING_360)

        // Set gimbal angle before recording
        if (gimbalPitch != 0.0) {
            Log.d(TAG, "Setting gimbal to ${gimbalPitch}° for 360 recording")
            GimbalManager.setPitchAngle(gimbalPitch)
            GimbalManager.waitForPitchReady(gimbalPitch)
            delay(500)
        }

        CameraManager.setCameraMode(false)
        delay(500)

        // Trigger autofocus before recording
        Log.d(TAG, "Triggering autofocus before 360 recording")
        CameraManager.triggerAutoFocus()
        delay(2000)  // Wait for camera to focus

        suspendCancellableCoroutine<Unit> { cont ->
            CameraManager.startVideoRecord(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.d(TAG, "Recording started")
                    if (cont.isActive) cont.resume(Unit) {}
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Start recording failed: $error")
                    if (cont.isActive) cont.resume(Unit) {}
                }
            })
        }

        var totalYaw = 0.0
        var lastYaw = FlightManager.getCurrentYaw()
        val yawStartTime360 = System.currentTimeMillis()
        while (coroutineContext.isActive && totalYaw < 370.0) {
            if (System.currentTimeMillis() - yawStartTime360 > 60000L) {
                Log.e(TAG, "RECORDING_360: Yaw rotation timeout! totalYaw=$totalYaw, forcing exit")
                break
            }
            param.yaw = AppConfig.RECORDING_YAW_SPEED_DEG_PER_SEC.toDouble()
            param.pitch = 0.0; param.roll = 0.0; param.verticalThrottle = 0.0
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
            delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)

            val currentYaw = FlightManager.getCurrentYaw()
            var delta = currentYaw - lastYaw
            if (delta > 180) delta -= 360
            if (delta < -180) delta += 360
            totalYaw += abs(delta)
            lastYaw = currentYaw
            Log.d(TAG, "360Yaw loop: current=$currentYaw, delta=${"%.2f".format(delta)}, total=${"%.1f".format(totalYaw)}")
        }

        sendZeroCommand(param)
        delay(500)

        suspendCancellableCoroutine<Unit> { cont ->
            CameraManager.stopVideoRecord(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.d(TAG, "360 recording stopped")
                    if (cont.isActive) cont.resume(Unit) {}
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Stop recording failed: $error")
                    if (cont.isActive) cont.resume(Unit) {}
                }
            })
        }
        Log.d(TAG, "360 recording complete")

        // Reset gimbal to horizontal
        GimbalManager.setPitchAngle(0.0)
        GimbalManager.waitForPitchReady(0.0)
    }

    /** B 模式：以指定半徑順時針環繞目標 360° 錄影 */
    private suspend fun executeOrbitPhase(
        centerLat: Double,
        centerLng: Double,
        orbitAlt: Double,
        orbitRadius: Double,
        gimbalPitch: Double,
        param: VirtualStickFlightControlParam
    ) {
        setState(MissionState.ORBITING)
        Log.d(TAG, "Starting orbit: radius=${orbitRadius}m, alt=${orbitAlt}m, gimbal=${gimbalPitch}°")

        // Set gimbal angle before recording
        Log.d(TAG, "Setting gimbal to ${gimbalPitch}°")
        GimbalManager.setPitchAngle(gimbalPitch)
        GimbalManager.waitForPitchReady(gimbalPitch)
        delay(500)

        // Start video recording
        CameraManager.setCameraMode(false)
        delay(500)

        // Trigger autofocus before recording
        Log.d(TAG, "Triggering autofocus before orbit recording")
        CameraManager.triggerAutoFocus()
        delay(2000)  // Wait for camera to focus

        // Orbit starting position: North of center at orbitRadius
        val startLat = centerLat + (orbitRadius / 6371000.0) * Math.toDegrees(1.0)
        val startLng = centerLng

        // Move from center to orbit starting position
        if (orbitRadius > 0.5) {
            Log.d(TAG, "Flying to orbit start: ${"%.2f".format(startLat)}, ${"%.2f".format(startLng)} (${orbitRadius}m N of center)")
            while (coroutineContext.isActive) {
                val current = FlightManager.getCurrentLocation() ?: break
                val dist = haversine(current.latitude, current.longitude, startLat, startLng)
                if (dist < 1.0) break
                val bearing = calculateBearing(current.latitude, current.longitude, startLat, startLng)
                val bearingRad = Math.toRadians(bearing)
                val speed = min((dist * 0.5).toFloat(), AppConfig.VIRTUAL_STICK_MAX_SPEED)
                param.pitch = (speed * sin(bearingRad)).toDouble()
                param.roll = (speed * cos(bearingRad)).toDouble()
                // Yaw toward orbit center during approach
                val headingToCenter = calculateBearing(current.latitude, current.longitude, centerLat, centerLng)
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
            sendZeroCommand(param)
            delay(500)
            Log.d(TAG, "Arrived at orbit starting position")
        }

        suspendCancellableCoroutine<Unit> { cont ->
            CameraManager.startVideoRecord(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.d(TAG, "Orbit recording started")
                    if (cont.isActive) cont.resume(Unit) {}
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Orbit recording start failed: $error")
                    if (cont.isActive) cont.resume(Unit) {}
                }
            })
        }

        // Limit angular velocity for stable yaw tracking and camera footage
        // max ~3°/s angular velocity, with minimum orbit time of 60s for small radii
        val minOrbitTimeSec = 60.0
        val maxAngularVelRad = Math.toRadians(360.0 / minOrbitTimeSec) // 6°/s cap
        val minLinearSpeed = 2 * Math.PI * orbitRadius / minOrbitTimeSec
        val orbitSpeed = min(
            AppConfig.VIRTUAL_STICK_MAX_SPEED.toDouble(),
            max(orbitRadius * maxAngularVelRad, minLinearSpeed)
        )
        var prevBearingFromCenter: Double? = null
        var totalAngle = 0.0
        var yawIntegral = 0.0  // PI controller integral accumulator

        // Yaw stabilization: hold position at orbit start + rotate nose toward center
        Log.d(TAG, "Orbit: stabilizing yaw toward center before starting orbit loop")
        val yawStabilizeStart = System.currentTimeMillis()
        while (coroutineContext.isActive && System.currentTimeMillis() - yawStabilizeStart < 10000L) {
            val loc = FlightManager.getCurrentLocation() ?: break
            val bearToCenter = calculateBearing(loc.latitude, loc.longitude, centerLat, centerLng)
            val hdg = FlightManager.heading.value
            var yErr = bearToCenter - hdg
            if (yErr > 180) yErr -= 360
            if (yErr < -180) yErr += 360
            Log.d(TAG, "Orbit stabilize: bearToCenter=${"%.1f".format(bearToCenter)}° heading=${"%.1f".format(hdg)}° yawErr=${"%.1f".format(yErr)}°")
            if (abs(yErr) < 5.0) {
                Log.d(TAG, "Orbit: yaw stabilized (err=${"%.1f".format(yErr)}°)")
                break
            }
            // Position hold: keep drone at orbit starting position
            val distToStart = haversine(loc.latitude, loc.longitude, startLat, startLng)
            if (distToStart > 0.5) {
                val bearingToStart = calculateBearing(loc.latitude, loc.longitude, startLat, startLng)
                val bearingRad = Math.toRadians(bearingToStart)
                val posSpeed = min((distToStart * 0.5).toFloat(), 1.0f)  // max 1 m/s for gentle correction
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
        sendZeroCommand(param)
        delay(200)

        // Start orbit timer AFTER yaw stabilization (Fix #2)
        val orbitStartTime = System.currentTimeMillis()
        Log.d(TAG, "Orbit loop starting, speed=${"%.2f".format(orbitSpeed)}m/s")

        while (coroutineContext.isActive && totalAngle < AppConfig.ORBIT_ANGLE_THRESHOLD) {
            if (System.currentTimeMillis() - orbitStartTime > AppConfig.ORBIT_TIMEOUT_MS) {
                Log.e(TAG, "Orbit timeout, totalAngle=${"%.1f".format(totalAngle)}°")
                break
            }

            val current = FlightManager.getCurrentLocation()
            if (current == null) {
                Log.w(TAG, "Orbit: cannot get location, retrying...")
                delay(100)
                continue
            }

            val distFromCenter = haversine(current.latitude, current.longitude, centerLat, centerLng)
            val bearingFromCenter = calculateBearing(centerLat, centerLng, current.latitude, current.longitude)

            // Track accumulated angle
            if (prevBearingFromCenter != null) {
                var angleDelta = bearingFromCenter - prevBearingFromCenter!!
                if (angleDelta > 180) angleDelta -= 360
                if (angleDelta < -180) angleDelta += 360
                totalAngle += abs(angleDelta)
            }
            prevBearingFromCenter = bearingFromCenter

            // Cross-track error: positive = too far, negative = too close
            val radialError = distFromCenter - orbitRadius

            // Radial correction (proportional control)
            val radialSpeed = (-radialError * AppConfig.ORBIT_KP).coerceIn(-orbitSpeed * 0.5, orbitSpeed * 0.5)

            // GROUND frame: compute tangential + radial velocity in N/E
            val bearingRad = Math.toRadians(bearingFromCenter)
            // Tangential direction (clockwise): (-sin(bearing), cos(bearing))
            val tangentialNorth = -sin(bearingRad) * orbitSpeed
            val tangentialEast = cos(bearingRad) * orbitSpeed
            // Radial correction: toward center = (-sin(bearing), cos(bearing))
            val radialNorth = -sin(bearingRad) * radialSpeed
            val radialEast = cos(bearingRad) * radialSpeed

            // Normalize combined velocity to maintain constant orbit speed (Fix #3)
            val combinedNorth = tangentialNorth + radialNorth
            val combinedEast = tangentialEast + radialEast
            val combinedSpeed = sqrt(combinedNorth * combinedNorth + combinedEast * combinedEast)
            val scale = if (combinedSpeed > 0.01) orbitSpeed / combinedSpeed else 1.0

            param.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
            // DJI GROUND+VELOCITY: pitch(+)=East, roll(+)=North
            param.pitch = combinedEast * scale
            param.roll = combinedNorth * scale

            // Yaw: keep nose pointed at target center (PI control)
            val bearingToCenter = (bearingFromCenter + 180.0) % 360.0
            val currentHeading = FlightManager.heading.value
            var yawError = bearingToCenter - currentHeading
            if (yawError > 180) yawError -= 360
            if (yawError < -180) yawError += 360
            val ORBIT_YAW_KP = 0.6
            val ORBIT_YAW_KI = 0.05
            yawIntegral = (yawIntegral + yawError * 0.05).coerceIn(-50.0, 50.0) // 0.05s = send interval
            param.yaw = (yawError * ORBIT_YAW_KP + yawIntegral * ORBIT_YAW_KI).coerceIn(-30.0, 30.0)
            param.verticalThrottle = 0.0

            // Diagnostic log every ~1s (every 20 iterations at 50ms)
            if (System.currentTimeMillis() % 1000 < 60) {
                Log.d(TAG, "Orbit: bear=${"%.1f".format(bearingFromCenter)}° dist=${"%.1f".format(distFromCenter)}m rErr=${"%.1f".format(radialError)}m " +
                    "tanN=${"%.2f".format(tangentialNorth)} tanE=${"%.2f".format(tangentialEast)} " +
                    "p=${"%.2f".format(param.pitch)} r=${"%.2f".format(param.roll)} yErr=${"%.1f".format(yawError)}° y=${"%.1f".format(param.yaw)}°/s")
            }

            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
            delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
        }

        sendZeroCommand(param)
        delay(500)

        // Stop recording
        suspendCancellableCoroutine<Unit> { cont ->
            CameraManager.stopVideoRecord(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.d(TAG, "Orbit recording stopped")
                    if (cont.isActive) cont.resume(Unit) {}
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Orbit recording stop failed: $error")
                    if (cont.isActive) cont.resume(Unit) {}
                }
            })
        }

        // Reset gimbal to horizontal
        GimbalManager.setPitchAngle(0.0)
        GimbalManager.waitForPitchReady(0.0)
        Log.d(TAG, "Gimbal reset to 0°")

        // Reset to GROUND coordinate system for remaining phases
        param.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
        Log.d(TAG, "Orbit phase complete, totalAngle=${"%.1f".format(totalAngle)}°")
    }

    // ========== VirtualStick Helpers ==========

    /** 啟用 VirtualStick，最多重試 3 次 */
    private suspend fun enableVirtualStickWithRetry(): Boolean {
        // Always force re-enable — DJI may briefly enable VS during takeoff,
        // causing lastVSState to incorrectly appear enabled
        lastVSError = null
        setupVirtualStickStateListener()
        repeat(3) { attempt ->
            var errorString: String? = null
            suspendCancellableCoroutine<Unit> { cont ->
                VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.d(TAG, "enableVirtualStick callback success")
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                    override fun onFailure(error: IDJIError) {
                        errorString = error.toString()
                        Log.w(TAG, "enableVirtualStick callback failed: $error")
                        if (cont.isActive) cont.resume(Unit) {} // don't throw, retry
                    }
                })
            }

            // Non-retryable: RC not in N mode — fail immediately
            if (errorString != null && errorString!!.contains("CONTROL_AUTH_RC_NOT_P_MODE")) {
                lastVSError = "RC_NOT_N_MODE"
                Log.e(TAG, "VirtualStick failed: RC not in N mode (C/S mode). Please switch to N mode.")
                return false
            }

            delay(1000) // Give SDK more time to release control authority

            val vsEnabled = lastVSState?.isVirtualStickEnable ?: false
            if (vsEnabled) {
                VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
                delay(300)
                val advEnabled = lastVSState?.isVirtualStickAdvancedModeEnabled ?: false
                if (advEnabled) {
                    Log.d(TAG, "VirtualStick + AdvancedMode enabled on attempt ${attempt + 1}")
                    return true
                }
            }
            Log.w(TAG, "VirtualStick enable attempt ${attempt + 1} failed, retrying...")
        }
        Log.e(TAG, "VirtualStick enable failed after 3 attempts")
        return false
    }

    /** 安全停用 VirtualStick：先歸零所有控制參數再關閉 */
    private suspend fun safeDisableVirtualStick() {
        val param = VirtualStickFlightControlParam().apply {
            pitch = 0.0; roll = 0.0; yaw = 0.0; verticalThrottle = 0.0
            rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
        }
        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
        delay(200)

        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
        delay(100)

        suspendCancellableCoroutine<Unit> { cont ->
            VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.d(TAG, "VirtualStick disabled safely")
                    if (cont.isActive) cont.resume(Unit) {}
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "disableVirtualStick failed: $error")
                    if (cont.isActive) cont.resume(Unit) {}
                }
            })
        }
        removeVirtualStickStateListener()
        lastVSState = null
        Log.d(TAG, "lastVSState reset to null after disable")
    }

    /** 註冊 VirtualStick 狀態監聽器，偵測控制權丟失 */
    private fun setupVirtualStickStateListener() {
        if (vsListenerSet) return
        VirtualStickManager.getInstance().setVirtualStickStateListener(object : VirtualStickStateListener {
            override fun onVirtualStickStateUpdate(stickState: VirtualStickState) {
                lastVSState = stickState
                if (!stickState.isVirtualStickEnable) {
                    val currentState = _state.value
                    if (currentState == MissionState.IDLE || currentState == MissionState.ERROR || currentState == MissionState.RETURNING_HOME || currentState == MissionState.SHOOTING_PHOTOS || currentState == MissionState.DESCENDING_TO_ORBIT_ALT || currentState == MissionState.ORBITING) {
                        Log.d(TAG, "VirtualStick released during $currentState, normal")
                        return
                    }
                    Log.w(TAG, "VirtualStick control lost during $currentState! Authority: ${stickState.currentFlightControlAuthorityOwner}")
                    MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"ERROR","reason":"VirtualStick control lost"}""")
                    cancelMission()
                }
            }
            override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
                Log.w(TAG, "Flight control authority changed: $reason")
                if (reason != FlightControlAuthorityChangeReason.MSDK_REQUEST && _state.value != MissionState.IDLE) {
                    MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"WARNING","reason":"Authority changed: $reason"}""")
                }
            }
        })
        vsListenerSet = true
        Log.d(TAG, "VirtualStickStateListener registered")
    }

    /** 移除 VirtualStick 狀態監聽器 */
    private fun removeVirtualStickStateListener() {
        if (vsListenerSet) {
            VirtualStickManager.getInstance().clearAllVirtualStickStateListener()
            vsListenerSet = false
            Log.d(TAG, "VirtualStickStateListener removed")
        }
    }

    /** 發送歸零控制指令（所有軸速度為 0） */
    private fun sendZeroCommand(param: VirtualStickFlightControlParam) {
        param.pitch = 0.0; param.roll = 0.0; param.yaw = 0.0; param.verticalThrottle = 0.0
        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
    }

    /** 拍照階段：重新啟用 VS 旋轉指定角度後停用，降低馬達負載 */
    private suspend fun rotateYawForPhoto(degrees: Double) {
        if (!enableVirtualStickWithRetry()) {
            Log.e(TAG, "rotateYawForPhoto: Cannot enable VirtualStick")
            return
        }

        val param = VirtualStickFlightControlParam().apply {
            rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
        }

        var rotated = 0.0
        var lastYaw = FlightManager.getCurrentYaw()
        val startTime = System.currentTimeMillis()
        while (coroutineContext.isActive && rotated < degrees - 2.0) {
            if (System.currentTimeMillis() - startTime > 15000L) {
                Log.e(TAG, "rotateYawForPhoto: Timeout! rotated=${"%.1f".format(rotated)}°")
                break
            }
            param.yaw = AppConfig.YAW_SPEED_DEG_PER_SEC.toDouble()
            param.pitch = 0.0; param.roll = 0.0; param.verticalThrottle = 0.0
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
            delay(AppConfig.VIRTUAL_STICK_SEND_INTERVAL_MS)
            val yaw = FlightManager.getCurrentYaw()
            var delta = yaw - lastYaw
            if (delta > 180) delta -= 360
            if (delta < -180) delta += 360
            rotated += abs(delta)
            lastYaw = yaw
            Log.d(TAG, "rotateYawForPhoto: rotated=${"%.1f".format(rotated)}° / ${degrees}°")
        }

        sendZeroCommand(param)
        delay(200)
        safeDisableVirtualStick()
        Log.d(TAG, "rotateYawForPhoto: done, VS disabled, waiting for voltage...")
        delay(2000)
    }

    // ========== State Management ==========

    /** 更新任務狀態並透過 MQTT 即時通知後端 */
    private fun setState(newState: MissionState, reason: String? = null) {
        _state.value = newState
        Log.d(TAG, "State -> $newState" + (if (reason != null) " (reason=$reason)" else ""))
        val reasonJson = if (reason != null) """","reason":"$reason""" else ""
        MqttClientManager.publish("${AppConfig.getTopicPrefix()}/status", """{"state":"$newState$reasonJson","time":${System.currentTimeMillis()}}""")
        // 只在關鍵時刻發送完整狀態
        if (newState == MissionState.TAKING_OFF ||
            newState == MissionState.RETURNING_HOME ||
            newState == MissionState.IDLE ||
            newState == MissionState.ERROR ||
            newState == MissionState.ORBITING) {
            MqttStatusReporter.publishCurrentStatus()
        }

        // ERROR 狀態自動重置：90 秒後若仍為 ERROR 則恢復 IDLE
        if (newState == MissionState.ERROR) {
            scope.launch {
                delay(90_000L)
                if (_state.value == MissionState.ERROR) {
                    Log.w(TAG, "ERROR state timeout (90s), auto-resetting to IDLE")
                    resetToIdle()
                }
            }
        }
    }

    // ========== Safety Monitoring ==========

    /** 初始化安全監控：綁定電池/GPS 異常事件回調 */
    fun setupSafetyMonitoring(logger: SafetyEventLogger) {
        safetyEventLogger = logger
        FlightManager.onSafetyAnomaly = { anomalyType ->
            scope.launch { handleSafetyAnomaly(anomalyType) }
        }
        FlightManager.onGpsRecovered = {
            resetGpsWeakTimer()
        }
        Log.d(TAG, "Safety monitoring setup complete")
    }

    /** 處理安全異常事件：記錄座標、更新狀態、MQTT 通知後端 */
    private suspend fun handleSafetyAnomaly(anomalyType: SafetyAnomalyType) {
        val record = SafetyAnomalyRecord(
            timestamp = System.currentTimeMillis(),
            eventType = anomalyType.name,
            latitude = FlightManager.currentLat.value ?: 0.0,
            longitude = FlightManager.currentLng.value ?: 0.0,
            altitude = FlightManager.altitude.value,
            batteryPercent = FlightManager.batteryPercent.value,
            gpsSignalLevel = FlightManager.gpsSignalLevel.value ?: 0,
            gpsSatellites = FlightManager.gpsSatellites.value ?: 0,
            reason = anomalyType.mqttReason
        )

        // Log to local CSV
        safetyEventLogger?.logAnomaly(record)

        // Update state machine (only during active flight states)
        val currentState = _state.value
        if (currentState != MissionState.IDLE && currentState != MissionState.ERROR) {
            when (anomalyType) {
                SafetyAnomalyType.LOW_BATTERY_RTH,
                SafetyAnomalyType.BATTERY_FORCED_LAND -> {
                    Log.w(TAG, "Safety: Battery anomaly detected, firmware handling RTH/landing")
                    setState(MissionState.RETURNING_HOME, reason = anomalyType.mqttReason)
                }
                SafetyAnomalyType.GPS_SIGNAL_WEAK,
                SafetyAnomalyType.GPS_SIGNAL_LOST -> {
                    // GPS anomaly: log and notify, but let firmware handle it
                    // If GPS weak persists too long, transition to ERROR
                    if (gpsWeakStartTime == 0L) {
                        gpsWeakStartTime = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - gpsWeakStartTime > AppConfig.GPS_WEAK_TIMEOUT_MS) {
                        Log.e(TAG, "Safety: GPS signal lost for ${AppConfig.GPS_WEAK_TIMEOUT_MS / 1000}s, aborting mission")
                        setState(MissionState.ERROR, reason = "gps_signal_lost_timeout")
                        gpsWeakStartTime = 0L
                    }
                }
            }
        }

        // Publish immediate MQTT notification
        MqttStatusReporter.publishSafetyAnomaly(anomalyType, record)
    }

    /** GPS 訊號恢復時重置弱訊號計時器 */
    fun resetGpsWeakTimer() {
        if (gpsWeakStartTime != 0L) {
            Log.d(TAG, "GPS signal recovered, resetting weak timer")
            gpsWeakStartTime = 0L
        }
    }

    // ========== Math Helpers ==========

    /** Haversine 公式：計算兩 GPS 座標間的距離（公尺） */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /** 計算兩點間的方位角（度，0°=北，順時針） */
    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        var bearing = Math.toDegrees(atan2(y, x))
        if (bearing < 0) bearing += 360
        return bearing
    }
}
