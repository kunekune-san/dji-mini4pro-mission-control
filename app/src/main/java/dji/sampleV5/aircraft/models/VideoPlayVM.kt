package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sdk.keyvalue.value.common.IntMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.VideoPlayStatus
import dji.v5.utils.common.LogUtils

/**
 * @author feel.feng
 * @time 2022/05/16 8:07 下午
 * @description: 影片回放 ViewModel。提供影片播放狀態監聽、暫停/繼續/停止與跳轉播放等功能。
 */
class VideoPlayVM : DJIViewModel(){
    val TAG: String = LogUtils.getTag(this)
    var videoPlayStatus =  MutableLiveData<VideoPlayStatus>()

    /** 加入影片播放狀態監聽器。 */
    fun addVideoPlayStateListener(){
        MediaDataCenter.getInstance().mediaManager.addVideoPlayStateListener {
            videoPlayStatus.postValue(it)
        }
    }

    /** 移除所有影片播放狀態監聽器，並重設播放狀態。 */
    fun removeAllListener(){
        videoPlayStatus.postValue(VideoPlayStatus.Builder().build())
        MediaDataCenter.getInstance().mediaManager.removeAllVideoPlayStateListener()
    }

    /** 跳轉到指定的播放時間點。 */
    fun seek(position: Int) {
        val seekTo = IntMsg()
        seekTo.value = position
        MediaDataCenter.getInstance().mediaManager.seekVideo(position  , object : CommonCallbacks.CompletionCallback{
            override fun onSuccess() {
                LogUtils.i(TAG, "seekVideo video success")
            }

            override fun onFailure(error: IDJIError) {
                LogUtils.e(TAG, "seekVideo video faild$error")
            }

        })
    }

     /** 暫停影片播放。 */
     fun pause(){
        MediaDataCenter.getInstance().mediaManager.pauseVideo(object :CommonCallbacks.CompletionCallback{
            override fun onSuccess() {
                LogUtils.i(TAG, "pause success")
            }

            override fun onFailure(error: IDJIError) {
                LogUtils.e(TAG, "pause video faild$error")
            }

        })
    }


     /** 繼續影片播放。 */
     fun resume(){
        MediaDataCenter.getInstance().mediaManager.resumeVideo(object :CommonCallbacks.CompletionCallback{
            override fun onSuccess() {
                LogUtils.i(TAG, "resume success")
            }

            override fun onFailure(error: IDJIError) {
                LogUtils.e(TAG, "resume video faild$error")
            }

        })
    }


     /** 停止影片播放。 */
     fun stop(){

        MediaDataCenter.getInstance().mediaManager.stopVideo(object :CommonCallbacks.CompletionCallback{
            override fun onSuccess() {
                LogUtils.i(TAG, "stop success");
            }

            override fun onFailure(error: IDJIError) {
                LogUtils.e(TAG, "stop video faild$error")
            }

        })
    }

    /** 將秒數轉換為 HH:mm:ss 格式的時間字串。 */
    fun showTime(time: Int): String? {
        var minute = time / 60
        val hour = minute / 60
        val second = time % 60
        minute %= 60
        return String.format("%02d:%02d:%02d", hour, minute, second)
    }
}