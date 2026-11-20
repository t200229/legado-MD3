package io.legado.app.utils

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ActivitySystemBarTest {

    @Test
    fun showingSystemBarsClearsLegacyHideFlagsAndPreservesEdgeToEdgeLayout() {
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java)
            .setup()
            .visible()
        val activity = activityController.get()
        try {
            val decorView = activity.window.decorView
            val hideFlags = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            val layoutFlags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            decorView.systemUiVisibility = hideFlags or layoutFlags

            activity.toggleSystemBar(show = true)

            assertEquals(0, decorView.systemUiVisibility and hideFlags)
            assertEquals(layoutFlags, decorView.systemUiVisibility and layoutFlags)
        } finally {
            activityController.pause().stop().destroy()
        }
    }
}
