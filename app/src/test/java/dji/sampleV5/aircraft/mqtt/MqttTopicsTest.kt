package dji.sampleV5.aircraft.mqtt

import org.junit.Assert.*
import org.junit.Test

/** MqttTopics 單元測試 — topic 字串格式正確性（純字串計算，零依賴） */
class MqttTopicsTest {

    private val testPrefix = "test/app-001"

    // ============================================================
    // 函式版本（可傳入自訂 prefix，測試時不需 Android）
    // ============================================================

    @Test
    fun `command — 正確格式`() {
        assertEquals("test/app-001/command", MqttTopics.command(testPrefix))
    }

    @Test
    fun `missionStart — 正確格式`() {
        assertEquals("test/app-001/mission/start", MqttTopics.missionStart(testPrefix))
    }

    @Test
    fun `missionMulti — 正確格式`() {
        assertEquals("test/app-001/mission/multi", MqttTopics.missionMulti(testPrefix))
    }

    @Test
    fun `status — 正確格式`() {
        assertEquals("test/app-001/status", MqttTopics.status(testPrefix))
    }

    @Test
    fun `ack — 正確格式`() {
        assertEquals("test/app-001/ack", MqttTopics.ack(testPrefix))
    }

    @Test
    fun `heartbeat — 正確格式`() {
        assertEquals("test/app-001/heartbeat", MqttTopics.heartbeat(testPrefix))
    }

    // ============================================================
    // 邊界條件
    // ============================================================

    @Test
    fun `空 prefix 仍產生合法 topic`() {
        assertEquals("/command", MqttTopics.command(""))
    }

    @Test
    fun `含特殊字元的 prefix 正確串接`() {
        assertEquals("a/b-c.d_123/status", MqttTopics.status("a/b-c.d_123"))
    }

    @Test
    fun `所有 topic 結尾不重複斜線`() {
        // prefix 不含末尾斜線，topic 不含開頭斜線，格式一致
        val topics = listOf(
            MqttTopics.command(testPrefix),
            MqttTopics.missionStart(testPrefix),
            MqttTopics.missionMulti(testPrefix),
            MqttTopics.status(testPrefix),
            MqttTopics.ack(testPrefix),
            MqttTopics.heartbeat(testPrefix)
        )
        topics.forEach {
            assertFalse("topic should not contain double slash: $it", it.contains("//"))
        }
    }
}
