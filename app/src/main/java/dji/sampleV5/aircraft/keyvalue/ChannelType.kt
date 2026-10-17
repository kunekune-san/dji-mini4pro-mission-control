package dji.sampleV5.aircraft.keyvalue

/**
 * 通道類型列舉，對應 SDK 中各個元件（電池、雲台、相機等）的通道名稱。
 *
 * @author feel.feng
 * @time 2022/03/11 9:42 上午
 * @description: 通道類型列舉
 */
enum class ChannelType(name: String) {
    /**
     * 電池
     */
    CHANNEL_TYPE_BATTERY("BATTERY"),

    /**
     * 雲台
     */
    CHANNEL_TYPE_GIMBAL("GIMBAL"),

    /**
     * 相機
     */
    CHANNEL_TYPE_CAMERA("CAMERA"),


    /**
     * Airlink
     */
    CHANNEL_TYPE_AIRLINK("AIRLINK"),

    /**
     * Flight Assistant
     */
    CHANNEL_TYPE_FLIGHT_ASSISTANT("ASSISTANT"),

    /**
     * Flight Control
     */
    CHANNEL_TYPE_FLIGHT_CONTROL("FLIGHT CONTROL"),

    /**
     * Remote Controller
     */
    CHANNEL_TYPE_REMOTE_CONTROLLER("REMOTE CONTROLLER"),

    /**
     * BLE
     */
    CHANNEL_TYPE_BLE("BLE"),

    /**
     * RTK
     */
    CHANNEL_TYPE_RTK_BASE_STATION("RTK BASE STATION"),

    /**
     * RTK
     */
    CHANNEL_TYPE_RTK_MOBILE_STATION("RTK MOBILE STATION"),

    /**
     * Product
     */
    CHANNEL_TYPE_PRODUCT("PRODUCT"),

    /**
     * OcuSync
     */
    CHANNEL_TYPE_OCU_SYNC("OCU SYNC"),

    /**
     * Radar
     */
    CHANNEL_TYPE_RADAR("RADAR"),



    /**
     * Mobile Network
     */
    CHANNEL_TYPE_MOBILE_NETWORK("MOBILE NETWORK"),


    /**
     * on board
     */
    CHANNEL_TYPE_ON_BOARD("BOARD"),

    /**
     * Payload
     */
    CHANNEL_TYPE_ON_PAYLOAD("PAYLOAD"),

    /**
     * lidar
     */
    CHANNEL_TYPE_LIDAR("LIDAR"),

    /**
     * IntelligentBox
     */
    INTELLIGENT_BOX("INTELLIGENT BOX");

    private val value: String
    override fun toString(): String {
        return value
    }

    init {
        value = name
    }
}