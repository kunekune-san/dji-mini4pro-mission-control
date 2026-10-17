package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.data.DJIToastResult
import dji.upgrade.UpgradeError
import dji.v5.common.callback.CommonCallbacks

import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.upgrade.UpgradeInfo

import dji.v5.manager.aircraft.upgrade.UpgradeManager
import dji.v5.manager.aircraft.upgrade.UpgradeableComponent
import dji.v5.manager.aircraft.upgrade.UpgradeableComponentListener
import dji.v5.manager.aircraft.upgrade.model.ComponentType
import dji.v5.utils.common.LogUtils
import dji.v5.manager.aircraft.upgrade.UpgradeProgressState

/**
 * @author feel.feng
 * @time 2022/01/26 10:56 上午
 * @description: 韌體升級 ViewModel。提供可升級元件檢查、離線升級啟動與升級資訊監聽等功能。
 */
class UpgradeVM : DJIViewModel() {

    var upgradeStateInfo = MutableLiveData(UpgradeInfo(0, UpgradeProgressState.INITIALIZING, UpgradeError.NO_ERROR))

    /** 初始化並加入可升級元件監聽器。 */
    fun addUpgradeableComponentListener(listener: UpgradeableComponentListener) {
        UpgradeManager.getInstance().init()
        UpgradeManager.getInstance().addUpgradeableComponentListener(listener)
    }

    /** 檢查目前有哪些元件可以升級。 */
    fun checkUpgradeableComponents(callback: CommonCallbacks.CompletionCallbackWithParam<ComponentType>) {
        UpgradeManager.getInstance().checkUpgradeableComponents(object :
            CommonCallbacks.CompletionCallbackWithParam<ComponentType> {

            override fun onFailure(error: IDJIError) {
                callback.onFailure(error)
                toastResult?.postValue(DJIToastResult.failed("fetch error $error"))
            }

            override fun onSuccess(type: ComponentType) {

                callback.onSuccess(type)
                toastResult?.postValue(DJIToastResult.failed("$type fetch success"))
            }
        })
    }

    fun getUpgradeableComponents(): List<UpgradeableComponent> {
        return UpgradeManager.getInstance().upgradeableComponents
    }

    /** 加入升級進度資訊監聽器。 */
    fun addUpgradeInfoListener() {
        UpgradeManager.getInstance().addUpgradeInfoListener {
            upgradeStateInfo.postValue(it)
        }
    }

    /** 使用離線升級包對指定元件開始升級。 */
    fun startOfflineUpgrade(type: ComponentType, offlineFilePath: String) {
        UpgradeManager.getInstance().startOfflineUpgrade(type, offlineFilePath, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {

                toastResult?.postValue(DJIToastResult.success("start upgrade"))
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
                LogUtils.e(logTag, "upgrade error${error.description()}")
            }

        })
    }

    override fun onCleared() {
        super.onCleared()
        UpgradeManager.getInstance().removeAllUpgradeInfoListener()
    }
}