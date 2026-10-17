package dji.sampleV5.aircraft.views

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragMainTitleBinding
import dji.sampleV5.aircraft.pages.DJIFragment
import dji.v5.utils.common.LogUtils

/**
 * 顯示 MSDK 資訊（版本、裝置、網路等）與標題列的工具列 Fragment。
 *
 * @author Hoker
 * @date 2021/5/11
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
open class MSDKInfoFragment : DJIFragment() {

    private lateinit var msdkInfoTextMain: TextView
    private lateinit var msdkInfoTextSecond: TextView
    private lateinit var titleTextView: TextView
    private lateinit var returnBtn: ImageButton

    /** 建立並回傳 Fragment 的根視圖，並初始化畫面元件 */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.frag_main_title, container, false)
        initView(v)
        return v
    }

    fun initView(v: View) {
        msdkInfoTextMain = v.findViewById(R.id.msdk_info_text_main)
        msdkInfoTextSecond = v.findViewById(R.id.msdk_info_text_second)
        titleTextView = v.findViewById(R.id.title_text_view)
        returnBtn = v.findViewById(R.id.return_btn)
    }

    /** 視圖建立完成後初始化 MSDK 資訊訂閱與按鈕監聽 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initMSDKInfo()
        initListener()
    }

    private fun initMSDKInfo() {
        msdkInfoVm.msdkInfo.observe(viewLifecycleOwner) {
            it?.let {
                val mainInfo = "MSDK Info:[Ver:${it.SDKVersion} BuildVer:${it.buildVer} Debug:${it.isDebug} ProductCategory:${it.packageProductCategory} LDMLicenseLoaded:${it.isLDMLicenseLoaded} ]"
                msdkInfoTextMain.text = mainInfo
                val secondInfo = "Device:${it.productType} | Network:${it.networkInfo} | CountryCode:${it.countryCode} | FirmwareVer:${it.firmwareVer} | LDMEnabled:${it.isLDMEnabled}"
                msdkInfoTextSecond.text = secondInfo
            }
        }
        msdkInfoVm.refreshMSDKInfo()

        msdkInfoVm.mainTitle.observe(viewLifecycleOwner) {
            it?.let {
                titleTextView.text = it
            }
        }
    }

    private fun initListener() {
        returnBtn.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }
}