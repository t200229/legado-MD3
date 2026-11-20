package io.legado.app.ui.widget.components.mochi

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LocalAppUiConfiguration
import io.legado.app.ui.theme.LocalMochiScrollBlurProgress
import io.legado.app.ui.widget.components.GlassDefaults
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarScrollBehavior
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.M3GlassScrollBehavior
import io.legado.app.ui.widget.components.topbar.TopBarActionsRow
import ui.feice.mochi.basic.Text
import ui.feice.mochi.effect.content.MochiBlurText
import ui.feice.mochi.effect.content.mochiBlurReveal
import ui.feice.mochi.navigation.MochiTitleBar
import ui.feice.mochi.theme.MochiTheme
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppMochiTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    scrollBehavior: GlassTopAppBarScrollBehavior? = null,
    expandable: Boolean = false,
) {
    val flexible = expandable && LocalAppUiConfiguration.current.theme.useFlexibleTopAppBar
    val backgroundProgress = {
        if (flexible) scrollBehavior?.collapsedFraction ?: 0f else 1f
    }
    ProvideMochiScrollBlurProgress(backgroundProgress)
    val background = if (LocalMochiScrollBlurProgress.current != null) {
        GlassDefaults.secondaryColorOr { MochiTheme.colorScheme.surface }
    } else GlassTopAppBarDefaults.containerColor()
    val state = (scrollBehavior as? M3GlassScrollBehavior)?.m3Behavior?.state
    var navigationWidth by remember { mutableIntStateOf(0) }
    var actionsWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // 顶栏高度只用一个恒定源。之前 B2 的补偿算术（full - live）理论上恒定，
    // 但组合时读数与布局时读数不在同一帧更新：状态栏滑入动画期间 live 从 0
    // 渐变到满格，total 上下抖约 25px，书架跟着晃（视频逐帧量过，底部栏不动）。
    // 改成把状态栏 inset 在外层消费掉：子树（库内 MochiTitleBar 的
    // .statusBarsPadding() 与标题 Column 的 .statusBarsPadding()）读到的实时
    // inset 恒为 0，库顶栏高 = 0 + 56dp 恒定；外层顶距直接取忽略可见性的恒定值
    // fullStatusBarTop，不再算 full - live。状态栏滑入动画再也推不动书架。
    // 多窗口下窗口本来就不贴状态栏，保持原有行为（不消费、不补偿）。
    val inMultiWindow = (LocalContext.current as? Activity)?.isInMultiWindowMode == true
    val fullStatusBarTop =
        WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val topPadding = if (inMultiWindow) {
        0.dp
    } else {
        fullStatusBarTop
    }
    Column(
        modifier
            .windowInsetsPadding(TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Horizontal))
            .then(if (inMultiWindow) Modifier else Modifier.consumeWindowInsets(WindowInsets.statusBars))
            .padding(top = topPadding)
    ) {
        Box {
            // Native TitleBar reserves a fixed 64dp for each side. Existing app bars can
            // contain several actions; reserve their real width for the native blur text.
            MochiTitleBar(
                title = "",
                navigationIcon = { Box(Modifier.onSizeChanged { navigationWidth = it.width }) { navigationIcon() } },
                actions = { TopBarActionsRow(Modifier.onSizeChanged { actionsWidth = it.width }) { actions() } },
                background = background,
                backgroundProgress = backgroundProgress,
            )
            Column(
                Modifier.matchParentSize().statusBarsPadding()
                    .padding(
                        start = with(density) { navigationWidth.toDp() } + 20.dp,
                        end = with(density) { actionsWidth.toDp() } + 20.dp,
                    )
                    .clearAndSetSemantics {
                        heading()
                        text = AnnotatedString(listOfNotNull(title, subtitle?.takeIf { it.isNotBlank() }).joinToString("\n"))
                    },
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Keep the named heading outside the visual alpha/blur layer, including
                // while the expanded title is visible and the compact text is hidden.
                Column(
                    Modifier.mochiBlurReveal({ if (flexible) scrollBehavior?.collapsedFraction ?: 0f else 1f }, rise = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MochiBlurText(title, style = MochiTheme.textStyles.navTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!flexible && !subtitle.isNullOrBlank()) {
                        MochiBlurText(subtitle, style = MochiTheme.textStyles.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (flexible) {
            // Keep the existing nested-scroll contract. Measure the expanded title at its real
            // font scale, then reveal only the remaining height as the shared behavior collapses.
            Layout(modifier = Modifier.clipToBounds().clearAndSetSemantics {}, content = {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    MochiBlurText(title, style = MochiTheme.textStyles.largeTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrBlank()) Text(subtitle, style = MochiTheme.textStyles.footnote)
                }
            }) { measurables, constraints ->
                val child = measurables.single().measure(constraints.copy(minHeight = 0))
                state?.heightOffsetLimit = -child.height.toFloat()
                val visible = (child.height + (state?.heightOffset ?: 0f)).roundToInt().coerceAtLeast(0)
                layout(child.width, visible) { child.place(0, visible - child.height) }
            }
        }
    }
}

@Composable
internal fun ProvideMochiScrollBlurProgress(progress: () -> Float) {
    val scrollBlur = LocalMochiScrollBlurProgress.current ?: return
    val currentProgress by rememberUpdatedState(progress)
    DisposableEffect(scrollBlur) {
        val readProgress = { currentProgress() }
        scrollBlur.value = readProgress
        onDispose { if (scrollBlur.value === readProgress) scrollBlur.value = { 1f } }
    }
}
