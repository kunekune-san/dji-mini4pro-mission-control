package dji.sampleV5.aircraft.config

import android.content.Context
import dji.v5.utils.common.ContextUtil

/** 應用程式設定單例（編譯期常數 + SharedPreferences 運行時設定） */
object AppConfig {

    private const val PREFS_NAME = "app_settings"

    // ==========================================
    // 編譯期常數（不可在執行期變更）
    // ==========================================

    // ========== SharedPreferences Keys ==========
    const val KEY_MAX_FLIGHT_HEIGHT = "pref_max_flight_height"
    const val KEY_MAX_FLIGHT_RADIUS = "pref_max_flight_radius"
    const val KEY_RTH_HEIGHT = "pref_rth_height"
    const val KEY_SAFE_TAKEOFF_HEIGHT = "pref_safe_takeoff_height"
    const val KEY_APP_RTH_THRESHOLD = "pref_app_rth_threshold"
    const val KEY_MAX_FLIGHT_SPEED = "pref_max_flight_speed"
    const val KEY_SPEED_GAIN = "pref_speed_gain"

    // ========== Flight Safety Parameters ==========
    const val MAX_FLIGHT_RADIUS = 1000.0f        // 公尺（預設值；上限由 Settings 控制）
    const val MAX_FLIGHT_HEIGHT = 120.0f          // 公尺（預設值；上限由 Settings 控制）
    const val RTH_HEIGHT = 30.0f                 // 公尺（預設返航高度）
    const val SAFE_GPS_SATELLITE_MIN = 8
    const val GPS_STABILITY_WINDOW_SIZE = 10         // GPS 穩定性檢查樣本數（~2 秒 @ 5Hz）
    const val GPS_STABILITY_MAX_SPREAD_M = 2.0       // GPS 穩定性最大散佈（公尺）

    // ========== MQTT Configuration (defaults) ==========
    private const val DEFAULT_MQTT_HOST = "broker.emqx.io"
    private const val DEFAULT_MQTT_PORT = "1883"
    private const val DEFAULT_TOPIC_PREFIX = "example/DRONE-001"
    const val MQTT_QOS = 1

    // ========== RC Mode default ==========
    private const val DEFAULT_RC_MODE = "USA"

    // ========== VirtualStick Parameters ==========
    const val VIRTUAL_STICK_MAX_SPEED = 5.0f        // m/s
    const val VIRTUAL_STICK_SEND_INTERVAL_MS = 50L  // ms (20Hz)
    const val ARRIVAL_THRESHOLD_M = 2.0f             // 公尺
    const val SAFE_TAKEOFF_HEIGHT = 3.0f             // 公尺（低空測試）
    const val CLIMB_THROTTLE = 5.0f                  // m/s 垂直速度（DJI Mini 4 Pro 上升/下降上限）
    const val YAW_SPEED_DEG_PER_SEC = 45.0f          // 度/秒
    const val RECORDING_YAW_SPEED_DEG_PER_SEC = 12.0f // 約為 YAW_SPEED 的 27%，錄影時用穩定轉速
    const val MAX_MISSION_RADIUS = 1000.0            // 公尺
    const val MAX_MULTI_STOPS = 10                   // 多站任務最大站數
    const val TEST_PHOTO_COUNT = 2                   // 測試用張數，正式任務為 7

    // ========== Orbit Parameters ==========
    const val ORBIT_TIMEOUT_MS = 180000L              // 環繞超時 3 分鐘（配合降速後的較慢環繞）
    const val ORBIT_ANGLE_THRESHOLD = 370.0           // 完成一圈的角度門檻（+10° buffer 補償漂移）
    const val ORBIT_KP = 0.4                          // 半徑修正比例增益（0.4 抗風+縮小穩態誤差，KD=0.08 搭配壓震盪）
    const val ORBIT_RADIAL_KD = 0.08                   // 半徑修正微分增益（抑制過沖，搭配 GPS 濾波使用）
    const val ORBIT_MAX_SPEED = 8.0f                  // 環繞最大速度 (m/s)，獨立於飛行速度設定
    const val ORBIT_FLYAWAY_FACTOR = 2.5f             // orbit 半徑倍數閾值，超過則判定 flyaway
    const val ORBIT_FLYAWAY_CHECK_INTERVAL = 50       // 每 N 次迭代檢查一次 flyaway
    const val ORBIT_BATTERY_CHECK_INTERVAL_DEG = 90.0 // 每 90° 檢查一次電池

    // ========== Spiral Ascent Parameters (Mission C) ==========
    const val SPIRAL_TIMEOUT_MS = 600000L                // 螺旋上升超時 10 分鐘（配合 maxTurns=10）
    const val SPIRAL_MIN_RADIUS = 5.0                     // 最小螺旋半徑 (m)
    const val SPIRAL_MIN_TURNS = 3                        // 最少旋轉圈數
    const val SPIRAL_MAX_TURNS = 10                       // 最多旋轉圈數
    const val SPIRAL_GIMBAL_HEIGHT_THRESHOLD = 1.7        // 雲台切換高度門檻 (m)
    const val SPIRAL_ALT_KP = 0.8                         // 高度追蹤比例增益
    const val SPIRAL_GIMBAL_UPDATE_INTERVAL_M = 0.5       // 雲台更新間隔 (m 高度變化)
    const val SPIRAL_GIMBAL_UPDATE_INTERVAL_MS = 500L     // 雲台更新最小時間間隔 (ms)，高頻追蹤避免畫面偏移
    const val SPIRAL_MAX_CLIMB_RATE = 5.0f                // 最大垂直速度 (m/s)，DJI Mini 4 Pro 上限
    const val SPIRAL_RAMP_UP_DURATION_SEC = 1.5           // 螺旋啟動加速時間（秒），easeInSine 從 0 平滑過渡至目標速度

    // ========== Expanding Spiral Parameters (Mission D) ==========
    // 半徑倍率限制已移除：改為僅檢查 radius_end > radius 且 radius_end ≤ MAX_MISSION_RADIUS

    // ========== Gimbal Pull-Up (Mission D 結束過渡) ==========
    const val GIMBAL_PULL_UP_DECELERATION_SEC = 2.0           // 雲台拉平前水平漸進減速時間（秒），避免急煞抖動
    const val GIMBAL_PULL_UP_SETTLE_PAUSE_MS = 500L           // 減速完成後穩定暫停（毫秒），確保完全靜止
    const val GIMBAL_PULL_UP_DURATION_SEC = 6.0              // 雲台拉平持續時間（秒），錄影中抬升至 0°
    const val GIMBAL_PULL_UP_HOLD_SEC = 1.0                  // 雲台拉平後定格時間（秒），再停止錄影

    // ========== Orbit Speed Multiplier ==========
    const val ORBIT_SPEED_MIN = 1.0                        // 最慢（每圈 60s，標準）
    const val ORBIT_SPEED_MAX = 5.0                        // 最快（每圈 12s，極快，C/D 使用）
    const val ORBIT_SPEED_MAX_A = 2.0                      // A 模式上限（原地旋轉，避免 rolling shutter）

    // ========== MQTT Disconnect ==========
    const val MQTT_DISCONNECT_RTH_TIMEOUT_MS = 120_000L  // MQTT 斷線超過 120 秒且飛行中自動返航

    // ========== Phase 5: Media Download ==========
    const val DEFAULT_PHOTO_COUNT = 7
    const val MAX_DOWNLOAD_PHOTOS = 7
    const val DOWNLOAD_FOLDER = "DJI_Mission"

    // ========== Safety Monitoring ==========
    const val GPS_SIGNAL_WEAK_THRESHOLD = 3        // GPS 訊號低於此值 = 弱
    const val GPS_SATELLITE_MIN_SAFE = 5           // 低於此值 = 危險
    const val GPS_WEAK_TIMEOUT_MS = 10_000L        // 弱 GPS 持續 10 秒 → ERROR
    const val SAFETY_ANOMALY_DEBOUNCE_MS = 5_000L  // 同類異常防抖間隔
    const val GPS_ANOMALY_CONFIRM_COUNT = 3        // 連續 N 次低訊號才觸發（避免瞬間觸發，每次 ~50ms → ~150ms）

    // ========== Battery Threshold ==========
    const val DEFAULT_APP_RTH_THRESHOLD = 20       // 預設 App RTH 門檻（%）
    const val APP_RTH_THRESHOLD_MIN = 20           // 門檻下限（%）
    const val APP_RTH_THRESHOLD_MAX = 50           // 門檻上限（%）
    const val BATTERY_SAFETY_MARGIN = 10           // 任務電量預估 +10% 安全邊際（僅警告，不阻擋）
    const val FIRMWARE_RTH_THRESHOLD = 30          // 韌體 RTH 門檻（%）
    const val FIRMWARE_FORCED_LAND_THRESHOLD = 15  // 韌體強制降落門檻（%）

    // ========== Mission Timing Constants ==========
    const val CAMERA_WRITE_DELAY_MS = 6000L                // SD 卡寫入等待
    const val VS_STATE_RESET_DELAY_MS = 1000L              // VirtualStick 狀態重置等待
    const val TAKEOFF_ALTITUDE_STABLE_THRESHOLD_M = 1.0    // 起飛穩定高度門檻
    const val TAKEOFF_ALTITUDE_STABILITY_TOLERANCE_M = 0.1 // 高度穩定性容差
    const val TAKEOFF_STABLE_READINGS_REQUIRED = 3         // 連續穩定讀數次數
    const val TAKEOFF_TIMEOUT_MS = 15000L                  // 自動起飛超時
    const val CLIMB_TIMEOUT_MS = 30000L                    // 爬升超時
    const val FLY_TO_TARGET_TIMEOUT_MS = 300_000L          // 飛往目標超時（5 分鐘）
    const val YAW_ALIGNMENT_TIMEOUT_MS = 15000L            // Yaw 對齊超時
    const val YAW_ALIGNMENT_TOLERANCE_DEG = 2.0            // Yaw 對齊容差（度）
    const val YAW_ROTATION_TIMEOUT_MS = 60000L             // Yaw 旋轉超時
    const val YAW_ROTATION_TARGET_DEG = 370.0              // 360° 旋轉完成門檻（+10° buffer）
    const val PHOTO_ROTATION_TARGET_DEG = 58.0             // ~60° 拍照旋轉完成門檻（略低於 60° 防止過沖）
    const val PHOTO_ROTATION_TIMEOUT_MS = 15000L           // 拍照旋轉超時
    const val DJI_STABILIZE_DELAY_MS = 3000L               // DJI 內部狀態穩定等待
    const val HOVER_TEST_DURATION_MS = 10000L              // 測試懸停時間
    const val RTH_TIMEOUT_MS = 120_000L                    // 返航超時
    const val CANCEL_RTH_TIMEOUT_MS = 60_000L              // 取消任務返航超時
    const val ERROR_AUTO_RESET_MS = 90_000L                // ERROR 狀態自動重置
    const val VS_RETRY_DELAY_MS = 1000L                    // VirtualStick 重試間隔
    const val VS_ADVANCED_MODE_DELAY_MS = 300L             // AdvancedMode 啟用等待
    const val CAMERA_MODE_SWITCH_DELAY_MS = 2000L          // 相機模式切換等待
    const val GIMBAL_SETTLE_DELAY_MS = 500L                // 雲台到位後穩定等待
    const val CAMERA_FOCUS_WAIT_MS = 2000L                 // 自動對焦等待
    const val PHOTO_INTER_SHOT_DELAY_MS = 2000L            // 拍照間隔
    const val PHOTO_PRE_SHOT_DELAY_MS = 1000L              // 拍照前準備等待
    const val VS_ZERO_SEND_DELAY_MS = 500L                 // 歸零指令後等待
    const val VS_DISABLE_DELAY_MS = 200L                   // VirtualStick 停用前等待
    const val VS_DISABLE_SETTLE_DELAY_MS = 100L            // AdvancedMode 關閉後等待
    const val VS_DISABLE_TIMEOUT_MS = 5000L                // disableVirtualStick SDK callback 超時
    const val ORBIT_YAW_STABILIZE_TIMEOUT_MS = 10000L      // 環繞 Yaw 穩定超時
    const val ORBIT_YAW_KP = 0.4                           // 環繞 Yaw 比例增益（0.6→0.4，減少擴展螺旋振盪）
    const val ORBIT_YAW_KI = 0.03                          // 環繞 Yaw 積分增益（0.05→0.03，減少積分漂移）
    const val YAW_INTEGRAL_CLAMP = 20.0                    // Yaw 積分項限幅（50→20：前饋主導後 I 項僅處理殘差）
    const val YAW_OUTPUT_CLAMP = 100.0                     // Yaw 輸出限幅（°/s），DJI Mini 4 Pro 硬體上限
    private const val DEFAULT_MAGNETIC_DECLINATION_DEG = 0.0 // 預設磁偏角（不補償）
    const val ORBIT_YAW_FEEDFORWARD_GAIN = 1.0             // Yaw 前饋增益（0.2→1.0：前饋扛主要追隨，bearingRate × 1.0 = 所需 yaw 速率）
    const val ORBIT_YAW_PREDICTION_SEC = 1.0               // Yaw 前饋預測時間（秒）（0.15→1.0：搭配 GAIN=1.0 使前饋 ≈ bearingRate）
    const val POSITION_HOLD_MAX_SPEED = 1.0f               // 位置保持最大速度
    const val YAW_ALIGN_PROPORTIONAL_GAIN = 0.8            // Yaw 對齊比例增益
    const val SPEED_PROPORTIONAL_GAIN = 0.5                // 飛行速度比例增益
    const val MAX_FLIGHT_SPEED_DEFAULT = 5.0f              // 預設最大飛行速度 (m/s)
    const val MAX_FLIGHT_SPEED_MIN = 1.0f                  // 最小可調速度 (m/s)
    const val MAX_FLIGHT_SPEED_MAX = 13.0f                 // 最大可調速度 (m/s)，DJI Mini 4 Pro 上限
    const val SPEED_GAIN_DEFAULT = 0.5                     // 預設比例增益
    const val SPEED_GAIN_MIN = 0.1                         // 最小增益（減速較晚）
    const val SPEED_GAIN_MAX = 1.0                         // 最大增益（減速較早）
    const val MIN_FLIGHT_SPEED = 1.0f                     // 最低飛行速度 (m/s)，防止短距離時被風卡住
    const val MIN_ORBIT_TIME_SEC = 60.0                    // 最短環繞時間（秒）
    const val DOWNLOAD_PULL_DELAY_MS = 3000L               // 檔案列表拉取後等待
    const val YAW_SMALL_ERROR_THRESHOLD_DEG = 2.0          // Yaw 小誤差門檻
    const val YAW_MEDIUM_ERROR_THRESHOLD_DEG = 15.0        // Yaw 中誤差門檻
    const val ALTITUDE_DESCENT_TOLERANCE_M = 0.3           // 下降到位容差
    const val ALTITUDE_COMPARISON_TOLERANCE_M = 0.5        // 高度比較容差
    const val ORBIT_POSITION_HOLD_TOLERANCE_M = 1.0        // 環繞起點到位容差
    const val YAW_STABILIZE_TOLERANCE_DEG = 5.0            // 環繞 Yaw 穩定容差

    // ========== Signal Smoothing (P0: 環繞精度強化) ==========
    const val GPS_EMA_ALPHA_DEFAULT = 0.4                  // GPS 低通濾波係數預設值
    const val GPS_EMA_ALPHA_MIN = 0.2                      // 最小值（更平滑，延遲更大）
    const val GPS_EMA_ALPHA_MAX = 0.8                      // 最大值（更即時，濾波較少）
    const val CMD_SMOOTH_ALPHA_DEFAULT = 0.5               // 指令低通濾波係數預設值（pitch/roll）
    const val CMD_SMOOTH_ALPHA_MIN = 0.3                   // 最小值（更絲滑，響應較慢）
    const val CMD_SMOOTH_ALPHA_MAX = 0.8                   // 最大值（更靈敏，可能微震）
    const val CMD_SMOOTH_YAW_ALPHA = 0.6                   // yaw 指令低通濾波係數（0.8→0.6，更平滑減少振盪）
    const val ORBIT_BEARING_RATE_ALPHA = 0.25              // bearingRate 前饋低通濾波係數（0.15→0.25：前饋主導後需更快收斂，仍保有抑制雜訊）

    // ========== Visual Yaw Assist (方案 A) ==========
    const val VISUAL_TEMPLATE_SIZE = 48                    // 模板邊長（像素，原始解析度）
    const val VISUAL_DOWNSAMPLE = 2                        // 降採樣倍率（減少 SAD 計算量）
    const val VISUAL_SEARCH_RANGE_FRAC = 0.3f              // 搜尋範圍 = 畫面寬 × 此值
    const val VISUAL_YAW_GAIN = 12.0                       // 視覺修正增益（°/s per normalized error）
    const val VISUAL_CONFIDENCE_THRESHOLD = 0.6f           // 信心度門檻（SAD 相似度）
    const val VISUAL_MAX_CORRECTION = 15.0                 // 視覺修正上限（°/s）
    const val VISUAL_SELECT_TIMEOUT_MS = 2000L             // 環繞起點選取等待窗（ms）
    const val VISUAL_CENTER_TIMEOUT_MS = 5000L             // 視覺Yaw居中超時（ms）
    const val VISUAL_CENTER_TOLERANCE = 0.05f              // normalizedX 居中容差（~2.5% 畫面寬度）
    const val VISUAL_PRESELECT_OFFSET_M = 5.0              // 視覺預選取外移距離（公尺）
    const val VISUAL_PRESELECT_GIMBAL_PITCH = -65.0        // 視覺預選取雲台俯角（度）

    // ==========================================
    // 運行時設定（SharedPreferences）
    // ==========================================

    // ========== SharedPreferences getters ==========

    /** 取得 MQTT broker 主機位址 */
    fun getMqttHost(): String {
        return prefs.getString("mqtt_host", DEFAULT_MQTT_HOST) ?: DEFAULT_MQTT_HOST
    }

    /** 取得 MQTT broker 連接埠 */
    fun getMqttPort(): String {
        return prefs.getString("mqtt_port", DEFAULT_MQTT_PORT) ?: DEFAULT_MQTT_PORT
    }

    /** 取得 MQTT broker 完整 URI（tcp://host:port） */
    fun getMqttBrokerUri(): String {
        return "tcp://${getMqttHost()}:${getMqttPort()}"
    }

    /** 取得 MQTT topic 前綴（如 example/DRONE-001） */
    fun getTopicPrefix(): String {
        return prefs.getString("topic_prefix", DEFAULT_TOPIC_PREFIX) ?: DEFAULT_TOPIC_PREFIX
    }

    /** 取得 MQTT 連線使用者名稱 */
    fun getMqttUsername(): String {
        return prefs.getString("mqtt_username", "") ?: ""
    }

    /** 取得 MQTT 連線密碼 */
    fun getMqttPassword(): String {
        return prefs.getString("mqtt_password", "") ?: ""
    }

    /** 儲存 MQTT 連線設定到 SharedPreferences */
    fun saveMqttSettings(host: String, port: String, topicPrefix: String, username: String = "", password: String = "") {
        // 驗證 topic prefix 不含 MQTT 通配符字元
        require(!topicPrefix.contains('#') && !topicPrefix.contains('+') && !topicPrefix.contains(' ')) {
            "Topic prefix contains invalid MQTT characters (#, +, or null)"
        }
        prefs.edit()
            .putString("mqtt_host", host)
            .putString("mqtt_port", port)
            .putString("topic_prefix", topicPrefix)
            .putString("mqtt_username", username)
            .putString("mqtt_password", password)
            .apply()
    }

    /** 取得遙控器搖桿模式（USA/JP/CH） */
    fun getRcMode(): String {
        return prefs.getString("rc_mode", DEFAULT_RC_MODE) ?: DEFAULT_RC_MODE
    }

    /** 取得磁偏角補償值（度，正=東偏，負=西偏） */
    fun getMagneticDeclination(): Double {
        return prefs.getFloat("magnetic_declination", DEFAULT_MAGNETIC_DECLINATION_DEG.toFloat()).toDouble()
    }

    /** 儲存磁偏角補償值 */
    fun saveMagneticDeclination(declination: Double) {
        prefs.edit().putFloat("magnetic_declination", declination.toFloat()).apply()
    }

    /** 取得環繞 Yaw 手動偏移（°/s），正=順時針偏右，負=逆時針偏左 */
    fun getVisualYawOffset(): Double {
        return prefs.getFloat("visual_yaw_offset", 0.0f).toDouble()
    }

    /** 儲存環繞 Yaw 手動偏移 */
    fun saveVisualYawOffset(offset: Double) {
        prefs.edit().putFloat("visual_yaw_offset", offset.toFloat()).apply()
    }

    /** 儲存遙控器搖桿模式 */
    fun saveRcMode(mode: String) {
        prefs.edit().putString("rc_mode", mode).apply()
    }

    // ========== Flight Limit Getters ==========

    /** 取得最大飛行高度限制（公尺） */
    fun getMaxFlightHeight(): Float {
        return prefs.getFloat(KEY_MAX_FLIGHT_HEIGHT, MAX_FLIGHT_HEIGHT.toFloat())
    }

    /** 取得最大飛行半徑限制（公尺） */
    fun getMaxFlightRadius(): Float {
        return prefs.getFloat(KEY_MAX_FLIGHT_RADIUS, MAX_FLIGHT_RADIUS.toFloat())
    }

    /** 取得返航高度（公尺） */
    fun getRthHeight(): Float {
        return prefs.getFloat(KEY_RTH_HEIGHT, RTH_HEIGHT)
    }

    /** 取得安全起飛高度（公尺） */
    fun getSafeTakeoffHeight(): Float {
        return prefs.getFloat(KEY_SAFE_TAKEOFF_HEIGHT, SAFE_TAKEOFF_HEIGHT)
    }

    /** 儲存飛行限制參數（最大高度、半徑、返航高度） */
    fun saveFlightLimits(maxHeight: Float, maxRadius: Float, rthHeight: Float) {
        prefs.edit()
            .putFloat(KEY_MAX_FLIGHT_HEIGHT, maxHeight)
            .putFloat(KEY_MAX_FLIGHT_RADIUS, maxRadius)
            .putFloat(KEY_RTH_HEIGHT, rthHeight)
            .apply()
    }

    /** 儲存安全起飛高度 */
    fun saveSafeTakeoffHeight(height: Float) {
        prefs.edit().putFloat(KEY_SAFE_TAKEOFF_HEIGHT, height).apply()
    }

    /** 取得 App RTH 門檻（%，範圍 20-50） */
    fun getAppRthThreshold(): Int {
        return prefs.getInt(KEY_APP_RTH_THRESHOLD, DEFAULT_APP_RTH_THRESHOLD)
            .coerceIn(APP_RTH_THRESHOLD_MIN, APP_RTH_THRESHOLD_MAX)
    }

    /** 儲存 App RTH 門檻（自動 clamp 到 20-50） */
    fun saveAppRthThreshold(threshold: Int) {
        val clamped = threshold.coerceIn(APP_RTH_THRESHOLD_MIN, APP_RTH_THRESHOLD_MAX)
        prefs.edit().putInt(KEY_APP_RTH_THRESHOLD, clamped).apply()
    }

    /** 取得 ERROR 狀態自動重置時間（毫秒） */
    fun getErrorAutoResetMs(): Long {
        return prefs.getLong("error_auto_reset_ms", ERROR_AUTO_RESET_MS)
    }

    /** 取得最大飛行速度（m/s），範圍 1~13 */
    fun getMaxFlightSpeed(): Float {
        return prefs.getFloat(KEY_MAX_FLIGHT_SPEED, MAX_FLIGHT_SPEED_DEFAULT)
            .coerceIn(MAX_FLIGHT_SPEED_MIN, MAX_FLIGHT_SPEED_MAX)
    }

    /** 儲存最大飛行速度（自動 clamp 到 1~13） */
    fun saveMaxFlightSpeed(speed: Float) {
        val clamped = speed.coerceIn(MAX_FLIGHT_SPEED_MIN, MAX_FLIGHT_SPEED_MAX)
        prefs.edit().putFloat(KEY_MAX_FLIGHT_SPEED, clamped).apply()
    }

    /** 取得速度比例增益，範圍 0.1~1.0（越大接近目標時減速越早） */
    fun getSpeedGain(): Double {
        return prefs.getFloat(KEY_SPEED_GAIN, SPEED_GAIN_DEFAULT.toFloat()).toDouble()
            .coerceIn(SPEED_GAIN_MIN, SPEED_GAIN_MAX)
    }

    /** 儲存速度比例增益（自動 clamp 到 0.1~1.0） */
    fun saveSpeedGain(gain: Double) {
        val clamped = gain.coerceIn(SPEED_GAIN_MIN, SPEED_GAIN_MAX).toFloat()
        prefs.edit().putFloat(KEY_SPEED_GAIN, clamped).apply()
    }

    /** 取得 GPS EMA 濾波係數（0.2~0.8） */
    fun getGpsEmaAlpha(): Double {
        return prefs.getFloat("gps_ema_alpha", GPS_EMA_ALPHA_DEFAULT.toFloat()).toDouble()
            .coerceIn(GPS_EMA_ALPHA_MIN, GPS_EMA_ALPHA_MAX)
    }

    /** 儲存 GPS EMA 濾波係數（自動 clamp） */
    fun saveGpsEmaAlpha(alpha: Double) {
        val clamped = alpha.coerceIn(GPS_EMA_ALPHA_MIN, GPS_EMA_ALPHA_MAX).toFloat()
        prefs.edit().putFloat("gps_ema_alpha", clamped).apply()
    }

    /** 取得指令平滑化係數（0.3~0.8） */
    fun getCmdSmoothAlpha(): Double {
        return prefs.getFloat("cmd_smooth_alpha", CMD_SMOOTH_ALPHA_DEFAULT.toFloat()).toDouble()
            .coerceIn(CMD_SMOOTH_ALPHA_MIN, CMD_SMOOTH_ALPHA_MAX)
    }

    /** 儲存指令平滑化係數（自動 clamp） */
    fun saveCmdSmoothAlpha(alpha: Double) {
        val clamped = alpha.coerceIn(CMD_SMOOTH_ALPHA_MIN, CMD_SMOOTH_ALPHA_MAX).toFloat()
        prefs.edit().putFloat("cmd_smooth_alpha", clamped).apply()
    }

    private val prefs by lazy {
        ContextUtil.getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
