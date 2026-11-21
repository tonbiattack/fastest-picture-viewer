package com.tonbiattack.fastestpictureviewer.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * アプリ内ヘルプ・操作ガイド (Issue #127)。
 *
 * - 完全オフライン。ネットワークも画像も使わない
 * - 初回起動時の強制チュートリアルは設けない。メニューから開くだけ
 * - 見出しごとに折りたたみ、必要な項目だけ開いて読める
 * - 通常の戻る操作で元の画面に戻る（ナビゲーションの戻るに任せる）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 節ごとの開閉状態。初期値は HelpContent 側で決める
    val expanded = remember {
        mutableStateMapOf<String, Boolean>().apply {
            HelpContent.allSections.forEach { put(it.title, it.initiallyExpanded) }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("ヘルプ・操作ガイド") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る"
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = HelpContent.INTRO,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item { HelpHeading("操作ガイド") }

            items(HelpContent.SECTIONS, key = { "guide-${it.title}" }) { section ->
                HelpSectionCard(
                    section = section,
                    expanded = expanded[section.title] == true,
                    onToggle = { expanded[section.title] = expanded[section.title] != true }
                )
            }

            item { HelpHeading("よくある質問") }

            items(HelpContent.FAQ, key = { "faq-${it.title}" }) { section ->
                HelpSectionCard(
                    section = section,
                    expanded = expanded[section.title] == true,
                    onToggle = { expanded[section.title] = expanded[section.title] != true }
                )
            }
        }
    }
}

@Composable
private fun HelpHeading(text: String) {
    Column {
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
    }
}

/**
 * 見出し 1 つ分のカード。タップで開閉する。
 *
 * 折りたたみ中も見出しは常に見えるようにし、探したい項目をすぐ見つけられるようにする。
 */
@Composable
private fun HelpSectionCard(
    section: HelpContent.Section,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onToggle) {
                    Icon(
                        if (expanded) {
                            Icons.Filled.KeyboardArrowUp
                        } else {
                            Icons.Filled.KeyboardArrowDown
                        },
                        contentDescription = if (expanded) "閉じる" else "開く"
                    )
                }
            }
            if (expanded) {
                Text(
                    text = section.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }
    }
}
