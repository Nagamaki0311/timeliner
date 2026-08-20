package com.nagamaki0311.timeliner.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nagamaki0311.timeliner.data.ImportSource
import com.nagamaki0311.timeliner.export.VideoExporter
import com.nagamaki0311.timeliner.export.VideoOutput
import com.nagamaki0311.timeliner.model.Period
import com.nagamaki0311.timeliner.model.PeriodType
import com.nagamaki0311.timeliner.playback.PlaybackController
import com.nagamaki0311.timeliner.playback.PlaybackTimeline
import com.nagamaki0311.timeliner.playback.SpeedMode
import com.nagamaki0311.timeliner.store.PointBlobCodec
import com.nagamaki0311.timeliner.store.TimelineDb
import com.nagamaki0311.timeliner.store.TimelineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapLibreMap
import java.io.File
import java.time.LocalDate

/** [ImportScreen]が表示するインポート処理の状態。 */
sealed interface ImportUiState {
    data object Idle : ImportUiState
    data object InProgress : ImportUiState

    /**
     * 書き込み対象日付のうち[overwriteDayCount]日分が既存`days`行を上書きすることをユーザーへ確認する状態
     * （docs/decisions.md D-006）。[TimelineViewModel.confirmOverwrite]で書き込みを続行、
     * [TimelineViewModel.cancelImport]で中止できる。
     */
    data class ConfirmOverwrite(val overwriteDayCount: Int) : ImportUiState
    data class Success(val result: TimelineRepository.ImportResult) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

/** [ExportDialog]が表示する動画書き出し処理の状態（docs/tasks.md T-008）。 */
sealed interface ExportUiState {
    /** 未着手、または[TimelineViewModel.dismissExport]でダイアログを閉じた後の状態。目標再生時間の選択はここで行う。 */
    data object Idle : ExportUiState
    data class InProgress(val progress: Float) : ExportUiState
    data class Success(val videoUri: Uri) : ExportUiState
    data class Error(val message: String) : ExportUiState
}

/**
 * インポート実行・進捗状態を保持するViewModel（docs/tasks.md T-005・T-005b）。
 * ファイル選択→パース→クリーニング→DB書き込みはUIスレッドをブロックしないよう[Dispatchers.IO]で行う。
 */
class TimelineViewModel(private val repository: TimelineRepository) : ViewModel() {

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    private val _selectedPeriod = MutableStateFlow(Period.of(PeriodType.DAY, LocalDate.now()))
    val selectedPeriod: StateFlow<Period> = _selectedPeriod.asStateFlow()

    /** [selectedPeriod]に対応するルートの点列（未簡略化、[com.nagamaki0311.timeliner.render.RouteOverlayView]側で表示ズームに応じて簡略化する）。データが無い期間は`null`。 */
    private val _routePoints = MutableStateFlow<PointBlobCodec.DecodedPoints?>(null)
    val routePoints: StateFlow<PointBlobCodec.DecodedPoints?> = _routePoints.asStateFlow()

    /**
     * アニメーション再生の状態管理（docs/tasks.md T-007）。[selectedPeriod]のルートデータが変わるたびに
     * [PlaybackController.setRoute]で再構築する。
     */
    private val playbackController = PlaybackController(viewModelScope)
    val playbackState: StateFlow<PlaybackController.State> = playbackController.state

    /** 動画書き出しの状態管理（docs/tasks.md T-008）。 */
    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()
    private var exportJob: Job? = null

    init {
        viewModelScope.launch { loadRoute(_selectedPeriod.value) }
    }

    /** 期間を切り替え、対応するルートデータを読み込み直す（docs/tasks.md T-006）。 */
    fun selectPeriod(period: Period) {
        _selectedPeriod.value = period
        viewModelScope.launch { loadRoute(period) }
    }

    fun play() = playbackController.play()
    fun pause() = playbackController.pause()
    fun seekTo(progress: Float) = playbackController.seekTo(progress)
    fun setSpeedMode(mode: SpeedMode) = playbackController.setSpeedMode(mode)

    /**
     * 選択期間のルートを、目標再生時間[targetDurationMillis]（[SpeedMode.AUTO_DURATION_OPTIONS_MILLIS]から選択、
     * fps 30固定・音声トラックなし、D-002決定6）の動画として書き出す（docs/tasks.md T-008）。
     * 画面再生の速度モード（[playbackState.value.speedMode]）とは独立に、常に自動速度モード
     * （[PlaybackTimeline.buildAuto]）で書き出し用の写像を構築する（[ExportDialog]は目標再生時間のみを選ばせる設計のため）。
     * 書き込み・変換はUIスレッドをブロックしない（[VideoExporter.export]内部でCPU処理を[Dispatchers.Default]へ逃がす）。
     */
    fun exportVideo(context: Context, map: MapLibreMap, targetDurationMillis: Long) {
        val route = _routePoints.value
        if (route == null) {
            _exportState.value = ExportUiState.Error("書き出す期間にデータがありません")
            return
        }
        val appContext = context.applicationContext
        _exportState.value = ExportUiState.InProgress(0f)
        exportJob = viewModelScope.launch {
            val outputFile = File(appContext.cacheDir, "timeliner_export_${System.currentTimeMillis()}.mp4")
            try {
                val timeline = PlaybackTimeline.buildAuto(
                    route.timestampsMillis, route.latitudes, route.longitudes, targetDurationMillis
                )
                VideoExporter(appContext).export(
                    map = map,
                    latitudes = route.latitudes,
                    longitudes = route.longitudes,
                    timestampsMillis = route.timestampsMillis,
                    timeline = timeline,
                    outputFile = outputFile,
                    onProgress = { fraction -> _exportState.value = ExportUiState.InProgress(fraction) }
                )
                val videoUri = withContext(Dispatchers.IO) {
                    VideoOutput.saveToMediaStore(appContext, outputFile, outputFile.name)
                }
                _exportState.value = ExportUiState.Success(videoUri)
            } catch (e: CancellationException) {
                _exportState.value = ExportUiState.Idle
                throw e
            } catch (e: Exception) {
                _exportState.value = ExportUiState.Error(e.message ?: e::class.java.simpleName)
            } finally {
                outputFile.delete()
            }
        }
    }

    /** 書き出し中に[ExportDialog]の「キャンセル」から呼ばれる。中間ファイルは[exportVideo]のfinallyで削除される。 */
    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
    }

    /** 書き出しダイアログを閉じる（書き出し中は無視、[ExportUiState.Idle]へ戻す）。 */
    fun dismissExport() {
        if (_exportState.value !is ExportUiState.InProgress) {
            _exportState.value = ExportUiState.Idle
        }
    }

    /**
     * [period]に対応する`days`行をリポジトリから読み出し、日付昇順（＝時刻昇順）に結合して[_routePoints]へ反映する。
     * 読み込み中に[selectPeriod]で別の期間へ切り替わっていた場合、古い結果で上書きしない（連打対策）。
     */
    private suspend fun loadRoute(period: Period) {
        val merged = withContext(Dispatchers.IO) {
            val days = repository.queryDays(period.startDate.toString(), period.endDate.toString())
            if (days.isEmpty()) null else mergeDayPoints(days)
        }
        if (_selectedPeriod.value == period) {
            _routePoints.value = merged
            if (merged == null) {
                playbackController.setRoute(DoubleArray(0), DoubleArray(0), LongArray(0))
            } else {
                playbackController.setRoute(merged.latitudes, merged.longitudes, merged.timestampsMillis)
            }
        }
    }

    /** [ImportUiState.ConfirmOverwrite]表示中に保持する、書き込み未実行の準備済みインポート。 */
    private var pendingImport: TimelineRepository.PreparedImport? = null

    /**
     * [uri]からファイルを読み込み、パース→クリーニング→上書き対象日数の検出までを実行する。
     * 上書きが発生しない場合はそのままDB書き込みまで行う。上書きが発生する場合は
     * [ImportUiState.ConfirmOverwrite]を表示し、[confirmOverwrite]が呼ばれるまで書き込みを保留する。
     */
    fun importFrom(context: Context, uri: Uri) {
        _importState.value = ImportUiState.InProgress
        val appContext = context.applicationContext
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val rawTrack = ImportSource.readRawTrack(appContext, uri)
                    repository.prepareImport(rawTrack)
                }
                if (prepared.overwriteDayCount > 0) {
                    pendingImport = prepared
                    _importState.value = ImportUiState.ConfirmOverwrite(prepared.overwriteDayCount)
                } else {
                    commitPreparedImport(prepared)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _importState.value = ImportUiState.Error(e.message ?: e::class.java.simpleName)
            }
        }
    }

    /** [ImportUiState.ConfirmOverwrite]表示中にユーザーが続行を選んだ場合に呼ぶ。書き込みを実行する。 */
    fun confirmOverwrite() {
        val prepared = pendingImport ?: return
        pendingImport = null
        _importState.value = ImportUiState.InProgress
        viewModelScope.launch {
            commitPreparedImport(prepared)
        }
    }

    /** [ImportUiState.ConfirmOverwrite]表示中にユーザーが中止を選んだ場合に呼ぶ。書き込みを行わず[ImportUiState.Idle]へ戻る。 */
    fun cancelImport() {
        pendingImport = null
        _importState.value = ImportUiState.Idle
    }

    private suspend fun commitPreparedImport(prepared: TimelineRepository.PreparedImport) {
        try {
            val result = withContext(Dispatchers.IO) { repository.commitImport(prepared) }
            _importState.value = ImportUiState.Success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _importState.value = ImportUiState.Error(e.message ?: e::class.java.simpleName)
        }
    }

    companion object {
        /** [TimelineRepository.DayRecord]のリスト（日付昇順）を1つの点列へ結合する。日付順＝時刻順であるため単純連結でよい。 */
        private fun mergeDayPoints(days: List<TimelineRepository.DayRecord>): PointBlobCodec.DecodedPoints {
            val totalCount = days.sumOf { it.points.latitudes.size }
            val latitudes = DoubleArray(totalCount)
            val longitudes = DoubleArray(totalCount)
            val timestampsMillis = LongArray(totalCount)
            var offset = 0
            for (day in days) {
                val count = day.points.latitudes.size
                System.arraycopy(day.points.latitudes, 0, latitudes, offset, count)
                System.arraycopy(day.points.longitudes, 0, longitudes, offset, count)
                System.arraycopy(day.points.timestampsMillis, 0, timestampsMillis, offset, count)
                offset += count
            }
            return PointBlobCodec.DecodedPoints(latitudes, longitudes, timestampsMillis)
        }

        /** [Context]からリポジトリを組み立てるファクトリ。DIライブラリは導入しない（docs/decisions.md D-002）。 */
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = TimelineRepository(TimelineDb(context.applicationContext))
                return TimelineViewModel(repository) as T
            }
        }
    }
}
