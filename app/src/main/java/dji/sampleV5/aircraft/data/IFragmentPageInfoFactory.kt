package dji.sampleV5.aircraft.data

/**
 * 功能清單頁面資訊工廠介面，負責建立各頁面的 FragmentPageItemList
 *
 * @author Hoker
 * @date 2021/5/7
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
interface IFragmentPageInfoFactory {
    /**
     * 建立頁面的 FragmentPageItemList
     */
    fun createPageInfo(): FragmentPageItemList
}