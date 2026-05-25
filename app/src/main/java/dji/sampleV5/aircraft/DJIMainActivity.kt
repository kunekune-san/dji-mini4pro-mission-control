package dji.sampleV5.aircraft

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import dji.sampleV5.aircraft.databinding.ActivityMainBinding
import dji.sampleV5.aircraft.models.BaseMainActivityVm
import dji.sampleV5.aircraft.models.MSDKInfoVm
import dji.sampleV5.aircraft.models.MSDKManagerVM
import dji.sampleV5.aircraft.models.globalViewModels
import dji.sampleV5.aircraft.util.Helper
import dji.sampleV5.aircraft.util.ToastUtils
import dji.v5.utils.common.LogUtils
import dji.v5.utils.common.PermissionUtil
import dji.v5.utils.common.StringUtils
import androidx.lifecycle.lifecycleScope
import dji.sampleV5.aircraft.dji.FlightManager
import dji.sampleV5.aircraft.mission.MissionStateMachine
import dji.sampleV5.aircraft.mqtt.MqttClientManager
import dji.sampleV5.aircraft.mqtt.MqttMissionBridge
import dji.sampleV5.aircraft.mqtt.MqttStatusReporter
import io.reactivex.rxjava3.disposables.CompositeDisposable
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Class Description
 *
 * @author Hoker
 * @date 2022/2/10
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
abstract class DJIMainActivity : AppCompatActivity() {

    val tag: String = LogUtils.getTag(this)
    private val permissionArray = arrayListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.KILL_BACKGROUND_PROCESSES,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    init {
        permissionArray.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                add(Manifest.permission.READ_MEDIA_IMAGES)
//                add(Manifest.permission.READ_MEDIA_VIDEO)
//                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private val baseMainActivityVm: BaseMainActivityVm by viewModels()
    private val msdkInfoVm: MSDKInfoVm by viewModels()
    private val msdkManagerVM: MSDKManagerVM by globalViewModels()
    protected lateinit var binding: ActivityMainBinding
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val disposable = CompositeDisposable()

    abstract fun prepareUxActivity()

    abstract fun prepareTestingToolsActivity()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 有一些手机从系统桌面进入的时候可能会重启main类型的activity
        // 需要校验这种情况，业界标准做法，基本所有app都需要这个
        if (!isTaskRoot && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && Intent.ACTION_MAIN == intent.action) {

                finish()
                return

        }

        window.decorView.apply {
            systemUiVisibility =
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }

        initMSDKInfoView()
        observeSDKManager()
        checkPermissionAndRequest()

        // Initialize MQTT connection and observe state
        initMqttConnection()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkPermission()) {
            handleAfterPermissionPermitted()
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkPermission()) {
            handleAfterPermissionPermitted()
        }
    }

    /** 權限授予後的處理（準備測試工具頁面） */
    private fun handleAfterPermissionPermitted() {
        prepareTestingToolsActivity()
    }

    /** 初始化 MSDK 資訊顯示區域 */
    @SuppressLint("SetTextI18n")
    private fun initMSDKInfoView() {
        msdkInfoVm.msdkInfo.observe(this) {
            binding.textViewVersion.text = StringUtils.getResStr(R.string.sdk_version, it.SDKVersion + " " + it.buildVer)
            binding.textViewProductName.text = StringUtils.getResStr(R.string.product_name, it.productType.name)
            binding.textViewPackageProductCategory.text = StringUtils.getResStr(R.string.package_product_category, it.packageProductCategory)
            binding.textViewIsDebug.text = StringUtils.getResStr(R.string.is_sdk_debug, it.isDebug)
            binding.textCoreInfo.text = it.coreInfo.toString()
        }

        binding.iconSdkForum.setOnClickListener {
            Helper.startBrowser(this, StringUtils.getResStr(R.string.sdk_forum_url))
        }

        binding.iconReleaseNode.setOnClickListener {
            Helper.startBrowser(this, StringUtils.getResStr(R.string.release_node_url))
        }
        binding.iconTechSupport.setOnClickListener {
            Helper.startBrowser(this, StringUtils.getResStr(R.string.tech_support_url))
        }
        binding.viewBaseInfo.setOnClickListener {
            baseMainActivityVm.doPairing {
                showToast(it)
            }
        }
    }

    /** 監聽 SDK 註冊狀態與產品連線事件 */
    private fun observeSDKManager() {
        msdkManagerVM.lvRegisterState.observe(this) { resultPair ->
            val statusText: String?
            if (resultPair.first) {
                ToastUtils.showToast("Register Success")
                statusText = StringUtils.getResStr(this, R.string.registered)
                msdkInfoVm.initListener()
                initFlightManager()
                handler.postDelayed({
                    prepareUxActivity()
                }, 5000)
            } else {
                showToast("Register Failure: ${resultPair.second}")
                statusText = StringUtils.getResStr(this, R.string.unregistered)
            }
            binding.textViewRegistered.text = StringUtils.getResStr(R.string.registration_status, statusText)
        }

        msdkManagerVM.lvProductConnectionState.observe(this) { resultPair ->
            showToast("Product: ${resultPair.second} ,ConnectionState:  ${resultPair.first}")
        }

        msdkManagerVM.lvProductChanges.observe(this) { productId ->
            showToast("Product: $productId Changed")
        }

        msdkManagerVM.lvInitProcess.observe(this) { processPair ->
            showToast("Init Process event: ${processPair.first.name}")
        }

        msdkManagerVM.lvDBDownloadProgress.observe(this) { resultPair ->
            showToast("Database Download Progress current: ${resultPair.first}, total: ${resultPair.second}")
        }
    }

    /** 顯示 Toast 提示 */
    private fun showToast(content: String) {
        ToastUtils.showToast(content)

    }


    /** 啟用預設版面配置按鈕 */
    fun <T> enableDefaultLayout(cl: Class<T>) {
        enableShowCaseButton(binding.defaultLayoutButton, cl)
    }

    /** 啟用 Widget 列表按鈕 */
    fun <T> enableWidgetList(cl: Class<T>) {
        enableShowCaseButton(binding.widgetListButton, cl)
    }

    /** 啟用測試工具按鈕 */
    fun <T> enableTestingTools(cl: Class<T>) {
        enableShowCaseButton(binding.testingToolButton, cl)
    }

    /** 設定按鈕點擊跳轉到指定 Activity */
    private fun <T> enableShowCaseButton(view: View, cl: Class<T>) {
        view.isEnabled = true
        view.setOnClickListener {
            Intent(this, cl).also {
                startActivity(it)
            }
        }
    }

    /** 初始化 MQTT 連線（啟動連線、訂閱指令、開始狀態回報） */
    private fun initMqttConnection() {
        lifecycleScope.launch {
            MqttClientManager.connectionState.collectLatest { state ->
                LogUtils.i(tag, "MQTT Connection State: $state")
            }
        }
        observeCommands()
        MqttMissionBridge.init()
        MqttClientManager.connect()
        MqttStatusReporter.startReporting()
        MqttStatusReporter.startHeartbeat()
    }

    /** 初始化 FlightManager（啟動監聽、套用安全設定、訂閱遙測資料） */
    private fun initFlightManager() {
        FlightManager.startListening()
        FlightManager.applySafetyConfig()
        observeTelemetry()
        Log.i("DJIMainActivity", "FlightManager initialized — listening to telemetry")

        // Diagnostic: poll fetchCurrentState() every 5s, 5 times
        lifecycleScope.launch {
            repeat(5) { index ->
                delay(5000)
                FlightManager.fetchCurrentState()
                Log.d("DJIMainActivity", "Diagnostic poll ${index + 1}/5 completed")
            }
            Log.d("DJIMainActivity", "Diagnostic polling finished")
        }
    }

    /** 監聽遙測資料（高度、距離、電池、飛行狀態） */
    private fun observeTelemetry() {
        lifecycleScope.launch {
            FlightManager.altitude.collectLatest { alt ->
                LogUtils.i(tag, "Telemetry | Altitude: ${"%.1f".format(alt)}m")
            }
        }
        lifecycleScope.launch {
            FlightManager.distanceFromHome.collectLatest { dist ->
                LogUtils.i(tag, "Telemetry | Distance from home: ${"%.1f".format(dist)}m")
            }
        }
        lifecycleScope.launch {
            FlightManager.batteryPercent.collectLatest { pct ->
                LogUtils.i(tag, "Telemetry | Battery: $pct%")
            }
        }
        lifecycleScope.launch {
            FlightManager.isFlying.collectLatest { flying ->
                LogUtils.i(tag, "Telemetry | Is flying: $flying")
            }
        }
    }

    /** 監聽 MQTT 指令（用於 log 記錄） */
    private fun observeCommands() {
        lifecycleScope.launch {
            MqttClientManager.commandFlow.collectLatest { command ->
                LogUtils.i(tag, "MQTT Command received: $command")
            }
        }
    }

    /** 檢查並請求必要權限 */
    private fun checkPermissionAndRequest() {
        if (!checkPermission()) {
            requestPermission()
        }
    }

    /** 檢查所有必要權限是否已授予 */
    private fun checkPermission(): Boolean {
        for (i in permissionArray.indices) {
            if (!PermissionUtil.isPermissionGranted(this, permissionArray[i])) {
                return false
            }
        }
        return true
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        result?.entries?.forEach {
            if (!it.value) {
                requestPermission()
                return@forEach
            }
        }
    }

    /** 發起權限請求 */
    private fun requestPermission() {
        requestPermissionLauncher.launch(permissionArray.toArray(arrayOf()))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        disposable.dispose()
        MqttStatusReporter.stop()
        MqttClientManager.disconnect()
        FlightManager.stopListening()
    }
}