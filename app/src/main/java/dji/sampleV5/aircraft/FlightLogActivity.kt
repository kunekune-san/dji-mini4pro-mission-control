package dji.sampleV5.aircraft

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import dji.sampleV5.aircraft.databinding.ActivityFlightLogBinding
import dji.sampleV5.aircraft.safety.SafetyEventLogger

/**
 * 飛行紀錄頁面。
 * 顯示安全事件記錄（CSV），支援分享與清除全部紀錄。
 */
class FlightLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFlightLogBinding
    private lateinit var logger: SafetyEventLogger

    /** 建立頁面並初始化綁定、記錄器與按鈕事件。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFlightLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        logger = SafetyEventLogger(this)

        binding.btnBack.setOnClickListener { finish() }

        refreshList()

        binding.btnShare.setOnClickListener {
            val logFile = logger.getLogFile()
            if (!logFile.exists()) return@setOnClickListener
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileProvider", logFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "分享飛行記錄"))
        }

        binding.btnClearLog.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清除所有紀錄")
                .setMessage("確定要清除所有飛行紀錄嗎？此操作無法復原。")
                .setPositiveButton("清除") { _, _ ->
                    logger.clearAll()
                    refreshList()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun refreshList() {
        val records = logger.getRecentAnomalies(200)
        if (records.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvFlightLog.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.rvFlightLog.visibility = View.VISIBLE
            binding.rvFlightLog.layoutManager = LinearLayoutManager(this)
            binding.rvFlightLog.adapter = FlightLogAdapter(records)
        }
    }
}
