package dji.sampleV5.aircraft.mission

import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode

/** VirtualStick 控制參數工廠 */
object VirtualStickParams {

    /** 建立 GROUND + VELOCITY 模式的 VirtualStick 控制參數（所有軸歸零） */
    fun groundVelocityParams(): VirtualStickFlightControlParam =
        VirtualStickFlightControlParam().apply {
            rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
        }
}
