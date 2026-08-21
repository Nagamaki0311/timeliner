package com.nagamaki0311.timeliner.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * ファイル選択（SAF）→インポート進捗表示→完了サマリ表示を行う画面（docs/tasks.md T-005・T-005b）。
 * 地図上へのルート描画自体はT-006で行うため、本画面ではサマリ表示までにとどめる。
 * 既存`days`行を上書きするインポートの場合、書き込み実行前に確認ダイアログを表示する（docs/decisions.md D-006）。
 */
@Composable
fun ImportScreen(viewModel: TimelineViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by viewModel.importState.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importFrom(context, it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // ナビゲーションバーと重ならないよう余白を確保する（MainActivity.ktのTabRowと同じ理由、
            // docs/decisions.md D-012）。本画面はTOPが常に地図/インポートタブの下にあり
            // ステータスバーとは重ならないため、下側のみで足りる。
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(
            onClick = { launcher.launch(arrayOf("*/*")) },
            enabled = state !is ImportUiState.InProgress
        ) {
            Text("タイムラインファイル（.json / .zip）を選択")
        }

        Spacer(modifier = Modifier.padding(top = 16.dp))

        when (val current = state) {
            is ImportUiState.Idle -> {
                Text("Google Timelineのエクスポートファイルを選択してインポートしてください")
            }
            is ImportUiState.InProgress -> {
                CircularProgressIndicator()
                Text("インポート中…")
            }
            is ImportUiState.ConfirmOverwrite -> {
                Text("確認待ち…")
            }
            is ImportUiState.Success -> {
                val result = current.result
                Text("インポートが完了しました")
                Text("点数: ${result.pointCount}件 / セグメント数: ${result.segmentCount}件")
                Text("期間: ${result.earliestDate ?: "-"} 〜 ${result.latestDate ?: "-"}（${result.dayCount}日分）")
            }
            is ImportUiState.Error -> {
                Text(
                    "インポートに失敗しました: ${current.message}",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    val confirmState = state as? ImportUiState.ConfirmOverwrite
    if (confirmState != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text("既存データの上書き確認") },
            text = { Text("${confirmState.overwriteDayCount}日分の既存データを置き換えます。よろしいですか？") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmOverwrite() }) {
                    Text("続行")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImport() }) {
                    Text("キャンセル")
                }
            }
        )
    }
}
