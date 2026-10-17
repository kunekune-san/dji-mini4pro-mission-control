package dji.sampleV5.aircraft.settings

import android.os.Bundle
import android.util.Log
import dji.sampleV5.aircraft.util.AppLog
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dji.sampleV5.aircraft.config.AppConfig
import dji.sampleV5.aircraft.dji.CameraManager
import dji.sampleV5.aircraft.databinding.ActivitySettingsBinding
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.camera.VideoResolutionFrameRate
import dji.sdk.keyvalue.value.camera.VideoFileFormat
import dji.sdk.keyvalue.value.camera.VideoFrameRate
import dji.sdk.keyvalue.value.camera.VideoResolution
import dji.sdk.keyvalue.value.camera.VideoStandard
import dji.sdk.keyvalue.value.remotecontroller.ControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.et.set
import dji.v5.manager.KeyManager

/** 設定頁面 Activity（MQTT、飛行限制、遙控器模式設定） */
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
        loadFlightSpeed()
        loadBatteryThreshold()
        loadSignalSmoothing()
        loadMagneticDeclination()
        loadVisualYawOffset()
        setupVideoSettings()
        setupMqttSaveButton()
        setupRcModeRadio()
        setupFlightLimitsSaveButton()
        setupFlightSpeedSeekBar()
        setupBatteryThresholdSeekBar()
        setupSignalSmoothingSeekBar()
        setupMagneticDeclinationSaveButton()
        setupVisualYawOffsetSaveButton()
    }

    override fun onResume() {
        super.onResume()
        // 監聽無人機連線狀態
        FlightControllerKey.KeyConnection.create().listen(this) { connected ->
            val wasConnected = isConnected
            isConnected = connected == true
            AppLog.d(TAG, "Connection state changed: $isConnected")
            runOnUiThread {
                binding.tvFlightLimitsStatus.visibility = if (isConnected) View.GONE else View.VISIBLE
                binding.tvVideoSettingsStatus.visibility = if (isConnected) View.GONE else View.VISIBLE
                // 剛連線時重新套用飛行限制
                if (isConnected && !wasConnected) {
                    applyStoredFlightLimits()
                    loadCurrentVideoSettings()
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

            try {
                AppConfig.saveMqttSettings(host, port, topicPrefix, username, password)
                Toast.makeText(this, "Saved. Reconnect to apply.", Toast.LENGTH_SHORT).show()
            } catch (e: IllegalArgumentException) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            }
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

    /** 載入飛行速度設定到 SeekBar */
    private fun loadFlightSpeed() {
        val speed = AppConfig.getMaxFlightSpeed()
        binding.seekMaxSpeed.progress = (speed - AppConfig.MAX_FLIGHT_SPEED_MIN).toInt()
        binding.tvMaxSpeedValue.text = String.format("目前設定：%.1f m/s", speed)

        val gain = AppConfig.getSpeedGain()
        binding.seekSpeedGain.progress = ((gain - AppConfig.SPEED_GAIN_MIN) * 100).toInt()
        binding.tvSpeedGainValue.text = String.format("目前設定：%.1f", gain)
    }

    /** 設定飛行速度 SeekBar 變更監聽（即時儲存） */
    private fun setupFlightSpeedSeekBar() {
        binding.seekMaxSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = progress + AppConfig.MAX_FLIGHT_SPEED_MIN
                binding.tvMaxSpeedValue.text = String.format("目前設定：%.1f m/s", speed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val speed = (seekBar?.progress ?: 0) + AppConfig.MAX_FLIGHT_SPEED_MIN
                AppConfig.saveMaxFlightSpeed(speed.toFloat())
                Toast.makeText(this@SettingsActivity, String.format("最大飛行速度已設為 %.1f m/s", speed), Toast.LENGTH_SHORT).show()
            }
        })

        binding.seekSpeedGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val gain = progress / 100.0 + AppConfig.SPEED_GAIN_MIN
                binding.tvSpeedGainValue.text = String.format("目前設定：%.1f", gain)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val gain = (seekBar?.progress ?: 0) / 100.0 + AppConfig.SPEED_GAIN_MIN
                AppConfig.saveSpeedGain(gain)
                Toast.makeText(this@SettingsActivity, String.format("速度增益已設為 %.1f", gain), Toast.LENGTH_SHORT).show()
            }
        })
    }

    /** 載入電池返航門檻設定到 SeekBar */
    private fun loadBatteryThreshold() {
        val threshold = AppConfig.getAppRthThreshold()
        binding.seekBatteryThreshold.progress = threshold - AppConfig.APP_RTH_THRESHOLD_MIN
        binding.tvBatteryThresholdValue.text = "目前設定：${threshold}%"
    }

    /** 設定電池門檻 SeekBar 變更監聽（即時儲存） */
    private fun setupBatteryThresholdSeekBar() {
        binding.seekBatteryThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = progress + AppConfig.APP_RTH_THRESHOLD_MIN
                binding.tvBatteryThresholdValue.text = "目前設定：${threshold}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val threshold = (seekBar?.progress ?: 0) + AppConfig.APP_RTH_THRESHOLD_MIN
                AppConfig.saveAppRthThreshold(threshold)
                Toast.makeText(this@SettingsActivity, "電池返航門檻已設為 ${threshold}%", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // ========== Signal Smoothing (P0) ==========

    /** 載入濾波參數設定到 SeekBar */
    private fun loadSignalSmoothing() {
        val gpsAlpha = AppConfig.getGpsEmaAlpha()
        binding.seekGpsEmaAlpha.progress = ((gpsAlpha - AppConfig.GPS_EMA_ALPHA_MIN) * 10).toInt()
        binding.tvGpsEmaAlphaValue.text = String.format("目前設定：%.1f", gpsAlpha)

        val cmdAlpha = AppConfig.getCmdSmoothAlpha()
        binding.seekCmdSmoothAlpha.progress = ((cmdAlpha - AppConfig.CMD_SMOOTH_ALPHA_MIN) * 10).toInt()
        binding.tvCmdSmoothAlphaValue.text = String.format("目前設定：%.1f", cmdAlpha)
    }

    /** 設定濾波參數 SeekBar 變更監聽（即時儲存） */
    private fun setupSignalSmoothingSeekBar() {
        binding.seekGpsEmaAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = progress * 0.1 + AppConfig.GPS_EMA_ALPHA_MIN
                binding.tvGpsEmaAlphaValue.text = String.format("目前設定：%.1f", alpha)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val alpha = (seekBar?.progress ?: 0) * 0.1 + AppConfig.GPS_EMA_ALPHA_MIN
                AppConfig.saveGpsEmaAlpha(alpha)
                Toast.makeText(this@SettingsActivity, String.format("GPS 濾波係數已設為 %.1f", alpha), Toast.LENGTH_SHORT).show()
            }
        })

        binding.seekCmdSmoothAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = progress * 0.1 + AppConfig.CMD_SMOOTH_ALPHA_MIN
                binding.tvCmdSmoothAlphaValue.text = String.format("目前設定：%.1f", alpha)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val alpha = (seekBar?.progress ?: 0) * 0.1 + AppConfig.CMD_SMOOTH_ALPHA_MIN
                AppConfig.saveCmdSmoothAlpha(alpha)
                Toast.makeText(this@SettingsActivity, String.format("指令平滑係數已設為 %.1f", alpha), Toast.LENGTH_SHORT).show()
            }
        })
    }

    // ========== Video Recording Settings ==========

    /** 錄影格式選項（PAL） */
    private val palOptions = listOf(
        "3840×2160 @ 25fps (4K PAL)",
        "3840×2160 @ 50fps (4K PAL)",
        "2704×1520 @ 25fps (2.7K PAL)",
        "2704×1520 @ 50fps (2.7K PAL)",
        "1920×1080 @ 25fps (1080p PAL)",
        "1920×1080 @ 50fps (1080p PAL)"
    )

    /** 錄影格式選項（NTSC） */
    private val ntscOptions = listOf(
        "3840×2160 @ 30fps (4K NTSC)",
        "3840×2160 @ 60fps (4K NTSC)",
        "2704×1520 @ 30fps (2.7K NTSC)",
        "2704×1520 @ 60fps (2.7K NTSC)",
        "1920×1080 @ 30fps (1080p NTSC)",
        "1920×1080 @ 60fps (1080p NTSC)"
    )

    /** 錄影檔格式選項 */
    private val videoFormatOptions = listOf("MP4", "MOV")

    /** 設定錄影格式 UI */
    private fun setupVideoSettings() {
        // 錄影標準 RadioGroup
        binding.rgVideoStandard.setOnCheckedChangeListener { _, checkedId ->
            val options = if (checkedId == binding.rbPal.id) palOptions else ntscOptions
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerResolution.adapter = adapter
        }

        // 預設選擇 NTSC
        binding.rbNtsc.isChecked = true

        // 錄影檔格式 Spinner
        val formatAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, videoFormatOptions)
        formatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerVideoFormat.adapter = formatAdapter

        // 儲存按鈕
        binding.btnSaveVideoSettings.setOnClickListener {
            saveVideoSettings()
        }

        // 如果已連線，載入當前設定
        if (isConnected) {
            loadCurrentVideoSettings()
        } else {
            binding.tvVideoSettingsStatus.visibility = View.VISIBLE
        }
    }

    /** 載入當前錄影設定 */
    private fun loadCurrentVideoSettings() {
        binding.tvVideoSettingsStatus.visibility = View.GONE

        // 讀取錄影標準（先載入，確保 Spinner adapter 正確後再比對解析度）
        CameraManager.getVideoStandard(
            onSuccess = { standard ->
                runOnUiThread {
                    if (standard == VideoStandard.PAL) {
                        binding.rbPal.isChecked = true
                    } else {
                        binding.rbNtsc.isChecked = true
                    }
                    // 標準載入完成後再讀取解析度，確保 adapter 已切換
                    CameraManager.getVideoResolutionFrameRate(
                        onSuccess = { rf ->
                            runOnUiThread {
                                if (rf != null) selectResolutionSpinner(rf)
                            }
                        },
                        onFailure = { error ->
                            AppLog.w(TAG, "Cannot read video resolution: $error")
                        }
                    )
                }
            },
            onFailure = { error ->
                AppLog.w(TAG, "Cannot read video standard: $error")
            }
        )

        // 讀取錄影檔格式（獨立，無依賴）
        CameraManager.getVideoFileFormat(
            onSuccess = { format ->
                runOnUiThread {
                    val index = when (format) {
                        VideoFileFormat.MOV -> 1
                        else -> 0 // MP4
                    }
                    binding.spinnerVideoFormat.setSelection(index)
                }
            },
            onFailure = { error ->
                AppLog.w(TAG, "Cannot read video format: $error")
            }
        )
    }

    /** 根據 VideoResolutionFrameRate 選擇對應的 Spinner 項目 */
    private fun selectResolutionSpinner(rf: VideoResolutionFrameRate) {
        val resolution = rf.resolution ?: return
        val frameRate = rf.frameRate ?: return
        val resText = resolution.name.replace("RESOLUTION_", "").replace("x", "×")
        val fpsText = frameRate.name.replace("RATE_", "").replace("FPS", "fps")

        val adapter = binding.spinnerResolution.adapter ?: return
        for (i in 0 until adapter.count) {
            val item = adapter.getItem(i).toString()
            if (item.contains(resText) && item.contains(fpsText)) {
                binding.spinnerResolution.setSelection(i)
                return
            }
        }
    }

    /** 儲存錄影設定（序列化：標準 → 解析度 → 檔格式，避免競態錯誤） */
    private fun saveVideoSettings() {
        if (!isConnected) {
            Toast.makeText(this, "飛機未連線，無法儲存錄影設定", Toast.LENGTH_SHORT).show()
            return
        }

        // 先切換到錄影模式，完成後再設定錄影參數（避免競態導致 CORE 錯誤）
        CameraManager.setCameraMode(false, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                runOnUiThread { applyVideoSettings() }
            }
            override fun onFailure(error: IDJIError) {
                // 模式切換失敗仍嘗試設定（盡力而為）
                runOnUiThread { applyVideoSettings() }
            }
        })
    }

    /** 套用錄影設定（先讀當前標準，相同則跳過，避免 SDK CORE 錯誤） */
    private fun applyVideoSettings() {
        val standard = if (binding.rbPal.isChecked) VideoStandard.PAL else VideoStandard.NTSC
        val selected = binding.spinnerResolution.selectedItem?.toString() ?: return
        val rf = parseResolutionFrameRate(selected)
        val format = when (binding.spinnerVideoFormat.selectedItem?.toString()) {
            "MOV" -> VideoFileFormat.MOV
            else -> VideoFileFormat.MP4
        }

        // 讀取當前標準，若相同則跳過 setVideoStandard（避免 SDK 拒絕重複設定）
        CameraManager.getVideoStandard(
            onSuccess = { current ->
                if (current == standard) {
                    AppLog.d(TAG, "Video standard unchanged ($current), skipping to resolution")
                    applyResolutionAndFormat(rf, selected, format)
                } else {
                    AppLog.d(TAG, "Video standard changing: $current → $standard")
                    setStandardWithRetry(standard, rf, selected, format, isRetry = false)
                }
            },
            onFailure = {
                AppLog.w(TAG, "Cannot read current standard, attempting set anyway")
                setStandardWithRetry(standard, rf, selected, format, isRetry = false)
            }
        )
    }

    /** 設定錄影標準，失敗時延遲 500ms 重試一次（CORE 錯誤通常是相機忙碌中） */
    private fun setStandardWithRetry(standard: VideoStandard, rf: VideoResolutionFrameRate?, selected: String, format: VideoFileFormat, isRetry: Boolean) {
        CameraManager.setVideoStandard(standard, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "錄影標準已設為 ${standard.name}", Toast.LENGTH_SHORT).show()
                }
                applyResolutionAndFormat(rf, selected, format)
            }
            override fun onFailure(error: IDJIError) {
                if (!isRetry) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        setStandardWithRetry(standard, rf, selected, format, isRetry = true)
                    }, 500)
                } else {
                    // NTSC 在某些機型上 SDK 會回 CORE（可能已為目標標準），不影響後續設定
                    AppLog.w(TAG, "Video standard setting failed (non-critical, continuing): $error")
                    applyResolutionAndFormat(rf, selected, format)
                }
            }
        })
    }

    /** 設定解析度/幀率（含重試），完成後設定檔格式 */
    private fun applyResolutionAndFormat(rf: VideoResolutionFrameRate?, selected: String, format: VideoFileFormat) {
        if (rf != null) {
            setResolutionWithRetry(rf, selected, format, isRetry = false)
        } else {
            CameraManager.setVideoFileFormat(format, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    runOnUiThread {
                        Toast.makeText(this@SettingsActivity, "錄影檔格式已設為 ${format.name}", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(error: IDJIError) {
                    runOnUiThread {
                        Toast.makeText(this@SettingsActivity, "設定錄影檔格式失敗: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
    }

    /** 設定解析度/幀率，失敗時延遲 500ms 重試一次（CORE 錯誤通常是相機忙碌中） */
    private fun setResolutionWithRetry(rf: VideoResolutionFrameRate, selected: String, format: VideoFileFormat, isRetry: Boolean) {
        CameraManager.setVideoResolutionFrameRate(rf, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "錄影格式已設為 $selected", Toast.LENGTH_SHORT).show()
                }
                CameraManager.setVideoFileFormat(format, object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        runOnUiThread {
                            Toast.makeText(this@SettingsActivity, "錄影檔格式已設為 ${format.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onFailure(error: IDJIError) {
                        runOnUiThread {
                            Toast.makeText(this@SettingsActivity, "設定錄影檔格式失敗: $error", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
            override fun onFailure(error: IDJIError) {
                if (!isRetry) {
                    // CORE 錯誤通常是相機在標準切換後重置中，延遲重試
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        setResolutionWithRetry(rf, selected, format, isRetry = true)
                    }, 500)
                } else {
                    runOnUiThread {
                        Toast.makeText(this@SettingsActivity, "設定錄影格式失敗: $error", Toast.LENGTH_SHORT).show()
                    }
                    CameraManager.setVideoFileFormat(format, object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() {
                            runOnUiThread {
                                Toast.makeText(this@SettingsActivity, "錄影檔格式已設為 ${format.name}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onFailure(error: IDJIError) {
                            runOnUiThread {
                                Toast.makeText(this@SettingsActivity, "設定錄影檔格式失敗: $error", Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
                }
            }
        })
    }

    /** 解析選項文字為 VideoResolutionFrameRate */
    private fun parseResolutionFrameRate(text: String): VideoResolutionFrameRate? {
        // 格式: "3840×2160 @ 25fps (4K PAL)"
        val regex = Regex("""(\d+)×(\d+)\s+@\s+(\d+)fps""")
        val match = regex.find(text) ?: return null
        val (w, h, fps) = match.destructured

        val resolution = VideoResolution.values().find { it.name == "RESOLUTION_${w}x${h}" }
        val frameRate = VideoFrameRate.values().find { it.name == "RATE_${fps}FPS" }

        if (resolution == null || frameRate == null) return null
        return VideoResolutionFrameRate(resolution, frameRate)
    }

    // ========== Magnetic Declination ==========

    /** 載入磁偏角設定到 EditText */
    private fun loadMagneticDeclination() {
        val declination = AppConfig.getMagneticDeclination()
        binding.etMagneticDeclination.setText(if (declination == 0.0) "" else declination.toString())
    }

    /** 設定磁偏角儲存按鈕 */
    private fun setupMagneticDeclinationSaveButton() {
        binding.btnSaveMagneticDeclination.setOnClickListener {
            val text = binding.etMagneticDeclination.text.toString().trim()
            if (text.isEmpty()) {
                AppConfig.saveMagneticDeclination(0.0)
                Toast.makeText(this, "磁偏角已設為 0°（不補償）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val declination = text.toDoubleOrNull()
            if (declination == null || declination < -30 || declination > 30) {
                binding.etMagneticDeclination.error = "請輸入 -30 ~ 30 之間的數值"
                return@setOnClickListener
            }
            AppConfig.saveMagneticDeclination(declination)
            Toast.makeText(this, String.format("磁偏角已設為 %.1f°", declination), Toast.LENGTH_SHORT).show()
        }
    }

    // ========== Visual Yaw Offset ==========

    /** 載入 Yaw 偏移設定到 EditText */
    private fun loadVisualYawOffset() {
        val offset = AppConfig.getVisualYawOffset()
        binding.etVisualYawOffset.setText(if (offset == 0.0) "" else offset.toString())
    }

    /** 設定 Yaw 偏移儲存按鈕 */
    private fun setupVisualYawOffsetSaveButton() {
        binding.btnSaveVisualYawOffset.setOnClickListener {
            val text = binding.etVisualYawOffset.text.toString().trim()
            if (text.isEmpty()) {
                AppConfig.saveVisualYawOffset(0.0)
                Toast.makeText(this, "Yaw 偏移已設為 0°（不偏移）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val offset = text.toDoubleOrNull()
            if (offset == null || offset < -45 || offset > 45) {
                binding.etVisualYawOffset.error = "請輸入 -45 ~ +45 之間的數值"
                return@setOnClickListener
            }
            AppConfig.saveVisualYawOffset(offset)
            Toast.makeText(this, String.format("Yaw 偏移已設為 %.1f°/s", offset), Toast.LENGTH_SHORT).show()
        }
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

            if (height == null || height < 20 || height > 500) {
                binding.etMaxHeight.error = "請輸入 20–500 之間的數值"
                return@setOnClickListener
            }
            if (radius == null || radius < 15 || radius > 40000) {
                binding.etMaxRadius.error = "請輸入 15–40000 之間的數值"
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
            AppLog.d(TAG, "限制高度設定成功：${height.toInt()}m")
            runOnUiThread {
                Toast.makeText(this, "限制高度設定成功：${height.toInt()}m", Toast.LENGTH_SHORT).show()
            }
        }

        FlightControllerKey.KeyDistanceLimitEnabled.create().set(false) {
            AppLog.d(TAG, "距離限制已關閉（無限）")
        }

        FlightControllerKey.KeyDistanceLimit.create().set(radius.toInt()) {
            AppLog.d(TAG, "限制距離設定成功：${radius.toInt()}m")
            runOnUiThread {
                Toast.makeText(this, "限制距離設定成功：${radius.toInt()}m", Toast.LENGTH_SHORT).show()
            }
        }

        FlightControllerKey.KeyGoHomeHeight.create().set(rth.toInt()) {
            AppLog.d(TAG, "返航高度設定成功：${rth.toInt()}m")
            runOnUiThread {
                Toast.makeText(this, "返航高度設定成功：${rth.toInt()}m", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
