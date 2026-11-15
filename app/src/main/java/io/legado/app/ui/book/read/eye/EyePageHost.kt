// 眼动翻页的 Compose 宿主：挂在阅读页根 Box 里（ReadBookScreen 只是弹层，
// 且要等任意弹层打开过才组合，不适合做常驻宿主）。
// 负责：摄像头权限、RESUME/PAUSE 启停、旋转重建、朗读/自动翻页期间停机、
// 校准 overlay、落点圆点、「上看翻页」提示、首次开启的说明弹窗。
// 识别本身在 EyePageCoordinator（移植自 legadoC EyePageTurner 的逻辑）。
package io.legado.app.ui.book.read.eye

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.R
import io.legado.app.ui.book.read.ReadBookIntent
import io.legado.app.ui.book.read.ReadBookUiState
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.utils.toastOnUi

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

@Composable
fun EyePageHost(
    state: ReadBookUiState,
    onIntent: (ReadBookIntent) -> Unit,
    onNextPage: () -> Unit,
    textSelected: Boolean,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    onReadyHintChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val orientation = LocalConfiguration.current.orientation
    val coordinator = remember { EyePageCoordinator(context) }

    // 「上看翻页」提示显隐上报给阅读页：提示出现时宿主把页脚内容藏起来
    // （照 legadoC，提示出现时页脚信息临时隐藏），提示消失时恢复。
    LaunchedEffect(coordinator.readyHintVisible.value) {
        onReadyHintChanged(coordinator.readyHintVisible.value)
    }

    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var cameraPermissionGranted by remember { mutableStateOf(hasCameraPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        if (granted) {
            onIntent(ReadBookIntent.SetEyePageTurnEnabled(true))
        } else {
            onIntent(ReadBookIntent.SetEyePageTurnEnabled(false))
            context.toastOnUi(R.string.eye_page_permission_required)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    cameraPermissionGranted = hasCameraPermission(context)
                }
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 宿主提供的判定：每帧调用，读最新的 state（直接赋值，不经过 rememberUpdatedState，
    // 因为协调器在主线程回调里读取，组合时赋值已是最新值）。
    coordinator.readingBlockReason = {
        when {
            !resumed -> "not_resumed"
            !view.hasWindowFocus() -> "window_unfocused"
            state.menuVisible -> "menu_visible"
            state.sheetStack.isNotEmpty() -> "sheet_visible"
            state.isShowingSearchResult || state.searchMenuVisible -> "search_visible"
            !state.isInitFinish -> "initializing"
            state.isAutoPage -> "auto_paging"
            state.isReadAloudRunning -> "read_aloud"
            textSelected -> "text_selected"
            state.msg != null -> "message_page"
            else -> null
        }
    }
    coordinator.canCalibrate = {
        resumed && view.hasWindowFocus() && !state.menuVisible && state.sheetStack.isEmpty()
    }
    coordinator.displayDegrees = { displayDegrees(context) }
    coordinator.onNextPage = {
        onNextPage()
        coordinator.resetGesture()
    }
    coordinator.onError = { error ->
        // 只显示类名会把真正原因藏起来：ExceptionInInitializerError 的 message 为空，
        // 原因在 cause 链里（例如 UnsatisfiedLinkError: dlopen failed...）。
        onIntent(
            ReadBookIntent.EyePageTurnFailed(describeEyeError(error))
        )
    }
    coordinator.onCancel = { onIntent(ReadBookIntent.SetEyePageTurnEnabled(false)) }
    coordinator.onCalibrationSaved = { profile ->
        onIntent(ReadBookIntent.EyePageCalibrationSaved(profile))
    }

    // 硬停机条件：未恢复、无权限、朗读、自动翻页——任一成立即停机，恢复后自动重开。
    // 菜单/弹层/搜索/选中等软条件不停机，只由 readingBlockReason 按帧暂停识别。
    val shouldRun = state.eyePageTurnEnabled && resumed && cameraPermissionGranted &&
        !state.isAutoPage && !state.isReadAloudRunning
    LaunchedEffect(shouldRun, orientation) {
        if (shouldRun) {
            if (!cameraPermissionGranted) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                // 旋转时先停后开，让 EyeCameraTracker 用新的 displayDegrees 重建。
                coordinator.stop()
                coordinator.start()
            }
        } else {
            coordinator.stop()
        }
    }
    LaunchedEffect(state.eyePageGazePointEnabled) {
        coordinator.setGazePointEnabled(state.eyePageGazePointEnabled)
    }
    LaunchedEffect(state.eyeGazePointStyleNonce) {
        coordinator.refreshGazePointStyle()
    }
    LaunchedEffect(state.eyePageRecalibrateNonce) {
        if (state.eyePageRecalibrateNonce > 0) coordinator.recalibrate()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxHeight = maxHeight
        // 注意：这里不要放全屏 pointerInput 的 Box——它是根 Box 最上层，
        // 命中测试会先命中它，下面的正文、顶栏 ⋮、菜单都收不到触摸。
        // 「按下重置手势」只是来源 dispatchTouchEvent 的辅助行为，不要就不要了，
        // 别为它挡住整个阅读页的触摸。
        // 视线落点圆点：纵向按 56dp → 阅读区底 40dp 上方映射。
        val gazePosition = coordinator.gazePosition.value
        val gazeStyle = coordinator.gazePointStyle.value
        if (gazePosition.isFinite() && !coordinator.isCalibrating.value) {
            val top = 56.dp + (maxHeight - 96.dp) * gazePosition
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = top)
                    .size(gazeStyle.sizeDp.dp)
                    .clip(CircleShape)
                    .background(Color(gazeStyle.color).copy(alpha = gazeStyle.alpha))
                    .semantics { contentDescription = context.getString(R.string.eye_page_gaze_point) },
            )
        }
        // 「上看翻页」就绪提示：照 legadoC 的做法——不垫底色块、不占额外空间，
        // 只在需要时把一串裸字自动盖在页脚上（阅读区底在屏幕底 40dp 上方，提示贴在页脚行，
        // 字号跟页脚的 12sp 一致，平时不显示）。
        if (coordinator.readyHintVisible.value && !coordinator.isCalibrating.value) {
            AppText(
                text = stringResource(R.string.eye_page_look_up_hint),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp),
                color = LegadoTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
        // 校准全屏 overlay：盖住正文与菜单，点按不透传（校准期间禁止手动翻页）。
        if (coordinator.isCalibrating.value) {
            EyeCalibrationOverlay(
                stage = coordinator.calibrationStage.value,
                instruction = coordinator.instructionText.value,
                status = coordinator.statusText.value,
                backgroundColor = backgroundColor,
                onCancel = { coordinator.cancelCalibration() },
            )
        }
    }

    // 首次开启说明：只显示一次，点「开启眼动翻页」后才请求摄像头权限。
    AppAlertDialog(
        show = state.eyePageHelpVisible,
        onDismissRequest = { onIntent(ReadBookIntent.DismissEyePageHelp) },
        title = stringResource(R.string.eye_page_turn),
        text = stringResource(R.string.eye_page_help),
        confirmText = stringResource(R.string.eye_page_enable),
        onConfirm = {
            onIntent(ReadBookIntent.EyePageHelpConfirmed)
            if (cameraPermissionGranted) {
                onIntent(ReadBookIntent.SetEyePageTurnEnabled(true))
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(ReadBookIntent.DismissEyePageHelp) },
    )
}

/** 报错只给类名查不出原因：把 cause 链尾的原因类名和简短信息也带上。 */
private fun describeEyeError(error: Throwable): String {
    val root = generateSequence(error) { it.cause }.last()
    return buildString {
        append(error.javaClass.simpleName)
        if (root !== error) {
            append(" ← ")
            append(root.javaClass.simpleName)
            root.message?.take(120)?.takeIf { it.isNotBlank() }?.let {
                append(": ")
                append(it)
            }
        } else {
            error.localizedMessage?.takeIf { it.isNotBlank() }?.let {
                append(": ")
                append(it)
            }
        }
    }
}

@SuppressLint("NewApi")
@Suppress("DEPRECATION")
private fun displayDegrees(context: Context): Int {
    val rotation = (context as? Activity)?.windowManager?.defaultDisplay?.rotation
        ?: return 0
    return when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}

@Composable
private fun EyeCalibrationOverlay(
    stage: EyeCalibrationStage,
    instruction: String,
    status: String,
    backgroundColor: Color,
    onCancel: () -> Unit,
) {
    BackHandler(enabled = true) { onCancel() }
    val targetDescription = stringResource(R.string.eye_page_target)
    Box(
        Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        // 注视圆点：顶部 56dp / 底部 40dp / 正中，24dp。
        Box(
            Modifier
                .align(
                    when (stage) {
                        EyeCalibrationStage.TOP -> Alignment.TopCenter
                        EyeCalibrationStage.BOTTOM -> Alignment.BottomCenter
                        EyeCalibrationStage.MIDPOINT -> Alignment.Center
                    }
                )
                .padding(
                    top = if (stage == EyeCalibrationStage.TOP) 56.dp else 0.dp,
                    bottom = if (stage == EyeCalibrationStage.BOTTOM) 40.dp else 0.dp,
                )
                .size(24.dp)
                .clip(CircleShape)
                .background(LegadoTheme.colorScheme.primary)
                .semantics {
                    contentDescription = targetDescription
                },
        )
        // 说明与状态：上下阶段居中；中点阶段放下半区，避免遮住正中圆点。
        Column(
            Modifier
                .align(
                    if (stage == EyeCalibrationStage.MIDPOINT) Alignment.BottomCenter
                    else Alignment.Center
                )
                .padding(
                    start = 32.dp,
                    end = 32.dp,
                    bottom = if (stage == EyeCalibrationStage.MIDPOINT) 16.dp else 0.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppText(
                text = instruction,
                color = LegadoTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
            )
            AppText(
                text = status,
                color = LegadoTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            AppText(
                text = stringResource(R.string.cancel),
                color = LegadoTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onCancel,
                    ),
            )
        }
    }
}
