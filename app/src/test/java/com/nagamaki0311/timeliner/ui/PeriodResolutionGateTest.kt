package com.nagamaki0311.timeliner.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PeriodResolutionGate]（T-017レビュー指摘への対応、docs/decisions.md D-023決定1・決定2）を検証する。
 *
 * `TimelineViewModel`自体は`ViewModel`基底クラス・`TimelineRepository`のAndroid API依存で実`Context`無しに
 * JVM単体テストからインスタンス化できない（[com.nagamaki0311.timeliner.store.RouteOverviewCacheTest]・
 * docs/decisions.md D-020と同じ制約）ため、`resolveAndApplyAllPeriod`が依拠する並行性ロジック（世代ガード・
 * 「全期間を意図しているか」の状態）を[PeriodResolutionGate]へ切り出し、これを直接インスタンス化して検証する。
 * [RouteOverviewCacheTest]と同様、新規依存は追加せず`kotlinx.coroutines.runBlocking`＋`launch`＋`delay`のみで
 * 非同期の競合（decision2）を検証する。
 */
class PeriodResolutionGateTest {

    @Test
    fun initialState_isAllSelected_isTrue() {
        // 起動時の既定は全期間（docs/decisions.md D-017決定1）。
        assertTrue(PeriodResolutionGate().isAllSelected)
    }

    /**
     * DBが空の初回起動時、`resolveAllPeriod`は今日の[com.nagamaki0311.timeliner.model.PeriodType.DAY]へ
     * フォールバックするが、これは「ユーザーが明示選択したDAY」ではなく「全期間を意図した暫定フォールバック」
     * である（docs/decisions.md D-023決定1）。[beginResolution]呼び出し（フォールバック解決も含む）だけでは
     * [isAllSelected]はtrueのままであることを確認し、区別ができていることを検証する。
     */
    @Test
    fun fallbackResolutionWithoutExplicitSelection_isAllSelectedRemainsTrue() {
        val gate = PeriodResolutionGate()
        val generation = gate.beginResolution()
        // resolveAllPeriodがDBの空データによりPeriodType.DAY（今日）へフォールバックした状況を模す。
        // このフォールバックはユーザーの明示選択ではないため、isAllSelectedはtrueのまま。
        assertTrue(gate.isCurrent(generation))
        assertTrue(
            "フォールバックはユーザーの明示選択ではないため、isAllSelectedはtrueのままであるべき",
            gate.isAllSelected
        )
    }

    /** ユーザーがDAY/WEEK/MONTH/YEAR/CUSTOMを明示選択（`selectPeriod`相当）した後は、以降のインポート成功時にALLへ再解決してはいけない。 */
    @Test
    fun selectExplicit_setsIsAllSelectedFalse() {
        val gate = PeriodResolutionGate()
        gate.selectExplicit()
        assertFalse(gate.isAllSelected)
    }

    /** ALLを明示選択（`selectAllPeriod`相当）した後にDAY等へ切り替え、再度ALLを選ぶと意図は全期間へ戻る。 */
    @Test
    fun beginResolutionAfterSelectExplicit_setsIsAllSelectedTrueAgain() {
        val gate = PeriodResolutionGate()
        gate.selectExplicit()
        assertFalse(gate.isAllSelected)
        val generation = gate.beginResolution()
        assertTrue(gate.isAllSelected)
        assertTrue(gate.isCurrent(generation))
    }

    /**
     * 全期間解決中（[beginResolution]済み、`resolveAllPeriod`のIO待ち相当）にユーザーが
     * [selectExplicit]（`selectPeriod`相当）を呼んだ場合、その解決の世代はもはや最新ではなくなり、
     * 解決完了時に[isCurrent]で弾かれるべき（docs/decisions.md D-023決定2）。
     */
    @Test
    fun selectExplicitDuringInFlightResolution_invalidatesThatResolutionsGeneration() {
        val gate = PeriodResolutionGate()
        val generation = gate.beginResolution()
        assertTrue(gate.isCurrent(generation))

        gate.selectExplicit()

        assertFalse(
            "解決中にユーザーが明示選択したら、その解決の世代はもう最新ではないはず",
            gate.isCurrent(generation)
        )
        assertFalse(gate.isAllSelected)
    }

    /** 全期間解決が複数回（連続タップ・init直後のインポート等で）重なった場合、最新の世代のみがisCurrentになる。 */
    @Test
    fun multipleBeginResolutionCalls_onlyLatestGenerationIsCurrent() {
        val gate = PeriodResolutionGate()
        val first = gate.beginResolution()
        val second = gate.beginResolution()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    /**
     * `TimelineViewModel.init`が起動する非同期の全期間解決（`resolveAllPeriod`のIO待ち）が完了する前に、
     * ユーザーが`selectPeriod`で別の期間を選んだ場合、`init`側の代入がユーザーの選択を後から上書きしては
     * いけない（docs/decisions.md D-023決定2、T-017レビューMedium指摘）。
     * `resolveAndApplyAllPeriod`（`_selectedPeriod`への代入までを含む）の挙動を[delay]付きで模して検証する
     * （[com.nagamaki0311.timeliner.store.RouteOverviewCacheTest]と同種の手法）。
     */
    @Test
    fun concurrentInitResolutionAndUserSelectPeriod_resolutionDoesNotOverwriteUserChoice() {
        val gate = PeriodResolutionGate()
        var appliedPeriod: String? = null

        suspend fun resolveAndApply(resolvedValue: String, delayMillis: Long) {
            val generation = gate.beginResolution()
            delay(delayMillis) // resolveAllPeriodのDBクエリ（Dispatchers.IO）待ちを模す
            if (!gate.isCurrent(generation)) return
            appliedPeriod = resolvedValue
        }

        runBlocking {
            // initが起動する全期間解決（時間がかかる）。
            val job = launch { resolveAndApply("ALL(2024-01-01〜2026-08-21)", delayMillis = 50) }
            // 解決が完了するより先に、ユーザーがDAYを明示選択する（selectPeriod相当、同期的に即代入する）。
            delay(10)
            gate.selectExplicit()
            appliedPeriod = "DAY(today)"
            job.join()
        }

        assertEquals(
            "initの遅延解決が完了時に、後から行われたユーザーのDAY選択を上書きしてはいけない",
            "DAY(today)",
            appliedPeriod
        )
        assertFalse(gate.isAllSelected)
    }

    /**
     * インポート成功時、ユーザーがALLを選択中（[isAllSelected]=true）であれば再解決対象になる
     * （呼び出し側の`if (periodResolutionGate.isAllSelected) resolveAndApplyAllPeriod()`の判定に使う値）。
     */
    @Test
    fun isAllSelectedTrue_afterInitOrSelectAllPeriod_indicatesImportShouldReResolve() {
        val gate = PeriodResolutionGate()
        // 初期状態（起動直後、まだ何も明示選択していない）でも全期間を意図している。
        assertTrue(gate.isAllSelected)

        gate.beginResolution() // selectAllPeriod相当
        assertTrue(gate.isAllSelected)
    }

    /** ユーザーがDAY等を明示選択中であれば、インポート成功時に再解決してはいけない（対象外）。 */
    @Test
    fun isAllSelectedFalse_afterSelectExplicit_indicatesImportShouldNotReResolve() {
        val gate = PeriodResolutionGate()
        gate.selectExplicit()
        assertFalse(gate.isAllSelected)
    }
}
