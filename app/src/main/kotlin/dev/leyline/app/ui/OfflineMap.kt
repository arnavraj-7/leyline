package dev.leyline.app.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Street map with every phone that has shared a GPS fix. Tiles come from
 * OpenFreeMap (free, no key). "Save area offline" downloads about 4 km around
 * us while there is internet; after that the map works in airplane mode,
 * because MapLibre serves the same style from its offline database.
 */
@Composable
fun OfflineMap(state: MeshUiState, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var centred by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m -> m.setStyle(Style.Builder().fromUri(STYLE_URL)) { addPhoneLayers(it); map = m } }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    val me = state.topology.positions[state.topology.self]
    val sos = state.sightings.values.filter { it.info.carryingSos }.map { it.info.node }.toSet()
    Column {
        @SuppressLint("ClickableViewAccessibility")
        AndroidView(
            factory = {
                // Let the map pan inside the scrolling screen instead of the page scrolling.
                mapView.setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }
                mapView
            },
            modifier = modifier.clip(RoundedCornerShape(16.dp)),
            update = {
                val m = map ?: return@AndroidView
                val features = state.topology.positions.map { (node, location) ->
                    val self = node == state.topology.self
                    val color = when {
                        node in sos -> MeshColors.sos
                        self -> MeshColors.mesh
                        node in state.topology.direct -> MeshColors.linked
                        else -> MeshColors.relay
                    }
                    Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude)).apply {
                        addStringProperty("name", if (self) "You" else state.topology.names[node] ?: node.toString().takeLast(4))
                        addStringProperty("color", hex(color))
                    }
                }
                m.style?.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
                if (!centred && me != null) {
                    m.cameraPosition = CameraPosition.Builder().target(LatLng(me.latitude, me.longitude)).zoom(15.0).build()
                    centred = true
                }
            },
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                status ?: if (me == null) "Waiting for your GPS fix" else "Save the map while you have internet",
                Modifier.weight(1f),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    val target = me?.let { LatLng(it.latitude, it.longitude) } ?: map?.cameraPosition?.target ?: return@OutlinedButton
                    status = "Starting download…"
                    saveArea(context, target) { status = it }
                },
            ) { Text("Save area offline") }
        }
    }
}

private fun addPhoneLayers(style: Style) {
    style.addSource(GeoJsonSource(SOURCE))
    style.addLayer(
        CircleLayer("phones-dot", SOURCE).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    style.addLayer(
        SymbolLayer("phones-name", SOURCE).withProperties(
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textHaloColor("#000000"),
            PropertyFactory.textHaloWidth(1.2f),
        ),
    )
}

/** Downloads every tile within ~[AREA_DEGREES] of [centre], zoom 10..16; vector tiles overzoom past that. */
private fun saveArea(context: android.content.Context, centre: LatLng, onStatus: (String) -> Unit) {
    val bounds = LatLngBounds.from(
        centre.latitude + AREA_DEGREES, centre.longitude + AREA_DEGREES,
        centre.latitude - AREA_DEGREES, centre.longitude - AREA_DEGREES,
    )
    val definition = OfflineTilePyramidRegionDefinition(STYLE_URL, bounds, 10.0, 16.0, context.resources.displayMetrics.density)
    OfflineManager.getInstance(context).createOfflineRegion(
        definition,
        "leyline".toByteArray(),
        object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        if (status.isComplete) {
                            onStatus("Saved: map works offline here (${status.completedResourceSize / 1_048_576} MB)")
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        } else if (status.requiredResourceCount > 0) {
                            onStatus("Downloading ${100 * status.completedResourceCount / status.requiredResourceCount}%")
                        }
                    }

                    override fun onError(error: OfflineRegionError) = onStatus("Download failed: ${error.message}")

                    override fun mapboxTileCountLimitExceeded(limit: Long) = onStatus("Area too big ($limit tiles max)")
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) = onStatus("Download failed: $error")
        },
    )
}

private fun hex(color: Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
private const val SOURCE = "phones"
private const val AREA_DEGREES = 0.02
