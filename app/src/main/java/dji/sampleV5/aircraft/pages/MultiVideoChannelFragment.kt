package dji.sampleV5.aircraft.pages

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.activityViewModels
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.models.MultiVideoChannelVM
import dji.sampleV5.aircraft.util.ToastUtils


/**
 * 多路視訊頻道管理頁面，提供多路視訊頻道的重設與查詢功能。
 */
class MultiVideoChannelFragment : DJIFragment(), View.OnClickListener {
    private val multiVideoChannelVM: MultiVideoChannelVM by activityViewModels()
    private lateinit var primaryFragmentView: FragmentContainerView
    private lateinit var secondaryFragmentView: FragmentContainerView
    private lateinit var fabResetAllVideoChannel: FloatingActionButton
    private lateinit var fabGetAllVideoChannel: FloatingActionButton
    private var isFABOpen = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.frag_multi_video_channel_page, container, false)
        primaryFragmentView = view.findViewById(R.id.primary_video_channel_fragment)
        secondaryFragmentView = view.findViewById(R.id.secondary_video_channel_fragment)
        return view
    }

    @SuppressLint("RestrictedApi")
    private fun showFABMenu() {
        isFABOpen = true
        fabResetAllVideoChannel.visibility = View.VISIBLE
        fabGetAllVideoChannel.visibility = View.VISIBLE
    }

    @SuppressLint("RestrictedApi")
    private fun closeFABMenu() {
        isFABOpen = false
        fabResetAllVideoChannel.visibility = View.GONE
        fabGetAllVideoChannel.visibility = View.GONE
    }

    /** 畫面建立後初始化多路視訊頻道與各種狀態監聽器。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        multiVideoChannelVM.initMultiVideoChannels()
        multiVideoChannelVM.initChannelStateListener()
        multiVideoChannelVM.addConnectionListener()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        multiVideoChannelVM.removeStreamSourceListener()
        multiVideoChannelVM.removeChannelStateListener()
        multiVideoChannelVM.removeConnectionListener()
    }


    /**
     * 當某個 View 被點擊時的回呼。
     *
     * @param v 被點擊的 View。
     */
    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.fab -> {
                if (!isFABOpen) {
                    showFABMenu()
                } else {
                    closeFABMenu()
                }
            }
            R.id.fab_reset_all_video_channel -> {
                multiVideoChannelVM.resetMultiVideoChannels()
                this@MultiVideoChannelFragment.childFragmentManager.setFragmentResult(
                    "ResetAllVideoChannel",
                    bundleOf("ResetAllVideoChannel" to true)
                )
                ToastUtils.showToast(
                    "Reset All Video Channel Success"
                )
            }
            R.id.fab_get_all_channels -> {
                multiVideoChannelVM.getAllVideoChannels()?.let {
                    ToastUtils.showToast(
                        multiVideoChannelVM.getAllVideoChannels().toString() + "\n"
                    + multiVideoChannelVM.availableVideoStreamSources.value
                    )
                } ?: let {
                    ToastUtils.showToast(
                        "There is no available channel"
                    )
                }
            }
        }
    }
}