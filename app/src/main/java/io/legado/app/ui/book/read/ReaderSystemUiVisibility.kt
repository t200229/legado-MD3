package io.legado.app.ui.book.read

import android.os.Build
import android.view.View

/**
 * Returns legacy decor-view flags.
 *
 * Android 11+ controls visibility through the window insets controller instead, so no hide
 * flags (FULLSCREEN / HIDE_NAVIGATION / IMMERSIVE / IMMERSIVE_STICKY) are written here.
 * Only the layout bits are returned, so that statusBarsIgnoringVisibility still reports the
 * status bar height while the bar is hidden and the bookshelf top offset stays constant.
 */
@Suppress("DEPRECATION")
internal fun readerLegacySystemUiVisibilityFlags(
    sdkInt: Int,
    isInMultiWindow: Boolean,
    toolBarHide: Boolean,
    hideNavigationBar: Boolean,
    hideStatusBar: Boolean,
): Int? {
    if (sdkInt >= Build.VERSION_CODES.R) {
        // 只写 layout 位，不写藏栏位；藏栏继续走 insetsController.hide()
        return if (isInMultiWindow) {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_IMMERSIVE or
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    if (!isInMultiWindow) flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    if (hideNavigationBar) {
        flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (toolBarHide) flags = flags or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }
    if (hideStatusBar && toolBarHide) flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN
    return flags
}
