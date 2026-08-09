package com.mapconductor.here.polygon

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface

/**
 * 「外周 − 穴」を、互いに重ならない台形（凸四角形）の集合へ分解する。
 *
 * HERE のレンダラはピースを 1 枚ずつ不透明な MapPolygon として塗る。つまり合成は
 * **和集合**であって偶奇ではない。コア共通の
 * [com.mapconductor.core.polygon.splitPolygonWithHolesIntoSimpleRings] は
 * 「偶奇合成で正しい」ピース群を返す契約で、穴の橋が別の穴の輪郭へ着地する配置では
 * 2 枚のピースが穴の上で重なることがある。偶奇なら打ち消し合って穴に見えるが、
 * 不透明に塗ると穴が塗り潰されてしまう（札幌サンプルで穴の頂点をドラッグすると再現）。
 *
 * ここでは重なりが原理的に起きない分解を使う: 全リングの頂点緯度で水平スラブに切り、
 * 各スラブの中央緯度で交点を求めて偶奇で内側区間を取り、区間ごとに台形を 1 枚出す。
 * スラブ内には頂点が存在しないため交点は緯度に対して線形に動き、区間はちょうど台形になる。
 * 出力は必ず互いに素で、しかも凸なので HERE の三角形分割が確実に塗れる。
 *
 * 測地線ポリゴンは補間で頂点が大量に増え、スラブ数＝ポリゴン枚数も増える。
 * [maxPieces] を超える場合は null を返し、呼び出し側が従来の分割方式へ退避できるようにする。
 */
internal fun decomposePolygonWithHolesIntoTrapezoids(
    outer: List<GeoPointInterface>,
    holes: List<List<GeoPointInterface>>,
    maxPieces: Int = 512,
): List<List<GeoPointInterface>>? {
    if (outer.size < 3) return emptyList()

    val rings = buildList {
        add(outer)
        holes.filter { it.size >= 3 }.forEach { add(it) }
    }

    val latitudes = rings.flatMap { ring -> ring.map { it.latitude } }.distinct().sorted()
    if (latitudes.size < 2) return emptyList()
    // スラブ数 × 想定区間数がすぐ上限を超えるようなら、この方式は使わない。
    if (latitudes.size - 1 > maxPieces) return null

    val pieces = mutableListOf<List<GeoPointInterface>>()

    for (slab in 0 until latitudes.size - 1) {
        val south = latitudes[slab]
        val north = latitudes[slab + 1]
        if (north <= south) continue
        val middle = (south + north) / 2.0

        // 中央緯度を横切るエッジ。スラブ内に頂点は無いので、これらは必ずスラブを貫く。
        val crossings = mutableListOf<SlabEdge>()
        for (ring in rings) {
            for (index in ring.indices) {
                val a = ring[index]
                val b = ring[(index + 1) % ring.size]
                if ((a.latitude > middle) == (b.latitude > middle)) continue
                crossings.add(SlabEdge(a, b))
            }
        }
        if (crossings.size < 2) continue

        crossings.sortBy { it.longitudeAt(middle) }

        // 偶奇規則: 交点を 2 本ずつ組にした区間が内側。
        var index = 0
        while (index + 1 < crossings.size) {
            val left = crossings[index]
            val right = crossings[index + 1]
            val quad =
                listOf(
                    GeoPoint(latitude = south, longitude = left.longitudeAt(south)),
                    GeoPoint(latitude = south, longitude = right.longitudeAt(south)),
                    GeoPoint(latitude = north, longitude = right.longitudeAt(north)),
                    GeoPoint(latitude = north, longitude = left.longitudeAt(north)),
                )
            if (hasArea(quad)) {
                pieces.add(quad)
                if (pieces.size > maxPieces) return null
            }
            index += 2
        }
    }

    return pieces
}

/** スラブを貫くエッジ。任意の緯度での経度を線形に返す。 */
private class SlabEdge(
    private val a: GeoPointInterface,
    private val b: GeoPointInterface,
) {
    fun longitudeAt(latitude: Double): Double {
        val span = b.latitude - a.latitude
        if (span == 0.0) return a.longitude
        val t = (latitude - a.latitude) / span
        return a.longitude + t * (b.longitude - a.longitude)
    }
}

/** 潰れた（幅ゼロの）台形を捨てる。 */
private fun hasArea(quad: List<GeoPointInterface>): Boolean {
    var twiceArea = 0.0
    for (index in quad.indices) {
        val a = quad[index]
        val b = quad[(index + 1) % quad.size]
        twiceArea += a.longitude * b.latitude - b.longitude * a.latitude
    }
    return kotlin.math.abs(twiceArea) > 1e-14
}
