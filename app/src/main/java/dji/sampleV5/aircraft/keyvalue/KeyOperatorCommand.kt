package dji.sampleV5.aircraft.keyvalue

import dji.sdk.keyvalue.key.ComponentType
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.utils.MultiComponentManager
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.product.ProductType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.DJICommonError
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.capability.CapabilityManager
import dji.v5.manager.capability.CapabilityParser
import dji.v5.utils.common.DateUtils
import dji.v5.utils.common.FileUtils
import dji.v5.utils.common.LogUtils
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.CompletableEmitter
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.CountDownLatch

/**
 * key 的操作基類，定義依產品類型與元件類型執行 key 檢查的流程。
 *
 * @author feel.feng
 * @time 2022/10/26 11:18 上午
 * @description: key 的操作基類
 */
abstract class KeyOperatorCommand(
    private val productType: String,
    private val componentTypeName: String,
    private val componentIndex: Int
) {

    private val INTERVAL_TIME = 500L // 連續快速呼叫 key 時可能會導致失敗
    private val TAG_EQUAL = "=="
    private val TAG = LogUtils.getTag(this)
    private lateinit var competableEmitter: CompletableEmitter
    private var unPassedCount = 0;
    private lateinit var curCheckType: KeyCheckType
    private var keyCount = 0

    private var whiteList = mutableListOf<String>(
        "GimbalCalibrationStatus",
        "IsShootingPhotoPanorama",
        "PhotoPanoramaMode",
        "PhotoPanoramaProgress",
        "ThermalContrast",
        "ThermalDDE",
        "ThermalRegionMetersureTemperature",
        "ThermalBrightness",
        "ThermalGainModeTemperatureRange",
        "AircraftLocation3D",
        "PhotoRatio"
    )

    /**
     * 過濾 Key 類型條件
     */
    abstract fun filter(item: KeyItem<*, *>): Boolean

    /**
     * 指定動作類型
     */
    abstract fun run(item: KeyItem<*, *>)

    /**
     * 寫檔案需要
     */
    abstract fun getTAG(): String

    /**
     * Key 執行結果回呼 TAG，用來判斷 key 執行錯誤或失敗
     */
    abstract fun getErrorTAG(): String

    open fun getIntervalTime(): Long {
        return INTERVAL_TIME
    }

    /**
     * 執行 key 檢查流程，依序過濾並執行所有符合條件的 key
     */
    fun execute(): Completable {

        return Completable.create { emitter ->
            competableEmitter = emitter;
            unPassedCount = 0
            val allList: MutableList<KeyItem<*, *>> = ArrayList()
            KeyItemDataUtil.getAllKeyList(allList)
            val capabilityKeyCount =
                CapabilityManager.getInstance().getCapabilityKeyCount(productType)

            LogUtils.i(TAG, "begin check $capabilityKeyCount")
            saveResult(" ----- begin ${getTAG()} check -----\n\n", false, false)
            saveResult(" ----- begin ${getTAG()} check -----\n\n", true, false)

            allList.filter { item ->
                filter(item) && (item.toString() !in whiteList) && CapabilityManager.getInstance().isKeySupported(
                    productType, componentTypeName, ComponentType.find(item.getKeyInfo().componentType), "Key$item"
                )
            }.forEach { item ->
                LogUtils.e(TAG, "${++keyCount} doCheck $item ")
                LogUtils.e(TAG, "Thread name is 1 " + Thread.currentThread().name)
                val lock = CountDownLatch(1)
                item.componetIndex = if (MultiComponentManager.isMultiKey(item.keyInfo.componentType)) {
                    componentIndex
                } else {
                    0
                }
                dependKeySet(item, object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        // 從主執行緒再切回 io 執行緒
                        LogUtils.e(TAG, "Thread name is 2 " + Thread.currentThread().name)
                        lock.countDown()

                    }

                    override fun onFailure(error: IDJIError) {
                        LogUtils.e(TAG, "Set $item depend key failed!")
                        lock.countDown()
                    }
                })

                lock.await()
                Thread.sleep(getIntervalTime()) //  設定完後，立即設定可能會異常，例如 FrequencyBand
                run(item)
            }
            Thread.sleep(getIntervalTime())
            saveResult(" --------finish  ${getTAG()}---------\n", true, true)
            saveResult(" --------finish  ${getTAG()}---------\n", false, true)
            // 遍歷完成即完成
            competableEmitter.onComplete()
        }.subscribeOn(Schedulers.io())
    }

    /**
     * 執行依賴 key 的 Set 方法
     */
    private fun dependKeySet(item: KeyItem<*, *>, callback: CommonCallbacks.CompletionCallback) {
        // 首先取得具體的依賴 keyitem
        val keyName = item.toString()
        val valueBean = CapabilityParser.getInstance().getValueBean(keyName)
        val dependKeyItem = valueBean?.dependKeyName?.let { CapabilityKeyChecker.getKeyItem(it) }
        LogUtils.e(TAG, "dependKeyItem key : " + (valueBean?.dependKeyName ?: "null"))

        if (dependKeyItem != null) {
            dependKeyItem.setKeyOperateCallBack { res ->
                if (res.toString().contains("SetErrorMsg")) {
                    callback.onFailure(DJICommonError.FACTORY.build(res.toString()))
                } else {
                    callback.onSuccess()
                }
            }
            dependKeyItem.setComponetIndex(item.getComponetIndex())
            dependKeyItem.setSubComponetType(item.getSubComponetType())
            dependKeyItem.setSubComponetIndex(item.getSubComponetIndex())
            dependKeyItem.doSet(valueBean.dependKeyValue.replace("\\", ""))
        } else {
            // 沒有找到前置條件，返回成功。
            callback.onSuccess()
        }
    }

    /**
     * 依指定檢查類型（GET／SET／ACTION）執行單一 key 的參數檢查
     */
    fun doKeyParam(item: KeyItem<*, *>, type: KeyCheckType) {
        curCheckType = type
        getItemDecoderList(item).forEach {
            val lock = CountDownLatch(1)
            item.setKeyOperateCallBack {
                var result = StringBuilder()
                val resStr = it.toString()
                val keyNameIndex = resStr.indexOf(getTAG())
                if (keyNameIndex <= -1 ) return@setKeyOperateCallBack
                val keyName = resStr.substring(0, keyNameIndex)
                val isPassed: Boolean
                val failedReson = if (resStr.contains(getErrorTAG())) {
                    isPassed = false
                    resStr.substring(resStr.indexOf(TAG_EQUAL))
                } else {
                    isPassed = true
                    resStr.substring(resStr.indexOf(TAG_EQUAL))
                }
                var componentTYpe = ComponentType.find(item.getKeyInfo().componentType)

                result.append("${++unPassedCount} KeyName :${keyName} - ${componentTYpe}\n")
                    .append("SubType:${getLensName(item)}\n")
                    .append("Details:${failedReson}\n")
                    .append("\n ----------------------- \n")

                saveResult(result.toString(), isPassed, true)
                LogUtils.e(TAG, "SubType:${getLensName(item)} KeyName :${keyName}} ComponentType : $componentTYpe " + resStr)
                lock.countDown()
            }
            item.setComponetIndex(it.componetIndex)
            item.setSubComponetType(it.subComponetType)
            item.setSubComponetIndex(it.subComponetIndex)
            when (type) {
                KeyCheckType.ACTION -> item.doAction(it.jsonString)
                KeyCheckType.SET -> item.doSet(it.jsonString)
                KeyCheckType.GET -> item.doGet()
            }
            lock.await()
            Thread.sleep(getIntervalTime())
        }

        LogUtils.e(TAG, "check finish!")
    }

    private fun getLensName(keyItem: KeyItem<*, *>): String {
        return if (keyItem.keyInfo.componentType == ComponentType.CAMERA.value()) {
            CameraLensType.find(keyItem.getSubComponetType()).name
        } else {
            "DEFAULT"
        }
    }

    private fun saveResult(content: String, saveType: Boolean, append: Boolean) {
        val product = ProductKey.KeyProductType.create().get(ProductType.UNRECOGNIZED)
        var filePath = LogUtils.getLogPath() + getTAG() + product.name + "【${DateUtils.getSystemTimeOnlyYMD()}】"

        filePath += if (saveType) {
            "Success.txt"
        } else {
            "Failed.txt"
        }
        FileUtils.writeFile(filePath, content, append)
    }

    /**
     * 透過 key 名稱從能力集中取得 keyItem 需要的參數，包括 lenstype、用例 json
     * 如果在能力集中沒有找到用例，需要返回一個預設的（無參數的 action 返回空）
     */
    private fun getItemDecoderList(keyItem: KeyItem<*, *>): MutableList<CapabilityKeyChecker.ItemDecoder> {
        val resList: MutableList<CapabilityKeyChecker.ItemDecoder> = ArrayList()
        // 透過 item 取得支援的 lensType 列表
        var lensTypeList = if (keyItem.keyInfo.componentType == ComponentType.CAMERA.value()) {
            CapabilityManager.getInstance().getSupportLens("Key$keyItem", productType, componentTypeName)
        } else {
            arrayListOf("DEFAULT")
        }
        // 取得 javaBean 字元列表；用例中沒有檔案則返回空集合
        var keyParamList = CapabilityKeyChecker.getKeyParamList(keyItem.toString())

        // 用例檔案存在（set 類型都會有）；action 不在用例檔案中則不自動測試，需要人為測試；支援 set、get
        if (keyParamList.isNotEmpty() || keyItem.canGet()) {
            lensTypeList.map {
                transCameraLensTypeStr(it)
            }.forEach { subComponentType ->
                val index = if (MultiComponentManager.isMultiKey(keyItem.keyInfo.componentType)) {
                    componentIndex
                } else {
                    0
                }
                if (curCheckType == KeyCheckType.SET || curCheckType == KeyCheckType.ACTION) {
                    keyParamList
                        .forEach {
                            resList.add(
                                CapabilityKeyChecker.ItemDecoder(
                                    componetIndex = index,
                                    subComponetType = subComponentType,
                                    jsonString = it
                                )
                            )
                        }
                } else if (curCheckType == KeyCheckType.GET) {
                    resList.add(
                        CapabilityKeyChecker.ItemDecoder(
                            componetIndex = index,
                            subComponetType = subComponentType,
                            jsonString = ""
                        )
                    )
                }
            }
        } else {
            // 添加預設，確保每個 key 都可以執行到 set 方法；action 無參數不執行（在用例中未找到）
        }
        return resList
    }

    /**
     * 將能力集中 CameraLensType 字串轉為對應的 value
     */
    fun transCameraLensTypeStr(lensName: String): Int {
        CameraLensType.values().forEach {
            if (it.name.contains(lensName)) {
                return it.value()
            }
        }
        return CameraLensType.UNKNOWN.value()
    }
}