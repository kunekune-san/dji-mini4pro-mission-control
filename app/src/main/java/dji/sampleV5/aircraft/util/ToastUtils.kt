package dji.sampleV5.aircraft.util

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dji.v5.utils.common.ContextUtil
import java.lang.ref.WeakReference

/** Toast 工具類（自動取消前一個 Toast 避免堆疊） */
object ToastUtils {
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var toastRef: WeakReference<Toast>? = null

    /** 顯示 Toast（預設長時間） */
    fun showToast(msg: String) {
        showLongToast(msg)
    }

    /** 顯示長時間 Toast */
    fun showLongToast(msg: String) {
        showToast(msg, Toast.LENGTH_LONG)
    }

    /** 顯示短時間 Toast */
    fun showShortToast(msg: String) {
        showToast(msg, Toast.LENGTH_SHORT)
    }

    /** 顯示 Toast（指定時長，自動取消前一個） */
    @Synchronized
    fun showToast(msg: String, duration: Int) {
        handler.post {
            toastRef?.let {
                it.get()?.cancel()
                it.clear()
            }
            toastRef = null;
            val toast = Toast.makeText(ContextUtil.getContext(), msg, duration)
            toastRef = WeakReference(toast)
            toast.show()
        }
    }

}
