package dji.sampleV5.aircraft.pages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import dji.sampleV5.aircraft.databinding.FragDiagnosticPageBinding
import dji.sampleV5.aircraft.models.DiagnosticVm

/**
 * 診斷資訊頁面。
 *
 * 監聽裝置健康資訊與裝置狀態的變化，
 * 並將結果整理成文字訊息顯示於畫面。
 */
class DiagnosticFragment : DJIFragment() {

    private val diagnosticVm: DiagnosticVm by activityViewModels()
    private var binding: FragDiagnosticPageBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragDiagnosticPageBinding.inflate(inflater, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        diagnosticVm.startListenDeviceHealthInfoChange()
        diagnosticVm.startListenDeviceStatusChange()

        diagnosticVm.deviceHealthInfos.observe(viewLifecycleOwner) {
            updateDiagnosticMsg()
        }

        diagnosticVm.currentDeviceStatus.observe(viewLifecycleOwner) {
            updateDiagnosticMsg()
        }

        diagnosticVm.currentDeviceStatus.observe(viewLifecycleOwner) {
            updateDiagnosticMsg()
        }
    }

    /**
     * 將裝置健康資訊與裝置狀態清單整理為文字訊息並更新畫面。
     */
    private fun updateDiagnosticMsg() {
        val builder = StringBuffer()

        builder.append("DJIDeviceHealthInfo List:").append("\n")
        diagnosticVm.deviceHealthInfos.value?.forEach {
            builder.append("    ").append("code:").append(it.informationCode()).append("\n")
            builder.append("    ").append("warningLevel:").append(it.warningLevel()).append("\n")
            builder.append("    ").append("title:").append(it.title()).append("\n")
            builder.append("    ").append("componentId:").append(it.componentId()).append("\n")
            builder.append("    ").append("sensorIndex:").append(it.sensorIndex()).append("\n")
            builder.append("    ").append("description:").append(it.description()).append("\n")
            builder.append("*******************************").append("\n")
        }

        builder.append("Last DJIDeviceStatus:")
        diagnosticVm.lastDeviceStatus.value?.let {
            builder.append(it).append("(").append(it.description()).append(")")
            builder.append(":").append(it.statusCode()).append("(").append(it.warningLevel()).append(")")
            builder.append("\n")
        }

        builder.append("Current DJIDeviceStatus:")
        diagnosticVm.currentDeviceStatus.value?.let {
            builder.append(it).append("(").append(it.description()).append(")")
            builder.append(":").append(it.statusCode()).append("(").append(it.warningLevel()).append(")")
            builder.append("\n")
        }

        binding?.diagnosticMsg?.text = builder.toString()
    }
}
