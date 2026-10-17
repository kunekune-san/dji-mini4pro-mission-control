package dji.sampleV5.aircraft.util

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** GPS 座標距離、方位角與目標點計算工具 */
object GeoMath {

    private const val EARTH_RADIUS_M = 6371000.0

    /** Haversine 公式：計算兩 GPS 座標間的距離（公尺） */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = (sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)).coerceIn(0.0, 1.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /**
     * 已知起點、方位角、距離，計算目標座標。
     *
     * @param lat       起點緯度
     * @param lng       起點經度
     * @param bearingDeg 方位角（度，0°=北，順時針）
     * @param distanceM 距離（公尺）
     * @return 目標座標 (緯度, 經度)
     */
    fun calculateDestinationPoint(lat: Double, lng: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val brngRad = Math.toRadians(bearingDeg)
        val lat1Rad = Math.toRadians(lat)
        val lon1Rad = Math.toRadians(lng)
        val angularDist = distanceM / EARTH_RADIUS_M

        val lat2Rad = asin(
            sin(lat1Rad) * cos(angularDist) +
            cos(lat1Rad) * sin(angularDist) * cos(brngRad)
        )
        val lon2Rad = lon1Rad + atan2(
            sin(brngRad) * sin(angularDist) * cos(lat1Rad),
            cos(angularDist) - sin(lat1Rad) * sin(lat2Rad)
        )
        return Pair(Math.toDegrees(lat2Rad), Math.toDegrees(lon2Rad))
    }

    /** 計算兩點間的方位角（度，0°=北，順時針） */
    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        var bearing = Math.toDegrees(atan2(y, x))
        if (bearing < 0) bearing += 360
        return bearing
    }
}
