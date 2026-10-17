package dji.sampleV5.aircraft

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dji.sampleV5.aircraft.databinding.ItemFlightLogBinding
import dji.sampleV5.aircraft.safety.SafetyAnomalyRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 飛行紀錄列表的 RecyclerView Adapter。
 * 將安全異常記錄轉為列表項目，顯示時間、類型、原因與 MQTT Payload 等詳細資料。
 */
class FlightLogAdapter(private val records: List<SafetyAnomalyRecord>) :
    RecyclerView.Adapter<FlightLogAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private val typeLabels = mapOf(
        "LOW_BATTERY_RTH" to "低電池返航",
        "BATTERY_FORCED_LAND" to "電量強制降落",
        "FIRMWARE_RTH" to "韌體自動返航",
        "GPS_SIGNAL_WEAK" to "GPS 訊號弱",
        "GPS_SIGNAL_LOST" to "GPS 訊號遺失",
        "MISSION_CANCELLED" to "任務取消",
        "MISSION_VALIDATION_FAIL" to "參數驗證失敗",
        "MISSION_ERROR" to "任務執行異常",
        "SPIRAL_TIMEOUT" to "螺旋超時",
        "ORBIT_FLYAWAY" to "環繞飛離",
        "BATTERY_ABORT" to "電量不足中止",
        "RTH_FAILED" to "返航失敗",
        "VIRTUAL_STICK_FAILED" to "VirtualStick 失敗"
    )

    class ViewHolder(val binding: ItemFlightLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFlightLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]
        holder.binding.apply {
            tvLogTime.text = dateFormat.format(Date(record.timestamp))
            tvLogType.text = typeLabels[record.eventType] ?: record.eventType
            tvLogReason.text = record.reason
            tvLogDetails.text = "%.4f° %.4f°  ALT %.1fm  BAT %d%%  GPS %d".format(
                record.latitude, record.longitude,
                record.altitude, record.batteryPercent, record.gpsSatellites
            )
            // 有 MQTT Payload 時顯示
            if (!record.mqttPayload.isNullOrEmpty()) {
                tvMqttPayload.visibility = android.view.View.VISIBLE
                tvMqttPayload.text = "MQTT: ${record.mqttPayload}"
            } else {
                tvMqttPayload.visibility = android.view.View.GONE
            }
        }
    }

    override fun getItemCount(): Int = records.size
}
