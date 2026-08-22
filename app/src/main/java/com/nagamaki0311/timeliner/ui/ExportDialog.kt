package com.nagamaki0311.timeliner.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nagamaki0311.timeliner.playback.SpeedMode

/**
 * 動画書き出しダイアログ（docs/tasks.md T-008）。目標再生時間の選択（[SpeedMode.AUTO_DURATION_OPTIONS_MILLIS]、
 * fpsは30固定・音声トラックなし、D-002決定6）→進捗表示→完了後の「共有」「アプリで開く」を1つのダイアログ内で切り替える。
 */
@Composable
fun ExportDialog(
    state: ExportUiState,
    onExport: (targetDurationMillis: Long) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onShare: (Uri) -> Unit,
    onOpen: (Uri) -> Unit
) {
    var selectedDurationMillis by remember { mutableStateOf(SpeedMode.DEFAULT_AUTO_DURATION_MILLIS) }

    AlertDialog(
        onDismissRequest = { if (state !is ExportUiState.InProgress) onDismiss() },
        title = { Text("動画として保存") },
        text = {
            Column {
                when (state) {
                    is ExportUiState.Idle -> {
                        Text("再生時間を選択してください（fps: 30固定、音声なし）")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            SpeedMode.AUTO_DURATION_OPTIONS_MILLIS.forEach { durationMillis ->
                                TextButton(onClick = { selectedDurationMillis = durationMillis }) {
                                    Text(
                                        text = "${durationMillis / 1000}秒",
                                        fontWeight = if (selectedDurationMillis == durationMillis) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selectedDurationMillis == durationMillis) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }
                    is ExportUiState.InProgress -> {
                        Text("書き出し中… ${(state.progress * 100).toInt()}%")
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is ExportUiState.Success -> {
                        Text("書き出しが完了しました")
                    }
                    is ExportUiState.Error -> {
                        Text("書き出しに失敗しました: ${state.message}", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is ExportUiState.Idle -> TextButton(onClick = { onExport(selectedDurationMillis) }) { Text("開始") }
                is ExportUiState.InProgress -> TextButton(onClick = onCancel) { Text("キャンセル") }
                is ExportUiState.Success -> TextButton(onClick = { onOpen(state.videoUri) }) { Text("アプリで開く") }
                is ExportUiState.Error -> TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        },
        dismissButton = {
            when (state) {
                is ExportUiState.Idle -> TextButton(onClick = onDismiss) { Text("キャンセル") }
                is ExportUiState.Success -> TextButton(onClick = { onShare(state.videoUri) }) { Text("共有") }
                else -> Unit
            }
        }
    )
}
