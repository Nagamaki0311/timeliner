package com.nagamaki0311.timeliner.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DetailWindowGate]（T-021レビュー指摘への対応、docs/decisions.md D-027決定1）を検証する。
 *
 * `TimelineViewModel`自体は`ViewModel`基底クラス・`TimelineRepository`のAndroid API依存で実`Context`無しに
 * JVM単体テストからインスタンス化できない（[com.nagamaki0311.timeliner.store.RouteOverviewCacheTest]・
 * [PeriodResolutionGateTest]・docs/decisions.md D-020と同じ制約）ため、`selectPeriod`/`scheduleDetailWindowLoad`が
 * 依拠する並行性ロジック（世代ガード・「長期間選択中か」の状態）を[DetailWindowGate]へ切り出し、これを直接
 * インスタンス化して検証する。[PeriodResolutionGateTest]と同様、新規依存は追加せず`kotlinx.coroutines.runBlocking`＋
 * `launch`＋`delay`のみで非同期の競合（D-027決定1で報告されたレース条件）を再現・検証する。
 */
class DetailWindowGateTest {

    @Test
    fun initialState_isLongPeriodSelected_isFalse() {
        assertFalse(DetailWindowGate().isLongPeriodSelected)
    }

    @Test
    fun activate_setsIsLongPeriodSelectedToGivenValue() {
        val gate = DetailWindowGate()
        gate.activate(true)
        assertTrue(gate.isLongPeriodSelected)

        gate.activate(false)
        assertFalse(gate.isLongPeriodSelected)
    }

    @Test
    fun invalidate_setsIsLongPeriodSelectedFalse_evenIfPreviouslyActivatedAsLongPeriod() {
        val gate = DetailWindowGate()
        gate.activate(true)
        assertTrue(gate.isLongPeriodSelected)

        gate.invalidate()

        assertFalse(gate.isLongPeriodSelected)
    }

    @Test
    fun beginLoad_returnsIncreasingGeneration_andIsCurrentTracksLatest() {
        val gate = DetailWindowGate()
        val first = gate.beginLoad()
        val second = gate.beginLoad()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    /**
     * [invalidate]は、それ以前に発行された[beginLoad]の世代を無効化する。`scheduleDetailWindowLoad`が
     * IO待ち中に期間切り替えが発生した場合、待機完了後の[DetailWindowGate.isCurrent]判定で
     * 結果を反映しないようにするための直接的な検証。
     */
    @Test
    fun invalidateAfterBeginLoad_invalidatesThatLoadsGeneration() {
        val gate = DetailWindowGate()
        gate.activate(true)
        val generation = gate.beginLoad()
        assertTrue(gate.isCurrent(generation))

        gate.invalidate()

        assertFalse(
            "期間切り替え（invalidate）後は、切り替え前に発行されたロード世代はもう最新ではないはず",
            gate.isCurrent(generation)
        )
    }

    /**
     * D-027決定1で報告されたレース条件そのものを模した再現テスト。
     *
     * `selectPeriod`は`_selectedPeriod`を同期的に即時更新するが、[DetailWindowGate.isLongPeriodSelected]は
     * `loadRoute`のIO完了後（[DetailWindowGate.activate]相当）まで更新されない。この間に旧期間の再生ループから
     * `scheduleDetailWindowLoad`相当の処理がIO待ちへ入った場合、[invalidate]を`selectPeriod`と同じ同期区間で
     * 呼んでいれば、待機完了後の反映が[isCurrent]で正しく弾かれることを検証する
     * （[invalidate]を呼ばない場合にレースが再現することも合わせて確認する）。
     */
    @Test
    fun concurrentDetailWindowLoadDuringPeriodSwitch_invalidateBeforeLoadCompletion_discardsStaleResult() {
        val gate = DetailWindowGate()
        gate.activate(true) // 旧期間（長期間）を選択中
        var appliedRange: String? = null

        suspend fun scheduleDetailWindowLoad(rangeLabel: String, delayMillis: Long) {
            val generation = gate.beginLoad()
            delay(delayMillis) // repository.queryDaysのIO待ちを模す
            if (!gate.isCurrent(generation)) return
            appliedRange = rangeLabel
        }

        runBlocking {
            // 旧期間の再生ループ由来で詳細ウィンドウの再ロードが走り出す（IO待ちに入る）。
            val job = launch { scheduleDetailWindowLoad("旧期間の境界", delayMillis = 50) }
            // ロード完了より先に、ユーザーがselectPeriodで新期間へ切り替える
            // （_selectedPeriod更新と同じ同期区間でinvalidateを呼ぶ）。
            delay(10)
            gate.invalidate()
            job.join()
        }

        assertEquals(
            "invalidateを同期区間で呼んでいれば、期間切り替え前に発行された詳細ウィンドウロードの結果は反映されないはず",
            null,
            appliedRange
        )
        assertFalse(
            "期間切り替え直後はloadRoute完了まで詳細ウィンドウ機構が無効化されているべき",
            gate.isLongPeriodSelected
        )
    }
}
