package dji.sampleV5.aircraft.virtualstick

/** 螢幕虛擬搖桿觸控事件監聽器 */
interface OnScreenJoystickListener {
    /**
     * 搖桿被觸控時呼叫。
     *
     * @param joystick 被觸控的搖桿
     * @param pX       旋鈕的 x 座標，範圍 -1（左）~ 1（右）
     * @param pY       旋鈕的 y 座標，範圍 -1（下）~ 1（上）
     */
    fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float)
}