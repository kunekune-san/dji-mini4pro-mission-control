package dji.sampleV5.aircraft.virtualstick

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.View.OnTouchListener
import dji.sampleV5.aircraft.R
import dji.v5.utils.common.LogUtils
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 螢幕虛擬搖桿（以 SurfaceView 繪製）。
 *
 * 透過觸控事件更新搖桿旋鈕位置，並以 -1~1 的歸一化座標回報給 [OnScreenJoystickListener]，
 * 供 VirtualStick 控制使用。可設定自動置中（放開後旋鈕回到中央）。
 */
class OnScreenJoystick(context: Context?, attrs: AttributeSet) : SurfaceView(context, attrs),
    SurfaceHolder.Callback, OnTouchListener {
    private var tag = LogUtils.getTag(this)
    private var mJoystick: Bitmap? = null
    private lateinit var mHolder: SurfaceHolder
    private var mKnobBounds: Rect? = null
    private var mThread: JoystickThread? = null
    private var mKnobX = 0
    private var mKnobY = 0
    private var mKnobSize = 0
    private var mBackgroundSize = 0
    private var mRadius = 0f
    private var mJoystickListener: OnScreenJoystickListener? = null
    private var isAutoCentering = true

    private fun initGraphics() {
        val res = context.resources
        mJoystick = BitmapFactory.decodeResource(res, R.mipmap.joystick)
    }

    private fun initBounds(pCanvas: Canvas?) {
        mBackgroundSize = pCanvas!!.height
        mKnobSize = (mBackgroundSize * 0.4f).roundToInt()
        mKnobBounds = Rect()
        mRadius = mBackgroundSize * 0.5f
        mKnobX = ((mBackgroundSize - mKnobSize) * 0.5f).roundToInt()
        mKnobY = ((mBackgroundSize - mKnobSize) * 0.5f).roundToInt()
    }

    private fun init() {
        mHolder = holder
        mHolder.addCallback(this)
        setZOrderOnTop(true)
        mHolder.setFormat(PixelFormat.TRANSPARENT)
        setOnTouchListener(this)
        isEnabled = true
        isAutoCentering = true
    }

    /** 設定搖桿觸控事件監聽器 */
    fun setJoystickListener(
        pJoystickListener: OnScreenJoystickListener?
    ) {
        mJoystickListener = pJoystickListener
    }

    override fun surfaceCreated(arg0: SurfaceHolder) {
        mThread?.setRunning(false)
        mThread = JoystickThread()
        mThread?.setRunning(true)
        mThread?.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // 暫無實作
    }

    override fun surfaceDestroyed(arg0: SurfaceHolder) {
        mThread?.setRunning(false)
        var retry = true
        while (retry) {
            try {
                // 結束繪圖執行緒
                mThread?.join()
                retry = false
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                LogUtils.e(tag, e.message)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mThread?.setRunning(false)
    }

    fun doDraw(pCanvas: Canvas?) {
        if (pCanvas == null) {
            return
        }
        // 重置畫布
        pCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        if (mKnobBounds == null) {
            initBounds(pCanvas)
        }
        mKnobBounds!![mKnobX, mKnobY, mKnobX + mKnobSize] = mKnobY + mKnobSize
        pCanvas.drawBitmap(mJoystick!!, null, mKnobBounds!!, null)
    }

    override fun onTouch(arg0: View, pEvent: MotionEvent): Boolean {
        val x = pEvent.x
        val y = pEvent.y
        when (pEvent.action) {
            MotionEvent.ACTION_UP -> if (isAutoCentering) {
                mKnobX = ((mBackgroundSize - mKnobSize) * 0.5f).roundToInt()
                mKnobY = ((mBackgroundSize - mKnobSize) * 0.5f).roundToInt()
            }
            else ->
                // 檢查座標是否在範圍內；若超出則將旋鈕移至範圍內最接近的座標
                if (checkBounds(x, y)) {
                    mKnobX = (x - mKnobSize * 0.5f).roundToInt()
                    mKnobY = (y - mKnobSize * 0.5f).roundToInt()
                } else {
                    val angle = Math.atan2((y - mRadius).toDouble(), (x - mRadius).toDouble())
                    mKnobX =
                        ((mRadius + (mRadius - mKnobSize * 0.5f) * cos(angle)).roundToInt() - mKnobSize * 0.5f).toInt()
                    mKnobY =
                        ((mRadius + (mRadius - mKnobSize * 0.5f) * sin(angle)).roundToInt() - mKnobSize * 0.5f).toInt()
                }
        }
        pushTouchEvent()
        return true
    }

    private fun checkBounds(pX: Float, pY: Float): Boolean {
        return (mRadius - pX).toDouble().pow(2.0) + (mRadius - pY).toDouble().pow(2.0) <= (mRadius - mKnobSize * 0.5f).toDouble().pow(2.0)
    }

    private fun pushTouchEvent() {
        if (mJoystickListener != null) {
            mJoystickListener!!.onTouch(
                this,
                (0.5f - mKnobX.toFloat() / (mBackgroundSize - mKnobSize)) * -2,
                (0.5f - mKnobY.toFloat() / (mBackgroundSize - mKnobSize)) * 2
            )
        }
    }

    private inner class JoystickThread : Thread() {
        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean {
            return running.get()
        }

        fun setRunning(pRunning: Boolean) {
            running.set(pRunning)
        }

        override fun run() {
            while (isRunning()) {
                // 將所有內容繪製到畫布
                var canvas: Canvas? = null
                try {
                    canvas = mHolder.lockCanvas(null)
                    synchronized(mHolder) {
                        doDraw(canvas)
                    }
                } catch (e: Exception) {
                    LogUtils.e(tag, e.message)
                } finally {
                    if (canvas != null) {
                        mHolder.unlockCanvasAndPost(canvas)
                    }
                }
            }
        }
    }

    init {
        initGraphics()
        init()
    }
}