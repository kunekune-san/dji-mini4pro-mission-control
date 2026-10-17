package dji.sampleV5.aircraft.pages

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.data.DJIRTKBaseStationConnectInfo
import dji.sampleV5.aircraft.data.RtkStationScanAdapter
import dji.sampleV5.aircraft.databinding.FragStationRtkPageBinding
import dji.sampleV5.aircraft.models.RTKStationVM
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.rtkbasestation.RTKBaseStationResetPasswordInfo
import dji.sdk.keyvalue.value.rtkbasestation.RTKStationConnetState
import dji.sdk.keyvalue.value.rtkbasestation.RTKStationInfo
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.utils.GpsUtils
import dji.v5.manager.aircraft.rtk.station.ConnectedRTKStationInfo
import dji.v5.utils.common.LogUtils
import dji.v5.utils.common.StringUtils
import dji.v5.ux.core.extension.hide
import dji.v5.ux.core.extension.show
import kotlin.math.ceil

/**
 * Description :基站 RTK 操作介面
 *
 * @author: Byte.Cai
 *  date : 2022/3/4
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class RTKStationFragment : DJIFragment(), RtkStationScanAdapter.OnItemClickListener {
    private val stationList = ArrayList<DJIRTKBaseStationConnectInfo>()

    //這裡只能用 fragment 級別的 viewModel，否則會出現黏性事件
    private val rtkStationVM: RTKStationVM by viewModels()
    private var binding: FragStationRtkPageBinding? = null
    private lateinit var rtkStationScanAdapter: RtkStationScanAdapter
    private var loginStatus = false
    private var connectState = RTKStationConnetState.UNKNOWN
    private var rtkLocation: LocationCoordinate3D = LocationCoordinate3D()

    companion object {
        private const val STATION_PASSWORD_LENGTH = 6//密碼長度必須為 6
        private const val TAG = "StationRTKFragment"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragStationRtkPageBinding.inflate(inflater, container, false)
        return binding?.root
    }

    /** 畫面建立後初始化視圖與監聽器。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initListener()
    }

    private fun initView() {
        LogUtils.i(TAG, "initView")
        //初始化 station RTK 列表
        val layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        binding?.rvStationList?.layoutManager = layoutManager
        rtkStationScanAdapter = RtkStationScanAdapter(requireContext(), stationList)
        binding?.rvStationList?.adapter = rtkStationScanAdapter

        rtkStationVM.stationListLD.observe(viewLifecycleOwner) { result ->
            handleStationRTKList(result)
        }


        rtkStationVM.appStationConnectStatusLD.observe(viewLifecycleOwner) { result ->
            handleConnectStatus(result)
        }

        rtkStationVM.appStationConnectedInfoLD.observe(viewLifecycleOwner) { result ->
            showConnectStationInfo(result)
            handleReconnectedStationInfo(result)
        }
        rtkStationVM.rtkLocationLD.observe(viewLifecycleOwner) { result ->
            result.rtkLocation?.apply {
                binding?.tvStationLocationInfo?.text = baseStationLocation.toString()
                rtkLocation = baseStationLocation
            }

        }
        rtkStationVM.stationPositionLD.observe(viewLifecycleOwner) {
            ToastUtils.showToast(it.toString())
        }

        //清除資料
        showConnectStationInfo(ConnectedRTKStationInfo())
    }

    override fun onResume() {
        loginStatus = false
        super.onResume()
    }


    private fun initListener() {
        //一進入介面就開始監聽基站 RTK 連接情況
        rtkStationVM.addSearchRTKStationListener()
        rtkStationVM.addStationConnectStatusListener()
        rtkStationVM.addConnectedRTKStationInfoListener()
        rtkStationVM.addRTKLocationListener()
        rtkStationScanAdapter.setOnItemClickListener(this)

        binding?.btnStartSearchStation?.setOnClickListener {
            //初始化 UI
            handleStationRTKList(null)
            binding?.tvStationListTip?.hide()
            rtkStationVM.startSearchStation()
        }

        binding?.btnStopSearchStation?.setOnClickListener {
            rtkStationVM.stopSearchStation()
        }

        binding?.btnLogin?.setOnClickListener {
            //登入操作只允許在基站連接之後才可以進行
            if (connectState != RTKStationConnetState.CONNECTED) {
                ToastUtils.showToast("Please connect to the base station first！")
                return@setOnClickListener
            }
            showDialog(StringUtils.getResStr(R.string.tip_input_station_password)) {
                it?.run {
                    val password = trim()
                    if (!TextUtils.isEmpty(password) && password.length == STATION_PASSWORD_LENGTH) {
                        rtkStationVM.loginAsAdmin(password, object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {
                                loginStatus = true
                                ToastUtils.showToast("Login Success")
                            }

                            override fun onFailure(error: IDJIError) {
                                ToastUtils.showToast(error.toString())
                            }

                        })
                    } else {
                        ToastUtils.showToast("The password length does not meet the requirements, the password length must be 6！！！")
                    }
                }
            }

        }
        binding?.btnSetStationPosition?.setOnClickListener {
            process {
                rtkLocation.run {
                    val locationCoordinate3D = LocationCoordinate3D(latitude, longitude, altitude)
                    showDialog(StringUtils.getResStr(R.string.tip_input_station_location), locationCoordinate3D.toString()) {
                        it?.let {
                            setRTKStationPosition(it)
                        }
                    }
                }

            }
        }
        binding?.btnGetStationPosition?.setOnClickListener {
            rtkStationVM.getRTKStationPosition()
        }
        binding?.btnResetStationPosition?.setOnClickListener {
            process {
                rtkStationVM.resetRTKStationPosition()
            }
        }
        binding?.btnResetStationPassword?.setOnClickListener {
            process {
                val rtkBaseStationResetPasswordInfo =
                    RTKBaseStationResetPasswordInfo("111111", "111111")
                showDialog(StringUtils.getResStr(R.string.tip_reset_station_password), rtkBaseStationResetPasswordInfo.toString()) {
                    it?.let {
                        val resetPasswordInfo = it.trim()
                        val fromJson = RTKBaseStationResetPasswordInfo.fromJson(resetPasswordInfo)
                        if (fromJson == null) {
                            showToJsonErrorMsg()
                            return@let
                        }
                        rtkStationVM.resetStationPassword(RTKBaseStationResetPasswordInfo.fromJson(resetPasswordInfo))
                    }

                }
            }
        }
        binding?.btnSetStationName?.setOnClickListener {
            process {
                showDialog(StringUtils.getResStr(R.string.tip_change_station_name)) {
                    it?.let {
                        val stationName = it.trim()
                        rtkStationVM.setStationName(stationName)
                    }
                }
            }
        }
    }

    private fun checkNeedUpdateUI(list: List<RTKStationInfo>?): Boolean {
        if (list?.size != stationList.size) {
            return true
        }
        for (i in stationList.indices) {
            if (stationList[i].toString() != list[i].toString()) {
                return true
            }
        }
        return false
    }

    private fun handleStationRTKList(list: List<DJIRTKBaseStationConnectInfo>?) {
        //過濾重複的資料，防止介面重新刷新
        if (checkNeedUpdateUI(list)) {
            stationList.clear()
            LogUtils.i(TAG, "clear stationList")
            list?.let {
                for (i in it) {
                    LogUtils.i(TAG, "stationName=${i.rtkStationName}+,signalLevel=${i.signalLevel}")
                    stationList.add(i)
                    binding?.tvStationListTip?.show()
                }
            }
            rtkStationScanAdapter.notifyDataSetChanged()
        }
    }

    /**
     * 選中某個基站，注意這裡 selectedRTKStationConnectInfo 不能初始化為 null
     */
    private var selectedRTKStationConnectInfo: DJIRTKBaseStationConnectInfo = DJIRTKBaseStationConnectInfo()
    override fun onItemClick(view: View?, position: Int) {
        selectedRTKStationConnectInfo = stationList[position]
        //連接某一個基站時，將其他基站的連接狀態重設為空閒，因為一次只能連接一個基站
        for (stationInfo in stationList) {
            stationInfo.connectStatus = RTKStationConnetState.IDLE
        }
        selectedRTKStationConnectInfo.run {
            rtkStationVM.startConnectToRTKStation(baseStationId)
            selectedRTKStationConnectInfo.refresh(RTKStationConnetState.CONNECTING)
            LogUtils.i(TAG, "click and connecting rtk")
        }

    }


    private inline fun process(block: () -> Unit) {
        if (loginStatus) {
            block()
        } else {
            ToastUtils.showToast("You need to login in before operation！！！")
        }
    }

    private fun DJIRTKBaseStationConnectInfo.refresh(connectState: RTKStationConnetState?) {
        connectState?.let {
            LogUtils.i(dji.sampleV5.aircraft.pages.RTKStationFragment.TAG, "connectState=$connectState")
            this.connectStatus = it
        }
        rtkStationScanAdapter.notifyDataSetChanged()

    }

    private fun handleConnectStatus(rtkBaseStationConnectState: RTKStationConnetState?) {
        if (rtkBaseStationConnectState == null) {
            return
        }
        LogUtils.i(TAG, rtkBaseStationConnectState.name)
        when (rtkBaseStationConnectState) {
            RTKStationConnetState.CONNECTED -> {
                ToastUtils.showToast("Station has connected")
            }
            RTKStationConnetState.DISCONNECTED -> {
                ToastUtils.showToast("Station has disconnected")
            }
            else -> {
                LogUtils.i(TAG, "Current station status is $rtkBaseStationConnectState")
            }
        }
        connectState = rtkBaseStationConnectState
        //回傳連接狀態，更新 UI
        selectedRTKStationConnectInfo.refresh(rtkBaseStationConnectState)

    }


    private var lastStationName = ""
    private fun showConnectStationInfo(infoConnected: ConnectedRTKStationInfo?) {
        infoConnected?.run {
            binding?.tvStationNameInfo?.text = stationName
            binding?.tvStationIdInfo?.text = "$stationId"
            binding?.tvStationSignalLevelInfo?.text = "$signalLevel"
            binding?.tvStationBatteryCurrentInfo?.text = "$batteryCurrent"
            binding?.tvStationBatteryVoltageInfo?.text = "$batteryVoltage"
            binding?.tvStationBatteryTemperatureInfo?.text = "$batteryTemperature"
            binding?.tvStationBatteryCapacityPercentInfo?.text = "$batteryCapacityPercent"

            //初始化名字
            if (TextUtils.isEmpty(lastStationName)) {
                lastStationName = stationName
                //這裡加上非空判斷，是因為斷開連接後 stationName 也為空，排除這種情況
            } else if (lastStationName != stationName && !TextUtils.isEmpty(stationName)) {
                //修改名字後刷新 UI
                selectedRTKStationConnectInfo.rtkStationName = stationName
                lastStationName = stationName
                rtkStationScanAdapter.notifyDataSetChanged()

            }
        }
    }


    private fun handleReconnectedStationInfo(infoConnected: ConnectedRTKStationInfo?) {
        infoConnected?.run {
            //第一次連接過基站後，再次重啟飛機或重啟 App 韌體會幫忙自動連接基站。這裡就是為了建立自動重連的基站資訊
            if (selectedRTKStationConnectInfo.baseStationId == 0) {
                LogUtils.i(TAG, "RTK Station has reconnected")
                selectedRTKStationConnectInfo = DJIRTKBaseStationConnectInfo(stationId, signalLevel, stationName, RTKStationConnetState.CONNECTED)
                handleStationRTKList(arrayListOf(selectedRTKStationConnectInfo))
                connectState = RTKStationConnetState.CONNECTED;
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        selectedRTKStationConnectInfo = DJIRTKBaseStationConnectInfo()
        rtkStationVM.clearAllStationConnectStatusListener()
        rtkStationVM.clearAllSearchRTKStationListener()
        rtkStationVM.clearAllConnectedRTKStationInfoListener()
    }



    private fun showToJsonErrorMsg() {
        ToastUtils.showToast("数据解析失败，请重试")
    }

    private fun setRTKStationPosition(input: String) {
        val inputLocation = LocationCoordinate3D.fromJson(input)
        if (inputLocation == null) {
            showToJsonErrorMsg()
            return
        }
        val distance = ceil(
            GpsUtils.gps2m(
                rtkLocation.latitude,
                rtkLocation.longitude,
                rtkLocation.altitude,
                inputLocation.latitude,
                inputLocation.longitude,
                inputLocation.altitude
            )
        ).toInt()
        ToastUtils.showToast(StringUtils.getResStr(R.string.tv_station_relative_distance, distance))
        rtkStationVM.setRTKStationPosition(inputLocation)
    }


}