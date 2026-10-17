package dji.sampleV5.aircraft.pages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.models.CameraStreamListVM
import dji.sdk.keyvalue.value.common.ComponentIndexType

/**
 * 相機串流列表頁面。
 *
 * 顯示目前可用的相機清單，並為每台相機建立獨立的
 * CameraStreamDetailFragment 預覽畫面。
 */
class CameraStreamListFragment : DJIFragment() {

    private lateinit var llCameraList: LinearLayout

    private val viewModule: CameraStreamListVM by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.frag_camera_stream_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        llCameraList = view.findViewById(R.id.ll_camera_preview_list)
        viewModule.availableCameraListData.observe(viewLifecycleOwner) { availableCameraList ->
            updateAvailableCamera(availableCameraList)
        }
    }

    /**
     * 依可用相機清單更新畫面：移除舊 Fragment，
     * 並為每台相機建立獨立的預覽 FrameLayout。
     */
    private fun updateAvailableCamera(availableCameraList: List<ComponentIndexType>) {
        if (availableCameraList.isEmpty()){
            return
        }
        var ft = childFragmentManager.beginTransaction()
        val fragmentList = childFragmentManager.fragments
        for (fragment in fragmentList) {
            ft.remove(fragment!!)
        }
        ft.commitAllowingStateLoss()
        llCameraList.removeAllViews()
        ft = childFragmentManager.beginTransaction()
        val onlyOneCamera = availableCameraList.size == 1
        for (cameraIndex in availableCameraList) {
            val frameLayout = FrameLayout(llCameraList.context)
            frameLayout.id = View.generateViewId()
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            llCameraList.addView(frameLayout, lp)
            ft.replace(frameLayout.id, CameraStreamDetailFragment.newInstance(cameraIndex, onlyOneCamera), cameraIndex.name)
        }
        ft.commitAllowingStateLoss()
    }
}