package dji.sampleV5.aircraft.mqtt

import org.junit.Assert.*
import org.junit.Test

/** MissionValidator 單元測試 — 驗證規則正確性（純邏輯，零 Android/DJI 依賴） */
class MissionValidatorTest {

    private val validTarget = MissionTarget(lat = 25.0, lng = 121.5, alt = 30.0)
    private val validOrbit = MissionOrbit(radius = 15.0, alt = 20.0, gimbalPitch = -45.0, maxAlt = 80.0, turns = 3)

    // ============================================================
    // validateMissionType
    // ============================================================

    @Test
    fun `validateMissionType — A B C D 全部通過`() {
        assertNull(MissionValidator.validateMissionType("A"))
        assertNull(MissionValidator.validateMissionType("B"))
        assertNull(MissionValidator.validateMissionType("C"))
        assertNull(MissionValidator.validateMissionType("D"))
    }

    @Test
    fun `validateMissionType — 未知類型回傳錯誤`() {
        assertEquals("Unknown mission: E", MissionValidator.validateMissionType("E"))
    }

    @Test
    fun `validateMissionType — 空字串回傳錯誤`() {
        assertEquals("Unknown mission: ", MissionValidator.validateMissionType(""))
    }

    @Test
    fun `validateMissionType — 小寫字母回傳錯誤`() {
        assertEquals("Unknown mission: a", MissionValidator.validateMissionType("a"))
    }

    // ============================================================
    // validateTarget
    // ============================================================

    @Test
    fun `validateTarget — 正常座標通過`() {
        assertNull(MissionValidator.validateTarget(validTarget, maxAlt = 500.0))
    }

    @Test
    fun `validateTarget — lat 超過 90 回傳錯誤`() {
        val t = MissionTarget(lat = 91.0, lng = 121.5, alt = 30.0)
        assertTrue(MissionValidator.validateTarget(t, maxAlt = 500.0)!!.contains("latitude"))
    }

    @Test
    fun `validateTarget — lat 低於 -90 回傳錯誤`() {
        val t = MissionTarget(lat = -91.0, lng = 121.5, alt = 30.0)
        assertTrue(MissionValidator.validateTarget(t, maxAlt = 500.0)!!.contains("latitude"))
    }

    @Test
    fun `validateTarget — lng 超過 180 回傳錯誤`() {
        val t = MissionTarget(lat = 25.0, lng = 181.0, alt = 30.0)
        assertTrue(MissionValidator.validateTarget(t, maxAlt = 500.0)!!.contains("longitude"))
    }

    @Test
    fun `validateTarget — lng 低於 -180 回傳錯誤`() {
        val t = MissionTarget(lat = 25.0, lng = -181.0, alt = 30.0)
        assertTrue(MissionValidator.validateTarget(t, maxAlt = 500.0)!!.contains("longitude"))
    }

    @Test
    fun `validateTarget — alt 負數回傳錯誤`() {
        val t = MissionTarget(lat = 25.0, lng = 121.5, alt = -1.0)
        assertTrue(MissionValidator.validateTarget(t, maxAlt = 500.0)!!.contains("altitude"))
    }

    @Test
    fun `validateTarget — alt 超過 maxAlt 回傳錯誤`() {
        val t = MissionTarget(lat = 25.0, lng = 121.5, alt = 150.0)
        assertTrue(MissionValidator.validateTarget(t, maxAlt = 120.0)!!.contains("120"))
    }

    @Test
    fun `validateTarget — alt 等於 maxAlt 邊界通過`() {
        val t = MissionTarget(lat = 25.0, lng = 121.5, alt = 120.0)
        assertNull(MissionValidator.validateTarget(t, maxAlt = 120.0))
    }

    @Test
    fun `validateTarget — lat=lng=0 通過`() {
        val t = MissionTarget(lat = 0.0, lng = 0.0, alt = 30.0)
        assertNull(MissionValidator.validateTarget(t, maxAlt = 500.0))
    }

    // ============================================================
    // validatePhotoCount
    // ============================================================

    @Test
    fun `validatePhotoCount — 正常範圍通過`() {
        assertNull(MissionValidator.validatePhotoCount(2))
        assertNull(MissionValidator.validatePhotoCount(7))
        assertNull(MissionValidator.validatePhotoCount(20))
    }

    @Test
    fun `validatePhotoCount — 小於 2 回傳錯誤`() {
        assertTrue(MissionValidator.validatePhotoCount(1)!!.contains("2~20"))
    }

    @Test
    fun `validatePhotoCount — 大於 20 回傳錯誤`() {
        assertTrue(MissionValidator.validatePhotoCount(21)!!.contains("2~20"))
    }

    @Test
    fun `validatePhotoCount — 0 回傳錯誤`() {
        assertTrue(MissionValidator.validatePhotoCount(0)!!.contains("2~20"))
    }

    @Test
    fun `validatePhotoCount — 負數回傳錯誤`() {
        assertTrue(MissionValidator.validatePhotoCount(-1)!!.contains("2~20"))
    }

    // ============================================================
    // validateOrbitParams (maxAlt explicitly passed for testability)
    // ============================================================

    @Test
    fun `validateOrbitParams — 合法 orbit 通過`() {
        assertNull(MissionValidator.validateOrbitParams(validOrbit, maxAlt = 500.0))
    }

    @Test
    fun `validateOrbitParams — alt 超過 maxAlt 回傳錯誤`() {
        val o = MissionOrbit(alt = 150.0)
        assertTrue(MissionValidator.validateOrbitParams(o, maxAlt = 120.0)!!.contains("Invalid orbit.alt"))
    }

    @Test
    fun `validateOrbitParams — radius 負數回傳錯誤`() {
        val o = MissionOrbit(radius = -1.0)
        assertTrue(MissionValidator.validateOrbitParams(o, maxAlt = 500.0)!!.contains("radius"))
    }

    @Test
    fun `validateOrbitParams — gimbalPitch 超出範圍回傳錯誤`() {
        val o = MissionOrbit(gimbalPitch = 50.0)
        assertTrue(MissionValidator.validateOrbitParams(o, maxAlt = 500.0)!!.contains("gimbal_pitch"))
    }

    @Test
    fun `validateOrbitParams — gimbalPitch 邊界 -130 通過`() {
        val o = MissionOrbit(gimbalPitch = -130.0)
        assertNull(MissionValidator.validateOrbitParams(o, maxAlt = 500.0))
    }

    @Test
    fun `validateOrbitParams — photoGimbalPitch 超出範圍回傳錯誤`() {
        val o = MissionOrbit(photoGimbalPitch = -131.0)
        assertTrue(MissionValidator.validateOrbitParams(o, maxAlt = 500.0)!!.contains("photo_gimbal_pitch"))
    }

    // ============================================================
    // validateMissionB
    // ============================================================

    @Test
    fun `validateMissionB — 有 orbit 且 radius 大於 0 通過`() {
        assertNull(MissionValidator.validateMissionB(MissionOrbit(radius = 10.0)))
    }

    @Test
    fun `validateMissionB — 無 orbit 回傳錯誤`() {
        assertTrue(MissionValidator.validateMissionB(null)!!.contains("requires orbit"))
    }

    @Test
    fun `validateMissionB — radius = 0 回傳錯誤`() {
        assertTrue(MissionValidator.validateMissionB(MissionOrbit(radius = 0.0))!!.contains("radius > 0"))
    }

    // ============================================================
    // validateMissionC
    // ============================================================

    @Test
    fun `validateMissionC — 完整合法參數通過`() {
        val o = MissionOrbit(radius = 10.0, alt = 15.0, maxAlt = 80.0, turns = 4)
        assertNull(MissionValidator.validateMissionC(o))
    }

    @Test
    fun `validateMissionC — 缺 maxAlt 回傳錯誤`() {
        val o = MissionOrbit(radius = 10.0, turns = 3)
        assertTrue(MissionValidator.validateMissionC(o)!!.contains("max_alt"))
    }

    @Test
    fun `validateMissionC — maxAlt 小於等於 alt 回傳錯誤`() {
        val o = MissionOrbit(radius = 10.0, alt = 50.0, maxAlt = 50.0, turns = 3)
        assertTrue(MissionValidator.validateMissionC(o)!!.contains("must be > alt"))
    }

    @Test
    fun `validateMissionC — turns 小於最小值回傳錯誤`() {
        val o = MissionOrbit(radius = 10.0, maxAlt = 80.0, turns = 1)
        assertTrue(MissionValidator.validateMissionC(o)!!.contains("turns"))
    }

    @Test
    fun `validateMissionC — radius 小於最小螺旋半徑回傳錯誤`() {
        val o = MissionOrbit(radius = 2.0, maxAlt = 80.0, turns = 3)
        assertTrue(MissionValidator.validateMissionC(o)!!.contains("radius must be >="))
    }

    @Test
    fun `validateMissionC — 無 orbit 回傳錯誤`() {
        assertTrue(MissionValidator.validateMissionC(null)!!.contains("requires orbit"))
    }

    // ============================================================
    // validateMissionD
    // ============================================================

    @Test
    fun `validateMissionD — 完整合法參數通過`() {
        val o = MissionOrbit(radius = 10.0, alt = 15.0, maxAlt = 80.0, turns = 4, radiusEnd = 15.0)
        assertNull(MissionValidator.validateMissionD(o))
    }

    @Test
    fun `validateMissionD — 缺 radiusEnd 回傳錯誤`() {
        val o = MissionOrbit(radius = 10.0, alt = 15.0, maxAlt = 80.0, turns = 4, radiusEnd = null)
        assertTrue(MissionValidator.validateMissionD(o)!!.contains("radius_end"))
    }

    @Test
    fun `validateMissionD — radiusEnd 小於等於 radius 回傳錯誤`() {
        val o = MissionOrbit(radius = 10.0, alt = 15.0, maxAlt = 80.0, turns = 4, radiusEnd = 10.0)
        assertTrue(MissionValidator.validateMissionD(o)!!.contains("must be > radius"))
    }

    @Test
    fun `validateMissionD — finalHeading 超出範圍回傳錯誤`() {
        val o = MissionOrbit(radius = 10.0, alt = 15.0, maxAlt = 80.0, turns = 4, radiusEnd = 15.0, finalHeading = 361.0)
        assertTrue(MissionValidator.validateMissionD(o)!!.contains("final_heading"))
    }

    @Test
    fun `validateMissionD — finalHeading = 0 通過`() {
        val o = MissionOrbit(radius = 10.0, alt = 15.0, maxAlt = 80.0, turns = 4, radiusEnd = 15.0, finalHeading = 0.0)
        assertNull(MissionValidator.validateMissionD(o))
    }

    @Test
    fun `validateMissionD — finalHeading = 360 邊界通過`() {
        val o = MissionOrbit(radius = 10.0, alt = 15.0, maxAlt = 80.0, turns = 4, radiusEnd = 15.0, finalHeading = 360.0)
        assertNull(MissionValidator.validateMissionD(o))
    }

    @Test
    fun `validateMissionD — finalHeading = null 通過（optional 欄位）`() {
        val o = MissionOrbit(radius = 10.0, alt = 15.0, maxAlt = 80.0, turns = 4, radiusEnd = 15.0, finalHeading = null)
        assertNull(MissionValidator.validateMissionD(o))
    }

    @Test
    fun `validateMissionD — 繼承 C 的所有規則：缺 maxAlt 回傳錯誤`() {
        val o = MissionOrbit(radius = 10.0, turns = 4, radiusEnd = 15.0)
        assertTrue(MissionValidator.validateMissionD(o)!!.contains("max_alt"))
    }

    // ============================================================
    // validateSingleMission（整合路徑）
    // ============================================================

    @Test
    fun `validateSingleMission — 合法 Mission A 通過`() {
        val p = MissionStartPayload(
            msgId = "msn_001", mission = "A",
            target = validTarget, photoCount = 7, orbit = null,
            timestamp = 1700000000000, rawJson = "{}"
        )
        assertNull(MissionValidator.validateSingleMission(p, maxAlt = 500.0))
    }

    @Test
    fun `validateSingleMission — 合法 Mission B 通過`() {
        val p = MissionStartPayload(
            msgId = "msn_001", mission = "B",
            target = validTarget, photoCount = 7,
            orbit = MissionOrbit(radius = 15.0, alt = 20.0, gimbalPitch = -45.0),
            timestamp = 1700000000000, rawJson = "{}"
        )
        assertNull(MissionValidator.validateSingleMission(p, maxAlt = 500.0))
    }

    @Test
    fun `validateSingleMission — 未知 mission type 在第一關就攔截`() {
        val p = MissionStartPayload(
            msgId = "msn_001", mission = "X",
            target = validTarget, photoCount = 7, orbit = null,
            timestamp = 1700000000000, rawJson = "{}"
        )
        assertEquals("Unknown mission: X", MissionValidator.validateSingleMission(p, maxAlt = 500.0))
    }

    @Test
    fun `validateSingleMission — 非法座標被攔截`() {
        val p = MissionStartPayload(
            msgId = "msn_001", mission = "A",
            target = MissionTarget(lat = 200.0, lng = 121.5, alt = 30.0),
            photoCount = 7, orbit = null,
            timestamp = 1700000000000, rawJson = "{}"
        )
        assertTrue(MissionValidator.validateSingleMission(p, maxAlt = 500.0)!!.contains("latitude"))
    }

    // ============================================================
    // validateStop（多站站點驗證）
    // ============================================================

    @Test
    fun `validateStop — 合法站點通過`() {
        val s = StopDefinition(
            mission = "A", target = validTarget, orbit = null
        )
        assertNull(MissionValidator.validateStop(s, maxAlt = 500.0))
    }

    @Test
    fun `validateStop — 有 orbit 參數的站點通過`() {
        val s = StopDefinition(
            mission = "B", target = validTarget,
            orbit = MissionOrbit(radius = 15.0, gimbalPitch = -30.0)
        )
        assertNull(MissionValidator.validateStop(s, maxAlt = 500.0))
    }
}
