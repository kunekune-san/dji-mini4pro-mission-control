package dji.sampleV5.aircraft.data

/**
 * 頁面項目清單資料類別，包含導覽圖資源 ID 與頁面項目集合
 */
data class FragmentPageItemList(
    val vavGraphId: Int = DEFAULT_RES_ID,
    val items: LinkedHashSet<FragmentPageItem> = LinkedHashSet()
)

/**
 * 單一頁面項目資料類別，包含 ID、標題與描述等資源 ID
 */
data class FragmentPageItem(
    val id: Int = DEFAULT_RES_ID,
    val title: Int = DEFAULT_RES_ID,
    val description: Int = DEFAULT_RES_ID,
    val isStrike: Boolean = false
)