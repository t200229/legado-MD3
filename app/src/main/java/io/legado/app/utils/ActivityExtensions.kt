package io.legado.app.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimationControlListener
import android.view.WindowInsetsAnimationController
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.WindowMetrics
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
import androidx.fragment.app.DialogFragment
import io.legado.app.R

inline fun <reified T : DialogFragment> AppCompatActivity.showDialogFragment(
    arguments: Bundle.() -> Unit = {}
) {
    @Suppress("DEPRECATION")
    val dialog = T::class.java.newInstance()
    val bundle = Bundle()
    bundle.apply(arguments)
    dialog.arguments = bundle
    dialog.show(supportFragmentManager, T::class.simpleName)
}

inline fun <reified T : DialogFragment> AppCompatActivity.dismissDialogFragment() {
    supportFragmentManager.fragments.forEach {
        if (it is T) {
            it.dismissAllowingStateLoss()
        }
    }
}

fun AppCompatActivity.showDialogFragment(dialogFragment: DialogFragment) {
    dialogFragment.show(supportFragmentManager, dialogFragment::class.simpleName)
}

val WindowManager.windowSize: DisplayMetrics
    get() {
        val displayMetrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics: WindowMetrics = currentWindowMetrics
            val insets = windowMetrics.windowInsets
                .getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
            val windowWidth = windowMetrics.bounds.width()
            val windowHeight = windowMetrics.bounds.height()
            var insetsWidth = insets.left + insets.right
            var insetsHeight = insets.top + insets.bottom
            if (windowWidth > windowHeight) {
                val tmp = insetsWidth
                insetsWidth = insetsHeight
                insetsHeight = tmp
            }
            displayMetrics.widthPixels = windowWidth - insetsWidth
            displayMetrics.heightPixels = windowHeight - insetsHeight
        } else {
            @Suppress("DEPRECATION")
            defaultDisplay.getMetrics(displayMetrics)
        }
        return displayMetrics
    }

@Suppress("DEPRECATION")
fun Activity.fullScreen() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.setDecorFitsSystemWindows(false)
    }
    window.decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    window.clearFlags(
        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
    )
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
}

/**
 * 设置状态栏颜色
 */
@Suppress("DEPRECATION")
fun Activity.setStatusBarColorAuto(
    @ColorInt color: Int,
    isTransparent: Boolean,
    fullScreen: Boolean = true
) {
    val isLightBar = ColorUtils.isColorLight(color)
    if (fullScreen) {
        if (isTransparent) {
            window.statusBarColor = Color.TRANSPARENT
        } else {
            window.statusBarColor = getCompatColor(R.color.status_bar_bag)
        }
    } else {
        window.statusBarColor = color
    }
    setLightStatusBar(isLightBar)
}

@SuppressLint("ObsoleteSdkInt")
fun Activity.setLightStatusBar(isLightBar: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.let {
            if (isLightBar) {
                it.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                it.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            }
        }
    }
    @Suppress("DEPRECATION")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val decorView = window.decorView
        val systemUiVisibility = decorView.systemUiVisibility
        if (isLightBar) {
            decorView.systemUiVisibility =
                systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            decorView.systemUiVisibility =
                systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }
}

fun Activity.keepScreenOn(on: Boolean) {
    val isScreenOn =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
    if (on == isScreenOn) return
    if (on) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

fun Activity.toggleSystemBar(show: Boolean) {
    WindowCompat.getInsetsController(window, window.decorView).run {
        if (show) {
            show(WindowInsetsCompat.Type.systemBars())
        } else {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/**
 * 系统栏直接出现/消失，不走系统约 300ms 的滑入滑出动画。
 * API 30+：用 controlWindowInsetsAnimation 接管后立刻把栏位移到目标状态再 finish；
 * 接管失败、被取消或低版本时，退回普通的 show()/hide()。
 * 另有 200ms 后的一次兜底 show()/hide()：栏已在位时是空操作，接管失败时保证栏一定出现。
 */
fun Activity.setSystemBarsInstant(show: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        toggleSystemBar(show)
        return
    }
    val controller = window.insetsController
    if (controller == null) {
        toggleSystemBar(show)
        return
    }
    try {
        controller.controlWindowInsetsAnimation(
            WindowInsets.Type.systemBars(),
            0,
            null,
            null,
            object : WindowInsetsAnimationControlListener {
                override fun onReady(
                    controller: WindowInsetsAnimationController,
                    types: Int,
                ) {
                    // 只调 finish 不会真的把栏位移过去，必须先把 insets 设到目标状态。
                    if (show) {
                        controller.setInsetsAndAlpha(controller.shownStateInsets, 1f, 1f)
                    } else {
                        controller.setInsetsAndAlpha(controller.hiddenStateInsets, 1f, 1f)
                    }
                    controller.finish(show)
                }

                override fun onFinished(controller: WindowInsetsAnimationController) = Unit

                override fun onCancelled(controller: WindowInsetsAnimationController?) {
                    this@setSystemBarsInstant.toggleSystemBar(show)
                }
            },
        )
    } catch (_: Exception) {
        toggleSystemBar(show)
    }
    // 兜底：接管没生效时栏也一定出现（已在位时 show()/hide() 是空操作）。
    window.decorView.postDelayed({ toggleSystemBar(show) }, 200)
}

/////以下方法需要在View完全被绘制出来之后调用，否则判断不了,在比如 onWindowFocusChanged（）方法中可以得到正确的结果/////

/**
 * 返回NavigationBar
 */
val Activity.navigationBar: View?
    get() {
        val viewGroup = (window.decorView as? ViewGroup) ?: return null
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            val childId = child.id
            if (childId != View.NO_ID
                && resources.getResourceEntryName(childId) == "navigationBarBackground"
            ) {
                return child
            }
        }
        return null
    }

/**
 * 返回NavigationBar是否存在
 */
val Activity.isNavigationBarExist: Boolean
    get() = navigationBar != null

/**
 * 返回NavigationBar高度
 */
val Activity.navigationBarHeight: Int
    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    get() {
        if (isNavigationBarExist) {
            val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            return resources.getDimensionPixelSize(resourceId)
        }
        return 0
    }

/**
 * 返回navigationBar位置
 */
val Activity.navigationBarGravity: Int
    get() {
        val gravity = (navigationBar?.layoutParams as? FrameLayout.LayoutParams)?.gravity
        return gravity ?: Gravity.BOTTOM
    }
