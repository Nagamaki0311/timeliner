package com.nagamaki0311.timeliner.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * [PlaybackController]の速度モード（docs/tasks.md T-007）。
 * 選ばれたモードから[PlaybackTimeline]を構築する（[PlaybackController.setSpeedMode]）。
 */
sealed interface SpeedMode {
    /** 関心度に基づく自動速度（[PlaybackTimeline.buildAuto]）。[targetDurationMillis]は総再生時間の目標値。 */
    data class Auto(val targetDurationMillis: Long) : SpeedMode

    /** 一定倍率の手動速度（[PlaybackTimeline.buildManual]）。 */
    data class Manual(val speedMultiplier: Double) : SpeedMode

    companion object {
        /** UIで選択させる自動モードの目標再生時間の候補（docs/decisions.md D-017、T-018）。 */
        val AUTO_DURATION_OPTIONS_MILLIS = listOf(30_000L, 60_000L, 120_000L, 180_000L, 300_000L)

        /** UIで選択させる手動モードの倍率候補。 */
        val MANUAL_SPEED_MULTIPLIER_OPTIONS = listOf(60.0, 300.0, 1800.0, 3600.0)

        /**
         * 自動モードの既定の目標再生時間（60秒、docs/decisions.md D-017、T-018）。
         * [AUTO_DURATION_OPTIONS_MILLIS]内の並び順に依存しない明示的な定数として、
         * [PlaybackControls]/[com.nagamaki0311.timeliner.ui.ExportDialog]の既定値選択から参照される。
         */
        const val DEFAULT_AUTO_DURATION_MILLIS = 60_000L

        /** 既定の速度モード（自動・60秒、D-017/T-018）。 */
        val DEFAULT: SpeedMode = Auto(targetDurationMillis = DEFAULT_AUTO_DURATION_MILLIS)
    }
}

/**
 * 選択期間のルートデータから[PlaybackTimeline]を構築し、再生・一時停止・シークを管理する
 * （docs/tasks.md T-007）。[com.nagamaki0311.timeliner.ui.TimelineViewModel]が`viewModelScope`を渡して保持する。
 *
 * 再生中は[FRAME_INTERVAL_MILLIS]間隔のコルーチンループ（`delay`）で経過時間を進める。
 * Composeの`withFrameNanos`も選択肢だったが、ViewModel側で状態を一元管理でき実装がシンプルなこちらを採用した
 * （タスク指示: 「実装しやすい方でよい。過剰に凝った実装は避ける」）。
 */
class PlaybackController(private val scope: CoroutineScope) {

    /** [PlaybackController]が公開する再生状態。 */
    data class State(
        val isPlaying: Boolean = false,
        /** 再生進捗（0.0〜1.0）。ルート未設定時は0。 */
        val progress: Float = 0f,
        /** 現在の再生位置に対応するデータ時刻（epochミリ秒）。ルート未設定時はnull。 */
        val dataTimeMillis: Long? = null,
        val speedMode: SpeedMode = SpeedMode.DEFAULT
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private data class RouteData(val latitudes: DoubleArray, val longitudes: DoubleArray, val timestampsMillis: LongArray)

    private var route: RouteData? = null
    private var timeline: PlaybackTimeline? = null
    private var elapsedPlaybackMillis = 0L
    private var playbackJob: Job? = null

    /**
     * [rebuildTimeline]の呼び出し世代。呼び出しごとに増分し、[Dispatchers.Default]上での計算完了時に
     * 最新世代と一致するかを確認することで、古い呼び出しの結果が新しい呼び出しの結果を上書きしないようにする
     * （T-013タスク4、`setRoute`/`setSpeedMode`が[withContext]の中断点を挟んで交錯しうるため）。
     */
    private var rebuildGeneration = 0L

    /**
     * 表示するルートの点列を設定する（時刻昇順）。既存の再生は停止し、進捗を先頭へ戻して[timeline]を再構築する。
     * [PlaybackTimeline.buildAuto]は560日規模（数十万点）では軽くないため、[Dispatchers.Default]上で実行する
     * （T-013）。
     */
    suspend fun setRoute(latitudes: DoubleArray, longitudes: DoubleArray, timestampsMillis: LongArray) {
        pause()
        route = if (timestampsMillis.isEmpty()) null else RouteData(latitudes, longitudes, timestampsMillis)
        elapsedPlaybackMillis = 0L
        rebuildTimeline()
    }

    /** 速度モードを切り替える。既存の再生は停止し、進捗を先頭へ戻して[timeline]を再構築する（T-013、[setRoute]と同様の理由で非同期化）。 */
    suspend fun setSpeedMode(mode: SpeedMode) {
        pause()
        elapsedPlaybackMillis = 0L
        _state.update { it.copy(speedMode = mode) }
        rebuildTimeline()
    }

    private suspend fun rebuildTimeline() {
        val myGeneration = ++rebuildGeneration
        val currentRoute = route
        val mode = _state.value.speedMode
        val newTimeline = if (currentRoute == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                when (mode) {
                    is SpeedMode.Auto -> PlaybackTimeline.buildAuto(
                        currentRoute.timestampsMillis, currentRoute.latitudes, currentRoute.longitudes, mode.targetDurationMillis
                    )
                    is SpeedMode.Manual -> PlaybackTimeline.buildManual(currentRoute.timestampsMillis, mode.speedMultiplier)
                }
            }
        }
        // withContext中に別のsetRoute/setSpeedMode呼び出しが後から開始・完了していたら、古い結果で上書きしない。
        if (myGeneration != rebuildGeneration) return
        timeline = newTimeline
        publishState()
    }

    /** 再生を開始する。ルート未設定、または既に末尾まで再生完了している場合は末尾判定に応じて先頭から再開する。 */
    fun play() {
        val currentTimeline = timeline ?: return
        if (_state.value.isPlaying) return
        // 総再生時間が0（点数1等、アニメーションする区間が無い）の場合は再生状態にする意味がない。
        if (currentTimeline.totalPlaybackMillis() <= 0L) return
        if (elapsedPlaybackMillis >= currentTimeline.totalPlaybackMillis()) {
            elapsedPlaybackMillis = 0L
        }
        _state.update { it.copy(isPlaying = true) }
        playbackJob = scope.launch {
            var lastFrameNanos = System.nanoTime()
            while (isActive) {
                delay(FRAME_INTERVAL_MILLIS)
                val nowNanos = System.nanoTime()
                val deltaMillis = (nowNanos - lastFrameNanos) / 1_000_000L
                lastFrameNanos = nowNanos
                val total = currentTimeline.totalPlaybackMillis()
                elapsedPlaybackMillis = (elapsedPlaybackMillis + deltaMillis).coerceAtMost(total)
                publishState()
                if (elapsedPlaybackMillis >= total) {
                    _state.update { it.copy(isPlaying = false) }
                    break
                }
            }
        }
    }

    /** 再生を一時停止する。現在の進捗は保持する。 */
    fun pause() {
        playbackJob?.cancel()
        playbackJob = null
        if (_state.value.isPlaying) {
            _state.update { it.copy(isPlaying = false) }
        }
    }

    /**
     * 指定した進捗（0.0〜1.0）へシークする。再生中/一時停止中いずれでも呼べる。
     * 再生ループが動いていると自動更新される[elapsedPlaybackMillis]とシーク値が競合するため、
     * [pause]と同等の処理で再生ループを停止してから進捗を書き換える（docs/decisions.md D-008決定1）。
     * シーク後も再生を続けたい場合、呼び出し元（[com.nagamaki0311.timeliner.ui.TimelineScreen]）が
     * シーク完了時に明示的に[play]を呼ぶ。
     */
    fun seekTo(progress: Float) {
        val currentTimeline = timeline ?: return
        pause()
        elapsedPlaybackMillis = (progress.coerceIn(0f, 1f) * currentTimeline.totalPlaybackMillis()).toLong()
        publishState()
    }

    private fun publishState() {
        val currentTimeline = timeline
        val total = currentTimeline?.totalPlaybackMillis() ?: 0L
        val progress = if (currentTimeline == null || total <= 0L) 0f else (elapsedPlaybackMillis.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        val dataTime = currentTimeline?.dataTimeAtPlaybackMillis(elapsedPlaybackMillis)
        _state.update { it.copy(progress = progress, dataTimeMillis = dataTime) }
    }

    companion object {
        /** 再生ループの更新間隔（約60fps）。 */
        private const val FRAME_INTERVAL_MILLIS = 16L
    }
}

/**
 * [PlaybackController.State.dataTimeMillis]（epochミリ秒）の表示用フォーマット。
 * [com.nagamaki0311.timeliner.ui.PlaybackControls]（操作パネル上の日時表示）と
 * [com.nagamaki0311.timeliner.render.RouteOverlayView]（地図上への焼き込み、T-008でも共用）の両方から使う
 * 共通ロジックのため、専用ファイルを増やさずここへ置く。
 */
object PlaybackTimeFormat {
    private val FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    fun format(dataTimeMillis: Long): String =
        Instant.ofEpochMilli(dataTimeMillis).atZone(ZoneId.systemDefault()).format(FORMATTER)
}
