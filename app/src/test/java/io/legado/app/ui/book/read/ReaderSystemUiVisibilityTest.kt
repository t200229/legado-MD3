package io.legado.app.ui.book.read

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSystemUiVisibilityTest {

    @Test
    fun android11AndLaterKeepOnlyLayoutFlags() {
        val flags = requireNotNull(
            readerLegacySystemUiVisibilityFlags(
                sdkInt = 35,
                isInMultiWindow = false,
                toolBarHide = true,
                hideNavigationBar = true,
                hideStatusBar = true,
            )
        )

        assertEquals(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN,
            flags,
        )
        assertFalse(flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        assertFalse(flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
        assertFalse(flags and View.SYSTEM_UI_FLAG_IMMERSIVE != 0)
        assertFalse(flags and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY != 0)
    }

    @Test
    fun android11AndLaterMultiWindowKeepsOnlyLayoutStable() {
        val flags = requireNotNull(
            readerLegacySystemUiVisibilityFlags(
                sdkInt = 35,
                isInMultiWindow = true,
                toolBarHide = true,
                hideNavigationBar = true,
                hideStatusBar = true,
            )
        )

        assertEquals(View.SYSTEM_UI_FLAG_LAYOUT_STABLE, flags)
    }

    @Test
    fun preAndroid11KeepsImmersiveAndConfiguredHideFlags() {
        val flags = requireNotNull(
            readerLegacySystemUiVisibilityFlags(
                sdkInt = 29,
                isInMultiWindow = false,
                toolBarHide = true,
                hideNavigationBar = true,
                hideStatusBar = true,
            )
        )

        assertTrue(flags and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY != 0)
        assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN != 0)
        assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION != 0)
        assertTrue(flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
        assertTrue(flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
    }

    @Test
    fun preAndroid11LeavesStatusAndNavigationBarsVisibleWhenConfigured() {
        val flags = requireNotNull(
            readerLegacySystemUiVisibilityFlags(
                sdkInt = 29,
                isInMultiWindow = true,
                toolBarHide = true,
                hideNavigationBar = false,
                hideStatusBar = false,
            )
        )

        assertFalse(flags and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN != 0)
        assertFalse(flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
        assertFalse(flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        assertEquals(View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_IMMERSIVE or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY, flags)
    }
}
