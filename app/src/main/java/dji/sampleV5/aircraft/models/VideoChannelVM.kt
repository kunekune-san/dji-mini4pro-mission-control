package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.data.VideoChannelInfo
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.v5.common.video.channel.VideoChannelState
import dji.v5.common.video.channel.VideoChannelType
import dji.v5.common.video.interfaces.IVideoChannel
import dji.v5.common.video.interfaces.VideoChannelStateChangeListener
import dji.v5.et.action
import dji.v5.et.cancelListen
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.datacenter.MediaDataCenter

/** 單一視訊頻道 ViewModel。管理指定頻道的狀態、串流來源與連線狀態監聽，並在飛機重啟時更新頻道。 */
class VideoChannelVM(channelType: VideoChannelType) : DJIViewModel() {
    val videoChannelInfo = MutableLiveData<VideoChannelInfo>()
    var videoChannel: IVideoChannel? = null
    var videoChannelStateListener: VideoChannelStateChangeListener? = null
    var curChannelType: VideoChannelType? = null
    var fcHasInit = false
    init {
        MediaDataCenter.getInstance().videoStreamManager.getAvailableVideoChannel(channelType)
            ?.let {
                videoChannel = it
                curChannelType = channelType
                if (videoChannelInfo.value == null) {
                    videoChannelInfo.value = VideoChannelInfo(it.videoChannelStatus)
                }
                videoChannelInfo.value?.streamSource = videoChannel!!.streamSource
                videoChannelInfo.value?.videoChannelType = videoChannel!!.videoChannelType
                videoChannelInfo.value?.format = videoChannel!!.videoStreamFormat.name
                videoChannelStateListener = VideoChannelStateChangeListener { _, to ->
                    /**
                     * 碼流通道切換事件回呼方法
                     *
                     * @param from 碼流通道前一個狀態
                     * @param to 碼流通道目前狀態
                     */
                    /**
                     * 碼流通道切換事件回呼方法
                     *
                     * @param from 碼流通道前一個狀態
                     * @param to 碼流通道目前狀態
                     */
                    if (videoChannelInfo.value == null) {
                        videoChannelInfo.value = VideoChannelInfo(to)
                    } else {
                        videoChannelInfo.value?.videoChannelState = to
                    }
                    videoChannelInfo.value?.format = videoChannel!!.videoStreamFormat.name
                    if (to == VideoChannelState.ON || to == VideoChannelState.SOCKET_ON) {
                        videoChannelInfo.value?.streamSource = videoChannel!!.streamSource
                    } else {
                        videoChannelInfo.value?.streamSource = null
                    }
                    refreshVideoChannelInfo()
                }
                initListeners()
            }

        addConnectionListener()
        //防止進入圖傳介面的時候還處於回放狀態
        CameraKey.KeyExitPlayback.create(0).action()
    }

    override fun onCleared() {
        removeListeners()
        removeConnectionListener()
    }

    private fun initListeners() {
        videoChannel?.let {
            it.addVideoChannelStateChangeListener(videoChannelStateListener)
        }
    }

    private fun removeListeners() {
        videoChannel?.let {
            it.removeVideoChannelStateChangeListener(videoChannelStateListener)
        }
    }

    private fun addConnectionListener() {
        FlightControllerKey.KeyConnection.create().listen(this) {
            it?.let {
                //飛機重啟的時候更新一下所持有的 channel
                if (it and fcHasInit) {
                    curChannelType?.let { it1 ->
                        MediaDataCenter.getInstance().videoStreamManager.getAvailableVideoChannel(
                            it1
                        ).let {
                            videoChannel = it
                        }
                    }
                    removeListeners()
                    initListeners()
                }
                fcHasInit = true
            }
        }
    }

    private fun removeConnectionListener(){
        FlightControllerKey.KeyConnection.create().cancelListen(this)
    }

    /** 將目前的頻道資訊重新發佈到 LiveData。 */
    fun refreshVideoChannelInfo() {
        videoChannelInfo.postValue(videoChannelInfo.value)
    }
}