package com.nagamaki0311.timeliner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nagamaki0311.timeliner.model.Period
import com.nagamaki0311.timeliner.model.PeriodType

/** [PeriodSelector]で選択できる期間種別（任意範囲[PeriodType.CUSTOM]はUIからは選択不可、docs/tasks.md T-006）。 */
private val SELECTABLE_TYPES = listOf(PeriodType.DAY, PeriodType.WEEK, PeriodType.MONTH, PeriodType.YEAR)

private val TYPE_LABELS = mapOf(
    PeriodType.DAY to "日",
    PeriodType.WEEK to "週",
    PeriodType.MONTH to "月",
    PeriodType.YEAR to "年"
)

/**
 * 期間種別（日/週/月/年）の選択タブと、前後移動ボタン・現在の期間表示を提供するUI（docs/tasks.md T-006）。
 * 種別を切り替えた場合、現在の[period]の[Period.startDate]を基準日として新しい期間を計算する。
 */
@Composable
fun PeriodSelector(period: Period, onPeriodChange: (Period) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        TabRow(selectedTabIndex = SELECTABLE_TYPES.indexOf(period.type).coerceAtLeast(0)) {
            SELECTABLE_TYPES.forEach { type ->
                Tab(
                    selected = period.type == type,
                    onClick = { onPeriodChange(Period.of(type, period.startDate)) },
                    text = { Text(TYPE_LABELS.getValue(type)) }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { onPeriodChange(period.previous()) }) {
                Text("前の期間")
            }
            Text(period.label(), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onPeriodChange(period.next()) }) {
                Text("次の期間")
            }
        }
    }
}
