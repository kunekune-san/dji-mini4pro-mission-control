package dji.sampleV5.aircraft.mqtt

import dji.sampleV5.aircraft.config.AppConfig

/**
 * MQTT Topic 常數集中管理。
 *
 * 所有 topic 字串由此物件統一生成，避免 suffix 拼錯或重複。
 * 若 topic 結構未來變更，只需修改此處。
 */
object MqttTopics {

    /** 預設前綴（生產環境），測試時可覆蓋。 */
    @JvmStatic
    fun command(prefix: String = AppConfig.getTopicPrefix())      = "$prefix/command"
    @JvmStatic
    fun missionStart(prefix: String = AppConfig.getTopicPrefix()) = "$prefix/mission/start"
    @JvmStatic
    fun missionMulti(prefix: String = AppConfig.getTopicPrefix()) = "$prefix/mission/multi"
    @JvmStatic
    fun status(prefix: String = AppConfig.getTopicPrefix())       = "$prefix/status"
    @JvmStatic
    fun ack(prefix: String = AppConfig.getTopicPrefix())          = "$prefix/ack"
    @JvmStatic
    fun heartbeat(prefix: String = AppConfig.getTopicPrefix())    = "$prefix/heartbeat"

    // 向後相容：保留屬性式存取（既有 call site 不需修改）
    val COMMAND       get() = command()
    val MISSION_START get() = missionStart()
    val MISSION_MULTI get() = missionMulti()
    val STATUS        get() = status()
    val ACK           get() = ack()
    val HEARTBEAT     get() = heartbeat()
}
