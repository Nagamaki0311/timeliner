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
import com.nagamaki0311.timeliner.process.GeoBounds
import com.nagamaki0311.timeliner.store.PointBlobCodec
import com.nagamaki0311.timeliner.store.RouteOverview
import com.nagamaki0311.timeliner.store.RouteOverviewCache
import com.nagamaki0311.timeliner.store.TimelineDb
import com.nagamaki0311.timeliner.store.TimelineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapLibreMap
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** [ImportScreen]が表示するインポート処理の状態。 */
sealed interface ImportUiState {
    data object Idle : ImportUiState

    /**
     * パース処理中の進捗（docs/tasks.md T-015）。パーサ（`TimelineJsonParser.parseJson`/`parseZip`）
     * は総サイズ・総行数を事前に知らないストリーミング走査のため正確な割合は出せず、代わりに
     * 「これまでに読み取った点数」「これまでに見つかった日付範囲」を表示してユーザーが進行を確認できるようにする。
     * コールバックがまだ一度も呼ばれていない開始直後は既定値（[pointCount]=0、日付=null）のまま。
     *
     * [writing]は、パース完了後のDB書き込みフェーズ（`repository.commitImport`、`onProgress`は呼ばれない）を
     * 表す場合`true`。[TimelineViewModel.confirmOverwrite]はパース完了直前の点数・日付範囲を[writing]=`true`で
     * 引き継ぐことで、書き込み中も表示が唐突に消えないようにする（docs/decisions.md D-021決定1）。
     */
    data class InProgress(
        val pointCount: Int = 0,
        val earliestDate: String? = null,
        val latestDate: String? = null,
        val writing: Boolean = false
    ) : ImportUiState

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

    /**
     * [selectedPeriod]に対応するルートの点列。短期間（[SHORT_PERIOD_MAX_DAYS]日以下）は`days`行から
     * 全解像度で取得し、長期間は[RouteOverview]（日ごとに小予算でDPした概観点列）から該当区間を
     * 切り出す（docs/tasks.md T-014・docs/decisions.md D-017）。いずれも[com.nagamaki0311.timeliner.render.RouteOverlayView]側で
     * 表示ズームに応じてさらに簡略化する想定。データが無い期間は`null`。
     */
    private val _routePoints = MutableStateFlow<PointBlobCodec.DecodedPoints?>(null)
    val routePoints: StateFlow<PointBlobCodec.DecodedPoints?> = _routePoints.asStateFlow()

    /** [selectedPeriod]に対応するbbox。[fitBounds]用（docs/tasks.md T-014）。データが無い期間は`null`。 */
    private val _routeBounds = MutableStateFlow<GeoBounds.Bounds?>(null)
    val routeBounds: StateFlow<GeoBounds.Bounds?> = _routeBounds.asStateFlow()

    private val _isRouteLoading = MutableStateFlow(false)
    /** 選択期間のルート読み込み中（[RouteOverview]の初回構築を含みうる）にtrueになる（docs/tasks.md T-014）。 */
    val isRouteLoading: StateFlow<Boolean> = _isRouteLoading.asStateFlow()

    /**
     * [RouteOverview]のキャッシュ。初回アクセス時に[Dispatchers.Default]上で1度だけ構築し、
     * インポート成功時（[commitPreparedImport]）に無効化して再構築させる（docs/tasks.md T-014）。
     * 並行性ロジック（世代ガード）自体は[RouteOverviewCache]へ切り出し、DBに依存しない形で
     * JVM単体テスト（[RouteOverviewCacheTest][com.nagamaki0311.timeliner.store.RouteOverviewCacheTest]）
     * できるようにしている（docs/decisions.md D-020）。
     */
    private val routeOverviewCache = RouteOverviewCache(viewModelScope) { RouteOverview.build(repository) }

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

    /**
     * 「ユーザーが全期間（[PeriodType.ALL]）を意図しているか」の状態と、非同期な全期間解決と
     * ユーザー操作の競合を防ぐ世代ガードを保持する（docs/decisions.md D-023決定1・決定2）。
     */
    private val periodResolutionGate = PeriodResolutionGate()

    /**
     * アプリ起動時のオンスクリーン初期表示期間は全期間（[PeriodType.ALL]）とする（docs/decisions.md D-017決定1）。
     * DBに実在する最古日〜最新日は[TimelineRepository.queryDateRange]で非同期に解決する必要があるため、
     * [_selectedPeriod]の初期値はいったん暫定（今日の[PeriodType.DAY]、データ未取込の初回起動時と同じフォールバック）
     * にし、解決でき次第[PeriodType.ALL]へ差し替える（[resolveAndApplyAllPeriod]）。
     */
    init {
        viewModelScope.launch { resolveAndApplyAllPeriod() }
    }

    /**
     * 期間を切り替え、対応するルートデータを読み込み直す（docs/tasks.md T-006）。
     * [periodResolutionGate]へユーザーの明示選択を伝え、進行中（または今後resumeする）全期間解決が
     * この選択を後から上書きしないようにする（docs/decisions.md D-023決定2）。
     */
    fun selectPeriod(period: Period) {
        periodResolutionGate.selectExplicit()
        _selectedPeriod.value = period
        viewModelScope.launch { loadRoute(period) }
        enforceSpeedModeConstraint(period.type)
    }

    /**
     * 全期間（[PeriodType.ALL]）を選択する（docs/tasks.md T-017）。日付範囲はDBクエリで非同期に解決する必要があるため、
     * 同期的に完結する[selectPeriod]とは別経路にしている（[PeriodSelector]の`onSelectAll`から呼ばれる）。
     */
    fun selectAllPeriod() {
        viewModelScope.launch {
            resolveAndApplyAllPeriod()?.let { enforceSpeedModeConstraint(it.type) }
        }
    }

    /**
     * [resolveAllPeriod]で全期間を解決し、解決完了時点で依然として最新の要求であれば
     * （[PeriodResolutionGate.isCurrent]）[_selectedPeriod]・[loadRoute]へ反映する。
     * 解決中に[selectPeriod]でユーザーが別の期間へ切り替えていた場合は反映せず`null`を返す
     * （docs/decisions.md D-023決定2）。[init]・[selectAllPeriod]・インポート成功時（[commitPreparedImport]）の
     * 3箇所から呼ばれる共通経路。
     */
    private suspend fun resolveAndApplyAllPeriod(): Period? {
        val generation = periodResolutionGate.beginResolution()
        val period = resolveAllPeriod()
        if (!periodResolutionGate.isCurrent(generation)) return null
        _selectedPeriod.value = period
        loadRoute(period)
        return period
    }

    /**
     * [repository.queryDateRange]からDBに実在する最古日〜最新日を読み取り[Period.ofAll]を返す。
     * データが1件も無い場合（初回起動・未インポート）は今日の[PeriodType.DAY]へフォールバックする。
     */
    private suspend fun resolveAllPeriod(): Period {
        val range = withContext(Dispatchers.IO) { repository.queryDateRange() }
        return range?.let { (earliest, latest) -> Period.ofAll(LocalDate.parse(earliest), LocalDate.parse(latest)) }
            ?: Period.of(PeriodType.DAY, LocalDate.now())
    }

    fun play() = playbackController.play()
    fun pause() = playbackController.pause()
    fun seekTo(progress: Float) = playbackController.seekTo(progress)

    /**
     * [PlaybackController.setSpeedMode]は[PlaybackTimeline.buildAuto]等をMainスレッド外で実行するためsuspend化されている（T-013）。
     * 全期間（[PeriodType.ALL]）選択中に手動固定倍率モードへの切り替えが要求された場合は無視する（docs/decisions.md D-017決定2）。
     * UI（[PlaybackControls]）が手動ボタンを無効化していれば通常到達しないが、防御的にここでも判定する。
     */
    fun setSpeedMode(mode: SpeedMode) {
        if (mode is SpeedMode.Manual && !isManualModeAllowed(_selectedPeriod.value.type)) return
        viewModelScope.launch { playbackController.setSpeedMode(mode) }
    }

    /** [period]切り替え後、その期間種別で手動モードが許可されないのに現在の速度モードが手動なら自動モードへ強制切り替えする。 */
    private fun enforceSpeedModeConstraint(periodType: PeriodType) {
        if (!isManualModeAllowed(periodType) && playbackController.state.value.speedMode is SpeedMode.Manual) {
            setSpeedMode(SpeedMode.DEFAULT)
        }
    }

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
        if (route.latitudes.size < 2) {
            // PlaybackTimelineの総再生時間は点1つのみでは0になり、VideoExporter.export内部の
            // requireが投げる例外メッセージがそのまま露出してしまうため、ここで検出しユーザー向け文言にする
            // （docs/decisions.md D-010決定3）。
            _exportState.value = ExportUiState.Error("この期間はデータが少なく動画を作成できません")
            return
        }
        val appContext = context.applicationContext
        _exportState.value = ExportUiState.InProgress(0f)
        exportJob = viewModelScope.launch {
            val outputFile = File(appContext.cacheDir, "timeliner_export_${System.currentTimeMillis()}.mp4")
            // insert直後（コピー完了前）に発行されるMediaStore URI。コピー完了後に本コルーチンが
            // キャンセルされ戻り値が失われる場合でも、ここに残った値でロールバックできるようにする
            // （docs/decisions.md D-010決定1）。
            var mediaStoreUri: Uri? = null
            try {
                // buildAutoは560日規模（数十万点）では軽くないため、他のplaybackController経由の呼び出し
                // （T-013）と同様にMainスレッド外で実行する。
                val timeline = withContext(Dispatchers.Default) {
                    PlaybackTimeline.buildAuto(
                        route.timestampsMillis, route.latitudes, route.longitudes, targetDurationMillis
                    )
                }
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
                    VideoOutput.saveToMediaStore(appContext, outputFile, outputFile.name) { uri ->
                        mediaStoreUri = uri
                    }
                }
                _exportState.value = ExportUiState.Success(videoUri)
            } catch (e: CancellationException) {
                mediaStoreUri?.let { uri ->
                    withContext(Dispatchers.IO + NonCancellable) {
                        appContext.contentResolver.delete(uri, null, null)
                    }
                }
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
     * [period]に対応するルートを読み出し[_routePoints]・[_routeBounds]へ反映する。読み込み中に
     * [selectPeriod]で別の期間へ切り替わっていた場合、古い結果で上書きしない（連打対策）。
     * DB破損等（[PointBlobCodec.decode]の`require`失敗や`android.database.sqlite.SQLiteException`）で
     * 読み込みに失敗した場合、クラッシュさせずその期間はデータ無し（`null`）として扱う（docs/tasks.md T-009）。
     *
     * 期間が[SHORT_PERIOD_MAX_DAYS]日以下なら`days`行から全解像度で取得し（従来通り）、
     * それより長い期間は[RouteOverview]（日ごとに小予算でDPした概観点列、初回アクセス時に構築しキャッシュする）
     * から該当区間を二分探索で切り出す。これにより、大きな期間を選択するたびに全解像度データを
     * 毎回展開する（旧`mergeDayPoints`の全期間一括展開）コストを避ける（docs/tasks.md T-014・docs/decisions.md D-017）。
     */
    private suspend fun loadRoute(period: Period) {
        _isRouteLoading.value = true
        val loaded = try {
            val spanDays = ChronoUnit.DAYS.between(period.startDate, period.endDate) + 1
            if (spanDays <= SHORT_PERIOD_MAX_DAYS) {
                withContext(Dispatchers.IO) {
                    val days = repository.queryDays(period.startDate.toString(), period.endDate.toString())
                    if (days.isEmpty()) null else {
                        val merged = mergeDayPoints(days)
                        merged to GeoBounds.compute(merged.latitudes, merged.longitudes)
                    }
                }
            } else {
                val overview = ensureRouteOverview()
                withContext(Dispatchers.Default) { sliceOverview(overview, period) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "選択期間のルート読み込みに失敗しました: ${e.message}", e)
            null
        }
        if (_selectedPeriod.value == period) {
            _routePoints.value = loaded?.first
            _routeBounds.value = loaded?.second
            if (loaded == null) {
                playbackController.setRoute(DoubleArray(0), DoubleArray(0), LongArray(0))
            } else {
                val merged = loaded.first
                playbackController.setRoute(merged.latitudes, merged.longitudes, merged.timestampsMillis)
            }
            _isRouteLoading.value = false
        }
    }

    /**
     * [period]に対応する区間を[overview]から二分探索で切り出す（概観自体は共有し、切り出し結果のみ新規配列にする）。
     * bboxは[RouteOverview.boundsForDateRange]（日ごとの全解像度bboxの結合）を再利用し、DPで間引かれた
     * 点列から再計算しない（間引きで失われた極値を取りこぼさないため、[GeoBounds.compute]より正確）。
     */
    private fun sliceOverview(overview: RouteOverview, period: Period): Pair<PointBlobCodec.DecodedPoints, GeoBounds.Bounds>? {
        val startMillis = period.startDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMillis = period.endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        val range = overview.sliceRange(startMillis, endMillis) ?: return null
        val sliced = PointBlobCodec.DecodedPoints(
            overview.latitudes.copyOfRange(range.first, range.last + 1),
            overview.longitudes.copyOfRange(range.first, range.last + 1),
            overview.timestampsMillis.copyOfRange(range.first, range.last + 1)
        )
        // 通常はrangeが非nullならこの期間に含まれるdaysも存在するはずだが、念のためのフォールバック
        // （データ不整合等でboundsForDateRangeがnullを返す場合、切り出し済み点列から計算し直す）。
        val bounds = overview.boundsForDateRange(period.startDate.toString(), period.endDate.toString())
            ?: GeoBounds.compute(sliced.latitudes, sliced.longitudes)
        return sliced to bounds
    }

    /** [routeOverviewCache]を返す。未構築なら[Dispatchers.Default]上で1度だけ構築してキャッシュする。 */
    private suspend fun ensureRouteOverview(): RouteOverview = routeOverviewCache.ensure()

    /** インポート成功時に[routeOverviewCache]を無効化し、次回アクセス時に再構築させる。 */
    private fun invalidateRouteOverview() = routeOverviewCache.invalidate()

    /** [ImportUiState.ConfirmOverwrite]表示中に保持する、書き込み未実行の準備済みインポート。 */
    private var pendingImport: TimelineRepository.PreparedImport? = null

    /**
     * [uri]からファイルを読み込み、パース→クリーニング→上書き対象日数の検出までを実行する。
     * 上書きが発生しない場合はそのままDB書き込みまで行う。上書きが発生する場合は
     * [ImportUiState.ConfirmOverwrite]を表示し、[confirmOverwrite]が呼ばれるまで書き込みを保留する。
     */
    fun importFrom(context: Context, uri: Uri) {
        _importState.value = ImportUiState.InProgress()
        val appContext = context.applicationContext
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val rawTrack = ImportSource.readRawTrack(
                        appContext,
                        uri,
                        onProgress = { pointCount, earliestMillis, latestMillis ->
                            // パーサ内のバックグラウンドスレッド（Dispatchers.IO）から呼ばれるが、
                            // MutableStateFlow.valueへの代入はスレッドセーフなため問題ない
                            // （collectAsStateWithLifecycleが安全にMainへ届ける）。
                            _importState.value = ImportUiState.InProgress(
                                pointCount = pointCount,
                                earliestDate = millisToDateString(earliestMillis),
                                latestDate = millisToDateString(latestMillis)
                            )
                        },
                        // viewModelScope（を継承したwithContextのコルーチンコンテキスト）のJobが
                        // キャンセルされた（画面破棄等）後もIOスレッド上でパースが動き続けないようにする
                        // （docs/decisions.md D-021決定1）。
                        isActive = { isActive }
                    )
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

    /**
     * [ImportUiState.ConfirmOverwrite]表示中にユーザーが続行を選んだ場合に呼ぶ。書き込みを実行する。
     * DB書き込みフェーズ（[commitPreparedImport]）は`onProgress`が呼ばれないため、[ImportUiState.InProgress]を
     * 既定値へリセットせず、[pendingImport]（パース確定済みの点数・日付範囲）から引き継ぎ、[ImportUiState.InProgress.writing]=`true`
     * で表示を継続する（docs/decisions.md D-021決定1）。
     */
    fun confirmOverwrite() {
        val prepared = pendingImport ?: return
        pendingImport = null
        _importState.value = ImportUiState.InProgress(
            pointCount = prepared.pointCount,
            earliestDate = prepared.dayGroups.minOfOrNull { it.date },
            latestDate = prepared.dayGroups.maxOfOrNull { it.date },
            writing = true
        )
        viewModelScope.launch {
            commitPreparedImport(prepared)
        }
    }

    /** [ImportUiState.ConfirmOverwrite]表示中にユーザーが中止を選んだ場合に呼ぶ。書き込みを行わず[ImportUiState.Idle]へ戻る。 */
    fun cancelImport() {
        pendingImport = null
        _importState.value = ImportUiState.Idle
    }

    /** ミリ秒タイムスタンプを端末のデフォルトタイムゾーンで`YYYY-MM-DD`へ変換する（[ImportUiState.InProgress]表示用）。 */
    private fun millisToDateString(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private suspend fun commitPreparedImport(prepared: TimelineRepository.PreparedImport) {
        try {
            val result = withContext(Dispatchers.IO) { repository.commitImport(prepared) }
            // 新しいdays行が追加された可能性があるため、次回アクセス時にRouteOverviewを再構築させる（docs/tasks.md T-014）。
            invalidateRouteOverview()
            // ユーザーが全期間（ALL）を意図している場合、インポートで拡張された日付範囲を反映するため
            // 全期間の境界を再解決する。DAY/WEEK/MONTH/YEAR/CUSTOMを明示選択中の場合は上書きしない
            // （docs/decisions.md D-023決定1）。
            if (periodResolutionGate.isAllSelected) {
                resolveAndApplyAllPeriod()?.let { enforceSpeedModeConstraint(it.type) }
            }
            _importState.value = ImportUiState.Success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _importState.value = ImportUiState.Error(e.message ?: e::class.java.simpleName)
        }
    }

    companion object {
        private const val TAG = "TimelineViewModel"

        /**
         * [periodType]で手動固定倍率モード（[SpeedMode.Manual]）を選択可能かを返す（docs/decisions.md D-017決定2）。
         * 全期間（[PeriodType.ALL]）選択時は無効（自動モードのみ）。DB等に依存しない純粋関数として切り出し、
         * [TimelineViewModel]自体をインスタンス化できないJVM単体テストからも呼べるようにしている
         * （[TimelineViewModel]は`ViewModel`基底クラス・`TimelineRepository`のAndroid API依存でインスタンス化不可、
         * docs/decisions.md D-020と同じ制約）。
         */
        fun isManualModeAllowed(periodType: PeriodType): Boolean = periodType != PeriodType.ALL

        /**
         * この日数以下の期間は`days`行から全解像度で読み出し、これを超える期間は[RouteOverview]から
         * 切り出す（[loadRoute]、docs/tasks.md T-014）。将来調整可能なよう定数として公開する。
         */
        private const val SHORT_PERIOD_MAX_DAYS = 7

        /**
         * [TimelineRepository.DayRecord]のリスト（日付昇順）を1つの点列へ結合する。日付順＝時刻順であるため単純連結でよい。
         * 短期間（[SHORT_PERIOD_MAX_DAYS]日以下）の`queryDays`結果専用のヘルパー
         * （長期間は[RouteOverview]からの切り出しに置き換えたため、ここでは全期間一括展開はしない）。
         */
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
