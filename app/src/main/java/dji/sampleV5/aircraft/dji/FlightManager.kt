package dji.sampleV5.aircraft.dji

import android.util.Log
import dji.sampleV5.aircraft.util.AppLog
import dji.sampleV5.aircraft.config.AppConfig
import dji.sampleV5.aircraft.util.GeoMath
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


/** 飛行狀態管理單例，訂閱 KeyManager 取得即時遙測數據 */
object FlightManager {

    private const val TAG = "FlightManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ========== Connection State ==========
    /** 無人機連線狀態 */
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    // ========== Telemetry StateFlows ==========
    /** 相對飛行高度（公尺） */
    private val _altitude = MutableStateFlow(0.0)
    val altitude: StateFlow<Double> = _altitude

    /** 與起飛點距離（公尺） */
    private val _distanceFromHome = MutableStateFlow(0.0)
    val distanceFromHome: StateFlow<Double> = _distanceFromHome

    /** 電池電量百分比 */
    private val _batteryPercent = MutableStateFlow(0)
    val batteryPercent: StateFlow<Int> = _batteryPercent

    /** 是否飛行中 */
    private val _isFlying = MutableStateFlow(false)
    val isFlying: StateFlow<Boolean> = _isFlying

    // ========== GPS StateFlows ==========
    /** GPS 衛星數量 */
    private val _gpsSatellites = MutableStateFlow<Int?>(null)
    val gpsSatellites: StateFlow<Int?> = _gpsSatellites.asStateFlow()

    /** GPS 訊號等級（enum ordinal: 0=LEVEL_0, 1=LEVEL_1, ...） */
    private val _gpsSignalLevel = MutableStateFlow<Int?>(null)
    val gpsSignalLevel: StateFlow<Int?> = _gpsSignalLevel.asStateFlow()

    /** 當前緯度（原始值，供 HUD 顯示） */
    private val _currentLat = MutableStateFlow<Double?>(null)
    val currentLat: StateFlow<Double?> = _currentLat.asStateFlow()

    /** 當前經度（原始值，供 HUD 顯示） */
    private val _currentLng = MutableStateFlow<Double?>(null)
    val currentLng: StateFlow<Double?> = _currentLng.asStateFlow()

    /** 濾波後緯度（EMA，供環繞控制使用） */
    private val _filteredLat = MutableStateFlow<Double?>(null)

    /** 濾波後經度（EMA，供環繞控制使用） */
    private val _filteredLng = MutableStateFlow<Double?>(null)

    /** 機頭朝向角（度） */
    private val _heading = MutableStateFlow(0.0)
    val heading: StateFlow<Double> = _heading.asStateFlow()

    // ========== Safety Monitoring StateFlows ==========
    /** 固件自動返航是否啟動（低電池觸發） */
    private val _isFirmwareRthActive = MutableStateFlow(false)
    val isFirmwareRthActive: StateFlow<Boolean> = _isFirmwareRthActive.asStateFlow()

    /** 韌體估算返航所需電量（%），來自 KeyBatteryPercentNeededToGoHome */
    private val _firmwareRthNeeded = MutableStateFlow(0)
    val firmwareRthNeeded: StateFlow<Int> = _firmwareRthNeeded.asStateFlow()

    /** 有效 RTH 門檻（直接採用使用者設定值） */
    private val _effectiveThreshold = MutableStateFlow(AppConfig.getAppRthThreshold())
    val effectiveThreshold: StateFlow<Int> = _effectiveThreshold.asStateFlow()

    /** 安全警告文字（低電池/GPS 弱等），null 表示無警告 */
    private val _safetyWarning = MutableStateFlow<String?>(null)
    val safetyWarning: StateFlow<String?> = _safetyWarning

    @Volatile private var homeLocation: LocationCoordinate3D? = null

    // 監聽快取值（供一次性查詢使用，避免阻塞 SDK 呼叫）
    @Volatile private var cachedYaw: Double = 0.0
    @Volatile private var cachedLocation: LocationCoordinate3D? = null
    @Volatile private var cachedMotorsOn: Boolean = false
    @Volatile private var onMotorLockedCallback: (() -> Unit)? = null

    // ========== GPS Stability Tracking ==========
    /** 最近 GPS 讀數佇列（用於起飛前穩定性檢查） */
    private val recentLocations = ArrayDeque<Pair<Double, Double>>(AppConfig.GPS_STABILITY_WINDOW_SIZE + 1)

    // ========== Safety Anomaly Detection ==========
    /** 安全異常回調（低電池、GPS 訊號異常等） */
    @Volatile var onSafetyAnomaly: ((SafetyAnomalyType) -> Unit)? = null
    /** GPS 訊號恢復回調 */
    @Volatile var onGpsRecovered: (() -> Unit)? = null
    /** GPS 異常連續確認計數（需連續 N 次低訊號才觸發，避免瞬間觸發） */
    @Volatile private var gpsAnomalyConfirmCount = 0
    private var previousBatteryPct: Int = -1
    private var lastGpsAnomalyTime: Long = 0L

    // ========== Telemetry Listening ==========

    /** 重新計算有效 RTH 門檻：直接採用使用者設定值 */
    private fun updateEffectiveThreshold() {
        _effectiveThreshold.value = AppConfig.getAppRthThreshold()
    }

    /** 註冊所有 KeyManager 監聽器（連線、高度、GPS、電池、馬達狀態等） */
    fun startListening() {
        AppLog.d(TAG, "startListening() called — registering all key listeners")

        // 連線狀態
        FlightControllerKey.KeyConnection.create().listen(this) { connected ->
            val status = if (connected == true) "Connected" else "Disconnected"
            AppLog.d(TAG, "Aircraft Connection Status: $status")
            _isConnected.value = connected == true
            if (connected == true) {
                writeSafetyParameters()
                applyRcMode()
            }
        }

        // 高度（相對於起飛點）
        FlightControllerKey.KeyAltitude.create().listen(this) { altitude ->
            altitude?.let {
                AppLog.d(TAG, "Listen triggered: KeyAltitude = $it")
                _altitude.value = it
            }
        }

        // 無人機三維座標 — 用於計算與起飛點距離
        FlightControllerKey.KeyAircraftLocation3D.create().listen(this) { location ->
            location?.let {
                AppLog.d(TAG, "Listen triggered: KeyAircraftLocation3D = lat=${it.latitude}, lon=${it.longitude}, alt=${it.altitude}")
                cachedLocation = it
                updateDistanceFromHome(it)
                _currentLat.value = it.latitude
                _currentLng.value = it.longitude

                // GPS 穩定性追蹤：記錄最近 N 筆讀數
                synchronized(recentLocations) {
                    recentLocations.addLast(it.latitude to it.longitude)
                    while (recentLocations.size > AppConfig.GPS_STABILITY_WINDOW_SIZE) {
                        recentLocations.removeFirst()
                    }
                }

                // GPS EMA 低通濾波：平滑座標跳動，提升環繞軌跡精度
                val prevLat = _filteredLat.value
                val prevLng = _filteredLng.value
                val alpha = AppConfig.getGpsEmaAlpha()
                if (prevLat == null || prevLng == null) {
                    // 首筆資料直接採用
                    _filteredLat.value = it.latitude
                    _filteredLng.value = it.longitude
                } else {
                    _filteredLat.value = alpha * it.latitude + (1 - alpha) * prevLat
                    _filteredLng.value = alpha * it.longitude + (1 - alpha) * prevLng
                }
            }
        }

        // 機體姿態（yaw）— 快取供 getCurrentYaw() 使用
        // 磁偏角修正：rawYaw + declination → true heading（預設 declination=0，可在設定頁調整）
        FlightControllerKey.KeyAircraftAttitude.create().listen(this) { attitude ->
            attitude?.let {
                val declination = AppConfig.getMagneticDeclination()
                val corrected = (it.yaw + declination).let { y -> if (y < 0) y + 360 else if (y >= 360) y - 360 else y }
                cachedYaw = corrected
                _heading.value = corrected
                AppLog.d(TAG, "Listen triggered: KeyAircraftAttitude yaw=${it.yaw}, corrected=${"%.1f".format(corrected)}° (declination=${"%.1f".format(declination)}°)")
            }
        }

        // 起飛點位置（KeyHomeLocation 回傳 LocationCoordinate2D）
        FlightControllerKey.KeyHomeLocation.create().listen(this) { location ->
            location?.let {
                AppLog.d(TAG, "Listen triggered: KeyHomeLocation = lat=${it.latitude}, lon=${it.longitude}")
                homeLocation = LocationCoordinate3D(it.latitude, it.longitude, 0.0)
            }
        }

        // 電池電量百分比
        BatteryKey.KeyChargeRemainingInPercent.create().listen(this) { percent ->
            percent?.let {
                AppLog.d(TAG, "Listen triggered: KeyChargeRemainingInPercent = $it%")
                _batteryPercent.value = it

                // 跳過首次回調 — 僅記錄基準值，不觸發警報
                if (previousBatteryPct < 0) {
                    previousBatteryPct = it
                    return@let
                }

                // 電池門檻偵測（僅偵測向下穿越）
                val threshold = _effectiveThreshold.value
                if (it <= AppConfig.FIRMWARE_FORCED_LAND_THRESHOLD && previousBatteryPct > AppConfig.FIRMWARE_FORCED_LAND_THRESHOLD) {
                    Log.w(TAG, "Battery critical: $it% — firmware may force landing")
                    _isFirmwareRthActive.value = true
                    _safetyWarning.value = "⚠ 電量極低 ${it}%"
                    onSafetyAnomaly?.invoke(SafetyAnomalyType.BATTERY_FORCED_LAND)
                } else if (it <= threshold && previousBatteryPct > threshold) {
                    Log.w(TAG, "Battery low: $it% (threshold=$threshold) — triggering RTH")
                    _isFirmwareRthActive.value = true
                    _safetyWarning.value = "⚠ 低電量 ${it}%"
                    onSafetyAnomaly?.invoke(SafetyAnomalyType.LOW_BATTERY_RTH)
                } else if (it > threshold && previousBatteryPct <= threshold) {
                    // 電池回升至門檻以上 — 重置韌體 RTH 旗標
                    if (_isFirmwareRthActive.value) {
                        AppLog.d(TAG, "Battery recovered to $it%, resetting firmware RTH flag")
                        _isFirmwareRthActive.value = false
                        _safetyWarning.value = null
                    }
                }
                previousBatteryPct = it
            }
        }

        // 韌體 RTH 電量估算（動態，依距離/Home 更新）
        FlightControllerKey.KeyBatteryPercentNeededToGoHome.create().listen(this) { needed ->
            needed?.let {
                AppLog.d(TAG, "Listen triggered: KeyBatteryPercentNeededToGoHome = $it%")
                _firmwareRthNeeded.value = it
                updateEffectiveThreshold()
            }
        }

        // 飛行狀態
        FlightControllerKey.KeyIsFlying.create().listen(this) { flying ->
            flying?.let {
                AppLog.d(TAG, "Listen triggered: KeyIsFlying = $it")
                _isFlying.value = it
            }
        }

        // 馬達狀態 — 偵測馬達由開轉關（= 降落並鎖定）
        FlightControllerKey.KeyAreMotorsOn.create().listen(this) { motorsOn ->
            val newValue = motorsOn == true
            AppLog.d(TAG, "Listen triggered: KeyAreMotorsOn = $newValue (prev=$cachedMotorsOn)")
            if (cachedMotorsOn && !newValue) {
                AppLog.d(TAG, "Motors locked, triggering post-mission callback")
                onMotorLockedCallback?.invoke()
            }
            cachedMotorsOn = newValue
        }

        // GPS 衛星數量
        FlightControllerKey.KeyGPSSatelliteCount.create().listen(this) { count ->
            count?.let {
                AppLog.d(TAG, "Listen triggered: KeyGPSSatelliteCount = $it")
                _gpsSatellites.value = it
            }
        }

        // GPS 訊號等級（enum ordinal: 0=LEVEL_0, 1=LEVEL_1, ...）
        FlightControllerKey.KeyGPSSignalLevel.create().listen(this) { level ->
            level?.let {
                AppLog.d(TAG, "Listen triggered: KeyGPSSignalLevel = $it")
                _gpsSignalLevel.value = it.ordinal

                // GPS 訊號衰退偵測（含去抖 + 連續確認）
                if (it.ordinal < AppConfig.GPS_SIGNAL_WEAK_THRESHOLD) {
                    gpsAnomalyConfirmCount++
                    AppLog.d(TAG, "GPS anomaly count: $gpsAnomalyConfirmCount/${AppConfig.GPS_ANOMALY_CONFIRM_COUNT}")

                    // 需連續 N 次低訊號才觸發（避免瞬間觸發）
                    if (gpsAnomalyConfirmCount < AppConfig.GPS_ANOMALY_CONFIRM_COUNT) {
                        return@let
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastGpsAnomalyTime < AppConfig.SAFETY_ANOMALY_DEBOUNCE_MS) {
                        AppLog.d(TAG, "GPS anomaly debounced (within ${AppConfig.SAFETY_ANOMALY_DEBOUNCE_MS}ms)")
                        return@let
                    }
                    lastGpsAnomalyTime = now
                    val satellites = _gpsSatellites.value
                    if (it.ordinal <= 1 || (satellites != null && satellites < AppConfig.GPS_SATELLITE_MIN_SAFE)) {
                        Log.w(TAG, "GPS signal lost: level=$it, satellites=$satellites")
                        _safetyWarning.value = "⚠ GPS 訊號遺失"
                        onSafetyAnomaly?.invoke(SafetyAnomalyType.GPS_SIGNAL_LOST)
                    } else {
                        Log.w(TAG, "GPS signal weak: level=$it, satellites=$satellites")
                        _safetyWarning.value = "⚠ GPS 訊號弱"
                        onSafetyAnomaly?.invoke(SafetyAnomalyType.GPS_SIGNAL_WEAK)
                    }
                } else {
                    // GPS 訊號恢復 — 重置計數器
                    gpsAnomalyConfirmCount = 0
                    _safetyWarning.value = null
                    onGpsRecovered?.invoke()
                }
            }
        }

        AppLog.d(TAG, "startListening() — all listeners registered")
    }

    /** 取消所有 KeyManager 監聽器 */
    fun stopListening() {
        KeyManager.getInstance().cancelListen(this)
        AppLog.d(TAG, "stopListening() — all listeners cancelled")
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

        AppLog.d(TAG, "writeSafetyParameters: aircraft on ground, writing safety params...")

        val maxHeight = AppConfig.getMaxFlightHeight().toInt()
        val maxRadius = AppConfig.getMaxFlightRadius().toInt()
        val rthHeight = AppConfig.getRthHeight().toInt()

        FlightControllerKey.KeyHeightLimit.create().set(maxHeight, {
            AppLog.d(TAG, "Safety: Max height set to ${maxHeight}m")
        }, { e -> Log.e(TAG, "Safety: Failed to set max height: $e") })

        FlightControllerKey.KeyDistanceLimitEnabled.create().set(false, {
            AppLog.d(TAG, "Safety: Distance limit disabled (unlimited)")
        }, { e -> Log.e(TAG, "Safety: Failed to disable distance limit: $e") })

        FlightControllerKey.KeyDistanceLimit.create().set(maxRadius, {
            AppLog.d(TAG, "Safety: Max radius set to ${maxRadius}m")
        }, { e -> Log.e(TAG, "Safety: Failed to set max radius: $e") })

        FlightControllerKey.KeyFailsafeAction.create().set(FailsafeAction.GOHOME, {
            AppLog.d(TAG, "Safety: Failsafe action set to GOHOME")
        }, { e -> Log.e(TAG, "Safety: Failed to set failsafe action: $e") })

        FlightControllerKey.KeyGoHomePathMode.create().set(GoHomePathMode.HEIGHT_NEAR_GROUND, {
            AppLog.d(TAG, "Safety: GoHome path mode set to HEIGHT_NEAR_GROUND")
        }, { e -> Log.e(TAG, "Safety: Failed to set gohome path mode: $e") })

        FlightControllerKey.KeyGoHomeHeight.create().set(rthHeight, {
            AppLog.d(TAG, "Safety: RTH height set to ${rthHeight}m")
        }, { e -> Log.e(TAG, "Safety: Failed to set RTH height: $e") })

        // 韌體電池門檻（與 App 動態門檻協同）
        FlightControllerKey.KeyLowBatteryWarningThreshold.create().set(AppConfig.FIRMWARE_RTH_THRESHOLD, {
            AppLog.d(TAG, "Safety: Firmware RTH threshold set to ${AppConfig.FIRMWARE_RTH_THRESHOLD}%")
        }, { e -> Log.e(TAG, "Safety: Failed to set firmware RTH threshold: $e") })

        FlightControllerKey.KeySeriousLowBatteryWarningThreshold.create().set(AppConfig.FIRMWARE_FORCED_LAND_THRESHOLD, {
            AppLog.d(TAG, "Safety: Firmware forced-land threshold set to ${AppConfig.FIRMWARE_FORCED_LAND_THRESHOLD}%")
        }, { e -> Log.e(TAG, "Safety: Failed to set firmware forced-land threshold: $e") })

        AppLog.d(TAG, "writeSafetyParameters complete")
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
            AppLog.d(TAG, "RC mode set to $modeStr")
        }
    }

    // ========== GPS Check ==========

    /**
     * 檢查 GPS 是否滿足任務啟動條件（硬性條件）：
     * 1. 衛星數 ≥ SAFE_GPS_SATELLITE_MIN（8 顆）
     * 2. 訊號等級 ≥ LEVEL_3
     *
     * 另有 GPS 穩定性軟檢查（僅記錄警告，不阻擋起飛）：
     * - 座標散佈 ≥ GPS_STABILITY_MAX_SPREAD_M 時輸出警告
     */
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

        // 軟檢查：GPS 穩定性（僅警告，不阻擋）
        warnIfGpsUnstable()

        AppLog.d(TAG, "GPS check: satellites=$satelliteCount (need >=${AppConfig.SAFE_GPS_SATELLITE_MIN}), " +
            "signal=$signalLevel (need >=LEVEL_3), ready=${countOk && levelOk}")
        return countOk && levelOk
    }

    /**
     * GPS 穩定性軟檢查：散佈過大時記錄警告，不影響起飛決策。
     * 樣本不足時靜默跳過（剛連線尚未累積足夠讀數）。
     */
    private fun warnIfGpsUnstable() {
        val snapshot = synchronized(recentLocations) {
            if (recentLocations.size < AppConfig.GPS_STABILITY_WINDOW_SIZE) return
            recentLocations.toList()
        }
        val first = snapshot.first()
        val last = snapshot.last()
        val spread = GeoMath.haversine(first.first, first.second, last.first, last.second)
        if (spread >= AppConfig.GPS_STABILITY_MAX_SPREAD_M) {
            Log.w(TAG, "GPS unstable: spread=${"%.2f".format(spread)}m (>${AppConfig.GPS_STABILITY_MAX_SPREAD_M}m), orbit accuracy may be reduced")
        } else {
            AppLog.d(TAG, "GPS stable: spread=${"%.2f".format(spread)}m")
        }
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

    /** 查詢馬達是否運轉中（同步 SDK 呼叫） */
    fun areMotorsOn(): Boolean {
        return try {
            FlightControllerKey.KeyAreMotorsOn.create().get(false) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "areMotorsOn failed: $e")
            false
        }
    }

    /** 快取版馬達狀態（由 KeyAreMotorsOn listener 即時更新，無阻塞） */
    fun isMotorsOn(): Boolean = cachedMotorsOn

    /** 取得當前機頭朝向角（度） */
    fun getCurrentYaw(): Double {
        return cachedYaw
    }

    /** 取得當前 GPS 三維座標（原始快取值，供 HUD 顯示） */
    fun getCurrentLocation(): LocationCoordinate3D? {
        return cachedLocation
    }

    /** 取得 EMA 濾波後的 GPS 座標（供環繞控制使用，減少 GPS 跳動） */
    fun getFilteredLocation(): LocationCoordinate3D? {
        val lat = _filteredLat.value ?: return null
        val lng = _filteredLng.value ?: return null
        val alt = cachedLocation?.altitude ?: 0.0
        return LocationCoordinate3D(lat, lng, alt)
    }

    /** 設定馬達鎖定（降落完成）後的回調函數 */
    fun setOnMotorLockedCallback(callback: (() -> Unit)?) {
        onMotorLockedCallback = callback
        AppLog.d(TAG, "Motor locked callback ${if (callback != null) "set" else "cleared"}")
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
                    AppLog.d(TAG, "fetchCurrentState - Altitude: $value")
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
                    AppLog.d(TAG, "fetchCurrentState - Battery: $value%")
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "fetchCurrentState - getValue KeyChargeRemainingInPercent failed: $error")
                }
            })

        KeyManager.getInstance().getValue(
            FlightControllerKey.KeyHomeLocation.create(),
            object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.LocationCoordinate2D> {
                override fun onSuccess(value: dji.sdk.keyvalue.value.common.LocationCoordinate2D) {
                    homeLocation = LocationCoordinate3D(value.latitude, value.longitude, 0.0)
                    AppLog.d(TAG, "fetchCurrentState - HomeLocation: lat=${value.latitude}, lon=${value.longitude}")
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "fetchCurrentState - getValue KeyHomeLocation failed: $error")
                }
            })
    }

    // ========== Safety Boundary ==========

    /** 套用安全設定（公開入口，內部呼叫 writeSafetyParameters） */
    fun applySafetyConfig() {
        AppLog.d(TAG, "applySafetyConfig() called")
        writeSafetyParameters()
    }

    // ========== Flight Control ==========

    /** 執行自動起飛 */
    fun takeOff(callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>? = null) {
        FlightControllerKey.KeyStartTakeoff.create().action({
            AppLog.d(TAG, "Takeoff success")
            callback?.onSuccess(it)
        }, { e: IDJIError ->
            Log.e(TAG, "Takeoff failed: $e")
            callback?.onFailure(e)
        })
    }

    /** 執行自動降落 */
    fun land(callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>? = null) {
        FlightControllerKey.KeyStartAutoLanding.create().action({
            AppLog.d(TAG, "Landing success")
            callback?.onSuccess(it)
        }, { e: IDJIError ->
            Log.e(TAG, "Landing failed: $e")
            callback?.onFailure(e)
        })
    }

    /** 啟動自動返航（Return to Home） */
    fun startRTH(callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>? = null) {
        FlightControllerKey.KeyStartGoHome.create().action({
            AppLog.d(TAG, "RTH started")
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
        _distanceFromHome.value = GeoMath.haversine(
            home.latitude, home.longitude,
            currentLocation.latitude, currentLocation.longitude
        )
    }
}
