package dji.sampleV5.aircraft

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dji.sampleV5.aircraft.databinding.ActivityMissionControlBinding
import dji.sampleV5.aircraft.dji.FlightManager
import dji.sampleV5.aircraft.mission.MissionStateMachine
import dji.sampleV5.aircraft.mission.MissionStateMachine.MissionState
import dji.sampleV5.aircraft.mqtt.MqttClientManager
import dji.sampleV5.aircraft.mqtt.MqttClientManager.ConnectionState
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MissionControlActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityMissionControlBinding
    private var surfaceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全螢幕（沿用專案現有模式）
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        binding = ActivityMissionControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.surfaceViewFpv.holder.addCallback(this)

        binding.btnCancelMission.setOnClickListener {
            MissionStateMachine.cancelMission()
        }

        observeFlows()
    }

    override fun onResume() {
        super.onResume()
        if (surfaceReady) {
            addFpvSurface()
        }
    }

    override fun onPause() {
        removeFpvSurface()
        super.onPause()
    }

    override fun onDestroy() {
        binding.surfaceViewFpv.holder.removeCallback(this)
        super.onDestroy()
    }

    // ========== SurfaceHolder.Callback ==========

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        addFpvSurface()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        removeFpvSurface()
        surfaceReady = false
    }

    // ========== FPV Stream ==========

    /** 將 FPV 串流畫面綁定到 SurfaceView */
    private fun addFpvSurface() {
        val surface = binding.surfaceViewFpv.holder.surface
        val width = binding.surfaceViewFpv.width
        val height = binding.surfaceViewFpv.height
        if (width <= 0 || height <= 0) return

        MediaDataCenter.getInstance().cameraStreamManager.putCameraStreamSurface(
            ComponentIndexType.LEFT_OR_MAIN, surface, width, height,
            ICameraStreamManager.ScaleType.CENTER_INSIDE
        )
    }

    /** 移除 FPV 串流畫面 */
    private fun removeFpvSurface() {
        try {
            MediaDataCenter.getInstance().cameraStreamManager
                .removeCameraStreamSurface(binding.surfaceViewFpv.holder.surface)
        } catch (_: Exception) {}
    }

    // ========== HUD Flow Collection ==========

    /** 啟動所有 HUD 資料流監聽 */
    private fun observeFlows() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { observeAltitude() }
                launch { observeDistance() }
                launch { observeBattery() }
                launch { observeGps() }
                launch { observeCoordinates() }
                launch { observeMissionState() }
                launch { observeMqttState() }
            }
        }
    }

    /** 監聽飛行高度並更新 HUD */
    private suspend fun observeAltitude() {
        FlightManager.altitude.collectLatest { alt ->
            binding.tvAltitude.text = "ALT  %.1f m".format(alt)
        }
    }

    /** 監聽與起飛點距離並更新 HUD */
    private suspend fun observeDistance() {
        FlightManager.distanceFromHome.collectLatest { dist ->
            binding.tvDistance.text = "DST  %.1f m".format(dist)
        }
    }

    /** 監聽電池百分比並更新 HUD */
    private suspend fun observeBattery() {
        FlightManager.batteryPercent.collectLatest { pct ->
            binding.tvBattery.text = "BAT  %d%%".format(pct)
        }
    }

    /** 監聽 GPS 衛星數與訊號強度並更新 HUD */
    private suspend fun observeGps() {
        FlightManager.gpsSatellites.collectLatest { sats ->
            val sig = FlightManager.gpsSignalLevel.value ?: 0
            val bars = when {
                sig >= 4 -> "▂▄▆█"
                sig == 3 -> "▂▄▆·"
                sig == 2 -> "▂▄··"
                sig == 1 -> "▂···"
                else     -> "····"
            }
            binding.tvGps.text = "GPS  %d %s".format(sats ?: 0, bars)
        }
    }

    /** 監聽 GPS 座標並更新 HUD */
    private suspend fun observeCoordinates() {
        combine(FlightManager.currentLat, FlightManager.currentLng) { lat, lng ->
            Pair(lat, lng)
        }.collect { (lat, lng) ->
            binding.tvCoordinates.text = if (lat != null && lng != null) {
                "LAT  %.6f\nLNG  %.6f".format(lat, lng)
            } else {
                "LAT  --\nLNG  --"
            }
        }
    }

    /** 監聽任務狀態並更新 HUD 與取消按鈕顯示 */
    private suspend fun observeMissionState() {
        MissionStateMachine.state.collectLatest { state ->
            binding.tvMissionState.text = "任務  ${missionStateToText(state)}"
            // 取消按鈕：僅在飛行任務進行中顯示（非 IDLE / RETURNING_HOME / ERROR）
            val inFlight = state != MissionState.IDLE &&
                state != MissionState.RETURNING_HOME &&
                state != MissionState.ERROR
            binding.btnCancelMission.visibility = if (inFlight) View.VISIBLE else View.GONE
        }
    }

    /** 監聽 MQTT 連線狀態並更新 HUD */
    private suspend fun observeMqttState() {
        MqttClientManager.connectionState.collectLatest { state ->
            val (icon, label) = when (state) {
                ConnectionState.CONNECTED    -> "🟢" to "MQTT 已連線"
                ConnectionState.CONNECTING   -> "🟡" to "MQTT 連線中"
                ConnectionState.DISCONNECTED -> "🔴" to "MQTT 斷線"
                ConnectionState.ERROR        -> "🔴" to "MQTT 錯誤"
                else                         -> "⚪" to "MQTT 未知"
            }
            binding.tvMqttState.text = "$icon $label"
        }
    }

    // ========== Helpers ==========

    /** 將任務狀態轉換為中文顯示文字 */
    private fun missionStateToText(state: MissionState): String {
        return when (state) {
            MissionState.IDLE                  -> "待機"
            MissionState.TAKING_OFF            -> "起飛中"
            MissionState.FLYING_TO_TARGET      -> "飛往目標"
            MissionState.DESCENDING_TO_ORBIT_ALT -> "降至環繞高度"
            MissionState.ORBITING              -> "環繞錄影中"
            MissionState.RECORDING_360         -> "360° 錄影中"
            MissionState.SHOOTING_PHOTOS       -> "拍照中"
            MissionState.RETURNING_HOME        -> "返航中"
            MissionState.ERROR                 -> "⚠ 錯誤"
        }
    }
}
