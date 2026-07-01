package com.mapconductor.here

import android.graphics.PointF
import com.here.sdk.core.Point2D
import com.here.sdk.mapview.MapScene
import com.here.sdk.mapview.MapView
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.map.MapViewHolderInterface

class HereViewHolder(
    override val mapView: MapView,
    override val map: MapScene,
) : MapViewHolderInterface<MapView, MapScene> {
    override fun toScreenOffset(position: GeoPointInterface): PointF? {
        val result =
            mapView.geoToViewCoordinates(
                GeoPoint.from(position).toGeoCoordinates(),
            ) ?: return null

        return PointF(result.x.toFloat(), result.y.toFloat())
    }

    override suspend fun fromScreenOffset(offset: PointF): GeoPoint? =
        mapView
            .viewToGeoCoordinates(
                Point2D(offset.x.toDouble(), offset.y.toDouble()),
            )?.toGeoPoint()

    override fun fromScreenOffsetSync(offset: PointF): GeoPoint? =
        mapView
            .viewToGeoCoordinates(
                Point2D(offset.x.toDouble(), offset.y.toDouble()),
            )?.toGeoPoint()
}
