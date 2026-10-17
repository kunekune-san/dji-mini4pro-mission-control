package dji.sampleV5.aircraft.dji

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import dji.sampleV5.aircraft.util.AppLog
import dji.sampleV5.aircraft.config.AppConfig
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import dji.v5.utils.common.ContextUtil
import dji.sdk.keyvalue.value.camera.MediaFileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.Calendar
import kotlin.coroutines.resume

/** 將 DJI DateTime 轉換為 epoch 毫秒（用於排序） */
private fun djiDateTimeToMillis(dt: dji.sdk.keyvalue.value.camera.DateTime?): Long {
    if (dt == null) return 0L
    return try {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, dt.year ?: 0)
        cal.set(Calendar.MONTH, (dt.month?.coerceIn(1, 12) ?: 1) - 1)
        cal.set(Calendar.DAY_OF_MONTH, dt.day ?: 1)
        cal.set(Calendar.HOUR_OF_DAY, dt.hour ?: 0)
        cal.set(Calendar.MINUTE, dt.minute ?: 0)
        cal.set(Calendar.SECOND, dt.second ?: 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    } catch (e: Exception) {
        0L
    }
}

/** 照片下載結果 */
sealed class DownloadResult {
    /** 下載成功，count 為下載張數 */
    data class Success(val count: Int) : DownloadResult()
    /** 下載失敗，reason 為失敗原因 */
    data class Failure(val reason: String) : DownloadResult()
}

/** 媒體下載管理單例，負責從 SD 卡下載照片到手機相簿 */
object MediaDownloadManager {

    private const val TAG = "MediaDownload"

    private val mediaManager get() = MediaDataCenter.getInstance().mediaManager

    /** 下載 SD 卡上最新的 N 張照片到手機相簿（無時間過濾） */
    suspend fun downloadLastNPhotos(n: Int = 7): DownloadResult {
        AppLog.d(TAG, "Downloading last $n photos from SD card...")
        return downloadPhotos(
            filter = { true },
            transform = { it.sortedByDescending { f -> djiDateTimeToMillis(f.date) } },
            limit = n,
            emptyMessage = "No photos found on SD card"
        )
    }

    /** 共用照片下載管線：啟用媒體模式 → 拉取檔案清單 → 過濾 → 下載 → 停用媒體模式 */
    private suspend fun downloadPhotos(
        filter: (MediaFile) -> Boolean,
        transform: ((List<MediaFile>) -> List<MediaFile>)? = null,
        limit: Int,
        emptyMessage: String? = null,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): DownloadResult {
        return try {
            enableMediaMode()
            AppLog.d(TAG, "Media mode enabled")

            pullFileList()
            AppLog.d(TAG, "File list pulled")

            val allFiles = try {
                mediaManager.mediaFileListData?.data ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get media file list: $e")
                emptyList()
            }
            AppLog.d(TAG, "Total files on SD card: ${allFiles.size}")

            val isPhoto: (MediaFile) -> Boolean = { file ->
                file.fileType == MediaFileType.JPEG || file.fileType == MediaFileType.DNG
            }

            var photos = allFiles.filter { isPhoto(it) && filter(it) }
            if (transform != null) photos = transform(photos)
            photos = photos.take(limit)

            AppLog.d(TAG, "Filtered photos: ${photos.size}")

            if (photos.isEmpty()) {
                disableMediaMode()
                if (emptyMessage != null) return DownloadResult.Failure(emptyMessage)
                return DownloadResult.Success(0)
            }

            var downloaded = 0
            val ctx = currentCoroutineContext()
            for ((index, mediaFile) in photos.withIndex()) {
                if (!ctx.isActive) {
                    Log.w(TAG, "Download cancelled at ${index}/${photos.size}")
                    break
                }
                AppLog.d(TAG, "Downloading ${index + 1}/${photos.size}: ${mediaFile.fileName}")
                onProgress?.invoke(index + 1, photos.size)

                val success = downloadFileToGallery(mediaFile)
                if (success) {
                    downloaded++
                    AppLog.d(TAG, "Saved ${mediaFile.fileName} to gallery")
                } else {
                    Log.e(TAG, "Failed to save ${mediaFile.fileName}")
                }
            }

            disableMediaMode()
            AppLog.d(TAG, "Download complete: $downloaded/${photos.size} photos saved")
            DownloadResult.Success(downloaded)

        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            try { disableMediaMode() } catch (e: Exception) { Log.w(TAG, "disableMediaMode cleanup failed", e) }
            DownloadResult.Failure(e.message ?: "Unknown error")
        }
    }

    /** 啟用媒體模式（進入檔案存取狀態） */
    private suspend fun enableMediaMode() {
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation {
                Log.w(TAG, "enableMediaMode cancelled, SDK callback will be ignored")
            }
            mediaManager.enable(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.d(TAG, "Media mode enabled")
                    if (cont.isActive) cont.resume(Unit) {}
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Enable media mode failed: $error")
                    if (cont.isActive) cont.resumeWith(Result.failure(Exception("Enable media mode failed: $error")))
                }
            })
        }
    }

    /** 停用媒體模式（退出檔案存取狀態） */
    private suspend fun disableMediaMode() {
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation {
                Log.w(TAG, "disableMediaMode cancelled, SDK callback will be ignored")
            }
            mediaManager.disable(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.d(TAG, "Media mode disabled")
                    if (cont.isActive) cont.resume(Unit) {}
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Disable media mode failed: $error")
                    if (cont.isActive) cont.resume(Unit) {}
                }
            })
        }
    }

    /** 從相機拉取 SD 卡檔案清單 */
    private suspend fun pullFileList() {
        suspendCancellableCoroutine<Unit> { cont ->
            mediaManager.pullMediaFileListFromCamera(
                PullMediaFileListParam.Builder()
                    .mediaFileIndex(-1) // 從第一筆開始
                    .count(-1)          // 拉取全部
                    .build(),
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        AppLog.d(TAG, "Pull file list success")
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                    override fun onFailure(error: IDJIError) {
                        Log.e(TAG, "Pull file list failed: $error")
                        if (cont.isActive) cont.resumeWith(Result.failure(Exception("Pull file list failed: $error")))
                    }
                }
            )
        }
        // 給 SDK 更多時間更新內部檔案列表
        kotlinx.coroutines.delay(AppConfig.DOWNLOAD_PULL_DELAY_MS)
    }

    /** 下載單一檔案並串流寫入手機相簿（避免整檔載入記憶體） */
    private suspend fun downloadFileToGallery(mediaFile: MediaFile): Boolean {
        val mimeType = when (mediaFile.fileType) {
            MediaFileType.DNG -> "image/x-adobe-dng"
            else -> "image/jpeg"
        }

        return withContext(Dispatchers.IO) {
            val context = ContextUtil.getContext()
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, mediaFile.fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/${AppConfig.DOWNLOAD_FOLDER}")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            if (uri == null) {
                Log.e(TAG, "Failed to create MediaStore entry for ${mediaFile.fileName}")
                return@withContext false
            }

            var success = false
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    success = downloadFileStreaming(mediaFile, outputStream)
                } ?: run {
                    Log.e(TAG, "Failed to open OutputStream for ${mediaFile.fileName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download/write failed for ${mediaFile.fileName}: $e")
            }

            if (success) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                }
                AppLog.d(TAG, "Saved to gallery: ${mediaFile.fileName}")
            } else {
                // 清理失敗的 MediaStore 記錄
                try { context.contentResolver.delete(uri, null, null) } catch (e: Exception) { Log.w(TAG, "MediaStore cleanup failed", e) }
            }
            success
        }
    }

    /** 從相機串流下載檔案到 OutputStream（不累積記憶體），60 秒超時 */
    private suspend fun downloadFileStreaming(mediaFile: MediaFile, outputStream: java.io.OutputStream): Boolean {
        return withTimeout(60000L) {
            suspendCancellableCoroutine { cont ->
                mediaFile.pullOriginalMediaFileFromCamera(0L, object : MediaFileDownloadListener {
                    override fun onStart() {
                        AppLog.d(TAG, "Download started: ${mediaFile.fileName}")
                    }

                    override fun onProgress(total: Long, current: Long) {
                        if (total > 0) {
                            AppLog.d(TAG, "Download progress: ${mediaFile.fileName} ${current * 100 / total}%")
                        }
                    }

                    override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
                        try {
                            outputStream.write(data)
                        } catch (e: Exception) {
                            Log.e(TAG, "Write failed during download: ${mediaFile.fileName}: $e")
                        }
                    }

                    override fun onFinish() {
                        try {
                            outputStream.flush()
                        } catch (e: Exception) {
                            Log.e(TAG, "Flush failed: ${mediaFile.fileName}: $e")
                        }
                        AppLog.d(TAG, "Download finished: ${mediaFile.fileName}")
                        if (cont.isActive) cont.resume(true) {}
                    }

                    override fun onFailure(error: IDJIError?) {
                        Log.e(TAG, "Download failed: ${mediaFile.fileName}: $error")
                        if (cont.isActive) cont.resume(false) {}
                    }
                })
            }
        }
    }
}
