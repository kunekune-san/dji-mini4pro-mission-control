package dji.sampleV5.aircraft.models

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import dji.v5.network.DJINetworkManager

/** MSDK 初始化管理 ViewModel。負責初始化與銷毀 Mobile SDK，並以 LiveData 回報註冊狀態、產品連線與初始化進度。 */
class MSDKManagerVM : ViewModel() {
    // 資料以 LiveData 方式保存，你也可以用任何自己喜歡的方式保存 SDK 回呼的結果。
    val lvRegisterState = MutableLiveData<Pair<Boolean, IDJIError?>>()
    val lvProductConnectionState = MutableLiveData<Pair<Boolean, Int>>()
    val lvProductChanges = MutableLiveData<Int>()
    val lvInitProcess = MutableLiveData<Pair<DJISDKInitEvent, Int>>()
    val lvDBDownloadProgress = MutableLiveData<Pair<Long, Long>>()
    var isInit = false

    /** 初始化 Mobile SDK，並設定 SDK 回呼與網路狀態監聽。 */
    fun initMobileSDK(appContext: Context) {
        // 初始化並設定 SDK 回呼，SDK 內部會持有此回呼直到呼叫 destroy()
        SDKManager.getInstance().init(appContext, object : SDKManagerCallback {
            override fun onRegisterSuccess() {
                lvRegisterState.postValue(Pair(true, null))
            }

            override fun onRegisterFailure(error: IDJIError) {
                lvRegisterState.postValue(Pair(false, error))
            }

            override fun onProductDisconnect(productId: Int) {
                lvProductConnectionState.postValue(Pair(false, productId))
            }

            override fun onProductConnect(productId: Int) {
                lvProductConnectionState.postValue(Pair(true, productId))
            }

            override fun onProductChanged(productId: Int) {
                lvProductChanges.postValue(productId)
            }

            override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
                lvInitProcess.postValue(Pair(event, totalProcess))
                // 別忘了呼叫 registerApp()
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    isInit = true
                    SDKManager.getInstance().registerApp()
                }
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                lvDBDownloadProgress.postValue(Pair(current, total))
            }
        })

        DJINetworkManager.getInstance().addNetworkStatusListener { isAvailable ->
            if (isInit && isAvailable && !SDKManager.getInstance().isRegistered) {
                SDKManager.getInstance().registerApp()
            }
        }
    }

    /** 銷毀 Mobile SDK，釋放相關資源。 */
    fun destroyMobileSDK() {
        SDKManager.getInstance().destroy()
    }

}