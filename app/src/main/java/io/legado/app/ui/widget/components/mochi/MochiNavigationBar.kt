package io.legado.app.ui.widget.components.mochi

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoTheme
import kotlinx.collections.immutable.ImmutableList
import ui.feice.mochi.basic.MochiTabBar
import ui.feice.mochi.basic.MochiTabBarDefaults
import ui.feice.mochi.basic.MochiTabItem
import ui.feice.mochi.theme.MochiTheme

@Composable
internal fun AppMochiNavigationBar(
    items: ImmutableList<MochiTabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    alwaysShowLabel: Boolean = true,
    floating: Boolean = false,
    glass: Boolean = true,
) {
    MochiTabBar(
        items = items,
        selectedIndex = selectedIndex,
        onSelect = onSelect,
        onReselect = onSelect,
        modifier = modifier.onKeyEvent { event ->
            val target = when (event.key) {
                Key.DirectionLeft -> (selectedIndex - 1).coerceAtLeast(0)
                Key.DirectionRight -> (selectedIndex + 1).coerceAtMost(items.lastIndex)
                Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter -> selectedIndex
                else -> return@onKeyEvent false
            }
            if (event.type == KeyEventType.KeyUp && target in items.indices) onSelect(target)
            true
        }.focusable(),
        behavior = MochiTabBarDefaults.behavior(
            itemWidth = if (floating) 76.dp else 92.dp,
            showLabels = showLabel,
            selectedLabelOnly = showLabel && !alwaysShowLabel,
        ),
        windowInsets = if (floating) WindowInsets(0) else MochiTabBarDefaults.windowInsets,
        // 悬浮纯色底栏（glass=false 时 material=null）走 MochiTabBarDefaults.colors() 的
        // 默认值：浅色 container 是 surfaceContainerLowest，各主题基本纯白，选中指示是
        // secondaryContainer——截图里纯白胶囊 + 浅蓝 pill 就是这么来的，v2 改的玻璃
        // fallback 在这条路上根本走不到。只覆盖 container 为 surfaceContainer，和设置
        // 卡片同一个 token；选中指示、文字、图标保持默认。玻璃模式下底栏表面仍是玻璃的
        // fallback（v2 已改成 surfaceContainer），colors.container 不参与玻璃表面绘制。
        colors = MochiTabBarDefaults.colors(
            container = LegadoTheme.colorScheme.surfaceContainer,
        ),
        // 没 backdrop 时底栏整块画的就是玻璃的 fallback（mochi 库 iOS 玻璃的固定
        // #F2F0EE/#2A2826，不跟主题），改成跟设置卡片同一个 surfaceContainer；
        // 有 backdrop 时玻璃照常采样页面，fallback 只在被盖住时当备用色。
        material = if (glass) {
            MochiTheme.materials.bar?.copy(
                fallback = LegadoTheme.colorScheme.surfaceContainer,
            )
        } else {
            null
        },
    )
}
