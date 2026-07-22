package com.mapconductor.here.polygon

import androidx.compose.ui.graphics.toArgb
import com.here.sdk.core.Color
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.GeoPolygon
import com.here.sdk.mapview.MapPolygon
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.normalizeLng
import com.mapconductor.core.polygon.AbstractPolygonOverlayRenderer
import com.mapconductor.core.polygon.PolygonEntityInterface
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.spherical.createInterpolatePoints
import com.mapconductor.here.HereActualPolygon
import com.mapconductor.here.HereViewHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HerePolygonOverlayRenderer(
    override val holder: HereViewHolder,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : AbstractPolygonOverlayRenderer<HereActualPolygon>() {
    override suspend fun removePolygon(entity: PolygonEntityInterface<HereActualPolygon>) {
        withContext(Dispatchers.Main) {
            entity.polygon.forEach { holder.map.removeMapPolygon(it) }
        }
    }

    override suspend fun createPolygon(state: PolygonState): HereActualPolygon? {
        val polygon = createMapPolygon(state)
        withContext(Dispatchers.Main) {
            holder.map.addMapPolygon(polygon)
        }
        return listOf(polygon)
    }

    override suspend fun updatePolygonProperties(
        polygon: HereActualPolygon,
        current: PolygonEntityInterface<HereActualPolygon>,
        prev: PolygonEntityInterface<HereActualPolygon>,
    ): HereActualPolygon? =
        withContext(coroutine.coroutineContext) {
            val finger = current.fingerPrint
            val prevFinger = prev.fingerPrint

            if (polygon.size != 1) {
                val replacement = createMapPolygon(current.state)
                withContext(Dispatchers.Main) {
                    polygon.forEach { holder.map.removeMapPolygon(it) }
                    holder.map.addMapPolygon(replacement)
                }
                return@withContext listOf(replacement)
            }

            val actual = polygon.first()
            if (
                finger.points != prevFinger.points ||
                finger.holes != prevFinger.holes ||
                finger.geodesic != prevFinger.geodesic
            ) {
                val geometry = createGeometry(current.state)
                withContext(Dispatchers.Main) {
                    actual.geometry = geometry
                }
            }
            if (finger.strokeColor != prevFinger.strokeColor) {
                actual.outlineColor = Color.valueOf(current.state.strokeColor.toArgb())
            }
            if (finger.strokeWidth != prevFinger.strokeWidth) {
                actual.outlineWidth = ResourceProvider.dpToPx(current.state.strokeWidth)
            }
            if (finger.fillColor != prevFinger.fillColor) {
                actual.fillColor = Color.valueOf(current.state.fillColor.toArgb())
            }
            if (finger.zIndex != prevFinger.zIndex) {
                actual.drawOrder = current.state.zIndex.coerceIn(0, 511)
            }

            polygon
        }

    private fun createMapPolygon(state: PolygonState): MapPolygon =
        MapPolygon(
            createGeometry(state),
            Color.valueOf(state.fillColor.toArgb()),
            Color.valueOf(state.strokeColor.toArgb()),
            ResourceProvider.dpToPx(state.strokeWidth),
        ).apply {
            drawOrder = state.zIndex.coerceIn(0, 511)
        }

    private fun createGeometry(state: PolygonState): GeoPolygon =
        GeoPolygon(
            toRing(state.points, state.geodesic, counterClockwise = true),
            state.holes
                .map { toRing(it, state.geodesic, counterClockwise = false) }
                .filter { it.size >= 3 },
        )

    private fun toRing(
        points: List<GeoPointInterface>,
        geodesic: Boolean,
        counterClockwise: Boolean,
    ): List<GeoCoordinates> =
        (
            if (geodesic) {
                createInterpolatePoints(points)
            } else {
                points
            }
        ).map { GeoCoordinates(it.latitude, normalizeLng(it.longitude)) }
            .let { points ->
                if (points.size >= 2 && points.first() == points.last()) points.dropLast(1) else points
            }.let { ring ->
                val signedArea =
                    ring.indices.sumOf { index ->
                        val current = ring[index]
                        val next = ring[(index + 1) % ring.size]
                        current.longitude * next.latitude - next.longitude * current.latitude
                    }
                if ((counterClockwise && signedArea < 0) || (!counterClockwise && signedArea > 0)) {
                    ring.asReversed()
                } else {
                    ring
                }
            }
}
