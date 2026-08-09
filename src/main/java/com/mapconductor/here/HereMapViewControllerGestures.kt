package com.mapconductor.here

import com.here.sdk.core.Point2D
import com.here.sdk.gestures.GestureState
import com.mapconductor.core.circle.CircleEvent
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.groundimage.GroundImageEvent
import com.mapconductor.core.marker.clickableOnly
import com.mapconductor.core.polygon.PolygonEvent
import com.mapconductor.core.polyline.PolylineEvent
import kotlinx.coroutines.launch

// タップと長押しの処理。
// タップは**マーカーが先**で、どのマーカーにも当たらなかったときだけ
// 地図のタップとして扱う（android の他プロバイダと同じ順序）。
internal fun HereMapViewController.handleTap(point: Point2D) {
    val touchPosition = this.getGeoPointFromPoint(point) ?: return

    markerEventControllers.forEach { controller ->
        controller.find(touchPosition).clickableOnly()?.let { entity ->
            controller.dispatchClick(entity.state)
            return
        }
    }

    circleController.find(touchPosition)?.let { entity ->
        val event =
            CircleEvent(
                state = entity.state,
                clicked = touchPosition,
            )
        circleController.dispatchClick(event)
        return
    }

    groundImageController.find(touchPosition)?.let { entity ->
        val event =
            GroundImageEvent(
                state = entity.state,
                clicked = touchPosition,
            )
        groundImageController.dispatchClick(event)
        return
    }

    polylineController.findWithClosestPoint(touchPosition)?.let { hitResult ->
        val event =
            PolylineEvent(
                state = hitResult.entity.state,
                clicked = hitResult.closestPoint,
            )
        mainCoroutine.launch {
            polylineController.dispatchClick(event)
        }
        return
    }

    polygonController.find(touchPosition)?.let { entity ->
        val event =
            PolygonEvent(
                state = entity.state,
                clicked = touchPosition,
            )
        mainCoroutine.launch {
            polygonController.dispatchClick(event)
        }
        return
    }

    // If no overlay is processed, process the tap as onMapClick
    emitMapClick(touchPosition)
}

internal fun HereMapViewController.handleLongPress(
    gesture: GestureState,
    point: Point2D,
) {
    val position = this.getGeoPointFromPoint(point) ?: return

    when (gesture.value) {
        GestureState.BEGIN.value -> {
            markerEventControllers.forEach { controller ->
                controller.find(position)?.let { entity ->
                    if (entity.state.draggable) {
                        entity.state.position = position
                        activeDragController = controller
                        controller.setSelectedMarker(entity)
                        controller.dispatchDragStart(entity.state)
                        return
                    }
                }
            }
            emitMapLongClick(position)
        }

        GestureState.UPDATE.value -> {
            val controller = activeDragController ?: return
            controller.getSelectedMarker()?.also { selected ->
                holder.mapView.viewToGeoCoordinates(point)?.also { coordinates ->
                    selected.marker?.coordinates = coordinates
                    selected.state.position = coordinates.toGeoPoint()
                }
                controller.dispatchDrag(selected.state)
            }
        }

        GestureState.END.value, GestureState.CANCEL.value -> {
            val controller = activeDragController ?: return
            controller.getSelectedMarker()?.also { selected ->
                controller.dispatchDragEnd(selected.state)
                controller.setSelectedMarker(null)
                activeDragController = null
            }
        }
    }
}

internal fun HereMapViewController.getGeoPointFromPoint(point: Point2D): GeoPoint? =
    holder.mapView
        .viewToGeoCoordinates(point)
        ?.toGeoPoint()
