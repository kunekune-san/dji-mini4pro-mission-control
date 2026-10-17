package dji.sampleV5.aircraft.models

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.v5.common.video.channel.VideoChannelState
import dji.v5.common.video.channel.VideoChannelType
import dji.v5.common.video.interfaces.IVideoChannel
import dji.v5.common.video.interfaces.VideoChannelStateChangeListener
import dji.v5.common.video.stream.StreamSource
import dji.v5.et.cancelListen
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.video.StreamSourceListener
import java.util.concurrent.CopyOnWriteArrayList

/** 多視訊頻道 ViewModel。管理主/副頻道的 StreamSource 分配、頻道開關狀態監聽與頻道重設。 */
class MultiVideoChannelVM : DJIViewModel() {
    val videoStreamSources = MutableLiveData<CopyOnWriteArrayList<StreamSource>>()
    val availableVideoStreamSources = MutableLiveData<CopyOnWriteArrayList<StreamSource>>()
    val handler = Handler(Looper.getMainLooper())
    val DELAY_TIME = 4000L
    var primaryChannel = MediaDataCenter.getInstance().videoStreamManager.getAvailableVideoChannel(
        VideoChannelType.PRIMARY_STREAM_CHANNEL
    )
    var secondaryChannel =
        MediaDataCenter.getInstance().videoStreamManager.getAvailableVideoChannel(
            VideoChannelType.SECONDARY_STREAM_CHANNEL
        )
    var curPrimarySource: StreamSource? = null
    var curSecondarySource: StreamSource? = null
    val primaryChannelStateListener = object : VideoChannelStateChangeListener {
        override fun onUpdate(from: VideoChannelState?, to: VideoChannelState?) {
            if (VideoChannelState.ON == to) {
                primaryChannel?.let { it ->
                    it.streamSource?.let {
                        removeStreamSource(it)
                        curPrimarySource = it
                    }
                }
            }
            if (VideoChannelState.CLOSE == to) {
                primaryChannel?.let { _ ->
                    curPrimarySource?.let { it1 -> addStreamSource(it1) }
                }
            }
            updateVideoChannel()
        }
    }
    val secondaryChannelStateListener = object : VideoChannelStateChangeListener {
        override fun onUpdate(from: VideoChannelState?, to: VideoChannelState?) {
            if (VideoChannelState.ON == to) {
                secondaryChannel?.let { it ->
                    it.streamSource?.let {
                        removeStreamSource(it)
                        curSecondarySource = it
                    }
                }
            }
            if (VideoChannelState.CLOSE == to) {
                //在這裡 close 的時候 streamSource 會被清空，在 VM 中可以先暫存
                secondaryChannel?.let { _ ->
                    curSecondarySource?.let { it1 -> addStreamSource(it1) }
                }
            }
            updateVideoChannel()
        }
    }

    private val streamSourcesListener = StreamSourceListener {
        it?.let {
            availableVideoStreamSources.value = CopyOnWriteArrayList(it.toMutableList())
            updateVideoChannel()
        }
    }

    /** 註冊串流來源清單的監聽。 */
    fun initMultiVideoChannels() {
        MediaDataCenter.getInstance().videoStreamManager.addStreamSourcesListener(
            streamSourcesListener
        )
    }

    /** 移除串流來源清單的監聽。 */
    fun removeStreamSourceListener() {
        MediaDataCenter.getInstance().videoStreamManager.removeStreamSourcesListener(
            streamSourcesListener
        )
    }

    /** 重設所有視訊頻道。 */
    fun resetMultiVideoChannels() {
        if (MediaDataCenter.getInstance().videoStreamManager != null) {
            MediaDataCenter.getInstance().videoStreamManager.resetAllVideoChannels()
        }
    }

    /** 取得所有可用的視訊頻道。 */
    fun getAllVideoChannels(): List<IVideoChannel>? {
        return MediaDataCenter.getInstance().videoStreamManager?.let {
            MediaDataCenter.getInstance().videoStreamManager.availableVideoChannels
        } ?: let {
            null
        }
    }

    /**
     * 監聽 channel 的開關狀態，即時更新可用 streamSources；已被開啟的 channel 所使用的 source 需要從 videoStreamSources 移除，被關閉的需要添加。
     */
    fun initChannelStateListener() {
        primaryChannel?.addVideoChannelStateChangeListener(primaryChannelStateListener)
        secondaryChannel?.addVideoChannelStateChangeListener(secondaryChannelStateListener)
    }

    /** 移除頻道開關狀態的監聽。 */
    fun removeChannelStateListener() {
        primaryChannel?.removeVideoChannelStateChangeListener(primaryChannelStateListener)
        secondaryChannel?.removeVideoChannelStateChangeListener(secondaryChannelStateListener)
    }

    /** 從串流來源清單中移除指定的 StreamSource。 */
    fun removeStreamSource(source: StreamSource) {
        val targetSource = videoStreamSources.value!!.find { it.streamId == source.streamId }
        targetSource?.let {
            videoStreamSources.value!!.remove(it)
        }
        videoStreamSources.postValue(videoStreamSources.value)
    }

    /** 將指定的 StreamSource 加入串流來源清單（重複項目會忽略）。 */
    fun addStreamSource(source: StreamSource) {
        for (i in 0 until videoStreamSources.value!!.size) {
            if (videoStreamSources.value!![i].streamId == source.streamId) {
                return
            }
        }
        videoStreamSources.value!!.add(source)
        videoStreamSources.postValue(videoStreamSources.value)
    }

    /** 註冊飛行器連線狀態監聽，連線時重設頻道與串流來源。 */
    fun addConnectionListener() {
        FlightControllerKey.KeyConnection.create().listen(this) {
            it?.let {
                if (it) {
                    reset()
                }
            }
        }
    }

    /** 移除飛行器連線狀態監聽。 */
    fun removeConnectionListener() {
        FlightControllerKey.KeyConnection.create().cancelListen(this)
    }

    private fun reset() {
        primaryChannel = MediaDataCenter.getInstance().videoStreamManager.getAvailableVideoChannel(
            VideoChannelType.PRIMARY_STREAM_CHANNEL
        )
        secondaryChannel =
            MediaDataCenter.getInstance().videoStreamManager.getAvailableVideoChannel(
                VideoChannelType.SECONDARY_STREAM_CHANNEL
            )
        removeChannelStateListener()
        initChannelStateListener()
        removeStreamSourceListener()
        initMultiVideoChannels()
        videoStreamSources.postValue(videoStreamSources.value)
    }

    private fun updateVideoChannel() {
        handler.post {
            availableVideoStreamSources.value?.let { availableVideoStreamSourceList ->
                videoStreamSources.value = CopyOnWriteArrayList(availableVideoStreamSourceList)
                val allChannels = getAllVideoChannels()
                allChannels?.let {
                    it.forEach {
                        if (it.videoChannelStatus == VideoChannelState.ON) {
                            /**
                             * 更換相機時重新組織 videoStreamSources，去除掉 channel 已經開啟的部分
                             */
                            videoStreamSources.value!!.forEach { v ->
                                run {
                                    if (v.streamId == it?.streamSource?.streamId) {
                                        videoStreamSources.value!!.remove(v)
                                    }
                                }
                            }
                        }
                    }
                }
                videoStreamSources.postValue(videoStreamSources.value)
            }
        }
        //更換相機時觸發自動分配 StreamSource，需要給一定的 delayTime
    }
}