package dji.sampleV5.aircraft.util

import android.content.Context
import android.graphics.Point
import android.graphics.Rect

/** 螢幕密度與單位換算工具 */
object DensityUtil {
    /** dp 轉 px */
    @JvmStatic
	fun dip2px(context: Context, dpValue: Float): Int {
        val scale = context.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }

    /** px 轉 dp */
    fun px2dip(context: Context, pxValue: Float): Int {
        val scale = context.resources.displayMetrics.density
        return (pxValue / scale + 0.5f).toInt()
    }

    /** 取得螢幕解析度（寬×高） */
    fun getScreenMetrics(context: Context): Point {
        val dm = context.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        return Point(screenWidth, screenHeight)
    }

    /** 以 dp 邊界建立置中矩形（px 座標） */
    fun createCenterRect(context: Context, rect: Rect): Rect {
        val left = dip2px(context, rect.left.toFloat())
        val top = dip2px(context, rect.top.toFloat())
        val right = getScreenMetrics(context).x - dip2px(context, rect.right.toFloat())
        val bottom = getScreenMetrics(context).y - dip2px(context, rect.bottom.toFloat())
        return Rect(left, top, right, bottom)
    }

    /** px 轉 sp */
    fun px2sp(context: Context, pxValue: Float): Int {
        val scaleDensity = context.resources.displayMetrics.scaledDensity //縮放密度
        return (pxValue / scaleDensity + 0.5f).toInt()
    }

    /** sp 轉 px */
    fun sp2px(context: Context, spValue: Float): Int {
        val scaleDensity = context.resources.displayMetrics.scaledDensity
        return (spValue * scaleDensity + 0.5f).toInt()
    }
}