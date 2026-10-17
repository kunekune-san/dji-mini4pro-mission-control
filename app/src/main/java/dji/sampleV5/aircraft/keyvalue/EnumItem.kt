package dji.sampleV5.aircraft.keyvalue

import java.io.Serializable

/**
 * 參數條目的選項資料類別，封裝顯示描述、SDK 對應列舉名稱與選中狀態。
 *
 * @author feel.feng
 * @time 2022/03/10 9:58 上午
 * @description: 參數條目的選項資料類別
 */
class EnumItem : Serializable {
    /**
     * 需要顯示的條目描述
     */
    private var description: String? = null

    /**
     * SDK 對應的列舉
     */
    private var name: String? = null

    /**
     * 是否選中
     */
    private var selected = false
    fun getName(): String? {
        return name
    }

    fun setName(name: String?) {
        this.name = name
    }

    fun getDescription(): String? {
        return description
    }

    fun setDescription(description: String?) {
        this.description = description
    }

    fun isSelected(): Boolean {
        return selected
    }

    fun setSelected(selected: Boolean) {
        this.selected = selected
    }

    companion object {
        private const val serialVersionUID = 876323262645176354L
    }
}