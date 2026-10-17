package dji.sampleV5.aircraft.mission

import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam

/**
 * 環繞飛行控制入口 — 委派給 OrbitModeA/B/C/D 執行具體模式。
 *
 * 共用輔助函式已提取至 [OrbitHelpers]。
 * 各模式實作已拆分至獨立檔案：
 * - [OrbitModeA] A 模式原地旋轉錄影
 * - [OrbitModeB] B 模式環繞錄影
 * - [OrbitModeC] C 模式螺旋上升
 * - [OrbitModeD] D 模式擴展螺旋上升
 */
internal object OrbitController {

    /** A 模式：原地 360° yaw 旋轉錄影 */
    suspend fun executeInPlaceRotation(
        gimbalPitch: Double,
        param: VirtualStickFlightControlParam,
        targetAlt: Double = 0.0,
        orbitSpeedMultiplier: Double = 1.0
    ) = OrbitModeA.execute(gimbalPitch, param, targetAlt, orbitSpeedMultiplier)

    /** B 模式：以指定半徑順時針環繞目標 360° 錄影 */
    suspend fun executeOrbitPhase(
        centerLat: Double,
        centerLng: Double,
        orbitAlt: Double,
        orbitRadius: Double,
        gimbalPitch: Double,
        param: VirtualStickFlightControlParam,
        orbitSpeedMultiplier: Double = 1.0
    ) = OrbitModeB.execute(centerLat, centerLng, orbitAlt, orbitRadius, gimbalPitch, param, orbitSpeedMultiplier)

    /** C 模式：螺旋上升環繞錄影，從 zMin 上升到 zMax，完成 N 圈旋轉。
     *  [finalHeading] 螺旋結束後繼續繞行至此方位角再抬升雲台（0°=北順時針），null = 不繞行直接拉平。 */
    suspend fun executeSpiralAscent(
        centerLat: Double,
        centerLng: Double,
        zMin: Double,
        zMax: Double,
        orbitRadius: Double,
        turns: Int,
        param: VirtualStickFlightControlParam,
        orbitSpeedMultiplier: Double = 1.0,
        finalHeading: Double? = null
    ) = OrbitModeC.execute(centerLat, centerLng, zMin, zMax, orbitRadius, turns, param, orbitSpeedMultiplier, finalHeading)

    /** D 模式：擴展螺旋上升環繞錄影，半徑從 rStart 線性增長到 rEnd，完成 N 圈旋轉 */
    suspend fun executeExpandingSpiral(
        centerLat: Double,
        centerLng: Double,
        zMin: Double,
        zMax: Double,
        rStart: Double,
        rEnd: Double,
        turns: Int,
        param: VirtualStickFlightControlParam,
        orbitSpeedMultiplier: Double = 1.0,
        finalHeading: Double? = null
    ) = OrbitModeD.execute(centerLat, centerLng, zMin, zMax, rStart, rEnd, turns, param, orbitSpeedMultiplier, finalHeading)
}
