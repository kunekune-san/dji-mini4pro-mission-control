package dji.sampleV5.aircraft.mqtt

import dji.sampleV5.aircraft.config.AppConfig

/**
 * 任務驗證器 — 集中所有 MQTT payload 的驗證規則。
 *
 * 消除 handleMissionStart() 和 handleMultiStart() 之間的重複驗證邏輯。
 * 每個規則函式回傳 null = 通過，回傳 String = 錯誤訊息。
 */
object MissionValidator {

    // ═══════════════════════════════════════════════════════════
    // 通用驗證（所有模式共用）
    // ═══════════════════════════════════════════════════════════

    /** 任務類型必須是 A/B/C/D */
    fun validateMissionType(mission: String): String? {
        if (mission !in setOf("A", "B", "C", "D")) {
            return "Unknown mission: $mission"
        }
        return null
    }

    /** 目標座標範圍檢查 */
    fun validateTarget(target: MissionTarget, maxAlt: Double = 500.0): String? {
        if (target.lat < -90 || target.lat > 90) {
            return "Invalid latitude: ${target.lat}"
        }
        if (target.lng < -180 || target.lng > 180) {
            return "Invalid longitude: ${target.lng}"
        }
        if (target.alt < 0 || target.alt > maxAlt) {
            return "Invalid altitude: ${target.alt} (max: $maxAlt)"
        }
        return null
    }

    /** photoCount 範圍檢查（2~20） */
    fun validatePhotoCount(count: Int): String? {
        if (count < 2 || count > 20) {
            return "Invalid photo_count: $count (must be 2~20)"
        }
        return null
    }

    /** orbit 通用參數檢查（alt、radius、gimbalPitch、photoGimbalPitch、orbitSpeed） */
    fun validateOrbitParams(orbit: MissionOrbit, maxAlt: Double = 500.0): String? {
        if (orbit.alt != null && (orbit.alt < 0 || orbit.alt > maxAlt)) {
            return "Invalid orbit.alt: ${orbit.alt}"
        }
        if (orbit.radius < 0 || orbit.radius > AppConfig.MAX_MISSION_RADIUS) {
            return "Invalid orbit.radius: ${orbit.radius}"
        }
        if (orbit.gimbalPitch !in -130.0..45.0) {
            return "Invalid gimbal_pitch: ${orbit.gimbalPitch} (range: -130 to 45)"
        }
        if (orbit.photoGimbalPitch !in -130.0..45.0) {
            return "Invalid photo_gimbal_pitch: ${orbit.photoGimbalPitch} (range: -130 to 45)"
        }
        if (orbit.orbitSpeed !in AppConfig.ORBIT_SPEED_MIN..AppConfig.ORBIT_SPEED_MAX) {
            return "Invalid orbit_speed: ${orbit.orbitSpeed} (must be ${AppConfig.ORBIT_SPEED_MIN}-${AppConfig.ORBIT_SPEED_MAX})"
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════
    // 模式專屬驗證
    // ═══════════════════════════════════════════════════════════

    /** Mission B：需要 orbit block 且 radius > 0 */
    fun validateMissionB(orbit: MissionOrbit?): String? {
        if (orbit == null || orbit.radius <= 0.0) {
            return "Mission B requires orbit block with radius > 0"
        }
        return null
    }

    /** Mission C 專屬規則（radius、max_alt、turns、spiral 限制） */
    fun validateMissionC(orbit: MissionOrbit?, missionName: String = "C"): String? {
        if (orbit == null || orbit.radius <= 0.0) {
            return "Mission $missionName requires orbit block with radius > 0"
        }
        if (orbit.maxAlt == null || orbit.maxAlt <= 0.0) {
            return "Mission $missionName requires orbit.max_alt > 0"
        }
        if (orbit.alt != null && orbit.maxAlt <= orbit.alt) {
            return "Mission $missionName: max_alt (${orbit.maxAlt}) must be > alt (${orbit.alt})"
        }
        val turns = orbit.turns
        if (turns == null || turns < AppConfig.SPIRAL_MIN_TURNS || turns > AppConfig.SPIRAL_MAX_TURNS) {
            return "Mission $missionName: turns must be ${AppConfig.SPIRAL_MIN_TURNS}-${AppConfig.SPIRAL_MAX_TURNS}"
        }
        if (orbit.radius < AppConfig.SPIRAL_MIN_RADIUS) {
            return "Mission $missionName: radius must be >= ${AppConfig.SPIRAL_MIN_RADIUS}m"
        }
        return null
    }

    /** Mission D 專屬規則（C 的所有規則 + radius_end、final_heading） */
    fun validateMissionD(orbit: MissionOrbit?): String? {
        // 先跑 C 的基礎檢查（傳入 "D" 使錯誤訊息正確顯示 Mission D）
        validateMissionC(orbit, "D")?.let { return it }

        val o = orbit!!  // validateMissionC 已確保非 null

        val radiusEnd = o.radiusEnd
        if (radiusEnd == null || radiusEnd <= o.radius) {
            return "Mission D: radius_end ($radiusEnd) must be > radius (${o.radius})"
        }
        if (radiusEnd > AppConfig.MAX_MISSION_RADIUS) {
            return "Mission D: radius_end ($radiusEnd) must be <= ${AppConfig.MAX_MISSION_RADIUS}m"
        }
        val fh = o.finalHeading
        if (fh != null && (fh < 0.0 || fh > 360.0)) {
            return "Mission D: final_heading ($fh) must be 0~360°"
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════
    // 組合驗證（一次跑完所有相關規則）
    // ═══════════════════════════════════════════════════════════

    /**
     * 對單站任務 payload 執行完整驗證。
     * @return null = 全部通過；String = 第一條失敗訊息
     */
    fun validateSingleMission(payload: MissionStartPayload, maxAlt: Double = 500.0): String? {
        validateMissionType(payload.mission)?.let { return it }
        validateTarget(payload.target, maxAlt)?.let { return it }
        validatePhotoCount(payload.photoCount)?.let { return it }
        return validateMissionSpecific(payload.mission, payload.orbit, maxAlt)
    }

    /**
     * 對多站任務的單一站點執行驗證。
     * @return null = 全部通過；String = 第一條失敗訊息
     */
    fun validateStop(stop: StopDefinition, maxAlt: Double = 500.0): String? {
        validateMissionType(stop.mission)?.let { return it }
        validateTarget(stop.target, maxAlt)?.let { return it }
        stop.orbit?.let { validateOrbitParams(it, maxAlt) }?.let { return it }
        return validateMissionSpecific(stop.mission, stop.orbit, maxAlt)
    }

    /** 根據 mission 類型執行對應的專屬驗證 + orbit 參數驗證 */
    private fun validateMissionSpecific(mission: String, orbit: MissionOrbit?, maxAlt: Double): String? {
        when (mission) {
            "B" -> validateMissionB(orbit)?.let { return it }
            "C" -> validateMissionC(orbit)?.let { return it }
            "D" -> validateMissionD(orbit)?.let { return it }
        }
        // 若有 orbit block，驗證通用參數
        orbit?.let { validateOrbitParams(it, maxAlt) }?.let { return it }
        return null
    }
}
