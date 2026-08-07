package com.mapconductor.here

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.here.sdk.mapview.MapProjection as HereMapProjection
import com.here.sdk.mapview.MapRenderMode
import com.here.sdk.mapview.MapView
import com.here.sdk.mapview.MapViewOptions
import com.mapconductor.compose.map.MapViewBase
import com.mapconductor.core.OnCameraMoveHandler
import com.mapconductor.core.OnMapEventHandler
import com.mapconductor.core.OnMapLoadedHandler
import com.mapconductor.core.map.CameraRestriction
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapCameraPositionInterface
import com.mapconductor.core.map.MapProjection
import com.mapconductor.core.marker.MarkerEventControllerInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerRenderingStrategyInterface
import com.mapconductor.core.marker.MarkerRenderingSupport
import com.mapconductor.core.marker.MarkerRenderingSupportKey
import com.mapconductor.core.marker.MarkerTilingOptions
import com.mapconductor.core.marker.StrategyMarkerController
import com.mapconductor.core.tileserver.TileServerRegistry
import com.mapconductor.here.circle.HereCircleController
import com.mapconductor.here.circle.HereCircleOverlayRenderer
import com.mapconductor.here.groundimage.HereGroundImageController
import com.mapconductor.here.groundimage.HereGroundImageOverlayRenderer
import com.mapconductor.here.marker.HereMarkerController
import com.mapconductor.here.polygon.HerePolygonController
import com.mapconductor.here.polygon.HerePolygonOverlayRenderer
import com.mapconductor.here.polyline.HerePolylineController
import com.mapconductor.here.polyline.HerePolylineOverlayRenderer
import com.mapconductor.here.raster.HereRasterLayerController
import com.mapconductor.here.raster.HereRasterLayerOverlayRenderer
import java.util.concurrent.atomic.AtomicBoolean
import android.annotation.SuppressLint
import android.view.ViewGroup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine

@SuppressLint("RememberReturnType")
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun HereMapView(
    state: HereViewState,
    modifier: Modifier = Modifier,
    markerTiling: MarkerTilingOptions? = null,
    cameraRestriction: CameraRestriction? = null,
    sdkInitialize: (suspend (android.content.Context) -> Boolean)? = null,
    onMapLoaded: OnMapLoadedHandler? = null,
    onMapClick: OnMapEventHandler? = null,
    onMapLongClick: OnMapEventHandler? = null,
    onCameraMoveStart: OnCameraMoveHandler? = null,
    onCameraMove: OnCameraMoveHandler? = null,
    onCameraMoveEnd: OnCameraMoveHandler? = null,
    projection: MapProjection = MapProjection.Globe,
    content: (@Composable HereViewScope.() -> Unit)? = null,
) {
    // Warmup the tile server early to reduce latency for raster layers
    remember { TileServerRegistry.warmup() }

    val holderRef = remember { Ref<HereViewHolder>() }
    val scope = remember { HereViewScope() }
    val controllerRef = remember { Ref<HereMapViewController>() }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val registry = remember { scope.buildRegistry() }
    val cameraState = remember { mutableStateOf<MapCameraPositionInterface?>(state.cameraPosition) }
    // Capture the desired initial camera before any early camera callbacks can overwrite state.
    val initialCameraPosition = remember(state.id) { state.cameraPosition }

    MapViewBase(
        state = state,
        cameraState = cameraState,
        modifier = modifier,
        sdkInitialize = {
            if (sdkInitialize != null) {
                sdkInitialize(context)
            } else {
                HereMapViewControllerStore.initSDK(context.applicationContext)
                true
            }
        },
        viewProvider = {
            // TEXTUREモードにしないとデバイスが回転したときに再描画を適切に行わない
            val viewOptions =
                MapViewOptions().also {
                    it.projection =
                        when (projection) {
                            MapProjection.Mercator -> HereMapProjection.WEB_MERCATOR
                            MapProjection.Globe -> HereMapProjection.GLOBE
                        }
                    it.renderMode = MapRenderMode.TEXTURE
                }

            MapView(context, viewOptions).apply {
                onCreate(null)
                onResume()
            }
        },
        holderProvider = { mapView ->
//            val lookAt = state.cameraPosition.toMapCameraUpdate()
//            mapView.camera.applyUpdate(lookAt)
            HereViewHolder(mapView, mapView.mapScene)
        },
        controllerProvider = { holder ->
            val rasterLayerController = getRasterLayerController(holder)
            val markerController =
                getMarkerController(
                    holder = holder,
                    markerTiling = markerTiling ?: MarkerTilingOptions.Default,
                )
            val polylineController = getPolylineController(holder)
            val polygonController = getPolygonController(holder)
            val groundImageController = getGroundImageController(holder)
            val circleController = getHereCircleController(holder)

            // Defer initial camera update until after controller is created and camera is moved

            val controller =
                HereMapViewController(
                    holder = holder,
                    markerController = markerController,
                    polylineController = polylineController,
                    polygonController = polygonController,
                    groundImageController = groundImageController,
                    circleController = circleController,
                    rasterLayerController = rasterLayerController,
                )
            controller.setMapClickListener(onMapClick)
            controller.setMapLongClickListener(onMapLongClick)
            state.setController(controller)
            controller.setMapDesignTypeChangeListener(state::onMapDesignTypeChange)
            cameraRestriction?.let { controller.setCameraRestriction(it) }

            holderRef.value = controller.holder
            controllerRef.value = controller

            val mapController = controller
            state.serviceRegistry.put(
                MarkerRenderingSupportKey,
                object : MarkerRenderingSupport<HereActualMarker> {
                    override fun createMarkerRenderer(
                        strategy: MarkerRenderingStrategyInterface<HereActualMarker>,
                    ): MarkerOverlayRendererInterface<HereActualMarker> = controller.createMarkerRenderer()

                    override fun createMarkerEventController(
                        controller: StrategyMarkerController<HereActualMarker>,
                        renderer: MarkerOverlayRendererInterface<HereActualMarker>,
                    ): MarkerEventControllerInterface<HereActualMarker> =
                        mapController.createMarkerEventController(controller)

                    override fun registerMarkerEventController(
                        controller: MarkerEventControllerInterface<HereActualMarker>,
                    ) {
                        mapController.registerMarkerEventController(controller)
                    }
                },
            )

            return@MapViewBase suspendCancellableCoroutine<HereMapViewController> { cont ->
                val resumed = AtomicBoolean(false)

                controller.holder.mapView.mapScene.loadScene(state.mapDesignType.getValue()) { mapError ->
                    if (mapError != null) {
                        throw Throwable("Loading map failed: mapError: " + mapError.name)
                    }

                    // Pre-warm HERE SDK's network stack by creating a dummy raster data source
                    // This triggers network reachability checks early
                    rasterLayerController.warmupNetworkIfNeeded(controller.holder)

                    // Start syncing camera only after the scene is ready; otherwise early camera updates
                    // can overwrite the initial camera (and then we'd re-apply the wrong value).
                    controller.setCameraMoveStartListener {
                        cameraState.value = it
                        state.updateCameraPosition(it)
                        onCameraMoveStart?.invoke(it)
                    }
                    controller.setCameraMoveListener {
                        cameraState.value = it
                        state.updateCameraPosition(it)
                        onCameraMove?.invoke(it)
                    }
                    controller.setCameraMoveEndListener {
                        cameraState.value = it
                        state.updateCameraPosition(it)
                        onCameraMoveEnd?.invoke(it)
                    }

                    // loadScene can reset the camera; re-apply the desired initial camera after wards.
                    controller.holder.mapView.post {
                        controller.moveCamera(MapCameraPosition.from(initialCameraPosition))
                        if (resumed.compareAndSet(false, true)) {
                            cont.resume(controller) { cause, _, _ -> }
                        }
                    }
                }
            }
        },
        scope = scope,
        registry = registry,
        onMapLoaded = onMapLoaded,
        customDisposableEffect = { _, holderRef ->

            // HERE specific DisposableEffect logic
            DisposableEffect(lifecycle) {
                val stateId = state.id // from BaseMapViewState
                val observer =
                    object : DefaultLifecycleObserver {
                        override fun onResume(owner: LifecycleOwner) {
                            // Do not call here to keep the MapView instance
                            // holderRef.value?.mapView?.onResume()
                        }

                        override fun onPause(owner: LifecycleOwner) {
                            // Do not call here to keep the MapView instance
                            // holderRef.value?.mapView?.onPause()
                        }

                        override fun onDestroy(owner: LifecycleOwner) {
                            val currentHolder = holderRef.value
                            if (currentHolder != null) {
                                val activity = context.findActivity()
                                if (activity?.isChangingConfigurations == true) {
                                    (currentHolder.mapView.parent as? ViewGroup)?.removeView(currentHolder.mapView)
                                } else {
                                    // Ensure these calls are safe if mapView might be null or already destroyed
                                    currentHolder.mapView.onPause()
                                    currentHolder.mapView.onDestroy()
                                    // Real teardown: free overlay controllers (tile-server
                                    // routes, marker managers) and cancel the controller scope.
                                    HereMapViewControllerStore.get(stateId)?.destroy()
                                    HereMapViewControllerStore.remove(stateId) // Clean up from your store
                                }
                            }
                        }
                    }
                lifecycle.addObserver(observer)
                onDispose {
                    lifecycle.removeObserver(observer)
                }
            }
        },
        // Pass content if it needs to be rendered within the overlay providers in MapViewBase,
        // or handle it here if it's specific to HereMapView structure before calling MapViewBase.
        // For now, assuming content relates to overlay definitions.
        content = content, // This might need adjustment based on how overlays are handled
    )
}

private fun getPolylineController(holder: HereViewHolder): HerePolylineController {
    val renderer =
        HerePolylineOverlayRenderer(
            holder = holder,
        )

    val controller =
        HerePolylineController(
            renderer = renderer,
        )
    return controller
}

private fun getMarkerController(
    holder: HereViewHolder,
    markerTiling: MarkerTilingOptions,
) = HereMarkerController.create(
    holder = holder,
    markerTiling = markerTiling,
)

private fun getHereCircleController(holder: HereViewHolder): HereCircleController {
    val renderer =
        HereCircleOverlayRenderer(
            holder = holder,
        )

    val controller =
        HereCircleController(
            renderer = renderer,
        )
    return controller
}

private fun getPolygonController(holder: HereViewHolder): HerePolygonController {
    val renderer =
        HerePolygonOverlayRenderer(
            holder = holder,
        )

    val controller =
        HerePolygonController(
            renderer = renderer,
        )
    return controller
}

private fun getRasterLayerController(holder: HereViewHolder): HereRasterLayerController {
    val tileServer = TileServerRegistry.get(forceNoStoreCache = true)
    val renderer =
        HereRasterLayerOverlayRenderer(
            holder = holder,
            tileServer = tileServer,
        )
    return HereRasterLayerController(
        renderer = renderer,
    )
}

private fun getGroundImageController(holder: HereViewHolder): HereGroundImageController {
    val tileServer = TileServerRegistry.get()
    val renderer =
        HereGroundImageOverlayRenderer(
            holder = holder,
            tileServer = tileServer,
        )
    return HereGroundImageController(renderer = renderer)
}
