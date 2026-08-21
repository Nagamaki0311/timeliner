package com.nagamaki0311.timeliner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nagamaki0311.timeliner.ui.ImportScreen
import com.nagamaki0311.timeliner.ui.TimelineScreen
import com.nagamaki0311.timeliner.ui.TimelineViewModel

class MainActivity : ComponentActivity() {

    private val timelineViewModel: TimelineViewModel by viewModels { TimelineViewModel.factory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var selectedTab by remember { mutableIntStateOf(0) }
                    val tabTitles = listOf("地図", "インポート")

                    Column(modifier = Modifier.fillMaxSize()) {
                        // targetSdk 36（Android 15+相当）はedge-to-edgeが強制され、ステータスバー分の余白を
                        // 自前で確保しないとタブがステータスバーに隠れて操作不能になる（docs/decisions.md D-012）。
                        // 地図（Box weight(1f)）はこのTabRowの下に続くため、ここにだけ余白を足せば地図は必要以上に狭まらない。
                        TabRow(
                            selectedTabIndex = selectedTab,
                            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
                        ) {
                            tabTitles.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index },
                                    text = { Text(title) }
                                )
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            when (selectedTab) {
                                0 -> TimelineScreen(viewModel = timelineViewModel, modifier = Modifier.fillMaxSize())
                                else -> ImportScreen(viewModel = timelineViewModel, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }
}
