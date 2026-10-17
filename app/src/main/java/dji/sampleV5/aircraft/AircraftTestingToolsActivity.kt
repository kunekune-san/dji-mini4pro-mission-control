package dji.sampleV5.aircraft

import androidx.fragment.app.commit
import dji.sampleV5.aircraft.data.AircraftFragmentPageInfoFactory
import dji.sampleV5.aircraft.data.CommonFragmentPageInfoFactory
import dji.sampleV5.aircraft.data.FragmentPageItemList

/**
 * 無人機的測試工具集合 Activity。
 * 載入共通與飛機專屬的功能頁面清單，並顯示 MSDK 資訊標題。
 *
 * @author Hoker
 * @date 2021/3/9
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
class AircraftTestingToolsActivity : TestingToolsActivity() {

    override fun loadPages() {
        msdkCommonOperateVm.apply {
            val itemList = LinkedHashSet<FragmentPageItemList>().also {
                it.add(CommonFragmentPageInfoFactory().createPageInfo())
                it.add(AircraftFragmentPageInfoFactory().createPageInfo())
            }
            loaderItem(itemList)
        }
    }

    override fun loadTitleView() {
        supportFragmentManager.commit {
            replace(R.id.main_info_fragment_container, AircraftMSDKInfoFragment())
        }
    }
}