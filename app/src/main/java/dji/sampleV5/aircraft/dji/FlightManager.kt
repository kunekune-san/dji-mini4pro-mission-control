package dji.sampleV5.aircraft.dji

import android.util.Log
import dji.sampleV5.aircraft.config.AppConfig
import dji.sampleV5.aircraft.safety.SafetyAnomalyType
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.remotecontroller.ControlMode
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.flightcontroller.FailsafeAction
import dji.sdk.keyvalue.value.flightcontroller.GoHomePathMode
import dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.listen
import dji.v5.et.set
import dji.v5.manager.KeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object FlightManager {

    private const val TAG = "FlightManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ========== Connection State ==========
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    // ========== Telemetry StateFlows ==========
    private val _altitude = MutableStateFlow(0.0)
    val altitude: StateFlow<Double> = _altitude

    private val _distanceFromHome = MutableStateFlow(0.0)
    val distanceFromHome: StateFlow<Double> = _distanceFromHome

    private val _batteryPercent = MutableStateFlow(0)
    val batteryPercent: StateFlow<Int> = _batteryPercent

    private val _isFlying = MutableStateFlow(false)
    val isFlying: StateFlow<Boolean> = _isFlying

    // ========== GPS StateFlows ==========
    private val _gpsSatellites = MutableStateFlow<Int?>(null)
    val gpsSatellites: StateFlow<Int?> = _gpsSatellites.asStateFlow()

    private val _gpsSignalLevel = MutableStateFlow<Int?>(null)
    val gpsSignalLevel: StateFlow<Int?> = _gpsSignalLevel.asStateFlow()

    private val _currentLat = MutableStateFlow<Double?>(null)
    val currentLat: StateFlow<Double?> = _currentLat.asStateFlow()

    private val _currentLng = MutableStateFlow<Double?>(null)
    val currentLng: StateFlow<Double?> = _currentLng.asStateFlow()

    private val _heading = MutableStateFlow(0.0)
    val heading: StateFlow<Double> = _heading.asStateFlow()

    // ========== Safety Monitoring StateFlows ==========
    private val _isFirmwareRthActive = MutableStateFlow(false)
    val isFirmwareRthActive: StateFlow<Boolean> = _isFirmwareRthActive.asStateFlow()

    private var homeLocation: LocationCoordinate3D? = null

    // Listen-cached values for one-shot queries
    private var cachedYaw: Double = 0.0
    private var cachedLocation: LocationCoordinate3D? = null
    private var cachedMotorsOn: Boolean = false
    private var onMotorLockedCallback: (() -> Unit)? = null

    // ========== Safety Anomaly Detection ==========
    var onSafetyAnomaly: ((SafetyAnomalyType) -> Unit)? = null
    var onGpsRecovered: (() -> Unit)? = null
    private var previousBatteryPct: Int = 100

    // ========== Telemetry Listening ==========

    /** 註冊所有 KeyManager 監聽器（連線、高度、GPS、電池、馬達狀態等） */
    fun startListening() {
        Log.d(TAG, "startListening() called — registering all key listeners")

        // Connection status
        FlightControllerKey.KeyConnection.create().listen(this) { connected ->
            val status = if (connected == true) "Connected" else "Disconnected"
            Log.d(TAG, "Aircraft Connection Status: $status")
            _isConnected.value = connected == true
            if (connected == true) {
                writeSafetyParameters()
                applyRcMode()
            }
        }

        // Altitude (relative to takeoff point)
        FlightControllerKey.KeyAltitude.create().listen(this) { altitude ->
            altitude?.let {
                Log.d(TAG, "Listen triggered: KeyAltitude = $it")
                _altitude.value = it
            }
        }

        // Aircraft 3D location — used to compute distance from home
        FlightControllerKey.KeyAircraftLocation3D.create().listen(this) { location ->
            location?.let {
                Log.d(TAG, "Listen triggered: KeyAircraftLocation3D = lat=${it.latitude}, lon=${it.longitude}, alt=${it.altitude}")
                cachedLocation = it
                updateDistanceFromHome(it)
                _currentLat.value = it.latitude
                _currentLng.value = it.longitude
            }
        }

        // Aircraft attitude (yaw) — cached for getCurrentYaw()
        FlightControllerKey.KeyAircraftAttitude.create().listen(this) { attitude ->
            attitude?.let {
                cachedYaw = it.yaw
                _heading.value = it.yaw
                Log.d(TAG, "Listen triggered: KeyAircraftAttitude yaw=${it.yaw}")
            }
        }

        // Home location (KeyHomeLocation returns LocationCoordinate2D)
        FlightControllerKey.KeyHomeLocation.create().listen(this) { location ->
            location?.let {
                Log.d(TAG, "Listen triggered: KeyHomeLocation = lat=${it.latitude}, lon=${it.longitude}")
                homeLocation = LocationCoordinate3D(it.latitude, it.longitude, 0.0)
            }
        }

        // Battery percentage
        BatteryKey.KeyChargeRemainingInPercent.create().listen(this) { percent ->
            percent?.let {
                Log.d(TAG, "Listen triggered: KeyChargeRemainingInPercent = $it%")
                _batteryPercent.value = it

                // Battery threshold detection (downward crossing only)
                if (it <= 25 && previousBatteryPct > 25) {
                    Log.w(TAG, "Battery critical: $it% — firmware may force landing")
                    _isFirmwareRthActive.value = true
                    onSafetyAnomaly?.invoke(SafetyAnomalyType.BATTERY_FORCED_LAND)
                } else if (it <= 50 && previousBatteryPct > 50) {
                    Log.w(TAG, "Battery low: $it% — firmware may trigger RTH")
                    _isFirmwareRthActive.value = true
                    onSafetyAnomaly?.invoke(SafetyAnomalyType.LOW_BATTERY_RTH)
                }
                previousBatteryPct = it
            }
        }

        // Flying state
        FlightControllerKey.KeyIsFlying.create().listen(this) { flying ->
            flying?.let {
                Log.d(TAG, "Listen triggered: KeyIsFlying = $it")
                _isFlying.value = it
            }
        }

        // Motor state — detect motors on→off transition (= landing & locking)
        FlightControllerKey.KeyAreMotorsOn.create().listen(this) { motorsOn ->
            val newValue = motorsOn == true
            Log.d(TAG, "Listen triggered: KeyAreMotorsOn = $newValue (prev=$cachedMotorsOn)")
            if (cachedMotorsOn && !newValue) {
                Log.d(TAG, "Motors locked, triggering post-mission callback")
                onMotorLockedCallback?.invoke()
            }
            cachedMotorsOn = newValue
        }

        // GPS satellite count
        FlightControllerKey.KeyGPSSatelliteCount.create().listen(this) { count ->
            count?.let {
                Log.d(TAG, "Listen triggered: KeyGPSSatelliteCount = $it")
                _gpsSatellites.value = it
            }
        }

        // GPS signal level (enum ordinal: 0=LEVEL_0, 1=LEVEL_1, ...)
        FlightControllerKey.KeyGPSSignalLevel.create().listen(this) { level ->
            level?.let {
                Log.d(TAG, "Listen triggered: KeyGPSSignalLevel = $it")
                _gpsSignalLevel.value = it.ordinal

                // GPS signal degradation detection
                if (it.ordinal < AppConfig.GPS_SIGNAL_WEAK_THRESHOLD) {
                    val satellites = _gpsSatellites.value
                    if (it.ordinal <= 1 || (satellites != null && satellites < AppConfig.GPS_SATELLITE_MIN_SAFE)) {
                        Log.w(TAG, "GPS signal lost: level=$it, satellites=$satellites")
                        onSafetyAnomaly?.invoke(SafetyAnomalyType.GPS_SIGNAL_LOST)
                    } else {
                        Log.w(TAG, "GPS signal weak: level=$it, satellites=$satellites")
                        onSafetyAnomaly?.invoke(SafetyAnomalyType.GPS_SIGNAL_WEAK)
                    }
                } else {
                    // GPS signal recovered
                    onGpsRecovered?.invoke()
                }
            }
        }

        Log.d(TAG, "startListening() — all listeners registered")
    }

    /** 取消所有 KeyManager 監聽器 */
    fun stopListening() {
        KeyManager.getInstance().cancelListen(this)
        Log.d(TAG, "stopListening() — all listeners cancelled")
    }

    // ========== Safety Parameters ==========

    /** 寫入安全參數：最大高度、距離限制、失聯行為、返航高度 */
    fun writeSafetyParameters() {
        val flying = _isFlying.value
        val motorsOn = try { FlightControllerKey.KeyAreMotorsOn.create().get(false) } catch (e: Exception) { false }

        if (flying || motorsOn == true) {
            Log.w(TAG, "writeSafetyParameters skipped: aircraft isFlying=$flying, motorsOn=$motorsOn")
            return
        }

        Log.d(TAG, "writeSafetyParameters: aircraft on ground, writing safety params...")

        val maxHeight = AppConfig.getMaxFlightHeight().toInt()
        val maxRadius = AppConfig.getMaxFlightRadius().toInt()
        val rthHeight = AppConfig.getRthHeight().toInt()

        FlightControllerKey.KeyHeightLimit.create().set(maxHeight) {
            Log.d(TAG, "Safety: Max height set to ${maxHeight}m")
        }

        FlightControllerKey.KeyDistanceLimitEnabled.create().set(true) {
            Log.d(TAG, "Safety: Distance limit enabled")
        }

        FlightControllerKey.KeyDistanceLimit.create().set(maxRadius) {
            Log.d(TAG, "Safety: Max radius set to ${maxRadius}m")
        }

        FlightControllerKey.KeyFailsafeAction.create().set(FailsafeAction.GOHOME) {
            Log.d(TAG, "Safety: Failsafe action set to GOHOME")
        }

        FlightControllerKey.KeyGoHomePathMode.create().set(GoHomePathMode.HEIGHT_NEAR_GROUND) {
            Log.d(TAG, "Safety: GoHome path mode set to HEIGHT_NEAR_GROUND")
        }

        FlightControllerKey.KeyGoHomeHeight.create().set(rthHeight) {
            Log.d(TAG, "Safety: RTH height set to ${rthHeight}m")
        }

        Log.d(TAG, "writeSafetyParameters complete")
    }

    /** 套用遙控器搖桿模式（USA/JP/CH） */
    private fun applyRcMode() {
        val modeStr = AppConfig.getRcMode()
        val mode = when (modeStr) {
            "JP" -> ControlMode.JP
            "CH" -> ControlMode.CH
            else -> ControlMode.USA
        }
        RemoteControllerKey.KeyControlMode.create().set(mode) {
            Log.d(TAG, "RC mode set to $modeStr")
        }
    }

    // ========== GPS Check ==========

    /** 檢查 GPS 是否滿足任務啟動條件（衛星數 ≥ 閾值且訊號 ≥ LEVEL_3） */
    fun isGpsReadyForMission(): Boolean {
        val satelliteCount = try {
            FlightControllerKey.KeyGPSSatelliteCount.create().get(0)
        } catch (e: Exception) {
            Log.e(TAG, "getGPSSatelliteCount failed: $e")
            0
        }

        val signalLevel = try {
            FlightControllerKey.KeyGPSSignalLevel.create().get(GPSSignalLevel.LEVEL_0)
        } catch (e: Exception) {
            Log.e(TAG, "getGPSSignalLevel failed: $e")
            GPSSignalLevel.LEVEL_0
        }

        val countOk = (satelliteCount ?: 0) >= AppConfig.SAFE_GPS_SATELLITE_MIN
        val levelOk = (signalLevel?.ordinal ?: 0) >= GPSSignalLevel.LEVEL_3.ordinal

        Log.d(TAG, "GPS check: satellites=$satelliteCount (need >=${AppConfig.SAFE_GPS_SATELLITE_MIN}), signal=$signalLevel (need >=LEVEL_3), ready=${countOk && levelOk}")
        return countOk && levelOk
    }

    // ========== State Queries ==========

    /** 查詢無人機是否在地面（非飛行狀態） */
    fun isOnGround(): Boolean {
        return try {
            val flying = FlightControllerKey.KeyIsFlying.create().get(true)
            flying == false
        } catch (e: Exception) {
            Log.e(TAG, "isOnGround failed: $e")
            false
        }
    }

    /** 查詢馬達是否運轉中 */
    fun areMotorsOn(): Boolean {
        return try {
            FlightControllerKey.KeyAreMotorsOn.create().get(false) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "areMotorsOn failed: $e")
            false
        }
    }

    /** 取得當前機頭朝向角（度） */
    fun getCurrentYaw(): Double {
        return cachedYaw
    }

    /** 取得當前 GPS 三維座標（快取值） */
    fun getCurrentLocation(): LocationCoordinate3D? {
        return cachedLocation
    }

    /** 設定馬達鎖定（降落完成）後的回調函數 */
    fun setOnMotorLockedCallback(callback: (() -> Unit)?) {
        onMotorLockedCallback = callback
        Log.d(TAG, "Motor locked callback ${if (callback != null) "set" else "cleared"}")
    }

    /** 取得當前相對飛行高度（公尺） */
    fun getCurrentAltitude(): Double? {
        return _altitude.value
    }

    // ========== Active State Fetch ==========

    /** 主動查詢並更新高度與電池狀態（一次性 fetch） */
    fun fetchCurrentState() {
        KeyManager.getInstance().getValue(
            FlightControllerKey.KeyAltitude.create(),
            object : CommonCallbacks.CompletionCallbackWithParam<Double> {
                override fun onSuccess(value: Double) {
                    _altitude.value = value
                    Log.d(TAG, "fetchCurrentState - Altitude: $value")
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "fetchCurrentState - getValue KeyAltitude failed: $error")
                }
            })

        KeyManager.getInstance().getValue(
            BatteryKey.KeyChargeRemainingInPercent.create(),
            object : CommonCallbacks.CompletionCallbackWithParam<Int> {
                override fun onSuccess(value: Int) {
                    _batteryPercent.value = value
                    Log.d(TAG, "fetchCurrentState - Battery: $value%")
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "fetchCurrentState - getValue KeyChargeRemainingInPercent failed: $error")
                }
            })
    }

    // ========== Safety Boundary ==========

    /** 套用安全設定（公開入口，內部呼叫 writeSafetyParameters） */
    fun applySafetyConfig() {
        Log.d(TAG, "applySafetyConfig() called")
        writeSafetyParameters()
    }

    // ========== Flight Control ==========

    /** 執行自動起飛 */
    fun takeOff(callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>? = null) {
        FlightControllerKey.KeyStartTakeoff.create().action({
            Log.d(TAG, "Takeoff success")
            callback?.onSuccess(it)
        }, { e: IDJIError ->
            Log.e(TAG, "Takeoff failed: $e")
            callback?.onFailure(e)
        })
    }

    /** 執行自動降落 */
    fun land(callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>? = null) {
        FlightControllerKey.KeyStartAutoLanding.create().action({
            Log.d(TAG, "Landing success")
            callback?.onSuccess(it)
        }, { e: IDJIError ->
            Log.e(TAG, "Landing failed: $e")
            callback?.onFailure(e)
        })
    }

    /** 啟動自動返航（Return to Home） */
    fun startRTH(callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>? = null) {
        FlightControllerKey.KeyStartGoHome.create().action({
            Log.d(TAG, "RTH started")
            callback?.onSuccess(it)
        }, { e: IDJIError ->
            Log.e(TAG, "RTH failed: $e")
            callback?.onFailure(e)
        })
    }

    // ========== Internal ==========

    /** 更新與起飛點的距離（公尺） */
    private fun updateDistanceFromHome(currentLocation: LocationCoordinate3D) {
        val home = homeLocation ?: return
        _distanceFromHome.value = haversine(
            home.latitude, home.longitude,
            currentLocation.latitude, currentLocation.longitude
        )
    }

    /** Haversine 公式：計算兩 GPS 座標間的距離（公尺） */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
