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
import kotlin.math.cos
import kotlin.math.tan

private val converter = ZoomAltitudeConverter()

internal data class HereDisplayCamera(
    val target: GeoPoint,
    val tiltDeg: Double,
    val hereZoomLevel: Double,
    val bearing: Double,
)

internal fun MapCameraPosition.toHereDisplayCamera(): HereDisplayCamera {
    if (tilt >= 0) {
        return HereDisplayCamera(
            target = GeoPoint.from(position),
            tiltDeg = tilt,
            hereZoomLevel = ZoomAltitudeConverter.googleZoomToHereZoom(zoom, position.latitude),
            bearing = bearing,
        )
    }
    // tilt < 0: HERE cannot represent upward pitch directly.
    // Keep the virtual eye direction by moving the ground target forward and rendering with abs(tilt).
    val tiltAbsDeg = abs(tilt).coerceIn(0.0, 60.0)
    val tiltAbsRad = Math.toRadians(tiltAbsDeg)
    val hereZoomOrig = ZoomAltitudeConverter.googleZoomToHereZoom(zoom, position.latitude)
    val altitude = converter.zoomLevelToAltitude(hereZoomOrig, position.latitude, 0.0)
    val distanceForward = altitude * tan(tiltAbsRad)
    val target = Spherical.computeOffset(position, distanceForward, bearing)
    val adjustedHereZoom = converter.altitudeToZoomLevel(altitude / cos(tiltAbsRad), target.latitude, 0.0)
    return HereDisplayCamera(
        target = target,
        tiltDeg = tiltAbsDeg,
        hereZoomLevel = adjustedHereZoom,
        bearing = bearing,
    )
}

@Keep
fun MapCameraPosition.toMapCameraUpdate(): MapCameraUpdate {
    val display = toHereDisplayCamera()
    return MapCameraUpdateFactory.lookAt(
        display.target.toGeoCoordinates().toUpdate(),
        GeoOrientation(display.bearing, display.tiltDeg).toUpdate(),
        MapMeasure(MapMeasure.Kind.ZOOM_LEVEL, display.hereZoomLevel),
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

fun MapCamera.State.toMapCameraPosition(): MapCameraPosition = toMapCameraPosition(logicalTiltHint = null)

internal data class HereCameraStateSnapshot(
    val cameraState: MapCamera.State,
    val logicalTiltHint: Double?,
) {
    fun toMapCameraPosition(): MapCameraPosition = cameraState.toMapCameraPosition(logicalTiltHint)
}

internal fun MapCamera.State.toMapCameraPosition(logicalTiltHint: Double?): MapCameraPosition {
    val pitch = orientationAtTarget.tilt
    val pitchAbsDeg = abs(pitch).coerceIn(0.0, 60.0)

    if (logicalTiltHint == null || logicalTiltHint >= 0.0 || pitchAbsDeg == 0.0) {
        val position = targetCoordinates.toGeoPoint()
        val ourZoom = ZoomAltitudeConverter.hereZoomToGoogleZoom(zoomLevel, position.latitude)
        return MapCameraPosition(
            position = position,
            zoom = ourZoom,
            bearing = orientationAtTarget.bearing,
            tilt = pitch,
            visibleRegion = null,
        )
    }

    // Recover original position and zoom from shifted camera state (tilt < 0 case)
    val pitchAbsRad = Math.toRadians(pitchAbsDeg)
    val shiftedCenter = targetCoordinates.toGeoPoint()
    val bear = orientationAtTarget.bearing

    val adjustedAltitude = converter.zoomLevelToAltitude(zoomLevel, shiftedCenter.latitude, 0.0)
    val originalAltitude = adjustedAltitude * cos(pitchAbsRad)
    val distanceBackward = originalAltitude * tan(pitchAbsRad)
    val originalCenter = Spherical.computeOffset(shiftedCenter, distanceBackward, bear + 180.0)
    val originalHereZoom = converter.altitudeToZoomLevel(originalAltitude, originalCenter.latitude, 0.0)
    val originalGoogleZoom = ZoomAltitudeConverter.hereZoomToGoogleZoom(originalHereZoom, originalCenter.latitude)

    return MapCameraPosition(
        position = originalCenter,
        zoom = originalGoogleZoom,
        bearing = bear,
        tilt = -pitchAbsDeg,
        visibleRegion = null,
    )
}
