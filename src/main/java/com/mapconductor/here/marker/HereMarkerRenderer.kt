package com.mapconductor.here.marker

import com.here.sdk.core.Metadata
import com.here.sdk.mapview.MapMarker
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.marker.AbstractMarkerOverlayRenderer
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.here.HereActualMarker
import com.mapconductor.here.HereViewHolder
import com.mapconductor.here.toAnchor2D
import com.mapconductor.here.toGeoCoordinates
import com.mapconductor.here.toMapImage
import java.io.Serializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HereMarkerRenderer(
    holder: HereViewHolder,
    coroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : AbstractMarkerOverlayRenderer<
        HereViewHolder,
        HereActualMarker,
    >(
        holder = holder,
        coroutine = coroutine,
    ) {
    // HERE の drawOrder は整数で、しかも小さい値でしか効かない（タブレットの実機で 2849 は
    // 効き、4274 以上は無視されて足した順になった）。zIndex と位置を 1 つの値に詰める余地が
    // 無いので、全マーカーを [drawOrderOf]（zIndex が第 1 キー、同じなら画面で下が手前）で
    // 並べた順位を 0..[MAX_DRAW_ORDER] に割り振る。足す・変える・地図が回るたびに振り直す。
    private val live = LinkedHashMap<String, Pair<MapMarker, MarkerState>>()

    private fun track(
        marker: MapMarker,
        state: MarkerState,
    ) = synchronized(live) { live[state.id] = marker to state }

    private suspend fun assignDrawOrders() {
        val sorted = synchronized(live) { live.values.toList() }.sortedBy { (_, state) -> drawOrderOf(state) }
        if (sorted.isEmpty()) return
        val last = (sorted.size - 1).coerceAtLeast(1).toLong()
        withContext(coroutine.coroutineContext) {
            sorted.forEachIndexed { i, (marker, _) ->
                // 数が多いと順位を間引く（隣どうしが同じ値になり、その間だけ足した順になる）。
                val order = if (sorted.size <= MAX_DRAW_ORDER + 1) i else (i * MAX_DRAW_ORDER / last).toInt()
                if (marker.drawOrder != order) marker.drawOrder = order
            }
        }
    }

    // 地図が回ったら、今の向きで順位を振り直す。
    override suspend fun applyDrawOrder(entities: List<MarkerEntityInterface<HereActualMarker>>) = assignDrawOrders()

    override val supportsAnimationOverlay: Boolean = true

    override fun setMarkerVisible(
        markerEntity: MarkerEntityInterface<HereActualMarker>,
        visible: Boolean,
    ) {
        // HERE MapMarker has no visibility property; remove/re-add on the scene.
        coroutine.launch {
            val marker = markerEntity.marker ?: return@launch
            if (visible) {
                holder.mapView.mapScene.addMapMarkers(listOf(marker))
            } else {
                holder.mapView.mapScene.removeMapMarkers(listOf(marker))
            }
        }
    }

    override fun setMarkerPosition(
        markerEntity: MarkerEntityInterface<HereActualMarker>,
        position: GeoPoint,
    ) {
        coroutine.launch {
            markerEntity.marker?.coordinates = position.toGeoCoordinates()
        }
    }

    override suspend fun onAdd(data: List<MarkerOverlayRendererInterface.AddParamsInterface>): List<HereActualMarker?> {
        val markers =
            data.map { params ->
                val marker =
                    MapMarker(
                        GeoPoint.from(params.state.position).toGeoCoordinates(),
                        params.bitmapIcon.toMapImage(),
                        params.bitmapIcon.toAnchor2D(),
                    ).apply {
                        metadata =
                            Metadata().apply {
                                // Always include MapConductor marker id
                                setString("mc:id", params.state.id)
                                // Optional user-defined extras from MarkerState.extra
                                putExtras(params.state.extra)
                            }
                    }
                track(marker, params.state)
                return@map marker
            }

        coroutine.launch {
            holder.mapView.mapScene.addMapMarkers(markers)
        }
        return markers
    }

    override suspend fun onRemove(data: List<MarkerEntityInterface<HereActualMarker>>) {
        synchronized(live) { data.forEach { live.remove(it.state.id) } }
        coroutine.launch {
            val markers: List<HereActualMarker> = data.mapNotNull { params -> params.marker }
            if (markers.isNotEmpty()) {
                holder.mapView.mapScene.removeMapMarkers(markers)
            }
        }
    }

    override suspend fun onPostProcess() {
        assignDrawOrders()
    }

    override suspend fun onChange(
        data: List<MarkerOverlayRendererInterface.ChangeParamsInterface<HereActualMarker>>,
    ): List<HereActualMarker?> =
        data.mapNotNull { params ->
            val prevFinger = params.prev.fingerPrint
            val currFinger = params.current.fingerPrint
            if (!params.current.visible) return@mapNotNull null

            val marker = params.current.marker ?: return@mapNotNull null
            if (currFinger.icon != prevFinger.icon) {
                marker.image = params.bitmapIcon.toMapImage()
                marker.anchor = params.bitmapIcon.toAnchor2D()
            }
            marker.coordinates =
                GeoPoint.from(params.current.state.position).toGeoCoordinates()
            track(marker, params.current.state)

            // Hereはマーカーを再作成しなくてよいので、同じマーカーのインスタンスを返す
            marker
        }
}

/** マーカーの drawOrder に使う上限。実機で効いた最大（2849）より小さく取る。 */
private const val MAX_DRAW_ORDER = 2047

// Convert MarkerState.extra into HERE Metadata entries.
// Supports Map<String, Any?> by best-effort type mapping; otherwise stores value as string under key "mc:extra".
private fun Metadata.putExtras(extra: Serializable?) {
    if (extra == null) return
    when (extra) {
        is Map<*, *> -> {
            extra.forEach { (k, v) ->
                val key = k?.toString() ?: return@forEach
                when (v) {
                    null -> setString(key, "null")
                    is String -> setString(key, v)
                    is Int -> setInteger(key, v)
                    is Long -> setInteger(key, v.toInt())
                    is Float -> setDouble(key, v.toDouble())
                    is Double -> setDouble(key, v)
                    is Boolean -> setString(key, v.toString())
                    else -> setString(key, v.toString())
                }
            }
        }
        else -> {
            setString("mc:extra", extra.toString())
        }
    }
}
