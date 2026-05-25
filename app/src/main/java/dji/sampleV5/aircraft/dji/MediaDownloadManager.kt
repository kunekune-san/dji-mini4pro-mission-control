package dji.sampleV5.aircraft.dji

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import dji.sampleV5.aircraft.config.AppConfig
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import dji.v5.manager.datacenter.media.MediaFileListState
import dji.v5.manager.datacenter.media.MediaFileListStateListener
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import dji.v5.utils.common.ContextUtil
import dji.sdk.keyvalue.value.camera.MediaFileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Calendar
import kotlin.coroutines.resume

/** 將 DJI DateTime 轉換為 epoch 毫秒（用於排序） */
private fun djiDateTimeToMillis(dt: dji.sdk.keyvalue.value.camera.DateTime?): Long {
    if (dt == null) return 0L
    return try {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, dt.year ?: 0)
        cal.set(Calendar.MONTH, (dt.month ?: 1) - 1)
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

sealed class DownloadResult {
    data class Success(val count: Int) : DownloadResult()
    data class Failure(val reason: String) : DownloadResult()
}

object MediaDownloadManager {

    private const val TAG = "MediaDownload"

    private val mediaManager get() = MediaDataCenter.getInstance().mediaManager

    /** 下載 SD 卡上最新的 N 張照片到手機相簿（無時間過濾） */
    suspend fun downloadLastNPhotos(n: Int = 7): DownloadResult {
        Log.d(TAG, "Downloading last $n photos from SD card...")

        return try {
            enableMediaMode()
            Log.d(TAG, "Media mode enabled")

            pullFileList()
            Log.d(TAG, "File list pulled")

            val allFiles = try {
                mediaManager.mediaFileListData?.data ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get media file list: $e")
                emptyList()
            }
            Log.d(TAG, "Total files on SD card: ${allFiles.size}")

            val photos = allFiles.filter { file ->
                file.fileType == MediaFileType.JPEG || file.fileType == MediaFileType.DNG
            }.sortedByDescending { djiDateTimeToMillis(it.date) }
             .take(n)

            if (photos.isEmpty()) {
                Log.w(TAG, "No photos found on SD card")
                disableMediaMode()
                return DownloadResult.Failure("No photos found on SD card")
            }

            Log.d(TAG, "Found ${photos.size} photos to download")
            var downloaded = 0
            photos.forEachIndexed { index, mediaFile ->
                Log.d(TAG, "Downloading ${index + 1}/${photos.size}: ${mediaFile.fileName}")
                val success = downloadFileToGallery(mediaFile)
                if (success) {
                    downloaded++
                    Log.d(TAG, "Saved ${mediaFile.fileName} to gallery")
                } else {
                    Log.e(TAG, "Failed to save ${mediaFile.fileName}")
                }
            }

            disableMediaMode()
            Log.d(TAG, "Download complete: $downloaded/${photos.size} photos saved")
            DownloadResult.Success(downloaded)

        } catch (e: Exception) {
            Log.e(TAG, "Download last N photos failed: ${e.message}", e)
            try { disableMediaMode() } catch (_: Exception) {}
            DownloadResult.Failure(e.message ?: "Unknown error")
        }
    }

    /** 下載任務期間拍攝的照片（依時間過濾，僅下載任務開始後的照片） */
    suspend fun downloadMissionPhotos(
        missionStartTime: Long,
        onProgress: (current: Int, total: Int) -> Unit
    ): DownloadResult {
        Log.d(TAG, "Starting photo download, mission start: $missionStartTime")

        return try {
            // 1. Enable media mode
            enableMediaMode()
            Log.d(TAG, "Media mode enabled")

            // 2. Pull media file list from camera
            pullFileList()
            Log.d(TAG, "File list pulled")

            // 3. Get and filter files
            val allFiles = try {
                mediaManager.mediaFileListData?.data ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get media file list: $e")
                emptyList()
            }
            Log.d(TAG, "Total files on SD card: ${allFiles.size}")

            val missionPhotos = allFiles.filter { file ->
                val isPhoto = file.fileType == MediaFileType.JPEG || file.fileType == MediaFileType.DNG
                val isAfterMission = try {
                    val dt = file.date
                    if (dt != null) {
                        val cal = Calendar.getInstance().apply {
                            set(Calendar.YEAR, dt.year ?: return@apply)
                            set(Calendar.MONTH, (dt.month ?: 1) - 1) // Calendar months are 0-based
                            set(Calendar.DAY_OF_MONTH, dt.day ?: 1)
                            set(Calendar.HOUR_OF_DAY, dt.hour ?: 0)
                            set(Calendar.MINUTE, dt.minute ?: 0)
                            set(Calendar.SECOND, dt.second ?: 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        cal.timeInMillis >= missionStartTime
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot parse date for ${file.fileName}: $e")
                    false
                }
                isPhoto && isAfterMission
            }.take(AppConfig.MAX_DOWNLOAD_PHOTOS)

            Log.d(TAG, "Filtered mission photos: ${missionPhotos.size}")

            if (missionPhotos.isEmpty()) {
                disableMediaMode()
                return DownloadResult.Success(0)
            }

            // 4. Download each photo to gallery
            var downloaded = 0
            missionPhotos.forEachIndexed { index, mediaFile ->
                Log.d(TAG, "Downloading ${index + 1}/${missionPhotos.size}: ${mediaFile.fileName}")
                onProgress(index + 1, missionPhotos.size)

                val success = downloadFileToGallery(mediaFile)
                if (success) {
                    downloaded++
                    Log.d(TAG, "Saved ${mediaFile.fileName} to gallery")
                } else {
                    Log.e(TAG, "Failed to save ${mediaFile.fileName}")
                }
            }

            // 5. Disable media mode
            disableMediaMode()
            Log.d(TAG, "Media mode disabled, download complete: $downloaded photos")

            DownloadResult.Success(downloaded)

        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            try { disableMediaMode() } catch (_: Exception) {}
            DownloadResult.Failure(e.message ?: "Unknown error")
        }
    }

    /** 啟用媒體模式（進入檔案存取狀態） */
    private suspend fun enableMediaMode() {
        suspendCancellableCoroutine<Unit> { cont ->
            mediaManager.enable(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.d(TAG, "Media mode enabled")
                    if (cont.isActive) cont.resume(Unit) {}
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Enable media mode failed: $error")
                    if (cont.isActive) cont.resume(Unit) {} // continue anyway
                }
            })
        }
    }

    /** 停用媒體模式（退出檔案存取狀態） */
    private suspend fun disableMediaMode() {
        suspendCancellableCoroutine<Unit> { cont ->
            mediaManager.disable(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.d(TAG, "Media mode disabled")
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
                    .mediaFileIndex(-1) // start from first
                    .count(-1)          // pull all
                    .build(),
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.d(TAG, "Pull file list success")
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                    override fun onFailure(error: IDJIError) {
                        Log.e(TAG, "Pull file list failed: $error")
                        if (cont.isActive) cont.resume(Unit) {}
                    }
                }
            )
        }
        // Give the SDK more time to update the internal list
        kotlinx.coroutines.delay(3000)
    }

    /** 下載單一檔案並儲存到手機相簿 */
    private suspend fun downloadFileToGallery(mediaFile: MediaFile): Boolean {
        return try {
            val data = downloadFileData(mediaFile)
            if (data == null || data.isEmpty()) {
                Log.e(TAG, "Downloaded data is empty for ${mediaFile.fileName}")
                return false
            }

            val saved = withContext(Dispatchers.IO) {
                saveToGallery(mediaFile.fileName, data)
            }
            saved
        } catch (e: Exception) {
            Log.e(TAG, "downloadFileToGallery failed for ${mediaFile.fileName}: $e")
            false
        }
    }

/** 從相機下載檔案原始資料（位元組陣列） */
    private suspend fun downloadFileData(mediaFile: MediaFile): ByteArray? {
        return suspendCancellableCoroutine { cont ->
            val bos = ByteArrayOutputStream()
            mediaFile.pullOriginalMediaFileFromCamera(0L, object : MediaFileDownloadListener {
                override fun onStart() {
                    Log.d(TAG, "Download started: ${mediaFile.fileName}")
                }

                override fun onProgress(total: Long, current: Long) {
                    if (total > 0) {
                        Log.d(TAG, "Download progress: ${mediaFile.fileName} ${current * 100 / total}%")
                    }
                }

                override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
                    bos.write(data)
                }

                override fun onFinish() {
                    Log.d(TAG, "Download finished: ${mediaFile.fileName} (${bos.size()} bytes)")
                    if (cont.isActive) cont.resume(bos.toByteArray()) {}
                }

                override fun onFailure(error: IDJIError?) {
                    Log.e(TAG, "Download failed: ${mediaFile.fileName}: $error")
                    if (cont.isActive) cont.resume(null) {}
                }
            })
        }
    }

    /** 將位元組資料儲存到手機相簿（MediaStore） */
    private fun saveToGallery(fileName: String, data: ByteArray): Boolean {
        return try {
            val context = ContextUtil.getContext()
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
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
                Log.e(TAG, "Failed to create MediaStore entry for $fileName")
                return false
            }

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(data)
                stream.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }

            Log.d(TAG, "Saved to gallery: $fileName (${data.size} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Save to gallery failed for $fileName: $e")
            false
        }
    }
}
