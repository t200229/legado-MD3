// 眼动落点样式：预设颜色圆点 + 透明度/大小滑杆。
// 不引入 legadoC 的 ColorPickerDialogCompat（目标没有它）；颜色只给预设档。
package io.legado.app.ui.book.read.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.book.read.ReadBookIntent
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.settingItem.TinySliderSettingItem
import io.legado.app.ui.widget.components.text.AppText

private val EyeGazePointPresetColors = listOf(
    0xFF9E9E9E.toInt(),
    0xFFF44336.toInt(),
    0xFFFF9800.toInt(),
    0xFFFFEB3B.toInt(),
    0xFF4CAF50.toInt(),
    0xFF2196F3.toInt(),
    0xFF9C27B0.toInt(),
    0xFFFFFFFF.toInt(),
)

@Composable
fun EyeGazePointStyleSheet(
    show: Boolean,
    color: Int,
    alpha: Int,
    size: Int,
    onDismissRequest: () -> Unit,
    onIntent: (ReadBookIntent) -> Unit,
) {
    var colorState by remember(show) { mutableIntStateOf(color) }
    var alphaState by remember(show) { mutableFloatStateOf(alpha.toFloat()) }
    var sizeState by remember(show) { mutableFloatStateOf(size.toFloat()) }
    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.eye_page_gaze_point_settings),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            AppText(
                text = stringResource(R.string.eye_page_gaze_point_color),
                color = LegadoTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EyeGazePointPresetColors.forEach { preset ->
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(preset))
                            .border(
                                width = if (preset == colorState) 2.dp else 1.dp,
                                color = if (preset == colorState) LegadoTheme.colorScheme.primary
                                else LegadoTheme.colorScheme.outline,
                                shape = CircleShape,
                            )
                            .clickable {
                                colorState = preset
                                onIntent(ReadBookIntent.EyeGazePointStyleChanged(color = preset))
                            },
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            TinySliderSettingItem(
                title = stringResource(R.string.eye_page_gaze_point_alpha, alphaState.toInt()),
                value = alphaState,
                valueRange = 0f..100f,
                onValueChange = { alphaState = it },
                onValueChangeFinished = {
                    onIntent(ReadBookIntent.EyeGazePointStyleChanged(alpha = alphaState.toInt()))
                },
            )
            TinySliderSettingItem(
                title = stringResource(R.string.eye_page_gaze_point_size, sizeState.toInt()),
                value = sizeState,
                valueRange = 8f..64f,
                onValueChange = { sizeState = it },
                onValueChangeFinished = {
                    onIntent(ReadBookIntent.EyeGazePointStyleChanged(size = sizeState.toInt()))
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
