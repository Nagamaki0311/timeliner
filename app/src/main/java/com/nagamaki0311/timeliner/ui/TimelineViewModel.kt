package com.nagamaki0311.timeliner.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nagamaki0311.timeliner.data.ImportSource
import com.nagamaki0311.timeliner.store.TimelineDb
import com.nagamaki0311.timeliner.store.TimelineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
