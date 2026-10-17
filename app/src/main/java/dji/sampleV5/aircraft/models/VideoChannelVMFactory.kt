package dji.sampleV5.aircraft.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dji.v5.common.video.channel.VideoChannelType

/** VideoChannelVM 的工廠類別。負責依指定的頻道類型建立 VideoChannelVM 實例。 */
class VideoChannelVMFactory (channelType: VideoChannelType): ViewModelProvider.Factory {
    private var videoChannelType: VideoChannelType = channelType

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(p0: Class<T>): T {
        return VideoChannelVM(videoChannelType) as T
    }
}