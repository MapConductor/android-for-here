package com.mapconductor.here

import androidx.annotation.Keep
import com.here.sdk.core.GeoOrientation
import com.here.sdk.mapview.MapCamera
import com.here.sdk.mapview.MapCameraUpdate
import com.here.sdk.mapview.MapCameraUpdateFactory
import com.here.sdk.mapview.MapMeasure
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapCameraPositionInterface
import com.mapconductor.core.spherical.Spherical
import com.mapconductor.here.zoom.ZoomAltitudeConverter
import kotlin.math.abs
import kotlin.math.tan

private val converter = ZoomAltitudeConverter()
private const val MAX_HERE_TILT = 90.0

internal data class HereCameraParameters(
    val target: GeoPoint,
    val orientation: GeoOrientation,
    val hereZoom: Double,
)

@Keep
fun MapCameraPosition.toMapCameraUpdate(): MapCameraUpdate {
    val cameraParameters = toHereCameraParameters()
    return MapCameraUpdateFactory.lookAt(
        cameraParameters.target.toGeoCoordinates().toUpdate(),
        cameraParameters.orientation.toUpdate(),
        MapMeasure(
            MapMeasure.Kind.ZOOM_LEVEL,
            cameraParameters.hereZoom,
        ),
    )
}

internal fun MapCameraPosition.toHereCameraParameters(): HereCameraParameters {
    if (tilt >= 0) {
        return HereCameraParameters(
            target = GeoPoint.from(position),
            orientation = GeoOrientation(bearing, tilt),
            hereZoom = ZoomAltitudeConverter.googleZoomToHereZoom(zoom, position.latitude),
        )
    }

    // tilt < 0: ArcGIS の仕様に合わせ、カメラ位置と高度を固定したまま前方を見る。
    // HERE は viewport center を指定するため、中心 target を pitch に応じて前方へ移動する。
    val tiltAbsDeg = abs(tilt).coerceIn(0.0, MAX_HERE_TILT)
    val tiltAbsRad = Math.toRadians(tiltAbsDeg)
    val distance = converter.zoomLevelToAltitude(zoom, position.latitude, 0.0)
    val distanceForward = distance * tan(tiltAbsRad)
    val target = Spherical.computeOffset(position, distanceForward, bearing)

    return HereCameraParameters(
        target = target,
        orientation = GeoOrientation(bearing, tiltAbsDeg),
        hereZoom = ZoomAltitudeConverter.googleZoomToHereZoom(zoom, position.latitude),
    )
}

fun MapCameraPosition.Companion.from(position: MapCameraPositionInterface): MapCameraPosition =
    when (position) {
        is MapCameraPosition -> position
        else ->
            MapCameraPosition(
                position = GeoPoint.from(position.position),
                zoom = position.zoom,
                bearing = position.bearing,
                tilt = position.tilt,
                paddings = position.paddings,
                visibleRegion = position.visibleRegion,
            )
    }

fun MapCamera.State.toMapCameraPosition(): MapCameraPosition {
    val position = targetCoordinates.toGeoPoint()
    val ourZoom = ZoomAltitudeConverter.hereZoomToGoogleZoom(zoomLevel, position.latitude)
    return MapCameraPosition(
        position = position,
        zoom = ourZoom,
        bearing = this.orientationAtTarget.bearing,
        tilt = this.orientationAtTarget.tilt,
        visibleRegion = null,
    )
}
