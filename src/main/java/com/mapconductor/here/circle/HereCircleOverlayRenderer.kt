package com.mapconductor.here.circle

import androidx.compose.ui.graphics.toArgb
import com.here.sdk.core.Color
import com.here.sdk.core.GeoCircle
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.GeoPolygon
import com.here.sdk.mapview.MapPolygon
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.circle.AbstractCircleOverlayRenderer
import com.mapconductor.core.circle.CircleEntityInterface
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.normalize
import com.mapconductor.core.geometry.circleToRing
import com.mapconductor.core.geometry.closeRing
import com.mapconductor.here.HereActualCircle
import com.mapconductor.here.HereViewHolder
import com.mapconductor.here.toGeoCoordinates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HereCircleOverlayRenderer(
    override val holder: HereViewHolder,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : AbstractCircleOverlayRenderer<HereActualCircle>() {
    override suspend fun createCircle(state: CircleState): HereActualCircle? {
        val geoCircle = createCirclePolygon(state)
        val lineWidth = ResourceProvider.dpToPx(state.strokeWidth.value.toDouble())
        val mapCircle =
            MapPolygon(
                geoCircle,
                Color.valueOf(state.fillColor.toArgb()),
                Color.valueOf(state.strokeColor.toArgb()),
                lineWidth,
            ).apply {
                drawOrder = (state.zIndex ?: 0).coerceIn(0, 511)
            }
        coroutine.launch {
            holder.map.addMapPolygon(mapCircle)
        }
        return mapCircle
    }

    override suspend fun removeCircle(entity: CircleEntityInterface<HereActualCircle>) {
        coroutine.launch {
            holder.map.removeMapPolygon(entity.circle)
        }
    }

    override suspend fun updateCircleProperties(
        circle: HereActualCircle,
        current: CircleEntityInterface<HereActualCircle>,
        prev: CircleEntityInterface<HereActualCircle>,
    ): HereActualCircle? =
        withContext(coroutine.coroutineContext) {
            val finger = current.fingerPrint
            val prevFinger = prev.fingerPrint

            // Update geometry if center or radius changed
            if (finger.center != prevFinger.center ||
                finger.radiusMeters != prevFinger.radiusMeters ||
                finger.geodesic != prevFinger.geodesic
            ) {
                val geoCircle = createCirclePolygon(current.state)
                current.circle.geometry = geoCircle
            }

            // Update stroke color
            if (finger.strokeColor != prevFinger.strokeColor) {
                current.circle.outlineColor =
                    Color.valueOf(
                        current.state.strokeColor
                            .toArgb(),
                    )
            }

            // Update stroke width
            if (finger.strokeWidth != prevFinger.strokeWidth) {
                val lineWidth =
                    ResourceProvider.dpToPx(
                        current.state.strokeWidth.value
                            .toDouble(),
                    )
                current.circle.outlineWidth = lineWidth
            }

            // Update fill color
            if (finger.fillColor != prevFinger.fillColor) {
                current.circle.fillColor =
                    Color.valueOf(
                        current.state.fillColor
                            .toArgb(),
                    )
            }
            if (finger.zIndex != prevFinger.zIndex) {
                current.circle.drawOrder = (current.state.zIndex ?: 0).coerceIn(0, 511)
            }

            circle
        }

    /**
     * Creates a circle that approximates a circle by generating points around the circumference
     */
    private fun createCirclePolygon(state: CircleState): GeoPolygon {
        val center = GeoPoint.from(state.center).toGeoCoordinates()
        if (state.geodesic) {
            // Native geodesic circle
            val geoCircle = GeoCircle(center, state.radiusMeters)
            return GeoPolygon(geoCircle)
        }
        // 非 geodesic はコア共通の circleToRing（局所平面近似・unwrap 座標）でリングを
        // 生成し、HERE の座標範囲に収まるよう正規化する。
        val pts: List<GeoCoordinates> =
            closeRing(circleToRing(state.center, state.radiusMeters, geodesic = false).map { it.normalize() })
                .map { GeoPoint.from(it).toGeoCoordinates() }
        return GeoPolygon(pts)
    }
}
