package com.mapconductor.here.polygon

import androidx.compose.ui.graphics.toArgb
import com.here.sdk.core.Color
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.GeoPolygon
import com.here.sdk.mapview.MapPolygon
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.normalizeLng
import com.mapconductor.core.polygon.AbstractPolygonOverlayRenderer
import com.mapconductor.core.polygon.PolygonEntityInterface
import com.mapconductor.core.polygon.PolygonRasterTileRenderer
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polygon.unionHoles
import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.raster.TileScheme
import com.mapconductor.core.tileserver.LocalTileServer
import com.mapconductor.core.tileserver.TileServerRegistry
import com.mapconductor.here.HereActualPolygon
import com.mapconductor.here.HereViewHolder
import com.mapconductor.here.raster.HereRasterLayerController
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val TAG = "HerePolygonRaster"

/**
 * HERE SDK does not reliably render polygon holes (inner boundaries) for world-mask polygons.
 *
 * For polygons with holes:
 * - Part 1 (outside a hole-bounding rectangle): rendered via an internal raster tile layer.
 * - Part 2 (inside the hole-bounding rectangle, excluding holes): triangulated and rendered as polygons.
 *
 * For polygons without holes:
 * - Render as a single polygon.
 */
class HerePolygonOverlayRenderer(
    override val holder: HereViewHolder,
    private val rasterLayerController: HereRasterLayerController,
    private val tileServer: LocalTileServer = TileServerRegistry.get(forceNoStoreCache = true),
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : AbstractPolygonOverlayRenderer<HereActualPolygon>() {
    companion object {
        private const val MASK_TILE_SIZE_PX = 256
    }

    private data class MaskHandle(
        val routeId: String,
        val provider: PolygonRasterTileRenderer,
        val rasterLayerId: String,
        var cacheVersion: Int,
    )

    private val masks = HashMap<String, MaskHandle>()

    override suspend fun removePolygon(entity: PolygonEntityInterface<HereActualPolygon>) {
        removeMapPolygons(entity.polygon)
        removeMaskLayer(entity.state.id)
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
                    removeMaskLayer(current.state.id)
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

            if (current.state.holes.isNotEmpty()) {
                ensureMaskLayer(resolved, forceRecreate = true)
                polygon.forEach {
                    it.fillColor = Color.valueOf(0f, 0f, 0f, 0f)
                    it.outlineColor = Color.valueOf(current.state.strokeColor.toArgb())
                    it.outlineWidth =
                        ResourceProvider.dpToPx(
                            current.state.strokeWidth,
                        )
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

    private suspend fun buildMapPolygons(state: PolygonState): HereActualPolygon =
        if (state.holes.isEmpty()) {
            Log.d(TAG, "No holes, using simple polygon")
            removeMaskLayer(state.id)
            listOf(createMapPolygon(state, GeoPolygon(toRing(state.points, state.geodesic))))
        } else {
            Log.d(TAG, "Has holes, using raster layer for mask")
            ensureMaskLayer(state, forceRecreate = true)
            // Create stroke-only polygons: outer boundary + each hole boundary
            val strokeColor = Color.valueOf(state.strokeColor.toArgb())
            val strokeWidth = ResourceProvider.dpToPx(state.strokeWidth)
            val transparentFill = Color.valueOf(0f, 0f, 0f, 0f)

            buildList {
                // Outer boundary stroke (transparent fill)
                add(
                    createMapPolygon(
                        state.copy(holes = emptyList()),
                        GeoPolygon(toRing(state.points, state.geodesic)),
                    ).apply {
                        fillColor = transparentFill
                        outlineColor = strokeColor
                        outlineWidth = strokeWidth
                    },
                )
                // Hole boundary strokes (transparent fill)
                state.holes.forEach { hole ->
                    val holeRing = toRing(hole, state.geodesic)
                    if (holeRing.size >= 3) {
                        add(
                            MapPolygon(
                                GeoPolygon(holeRing),
                                transparentFill,
                                strokeColor,
                                strokeWidth,
                            ).apply {
                                drawOrder = state.zIndex.coerceIn(0, 511)
                            },
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
                    .WGS84Geodesic.createInterpolatePoints(points)
            } else {
                points
            }
        ).map { GeoCoordinates(it.latitude, normalizeLng(it.longitude)) }
            .let { pts -> if (pts.size >= 2 && pts.first() == pts.last()) pts.dropLast(1) else pts }

    private suspend fun ensureMaskLayer(
        state: PolygonState,
        forceRecreate: Boolean = false,
    ) {
        val polygonId = state.id
        Log.d(TAG, "ensureMaskLayer: polygonId=$polygonId, forceRecreate=$forceRecreate")
        val handle = masks[polygonId]
        if (handle != null && !forceRecreate) {
            // Update bounds + keep URL the same (tiles are no-store for HERE via TileServerRegistry).
            Log.d(TAG, "Reusing existing mask layer, just updating bounds")
            updateMaskBounds(handle, state)
            return
        }

        // マスクの「旧削除 → 新作成」はアトミックに行う。オーバーレイ描画は
        // LaunchedEffect(flow) で駆動され、マーカードラッグ等で state が高速に変わると
        // 進行中の render（この recreate を含む）がキャンセル→再起動される。途中で
        // キャンセルされると旧マスクを削除したまま新マスクが作られず、マスクが消えた/
        // 古いまま残る（＝「ドラッグ後にタイルが再生成されない」）。NonCancellable で
        // 一連の再生成を完了させ、最後に走った更新が必ず一貫したマスクを残すようにする。
        withContext(NonCancellable) {
            if (handle != null) {
                Log.d(TAG, "Removing old mask layer before recreating")
                removeMaskLayer(polygonId)
            }

            val routeId = "polygon-raster-" + safeId(polygonId)
            val rasterLayerId = "polygon-raster-$polygonId"
            Log.d(TAG, "Creating new mask: routeId=$routeId, rasterLayerId=$rasterLayerId")
            val provider =
                PolygonRasterTileRenderer(
                    tileSizePx = MASK_TILE_SIZE_PX,
                )
            updateMaskBounds(provider, state)
            tileServer.register(routeId, provider)
            Log.d(TAG, "Registered tile provider with LocalTileServer baseUrl=${tileServer.baseUrl}")

            val cacheVersion = ((System.nanoTime() / 1_000_000) and 0x7fffffff).toInt()
            // Use query param for cache key - HERE SDK's TileUrlProviderFactory is stricter
            // about path segment formats. Query params are stripped during URL parsing.
            val urlTemplate = tileServer.urlTemplateWithQueryCacheKey(routeId, MASK_TILE_SIZE_PX, cacheVersion.toString())
            Log.d(TAG, "URL template: $urlTemplate")
            val rasterState =
                RasterLayerState(
                    source =
                        RasterLayerSource.UrlTemplate(
                            template = urlTemplate,
                            tileSize = MASK_TILE_SIZE_PX,
                            maxZoom = 22,
                            scheme = TileScheme.XYZ,
                        ),
                    // The tile already contains alpha. Keep opacity at 1.
                    opacity = 1.0f,
                    visible = true,
                    zIndex = state.zIndex,
                    id = rasterLayerId,
                )
            Log.d(TAG, "Calling rasterLayerController.upsert() with zIndex=${state.zIndex}")
            rasterLayerController.upsert(rasterState)

            // Only record the mask if the raster layer was actually created.
            val hasEntity = rasterLayerController.rasterLayerManager.hasEntity(rasterLayerId)
            Log.d(TAG, "After upsert: hasEntity=$hasEntity")
            if (!hasEntity) {
                Log.e(TAG, "Raster layer was NOT created! Unregistering tile provider.")
                tileServer.unregister(routeId)
                return@withContext
            }

            masks[polygonId] =
                MaskHandle(
                    routeId = routeId,
                    provider = provider,
                    rasterLayerId = rasterLayerId,
                    cacheVersion = cacheVersion,
                )
            Log.d(TAG, "Mask layer created successfully")
        }
    }

    private suspend fun removeMaskLayer(polygonId: String) {
        val handle = masks.remove(polygonId) ?: return
        tileServer.unregister(handle.routeId)
        rasterLayerController.removeById(handle.rasterLayerId)
    }

    private fun updateMaskBounds(
        handle: MaskHandle,
        state: PolygonState,
    ) {
        updateMaskBounds(handle.provider, state)
    }

    private fun updateMaskBounds(
        provider: PolygonRasterTileRenderer,
        state: PolygonState,
    ) {
        provider.points = state.points
        provider.holes = state.holes
        provider.fillColor = state.fillColor
        provider.strokeColor = androidx.compose.ui.graphics.Color.Transparent
        provider.strokeWidthPx = 0f
        provider.geodesic = state.geodesic
        provider.outerBounds = boundsOf(state.points)
    }

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
