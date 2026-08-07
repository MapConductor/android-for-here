package com.mapconductor.here

import HereMapDesignTypeChangeHandler
import HereMapViewControllerInterface
import com.here.sdk.core.Point2D
import com.here.sdk.gestures.GestureState
import com.here.sdk.gestures.GestureType
import com.here.sdk.gestures.LongPressListener
import com.here.sdk.gestures.TapListener
import com.here.sdk.mapview.MapCamera
import com.here.sdk.mapview.MapCameraListener
import com.mapconductor.core.circle.CircleCapableInterface
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.circle.OnCircleEventHandler
import com.mapconductor.core.controller.BaseMapViewController
import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.groundimage.OnGroundImageEventHandler
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapGesture
import com.mapconductor.core.map.MapUISettings
import com.mapconductor.core.map.MapUISettingsDiagnostics
import com.mapconductor.core.marker.MarkerAnimationOverlayHost
import com.mapconductor.core.marker.MarkerEventControllerInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.MarkerTileRasterLayerCallback
import com.mapconductor.core.marker.OnMarkerEventHandler
import com.mapconductor.core.marker.StrategyMarkerController
import com.mapconductor.core.polygon.OnPolygonEventHandler
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.OnPolylineEventHandler
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.here.circle.HereCircleController
import com.mapconductor.here.groundimage.HereGroundImageController
import com.mapconductor.here.marker.DefaultHereMarkerEventController
import com.mapconductor.here.marker.HereMarkerController
import com.mapconductor.here.marker.HereMarkerEventControllerInterface
import com.mapconductor.here.marker.HereMarkerRenderer
import com.mapconductor.here.marker.StrategyHereMarkerEventController
import com.mapconductor.here.polygon.HerePolygonController
import com.mapconductor.here.polyline.HerePolylineController
import com.mapconductor.here.raster.HereRasterLayerController
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class HereMapViewController(
    internal val markerController: HereMarkerController,
    internal val polylineController: HerePolylineController,
    internal val polygonController: HerePolygonController,
    internal val groundImageController: HereGroundImageController,
    internal val circleController: HereCircleController,
    internal val rasterLayerController: HereRasterLayerController,
    override val holder: HereViewHolder,
    override val defaultCoroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
    override val mainCoroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : BaseMapViewController(),
    CircleCapableInterface,
    HereMapViewControllerInterface,
    MapCameraListener,
    TapListener,
    LongPressListener {
    internal val markerEventControllers = mutableListOf<HereMarkerEventControllerInterface>()
    internal var activeDragController: HereMarkerEventControllerInterface? = null
    internal var markerClickListener: OnMarkerEventHandler? = null
    internal var markerDragStartListener: OnMarkerEventHandler? = null
    internal var markerDragListener: OnMarkerEventHandler? = null
    internal var markerDragEndListener: OnMarkerEventHandler? = null
    internal var markerAnimateStartListener: OnMarkerEventHandler? = null
    internal var markerAnimateEndListener: OnMarkerEventHandler? = null
    internal var lastRequestedCameraPosition: MapCameraPosition? = null
    internal val cameraRequestGeneration = AtomicLong(0L)

    // HERE's MapCameraListener provides only continuous updates. Synthesize a "move end" after an idle window
    // so app code can treat HERE similarly to other SDKs (e.g., for camera sync).
    internal var cameraMoveEndJob: Job? = null
    internal var cameraMoveInProgress: Boolean = false
    internal var isAnimatingCamera: Boolean = false
    internal var lastCameraPosition: MapCameraPosition? = null

    internal companion object {
        internal const val CAMERA_MOVE_END_IDLE_MS = 120L
    }

    override suspend fun clearOverlays() {
        markerController.clear()
        polylineController.clear()
        polygonController.clear()
        groundImageController.clear()
        circleController.clear()
        rasterLayerController.clear()
    }

    override suspend fun compositionMarkers(data: List<MarkerState>) = markerController.add(data)

    override fun setMarkerAnimationOverlayHost(host: MarkerAnimationOverlayHost?) {
        (markerController.renderer as HereMarkerRenderer).animationOverlayHost = host
    }

    override suspend fun updateMarker(state: MarkerState) = markerController.update(state)

    override suspend fun compositionGroundImages(data: List<GroundImageState>) = groundImageController.add(data)

    override suspend fun updateGroundImage(state: GroundImageState) = groundImageController.update(state)

    override fun hasMarker(state: MarkerState): Boolean = this.markerController.markerManager.hasEntity(state.id)

    override fun hasPolyline(state: PolylineState): Boolean =
        this.polylineController.polylineManager
            .hasEntity(state.id)

    override fun hasPolygon(state: PolygonState): Boolean = this.polygonController.polygonManager.hasEntity(state.id)

    override fun hasCircle(state: CircleState): Boolean = this.circleController.circleManager.hasEntity(state.id)

    override fun hasGroundImage(state: GroundImageState): Boolean =
        this.groundImageController.groundImageManager.hasEntity(state.id)

    override fun hasRasterLayer(state: RasterLayerState): Boolean =
        this.rasterLayerController.rasterLayerManager.hasEntity(state.id)

    @Deprecated("Use MarkerState.onDragStart instead.")
    override fun setOnMarkerDragStart(listener: OnMarkerEventHandler?) {
        markerDragStartListener = listener
        markerEventControllers.forEach { it.setDragStartListener(listener) }
    }

    @Deprecated("Use MarkerState.onDrag instead.")
    override fun setOnMarkerDrag(listener: OnMarkerEventHandler?) {
        markerDragListener = listener
        markerEventControllers.forEach { it.setDragListener(listener) }
    }

    @Deprecated("Use MarkerState.onDragEnd instead.")
    override fun setOnMarkerDragEnd(listener: OnMarkerEventHandler?) {
        markerDragEndListener = listener
        markerEventControllers.forEach { it.setDragEndListener(listener) }
    }

    @Deprecated("Use MarkerState.onAnimateStart instead.")
    override fun setOnMarkerAnimateStart(listener: OnMarkerEventHandler?) {
        markerAnimateStartListener = listener
        markerEventControllers.forEach { it.setAnimateStartListener(listener) }
    }

    @Deprecated("Use MarkerState.onAnimateEnd instead.")
    override fun setOnMarkerAnimateEnd(listener: OnMarkerEventHandler?) {
        markerAnimateEndListener = listener
        markerEventControllers.forEach { it.setAnimateEndListener(listener) }
    }

    @Deprecated("Use MarkerState.onClick instead.")
    override fun setOnMarkerClickListener(listener: OnMarkerEventHandler?) {
        markerClickListener = listener
        markerEventControllers.forEach { it.setClickListener(listener) }
    }

    override suspend fun compositionCircles(data: List<CircleState>) = circleController.add(data)

    override suspend fun updateCircle(state: CircleState) = circleController.update(state)

    @Deprecated("Use CircleState.onClick instead.")
    override fun setOnCircleClickListener(listener: OnCircleEventHandler?) {
        this.circleController.clickListener = listener
    }

    @Deprecated("Use GroundImageState.onClick instead.")
    override fun setOnGroundImageClickListener(listener: OnGroundImageEventHandler?) {
        this.groundImageController.clickListener = listener
    }

    override suspend fun compositionPolylines(data: List<PolylineState>) = polylineController.add(data)

    override suspend fun updatePolyline(state: PolylineState) = polylineController.update(state)

    override suspend fun compositionPolygons(data: List<PolygonState>) = polygonController.add(data)

    override suspend fun updatePolygon(state: PolygonState) = polygonController.update(state)

    override suspend fun compositionRasterLayers(data: List<RasterLayerState>) = rasterLayerController.add(data)

    override suspend fun updateRasterLayer(state: RasterLayerState) = rasterLayerController.update(state)

    init {
        setupListeners()
        registerOverlayController(markerController)
        registerOverlayController(polygonController)
        registerOverlayController(polylineController)
        registerOverlayController(groundImageController)
        registerOverlayController(circleController)
        registerOverlayController(rasterLayerController)
        registerMarkerEventController(DefaultHereMarkerEventController(markerController))

        markerController.setRasterLayerCallback(
            MarkerTileRasterLayerCallback { state ->
                if (state != null) {
                    rasterLayerController.upsert(state)
                } else {
                    val markerTileLayers =
                        rasterLayerController.rasterLayerManager
                            .allEntities()
                            .filter { it.state.id.startsWith("marker-tile-") }
                    markerTileLayers.forEach { entity -> rasterLayerController.removeById(entity.state.id) }
                }
            },
        )
    }

    override fun moveCamera(position: MapCameraPosition) = handleMoveCamera(position)

    override fun animateCamera(
        position: MapCameraPosition,
        duration: Long,
    ) = handleAnimateCamera(position, duration)

    override fun fitBounds(
        bounds: GeoRectBounds,
        padding: Int,
    ) = handleFitBounds(bounds, padding)

    override fun onMapCameraUpdated(cameraState: MapCamera.State) = handleCameraUpdated(cameraState)

    override fun onTap(point: Point2D) = handleTap(point)

    override fun onLongPress(
        gesture: GestureState,
        point: Point2D,
    ) = handleLongPress(gesture, point)

    // 拡張ファイル（Camera / Gestures）からは基底クラスの protected へ触れないため、
    // ここで internal の入口を用意しておく。
    internal fun mapInitializedHandler(): (() -> Unit)? = mapInitializedCallback

    internal fun clearMapInitializedHandler() {
        mapInitializedCallback = null
    }

    internal fun emitCameraMoveStart(position: MapCameraPosition) {
        cameraMoveStartCallback?.invoke(position)
    }

    internal fun emitCameraMoveEnd(position: MapCameraPosition) {
        cameraMoveEndCallback?.invoke(position)
    }

    internal fun emitMapClick(point: GeoPoint) {
        mapClickCallback?.invoke(point)
    }

    internal fun emitMapLongClick(point: GeoPoint) {
        mapLongClickCallback?.invoke(point)
    }

    internal suspend fun emitCameraPosition(position: MapCameraPosition) {
        notifyMapCameraPosition(position)
    }

    internal fun correctForCameraRestriction(current: MapCameraPosition): MapCameraPosition? =
        cameraRestrictionCorrection(current)

    fun setupListeners() {
        holder.mapView.camera.removeListener(this)
        holder.mapView.camera.addListener(this)
        holder.mapView.gestures.tapListener = this
        holder.mapView.gestures.longPressListener = this
    }

    override fun getControllers(): Map<String, OverlayControllerInterface<*, *>> =
        mapOf(
            "marker" to markerController,
            "polyline" to polylineController,
            "polygon" to polygonController,
            "circle" to circleController,
            "ground_image" to groundImageController,
            "raster_layer" to rasterLayerController,
        )

    override fun setOnPolylineClickListener(listener: OnPolylineEventHandler?) {
        polylineController.clickListener = listener
    }

    @Deprecated("Use PolygonState.onClick instead.")
    override fun setOnPolygonClickListener(listener: OnPolygonEventHandler?) {
        polygonController.clickListener = listener
    }

    private var mapDesignType: HereMapDesignType = HereMapDesign.NormalDay
    private var mapDesignTypeChangeListener: HereMapDesignTypeChangeHandler? = null

    override fun applyUISettings(settings: MapUISettings) {
        val gestures = holder.mapView.gestures

        fun apply(
            enabled: Boolean,
            gesture: GestureType,
        ) {
            if (enabled) gestures.enableDefaultAction(gesture) else gestures.disableDefaultAction(gesture)
        }
        apply(settings.scrollGesture, GestureType.PAN)
        apply(settings.tiltGesture, GestureType.TWO_FINGER_PAN)
        // HERE bundles pinch-zoom and rotation into one PINCH_ROTATE recogniser, so
        // neither can be switched off alone; only drop it when both are off.
        apply(settings.zoomGesture || settings.rotateGesture, GestureType.PINCH_ROTATE)
        apply(settings.zoomGesture, GestureType.DOUBLE_TAP)
        apply(settings.zoomGesture, GestureType.TWO_FINGER_TAP)
        if (settings.zoomGesture != settings.rotateGesture) {
            MapUISettingsDiagnostics.warnIfRequested(
                false,
                gesture = if (settings.zoomGesture) MapGesture.Rotate else MapGesture.Zoom,
                provider = "HERE",
                reason = "pinch zoom and rotation share one gesture recogniser, so they can only be disabled together",
            )
        }
    }

    override fun setMapDesignType(value: HereMapDesignType) {
        val scene = value.getValue()
        mainCoroutine.launch {
            holder.mapView.mapScene.loadScene(scene) {
                mapDesignType = value

                // loadScene can reset camera; restore the last requested camera to prevent jumping.
                lastRequestedCameraPosition?.let { cameraPosition ->
                    holder.mapView.post { moveCamera(cameraPosition) }
                }

                mapDesignTypeChangeListener?.invoke(value)
            }
        }
    }

    override fun setMapDesignTypeChangeListener(listener: HereMapDesignTypeChangeHandler) {
        mapDesignTypeChangeListener = listener
        listener(mapDesignType)
    }

    internal fun registerMarkerEventController(controller: HereMarkerEventControllerInterface) {
        if (markerEventControllers.contains(controller)) return
        markerEventControllers.add(controller)
        controller.setClickListener(markerClickListener)
        controller.setDragStartListener(markerDragStartListener)
        controller.setDragListener(markerDragListener)
        controller.setDragEndListener(markerDragEndListener)
        controller.setAnimateStartListener(markerAnimateStartListener)
        controller.setAnimateEndListener(markerAnimateEndListener)
    }

    fun createMarkerRenderer(): MarkerOverlayRendererInterface<HereActualMarker> = HereMarkerRenderer(holder = holder)

    fun createMarkerEventController(
        controller: StrategyMarkerController<HereActualMarker>,
    ): MarkerEventControllerInterface<HereActualMarker> = StrategyHereMarkerEventController(controller)

    fun registerMarkerEventController(controller: MarkerEventControllerInterface<HereActualMarker>) {
        val typed = controller as? HereMarkerEventControllerInterface ?: return
        registerMarkerEventController(typed)
    }
}
