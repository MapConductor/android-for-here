# HereViewScope

## Signature

```kotlin
class HereViewScope : MapViewScope()
```

## Description

`HereViewScope` is a specialized scope class for the HERE Maps implementation within the
MapConductor framework. It extends the base `MapViewScope`, inheriting all common map
functionalities.

This scope is the context for map configuration and control when using HERE Maps as the provider.
It is provided as the receiver within the `content` lambda of `HereMapView`, where overlay
composables are called.

## Example

`HereViewScope` is provided as the receiver within the `content` lambda of `HereMapView`.
Overlay composables are called within this scope.

```kotlin
HereMapView(
    state = mapState,
    modifier = Modifier.fillMaxSize(),
) {
    // 'this' is HereViewScope
    // Add overlays using composables from MapViewScope here.
    Marker(state = MarkerState(id = "marker-1", position = GeoPoint(35.681236, 139.767125)))
}
```
