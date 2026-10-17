package dji.sampleV5.aircraft.keyvalue

/**
 * 針對支援 get 的 key 執行取得測試的指令類別。
 *
 * @author feel.feng
 * @time 2022/10/26 2:25 下午
 * @description: key 的 get 取得測試指令
 */
class KeyGetCommand(
    private val productType: String,
    private val componentTypeName: String,
    private val componentIndex: Int
) : KeyOperatorCommand(productType, componentTypeName, componentIndex) {

    private val TAG_GET = "【GET】"
    private val TAG_ERROR = "GetErrorMsg"

    override fun filter(item: KeyItem<*, *>): Boolean {
        return item.canGet()
    }

    override fun run(item: KeyItem<*, *>) {
        super.doKeyParam(item, KeyCheckType.GET)
    }

    override fun getTAG(): String {
        return TAG_GET
    }

    override fun getErrorTAG(): String {
        return TAG_ERROR
    }


}