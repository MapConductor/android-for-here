package com.mapconductor.here

import com.here.sdk.mapview.MapScheme
import com.mapconductor.core.map.AttributionRule
import com.mapconductor.core.map.MapDesignTypeInterface

typealias HereMapDesignType = MapDesignTypeInterface<MapScheme>

sealed class HereMapDesign(
    override val id: MapScheme,
    override val attributionRules: List<AttributionRule> = emptyList(),
) : HereMapDesignType {
    object NormalDay : HereMapDesign(MapScheme.NORMAL_DAY) // 通常の昼モード

    object NormalNight : HereMapDesign(MapScheme.NORMAL_NIGHT) // 通常の夜モード

    object Satellite : HereMapDesign(MapScheme.SATELLITE) // サテライト（衛星写真）モード

    object HybridDay : HereMapDesign(MapScheme.HYBRID_DAY) // サテライト＋道路情報（昼）

    object HybridNight : HereMapDesign(MapScheme.HYBRID_NIGHT) // サテライト＋道路情報（夜）

    object LiteDay : HereMapDesign(MapScheme.LITE_DAY) // ライト（軽量）昼モード

    object LiteNight : HereMapDesign(MapScheme.LITE_NIGHT) // ライト（軽量）夜モード

    object LiteHybridDay : HereMapDesign(MapScheme.LITE_HYBRID_DAY) // ライトハイブリッド昼モード

    object LiteHybridNight : HereMapDesign(MapScheme.LITE_HYBRID_NIGHT) // ライトハイブリッド夜モード

    object LogisticsDay : HereMapDesign(MapScheme.LOGISTICS_DAY) // 物流向け昼モード

    object LogisticsNight : HereMapDesign(MapScheme.LOGISTICS_NIGHT)

    object LogisticsHybridDay : HereMapDesign(MapScheme.LOGISTICS_HYBRID_DAY)

    object RoadNetworkDay : HereMapDesign(MapScheme.ROAD_NETWORK_DAY)

    object RoadNetworkNight : HereMapDesign(MapScheme.ROAD_NETWORK_NIGHT)

    class Custom(
        id: MapScheme,
        attributionRules: List<AttributionRule> = emptyList(),
    ) : HereMapDesign(id, attributionRules)

    override fun getValue(): MapScheme = id

    companion object {
        fun create(id: Int): HereMapDesign =
            when (id) {
                NormalDay.id.value -> NormalDay
                NormalNight.id.value -> NormalNight
                Satellite.id.value -> Satellite
                HybridDay.id.value -> HybridDay
                HybridNight.id.value -> HybridNight
                LiteDay.id.value -> LiteDay
                LiteNight.id.value -> LiteNight
                LiteHybridDay.id.value -> LiteHybridDay
                LiteHybridNight.id.value -> LiteHybridNight
                LogisticsDay.id.value -> LogisticsDay
                LogisticsNight.id.value -> LogisticsNight
                LogisticsHybridDay.id.value -> LogisticsHybridDay
                RoadNetworkDay.id.value -> RoadNetworkDay
                RoadNetworkNight.id.value -> RoadNetworkNight
                else -> throw IllegalArgumentException("Unsupported MapScene : $id")
            }
    }
}
