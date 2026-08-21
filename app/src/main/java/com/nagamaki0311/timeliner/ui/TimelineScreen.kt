package com.nagamaki0311.timeliner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nagamaki0311.timeliner.export.VideoOutput
import com.nagamaki0311.timeliner.playback.PlaybackTimeFormat
import com.nagamaki0311.timeliner.render.RouteOverlayView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap

/**
 * 期間指定（[PeriodSelector]）＋地図（[MapContainer]）＋ルート表示（[RouteOverlayView]）を組み合わせた画面（docs/tasks.md T-006）。
 * 選択期間のルートデータは[TimelineViewModel]から取得し、期間切替のたびにルート全体が収まるようカメラをfitBoundsする。
 */
@Composable
fun TimelineScreen(viewModel: TimelineViewModel, modifier: Modifier = Modifier) {
    val period by viewModel.selectedPeriod.collectAsStateWithLifecycle()
    val route by viewModel.routePoints.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var overlayView by remember { mutableStateOf<RouteOverlayView?>(null) }
    // シークバードラッグ中は`PlaybackController.seekTo`が再生ループを止めるため（docs/decisions.md D-008決定1）、
    // ドラッグ開始時点で再生中だったかを覚えておき、ドラッグ終了時に再生を再開する（一般的な動画プレーヤーのUX）。
    var isSeeking by remember { mutableStateOf(false) }
    var resumePlaybackAfterSeek by remember { mutableStateOf(false) }
    // 動画書き出しダイアログ(docs/tasks.md T-008)の表示・非表示自体はUI側のローカル状態で管理し、
    // 書き出し処理そのものの状態(ExportUiState)はViewModelが保持する（ImportScreenの確認ダイアログと同じ分離）。
    var exportDialogVisible by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        PeriodSelector(period = period, onPeriodChange = viewModel::selectPeriod)
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            MapContainer(
                modifier = Modifier.fillMaxSize(),
                onMapReady = { readyMap ->
                    map = readyMap
                    overlayView?.attachMap(readyMap)
                }
            )
            AndroidView(
                factory = { context ->
                    RouteOverlayView(context).also { view ->
                        overlayView = view
                        map?.let(view::attachMap)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        PlaybackControls(
            state = playbackState,
            onPlayPause = { if (playbackState.isPlaying) viewModel.pause() else viewModel.play() },
            onSeek = { progress ->
                if (!isSeeking) {
                    isSeeking = true
                    resumePlaybackAfterSeek = playbackState.isPlaying
                }
                viewModel.seekTo(progress)
            },
            onSeekFinished = {
                isSeeking = false
                if (resumePlaybackAfterSeek) viewModel.play()
            },
            onSpeedModeChange = viewModel::setSpeedMode
        )
        // 画面最下部の操作ボタンはナビゲーションバー（ジェスチャーバー含む）と重ならないよう
        // 自前で余白を確保する（MainActivity.ktのTabRowと同じ理由、docs/decisions.md D-012）。
        Button(
            onClick = { exportDialogVisible = true },
            enabled = map != null && route != null,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 8.dp)
        ) {
            Text("動画として保存")
        }
    }

    if (exportDialogVisible) {
        ExportDialog(
            state = exportState,
            onExport = { targetDurationMillis ->
                val currentMap = map
                if (currentMap != null) {
                    viewModel.exportVideo(context, currentMap, targetDurationMillis)
                }
            },
            onCancel = viewModel::cancelExport,
            onDismiss = {
                viewModel.dismissExport()
                exportDialogVisible = false
            },
            onShare = { videoUri ->
                runCatching { context.startActivity(VideoOutput.createShareIntent(videoUri)) }
            },
            onOpen = { videoUri ->
                runCatching { context.startActivity(VideoOutput.createViewIntent(videoUri)) }
            }
        )
    }

    LaunchedEffect(route, overlayView) {
        val view = overlayView ?: return@LaunchedEffect
        val currentRoute = route
        if (currentRoute == null) {
            view.setRoute(DoubleArray(0), DoubleArray(0), LongArray(0))
        } else {
            view.setRoute(currentRoute.latitudes, currentRoute.longitudes, currentRoute.timestampsMillis)
        }
    }

    // 地図下部に焼き込む日時テキストは、再生中の現在データ時刻（[playbackState.dataTimeMillis]、
    // T-008の動画書き出しでも同じRouteFrameRendererが使う想定）を優先し、未再生時は期間ラベルへフォールバックする。
    LaunchedEffect(period, playbackState.dataTimeMillis, overlayView) {
        overlayView?.setDateTimeText(playbackState.dataTimeMillis?.let(PlaybackTimeFormat::format) ?: period.label())
    }

    LaunchedEffect(playbackState.dataTimeMillis, overlayView) {
        overlayView?.setPlaybackDataTimeMillis(playbackState.dataTimeMillis)
    }

    LaunchedEffect(route, map) {
        val currentMap = map
        val currentRoute = route
        if (currentMap != null && currentRoute != null && currentRoute.latitudes.isNotEmpty()) {
            fitBounds(currentMap, currentRoute.latitudes, currentRoute.longitudes)
        }
    }
}

/** [latitudes]/[longitudes]全体が収まるようカメラを移動する。1点のみの場合はその点を中心に固定ズームで表示する。 */
private fun fitBounds(map: MapLibreMap, latitudes: DoubleArray, longitudes: DoubleArray) {
    if (latitudes.size == 1) {
        map.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitudes[0], longitudes[0]), SINGLE_POINT_ZOOM))
        return
    }
    val boundsBuilder = LatLngBounds.Builder()
    for (i in latitudes.indices) {
        boundsBuilder.include(LatLng(latitudes[i], longitudes[i]))
    }
    map.easeCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), FIT_BOUNDS_PADDING_PX))
}

private const val SINGLE_POINT_ZOOM = 15.0
private const val FIT_BOUNDS_PADDING_PX = 64
