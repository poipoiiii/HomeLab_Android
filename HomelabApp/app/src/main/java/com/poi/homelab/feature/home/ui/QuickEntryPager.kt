package com.poi.homelab.feature.home.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.poi.homelab.core.ui.theme.CardSurfaceAlpha
import kotlin.math.absoluteValue

data class StackCard(val title: String, val subtitle: String)

/** 快捷入口卡片：横向滑动切换，当前卡片完整显示，两侧卡片缩小并淡出。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickEntryPager(
    cards: List<StackCard>,
    onCardClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { cards.size })

    Column(modifier) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 28.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth().height(120.dp),
        ) { page ->
            val offset = (
                (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                ).absoluteValue.coerceIn(0f, 1f)
            val scale = 1f - 0.06f * offset

            Surface(
                onClick = { onCardClick(page) },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - 0.22f * offset
                },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = CardSurfaceAlpha),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 3.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(18.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            text = cards[page].title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "0${page + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = cards[page].subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "轻点进入",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            cards.indices.forEach { index ->
                val active = pagerState.currentPage == index
                val width by animateDpAsState(
                    targetValue = if (active) 18.dp else 6.dp,
                    label = "quickEntryDot$index",
                )
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .width(width)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }
    }
}
