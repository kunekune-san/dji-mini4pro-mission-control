package dji.sampleV5.aircraft.mqtt

import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sampleV5.aircraft.util.AppLog
import android.widget.Toast
import dji.sampleV5.aircraft.config.AppConfig
import dji.v5.utils.common.ContextUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** MQTT 連線管理單例，負責 broker 連線、指令訂閱與訊息發布 */
object MqttClientManager {

    private const val TAG = "MqttManager"

    @Volatile private var mqttClient: MqttClient? = null
    @Volatile private var isConnected = false
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchdogJob: Job? = null
    private val isConnecting = AtomicBoolean(false)

    /** MQTT 連線狀態 */
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    /** 指令訊息 SharedFlow（供訂閱者接收原始 JSON） */
    private val _commandFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val commandFlow: SharedFlow<String> = _commandFlow

    /** 飛行指令回調（takeoff/rtl/cancel/reset） */
    @Volatile var onCommandReceived: ((CommandPayload) -> Unit)? = null
    /** 單站任務指令回調 */
    @Volatile var onMissionStartReceived: ((MissionStartPayload) -> Unit)? = null
    /** 多站任務指令回調 */
    @Volatile var onMultiStartReceived: ((MultiStopPayload) -> Unit)? = null

    // 指令去重（LRU 策略：超過 500 時移除最舊 200 筆）
    private val processedMsgIds = object : LinkedHashSet<String>(512) {
        override fun add(element: String): Boolean {
            val added = super.add(element)
            if (size > 500) {
                val iterator = iterator()
                repeat(200) { iterator.next(); iterator.remove() }
            }
            return added
        }
    }

    /** 指令去重：檢查 msgId 是否已處理過（synchronized 防止 check-then-act 競態） */
    private fun isDuplicate(msgId: String): Boolean {
        synchronized(processedMsgIds) {
            if (processedMsgIds.contains(msgId)) return true
            processedMsgIds.add(msgId)
            return false
        }
    }

    /** MQTT 連線狀態列舉 */
    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    /** 連線到 MQTT broker（公開入口，非同步啟動） */
    fun connect() {
        scope.launch { connectSuspend() }
    }

    /** 連線到 MQTT broker（suspend 版本，由 connect() 和 watchdog 共用） */
    private suspend fun connectSuspend() {
        if (!isConnecting.compareAndSet(false, true)) {
            AppLog.d(TAG, "Connection already in progress, skipping.")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        val clientId = "DJI_App_${System.currentTimeMillis()}"
        AppLog.d(TAG, "Connecting to broker: ${AppConfig.getMqttBrokerUri()}, clientId=$clientId")

        try {
            val client = MqttClient(AppConfig.getMqttBrokerUri(), clientId, null)
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = false
                connectionTimeout = 10
                keepAliveInterval = 20
                val username = AppConfig.getMqttUsername()
                val password = AppConfig.getMqttPassword()
                if (username.isNotEmpty()) {
                    userName = username
                    this.password = password.toCharArray()
                }
                // LWT：App 異常斷線時由 broker 自動發佈 OFFLINE 訊息
                val lwtPayload = JSONObject().apply {
                    put("state", "OFFLINE")
                    put("drone_id", AppConfig.getTopicPrefix())
                    put("timestamp", System.currentTimeMillis())
                }.toString()
                setWill(MqttTopics.STATUS, lwtPayload.toByteArray(), 1, true)
            }

            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    isConnected = true
                    _connectionState.value = ConnectionState.CONNECTED
                    MqttConnectionEventBus.onConnectionChanged(true)
                    AppLog.i(TAG, "MQTT connected (reconnect=$reconnect) to $serverURI")

                    try {
                        client.subscribe(MqttTopics.COMMAND, 1)
                        client.subscribe(MqttTopics.MISSION_START, 1)
                        client.subscribe(MqttTopics.MISSION_MULTI, 1)
                        AppLog.d(TAG, "Subscribed to MqttTopics.COMMAND, mission/start, mission/multi")
                    } catch (e: Exception) {
                        Log.e(TAG, "Subscribe ${AppConfig.getTopicPrefix()} topics failed: ${e.message}", e)
                    }

                    // 發佈 ONLINE retained 訊息（取代舊的 hello）
                    val onlinePayload = JSONObject().apply {
                        put("state", "ONLINE")
                        put("drone_id", AppConfig.getTopicPrefix())
                        put("timestamp", System.currentTimeMillis())
                    }.toString()
                    publishMessage(MqttTopics.STATUS, onlinePayload, 1, true)

                    // 重連時補發完整狀態快照
                    if (reconnect) {
                        scope.launch {
                            delay(500) // 等訂閱生效
                            MqttStatusReporter.publishCurrentStatus()
                            AppLog.d(TAG, "Reconnect snapshot published")
                        }
                    }

                    startMqttWatchdog()
                }

                override fun connectionLost(cause: Throwable?) {
                    isConnected = false
                    _connectionState.value = ConnectionState.DISCONNECTED
                    MqttConnectionEventBus.onConnectionChanged(false)
                    Log.w(TAG, "MQTT connection lost: ${cause?.message}")
                    // watchdog 持續運行，作為 Paho auto-reconnect 的安全網
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.toString() ?: return
                    AppLog.d(TAG, "Raw command received: '$payload'")
                    AppLog.d(TAG, "Message received: topic=$topic, msg=$payload")

                    when (topic) {
                        MqttTopics.COMMAND -> {
                            AppLog.d(TAG, "${AppConfig.getTopicPrefix()} command received: $payload")
                            val cmdPayload = CommandPayload.fromJson(payload)
                            if (cmdPayload != null && !isDuplicate(cmdPayload.msgId)) {
                                scope.launch { onCommandReceived?.invoke(cmdPayload) }
                            }
                        }
                        MqttTopics.MISSION_START -> {
                            AppLog.d(TAG, "[MQTT] mission/start raw payload: $payload")
                            val missionPayload = MissionStartPayload.fromJson(payload)
                            if (missionPayload == null) {
                                Log.e(TAG, "[MQTT] fromJson failed, payload format invalid")
                                publishAck(AckPayload(
                                    msgId = "", action = "mission_start",
                                    status = "failed", errorMsg = "Invalid payload format"
                                ))
                                return@messageArrived
                            }
                            AppLog.d(TAG, "[MQTT] parsed msgId=${missionPayload.msgId} mission=${missionPayload.mission}")
                            if (isDuplicate(missionPayload.msgId)) {
                                Log.w(TAG, "[MQTT] duplicate command, ignoring msgId=${missionPayload.msgId}")
                                return@messageArrived
                            }
                            if (onMissionStartReceived == null) {
                                Log.e(TAG, "[MQTT] onMissionStartReceived callback is null, MqttMissionBridge not initialized")
                                return@messageArrived
                            }
                            AppLog.d(TAG, "[MQTT] invoking onMissionStartReceived")
                            scope.launch { onMissionStartReceived?.invoke(missionPayload) }
                        }
                        MqttTopics.MISSION_MULTI -> {
                            AppLog.d(TAG, "[MQTT] mission/multi raw payload: $payload")
                            val multiPayload = MultiStopPayload.fromJson(payload)
                            if (multiPayload == null) {
                                Log.e(TAG, "[MQTT] multi-stop fromJson failed")
                                publishAck(AckPayload(
                                    msgId = "", action = "multi_start",
                                    status = "failed", errorMsg = "Invalid payload format"
                                ))
                                return@messageArrived
                            }
                            AppLog.d(TAG, "[MQTT] multi-stop parsed msgId=${multiPayload.msgId} stops=${multiPayload.stops.size}")
                            if (isDuplicate(multiPayload.msgId)) {
                                Log.w(TAG, "[MQTT] duplicate command, ignoring msgId=${multiPayload.msgId}")
                                return@messageArrived
                            }
                            if (onMultiStartReceived == null) {
                                Log.e(TAG, "[MQTT] onMultiStartReceived callback is null")
                                return@messageArrived
                            }
                            scope.launch { onMultiStartReceived?.invoke(multiPayload) }
                        }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    AppLog.d(TAG, "Delivery complete: token=${token?.messageId}")
                }
            })

            mqttClient = client  // 先賦值，確保 connectComplete 回調可見
            client.connect(options)
        } catch (e: Exception) {
            mqttClient = null  // connect 失敗時重置
            Log.e(TAG, "MQTT connect failed: ${e.message}", e)
            _connectionState.value = ConnectionState.ERROR
            showToast("MQTT connect failed: ${e.message}")
        } finally {
            isConnecting.set(false)
        }
    }

    /** 中斷 MQTT 連線並釋放資源 */
    fun disconnect() {
        stopMqttWatchdog()
        scope.launch {
            try {
                // 發佈 OFFLINE 訊息後再斷線
                if (isConnected) {
                    val offlinePayload = JSONObject().apply {
                        put("state", "OFFLINE")
                        put("timestamp", System.currentTimeMillis())
                    }.toString()
                    publishMessage(MqttTopics.STATUS, offlinePayload, 1, true)
                }
                mqttClient?.disconnect()
                mqttClient?.close()
                mqttClient = null
                isConnected = false
                _connectionState.value = ConnectionState.DISCONNECTED
                AppLog.i(TAG, "MQTT disconnected.")
            } catch (e: Exception) {
                Log.e(TAG, "MQTT disconnect failed: ${e.message}", e)
            }
        }
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        AppLog.d(TAG, "MQTT scope cancelled and recreated")
    }

    // ========== Publish ==========

    /** 底層發布方法：發送 MQTT 訊息到指定 topic */
    private fun publishMessage(topic: String, message: String, qos: Int = AppConfig.MQTT_QOS, retain: Boolean = false) {
        try {
            mqttClient?.publish(topic, message.toByteArray(), qos, retain)
            AppLog.d(TAG, "Publish success: topic=$topic, msgLen=${message.length}")
        } catch (e: Exception) {
            Log.e(TAG, "Publish failed: topic=$topic, error=${e.message}", e)
        }
    }

    /** 非同步發布 MQTT 訊息 */
    fun publish(topic: String, message: String) {
        scope.launch {
            publishMessage(topic, message)
        }
    }

    /** 發布指令執行結果 ACK */
    fun publishAck(payload: AckPayload) {
        publish(MqttTopics.ACK, payload.toJson())
    }

    /** 發布無人機狀態（座標、電池、GPS 等） */
    fun publishStatus(payload: DroneStatusPayload) {
        publish(MqttTopics.STATUS, payload.toJson())
    }

    /** 發布心跳訊息 */
    fun publishHeartbeat() {
        publish(MqttTopics.HEARTBEAT, HeartbeatPayload().toJson())
    }

    // ========== Watchdog ==========

    /** 啟動 MQTT 連線看門狗（每 10 秒檢查連線狀態） */
    private fun startMqttWatchdog() {
        stopMqttWatchdog()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(10000L)  // 每 10 秒檢查一次
                val alive = try {
                    mqttClient?.isConnected ?: false
                } catch (e: Exception) {
                    false
                }
                if (!alive) {
                    Log.w(TAG, "MQTT watchdog: connection lost, attempting reconnect...")
                    connectSuspend()
                } else {
                    AppLog.d(TAG, "MQTT watchdog: connection OK")
                }
            }
        }
        AppLog.d(TAG, "MQTT watchdog started")
    }

    /** 停止 MQTT 看門狗 */
    private fun stopMqttWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    // ========== Helpers ==========

    /** 在主線程顯示 Toast 提示 */
    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(ContextUtil.getContext(), message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "showToast failed: ${e.message}")
            }
        }
    }
}
