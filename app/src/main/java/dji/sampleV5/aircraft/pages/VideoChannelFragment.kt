package dji.sampleV5.aircraft.pages

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.*
import android.view.*
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.ViewModelProvider
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.data.DEFAULT_STR
import dji.sampleV5.aircraft.databinding.FragAppSilentlyUpgradePageBinding
import dji.sampleV5.aircraft.databinding.VideoChannelPageBinding
import dji.sampleV5.aircraft.models.MultiVideoChannelVM
import dji.sampleV5.aircraft.models.VideoChannelVM
import dji.sampleV5.aircraft.models.VideoChannelVMFactory
import dji.sampleV5.aircraft.util.ToastUtils
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.video.channel.VideoChannelState
import dji.v5.common.video.channel.VideoChannelType
import dji.v5.common.video.decoder.*
import dji.v5.common.video.interfaces.*
import dji.v5.common.video.stream.StreamSource
import dji.v5.utils.common.*
import java.io.*

/**
 * 影片頻道示範頁面：支援開啟/關閉碼流頻道、Socket 連線、廣播，以及 YUV 畫面截圖功能。
 * （已棄用，請改用 CameraStreamListFragment。）
 */
@Deprecated(message = "Replace With CameraStreamListFragment")
class VideoChannelFragment : DJIFragment(), View.OnClickListener, SurfaceHolder.Callback,
    YuvDataListener {
    private val TAG = LogUtils.getTag("VideoChannelFragment")
    private val PATH: String = "/DJI_ScreenShot"
    private val multiVideoChannelVM: MultiVideoChannelVM by activityViewModels()
    private var binding: VideoChannelPageBinding? = null
    private lateinit var channelVM: VideoChannelVM
    private lateinit var surfaceView: SurfaceView
    private lateinit var dialog: AlertDialog
    private var streamSources: List<StreamSource>? = null
    private var videoDecoder: IVideoDecoder? = null

    private var videoWidth: Int = -1
    private var videoHeight: Int = -1
    private var widthChanged = false
    private var heightChanged = false
    private var fps: Int = -1

    private var checkedItem: Int = -1
    private var count: Int = 0
    private var stringBuilder: StringBuilder? = StringBuilder()
    private val DISPLAY = 100
    private val mHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg?.what) {
                DISPLAY -> {
                    display(msg?.obj.toString())
                }
            }
        }
    }

    // 組幀後數據 Listener
    private val streamDataListener =
        StreamDataListener {
            /**
             * 碼流數據回呼方法
             *
             * @param VideoFrame 碼流幀數據
             */
            it.let {
                if (fps != it.fps) {
                    fps = it.fps
                    mainHandler.post {
                        channelVM.videoChannelInfo.value?.fps = fps
                        channelVM.refreshVideoChannelInfo()
                    }
                }
                if (videoWidth != it.width) {
                    videoWidth = it.width
                    widthChanged = true
                }
                if (videoHeight != it.height) {
                    videoHeight = it.height
                    heightChanged = true
                }
                if (widthChanged || heightChanged) {
                    widthChanged = false
                    heightChanged = false
                    mainHandler.post {
                        channelVM.videoChannelInfo.value?.resolution =
                            "${videoWidth}*${videoHeight}"
                        channelVM.refreshVideoChannelInfo()
                    }
                }
            }
        }

    private val decoderStateChangeListener =
        DecoderStateChangeListener { _, newState ->
            mainHandler.post {
                channelVM.videoChannelInfo.value?.decoderState = newState
                channelVM.refreshVideoChannelInfo()
            }

        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        binding = VideoChannelPageBinding.inflate(inflater, container, false)
        binding?.root?.let {
            surfaceView = it.findViewById(R.id.surface_view)
            surfaceView.holder.addCallback(this)
            it.findViewById<Button>(R.id.startChannel).setOnClickListener(this)
            it.findViewById<Button>(R.id.closeChannel).setOnClickListener(this)
            it.findViewById<Button>(R.id.yuvScreenShot).setOnClickListener(this)
            it.findViewById<Button>(R.id.startSocket).setOnClickListener(this)
            it.findViewById<Button>(R.id.closeSocket).setOnClickListener(this)
            it.findViewById<Button>(R.id.startBroadcast).setOnClickListener(this)
            it.findViewById<Button>(R.id.stopBroadcast).setOnClickListener(this)
        }
        return binding?.root
    }

    /**
     * 建立視圖後依 Fragment tag 決定影片頻道類型，並初始化對應的 ViewModel。
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val videoChannelType: VideoChannelType = when (tag) {
            VideoChannelType.SECONDARY_STREAM_CHANNEL.name -> VideoChannelType.SECONDARY_STREAM_CHANNEL
            VideoChannelType.EXTENDED_STREAM_CHANNEL.name -> VideoChannelType.EXTENDED_STREAM_CHANNEL
            else -> VideoChannelType.PRIMARY_STREAM_CHANNEL
        }
        val factory = VideoChannelVMFactory(videoChannelType)
        channelVM = ViewModelProvider(this, factory).get(VideoChannelVM::class.java)
        init()
    }

    private fun init() {
        multiVideoChannelVM.videoStreamSources.observe(viewLifecycleOwner) {
            streamSources = it
        }
        initVideoChannelInfo()
    }

    private fun initVideoChannelInfo() {
        streamSources = multiVideoChannelVM.videoStreamSources.value
        channelVM.videoChannelInfo.observe(viewLifecycleOwner) {
            it?.let {
                val videoStreamInfo =
                    "\n StreamSource: [${it.streamSource?.physicalDeviceCategory} : ${it.streamSource?.physicalDeviceType?.deviceType} : ${it.streamSource?.physicalDevicePosition}] \n " +
                            "ChannelType: [${it.videoChannelType.name}] State: [${it.videoChannelState}] \n " +
                            "DecoderState: [${it.decoderState}] Resolution: [${it.resolution}] \n " +
                            "FPS: [${it.fps}] Format: [${it.format}] BitRate: [${it.bitRate} Kb/s] \n " +
                            "Socket: [${it.socket}]"
                binding?.videoStreamInfo?.text = videoStreamInfo
            }
        }
        channelVM.videoChannel?.addStreamDataListener(streamDataListener) ?: showDisconnectToast()
        this@VideoChannelFragment.setFragmentResultListener("ResetAllVideoChannel") { requestKey, _ ->
            if ("ResetAllVideoChannel" == requestKey) {
                mainHandler.post {
                    channelVM.videoChannelInfo.value?.streamSource = null
                    channelVM.videoChannelInfo.value?.videoChannelState = VideoChannelState.CLOSE
                    channelVM.refreshVideoChannelInfo()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        channelVM.videoChannel?.removeStreamDataListener(streamDataListener)
        if (videoDecoder != null) {
            videoDecoder?.destroy()
            videoDecoder = null
        }
    }

    /**
     * 當任一視圖被點擊時的回呼，處理開始/關閉頻道與 YUV 截圖等操作。
     *
     * @param v 被點擊的視圖。
     */
    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.startChannel -> {
                channelVM.videoChannel?.let {
                    if (channelVM.videoChannel!!.videoChannelStatus == VideoChannelState.CLOSE) {
                        showStartChannelDialog()
                    }
                } ?: showDisconnectToast()
            }

            R.id.closeChannel -> {
                channelVM.videoChannel?.let {
                    channelVM.videoChannel!!.closeChannel(object :
                        CommonCallbacks.CompletionCallback {
                        /**
                         * 當非同步操作成功完成時呼叫。可覆寫以處理成功結果。
                         */
                        override fun onSuccess() {
                            mainHandler.post {
                                channelVM.videoChannelInfo.value?.videoChannelState =
                                    VideoChannelState.CLOSE
                                channelVM.videoChannelInfo.value?.socket = DEFAULT_STR
                                channelVM.refreshVideoChannelInfo()
                            }
                            ToastUtils.showToast("Close Channel Success")

                        }

                        /**
                         * 當非同步操作完成時呼叫。若操作成功完成，`error` 會是 `null`。可覆寫以處理失敗結果。
                         *
                         * @param error DJI 錯誤結果。
                         */
                        override fun onFailure(error: IDJIError) {
                            ToastUtils.showToast(
                                "Close Channel Failed: (errorCode ${error.errorCode()}, description: ${error.description()})"
                            )

                        }
                    })
                } ?: showDisconnectToast()
            }

            R.id.yuvScreenShot -> {
                channelVM.videoChannel?.let {
                    handlerYUV()
                } ?: showDisconnectToast()
            }
        }
    }

    private fun showStartChannelDialog() {
        val length = streamSources?.size
        if (length == 0) {
            return
        }
        val items = length?.let { arrayOfNulls<String>(it) }
        streamSources?.let {
            for (i in 0 until length!!) {
                items?.set(i, streamSources!![i].toString())
            }
            if (!items.isNullOrEmpty()) {
                dialog = this@VideoChannelFragment.requireContext().let { context ->
                    AlertDialog.Builder(context, androidx.appcompat.R.style.Base_ThemeOverlay_AppCompat_Dialog_Alert)
                        .setIcon(android.R.drawable.ic_menu_camera)
                        .setTitle(R.string.select_stream_source)
                        .setCancelable(false)
                        .setSingleChoiceItems(
                            items, checkedItem
                        ) { _, i ->
                            checkedItem = i
                            ToastUtils.showToast(
                                "选择的码流源为： " + (items[i] ?: "空"),
                            )
                        }.setPositiveButton(R.string.confirm) { dialog, _ ->
                            run {
                                val streamSource = streamSources?.getOrNull(checkedItem)
                                streamSource?.let { startChannel(it) }
                                dialog.dismiss()
                            }
                        }
                        .setNegativeButton(R.string.cancel) { dialog, _ ->
                            run {
                                dialog.dismiss()
                            }
                        }
                        .create()
                }
                dialog.show()
            }
        }
    }


    /**
     * 使用指定的串流來源開啟影片頻道。
     */
    private fun startChannel(streamSource: StreamSource) {
        channelVM.videoChannel?.let {
            channelVM.videoChannel!!.startChannel(
                streamSource,
                object : CommonCallbacks.CompletionCallback {
                    /**
                     * 當非同步操作成功完成時呼叫。可覆寫以處理成功結果。
                     */
                    override fun onSuccess() {
                        mainHandler.post {
                            channelVM.videoChannelInfo.value?.streamSource =
                                streamSource
                            channelVM.refreshVideoChannelInfo()
                        }

                        ToastUtils.showToast("Start Channel Success")

                    }

                    /**
                     * 當非同步操作完成時呼叫。若操作成功完成，`error` 會是 `null`。可覆寫以處理失敗結果。
                     *
                     * @param error DJI 錯誤結果。
                     */
                    override fun onFailure(error: IDJIError) {
                        ToastUtils.showToast(
                            "Start Channel Failed: (errorCode ${error.errorCode()}, description: ${error.description()})"
                        )
                    }
                })
        } ?: showDisconnectToast()
    }

    /**
     * 當 Surface 首次建立後立刻被呼叫。實作此方法應啟動所需的渲染程式碼。
     * 注意：任何時刻只有一個執行緒可以繪製到 [Surface]，若你的正常渲染在另一個執行緒，
     * 則不應在此處繪製 Surface。
     *
     * @param holder 正被建立的 SurfaceHolder。
     */
    override fun surfaceCreated(holder: SurfaceHolder) {
        // 不做任何事
    }

    /**
     * 當 Surface 發生結構性變更（格式或尺寸）後立刻被呼叫。此時應更新 Surface 中的影像。
     * 此方法在 [.surfaceCreated] 之後至少會被呼叫一次。
     *
     * @param holder 已變更的 SurfaceHolder。
     * @param format Surface 新的 PixelFormat。
     * @param width Surface 新的寬度。
     * @param height Surface 新的高度。
     */
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (videoDecoder == null) {
            channelVM.videoChannel?.let {
                videoDecoder = VideoDecoder(
                    this@VideoChannelFragment.context,
                    channelVM.videoChannel!!.videoChannelType,
                    DecoderOutputMode.SURFACE_MODE,
                    surfaceView.holder,
                    width,
                    height
                )
                videoDecoder?.addDecoderStateChangeListener(decoderStateChangeListener)
                decoderStateChangeListener.onUpdate(videoDecoder?.decoderStatus,videoDecoder?.decoderStatus)
            }
        }
    }

    /**
     * 當 Surface 即將被銷毀前立刻被呼叫。從此回呼返回後，不應再嘗試存取此 Surface。
     * 若有渲染執行緒直接存取 Surface，必須確保在返回前該執行緒已不再觸碰 Surface。
     *
     * @param holder 正被銷毀的 SurfaceHolder。
     */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        videoDecoder?.destroy()
    }

    /**
     * 接收 YUV 幀數據，每 30 幀將畫面資料儲存為 .yuv 檔並轉存為 JPEG。
     */
    override fun onReceive(mediaFormat: MediaFormat?, data: ByteArray?, width: Int, height: Int) {
        if (++count == 30) {
            count = 0
            data?.let {
                AsyncTask.execute(object : Runnable {
                    override fun run() {
                        var path: String =
                            DiskUtil.getExternalCacheDirPath(ContextUtil.getContext(), PATH)
                        val dir = File(path)
                        if (!dir.exists() || !dir.isDirectory) {
                            dir.mkdirs()
                        }
                        path = path + "/YUV_" + System.currentTimeMillis() + ".yuv"
                        var fos: FileOutputStream? = null
                        try {
                            val file = File(path)
                            if (file.exists()) {
                                file.delete()
                            }
                            fos = FileOutputStream(file)
                            fos.write(it, 0, it.size)
                        } catch (e: Exception) {
                            LogUtils.e(TAG, e.message)
                        } finally {
                            fos?.let {
                                fos.flush()
                                fos.close()
                            }
                        }
                        saveYuvData(mediaFormat, data, width, height)
                    }
                })
            }
        }
    }

    private fun saveYuvData(mediaFormat: MediaFormat?, data: ByteArray?, width: Int, height: Int) {
        data?.let {
            mediaFormat?.let {
                when (it.getInteger(MediaFormat.KEY_COLOR_FORMAT)) {
                    0, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> {
                        newSaveYuvDataToJPEG(data, width, height)
                    }

                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> {
                        newSaveYuvDataToJPEG420P(data, width, height)
                    }
                }
            }
        }
    }

    /**
     * 切換 YUV 截圖模式：暫停/重建解碼器並掛載或移除 YUV 數據監聽。
     */
    private fun handlerYUV() {
        if (binding?.horizontalScrollView?.yuvScreenShot?.isSelected == false) {
            binding?.horizontalScrollView?.yuvScreenShot?.setText(R.string.btn_resume_video)
            binding?.horizontalScrollView?.yuvScreenShot?.isSelected = true
            binding?.yuvScreenSavePath?.text = ""
            binding?.yuvScreenSavePath?.visibility = View.VISIBLE
            videoDecoder?.let {
                videoDecoder!!.onPause()
                videoDecoder!!.destroy()
                videoDecoder = null
            }
            videoDecoder = VideoDecoder(
                this@VideoChannelFragment.context,
                channelVM.videoChannel!!.videoChannelType
            )
            videoDecoder?.addDecoderStateChangeListener(decoderStateChangeListener)
            decoderStateChangeListener.onUpdate(videoDecoder?.decoderStatus,videoDecoder?.decoderStatus)
            videoDecoder?.addYuvDataListener(this)
            videoDecoder?.onResume()
        } else {
            stringBuilder?.let {
                stringBuilder!!.clear()
            }

            binding?.horizontalScrollView?.yuvScreenShot?.setText(R.string.btn_yuv_screen_shot)
            binding?.horizontalScrollView?.yuvScreenShot?.isSelected = false
            binding?.yuvScreenSavePath?.setText("")
            binding?.yuvScreenSavePath?.visibility = View.INVISIBLE
            videoDecoder?.let {
                videoDecoder!!.onPause()
                videoDecoder!!.destroy()
                videoDecoder = null
            }
            videoDecoder = VideoDecoder(
                this@VideoChannelFragment.context,
                channelVM.videoChannel!!.videoChannelType,
                DecoderOutputMode.SURFACE_MODE,
                surfaceView.holder,
                surfaceView.width,
                surfaceView.height
            )
            videoDecoder?.addDecoderStateChangeListener(decoderStateChangeListener)
            decoderStateChangeListener.onUpdate(videoDecoder?.decoderStatus,videoDecoder?.decoderStatus)
            videoDecoder?.removeYuvDataListener(this)
            videoDecoder?.onResume()

        }
    }

    private fun newSaveYuvDataToJPEG420P(yuvFrame: ByteArray, width: Int, height: Int) {
        if (yuvFrame.size < width * height) {
            return
        }
        val length = width * height
        val u = ByteArray(width * height / 4)
        val v = ByteArray(width * height / 4)
        for (i in u.indices) {
            u[i] = yuvFrame[length + i]
            v[i] = yuvFrame[length + u.size + i]
        }
        for (i in u.indices) {
            yuvFrame[length + 2 * i] = v[i]
            yuvFrame[length + 2 * i + 1] = u[i]
        }
        screenShot(
            yuvFrame,
            DiskUtil.getExternalCacheDirPath(ContextUtil.getContext(), PATH),
            width,
            height
        )
    }

    private fun newSaveYuvDataToJPEG(yuvFrame: ByteArray, width: Int, height: Int) {
        if (yuvFrame.size < width * height) {
            return
        }
        val length = width * height
        val u = ByteArray(width * height / 4)
        val v = ByteArray(width * height / 4)
        for (i in u.indices) {
            v[i] = yuvFrame[length + 2 * i]
            u[i] = yuvFrame[length + 2 * i + 1]
        }
        for (i in u.indices) {
            yuvFrame[length + 2 * i] = u[i]
            yuvFrame[length + 2 * i + 1] = v[i]
        }
        screenShot(
            yuvFrame,
            DiskUtil.getExternalCacheDirPath(ContextUtil.getContext(), PATH),
            width,
            height
        )
    }

    private fun screenShot(buf: ByteArray, shotDir: String, width: Int, height: Int) {
        val dir = File(shotDir)
        if (!dir.exists() || !dir.isDirectory) {
            dir.mkdirs()
        }
        val yuvImage = YuvImage(
            buf,
            ImageFormat.NV21,
            width,
            height,
            null
        )
        val outputFile: OutputStream
        val path = dir.toString() + "/ScreenShot_" + System.currentTimeMillis() + ".jpeg"
        outputFile = try {
            FileOutputStream(File(path))
        } catch (e: FileNotFoundException) {
            LogUtils.e(LogPath.SAMPLE, "screenShot: new bitmap output file error: $e")
            return
        }
        yuvImage.compressToJpeg(
            Rect(
                0,
                0,
                width,
                height
            ), 100, outputFile
        )
        try {
            outputFile.close()
        } catch (e: IOException) {
            LogUtils.e(LogPath.SAMPLE, "test screenShot: compress yuv image error: ${e.message}")
        }
        Message.obtain(mHandler, DISPLAY, path).sendToTarget()
    }

    private fun display(path: String) {
        stringBuilder?.let {
            it.insert(0, path)
            it.insert(0, "\n")
            binding?.yuvScreenSavePath?.text = it
        }
    }

    private fun showDisconnectToast() {
        ToastUtils.showShortToast("video stream disconnect")
    }
}