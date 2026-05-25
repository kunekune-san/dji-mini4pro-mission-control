package dji.sampleV5.aircraft.settings

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dji.sampleV5.aircraft.config.AppConfig
import dji.sampleV5.aircraft.databinding.ActivitySettingsBinding
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.remotecontroller.ControlMode
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.et.set
import dji.v5.manager.KeyManager

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
    }

    private lateinit var binding: ActivitySettingsBinding
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadCurrentSettings()
        loadFlightLimits()
        setupMqttSaveButton()
        setupRcModeRadio()
        setupFlightLimitsSaveButton()
    }

    override fun onResume() {
        super.onResume()
        // Listen for aircraft connection state
        FlightControllerKey.KeyConnection.create().listen(this) { connected ->
            val wasConnected = isConnected
            isConnected = connected == true
            Log.d(TAG, "Connection state changed: $isConnected")
            runOnUiThread {
                binding.tvFlightLimitsStatus.visibility = if (isConnected) View.GONE else View.VISIBLE
                // Re-apply flight limits when just connected
                if (isConnected && !wasConnected) {
                    applyStoredFlightLimits()
                }
            }
        }
    }

    override fun onPause() {
        KeyManager.getInstance().cancelListen(this)
        super.onPause()
    }

    /** 載入目前的 MQTT 與遙控器設定到 UI */
    private fun loadCurrentSettings() {
        binding.etMqttHost.setText(AppConfig.getMqttHost())
        binding.etMqttPort.setText(AppConfig.getMqttPort())
        binding.etTopicPrefix.setText(AppConfig.getTopicPrefix())
        binding.etMqttUsername.setText(AppConfig.getMqttUsername())
        binding.etMqttPassword.setText(AppConfig.getMqttPassword())

        when (AppConfig.getRcMode()) {
            "JP" -> binding.rbModeJp.isChecked = true
            "CH" -> binding.rbModeCh.isChecked = true
            else -> binding.rbModeUsa.isChecked = true
        }
    }

    /** 設定 MQTT 儲存按鈕點擊事件 */
    private fun setupMqttSaveButton() {
        binding.btnSaveMqtt.setOnClickListener {
            val host = binding.etMqttHost.text.toString().trim()
            val port = binding.etMqttPort.text.toString().trim()
            val topicPrefix = binding.etTopicPrefix.text.toString().trim()
            val username = binding.etMqttUsername.text.toString().trim()
            val password = binding.etMqttPassword.text.toString().trim()

            if (host.isEmpty() || port.isEmpty() || topicPrefix.isEmpty()) {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AppConfig.saveMqttSettings(host, port, topicPrefix, username, password)
            Toast.makeText(this, "Saved. Reconnect to apply.", Toast.LENGTH_SHORT).show()
        }
    }

    /** 設定遙控器模式 RadioButton 切換事件 */
    private fun setupRcModeRadio() {
        binding.rgRcMode.setOnCheckedChangeListener { _, checkedId ->
            val modeStr = when (checkedId) {
                binding.rbModeJp.id -> "JP"
                binding.rbModeCh.id -> "CH"
                else -> "USA"
            }
            AppConfig.saveRcMode(modeStr)

            val controlMode = when (modeStr) {
                "JP" -> ControlMode.JP
                "CH" -> ControlMode.CH
                else -> ControlMode.USA
            }
            RemoteControllerKey.KeyControlMode.create().set(controlMode) {
                runOnUiThread {
                    Toast.makeText(this, "RC mode set to $modeStr", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ========== Flight Limits ==========

    /** 載入飛行限制設定到 UI */
    private fun loadFlightLimits() {
        binding.etMaxHeight.setText(AppConfig.getMaxFlightHeight().toInt().toString())
        binding.etMaxRadius.setText(AppConfig.getMaxFlightRadius().toInt().toString())
        binding.etRthHeight.setText(AppConfig.getRthHeight().toInt().toString())
    }

    /** 設定飛行限制儲存按鈕（含輸入驗證） */
    private fun setupFlightLimitsSaveButton() {
        binding.btnSaveFlightLimits.setOnClickListener {
            val heightStr = binding.etMaxHeight.text.toString().trim()
            val radiusStr = binding.etMaxRadius.text.toString().trim()
            val rthStr = binding.etRthHeight.text.toString().trim()

            val height = heightStr.toFloatOrNull()
            val radius = radiusStr.toFloatOrNull()
            var rth = rthStr.toFloatOrNull()

            if (height == null || height < 20 || height > 120) {
                binding.etMaxHeight.error = "請輸入 20–120 之間的數值"
                return@setOnClickListener
            }
            if (radius == null || radius < 15 || radius > 8000) {
                binding.etMaxRadius.error = "請輸入 15–8000 之間的數值"
                return@setOnClickListener
            }
            if (rth == null || rth < 20 || rth > 500) {
                binding.etRthHeight.error = "請輸入 20–500 之間的數值"
                return@setOnClickListener
            }

            // 返航高度不得超過限制高度
            if (rth > height) {
                rth = height
                binding.etRthHeight.setText(rth.toInt().toString())
                Toast.makeText(this, "返航高度已自動調整為限制高度（${rth.toInt()}m）", Toast.LENGTH_SHORT).show()
            }

            AppConfig.saveFlightLimits(height, radius, rth)

            if (isConnected) {
                applyFlightLimitsToAircraft(height, radius, rth)
            } else {
                Toast.makeText(this, "設定已儲存，將於飛機連線後套用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 套用已儲存的飛行限制到無人機 */
    private fun applyStoredFlightLimits() {
        val height = AppConfig.getMaxFlightHeight()
        val radius = AppConfig.getMaxFlightRadius()
        val rth = AppConfig.getRthHeight()
        applyFlightLimitsToAircraft(height, radius, rth)
    }

    /** 將飛行限制參數寫入無人機（KeyManager） */
    private fun applyFlightLimitsToAircraft(height: Float, radius: Float, rth: Float) {
        FlightControllerKey.KeyHeightLimit.create().set(height.toInt()) {
            Log.d(TAG, "限制高度設定成功：${height.toInt()}m")
            runOnUiThread {
                Toast.makeText(this, "限制高度設定成功：${height.toInt()}m", Toast.LENGTH_SHORT).show()
            }
        }

        FlightControllerKey.KeyDistanceLimitEnabled.create().set(true) {
            Log.d(TAG, "距離限制已啟用")
        }

        FlightControllerKey.KeyDistanceLimit.create().set(radius.toInt()) {
            Log.d(TAG, "限制距離設定成功：${radius.toInt()}m")
            runOnUiThread {
                Toast.makeText(this, "限制距離設定成功：${radius.toInt()}m", Toast.LENGTH_SHORT).show()
            }
        }

        FlightControllerKey.KeyGoHomeHeight.create().set(rth.toInt()) {
            Log.d(TAG, "返航高度設定成功：${rth.toInt()}m")
            runOnUiThread {
                Toast.makeText(this, "返航高度設定成功：${rth.toInt()}m", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
