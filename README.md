# HERE SDK for MapConductor Android

## Description

MapConductor provides a unified API for Android Jetpack Compose.
You can use HERE view with Jetpack Compose, but you can also switch to other Maps SDKs (such as MapLibre, GoogleMaps, and so on), anytimes.
Even you use the wrapper API, but you can still access to the native HERE view if you want.

## Setup

https://docs-android.mapconductor.com/setup/here/

## Usage

```kotlin
@Composable
fun MapView(modifier: Modififer = Modififer) {
    var selectedMarker by remember { mutableStateOf<MarkerState?>(null) }

    val center = GeoPoint(
        latitude = 52.530909,
        longitude = 13.385076,
    )

    val mapViewState =
        rememberHereMapViewState(
            cameraPosition =
                MapCameraPosition(
                    position = center,
                    zoom = 11.0,
                ),
        )

    val markerState = remember { MarkerState(
            position = center,
            icon = DefaultMarkerIcon().copy(
                label = "HERE Technologies",
                fillColor = Color(
                    red = 31,
                    green = 244,
                    blue = 229,
                )
            ),
            onClick = {
                selectedMarker = it
            }
        )
    }

    HereMapView(
        state = mapViewState,
        modifier = modifier,
    ) {
        Marker(markerState)

        selectedMarker?.let {
            InfoBubble(
                marker = it,
            ) {
                Text("Hello, world!")
            }
        }
    }
}
```

![](docs/images/basic-setup-here.png)


**Quick examples**
<table>
<tr>
  <td><a href="https://docs-android.mapconductor.com/components/mapviewstate/"><img src="docs/images/mapview.png"><br>Map</a></td>
  <td><pre>
val initCameraPosition = MapCameraPosition(
    position = GeoPoint(
        latitude = 52.530909,
        longitude = 13.385076,
    ),
    zoom = 17.0,
    tilt = 60.0,
    bearing = 30.0,
)
val mapViewState = rememberHereMapViewState(
    cameraPosition = initCameraPosition,
)

HereMapView(mapViewState)
</pre></td>
</tr>
<tr>
  <td><a href="https://docs-android.mapconductor.com/components/marker/"><img src="docs/images/marker.png"><br>Map</a></td>
  <td><pre>
val markerState = remember { MarkerState(
        position = GeoPoint(
            latitude = 52.530909,
            longitude = 13.385076,
        ),
        icon = DefaultMarkerIcon().copy(
            label = "HERE Technologies",
        ),
        onClick = {
            // Perform click action
        }
    ) }

HereMapView(
    state = mapViewState,
) {
    Marker(markerState)
}
</pre></td>
</tr>
</table>
