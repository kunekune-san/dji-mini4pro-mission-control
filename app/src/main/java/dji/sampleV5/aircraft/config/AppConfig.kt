package dji.sampleV5.aircraft.config

import android.content.Context
import dji.v5.utils.common.ContextUtil

object AppConfig {

    private const val PREFS_NAME = "app_settings"

    // ========== SharedPreferences Keys ==========
    const val KEY_MAX_FLIGHT_HEIGHT = "pref_max_flight_height"
    const val KEY_MAX_FLIGHT_RADIUS = "pref_max_flight_radius"
    const val KEY_RTH_HEIGHT = "pref_rth_height"
    const val KEY_SAFE_TAKEOFF_HEIGHT = "pref_safe_takeoff_height"

    // ========== Flight Safety Parameters ==========
    const val MAX_FLIGHT_RADIUS = 1000.0        // meters
    const val MAX_FLIGHT_HEIGHT = 120.0          // meters
    const val RTH_HEIGHT = 30.0f                 // meters
    const val SAFE_GPS_SATELLITE_MIN = 8

    // ========== MQTT Configuration (defaults) ==========
    private const val DEFAULT_MQTT_HOST = "YOUR_MQTT_BROKER_HOST"
    private const val DEFAULT_MQTT_PORT = "1883"
    private const val DEFAULT_TOPIC_PREFIX = "drone/YOUR_DRONE_ID"
    const val MQTT_QOS = 1

    // ========== RC Mode default ==========
    private const val DEFAULT_RC_MODE = "USA"

    // ========== VirtualStick Parameters ==========
    const val VIRTUAL_STICK_MAX_SPEED = 1.5f        // m/s
    const val VIRTUAL_STICK_SEND_INTERVAL_MS = 50L  // ms (20Hz)
    const val ARRIVAL_THRESHOLD_M = 2.0f             // meters
    const val SAFE_TAKEOFF_HEIGHT = 3.0f             // meters (low-altitude test)
    const val CLIMB_THROTTLE = 1.5f                  // m/s vertical throttle during climb
    const val YAW_SPEED_DEG_PER_SEC = 45.0f          // degrees/sec
    const val RECORDING_YAW_SPEED_DEG_PER_SEC = 12.0f // 25% of YAW_SPEED, for stable recording
    const val MAX_MISSION_RADIUS = 1000.0            // meters
    const val TEST_PHOTO_COUNT = 2                   // 測試用張數，正式任務為 7

    // ========== Orbit Parameters ==========
    const val ORBIT_TIMEOUT_MS = 180000L              // 環繞超時 3 分鐘（配合降速後的較慢環繞）
    const val ORBIT_ANGLE_THRESHOLD = 370.0           // 完成一圈的角度門檻（+15° buffer 補償漂移）
    const val ORBIT_KP = 0.2                          // 半徑修正比例增益（0.5 太大會蓋過切線速度）

    // ========== Phase 5: Media Download ==========
    const val MAX_DOWNLOAD_PHOTOS = 7
    const val DOWNLOAD_FOLDER = "DJI_Mission"

    // ========== Safety Monitoring ==========
    const val GPS_SIGNAL_WEAK_THRESHOLD = 3        // GPS 訊號低於此值 = 弱
    const val GPS_SATELLITE_MIN_SAFE = 5           // 低於此值 = 危險
    const val GPS_WEAK_TIMEOUT_MS = 10_000L        // 弱 GPS 持續 10 秒 → ERROR
    const val SAFETY_ANOMALY_DEBOUNCE_MS = 5_000L  // 同類異常防抖間隔

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

    /** 取得 MQTT topic 前綴（如 drone/YOUR_DRONE_ID） */
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

    private val prefs by lazy {
        ContextUtil.getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
