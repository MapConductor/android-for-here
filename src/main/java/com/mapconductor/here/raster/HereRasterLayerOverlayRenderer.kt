package com.mapconductor.here.raster

import com.here.sdk.mapview.MapContentType
import com.here.sdk.mapview.MapLayer
import com.here.sdk.mapview.MapLayerBuilder
import com.here.sdk.mapview.datasource.RasterDataSource
import com.here.sdk.mapview.datasource.RasterDataSourceConfiguration
import com.here.sdk.mapview.datasource.RasterTileSource
import com.here.sdk.mapview.datasource.TileKey
import com.here.sdk.mapview.datasource.TileSource
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
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        // Tiles this process draws are handed over in process. HERE fetches
        // through its own network stack and, while the device reports no
        // connectivity, does not fetch at all -- not even from 127.0.0.1 --
        // so a URL-fed source goes blank the moment the network is away.
        // A tile source is asked for bytes and answered from the same
        // providers, with no network in between.
        val localTemplate = tileSpec.localTemplate
        val dataSource =
            if (localTemplate != null) {
                RasterDataSource(
                    holder.mapView.mapContext,
                    tileSpec.sourceName,
                    LocalRasterTileSource(tileServer, localTemplate, storageLevels),
                )
            } else {
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
                RasterDataSource(holder.mapView.mapContext, config)
            }

        return try {
            val layer =
                MapLayerBuilder()
                    .withName(tileSpec.layerName)
                    .withDataSource(tileSpec.sourceName, MapContentType.RASTER_IMAGE)
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
                    // The proxy route is local by construction; a direct
                    // template is local when it is one of the server's own.
                    localTemplate =
                        template.takeIf {
                            it.startsWith(tileServer.baseUrl + "/") &&
                                (routeId != null || source.scheme != TileScheme.TMS)
                        },
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
        /** The XYZ template on this process's tile server, when the tiles come from there. */
        val localTemplate: String? = null,
    )
}

/**
 * A HERE tile source answered from this process's tile server, with no HTTP
 * hop and no dependence on the device's connectivity.
 */
private class LocalRasterTileSource(
    private val tileServer: LocalTileServer,
    private val template: String,
    private val levels: List<Int>,
) : RasterTileSource {
    override fun getDataVersion(tileKey: TileKey): TileSource.DataVersion = VERSION

    override fun addListener(listener: TileSource.Listener) {}

    override fun removeListener(listener: TileSource.Listener) {}

    override fun getTilingScheme(): TilingScheme = TilingScheme.QUAD_TREE_MERCATOR

    override fun getStorageLevels(): List<Int> = levels

    override fun loadTile(
        tileKey: TileKey,
        handler: RasterTileSource.LoadResultHandler,
    ): TileSource.LoadTileRequestHandle {
        // Exactly one answer per request, whatever happens. A request HERE
        // cancels is still one it is waiting on: leave it unanswered and the
        // slot is never freed, and after a few gestures HERE stops asking for
        // tiles at all -- the map keeps the last picture and looks frozen.
        val answered = AtomicBoolean(false)

        fun answer(bytes: ByteArray?) {
            if (!answered.compareAndSet(false, true)) return
            if (bytes != null) handler.loaded(tileKey, bytes, METADATA) else handler.failed(tileKey)
        }
        // HERE counts rows from the south (Tokyo at z12 is row 2483, not
        // 1612); the templates count from the north, as XYZ does.
        val row = (1 shl tileKey.level) - 1 - tileKey.y
        val url =
            template
                .replace("{z}", tileKey.level.toString())
                .replace("{x}", tileKey.x.toString())
                .replace("{y}", row.toString())
        val job =
            scope.launch {
                answer(runCatching { tileServer.renderLocalTile(url) { !isActive } }.getOrNull())
            }
        // Cancelled, or the coroutine never ran: answered from a render
        // thread rather than from whichever thread cancelled it.
        job.invokeOnCompletion { if (!answered.get()) scope.launch { answer(null) } }
        return TileSource.LoadTileRequestHandle { job.cancel() }
    }

    /**
     * Renders run here and nowhere else.
     *
     * HERE asks for a screenful at once and every one of those is a tile to
     * draw. On `Dispatchers.Default` they take every thread the rest of the
     * SDK shares -- camera callbacks, overlays, the vector renderer itself --
     * and the map stops answering gestures while it fills. This is the same
     * width, and the same reasoning, as the tile server's own worker pool,
     * which is what the HTTP path went through.
     */
    private val scope = CoroutineScope(SupervisorJob() + renderDispatcher)

    private companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        val renderDispatcher = Dispatchers.Default.limitedParallelism(RENDER_WIDTH)

        /** Tile rendering is CPU-bound; more workers than cores adds contention, not throughput. */
        const val RENDER_WIDTH = 8

        val VERSION = TileSource.DataVersion(0, 0)

        /**
         * Tiles drawn here change only with their URL, so they are given a
         * long life -- but a plausible one. A date near the end of `Long`
         * does not survive the trip into the SDK: every tile came back
         * already expired, the same one was asked for again every 400 ms,
         * and after a pinch the map stopped asking for tiles at all.
         */
        val METADATA = TileSource.TileMetadata(VERSION, Date(System.currentTimeMillis() + TEN_YEARS_MS))

        const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000
    }
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
