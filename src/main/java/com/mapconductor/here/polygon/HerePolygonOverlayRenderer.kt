package com.mapconductor.here.polygon

import androidx.compose.ui.graphics.toArgb
import com.here.sdk.core.Color
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.GeoPolygon
import com.here.sdk.mapview.MapPolygon
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.normalizeLng
import com.mapconductor.core.polygon.AbstractPolygonOverlayRenderer
import com.mapconductor.core.polygon.PolygonEntityInterface
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polygon.splitPolygonWithHolesIntoSimpleRings
import com.mapconductor.core.polygon.unionHoles
import com.mapconductor.here.HereActualPolygon
import com.mapconductor.here.HereViewHolder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "HerePolygonRaster"

/**
 * HERE の穴付きポリゴンは「分割方式」で描画する（ios-for-here と同方式）。
 *
 * 実機検証の結果、HERE SDK の MapPolygon は
 * - `GeoPolygon(vertices, innerBoundaries)` の穴を描画で正しく抜かない
 * - ブリッジ（keyhole）方式の凹リングも三角形ファン相当の塗りで自己重複し、
 *   半透明色では穴領域が二重ブレンドされて濃く塗られる
 * ため、穴を持つ 1 枚のリングでは表現できない。
 *
 * そこで「穴を持たないピース群」へ分解し、1 ピース 1 枚の MapPolygon として塗る。
 * 輪郭（外周・各穴）は stroke-only の MapPolygon を重ねる。
 *
 * ピースを 1 枚ずつ不透明に塗る以上、**合成は和集合**であってピースは互いに素でなければ
 * ならない。重なると半透明色が二重に乗るだけでなく、穴の上で重なった場合はその穴が
 * 塗り潰されてしまう。既定の [decomposePolygonWithHolesIntoTrapezoids]（台形分解）は
 * これを原理的に保証する。コア共通の [splitPolygonWithHolesIntoSimpleRings] は
 * 「偶奇合成で正しい」までしか保証せず重なりうるため、台形分解が使えないとき
 * （測地線補間で枚数が膨らむ場合）の退避先に留める。
 *
 * - 穴なし: MapPolygon（fill + outline）1 枚。
 * - 穴あり: 分解ピースごとの fill-only MapPolygon ＋ 外周・各穴の stroke-only MapPolygon。
 *
 * 以前は塗りをタイル画像へ焼き、LocalTileServer 経由でラスタレイヤとして重ねる
 * 「ラスタマスク方式」だった。分割方式に置き換えたのは、ベクタのまま描けるため拡大時に
 * ぼけず、タイルサーバ／レイヤ生成の機構も要らなくなるため。焼き付けに使っていた
 * コアの PolygonRasterTileRenderer は利用者がここだけだったので、あわせて削除した。
 */
class HerePolygonOverlayRenderer(
    override val holder: HereViewHolder,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : AbstractPolygonOverlayRenderer<HereActualPolygon>() {
    override suspend fun removePolygon(entity: PolygonEntityInterface<HereActualPolygon>) {
        removeMapPolygons(entity.polygon)
    }

    override suspend fun createPolygon(state: PolygonState): HereActualPolygon? {
        val resolved = resolveHoles(state)
        Log.d(TAG, "createPolygon: id=${state.id}, holes=${resolved.holes.size}, points=${resolved.points.size}")
        val polygons = buildMapPolygons(resolved)
        addMapPolygons(polygons)
        return polygons
    }

    /**
     * 複数の穴が重なっている場合は結合（union）して重複を解消する。
     * 他プロバイダ（ArcGIS/Mapbox/MapLibre）と同じ [unionHoles] を用いる。
     */
    private suspend fun resolveHoles(state: PolygonState): PolygonState =
        if (state.holes.size > 1) {
            withContext(Dispatchers.Default) { state.unionHoles() }
        } else {
            state
        }

    override suspend fun updatePolygonProperties(
        polygon: HereActualPolygon,
        current: PolygonEntityInterface<HereActualPolygon>,
        prev: PolygonEntityInterface<HereActualPolygon>,
    ): HereActualPolygon? =
        withContext(coroutine.coroutineContext) {
            val finger = current.fingerPrint
            val prevFinger = prev.fingerPrint

            val resolved = resolveHoles(current.state)

            val geometryChanged =
                finger.points != prevFinger.points ||
                    finger.holes != prevFinger.holes ||
                    finger.geodesic != prevFinger.geodesic

            if (geometryChanged) {
                if (current.state.holes.isEmpty() && prev.state.holes.isEmpty() && polygon.size == 1) {
                    val geometry = GeoPolygon(toRing(resolved.points, resolved.geodesic))
                    withContext(Dispatchers.Main) {
                        polygon.first().geometry = geometry
                    }
                } else {
                    val newPolygons = buildMapPolygons(resolved)
                    withContext(Dispatchers.Main) {
                        polygon.forEach { holder.map.removeMapPolygon(it) }
                        newPolygons.forEach { holder.map.addMapPolygon(it) }
                    }
                    return@withContext newPolygons
                }
            }

            val styleChanged =
                finger.fillColor != prevFinger.fillColor ||
                    finger.strokeColor != prevFinger.strokeColor ||
                    finger.strokeWidth != prevFinger.strokeWidth

            if (current.state.holes.isNotEmpty()) {
                // 穴ありは塗り（分割ピース）と輪郭が別ポリゴンに分かれているため、
                // 色・線幅は個別更新せずまとめて作り直す。ios-for-here と同じ判断。
                if (styleChanged) {
                    val newPolygons = buildMapPolygons(resolved)
                    withContext(Dispatchers.Main) {
                        polygon.forEach { holder.map.removeMapPolygon(it) }
                        newPolygons.forEach { holder.map.addMapPolygon(it) }
                    }
                    return@withContext newPolygons
                }
            } else {
                if (finger.strokeColor != prevFinger.strokeColor) {
                    val stroke = Color.valueOf(current.state.strokeColor.toArgb())
                    polygon.forEach { it.outlineColor = stroke }
                }
                if (finger.strokeWidth != prevFinger.strokeWidth) {
                    val width =
                        ResourceProvider.dpToPx(
                            current.state.strokeWidth,
                        )
                    polygon.forEach { it.outlineWidth = width }
                }
                if (finger.fillColor != prevFinger.fillColor) {
                    val fill = Color.valueOf(current.state.fillColor.toArgb())
                    polygon.forEach { it.fillColor = fill }
                }
            }
            if (finger.zIndex != prevFinger.zIndex) {
                polygon.forEach { it.drawOrder = current.state.zIndex.coerceIn(0, 511) }
            }

            polygon
        }

    private fun buildMapPolygons(state: PolygonState): HereActualPolygon =
        if (state.holes.isEmpty()) {
            Log.d(TAG, "No holes, using simple polygon")
            listOf(createMapPolygon(state, GeoPolygon(toRing(state.points, state.geodesic))))
        } else {
            Log.d(TAG, "Has holes, using split rings for fill")
            val strokeColor = Color.valueOf(state.strokeColor.toArgb())
            val strokeWidth = ResourceProvider.dpToPx(state.strokeWidth)
            val transparentFill = Color.valueOf(0f, 0f, 0f, 0f)
            val fillColor = Color.valueOf(state.fillColor.toArgb())
            val order = state.zIndex.coerceIn(0, 511)

            buildList {
                // 塗り: 穴を持たないピース群へ分解し、1 ピース 1 枚で塗る。
                // 輪郭は付けない（ピース境界には分解で生じた継ぎ目が含まれるため）。
                //
                // 既定は台形分解。1 枚ずつ不透明に塗る以上ピースは互いに素でなければならず、
                // それを原理的に保証できるのはこちらだけ（理由は
                // [decomposePolygonWithHolesIntoTrapezoids] のコメント参照）。
                // 測地線補間で頂点が増えて枚数が上限を超えた場合だけ、従来の分割方式へ退避する。
                val outerGeo = toGeoRing(state.points, state.geodesic)
                val holeGeos = state.holes.map { toGeoRing(it, state.geodesic) }
                val pieces =
                    decomposePolygonWithHolesIntoTrapezoids(outerGeo, holeGeos)
                        ?: splitPolygonWithHolesIntoSimpleRings(outerGeo, holeGeos).also {
                            Log.d(TAG, "trapezoid decomposition too large; fell back to split rings")
                        }
                Log.d(TAG, "fill decomposed into ${pieces.size} pieces")
                for (piece in pieces) {
                    val coords = piece.map { GeoCoordinates(it.latitude, it.longitude) }
                    if (coords.size < 3) continue
                    add(MapPolygon(GeoPolygon(coords), fillColor).apply { drawOrder = order })
                }
                // 輪郭: 外周 + 各穴（透明 fill の stroke-only）
                add(
                    MapPolygon(
                        GeoPolygon(toRing(state.points, state.geodesic)),
                        transparentFill,
                        strokeColor,
                        strokeWidth,
                    ).apply { drawOrder = order },
                )
                state.holes.forEach { hole ->
                    val holeRing = toRing(hole, state.geodesic)
                    if (holeRing.size >= 3) {
                        add(
                            MapPolygon(
                                GeoPolygon(holeRing),
                                transparentFill,
                                strokeColor,
                                strokeWidth,
                            ).apply { drawOrder = order },
                        )
                    }
                }
            }
        }

    private suspend fun addMapPolygons(polygons: HereActualPolygon) {
        withContext(Dispatchers.Main) {
            polygons.forEach { holder.map.addMapPolygon(it) }
        }
    }

    private suspend fun removeMapPolygons(polygons: HereActualPolygon) {
        withContext(Dispatchers.Main) {
            polygons.forEach { holder.map.removeMapPolygon(it) }
        }
    }

    private fun createMapPolygon(
        state: PolygonState,
        geoPolygon: GeoPolygon,
    ): MapPolygon {
        val outlineWidth = ResourceProvider.dpToPx(state.strokeWidth)
        return MapPolygon(
            geoPolygon,
            Color.valueOf(state.fillColor.toArgb()),
            Color.valueOf(state.strokeColor.toArgb()),
            outlineWidth,
        ).apply { drawOrder = state.zIndex.coerceIn(0, 511) }
    }

    private fun toRing(
        points: List<GeoPointInterface>,
        geodesic: Boolean,
    ): List<GeoCoordinates> =
        (
            if (geodesic) {
                com.mapconductor.core.spherical
                    .WGS84Geodesic
                    .createInterpolatePoints(points)
            } else {
                points
            }
        ).map { GeoCoordinates(it.latitude, normalizeLng(it.longitude)) }
            .let { pts -> if (pts.size >= 2 && pts.first() == pts.last()) pts.dropLast(1) else pts }

    /** [toRing] と同じ補間・正規化を、分割へ渡すため GeoPointInterface のまま返す。 */
    private fun toGeoRing(
        points: List<GeoPointInterface>,
        geodesic: Boolean,
    ): List<GeoPointInterface> =
        (
            if (geodesic) {
                com.mapconductor.core.spherical
                    .WGS84Geodesic
                    .createInterpolatePoints(points)
            } else {
                points
            }
        ).map { GeoPoint(latitude = it.latitude, longitude = normalizeLng(it.longitude)) }

    private fun boundsOf(points: List<GeoPointInterface>): GeoRectBounds? {
        if (points.isEmpty()) return null
        val b = GeoRectBounds()
        points.forEach { b.extend(it) }
        // Ensure non-zero span to avoid division issues; pad by a tiny epsilon.
        val span = b.toSpan()
        if (span == null) return b
        val padLat = if (span.latitude == 0.0) 1e-6 else 0.0
        val padLon = if (span.longitude == 0.0) 1e-6 else 0.0
        return if (padLat != 0.0 || padLon != 0.0) b.expandedByDegrees(padLat, padLon) else b
    }

    private fun safeId(id: String): String =
        id
            .map { ch ->
                when {
                    ch.isLetterOrDigit() -> ch
                    ch == '-' || ch == '_' || ch == '.' -> ch
                    else -> '_'
                }
            }.joinToString("")
}
