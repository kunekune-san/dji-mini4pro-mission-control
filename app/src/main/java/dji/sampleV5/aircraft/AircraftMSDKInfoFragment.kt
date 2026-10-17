package dji.sampleV5.aircraft

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dji.sampleV5.aircraft.views.MSDKInfoFragment

/**
 * 無人機的 MSDK 資訊 Fragment。
 * 載入飛機主標題版面，並初始化 MSDK 資訊顯示。
 *
 * @author Hoker
 * @date 2022/12/16
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class AircraftMSDKInfoFragment : MSDKInfoFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.frag_aircraft_main_title, container, false)
        initView(v)
        return v
    }
}