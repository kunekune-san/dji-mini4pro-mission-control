package dji.sampleV5.aircraft.mqtt

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import dji.sampleV5.aircraft.config.AppConfig
import dji.sampleV5.aircraft.mission.MissionStateMachine
import dji.v5.utils.common.ContextUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.eclipse.paho.client.mqttv3.*
import java.util.concurrent.atomic.AtomicBoolean

object MqttClientManager {

    private const val TAG = "MqttManager"

    private var mqttClient: MqttClient? = null
    private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchdogJob: Job? = null
    private val isConnecting = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _commandFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val commandFlow: SharedFlow<String> = _commandFlow

    // 新架構 callbacks
    var onCommandReceived: ((CommandPayload) -> Unit)? = null
    var onMissionStartReceived: ((MissionStartPayload) -> Unit)? = null

    // 指令去重
    private val processedMsgIds = mutableSetOf<String>()

    /** 指令去重：檢查 msgId 是否已處理過 */
    private fun isDuplicate(msgId: String): Boolean {
        if (processedMsgIds.contains(msgId)) return true
        processedMsgIds.add(msgId)
        if (processedMsgIds.size > 200) processedMsgIds.clear()
        return false
    }

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    /** 連線到 MQTT broker，訂閱 command 和 mission/start topic */
    fun connect() {
        if (mqttClient != null && isConnected) {
            Log.d(TAG, "Already connected, skipping.")
            return
        }
        if (!isConnecting.compareAndSet(false, true)) {
            Log.d(TAG, "Connection already in progress, skipping.")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        val clientId = "DJI_App_${System.currentTimeMillis()}"
        Log.d(TAG, "Connecting to broker: ${AppConfig.getMqttBrokerUri()}, clientId=$clientId")

        scope.launch {
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
                }

                client.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        isConnected = true
                        _connectionState.value = ConnectionState.CONNECTED
                        Log.i(TAG, "MQTT connected (reconnect=$reconnect) to $serverURI")

                        try {
                            client.subscribe("${AppConfig.getTopicPrefix()}/command", 1)
                            client.subscribe("${AppConfig.getTopicPrefix()}/mission/start", 1)
                            Log.d(TAG, "Subscribed to ${AppConfig.getTopicPrefix()}/command and mission/start")
                        } catch (e: Exception) {
                            Log.e(TAG, "Subscribe ${AppConfig.getTopicPrefix()} topics failed: ${e.message}", e)
                        }

                        publishMessage("${AppConfig.getTopicPrefix()}/status", "Hello from DJI App! Time: ${System.currentTimeMillis()}")
                        startMqttWatchdog()
                    }

                    override fun connectionLost(cause: Throwable?) {
                        isConnected = false
                        _connectionState.value = ConnectionState.DISCONNECTED
                        Log.w(TAG, "MQTT connection lost: ${cause?.message}")
                        stopMqttWatchdog()
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val payload = message?.toString() ?: return
                        Log.d(TAG, "Raw command received: '$payload'")
                        Log.d(TAG, "Message received: topic=$topic, msg=$payload")

                        when (topic) {
                            "${AppConfig.getTopicPrefix()}/command" -> {
                                Log.d(TAG, "${AppConfig.getTopicPrefix()} command received: $payload")
                                val cmdPayload = CommandPayload.fromJson(payload)
                                if (cmdPayload != null && !isDuplicate(cmdPayload.msgId)) {
                                    onCommandReceived?.invoke(cmdPayload)
                                }
                            }
                            "${AppConfig.getTopicPrefix()}/mission/start" -> {
                                Log.d(TAG, "[MQTT] mission/start raw payload: $payload")
                                val missionPayload = MissionStartPayload.fromJson(payload)
                                if (missionPayload == null) {
                                    Log.e(TAG, "[MQTT] fromJson 解析失敗，payload 格式不符")
                                    return@messageArrived
                                }
                                Log.d(TAG, "[MQTT] 解析成功 msgId=${missionPayload.msgId} mission=${missionPayload.mission}")
                                if (isDuplicate(missionPayload.msgId)) {
                                    Log.w(TAG, "[MQTT] 重複指令，忽略 msgId=${missionPayload.msgId}")
                                    return@messageArrived
                                }
                                if (onMissionStartReceived == null) {
                                    Log.e(TAG, "[MQTT] onMissionStartReceived callback 為 null，MqttMissionBridge 未初始化")
                                    return@messageArrived
                                }
                                Log.d(TAG, "[MQTT] 呼叫 onMissionStartReceived")
                                onMissionStartReceived?.invoke(missionPayload)
                            }
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        Log.d(TAG, "Delivery complete: token=${token?.messageId}")
                    }
                })

                client.connect(options)
                mqttClient = client
            } catch (e: Exception) {
                Log.e(TAG, "MQTT connect failed: ${e.message}", e)
                _connectionState.value = ConnectionState.ERROR
                showToast("MQTT 連線失敗：${e.message}")
            } finally {
                isConnecting.set(false)
            }
        }
    }

    /** 中斷 MQTT 連線並釋放資源 */
    fun disconnect() {
        stopMqttWatchdog()
        scope.launch {
            try {
                mqttClient?.disconnect()
                mqttClient?.close()
                mqttClient = null
                isConnected = false
                _connectionState.value = ConnectionState.DISCONNECTED
                Log.i(TAG, "MQTT disconnected.")
            } catch (e: Exception) {
                Log.e(TAG, "MQTT disconnect failed: ${e.message}", e)
            }
        }
    }

    // ========== Publish ==========

    /** 底層發布方法：發送 MQTT 訊息到指定 topic */
    private fun publishMessage(topic: String, message: String, qos: Int = AppConfig.MQTT_QOS) {
        try {
            mqttClient?.publish(topic, message.toByteArray(), qos, false)
            Log.d(TAG, "Publish success: topic=$topic, message=$message")
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
        publish("${AppConfig.getTopicPrefix()}/ack", payload.toJson())
    }

    /** 發布無人機狀態（座標、電池、GPS 等） */
    fun publishStatus(payload: DroneStatusPayload) {
        publish("${AppConfig.getTopicPrefix()}/status", payload.toJson())
    }

    /** 發布心跳訊息（每 5 秒） */
    fun publishHeartbeat() {
        publish("${AppConfig.getTopicPrefix()}/heartbeat", HeartbeatPayload().toJson())
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
                    Log.e(TAG, "MQTT watchdog: connection lost, attempting reconnect...")
                } else {
                    Log.d(TAG, "MQTT watchdog: connection OK, mission state=${MissionStateMachine.state.value}")
                }
            }
        }
        Log.d(TAG, "MQTT watchdog started")
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
