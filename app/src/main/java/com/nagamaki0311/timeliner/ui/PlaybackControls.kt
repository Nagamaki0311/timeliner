package com.nagamaki0311.timeliner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nagamaki0311.timeliner.model.PeriodType
import com.nagamaki0311.timeliner.playback.PlaybackController
import com.nagamaki0311.timeliner.playback.PlaybackTimeFormat
import com.nagamaki0311.timeliner.playback.SpeedMode

/**
 * アニメーション再生の操作パネル（docs/tasks.md T-007）。
 * 再生/一時停止・シークバー・速度モード（自動/手動）の切り替え・現在のデータ日時を表示する。
 * 現在地の地名表示は行わない（[com.nagamaki0311.timeliner.model.TimelineSegment.placeId]/`placeName`は
 * [com.nagamaki0311.timeliner.store.TimelineRepository]に保持されているが、[TimelineViewModel.routePoints]は
 * 点列のみを扱い区間ごとのセグメント紐付けを持たないため、追加の突合ロジックが必要になる。
 * タスク指示「無ければ省略してよい」に従い、既知の制約としてここに記録し今回は実装しない）。
 *
 * [periodType]が[PeriodType.ALL]（全期間）の場合、手動固定倍率モードを無効化し自動モードのみ選択可能にする
 * （docs/decisions.md D-017決定2）。判定は[TimelineViewModel.isManualModeAllowed]に委譲する。
 */
@Composable
fun PlaybackControls(
    state: PlaybackController.State,
    periodType: PeriodType,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onSpeedModeChange: (SpeedMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onPlayPause) {
                Text(if (state.isPlaying) "一時停止" else "再生")
            }
            Text(
                text = state.dataTimeMillis?.let(PlaybackTimeFormat::format) ?: "--",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Slider(
            value = state.progress,
            onValueChange = onSeek,
            onValueChangeFinished = onSeekFinished,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "再生位置" }
        )
        SpeedModeRow(
            speedMode = state.speedMode,
            manualModeAllowed = TimelineViewModel.isManualModeAllowed(periodType),
            onSpeedModeChange = onSpeedModeChange
        )
    }
}

@Composable
private fun SpeedModeRow(speedMode: SpeedMode, manualModeAllowed: Boolean, onSpeedModeChange: (SpeedMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModeChoiceButton(
            label = "自動",
            selected = speedMode is SpeedMode.Auto,
            onClick = {
                val targetDurationMillis = (speedMode as? SpeedMode.Auto)?.targetDurationMillis
                    ?: SpeedMode.AUTO_DURATION_OPTIONS_MILLIS[1]
                onSpeedModeChange(SpeedMode.Auto(targetDurationMillis))
            }
        )
        ModeChoiceButton(
            label = "手動",
            selected = speedMode is SpeedMode.Manual,
            enabled = manualModeAllowed,
            onClick = {
                val multiplier = (speedMode as? SpeedMode.Manual)?.speedMultiplier
                    ?: SpeedMode.MANUAL_SPEED_MULTIPLIER_OPTIONS[0]
                onSpeedModeChange(SpeedMode.Manual(multiplier))
            }
        )
    }
    if (!manualModeAllowed) {
        Text(
            text = "全期間では自動モードのみ選択できます",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    when (speedMode) {
        is SpeedMode.Auto -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SpeedMode.AUTO_DURATION_OPTIONS_MILLIS.forEach { durationMillis ->
                ModeChoiceButton(
                    label = "${durationMillis / 1000}秒",
                    selected = speedMode.targetDurationMillis == durationMillis,
                    onClick = { onSpeedModeChange(SpeedMode.Auto(durationMillis)) }
                )
            }
        }
        is SpeedMode.Manual -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SpeedMode.MANUAL_SPEED_MULTIPLIER_OPTIONS.forEach { multiplier ->
                ModeChoiceButton(
                    label = "x${multiplier.toInt()}",
                    selected = speedMode.speedMultiplier == multiplier,
                    onClick = { onSpeedModeChange(SpeedMode.Manual(multiplier)) }
                )
            }
        }
    }
}

@Composable
private fun ModeChoiceButton(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            text = label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
