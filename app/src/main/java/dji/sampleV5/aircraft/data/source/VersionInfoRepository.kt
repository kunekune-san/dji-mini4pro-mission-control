package dji.sampleV5.aircraft.data.source

import android.annotation.SuppressLint
import androidx.annotation.WorkerThread
import com.google.gson.annotations.SerializedName
import dji.v5.common.error.IDJIError
import dji.v5.network.DJIHttpCallback
import dji.v5.network.DJIHttpRequest
import dji.v5.network.DJIHttpResponse
import dji.v5.network.DJINetworkManager
import dji.v5.utils.common.ContextUtil
import dji.v5.utils.common.FileUtils
import dji.v5.utils.common.JsonUtil
import dji.v5.utils.common.LogUtils
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.io.File
import java.util.*

/**
 * 該 Repository 負責簡單的 HTTP 資料取得能力，以及資料儲存和取得能力
 */
@SuppressLint("CheckResult")
class VersionInfoRepository {


    /**
     * 用於觀察網路模組（DJINetworkManager）是否初始化完成，並不關心網路實際是否有效
     */
    private val networkInitSubject: BehaviorSubject<Boolean> = BehaviorSubject.create()

    private var hostConfig: HostConfig? = null

    private var currentVersionInfo: VersionInfo? = null

    init {
        Completable.create { emitter ->
            DJINetworkManager.getInstance().addNetworkStatusListener {
                if (it) {
                    emitter.onComplete()
                }
            }
        }.mergeWith(Completable.fromAction {
            hostConfig = getHostConfig()
        }.observeOn(Schedulers.io())).subscribe {
            // 通知網路初始化完成
            networkInitSubject.onNext(true)
        }
    }

    /**
     * 取得目前版本資訊
     */
    fun fetchCurrentVersionInfo(versionName: String, language: String = getLanguageHeaderValue()): Single<VersionInfo> {
        val versionNameEx = getCurrentVersionExTest().ifEmpty { versionName }
        LogUtils.i(LOG_TAG, "getCurrentVersionInfo, $versionNameEx")

        val fileName = "version_info_${versionNameEx}_${language}.json"
        return Single.create<VersionInfo> {
            if (currentVersionInfo == null) {
                val currentVersionInfo = loadVersionInfoFromLocal(fileName)
                this.currentVersionInfo = currentVersionInfo
                it.onSuccess(currentVersionInfo)
                return@create
            }
            it.onError(VersionInfoCacheFileNotFound())
        }.onErrorResumeNext { fetchVersionInfoAndSave(versionNameEx, fileName, false, language) }
            .subscribeOn(Schedulers.io())
    }

    /**
     * 強制拉取最新版本資訊
     */
    fun fetchLatestVersionInfo(versionName: String, language: String = getLanguageHeaderValue()): Observable<VersionInfo> {
        return Observable.create { emitter ->
            val fileName = "${FILE_NAME_LATEST_VERSION_INFO_CACHE}_${language}.json"
            // 1. 從本地檔案載入並通知給用戶端，用於盡快刷新 UI
            Schedulers.io().scheduleDirect {
                try {
                    val versionInfo = loadVersionInfoFromLocal(fileName)
                    emitter.onNext(versionInfo)
                } catch (throwable: Throwable) {
                    //忽略
                    LogUtils.i(LOG_TAG, "error, $throwable")
                }
            }
            // 2. 從雲端取得最新資料，需要則通知給用戶端
            val fetchD = fetchVersionInfoAndSave(versionName, fileName, true, language)
                .observeOn(Schedulers.io())
                .subscribe({
                    emitter.onNext(it)
                    emitter.onComplete()
                }, {
                    if (emitter.isDisposed) {
                        return@subscribe
                    }
                    emitter.onError(it)
                })
            // 3. 請求被取消時取消雲端請求
            emitter.setCancellable {
                fetchD.dispose()
            }
        }
    }

    private fun fetchVersionInfoAndSave(
        versionName: String,
        fileNameForCache: String,
        isLatest: Boolean,
        language: String,
    ): Single<VersionInfo> {
        return fetchVersionInfoFromRemote(versionName, isLatest, language)
            .delaySubscription(networkInitSubject.filter {
                it
            }.firstOrError())
            .observeOn(Schedulers.io())
            .doOnSuccess {
                saveVersionInfoToLocal(fileNameForCache, it)
                currentVersionInfo = it
            }
            .onErrorResumeNext { Single.error(FetchVersionInfoError()) }
    }

    @WorkerThread
    @Synchronized
    private fun loadVersionInfoFromLocal(fileName: String): VersionInfo {
        // 取得檔案
        val versionInfoFile = ContextUtil.getContext().getFileStreamPath(fileName)

        // 判斷本地是否有該檔案，載入本地檔案
        if (!versionInfoFile.exists()) {
            // 不存在，丟出檔案未找到例外
            throw VersionInfoCacheFileNotFound()
        }

        // 存在，則載入本地檔案
        return JsonUtil.toBean(versionInfoFile, VersionInfo::class.java) ?: throw VersionInfoCacheFileNotFound()
    }

    @WorkerThread
    @Synchronized
    private fun saveVersionInfoToLocal(fileName: String, versionInfoList: VersionInfo) {
        // 取得檔案
        val versionInfoFile = ContextUtil.getContext().getFileStreamPath(fileName)

        // 重新建立檔案
        FileUtils.delFile(versionInfoFile, false)
        FileUtils.createFile(versionInfoFile)

        // 將版本資訊寫入檔案
        versionInfoFile.writer().use {
            it.write(JsonUtil.toJson(versionInfoList) ?: "")
        }
    }

    /**
     * 從雲端拉取版本資訊
     * @param versionName 目前版本資訊
     * @param isLatest true：拉取目前大版本（major version）的最新版本資訊；false：拉取 versionName 所指定版本資訊（可能拉取到空資料）
     */
    private fun fetchVersionInfoFromRemote(versionName: String, isLatest: Boolean, l: String): Single<VersionInfo> {
        return Single.create {
            DJINetworkManager.getInstance().enqueue(buildHttpRequestExTest(versionName, isLatest, l), object : DJIHttpCallback<DJIHttpResponse> {
                override fun onFailure(error: IDJIError) {
                    LogUtils.i(LOG_TAG, "error, ${error.errorCode()} ${error.errorType()} ${error.description()}")
                    it.onError(FetchVersionInfoError())
                }

                override fun onResponse(response: DJIHttpResponse) {
                    LogUtils.i(LOG_TAG, "success, ${response.code()}")

                    if (!response.isSuccessful) {
                        it.onError(FetchVersionInfoError())
                        return
                    }
                    val httpResult = HttpResult.fromJson(response.body())
                    if (httpResult == null) {
                        it.onError(FetchVersionInfoError())
                        return
                    }
                    LogUtils.i(LOG_TAG, "success, $httpResult")
                    it.onSuccess(httpResult.data)
                }

                override fun onLoading(current: Long, total: Long) {
//                    super.onLoading(current, total)
                }
            })
        }
    }

    /**
     * 根據入參建立不同的請求體
     */
    @WorkerThread
    private fun buildHttpRequest(host: String, versionName: String, isLatest: Boolean, language: String): DJIHttpRequest {
        val versionSplits = versionName.split(".").toMutableList()

        // 檢查 VersionName 是否為三段式版本號，不是則補齊
        val diff = 3 - versionSplits.size
        for (i in 0 until diff) {
            versionSplits.add("0")
        }

        val httpRequest = DJIHttpRequest.Builder.newBuilder()
            .requestType(DJIHttpRequest.RequestType.GET)
            .url(
                if (isLatest) {
                    "$host$URL_PATH_TCH_LATEPATHST_VERSION_INFO"
                } else {
                    "$host$URL_PATH_FETCH_CURRENT_VERSION_INFO"
                }
            )
            .params(
                if (isLatest) {
                    mapOf(
                        "sdk_name" to "mobile sdk",
                        "platform" to "android",
                        "major_version" to versionSplits[0],
                    )
                } else {
                    mapOf(
                        "sdk_name" to "mobile sdk",
                        "platform" to "android",
                        "major_version" to versionSplits[0],
                        "minor_version" to versionSplits[1],
                        "patch_version" to versionSplits[2],
                    )
                }
            )
            .headers(mapOf("Language" to language, "content-type" to "application/json"))
            .build()
        LogUtils.i(LOG_TAG, "request, $httpRequest")
        return httpRequest
    }

    private fun getHostConfig(): HostConfig? {
        val hostConfigFile = getHostConfigFile() ?: return null
        JsonUtil.toBean(hostConfigFile, HostConfig::class.java)?.let {
            // 做一個簡單的 URL 校驗
            if (it.host.endsWith('/') && it.host.startsWith("https://")) {
                return it
            }
        }
        return null
    }

    private fun getHostConfigFile(): File? {
        val dir = ContextUtil.getContext().getExternalFilesDir("")
        val configFile = File(dir, FILE_NAME_HOST_VERSION_CONFIG)
        if (configFile.exists()) {
            return configFile
        }
        return null
    }

    private fun buildHttpRequestExTest(versionName: String, isLatest: Boolean, language: String): DJIHttpRequest {
        hostConfig?.let {
            return buildHttpRequest(it.host, it.versionName.ifEmpty { versionName }, isLatest, language)
        }
        return buildHttpRequest(URL_HOST, versionName, isLatest, language)
    }

    /**
     * 目前 MSDK 只有中英文，經測試發現
     *  - string.xml 只有在系統設定為簡體中文（國家無所謂）時才會選擇中文的 string.xml
     * 所以該處新增兩項判斷
     */
    private fun getLanguageHeaderValue(): String {
        // "ZH" 表示中文
        val currentLocale = Locale.getDefault()
        val country: String = currentLocale.country.lowercase(Locale.ROOT)
        LogUtils.i(LOG_TAG,currentLocale.language,currentLocale.script)
        return if ("zh".equals(currentLocale.language, true) && "cn".equals(country)) {
            "cn"
        } else {
            "en"
        }
    }

    /**
     * 取得測試用 HostConfig 中設定的版本名稱
     */
    fun getCurrentVersionExTest(): String {
        return hostConfig?.versionName ?: ""
    }

    class VersionInfoCacheFileNotFound : Exception()
    class FetchVersionInfoError : Exception()

    companion object {
        /**
         * 伺服器 host
         */
        private const val URL_HOST = "https://dev.dji.com/"

        /**
         * 取得最新版本資訊
         */
        private const val URL_PATH_TCH_LATEPATHST_VERSION_INFO = "api/v1/release/latest"

        /**
         * 取得指定版本資訊
         */
        private const val URL_PATH_FETCH_CURRENT_VERSION_INFO = "api/v1/release"

        /**
         * 用於儲存最新版本資訊的檔案
         */
        private const val FILE_NAME_LATEST_VERSION_INFO_CACHE = "version_info_latest"

        /**
         * 提供給測試使用的設定檔路徑
         */
        @SuppressLint("SdCardPath")
        private const val FILE_NAME_HOST_VERSION_CONFIG = "config_host_version.json"
        private const val LOG_TAG = "VersionInfoRepository"
    }
}

private data class HostConfig(
    @SerializedName("host")
    val host: String = "",
    @SerializedName("version")
    val versionName: String = "",
)

data class HttpResult(val code: Int, val message: String, val data: VersionInfo) {

    companion object {
        fun fromJson(json: String): HttpResult? {
            return JsonUtil.toBean(json, HttpResult::class.java)
        }
    }

    override fun toString(): String {
        return "HttpResult(code=$code, message='$message', data='$data')"
    }
}

data class VersionInfo(
    val versionCode: Int = 0,
    @SerializedName("MajorVersion")
    val versionMajor: String = "",
    @SerializedName("MinorVersion")
    val versionMinor: String = "",
    @SerializedName("PatchVersion")
    val versionPatch: String = "",

    /**
     * UTC 時間戳（單位 s）
     */
    @SerializedName("ReleaseDate")
    val releaseTimeStamp: Long = 0,
    @SerializedName("SupportedProducts")
    val supportProducts: String = "",
    @SerializedName("Highlights")
    val releaseNode: String = "",
) {
    val versionName: String
        get() {
            return "$versionMajor.$versionMinor.$versionPatch"
        }
}