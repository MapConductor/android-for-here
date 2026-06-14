package com.mapconductor.here.raster

import com.here.sdk.mapview.MapContentType
import com.here.sdk.mapview.MapLayer
import com.here.sdk.mapview.MapLayerBuilder
import com.here.sdk.mapview.datasource.RasterDataSource
import com.here.sdk.mapview.datasource.RasterDataSourceConfiguration
import com.here.sdk.mapview.datasource.TileUrlProviderCallback
import com.here.sdk.mapview.datasource.TileUrlProviderFactory
import com.here.sdk.mapview.datasource.TilingScheme
import com.mapconductor.core.raster.RasterLayerEntityInterface
import com.mapconductor.core.raster.RasterLayerOverlayRendererInterface
import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.raster.TileScheme
import com.mapconductor.core.tileserver.LocalTileServer
import com.mapconductor.core.tileserver.TileProviderInterface
import com.mapconductor.core.tileserver.TileRequest
import com.mapconductor.here.HereViewHolder
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class HereRasterLayerOverlayRenderer(
    private val holder: HereViewHolder,
    private val tileServer: LocalTileServer,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : RasterLayerOverlayRendererInterface<HereRasterLayerHandle> {
    override suspend fun onAdd(
        data: List<RasterLayerOverlayRendererInterface.AddParamsInterface>,
    ): List<HereRasterLayerHandle?> =
        data.map { params ->
            addLayer(params.state)
        }

    override suspend fun onChange(
        data: List<RasterLayerOverlayRendererInterface.ChangeParamsInterface<HereRasterLayerHandle>>,
    ): List<HereRasterLayerHandle?> =
        data.map { params ->
            val prev = params.prev
            val next = params.current.state
            if (prev.state.source != next.source || prev.state.opacity != next.opacity) {
                removeLayer(prev)
                addLayer(next)
            } else {
                updateLayer(prev.layer, next)
                prev.layer
            }
        }

    override suspend fun onRemove(data: List<RasterLayerEntityInterface<HereRasterLayerHandle>>) {
        data.forEach { entity ->
            removeLayer(entity)
        }
    }

    override suspend fun onPostProcess() {}

    private fun addLayer(state: RasterLayerState): HereRasterLayerHandle? {
        val routeId =
            if (needsOpacityProxy(state)) {
                "here-raster-" + safeId(state.id)
            } else {
                null
            }
        if (routeId != null) {
            tileServer.register(routeId, HereRasterTileProxyProvider(state))
        }

        val tileSpec = resolveTileSpec(state, routeId)
        if (tileSpec == null) {
            routeId?.let { tileServer.unregister(it) }
            Log.e("HereRasterLayer", "resolveTileSpec returned null!")
            return null
        }
        val urlProvider = tileSpec.provider
        val storageLevels = tileSpec.storageLevels
        val dataSourceProvider =
            RasterDataSourceConfiguration.Provider(
                urlProvider,
                TilingScheme.QUAD_TREE_MERCATOR,
                storageLevels,
            )
        dataSourceProvider.hasAlphaChannel = true
        val cache =
            RasterDataSourceConfiguration.Cache(
                holder.mapView.context.cacheDir.absolutePath,
            )
        val config =
            RasterDataSourceConfiguration(
                tileSpec.sourceName,
                dataSourceProvider,
                cache,
            )
        val dataSource = RasterDataSource(holder.mapView.mapContext, config)

        return try {
            val layer =
                MapLayerBuilder()
                    .withName(tileSpec.layerName)
                    .withDataSource(config.name, MapContentType.RASTER_IMAGE)
                    .forMap(holder.mapView.hereMap)
                    .build()
            layer.setEnabled(state.visible)
            HereRasterLayerHandle(
                dataSource = dataSource,
                layer = layer,
                sourceName = tileSpec.sourceName,
                layerName = tileSpec.layerName,
                routeId = routeId,
            )
        } catch (e: MapLayerBuilder.InstantiationException) {
            routeId?.let { tileServer.unregister(it) }
            dataSource.destroy()
            Log.e("HereRasterLayer", "Failed to create raster layer: ${e.message}", e)
            null
        }
    }

    private fun updateLayer(
        handle: HereRasterLayerHandle,
        state: RasterLayerState,
    ) {
        handle.layer.setEnabled(state.visible)
    }

    private fun removeLayer(entity: RasterLayerEntityInterface<HereRasterLayerHandle>) {
        val handle = entity.layer
        handle.routeId?.let { tileServer.unregister(it) }
        handle.layer.destroy()
        handle.dataSource.destroy()
    }

    private fun resolveTileSpec(
        state: RasterLayerState,
        routeId: String?,
    ): TileSpec? =
        when (val source = state.source) {
            is RasterLayerSource.UrlTemplate -> {
                val template =
                    if (routeId == null) {
                        source.template
                    } else {
                        tileServer.urlTemplateWithQueryCacheKey(
                            routeId,
                            source.tileSize,
                            state.fingerPrint().hashCode().toString(),
                        )
                    }
                val provider =
                    if (routeId == null && source.scheme == TileScheme.TMS) {
                        TileUrlProviderCallback { x, y, zoom ->
                            val max = 1 shl zoom
                            val tmsY = max - 1 - y
                            template
                                .replace("{x}", x.toString())
                                .replace("{y}", tmsY.toString())
                                .replace("{z}", zoom.toString())
                        }
                    } else {
                        TileUrlProviderFactory.fromXyzUrlTemplate(template)
                            ?: TileUrlProviderCallback { x, y, zoom ->
                                template
                                    .replace("{x}", x.toString())
                                    .replace("{y}", y.toString())
                                    .replace("{z}", zoom.toString())
                            }
                    }
                TileSpec(
                    provider = provider,
                    sourceName = "raster-source-${state.id}",
                    layerName = "raster-layer-${state.id}",
                    storageLevels = buildStorageLevels(source.minZoom, source.maxZoom),
                )
            }
            is RasterLayerSource.TileJson -> {
                Log.w("HereRasterLayer", "HERE SDK does not support TileJson raster sources.")
                null
            }
            is RasterLayerSource.ArcGisService -> {
                val template =
                    if (routeId == null) {
                        val base = source.serviceUrl.trimEnd('/')
                        "$base/tile/{z}/{y}/{x}"
                    } else {
                        tileServer.urlTemplateWithQueryCacheKey(
                            routeId,
                            RasterLayerSource.DEFAULT_TILE_SIZE,
                            state.fingerPrint().hashCode().toString(),
                        )
                    }
                val provider =
                    TileUrlProviderFactory.fromXyzUrlTemplate(template)
                        ?: return null
                TileSpec(
                    provider = provider,
                    sourceName = "raster-source-${state.id}",
                    layerName = "raster-layer-${state.id}",
                    storageLevels = buildStorageLevels(null, null),
                )
            }
        }

    private fun buildStorageLevels(
        minZoom: Int?,
        maxZoom: Int?,
    ): List<Int> {
        val min = minZoom ?: 0
        val max = maxZoom ?: 20
        return (min..max).toList()
    }

    private fun needsOpacityProxy(state: RasterLayerState): Boolean = state.opacity.coerceIn(0.0f, 1.0f) < 0.999f

    private fun safeId(id: String): String =
        id
            .map { ch ->
                when {
                    ch.isLetterOrDigit() -> ch
                    ch == '-' || ch == '_' || ch == '.' -> ch
                    else -> '_'
                }
            }.joinToString("")

    private data class TileSpec(
        val provider: TileUrlProviderCallback,
        val sourceName: String,
        val layerName: String,
        val storageLevels: List<Int>,
    )
}

data class HereRasterLayerHandle(
    val dataSource: RasterDataSource,
    val layer: MapLayer,
    val sourceName: String,
    val layerName: String,
    val routeId: String?,
)

private class HereRasterTileProxyProvider(
    private val state: RasterLayerState,
) : TileProviderInterface {
    private val fetchCacheLock = Any()
    private val fetchCache =
        object : LruCache<String, ByteArray>(DEFAULT_FETCH_CACHE_SIZE_BYTES) {
            override fun sizeOf(
                key: String,
                value: ByteArray,
            ): Int = value.size
        }

    override fun renderTile(request: TileRequest): ByteArray? {
        val url = resolveUrl(request) ?: return null
        val bytes = fetch(url) ?: return null
        return applyOpacity(bytes, state.opacity)
    }

    private fun resolveUrl(request: TileRequest): String? =
        when (val source = state.source) {
            is RasterLayerSource.UrlTemplate -> {
                val y =
                    if (source.scheme == TileScheme.TMS) {
                        (1 shl request.z) - 1 - request.y
                    } else {
                        request.y
                    }
                source.template
                    .replace("{x}", request.x.toString())
                    .replace("{y}", y.toString())
                    .replace("{z}", request.z.toString())
            }
            is RasterLayerSource.ArcGisService ->
                "${source.serviceUrl.trimEnd('/')}/tile/${request.z}/${request.y}/${request.x}"
            is RasterLayerSource.TileJson -> null
        }

    private fun fetch(url: String): ByteArray? {
        synchronized(fetchCacheLock) {
            fetchCache.get(url)?.let { return it }
        }

        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 10000
                requestMethod = "GET"
                setRequestProperty("User-Agent", state.userAgent)
                state.extraHeaders?.forEach { (key, value) ->
                    setRequestProperty(key, value)
                }
            }
        return try {
            if (connection.responseCode !in 200..299) {
                return null
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            synchronized(fetchCacheLock) {
                fetchCache.put(url, bytes)
            }
            bytes
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun applyOpacity(
        bytes: ByteArray,
        opacity: Float,
    ): ByteArray? {
        val safeOpacity = opacity.coerceIn(0.0f, 1.0f)
        if (safeOpacity >= 0.999f) {
            return bytes
        }

        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (safeOpacity * 255.0f).toInt().coerceIn(0, 255)
            }
        canvas.drawBitmap(source, 0.0f, 0.0f, paint)

        val stream = ByteArrayOutputStream()
        output.compress(Bitmap.CompressFormat.PNG, 100, stream)
        output.recycle()
        source.recycle()
        return stream.toByteArray()
    }

    companion object {
        private const val DEFAULT_FETCH_CACHE_SIZE_BYTES = 16 * 1024 * 1024
    }
}
