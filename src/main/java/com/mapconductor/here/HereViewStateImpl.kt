package com.mapconductor.here

import HereMapViewControllerInterface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.here.sdk.mapview.MapScheme
import com.mapconductor.compose.map.BaseMapViewSaver
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapCameraPositionInterface
import com.mapconductor.core.map.MapPaddings
import com.mapconductor.core.map.MapPaddingsInterface
import com.mapconductor.core.map.MapViewState
import com.mapconductor.core.map.MapViewStateBundle
import com.mapconductor.core.map.MapViewStateInterface
import java.util.UUID
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle

interface HereViewStateInterface : MapViewStateInterface<HereMapDesignType>

class HereViewState(
    override val id: String,
    mapDesignType: HereMapDesignType,
    cameraPosition: MapCameraPosition = MapCameraPosition.Default,
) : MapViewState<HereMapDesignType>(cameraPosition),
    HereViewStateInterface {
    private var controller: HereMapViewControllerInterface? = null

    private var _mapDesignType: HereMapDesignType = mapDesignType

    override var mapDesignType: HereMapDesignType
        set(value) {
            _mapDesignType = value
            this.controller?.setMapDesignType(value)
        }
        get() = _mapDesignType

    /** 戻り型をこのプロバイダのホルダーへ絞る（アプリが `?.map` を取れる形を保つため）。 */
    override fun getMapViewHolder(): HereViewHolder? = super.getMapViewHolder() as? HereViewHolder

    internal fun setController(controller: HereMapViewControllerInterface) {
        this.controller = controller
//        controller.setMapDesignType(_mapDesignType)
        attachController(controller)
    }

    internal fun onMapDesignTypeChange(value: HereMapDesignType) {
        _mapDesignType = value
    }

    internal fun updateCameraPosition(cameraPosition: MapCameraPosition) {
        setCameraPositionInternal(cameraPosition)
    }
}

class HereMapViewSaver : BaseMapViewSaver<HereViewState>() {
    override fun saveMapDesign(
        state: HereViewState,
        bundle: Bundle,
    ) {
        val design = state.mapDesignType
        val scheme = design.getValue().value
        bundle.putInt("id", scheme)
        // 定義済み（NormalDay など）以外は Custom として戻す。id だけだと Custom の
        // attributionRules が落ちる。
        bundle.putBoolean(KEY_CUSTOM, design !== presetOrNull(scheme))
        MapViewStateBundle.putAttributionRules(bundle, design.attributionRules)
    }

    override fun createState(
        stateId: String,
        mapDesignBundle: Bundle?,
        cameraPosition: MapCameraPosition,
    ): HereViewState {
        val scheme = mapDesignBundle?.getInt("id", HereMapDesign.NormalDay.id.value) ?: HereMapDesign.NormalDay.id.value
        val preset = presetOrNull(scheme)
        val mapScheme = MapScheme.values().firstOrNull { it.value == scheme }
        val design =
            if ((mapDesignBundle?.getBoolean(KEY_CUSTOM) == true || preset == null) && mapScheme != null) {
                HereMapDesign.Custom(
                    id = mapScheme,
                    attributionRules = MapViewStateBundle.getAttributionRules(mapDesignBundle),
                )
            } else {
                preset ?: HereMapDesign.NormalDay
            }
        return HereViewState(
            id = stateId,
            mapDesignType = design,
            cameraPosition = cameraPosition,
        )
    }

    private fun presetOrNull(scheme: Int): HereMapDesign? = runCatching { HereMapDesign.create(scheme) }.getOrNull()

    override fun getCameraPaddings(): MapPaddingsInterface? = MapPaddings.Zeros

    override fun getStateId(state: HereViewState): String = state.id
}

// 公開 API の門番（javap）にコンパニオンの定数が載らないよう、ファイル private にする。
private const val KEY_CUSTOM = "custom"

@Composable
fun rememberHereMapViewState(
    mapDesign: HereMapDesign = HereMapDesign.NormalDay,
    cameraPosition: MapCameraPositionInterface = MapCameraPosition.Default,
): HereViewState {
    val stateId by rememberSaveable {
        val uuid = UUID.randomUUID().toString()
        mutableStateOf(uuid)
    }
    val state =
        rememberSaveable(
            stateSaver = HereMapViewSaver().createSaver(),
        ) {
            mutableStateOf(
                HereViewState(
                    id = stateId,
                    mapDesignType = mapDesign,
                    cameraPosition = MapCameraPosition.from(cameraPosition),
                ),
            )
        }

    return state.value
}

internal fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
