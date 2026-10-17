package dji.sampleV5.aircraft.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat.startActivity
import dji.v5.utils.common.LogUtils


/** 通用工具類（陣列轉列表、瀏覽器開啟、檔案選擇器） */
object Helper {

    val TAG = LogUtils.getTag("Helper")

    /** 將 Array 轉為 ArrayList<String> */
    fun makeList(o: Array<*>): ArrayList<String> {
        val list = ArrayList<String>()
        val iterator = o.iterator()
        while (iterator.hasNext()) {
            list.add(iterator.next().toString())
        }
        return list
    }

    /** 將 IntArray 轉為 ArrayList<String> */
    fun makeList(o: IntArray): ArrayList<String> {
        val list = ArrayList<String>()
        for (i in o.indices) {
            list.add(Integer.valueOf(o[i]).toString())
        }
        return list
    }

    /** 將 DoubleArray 轉為 ArrayList<String> */
    fun makeList(o: DoubleArray): ArrayList<String> {
        val list = ArrayList<String>()
        for (i in o.indices) {
            list.add(java.lang.Double.valueOf(o[i]).toString())
        }
        return list
    }

    /** 將 LongArray 轉為 ArrayList<String> */
    fun makeList(o: LongArray): ArrayList<String> {
        val list = ArrayList<String>()
        for (i in o.indices) {
            list.add(java.lang.Long.valueOf(o[i]).toString())
        }
        return list
    }

    /** 將 List 轉為 ArrayList<String> */
    fun makeList(o: List<*>): ArrayList<String> {
        val list = ArrayList<String>()
        val iterator = o.iterator()
        while (iterator.hasNext()) {
            list.add(iterator.next().toString())
        }
        return list
    }

    /** 開啟系統瀏覽器瀏覽指定 URL */
    fun startBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)   //有些機型上會出現瀏覽器拉不起來的情況，例如華為 P20
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // 解決使用 applicationContext 呼叫 startActivity 出現的異常
            startActivity(context, intent, null)
        } catch (e: Exception) {
            LogUtils.e(TAG, "startWeb ", e.message)
        }
    }

    /** 開啟系統檔案選擇器（指定初始路徑） */
    fun openFileChooser(path: String, context: Context?) {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary:$path")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context?.startActivity(
            Intent.createChooser(intent, "MSDK")
        )
    }
}