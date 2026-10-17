package dji.sampleV5.aircraft.pages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import dji.sampleV5.aircraft.databinding.FragAppSilentlyUpgradePageBinding
import dji.sampleV5.aircraft.models.APPSilentlyUpgradeVM

/**
 * APP 靜默升級頁面。
 *
 * 提供「靜默升級套件」與「安裝測試 App」兩個按鈕，
 * 分別觸發無使用者介面的 APK 安裝流程。
 */
class APPSilentlyUpgradeFragment : DJIFragment() {
    private val vm: APPSilentlyUpgradeVM by activityViewModels()
    private var binding: FragAppSilentlyUpgradePageBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragAppSilentlyUpgradePageBinding.inflate(inflater, container, false)
        return binding?.root
    }

    /**
     * 設定兩個按鈕的點擊事件：靜默升級套件 / 安裝測試 App。
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.btnSilentlyUpgradePackage?.setOnClickListener {
            vm.setAPPSilentlyUpgrade(requireContext())
        }
        binding?.btnInstallTestApp?.setOnClickListener {
            vm.installApkWithOutNotice(requireContext())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}