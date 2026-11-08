package com.mapconductor.here

import com.here.sdk.mapview.MapLayerPriority
import com.here.sdk.mapview.MapLayerPriorityBuilder

/**
 * 重なり順（ラスター < GroundImage < ポリゴン < サークル < ポリライン < マーカー）。
 *
 * HERE では:
 * - ポリゴン・サークル・ポリラインは map item で、`drawOrder` を 1 本の数で比べる。
 *   帯ごとに区間を分けて割り当てる（帯の中は zIndex の順）。
 * - ラスターと GroundImage は MapLayer。既定の優先度だと map item より上に描かれて
 *   ポリゴン等を覆うので、ベースマップのレイヤー（ルートの手前）に入れる。
 *   ラスター → GroundImage の順になるよう、別々のレイヤーの手前を指定する。
 * - マーカーは map item の上に描かれる（drawOrder はマーカーどうしの順）。
 */
internal object HereOverlayStacking {
    private const val BAND = 340

    fun polygonDrawOrder(zIndex: Int): Int = zIndex.coerceIn(0, BAND - 1)

    fun circleDrawOrder(zIndex: Int): Int = BAND + zIndex.coerceIn(0, BAND - 1)

    fun polylineDrawOrder(zIndex: Int): Int = 2 * BAND + zIndex.coerceIn(0, 1023 - 2 * BAND)

    /** ラスター: ベースマップの "route" レイヤーの手前（GroundImage より下）。 */
    fun rasterPriority(): MapLayerPriority = MapLayerPriorityBuilder().renderedBeforeLayer(RASTER_ANCHOR).build()

    /** GroundImage: "traffic_on_route" レイヤーの手前（ラスターより上、map item より下）。 */
    fun groundImagePriority(): MapLayerPriority = MapLayerPriorityBuilder().renderedBeforeLayer(GROUND_IMAGE_ANCHOR).build()

    private const val RASTER_ANCHOR = "route"
    private const val GROUND_IMAGE_ANCHOR = "traffic_on_route"
}
