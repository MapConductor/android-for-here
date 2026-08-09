package com.mapconductor.here.polygon

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HERE の穴あきポリゴンの塗り分解のテスト。
 *
 * HERE はピースを 1 枚ずつ不透明に塗るので、合成は和集合になる。したがって
 * 「どの点も 2 枚以上のピースに覆われない（＝互いに素）」ことと
 * 「穴の内側はどのピースにも覆われない」ことが要件。
 */
class HerePolygonFillDecompositionTest {
    private val outer =
        listOf(
            GeoPoint(latitude = 44.2, longitude = 140.0),
            GeoPoint(latitude = 44.2, longitude = 142.8),
            GeoPoint(latitude = 42.0, longitude = 142.8),
            GeoPoint(latitude = 42.0, longitude = 140.0),
        )

    private val hole1 =
        listOf(
            GeoPoint(latitude = 43.100869, longitude = 141.352909),
            GeoPoint(latitude = 43.044443, longitude = 141.411895),
            GeoPoint(latitude = 43.050601, longitude = 141.306563),
        )

    /** example-app / reactnative-basic の Hole Polygon サンプルで穴の頂点を南へドラッグした状態。 */
    private val hole2Dragged =
        listOf(
            GeoPoint(latitude = 42.93169952829921, longitude = 141.39802941496535),
            GeoPoint(latitude = 43.038285, longitude = 141.333247),
            GeoPoint(latitude = 43.049062, longitude = 141.286901),
        )

    private val hole2Initial =
        listOf(
            GeoPoint(latitude = 43.060351, longitude = 141.319905),
            GeoPoint(latitude = 43.038285, longitude = 141.333247),
            GeoPoint(latitude = 43.049062, longitude = 141.286901),
        )

    private fun contains(
        ring: List<GeoPointInterface>,
        lat: Double,
        lng: Double,
    ): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.latitude > lat) != (b.latitude > lat)) {
                val x = a.longitude + ((lat - a.latitude) / (b.latitude - a.latitude)) * (b.longitude - a.longitude)
                if (lng < x) inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun coverage(
        pieces: List<List<GeoPointInterface>>,
        lat: Double,
        lng: Double,
    ): Int = pieces.count { contains(it, lat, lng) }

    private fun centroid(ring: List<GeoPointInterface>): GeoPointInterface =
        GeoPoint(
            latitude = ring.sumOf { it.latitude } / ring.size,
            longitude = ring.sumOf { it.longitude } / ring.size,
        )

    /** bbox を走査して「覆い数」の最大値と、穴の外なのに塗られない点の有無を調べる。 */
    private fun scan(
        pieces: List<List<GeoPointInterface>>,
        holes: List<List<GeoPointInterface>>,
        steps: Int = 90,
    ): Triple<Int, Int, Int> {
        var maxCoverage = 0
        var holeCovered = 0
        var insideUncovered = 0
        for (i in 1 until steps) {
            for (j in 1 until steps) {
                val lat = 42.0 + 2.2 * i / steps
                val lng = 140.0 + 2.8 * j / steps
                val count = coverage(pieces, lat, lng)
                if (count > maxCoverage) maxCoverage = count
                val inHole = holes.any { contains(it, lat, lng) }
                if (inHole && count > 0) holeCovered++
                if (!inHole && count == 0) insideUncovered++
            }
        }
        return Triple(maxCoverage, holeCovered, insideUncovered)
    }

    @Test
    fun draggedHoleStaysOpen() {
        val holes = listOf(hole1, hole2Dragged)
        val pieces = decomposePolygonWithHolesIntoTrapezoids(outer, holes)
        assertNotNull(pieces)
        pieces!!

        val h1 = centroid(hole1)
        val h2 = centroid(hole2Dragged)
        assertEquals("hole1 を覆うピース枚数", 0, coverage(pieces, h1.latitude, h1.longitude))
        assertEquals("hole2 を覆うピース枚数", 0, coverage(pieces, h2.latitude, h2.longitude))

        val (maxCoverage, holeCovered, insideUncovered) = scan(pieces, holes)
        assertEquals("ピースが重なっている", 1, maxCoverage)
        assertEquals("穴の内側が塗られている点がある", 0, holeCovered)
        assertEquals("塗られるべき点が塗られていない", 0, insideUncovered)
    }

    @Test
    fun initialHolesStayOpen() {
        val holes = listOf(hole1, hole2Initial)
        val pieces = decomposePolygonWithHolesIntoTrapezoids(outer, holes)
        assertNotNull(pieces)

        val (maxCoverage, holeCovered, insideUncovered) = scan(pieces!!, holes)
        assertEquals("ピースが重なっている", 1, maxCoverage)
        assertEquals("穴の内側が塗られている点がある", 0, holeCovered)
        assertEquals("塗られるべき点が塗られていない", 0, insideUncovered)
    }

    @Test
    fun singleHoleStaysOpen() {
        val holes = listOf(hole1)
        val pieces = decomposePolygonWithHolesIntoTrapezoids(outer, holes)
        assertNotNull(pieces)

        val (maxCoverage, holeCovered, insideUncovered) = scan(pieces!!, holes)
        assertEquals("ピースが重なっている", 1, maxCoverage)
        assertEquals("穴の内側が塗られている点がある", 0, holeCovered)
        assertEquals("塗られるべき点が塗られていない", 0, insideUncovered)
    }

    @Test
    fun piecesAreConvexQuads() {
        val pieces = decomposePolygonWithHolesIntoTrapezoids(outer, listOf(hole1, hole2Dragged))
        assertNotNull(pieces)
        assertTrue("ピースが 1 枚も出ていない", pieces!!.isNotEmpty())
        assertTrue("台形以外のピースがある", pieces.all { it.size == 4 })
    }

    @Test
    fun tooManySlabsFallsBack() {
        // 頂点が多い（＝スラブが多い）外周では上限を超えて null を返し、
        // 呼び出し側が従来の分割方式へ退避できること。
        val manyVertices =
            (0 until 300).map { index ->
                GeoPoint(latitude = 42.0 + index * 0.001, longitude = 140.0 + (index % 2) * 0.5)
            }
        assertNull(decomposePolygonWithHolesIntoTrapezoids(manyVertices, listOf(hole1), maxPieces = 64))
    }
}
