package dji.sampleV5.aircraft.data

import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.v5.manager.aircraft.lte.LTEPrivatizationServerInfo
import dji.v5.manager.areacode.AreaCode
import dji.v5.utils.common.ContextUtil
import dji.v5.utils.common.DjiSharedPreferencesManager
import dji.v5.utils.common.JsonUtil

/**
 * 快速測試設定物件，提供 LTE 認證資訊與模擬器區域清單的快取讀寫
 */
object QuickTestConfig {

    private const val KEY_LTE_AUTHENTICATION_INFO = "key_lte_authentication_info"
    private const val KEY_AC_LTE_PRIVATIZATION_SERVER_INFO = "key_ac_lte_privatization_server_info"
    private const val KEY_RC_LTE_PRIVATIZATION_SERVER_INFO = "key_rc_lte_privatization_server_info"

    val simulatorAreaList = listOf(
        SimulatorArea("中国", LocationCoordinate2D(22.5797650, 113.9411710), AreaCode.CHINA),
        SimulatorArea("美国", LocationCoordinate2D(34.063191, -118.121621), AreaCode.UNITED_STATES_OF_AMERICA),
        SimulatorArea("日本", LocationCoordinate2D(35.658890, 139.746074), AreaCode.JAPAN),
        SimulatorArea("法国", LocationCoordinate2D(48.860284, 2.336282), AreaCode.FRANCE),
        SimulatorArea("德国", LocationCoordinate2D(52.516294, 13.376631), AreaCode.GERMANY),
        SimulatorArea("禁飞区", LocationCoordinate2D(22.645945, 113.816311), AreaCode.CHINA),
        SimulatorArea("授权区", LocationCoordinate2D(22.395237, 114.203203), AreaCode.CHINA),
        SimulatorArea("加强警告区", LocationCoordinate2D(22.208262, 114.03056), AreaCode.CHINA),
    )

    /**
     * 取得 LTE 認證資訊快取
     */
    fun getCacheLTEAuthenticationInfo(): LTEAuthCacheInfo? {
        val str = DjiSharedPreferencesManager.getString(ContextUtil.getContext(), KEY_LTE_AUTHENTICATION_INFO, "")
        if (str.isEmpty()) {
            return LTEAuthCacheInfo()
        }
        return JsonUtil.toBean(DjiSharedPreferencesManager.getString(ContextUtil.getContext(), KEY_LTE_AUTHENTICATION_INFO, ""), LTEAuthCacheInfo::class.java)
    }

    /**
     * 更新 LTE 認證資訊快取
     */
    fun updateCacheLTEAuthenticationInfo(info: LTEAuthCacheInfo) {
        DjiSharedPreferencesManager.putString(ContextUtil.getContext(), KEY_LTE_AUTHENTICATION_INFO, JsonUtil.toJson(info))
    }

    /**
     * 取得 AC 端 LTE 私有化伺服器資訊快取
     */
    fun getCacheACLTEPrivatizationServerInfo(): LTEPrivatizationServerInfo? {
        val str = DjiSharedPreferencesManager.getString(ContextUtil.getContext(), KEY_AC_LTE_PRIVATIZATION_SERVER_INFO, "")
        if (str.isEmpty()) {
            return LTEPrivatizationServerInfo()
        }
        return JsonUtil.toBean(DjiSharedPreferencesManager.getString(ContextUtil.getContext(), KEY_AC_LTE_PRIVATIZATION_SERVER_INFO, ""), LTEPrivatizationServerInfo::class.java)
    }

    /**
     * 更新 AC 端 LTE 私有化伺服器資訊快取
     */
    fun updateCacheACLTEPrivatizationServerInfo(info: LTEPrivatizationServerInfo) {
        DjiSharedPreferencesManager.putString(ContextUtil.getContext(), KEY_AC_LTE_PRIVATIZATION_SERVER_INFO, JsonUtil.toJson(info))
    }

    /**
     * 取得 RC 端 LTE 私有化伺服器資訊快取
     */
    fun getCacheRCLTEPrivatizationServerInfo(): LTEPrivatizationServerInfo? {
        val str = DjiSharedPreferencesManager.getString(ContextUtil.getContext(), KEY_RC_LTE_PRIVATIZATION_SERVER_INFO, "")
        if (str.isEmpty()) {
            return LTEPrivatizationServerInfo()
        }
        return JsonUtil.toBean(DjiSharedPreferencesManager.getString(ContextUtil.getContext(), KEY_RC_LTE_PRIVATIZATION_SERVER_INFO, ""), LTEPrivatizationServerInfo::class.java)
    }

    /**
     * 更新 RC 端 LTE 私有化伺服器資訊快取
     */
    fun updateCacheRCLTEPrivatizationServerInfo(info: LTEPrivatizationServerInfo) {
        DjiSharedPreferencesManager.putString(ContextUtil.getContext(), KEY_RC_LTE_PRIVATIZATION_SERVER_INFO, JsonUtil.toJson(info))
    }

    /**
     * 模擬器區域資料類別，包含區域名稱、座標與區域碼
     */
    data class SimulatorArea(
        val name: String, val location: LocationCoordinate2D, val areaCode: AreaCode
    ) {
        override fun toString(): String {
            return name
        }
    }

    /**
     * LTE 認證快取資訊資料類別
     */
    data class LTEAuthCacheInfo(
        val phoneAreaCode: String = "86",
        val phoneNumber: String = "1234567890",
        val verificationCode: String = "123456"
    )
}