package dji.sampleV5.aircraft.mission

import dji.sampleV5.aircraft.config.AppConfig
import dji.sampleV5.aircraft.util.AppLog
import java.util.concurrent.atomic.AtomicReference

/**
 * 視覺輔助 Yaw 修正模組（方案 A）。
 *
 * 使用者在 FPV 畫面點擊目標後，以 SAD（Sum of Absolute Differences）模板匹配
 * 追蹤目標在畫面中的水平位置，輸出 normalizedX 修正值供 [OrbitController] 的
 * Yaw 控制器使用。
 *
 * **設計原則：**
 * - 純加法：不選目標或追蹤失敗時回傳 0.0，不影響既有 GPS 控制
 * - 非阻塞：[processFrame] 在 CV Thread 執行，[getCorrection] 在控制迴圈讀取
 * - 零依賴：僅使用 Android 原生陣列操作，不需要 OpenCV
 *
 * **使用流程：**
 * 1. 使用者在 FPV 畫面點擊 → [selectTarget]
 * 2. CV Thread 每幀呼叫 [processFrame]
 * 3. 控制迴圈讀取 [getCorrection]
 * 4. 任務結束呼叫 [reset]
 */
internal object VisualYawAssist {

    private const val TAG = "VisualYaw"

    // ========== 狀態 ==========
    /** 是否正在等待使用者選取目標（選取等待窗期間為 true） */
    @Volatile var waitingForSelection = false
        private set
    @Volatile private var isTargetSelected = false
    @Volatile private var templateData: ByteArray? = null
    @Volatile private var templateW = 0
    @Volatile private var templateH = 0
    @Volatile private var lastMatchX = -1          // 上次匹配位置（降採樣座標）
    @Volatile private var lastMatchY = -1
    @Volatile private var frameW = 0               // 畫面寬度（原始解析度）
    @Volatile private var frameH = 0               // 畫面高度

    /** 最新修正結果（控制迴圈讀取） */
    val latestResult = AtomicReference(TrackResult(0.0f, 0.0f))

    // ========== 資料模型 ==========

    /**
     * 追蹤結果
     * @param normalizedX 水平偏移量：-1.0（最左）~ +1.0（最右），0.0 = 畫面中央
     * @param confidence  信心度：0.0（追蹤失敗）~ 1.0（完美匹配）
     */
    data class TrackResult(val normalizedX: Float, val confidence: Float)

    // ========== 公開 API ==========

    /**
     * 使用者在 FPV 畫面點擊後呼叫，從當前幀提取模板。
     *
     * @param nv21Data  NV21 格式的幀數據（Y 平面用於匹配）
     * @param width     畫面寬度（像素）
     * @param height    畫面高度（像素）
     * @param tapX      使用者點擊的 X 座標（畫面座標，像素）
     * @param tapY      使用者點擊的 Y 座標（畫面座標，像素）
     */
    fun selectTarget(nv21Data: ByteArray, width: Int, height: Int, tapX: Float, tapY: Float) {
        frameW = width
        frameH = height
        val tplSize = AppConfig.VISUAL_TEMPLATE_SIZE

        // 防禦：畫面太小時不選取
        if (width < tplSize || height < tplSize) {
            AppLog.d(TAG, "Frame too small for template: ${width}x${height} < ${tplSize}x${tplSize}")
            return
        }

        val half = tplSize / 2

        // 計算模板左上角（確保不超出畫面邊界）
        val startX = (tapX.toInt() - half).coerceIn(0, width - tplSize)
        val startY = (tapY.toInt() - half).coerceIn(0, height - tplSize)

        // 從 Y 平面提取模板（NV21 的 Y 平面在前 width*height bytes）
        val tpl = ByteArray(tplSize * tplSize)
        for (row in 0 until tplSize) {
            val srcOffset = (startY + row) * width + startX
            System.arraycopy(nv21Data, srcOffset, tpl, row * tplSize, tplSize)
        }

        templateData = tpl
        templateW = tplSize
        templateH = tplSize
        lastMatchX = -1
        lastMatchY = -1
        isTargetSelected = true

        AppLog.d(TAG, "Target selected at ($tapX, $tapY), template=${tplSize}x${tplSize}, " +
            "start=($startX, $startY), frame=${width}x${height}")
    }

    /**
     * CV Thread 每幀呼叫，執行 SAD 模板匹配。
     *
     * 演算法：
     * 1. 在上次匹配位置附近 ±SEARCH_RANGE 搜尋
     * 2. 降採樣後計算 SAD（每候選位置 ~tplPixels 次運算）
     * 3. 找到最小 SAD 位置，計算 confidence
     * 4. 輸出 normalizedX
     *
     * @param nv21Data  NV21 格式的幀數據
     * @param width     畫面寬度
     * @param height    畫面高度
     */
    fun processFrame(nv21Data: ByteArray, width: Int, height: Int) {
        val tpl = templateData ?: return
        val tplW = templateW
        val tplH = templateH
        if (!isTargetSelected || tplW == 0 || tplH == 0) return

        frameW = width
        frameH = height

        val ds = AppConfig.VISUAL_DOWNSAMPLE
        val tplWds = tplW / ds
        val tplHds = tplH / ds
        if (tplWds <= 0 || tplHds <= 0) return

        // 降採樣模板（從原始模板）
        val tplDs = downsampleTemplate(tpl, tplW, tplH, ds)

        // 搜尋範圍（原始座標 → 降採樣座標）
        val searchPxOrig = (width * AppConfig.VISUAL_SEARCH_RANGE_FRAC).toInt()
        val searchPx = searchPxOrig / ds

        // 搜尋中心：上次匹配位置（若有），否則畫面中央
        val centerXds = if (lastMatchX >= 0) lastMatchX else width / ds / 2
        val centerYds = if (lastMatchY >= 0) lastMatchY else height / ds / 2

        // 搜尋邊界（降採樣座標，確保模板不超出畫面）
        val xMin = (centerXds - searchPx).coerceIn(0, width / ds - tplWds)
        val xMax = (centerXds + searchPx).coerceIn(0, width / ds - tplWds)
        val yMin = (centerYds - searchPx).coerceIn(0, height / ds - tplHds)
        val yMax = (centerYds + searchPx).coerceIn(0, height / ds - tplHds)

        // SAD 匹配
        var bestSad = Long.MAX_VALUE
        var bestX = centerXds
        var bestY = centerYds
        var secondBestSad = Long.MAX_VALUE

        for (y in yMin..yMax step 2) {      // step 2 進一步減少計算量
            for (x in xMin..xMax step 2) {
                val sad = computeSad(nv21Data, width, height, ds, tplDs, tplWds, tplHds, x, y)
                if (sad < bestSad) {
                    secondBestSad = bestSad
                    bestSad = sad
                    bestX = x
                    bestY = y
                } else if (sad < secondBestSad) {
                    secondBestSad = sad
                }
            }
        }

        // 更新匹配位置
        lastMatchX = bestX
        lastMatchY = bestY

        // 計算信心度：1 - (bestSad / secondBestSad)
        // 差異越大代表匹配越明確
        val confidence = if (secondBestSad > 0) {
            (1.0f - bestSad.toFloat() / secondBestSad.toFloat()).coerceIn(0.0f, 1.0f)
        } else {
            1.0f
        }

        // 計算 normalizedX（降採樣座標 → 歸一化）
        val matchCenterXOrig = (bestX + tplWds / 2.0) * ds
        val normalizedX = ((matchCenterXOrig - width / 2.0) / (width / 2.0)).toFloat()
            .coerceIn(-1.0f, 1.0f)

        latestResult.set(TrackResult(normalizedX, confidence))

        if (confidence < AppConfig.VISUAL_CONFIDENCE_THRESHOLD) {
            AppLog.d(TAG, "Low confidence: ${"%.2f".format(confidence)}, " +
                "bestSAD=$bestSad, secondSAD=$secondBestSad, nX=${"%.3f".format(normalizedX)}")
        }
    }

    /**
     * 取得 Yaw 修正值（°/s）。
     *
     * 控制迴圈呼叫此方法，結果直接加到 yaw 指令上。
     * 沒選目標或信心度不足時回傳 0.0（不影響 GPS 控制）。
     *
     * @return Yaw 修正值（°/s），正 = 順時針，負 = 逆時針
     */
    fun getCorrection(): Double {
        if (!isTargetSelected) return 0.0

        val result = latestResult.get()
        if (result.confidence < AppConfig.VISUAL_CONFIDENCE_THRESHOLD) return 0.0

        val correction = result.normalizedX * AppConfig.VISUAL_YAW_GAIN
        return correction.coerceIn(-AppConfig.VISUAL_MAX_CORRECTION, AppConfig.VISUAL_MAX_CORRECTION)
    }

    /** 是否已選取目標 */
    fun hasTarget(): Boolean = isTargetSelected

    /** 最新信心度（供 UI 顯示） */
    fun getConfidence(): Float = latestResult.get().confidence

    /** 開啟選取等待窗（到達環繞起點時呼叫，等待使用者點擊選取目標） */
    fun beginSelectionWindow() {
        reset()
        waitingForSelection = true
        AppLog.d(TAG, "Selection window opened")
    }

    /** 關閉選取等待窗（逾時或已選取後呼叫） */
    fun endSelectionWindow() {
        waitingForSelection = false
        AppLog.d(TAG, "Selection window closed, hasTarget=${isTargetSelected}")
    }

    /** 重置所有狀態（任務結束或取消時呼叫） */
    fun reset() {
        isTargetSelected = false
        waitingForSelection = false
        templateData = null
        templateW = 0
        templateH = 0
        lastMatchX = -1
        lastMatchY = -1
        latestResult.set(TrackResult(0.0f, 0.0f))
        AppLog.d(TAG, "Reset")
    }

    // ========== 內部方法 ==========

    /**
     * 從原始模板降採樣（取每個 ds×ds 區塊的左上角像素）。
     * 不做平均是為了效能：取樣就夠了，SAD 對此不敏感。
     */
    private fun downsampleTemplate(src: ByteArray, srcW: Int, srcH: Int, ds: Int): ByteArray {
        val dstW = srcW / ds
        val dstH = srcH / ds
        val dst = ByteArray(dstW * dstH)
        for (row in 0 until dstH) {
            for (col in 0 until dstW) {
                dst[row * dstW + col] = src[(row * ds) * srcW + (col * ds)]
            }
        }
        return dst
    }

    /**
     * 計算模板與畫面區域的 SAD（Sum of Absolute Differences）。
     *
     * @param yPlane    NV21 完整幀數據（Y 平面在前）
     * @param frameW    畫面寬度（原始）
     * @param frameH    畫面高度（原始）
     * @param ds        降採樣倍率
     * @param tpl       降採樣後的模板
     * @param tplW      模板寬度（降採樣後）
     * @param tplH      模板高度（降採樣後）
     * @param candX     候選位置 X（降採樣座標）
     * @param candY     候選位置 Y（降採樣座標）
     * @return SAD 值（越小越相似）
     */
    private fun computeSad(
        yPlane: ByteArray, frameW: Int, frameH: Int, ds: Int,
        tpl: ByteArray, tplW: Int, tplH: Int,
        candX: Int, candY: Int
    ): Long {
        var sad = 0L
        val scaledFrameW = frameW / ds
        for (row in 0 until tplH) {
            val frameRow = candY + row
            if (frameRow < 0 || frameRow >= frameH / ds) continue
            val tplRowOffset = row * tplW
            for (col in 0 until tplW) {
                val frameCol = candX + col
                if (frameCol < 0 || frameCol >= scaledFrameW) continue
                // 降採樣像素：取原始 Y 平面中對應位置
                val frameIdx = (frameRow * ds) * frameW + (frameCol * ds)
                val diff = (yPlane[frameIdx].toInt() and 0xFF) - (tpl[tplRowOffset + col].toInt() and 0xFF)
                sad += if (diff < 0) -diff.toLong() else diff.toLong()
            }
        }
        return sad
    }
}
