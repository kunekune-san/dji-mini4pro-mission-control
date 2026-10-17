package dji.sampleV5.aircraft.safety

import android.content.Context
import android.util.Log
import dji.sampleV5.aircraft.util.AppLog
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException

class SafetyEventLogger(context: Context) {

    companion object {
        private const val TAG = "SafetyEventLogger"
        private const val DIR_NAME = "safety_events"
        private const val FILE_NAME = "anomaly_log.csv"
        private const val CSV_HEADER = "timestamp,event_type,latitude,longitude,altitude_m,battery_pct,gps_signal_level,gps_satellites,reason,mqtt_payload"
    }

    private val logDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME)
    private val logFile: File = File(logDir, FILE_NAME)

    init {
        if (!logDir.exists()) {
            if (!logDir.mkdirs()) {
                Log.e(TAG, "Failed to create log directory: ${logDir.absolutePath}")
            }
        }
        if (!logFile.exists()) {
            try {
                logFile.createNewFile()
                logFile.appendText("$CSV_HEADER\n")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to create log file: ${e.message}")
            }
        }
    }

    /** 記錄安全異常事件到 CSV 檔案（執行緒安全） */
    @Synchronized
    fun logAnomaly(record: SafetyAnomalyRecord) {
        try {
            val escapedReason = record.reason.replace("\"", "\"\"")
            val escapedPayload = record.mqttPayload?.replace("\"", "\"\"") ?: ""
            val line = "${record.timestamp},${record.eventType},${record.latitude},${record.longitude}," +
                "${record.altitude},${record.batteryPercent},${record.gpsSignalLevel}," +
                "${record.gpsSatellites},\"$escapedReason\",\"$escapedPayload\""
            BufferedWriter(FileWriter(logFile, true)).use { writer ->
                writer.write(line)
                writer.newLine()
            }
            AppLog.d(TAG, "Anomaly logged: ${record.eventType} at (${record.latitude}, ${record.longitude})")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to log anomaly: ${e.message}")
        }
    }

    /** 取得最近 N 筆異常記錄 */
    @Synchronized
    fun getRecentAnomalies(count: Int): List<SafetyAnomalyRecord> {
        return try {
            val lines = logFile.readLines()
            val dataLines = if (lines.size > 1) lines.subList(1, lines.size) else emptyList()
            dataLines.takeLast(count).mapNotNull { line ->
                try {
                    val parts = parseCsvLine(line)
                    if (parts.size < 9) {
                        Log.w(TAG, "CSV line has ${parts.size} fields, expected 9+, skipping")
                        return@mapNotNull null
                    }
                    SafetyAnomalyRecord(
                        timestamp = parts[0].toLong(),
                        eventType = parts[1],
                        latitude = parts[2].toDouble(),
                        longitude = parts[3].toDouble(),
                        altitude = parts[4].toDouble(),
                        batteryPercent = parts[5].toInt(),
                        gpsSignalLevel = parts[6].toInt(),
                        gpsSatellites = parts[7].toInt(),
                        reason = parts[8],
                        mqttPayload = if (parts.size > 9 && parts[9].isNotEmpty()) parts[9] else null
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read anomalies: ${e.message}")
            emptyList()
        }
    }

    /** 取得 CSV 日誌檔案路徑 */
    fun getLogFile(): File = logFile

    /** 清除所有記錄（重寫 CSV 檔案，僅保留標頭） */
    @Synchronized
    fun clearAll() {
        try {
            logFile.writeText("$CSV_HEADER\n")
            AppLog.d(TAG, "All flight logs cleared")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to clear logs: ${e.message}")
        }
    }

    /** 解析 CSV 行（支援雙引號包覆的欄位） */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { result.add(current.toString()); current.clear() }
                else -> current.append(ch)
            }
        }
        result.add(current.toString())
        return result
    }
}
