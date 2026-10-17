package dji.sampleV5.aircraft.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GeoMath 單元測試 — Haversine 距離、方位角、目的地點計算 */
class GeoMathTest {

    companion object {
        private const val DELTA_M = 1.0        // 距離容差（公尺）
        private const val DELTA_DEG = 0.5      // 角度容差（度）
    }

    // ============================================================
    // haversine
    // ============================================================

    @Test
    fun `haversine — 相同點距離為零`() {
        val d = GeoMath.haversine(25.0, 121.0, 25.0, 121.0)
        assertEquals(0.0, d, DELTA_M)
    }

    @Test
    fun `haversine — 赤道經度 1 度約 111km`() {
        // 赤道上 1 度經度：EARTH_RADIUS_M * π/180 = 6371000 * 0.0174533 ≈ 111195m
        val d = GeoMath.haversine(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_195.0, d, DELTA_M)
    }

    @Test
    fun `haversine — 台北到新竹約 60km`() {
        // 台北 101: 25.0338, 121.5645
        // 新竹車站: 24.8016, 120.9717
        val d = GeoMath.haversine(25.0338, 121.5645, 24.8016, 120.9717)
        assertEquals(65_000.0, d, 5000.0)  // 5km 容差（城市邊界差異）
    }

    @Test
    fun `haversine — 極短距離（同棟樓不同座標）`() {
        // 相距約 10 公尺的兩個點
        val d = GeoMath.haversine(25.0, 121.0, 25.00009, 121.0)
        assertEquals(10.0, d, DELTA_M)
    }

    // ============================================================
    // calculateDestinationPoint
    // ============================================================

    @Test
    fun `calculateDestinationPoint — 向北 111km 緯度 +1°`() {
        val (lat, lng) = GeoMath.calculateDestinationPoint(0.0, 0.0, 0.0, 111_320.0)
        assertEquals(1.0, lat, DELTA_DEG)
        assertEquals(0.0, lng, DELTA_DEG)
    }

    @Test
    fun `calculateDestinationPoint — 向東 111km 經度 +1°（赤道）`() {
        val (lat, lng) = GeoMath.calculateDestinationPoint(0.0, 0.0, 90.0, 111_320.0)
        assertEquals(0.0, lat, DELTA_DEG)
        assertEquals(1.0, lng, DELTA_DEG)
    }

    @Test
    fun `calculateDestinationPoint — 向南再向北回到原點`() {
        val north = GeoMath.calculateDestinationPoint(25.0, 121.0, 0.0, 1000.0)
        val back = GeoMath.calculateDestinationPoint(north.first, north.second, 180.0, 1000.0)
        assertEquals(25.0, back.first, DELTA_DEG)
        assertEquals(121.0, back.second, DELTA_DEG)
    }

    // ============================================================
    // calculateBearing
    // ============================================================

    @Test
    fun `calculateBearing — 正北`() {
        val b = GeoMath.calculateBearing(0.0, 0.0, 1.0, 0.0)
        // 方位角約 0°（正北），因大地測量曲率可能略偏
        assertEquals(0.0, b, DELTA_DEG)
    }

    @Test
    fun `calculateBearing — 正東`() {
        val b = GeoMath.calculateBearing(0.0, 0.0, 0.0, 1.0)
        assertEquals(90.0, b, DELTA_DEG)
    }

    @Test
    fun `calculateBearing — 正南`() {
        val b = GeoMath.calculateBearing(1.0, 0.0, 0.0, 0.0)
        assertEquals(180.0, b, DELTA_DEG)
    }

    @Test
    fun `calculateBearing — 正西`() {
        val b = GeoMath.calculateBearing(0.0, 1.0, 0.0, 0.0)
        assertEquals(270.0, b, DELTA_DEG)
    }

    @Test
    fun `calculateBearing — 結果一定在 0~360 範圍`() {
        // 各種組合都不會超出範圍
        val bearings = listOf(
            GeoMath.calculateBearing(25.0, 121.0, 24.0, 120.0),  // 西南
            GeoMath.calculateBearing(25.0, 121.0, 26.0, 122.0),  // 東北
            GeoMath.calculateBearing(25.0, 121.0, 25.0, 120.0),  // 正西
            GeoMath.calculateBearing(25.0, 121.0, 25.0, 122.0),  // 正東
        )
        for (b in bearings) {
            assertTrue("bearing $b out of range", b in 0.0..360.0)
        }
    }

    // ============================================================
    // 整合測試 — 計算出的目標點距離應等於輸入距離
    // ============================================================

    @Test
    fun `整合 — 位移後計算距離應等於輸入距離`() {
        val startLat = 25.0
        val startLng = 121.0
        val distance = 500.0  // 500m
        val bearing = 45.0

        val (endLat, endLng) = GeoMath.calculateDestinationPoint(startLat, startLng, bearing, distance)
        val roundTrip = GeoMath.haversine(startLat, startLng, endLat, endLng)
        assertEquals(distance, roundTrip, DELTA_M)
    }
}
