package com.nagamaki0311.timeliner.ui

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** 地図のベクタータイルスタイルURL。差し替える場合はここのみ変更する。 */
object MapConfig {
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    /** 地図帰属表示。[com.nagamaki0311.timeliner.render.RouteFrameRenderer]が画面・動画双方に焼き込む（docs/tasks.md T-006・T-008）。 */
    const val ATTRIBUTION_TEXT = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"
}

/**
 * @param onMapReady スタイル適用後の[MapLibreMap]を呼び出し元へ渡す（[com.nagamaki0311.timeliner.render.RouteOverlayView]の
 *   カメラ連動に使う、docs/tasks.md T-006）。地図単体表示のみで良い呼び出し元は省略してよい。
 */
@Composable
fun MapContainer(modifier: Modifier = Modifier, onMapReady: (MapLibreMap) -> Unit = {}) {
    val mapView = rememberMapViewWithLifecycle()

    // 初期化は`factory`（AndroidViewの生成時に一度だけ呼ばれる）で行う。`update`で行うと、
    // 呼び出し元（TimelineScreen等）の再コンポジションのたびに`setStyle`が再実行され、
    // スタイル（タイル）が無駄に再読み込みされてしまうため。
    AndroidView(
        factory = {
            mapView.getMapAsync { map ->
                map.setStyle(Style.Builder().fromUri(MapConfig.STYLE_URL)) {
                    // 後続タスクの座標変換を単純化するため、回転・チルトは無効化する。
                    map.uiSettings.isRotateGesturesEnabled = false
                    map.uiSettings.isTiltGesturesEnabled = false
                    onMapReady(map)
                }
            }
            mapView
        },
        modifier = modifier
    )
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(Bundle())
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    return mapView
}
