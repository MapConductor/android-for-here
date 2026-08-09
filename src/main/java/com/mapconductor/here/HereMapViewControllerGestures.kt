package com.mapconductor.here

import com.here.sdk.core.Point2D
import com.here.sdk.gestures.GestureState
import com.mapconductor.core.features.GeoPoint

// タップと長押しの処理。
// タップのカスケード（marker → circle → groundImage → polyline → polygon → map）は
// コアの BaseMapViewController.dispatchTap が回すので、ここは HERE の Point2D を
// 地理座標へ直して渡すだけ。長押しはドラッグ開始の判定が要るのでここに残す。
internal fun HereMapViewController.handleTap(point: Point2D) {
    val touchPosition = this.getGeoPointFromPoint(point) ?: return
    dispatchTap(touchPosition)
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
