package com.poi.homelab.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.poi.homelab.R
import kotlinx.coroutines.delay

/**
 * 启动时的头像动效：头像轻微放大淡入，停留片刻后整层淡出，露出主界面。
 *
 * 只淡出启动遮罩，不再给整个首页套一层 alpha：
 * 否则淡入的每一帧都要把「全屏壁纸 + 看板」重新合成进离屏层，启动那几秒会明显掉帧。
 */
@Composable
fun LaunchSplashHost(content: @Composable () -> Unit) {
    var visible by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (visible) {
            delay(3000)
            visible = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        content()
        AnimatedVisibility(
            visible = visible,
            enter = EnterTransition.None,
            exit = fadeOut(tween(420)),
        ) {
            SplashOverlay(onSkip = { visible = false })
        }
    }
}

@Composable
private fun SplashOverlay(onSkip: () -> Unit) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val interaction = remember { MutableInteractionSource() }

    val scale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.86f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "splashScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "splashAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onSkip,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.scale(scale).alpha(alpha).padding(horizontal = 32.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.app_avatar),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(108.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(28.dp),
                    ),
            )
            Text(
                text = "poi's home",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 20.dp),
            )
            Spacer(Modifier.height(22.dp))
            Box(
                Modifier
                    .width(28.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
            )
            Spacer(Modifier.height(14.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(
                    text = "喜欢的话，做什么都可以的吧。",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "就算是欺骗、犯罪、掠夺、杀害，\n也没有关系。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = "—— 松阪砂糖",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        Text(
            text = "轻触跳过",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
        )
    }
}
