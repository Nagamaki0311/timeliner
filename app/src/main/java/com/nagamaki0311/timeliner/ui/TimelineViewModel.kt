package com.nagamaki0311.timeliner.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nagamaki0311.timeliner.data.ImportSource
import com.nagamaki0311.timeliner.model.Period
import com.nagamaki0311.timeliner.model.PeriodType
import com.nagamaki0311.timeliner.store.PointBlobCodec
import com.nagamaki0311.timeliner.store.TimelineDb
import com.nagamaki0311.timeliner.store.TimelineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    init {
        viewModelScope.launch { loadRoute(_selectedPeriod.value) }
    }

    /** 期間を切り替え、対応するルートデータを読み込み直す（docs/tasks.md T-006）。 */
    fun selectPeriod(period: Period) {
        _selectedPeriod.value = period
        viewModelScope.launch { loadRoute(period) }
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
