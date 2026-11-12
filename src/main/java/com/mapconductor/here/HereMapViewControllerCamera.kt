package com.mapconductor.here

import androidx.compose.ui.geometry.Offset
import com.here.sdk.animation.AnimationState
import com.here.sdk.core.GeoOrientation
import com.here.sdk.core.Point2D
import com.here.sdk.core.Rectangle2D
import com.here.sdk.core.Size2D
import com.here.sdk.mapview.MapCamera
import com.here.sdk.mapview.MapCameraAnimationFactory
import com.here.sdk.mapview.MapMeasure
import com.here.time.Duration
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.CameraPaddingEmulation
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.VisibleRegion
import com.mapconductor.core.spherical.Spherical
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// カメラの適用と、HERE からのカメラ更新通知の受け口。
// HERE は「動き終わった」を通知しないので、更新が止まったところを
// デバウンスで作っている。範囲制限もネイティブ API が無く、停止時に
// 矩形内へクランプして再適用する。

/** 地図の高さ（dp）。パディングのエミュレーションで遠近を解くのに使う。 */
internal fun HereMapViewController.viewportHeightDp(): Double {
    val density = holder.mapView.resources.displayMetrics.density
    return holder.mapView.height / density.toDouble()
}

/**
 * HERE のカメラの縦方向の視野角（度）。SDK に取得 API が無いので、今のカメラから測る。
 *
 * ピンホールカメラでは、注視点で横に 1 m ずらすと `焦点距離 / 注視点までの距離` px 動く。
 * HERE は注視点までの距離（[MapCamera.State.distanceToTargetInMeters]）を返すので、
 * 横の 1 m が何 px かを投影で測れば焦点距離が出る。TB520FU では地図の大きさ・向き・
 * 画面密度によらず約 3550 px で、縦の視野角は縦長の全画面で約 45°、横長で約 29°。
 */
internal fun HereMapViewController.verticalFov(): Double? {
    val height = holder.mapView.height
    if (height <= 0) return null
    val state = holder.mapView.camera.state
    val target = state.targetCoordinates.toGeoPoint()
    val sideways = Spherical.computeOffset(target, 100.0, state.orientationAtTarget.bearing + 90.0)
    val a = holder.mapView.geoToViewCoordinates(target.toGeoCoordinates())
    val b = holder.mapView.geoToViewCoordinates(sideways.toGeoCoordinates())
    if (a != null && b != null && state.distanceToTargetInMeters > 0) {
        val pxPerMeter = hypot(b.x - a.x, b.y - a.y) / 100.0
        if (pxPerMeter > 0) focalLengthPx = state.distanceToTargetInMeters * pxPerMeter
    }
    val f = focalLengthPx ?: return null
    return Math.toDegrees(2 * atan((height / 2.0) / f))
}

/** パディングを中心のずらしに置き換えたカメラ。paddings が null なら今のパディングを使い続ける。 */
internal fun HereMapViewController.toNativeCamera(position: MapCameraPosition): MapCameraPosition {
    position.paddings?.let { cameraPaddings = it }
    return CameraPaddingEmulation.toNativeCamera(
        position.copy(paddings = cameraPaddings),
        verticalFov(),
        viewportHeightDp(),
    )
}

internal fun HereMapViewController.handleMoveCamera(position: MapCameraPosition) {
    lastRequestedCameraPosition = position
    val request = cameraRequestGeneration.incrementAndGet()
    val camera = this.holder.mapView.camera
    val adjustCameraUpdate = toNativeCamera(position).toMapCameraUpdate()

    camera.applyUpdate(adjustCameraUpdate)

    // If this runs before first layout, HERE may ignore it; retry once after layout.
    if (holder.mapView.width == 0 || holder.mapView.height == 0) {
        holder.mapView.post {
            if (cameraRequestGeneration.get() == request) {
                camera.applyUpdate(adjustCameraUpdate)
            }
        }
    }
}

internal fun HereMapViewController.handleAnimateCamera(
    position: MapCameraPosition,
    duration: Long,
) {
    lastRequestedCameraPosition = position
    cameraRequestGeneration.incrementAndGet()
    val camera = this.holder.mapView.camera

    val display = toNativeCamera(position).toHereDisplayCamera()

//      bowFactor > 0: 最初にズームアウト → 到達時にズームイン
//      bowFactor < 0: 最初にズームイン → 到達時にズームアウト（ややレア）
//      bowFactor = 0: 常に同じズーム（直線的）
    val bowFactor = 1.0
    val animation =
        MapCameraAnimationFactory.flyTo(
            display.target.toGeoCoordinates().toUpdate(),
            GeoOrientation(display.bearing, display.tiltDeg).toUpdate(),
            MapMeasure(MapMeasure.Kind.ZOOM_LEVEL, display.hereZoomLevel),
            bowFactor,
            Duration.ofMillis(duration),
        )
    mainCoroutine.launch {
        isAnimatingCamera = true
        camera.startAnimation(animation) { animState ->
            when (animState) {
                // Do nothing here
                AnimationState.STARTED -> {
                    getMapCameraPosition(holder.mapView.camera.state)?.let {
                        emitCameraMoveStart(it)
                    }
                }
                AnimationState.COMPLETED -> {
                    isAnimatingCamera = false
                    emitCameraMoveEnd(position)
                }
                AnimationState.CANCELLED -> {
                    isAnimatingCamera = false
                    getMapCameraPosition(holder.mapView.camera.state)?.let {
                        emitCameraMoveEnd(it)
                    }
                }
            }
        }
    }
}

internal fun HereMapViewController.handleFitBounds(
    bounds: GeoRectBounds,
    padding: Int,
) {
    val geoBox = bounds.toGeoBox() ?: return
    val camera = holder.mapView.camera
    val request = cameraRequestGeneration.incrementAndGet()

    // padding(px) は表示ビューポートを四辺インセットした矩形（Rectangle2D）として反映する。
    // ビューサイズが未確定（0）またはインセットが大き過ぎる場合はビューポート指定なしへフォールバック。
    fun buildUpdate(): com.here.sdk.mapview.MapCameraUpdate {
        val width = holder.mapView.width
        val height = holder.mapView.height
        val inset = padding.coerceAtLeast(0)
        return if (inset > 0 && width > 2 * inset && height > 2 * inset) {
            val viewRectangle =
                Rectangle2D(
                    Point2D(inset.toDouble(), inset.toDouble()),
                    Size2D(
                        (width - 2 * inset).toDouble(),
                        (height - 2 * inset).toDouble(),
                    ),
                )
            com.here.sdk.mapview.MapCameraUpdateFactory
                .lookAt(geoBox, viewRectangle)
        } else {
            com.here.sdk.mapview.MapCameraUpdateFactory
                .lookAt(geoBox)
        }
    }

    camera.applyUpdate(buildUpdate())
    if (holder.mapView.width == 0 || holder.mapView.height == 0) {
        holder.mapView.post {
            if (cameraRequestGeneration.get() == request) {
                camera.applyUpdate(buildUpdate())
            }
        }
    }
}

internal fun HereMapViewController.handleCameraUpdated(cameraState: MapCamera.State) {
    // Must run on main thread: HERE MapView coordinate conversion APIs are not thread-safe.
    mainCoroutine.launch {
        mapInitializedHandler()?.let {
            it.invoke()
            clearMapInitializedHandler()
        }

        val mapCameraPosition = getMapCameraPosition(cameraState) ?: return@launch
        lastCameraPosition = mapCameraPosition

        // This will call registered overlay controllers and cameraMoveCallback.
        emitCameraPosition(mapCameraPosition)

        // animateCamera() already provides a reliable end callback.
        if (isAnimatingCamera) return@launch

        if (!cameraMoveInProgress) {
            cameraMoveInProgress = true
            emitCameraMoveStart(mapCameraPosition)
        }

        cameraMoveEndJob?.cancel()
        cameraMoveEndJob =
            mainCoroutine.launch {
                delay(HereMapViewController.CAMERA_MOVE_END_IDLE_MS.milliseconds)
                val last = lastCameraPosition ?: return@launch
                // 範囲・ズーム制限に違反していれば矩形内へ引き戻す（HERE はネイティブの範囲制限 API が無いため）。
                // 再適用すると onMapCameraUpdated が再発火し、そこでは補正不要になり通常フローへ進む。
                correctForCameraRestriction(last)?.let { corrected ->
                    moveCamera(corrected)
                    return@launch
                }
                cameraMoveInProgress = false
                emitCameraMoveEnd(last)
            }
    }
}

internal fun HereMapViewController.getMapCameraPosition(cameraState: MapCamera.State): MapCameraPosition? {
    return holder.mapView.camera.boundingBox?.let { boundingBox ->
        val mapWidth = holder.mapView.width.toFloat()
        val mapHeight = holder.mapView.height.toFloat()
        val bounds = boundingBox.toGeoRectBounds()
        val visibleRegion =
            VisibleRegion(
                bounds = bounds,
                nearLeft = holder.fromScreenOffsetSync(Offset(0.0f, mapHeight)),
                nearRight = holder.fromScreenOffsetSync(Offset(mapWidth, mapHeight)),
                farLeft = holder.fromScreenOffsetSync(Offset(0.0f, 0.0f)),
                farRight = holder.fromScreenOffsetSync(Offset(mapWidth, 0.0f)),
            )
        val logicalCamera =
            CameraPaddingEmulation.fromNativeCamera(
                HereCameraStateSnapshot(cameraState, lastRequestedCameraPosition?.tilt).toMapCameraPosition(),
                cameraPaddings,
                verticalFov(),
                viewportHeightDp(),
            )
        return@let logicalCamera.copy(visibleRegion = visibleRegion)
    }
}
