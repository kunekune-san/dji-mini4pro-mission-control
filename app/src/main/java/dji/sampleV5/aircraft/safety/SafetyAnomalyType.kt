package dji.sampleV5.aircraft.safety

/** 安全異常類型（電池低電量、GPS 訊號異常、任務異常等） */
enum class SafetyAnomalyType(val mqttReason: String) {
    // 安全異常（原有）
    LOW_BATTERY_RTH("firmware_low_battery_rth"),
    BATTERY_FORCED_LAND("firmware_forced_landing"),
    FIRMWARE_RTH("firmware_rth"),                   // 韌體自行觸發返航（風速過大/訊號遺失/其他）
    GPS_SIGNAL_WEAK("gps_signal_degraded"),
    GPS_SIGNAL_LOST("gps_signal_lost"),

    // 任務異常（新增）
    MISSION_CANCELLED("mission_cancelled"),
    MISSION_VALIDATION_FAIL("mission_validation_failed"),
    MISSION_ERROR("mission_error"),
    SPIRAL_TIMEOUT("spiral_timeout"),
    ORBIT_FLYAWAY("orbit_flyaway"),
    BATTERY_ABORT("battery_abort"),
    RTH_FAILED("rth_failed"),
    VIRTUAL_STICK_FAILED("virtual_stick_failed")
}
