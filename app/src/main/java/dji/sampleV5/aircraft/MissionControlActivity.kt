package dji.sampleV5.aircraft

import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dji.sampleV5.aircraft.databinding.ActivityMissionControlBinding
import dji.sampleV5.aircraft.dji.CameraManager
import dji.sampleV5.aircraft.dji.FlightManager
import dji.sampleV5.aircraft.mission.MissionStateMachine
import dji.sampleV5.aircraft.mission.MissionStateMachine.MissionState
import dji.sampleV5.aircraft.mission.VisualYawAssist
import dji.sampleV5.aircraft.mqtt.MqttClientManager
import dji.sampleV5.aircraft.util.AppLog
import dji.sampleV5.aircraft.mqtt.MqttClientManager.ConnectionState
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.interfaces.ICameraStreamManager.FrameFormat
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 全螢幕 FPV + HUD 任務控制頁。
 * 顯示飛行遙測資料與任務狀態，負責 FPV 串流、視覺 Yaw 追蹤、MQTT 狀態顯示，並提供取消任務按鈕。
 */
class MissionControlActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val BACK_GUARD_MS = 3000L  // 任務完成後 3 秒內攔截返回鍵
    }

    private lateinit var binding: ActivityMissionControlBinding
    private var surfaceReady = false

    // ========== Visual Yaw Assist ==========
    private var frameListener: ICameraStreamManager.CameraFrameListener? = null
    @Volatile private var isVisualSelectMode = false

    // ========== Back-Button Guard ==========
    private var lastMissionCompleteTime: Long = 0L

    /** 建立頁面：設定全螢幕、綁定 FPV Surface、視覺點擊與取消任務事件，並啟動 HUD 資料流監聽。 */
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

        // 視覺追蹤：僅在選取等待窗內（visualPreSelection 的 2 秒停頓）接受點擊
        // 旋轉開始後不再接受新選取，避免干擾環繞 yaw 控制
        binding.surfaceViewFpv.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && VisualYawAssist.waitingForSelection) {
                onFpvTapped(event.x, event.y)
            }
            true
        }

        binding.btnCancelMission.setOnClickListener {
            MissionStateMachine.cancelMission(reason = "User pressed cancel button")
        }

        observeFlows()
    }

    override fun onResume() {
        super.onResume()
        if (surfaceReady) {
            addFpvSurface()
        }
        loadVideoFormat()
    }

    override fun onPause() {
        removeFpvSurface()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            // 診斷用：記錄 activity 被 finish 的呼叫棧
            AppLog.w("MissionCtrl", "Activity is finishing — missionState=${MissionStateMachine.state.value}")
            AppLog.e("MissionCtrl", "Activity finish stack trace", Exception("MissionControlActivity finish trace"))
        }
    }

    override fun onDestroy() {
        binding.surfaceViewFpv.holder.removeCallback(this)
        super.onDestroy()
    }

    override fun onBackPressed() {
        // 任務剛完成 3 秒內攔截返回鍵，防止誤觸跳回主畫面
        val elapsed = System.currentTimeMillis() - lastMissionCompleteTime
        if (elapsed < BACK_GUARD_MS) {
            android.widget.Toast.makeText(this, "再次按返回鍵離開", android.widget.Toast.LENGTH_SHORT).show()
            lastMissionCompleteTime = 0L  // 重置，讓第二次按可以離開
            return
        }
        super.onBackPressed()
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

    /** 將 FPV 串流畫面綁定到 SurfaceView，同時啟動幀監聽供視覺追蹤使用 */
    private fun addFpvSurface() {
        val surface = binding.surfaceViewFpv.holder.surface
        val width = binding.surfaceViewFpv.width
        val height = binding.surfaceViewFpv.height
        if (width <= 0 || height <= 0) return

        val csm = MediaDataCenter.getInstance().cameraStreamManager
        csm.putCameraStreamSurface(
            ComponentIndexType.LEFT_OR_MAIN, surface, width, height,
            ICameraStreamManager.ScaleType.CENTER_INSIDE
        )

        // 註冊幀監聽（NV21 格式，供 VisualYawAssist 使用）
        if (frameListener == null) {
            val listener = object : ICameraStreamManager.CameraFrameListener {
                override fun onFrame(frameData: ByteArray, offset: Int, length: Int, w: Int, h: Int, format: FrameFormat) {
                    if (offset == 0 && length > 0) {
                        // 使用者點擊了畫面 → 從當前幀提取模板
                        if (pendingSelect) {
                            pendingSelect = false
                            VisualYawAssist.selectTarget(frameData, w, h, pendingSelectX, pendingSelectY)
                        }
                        // 持續追蹤
                        if (isVisualSelectMode && VisualYawAssist.hasTarget()) {
                            VisualYawAssist.processFrame(frameData, w, h)
                        }
                    }
                }
            }
            frameListener = listener
            csm.addFrameListener(ComponentIndexType.LEFT_OR_MAIN, FrameFormat.NV21, listener)
        }
    }

    /** 移除 FPV 串流畫面與幀監聽 */
    private fun removeFpvSurface() {
        val csm = MediaDataCenter.getInstance().cameraStreamManager
        try {
            csm.removeCameraStreamSurface(binding.surfaceViewFpv.holder.surface)
        } catch (e: Exception) { android.util.Log.w("MissionControl", "removeCameraStreamSurface failed", e) }

        frameListener?.let {
            try { csm.removeFrameListener(it) } catch (_: Exception) {}
            frameListener = null
        }
    }

    // ========== Visual Yaw Assist ==========

    /** FPV 畫面被點擊：提取當前幀的 Y 平面作為追蹤模板 */
    private fun onFpvTapped(x: Float, y: Float) {
        // 取得當前幀數據（透過 CameraStreamManager 的 addFrameListener 持續更新）
        // 由於 processFrame 已在幀回調中執行，這裡只需要觸發 selectTarget
        // 但 selectTarget 需要幀數據，所以我們設一個待處理標記
        pendingSelectX = x
        pendingSelectY = y
        pendingSelect = true
        binding.tvVisualTargetHint.text = "⏳ 正在擷取目標..."
    }

    @Volatile private var pendingSelect = false
    @Volatile private var pendingSelectX = 0f
    @Volatile private var pendingSelectY = 0f

    /** 更新視覺追蹤狀態顯示 */
    private fun updateVisualTrackHud() {
        if (!isVisualSelectMode) {
            binding.tvVisualTargetHint.visibility = View.GONE
            binding.tvVisualTrackStatus.visibility = View.GONE
            return
        }

        if (VisualYawAssist.hasTarget()) {
            binding.tvVisualTargetHint.visibility = View.GONE
            binding.tvVisualTrackStatus.visibility = View.VISIBLE
            val conf = VisualYawAssist.getConfidence()
            val confBar = when {
                conf >= 0.8f -> "████"
                conf >= 0.6f -> "███░"
                conf >= 0.4f -> "██░░"
                conf >= 0.2f -> "█░░░"
                else         -> "░░░░"
            }
            binding.tvVisualTrackStatus.text = "VIS  $confBar ${"%.0f".format(conf * 100)}%"
            binding.tvVisualTrackStatus.setTextColor(
                if (conf >= 0.6f) 0xFF00FF88.toInt() else 0xFFFF9500.toInt()
            )
        } else if (VisualYawAssist.waitingForSelection) {
            // 選取等待窗期間：顯示倒數提示
            binding.tvVisualTargetHint.visibility = View.VISIBLE
            binding.tvVisualTargetHint.text = "🎯 點擊選取追蹤目標（自動開始環繞）"
            binding.tvVisualTrackStatus.visibility = View.GONE
        } else {
            binding.tvVisualTargetHint.visibility = View.VISIBLE
            binding.tvVisualTargetHint.text = "🎯 點擊畫面選取追蹤目標"
            binding.tvVisualTrackStatus.visibility = View.GONE
        }
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
                launch { observeHeading() }
                launch { observeSafetyWarning() }
                launch { observeMultiProgress() }
                launch { observeFirmwareRth() }
                launch { observeVisualTrackHud() }
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
        combine(FlightManager.gpsSatellites, FlightManager.gpsSignalLevel) { sats, sig ->
            Pair(sats, sig)
        }.collect { (sats, sig) ->
            val sigVal = sig ?: 0
            val bars = when {
                sigVal >= 4 -> "▂▄▆█"
                sigVal == 3 -> "▂▄▆·"
                sigVal == 2 -> "▂▄··"
                sigVal == 1 -> "▂···"
                else        -> "····"
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

    /** 監聽任務狀態並更新 HUD 與取消按鈕顯示，以及視覺追蹤模式 */
    private suspend fun observeMissionState() {
        var previousState: MissionState? = null
        MissionStateMachine.state.collectLatest { state ->
            binding.tvMissionState.text = "任務  ${missionStateToText(state)}"
            // 取消按鈕：僅在飛行任務進行中顯示（非 IDLE / RETURNING_HOME / ERROR）
            val inFlight = state != MissionState.IDLE &&
                state != MissionState.RETURNING_HOME &&
                state != MissionState.ERROR
            binding.btnCancelMission.visibility = if (inFlight) View.VISIBLE else View.GONE

            // 任務完成（非 IDLE → IDLE）：記錄時間戳供返回鍵防誤觸，並強制清除多站進度
            if (state == MissionState.IDLE && previousState != null && previousState != MissionState.IDLE) {
                lastMissionCompleteTime = System.currentTimeMillis()
                binding.tvMultiProgress.visibility = View.GONE
            }
            previousState = state

            // 視覺追蹤：環繞/螺旋階段啟用選取模式，結束時重置
            val wasVisualMode = isVisualSelectMode
            isVisualSelectMode = state == MissionState.ORBITING ||
                state == MissionState.SPIRAL_ASCENDING ||
                state == MissionState.FLYING_TO_TARGET ||
                state == MissionState.FLYING_TO_NEXT ||
                state == MissionState.DESCENDING_TO_ORBIT_ALT

            if (isVisualSelectMode && !wasVisualMode) {
                AppLog.d("MissionCtrl", "Visual select mode enabled (state=$state)")
            }
            if (!isVisualSelectMode && wasVisualMode) {
                VisualYawAssist.reset()
                pendingSelect = false
                AppLog.d("MissionCtrl", "Visual select mode disabled, target reset")
            }
            updateVisualTrackHud()
        }
    }

    /** 週期性更新視覺追蹤 HUD（500ms 間隔） */
    private suspend fun observeVisualTrackHud() {
        while (true) {
            updateVisualTrackHud()
            delay(500)
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

    // ========== 新增 HUD 觀察 ==========

    /** 監聽機頭朝向並更新 HUD */
    private suspend fun observeHeading() {
        FlightManager.heading.collectLatest { hdg ->
            binding.tvHeading.text = "HDG  %.1f°".format(hdg)
        }
    }

    /** 監聽安全警告並更新 HUD */
    private suspend fun observeSafetyWarning() {
        FlightManager.safetyWarning.collectLatest { warning ->
            if (warning != null) {
                binding.tvSafetyWarning.text = warning
                binding.tvSafetyWarning.visibility = View.VISIBLE
            } else {
                binding.tvSafetyWarning.visibility = View.GONE
            }
        }
    }

    /** 監聽多站進度並更新 HUD */
    private suspend fun observeMultiProgress() {
        MissionStateMachine.multiStopProgress.collectLatest { progress ->
            if (progress != null) {
                binding.tvMultiProgress.text = "站點 %d/%d".format(progress.first, progress.second)
                binding.tvMultiProgress.visibility = View.VISIBLE
            } else {
                binding.tvMultiProgress.visibility = View.GONE
            }
        }
    }

    /** 監聽韌體 RTH 狀態並更新 HUD */
    private suspend fun observeFirmwareRth() {
        FlightManager.isFirmwareRthActive.collectLatest { active ->
            binding.tvFirmwareRth.visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    // ========== Helpers ==========

    /** 查詢當前錄影格式並更新 HUD（onResume 時呼叫） */
    private fun loadVideoFormat() {
        CameraManager.getVideoResolutionFrameRate(
            onSuccess = { rf ->
                runOnUiThread {
                    binding.tvVideoFormat.text = "REC  ${formatToCompact(rf)}"
                }
            },
            onFailure = {
                runOnUiThread { binding.tvVideoFormat.text = "REC  --" }
            }
        )
    }

    /** 將 VideoResolutionFrameRate 轉為緊湊顯示（如 "4K30", "1080p60"） */
    private fun formatToCompact(rf: dji.sdk.keyvalue.value.camera.VideoResolutionFrameRate?): String {
        if (rf == null) return "--"
        val res = rf.resolution?.name ?: return "--"
        val fps = rf.frameRate?.name ?: return "--"
        val resLabel = when {
            res.contains("3840x2160") -> "4K"
            res.contains("2704x1520") -> "2.7K"
            res.contains("1920x1080") -> "1080p"
            else -> res.replace("RESOLUTION_", "").replace("x", "×")
        }
        val fpsNum = fps.replace("RATE_", "").replace("FPS", "")
        return "$resLabel$fpsNum"
    }

    /** 將任務狀態轉換為中文顯示文字 */
    private fun missionStateToText(state: MissionState): String {
        return when (state) {
            MissionState.IDLE                  -> "待機"
            MissionState.TAKING_OFF            -> "起飛中"
            MissionState.FLYING_TO_TARGET      -> "飛往目標"
            MissionState.DESCENDING_TO_ORBIT_ALT -> "降至環繞高度"
            MissionState.ORBITING              -> "環繞錄影中"
            MissionState.SPIRAL_ASCENDING      -> "螺旋上升錄影中"
            MissionState.RECORDING_360         -> "360° 錄影中"
            MissionState.SHOOTING_PHOTOS       -> "拍照中"
            MissionState.FLYING_TO_NEXT        -> "飛往下一站"
            MissionState.GIMBAL_PULL_UP        -> "雲台回正中"
            MissionState.RETURNING_HOME        -> "返航中"
            MissionState.ERROR                 -> "⚠ 錯誤"
        }
    }
}
