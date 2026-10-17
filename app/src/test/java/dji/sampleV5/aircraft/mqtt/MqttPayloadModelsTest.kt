package dji.sampleV5.aircraft.mqtt

import org.junit.Assert.*
import org.junit.Test

/** MqttPayloadModels 單元測試 — JSON 解析正確性與容錯 */
class MqttPayloadModelsTest {

    // ============================================================
    // CommandPayload.fromJson
    // ============================================================

    @Test
    fun `CommandPayload — 合法 JSON 正確解析`() {
        val json = """{"msg_id":"cmd_001","cmd":"takeoff","timestamp":1700000001000}"""
        val p = CommandPayload.fromJson(json)
        assertNotNull(p)
        assertEquals("cmd_001", p!!.msgId)
        assertEquals("takeoff", p.cmd)
        assertEquals(1700000001000L, p.timestamp)
    }

    @Test
    fun `CommandPayload — 缺少 msg_id 回傳 null`() {
        val json = """{"cmd":"takeoff","timestamp":1700000001000}"""
        val p = CommandPayload.fromJson(json)
        assertNull(p)
    }

    @Test
    fun `CommandPayload — 缺少 cmd 回傳 null`() {
        val json = """{"msg_id":"cmd_001","timestamp":1700000001000}"""
        val p = CommandPayload.fromJson(json)
        assertNull(p)
    }

    @Test
    fun `CommandPayload — 缺少 timestamp 使用預設值`() {
        val json = """{"msg_id":"cmd_001","cmd":"rtl"}"""
        val p = CommandPayload.fromJson(json)
        assertNotNull(p)
        assertEquals("rtl", p!!.cmd)
        assertTrue(p.timestamp > 0)  // 應為 System.currentTimeMillis()
    }

    @Test
    fun `CommandPayload — 無效 JSON 回傳 null`() {
        val p = CommandPayload.fromJson("not json at all")
        assertNull(p)
    }

    // ============================================================
    // MissionStartPayload.fromJson — Mission A (無 orbit)
    // ============================================================

    @Test
    fun `MissionStartPayload — Mission A 無 orbit 正確解析`() {
        val json = """
        {
            "msg_id": "msn_001",
            "mission": "A",
            "target": {"lat": 25.0, "lng": 121.5, "alt": 30.0},
            "photo_count": 7,
            "timestamp": 1700000002000
        }
        """.trimIndent()
        val p = MissionStartPayload.fromJson(json)
        assertNotNull(p)
        assertEquals("A", p!!.mission)
        assertEquals(25.0, p.target.lat, 0.0001)
        assertEquals(121.5, p.target.lng, 0.0001)
        assertEquals(30.0, p.target.alt, 0.0001)
        assertEquals(7, p.photoCount)
        assertNull(p.orbit)   // 無 orbit 區塊
    }

    @Test
    fun `MissionStartPayload — Mission B 含 orbit radius`() {
        val json = """
        {
            "msg_id": "msn_002",
            "mission": "B",
            "target": {"lat": 25.0, "lng": 121.5, "alt": 30.0},
            "orbit": {"radius": 15, "alt": 20, "gimbal_pitch": -45},
            "photo_count": 7,
            "timestamp": 1700000003000
        }
        """.trimIndent()
        val p = MissionStartPayload.fromJson(json)
        assertNotNull(p)
        assertEquals("B", p!!.mission)
        assertEquals(15.0, p.orbit!!.radius, 0.001)
        assertEquals(20.0, p.orbit!!.alt!!, 0.001)
        assertEquals(-45.0, p.orbit!!.gimbalPitch, 0.001)
    }

    @Test
    fun `MissionStartPayload — Mission C 含 maxAlt + turns`() {
        val json = """
        {
            "msg_id": "msn_003",
            "mission": "C",
            "target": {"lat": 25.0, "lng": 121.5, "alt": 20.0},
            "orbit": {"radius": 10, "alt": 15, "max_alt": 40, "turns": 3, "gimbal_pitch": -45},
            "photo_count": 7,
            "timestamp": 1700000004000
        }
        """.trimIndent()
        val p = MissionStartPayload.fromJson(json)
        assertNotNull(p)
        assertEquals(40.0, p!!.orbit!!.maxAlt!!, 0.001)
        assertEquals(3, p.orbit!!.turns)
    }

    @Test
    fun `MissionStartPayload — Mission D 含 radiusEnd + finalHeading`() {
        val json = """
        {
            "msg_id": "msn_004",
            "mission": "D",
            "target": {"lat": 25.0, "lng": 121.5, "alt": 20.0},
            "orbit": {"radius": 10, "alt": 20, "max_alt": 40, "turns": 3,
                      "radius_end": 25, "orbit_speed": 2.5, "final_heading": 180,
                      "gimbal_pitch": -45, "photo_gimbal_pitch": -60},
            "photo_count": 7,
            "timestamp": 1700000005000
        }
        """.trimIndent()
        val p = MissionStartPayload.fromJson(json)
        assertNotNull(p)
        assertEquals(25.0, p!!.orbit!!.radiusEnd!!, 0.001)
        assertEquals(2.5, p.orbit!!.orbitSpeed, 0.001)
        assertEquals(180.0, p.orbit!!.finalHeading!!, 0.001)
        assertEquals(-60.0, p.orbit!!.photoGimbalPitch, 0.001)
    }

    @Test
    fun `MissionStartPayload — 缺少 msg_id 回傳 null`() {
        val json = """{"mission":"A","target":{"lat":1,"lng":2,"alt":3},"photo_count":7}"""
        val p = MissionStartPayload.fromJson(json)
        assertNull(p)
    }

    @Test
    fun `MissionStartPayload — orbit 欄位使用預設值`() {
        val json = """
        {
            "msg_id": "msn_defaults",
            "mission": "A",
            "target": {"lat": 25.0, "lng": 121.5, "alt": 30.0},
            "orbit": {"radius": 0},
            "photo_count": 7,
            "timestamp": 1700000006000
        }
        """.trimIndent()
        val p = MissionStartPayload.fromJson(json)
        assertNotNull(p)
        // orbit 存在但只有 radius，其他應為預設值
        assertEquals(-45.0, p!!.orbit!!.gimbalPitch, 0.001)
        assertEquals(-45.0, p.orbit!!.photoGimbalPitch, 0.001)
        assertNull(p.orbit!!.alt)          // 未提供 → null
        assertNull(p.orbit!!.maxAlt)       // 未提供 → null
        assertNull(p.orbit!!.turns)        // 未提供 → null
        assertEquals(1.0, p.orbit!!.orbitSpeed, 0.001)  // 預設 1.0
    }

    @Test
    fun `MissionStartPayload — photo_count 預設 7`() {
        val json = """
        {
            "msg_id": "msn_nopc",
            "mission": "A",
            "target": {"lat": 25.0, "lng": 121.5, "alt": 30.0},
            "timestamp": 1700000007000
        }
        """.trimIndent()
        val p = MissionStartPayload.fromJson(json)
        assertNotNull(p)
        assertEquals(7, p!!.photoCount)  // 預設值
    }

    // ============================================================
    // AckPayload.toJson
    // ============================================================

    @Test
    fun `AckPayload — toJson 產出正確 JSON`() {
        val ack = AckPayload(
            msgId = "msn_001",
            action = "mission_start",
            status = "success",
            errorMsg = ""
        )
        val json = ack.toJson()
        assertTrue(json.contains(""""msg_id":"msn_001""""))
        assertTrue(json.contains(""""action":"mission_start""""))
        assertTrue(json.contains(""""status":"success""""))
        assertTrue(json.contains(""""timestamp":"""))
    }

    @Test
    fun `AckPayload — failed ack 包含 error_msg`() {
        val ack = AckPayload(
            msgId = "msn_001",
            action = "mission_start",
            status = "failed",
            errorMsg = "Unknown mission: X"
        )
        val json = ack.toJson()
        assertTrue(json.contains(""""status":"failed""""))
        assertTrue(json.contains(""""error_msg":"Unknown mission: X""""))
    }

    // ============================================================
    // MultiStopPayload.fromJson
    // ============================================================

    @Test
    fun `MultiStopPayload — 2 站正確解析`() {
        val json = """
        {
            "msg_id": "multi_001",
            "stops": [
                {
                    "mission": "A",
                    "target": {"lat": 25.0, "lng": 121.5, "alt": 30.0},
                    "orbit": {"gimbal_pitch": -45, "photo_gimbal_pitch": -30}
                },
                {
                    "mission": "B",
                    "target": {"lat": 25.1, "lng": 121.6, "alt": 35.0},
                    "orbit": {"radius": 15, "alt": 20, "gimbal_pitch": -45}
                }
            ],
            "timestamp": 1700000008000
        }
        """.trimIndent()
        val p = MultiStopPayload.fromJson(json)
        assertNotNull(p)
        assertEquals(2, p!!.stops.size)
        assertEquals("A", p.stops[0].mission)
        assertEquals("B", p.stops[1].mission)
        assertEquals(25.0, p.stops[0].target.lat, 0.0001)
        assertEquals(25.1, p.stops[1].target.lat, 0.0001)
    }

    @Test
    fun `MultiStopPayload — 只有 1 站回傳 null`() {
        val json = """
        {
            "msg_id": "multi_single",
            "stops": [
                {"mission": "A", "target": {"lat": 25.0, "lng": 121.5, "alt": 30.0}}
            ],
            "timestamp": 1700000009000
        }
        """.trimIndent()
        val p = MultiStopPayload.fromJson(json)
        assertNull(p)  // 需要至少 2 站
    }

    @Test
    fun `MultiStopPayload — 無效 stops JSON 回傳 null`() {
        val p = MultiStopPayload.fromJson("""{"msg_id":"m1","stops":"not_an_array"}""")
        assertNull(p)
    }
}
