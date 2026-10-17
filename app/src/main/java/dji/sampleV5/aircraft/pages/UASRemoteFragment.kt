package dji.sampleV5.aircraft.pages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.Navigation
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.databinding.FragUasPageBinding
import androidx.navigation.findNavController

/**
 * Description :無人機遠端識別的示範入口頁面
 *
 * @author: Byte.Cai
 *  date : 2022/6/26
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class UASRemoteFragment : DJIFragment() {
    private var binding: FragUasPageBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragUasPageBinding.inflate(inflater, container, false)
        return binding?.root
    }

    /**
     * 初始化畫面並設定各國 UAS 遠端識別頁面的導覽按鈕。
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.btnFrance?.setOnClickListener {
            Navigation.findNavController(it).navigate(R.id.action_open_uas_france_page)
        }
        binding?.btnJp?.setOnClickListener {
            Navigation.findNavController(it).navigate(R.id.action_open_uas_jp_pag)
        }
        binding?.btnUs?.setOnClickListener {
            Navigation.findNavController(it).navigate(R.id.action_open_uas_us_page)
        }
        binding?.btnEu?.setOnClickListener {
            Navigation.findNavController(it).navigate(R.id.action_open_uas_european_page)
        }
        binding?.btnChina?.setOnClickListener {
            Navigation.findNavController(it).navigate(R.id.action_open_uas_chin_page)
        }
        binding?.btnSingapore?.setOnClickListener {
            Navigation.findNavController(it).navigate(R.id.action_open_uas_singapore_page)
        }

        binding?.btnUae?.setOnClickListener {
            Navigation.findNavController(it).navigate(R.id.action_open_uas_uae_page)
        }
    }
}