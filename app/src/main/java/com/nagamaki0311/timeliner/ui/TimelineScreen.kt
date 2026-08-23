package com.nagamaki0311.timeliner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
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
import com.nagamaki0311.timeliner.process.GeoBounds
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
    // 地図上への実際の描画（RouteOverlayView）は、長期間選択時は再生位置近傍だけ全解像度に差し替えた
    // displayRoutePointsを使う（docs/tasks.md T-021）。routeBounds（fitBounds用）・動画書き出し・
    // 「動画として保存」ボタンのenabled条件はスコープ外のためroutePoints（route）のまま変更しない。
    val displayRoute by viewModel.displayRoutePoints.collectAsStateWithLifecycle()
    val routeBounds by viewModel.routeBounds.collectAsStateWithLifecycle()
    val isRouteLoading by viewModel.isRouteLoading.collectAsStateWithLifecycle()
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

    // ランドスケープ+レガシー（2/3ボタン）ナビゲーションではナビゲーションバーが画面左右に移動するため、
    // TabRow/Button側の縦方向inset対応（docs/decisions.md D-012）と重複しないよう横方向のみ確保する（D-013）。
    val horizontalNavBarInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)

    Column(modifier = modifier.fillMaxSize()) {
        PeriodSelector(
            period = period,
            onPeriodChange = viewModel::selectPeriod,
            onSelectAll = viewModel::selectAllPeriod,
            modifier = Modifier.windowInsetsPadding(horizontalNavBarInsets)
        )
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
            // 長期間（RouteOverview経由）の初回読み込みは概観点列の構築を伴い一瞬で終わらないため、
            // 読み込み中であることが分かるよう表示する（docs/tasks.md T-014）。
            if (isRouteLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
        PlaybackControls(
            state = playbackState,
            periodType = period.type,
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
            onSpeedModeChange = viewModel::setSpeedMode,
            modifier = Modifier.windowInsetsPadding(horizontalNavBarInsets)
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

    LaunchedEffect(displayRoute, overlayView) {
        val view = overlayView ?: return@LaunchedEffect
        val currentRoute = displayRoute
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

    LaunchedEffect(route, routeBounds, map) {
        val currentMap = map
        val currentRoute = route
        val currentBounds = routeBounds
        if (currentMap != null && currentRoute != null && currentBounds != null && currentRoute.latitudes.isNotEmpty()) {
            fitBounds(currentMap, currentRoute.latitudes, currentRoute.longitudes, currentBounds)
        }
    }

    // 画面再生でのカメラ追従（docs/tasks.md T-024、docs/decisions.md D-028）。CameraDirector.computeKeyframes
    // （PlaybackControllerが再生位置ごとにplaybackState.activeCameraKeyframeへ計算済み）が切り替わるたびに
    // easeCameraで滑らかに追従する。再生中（isPlaying）のみ発火し、一時停止・停止中は上のfitBoundsや
    // ユーザーの手動パン・ズームを妨げないよう何もしない（再生停止時にfitBoundsへ戻す処理も行わない、
    // ユーザーがその場に留まって見続けられる方が自然という要件）。キーは
    // activeCameraKeyframe自体（値が変わった時のみ発火、同じキーフレームの間は毎フレーム再発火しない）。
    LaunchedEffect(playbackState.activeCameraKeyframe, playbackState.isPlaying, map) {
        val currentMap = map ?: return@LaunchedEffect
        val keyframe = playbackState.activeCameraKeyframe ?: return@LaunchedEffect
        if (!playbackState.isPlaying) return@LaunchedEffect
        currentMap.easeCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(keyframe.centerLatitude, keyframe.centerLongitude), keyframe.zoom),
            CAMERA_FOLLOW_DURATION_MILLIS
        )
    }
}

/**
 * [latitudes]/[longitudes]全体が収まるようカメラを移動する。1点のみの場合はその点を中心に固定ズームで表示する。
 * 点数分の[LatLng]オブジェクトを生成せず、[bounds]（[TimelineViewModel.routeBounds]、[GeoBounds.compute]または
 * [com.nagamaki0311.timeliner.store.RouteOverview.boundsForDateRange]で求めたbbox）の対角2点のみを
 * `LatLngBounds.Builder`へ渡す（560日規模でのオブジェクト生成コスト削減、docs/tasks.md T-013・T-014）。
 */
private fun fitBounds(map: MapLibreMap, latitudes: DoubleArray, longitudes: DoubleArray, bounds: GeoBounds.Bounds) {
    if (latitudes.size == 1) {
        map.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitudes[0], longitudes[0]), SINGLE_POINT_ZOOM))
        return
    }
    val boundsBuilder = LatLngBounds.Builder()
        .include(LatLng(bounds.minLatitude, bounds.minLongitude))
        .include(LatLng(bounds.maxLatitude, bounds.maxLongitude))
    map.easeCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), FIT_BOUNDS_PADDING_PX))
}

private const val SINGLE_POINT_ZOOM = 15.0
private const val FIT_BOUNDS_PADDING_PX = 64

/**
 * 画面再生でのカメラ追従（[TimelineScreen]内の`LaunchedEffect(playbackState.activeCameraKeyframe, ...)`）で
 * 使う`easeCamera`のアニメーション時間。キーフレーム間隔の既定値（`CameraDirector`の5秒）より十分短い
 * 固定値とすることで、次のキーフレームへ切り替わる前にアニメーションが収まり、滑らかに追従しているように
 * 見えるようにする（docs/tasks.md T-024。次のキーフレームまでの実際の間隔を都度受け渡す設計も検討したが、
 * AGENTS.md判定ラダー「過度に複雑な配線をしない」に従い固定値を採用した）。
 */
private const val CAMERA_FOLLOW_DURATION_MILLIS = 900
