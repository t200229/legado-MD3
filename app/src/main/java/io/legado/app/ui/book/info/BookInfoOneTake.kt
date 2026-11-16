package io.legado.app.ui.book.info

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import io.legado.app.utils.themeColor
import java.lang.reflect.Method
import kotlin.math.roundToInt

/**
 * 书封面一镜到底的锚点登记。
 *
 * 书架 / 发现页的卡片在布局时把自己的屏幕矩形记下来（key 是 bookUrl），点开详情时
 * 按 bookUrl 取。只存布局快照，不存业务状态；容量有上限，旧的按 LRU 淘汰。
 * 用读不取（get，不删除）的方式取：卡片只要还在屏幕上，矩形就会随布局刷新，
 * 同一张卡片反复点开都能拿到新鲜矩形。
 */
object BookInfoOneTakeAnchors {
    private const val MAX_ENTRIES = 512

    private val rects = object : LinkedHashMap<String, Rect>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Rect>?
        ): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun record(bookUrl: String, rect: Rect) {
        if (bookUrl.isBlank() || rect.isEmpty) return
        rects[bookUrl] = Rect(rect)
    }

    /** 卡片的屏幕矩形换算（仿 bdFun OneTakeAnchor：boundsInRoot + View 在屏幕上的位置）。 */
    @Synchronized
    fun record(view: View, coords: LayoutCoordinates, bookUrl: String) {
        val bounds = coords.boundsInRoot()
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        record(
            bookUrl,
            Rect(
                (bounds.left + loc[0]).roundToInt(),
                (bounds.top + loc[1]).roundToInt(),
                (bounds.right + loc[0]).roundToInt(),
                (bounds.bottom + loc[1]).roundToInt(),
            ),
        )
    }

    @Synchronized
    fun get(bookUrl: String): Rect? = rects[bookUrl]?.let { Rect(it) }
}

/**
 * 让“书封面 → 详情页”走系统自带的窗口放大动画（就是桌面点图标打开 App 的那套）。
 *
 * 做法仿 bdFun 的 TeakOneTake：只提供起点矩形、卡片截图、圆角；放大过程由系统合成器完成，
 * 所以文字不会糊、不会拉伸，背景压暗也是系统做的。返回时系统自动播反向缩小动画。
 *
 * 关键是不走 ITGSA 金标 Kit（小米 14 上 isViewAnimationSupported 返回 0），而是反射调
 * AOSP 隐藏接口 `ActivityOptions.makeScaleUpDown`（启动器开 App 用的就是它），
 * HyperOS 里有这个接口，所以能调通。拿不到隐藏接口时退回公开的
 * `makeThumbnailScaleUpAnimation`，再不行退回普通启动（由 fallback 接管，一般是原 Nav3 路由）。
 */
object BookInfoOneTake {

    private const val TAG = "BookInfoOneTake"

    /** 带缩略图的启动动画（与启动器开 App 同款）。 */
    private const val ANIM_SCALED_THUMB = 103

    @Volatile
    private var method: Method? = null

    @Volatile
    private var resolved = false

    /**
     * 用系统动画打开书详情页（独立的 [BookInfoActivity]）。
     * @param anchor 卡片在**屏幕坐标系**里的矩形（px），一般由 [BookInfoOneTakeAnchors] 登记
     * @param radiusPx 卡片圆角（px），要和卡片看起来一致，否则起飞瞬间会跳一下
     * @param backgroundColor 系统在缩放过程中铺在缩略图底下的颜色，0 表示用当前主题 surface 色
     * @param fallback 拿不到动画条件时退回普通打开（调用方一般接原 Nav3 路由），不要崩
     */
    @JvmStatic
    fun openBookInfo(
        context: Context,
        name: String?,
        author: String?,
        bookUrl: String,
        origin: String?,
        coverPath: String?,
        anchor: Rect?,
        radiusPx: Float = 12f * context.resources.displayMetrics.density,
        backgroundColor: Int = 0,
        fallback: () -> Unit,
    ) {
        val activity = findActivity(context)
        if (activity == null) {
            Log.w(TAG, "拿不到 Activity，退回普通打开")
            fallback()
            return
        }
        val decor = activity.window?.decorView
        if (decor == null || decor.width <= 0 || decor.height <= 0) {
            Log.w(TAG, "拿不到窗口，退回普通打开")
            fallback()
            return
        }
        if (anchor == null || anchor.width() <= 0 || anchor.height() <= 0) {
            Log.w(TAG, "卡片位置还没算出来，退回普通打开")
            fallback()
            return
        }
        val bgColor = if (backgroundColor != 0) backgroundColor else {
            runCatching {
                context.themeColor(com.google.android.material.R.attr.colorSurface)
            }.getOrNull() ?: 0
        }
        val m = resolveMethod()

        // 截图必须走 PixelCopy：Compose 的界面用 view.draw() 画出来是空的
        captureByPixelCopy(activity, decor, anchor) { thumb ->
            if (thumb == null) {
                Log.w(TAG, "截图失败，退回普通打开")
                fallback()
                return@captureByPixelCopy
            }
            if (m == null) {
                startPublic(activity, decor, anchor, thumb, name, author, bookUrl, origin, coverPath, fallback)
            } else {
                start(activity, m, decor, anchor, radiusPx, bgColor, thumb, name, author, bookUrl, origin, coverPath) {
                    startPublic(activity, decor, anchor, thumb, name, author, bookUrl, origin, coverPath, fallback)
                }
            }
        }
    }

    /** 非厂商系统也保留连续缩略图过渡，坐标转换到启动 View。 */
    private fun startPublic(
        activity: Activity,
        decor: View,
        anchor: Rect,
        thumb: Bitmap,
        name: String?,
        author: String?,
        bookUrl: String,
        origin: String?,
        coverPath: String?,
        fallback: () -> Unit,
    ) {
        try {
            val location = IntArray(2)
            decor.getLocationOnScreen(location)
            val options = ActivityOptions.makeThumbnailScaleUpAnimation(
                decor, thumb, anchor.left - location[0], anchor.top - location[1],
            )
            Log.i(TAG, "path=public-thumbnail")
            launch(activity, options, name, author, bookUrl, origin, coverPath)
        } catch (error: Exception) {
            Log.w(TAG, "公开缩略图过渡启动失败，退回普通打开", error)
            fallback()
        }
    }

    private fun start(
        activity: Activity,
        m: Method,
        decor: View,
        anchor: Rect,
        radiusPx: Float,
        backgroundColor: Int,
        thumb: Bitmap,
        name: String?,
        author: String?,
        bookUrl: String,
        origin: String?,
        coverPath: String?,
        fallback: () -> Unit,
    ) {
        try {
            val handler = Handler(Looper.getMainLooper())
            val opts = m.invoke(
                null,
                decor,                          // 只用来取包名
                thumb,                          // 起飞用的截图
                anchor.left, anchor.top,        // 屏幕坐标
                radiusPx.toInt(),               // 圆角
                backgroundColor,                // 对应官方配置的 bgColor
                1.0f,                           // 按下缩放：1.0 = 不做缩放
                handler,
                null, null, null, null,         // 进出动画回调
                ANIM_SCALED_THUMB,
            ) as? ActivityOptions
            if (opts == null) {
                Log.w(TAG, "系统没给出动画选项，退回普通打开")
                fallback()
                return
            }
            Log.i(
                TAG,
                "path=private requestedType=$ANIM_SCALED_THUMB " +
                    "returnedType=${opts.toBundle().getInt("android:activity.animType", -1)}"
            )
            launch(activity, opts, name, author, bookUrl, origin, coverPath)
        } catch (t: Throwable) {
            Log.w(TAG, "隐藏接口启动失败，退回普通打开", t)
            fallback()
        }
    }

    private fun launch(
        activity: Activity,
        options: ActivityOptions,
        name: String?,
        author: String?,
        bookUrl: String,
        origin: String?,
        coverPath: String?,
    ) {
        val intent = BookInfoActivity.createIntent(activity, name, author, bookUrl, origin, coverPath)
        activity.startActivity(intent, options.toBundle())
    }

    /** 用 PixelCopy 抓窗口里卡片那一块的像素（API 26+）。 */
    private fun captureByPixelCopy(
        activity: Activity,
        decor: View,
        anchor: Rect,
        onDone: (Bitmap?) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onDone(captureByDraw(decor, anchor))
            return
        }
        val loc = IntArray(2)
        decor.getLocationOnScreen(loc)
        val src = Rect(
            anchor.left - loc[0],
            anchor.top - loc[1],
            anchor.right - loc[0],
            anchor.bottom - loc[1],
        )
        // 夹到窗口范围内
        val win = Rect(0, 0, decor.width, decor.height)
        if (!src.intersect(win) || src.width() <= 0 || src.height() <= 0) {
            onDone(null)
            return
        }
        val bitmap = try {
            Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            onDone(null)
            return
        }
        try {
            PixelCopy.request(
                activity.window,
                src,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        onDone(bitmap)
                    } else {
                        Log.w(TAG, "PixelCopy 失败 result=$result")
                        onDone(null)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "PixelCopy 抛错", t)
            onDone(null)
        }
    }

    /** 兜底方案：直接画（普通 View 能用，Compose 可能画不出来）。 */
    private fun captureByDraw(root: View, anchor: Rect): Bitmap? {
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) return null
        return try {
            val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(full))
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            val l = (anchor.left - loc[0]).coerceIn(0, w - 1)
            val t = (anchor.top - loc[1]).coerceIn(0, h - 1)
            val r = (anchor.right - loc[0]).coerceIn(l + 1, w)
            val b = (anchor.bottom - loc[1]).coerceIn(t + 1, h)
            Bitmap.createBitmap(full, l, t, r - l, b - t)
        } catch (t: Throwable) {
            Log.w(TAG, "截图失败", t)
            null
        }
    }

    /** Compose 里拿到的 Context 常常被包装过，要一层层剥到底。 */
    private fun findActivity(context: Context): Activity? {
        var c: Context? = context
        while (c != null) {
            if (c is Activity) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }

    /** 反射拿隐藏接口：先直取，失败再用元反射绕一层。 */
    private fun resolveMethod(): Method? {
        method?.let { return it }
        if (resolved) return method
        synchronized(this) {
            if (resolved) return method
            val params = arrayOf<Class<*>>(
                View::class.java, Bitmap::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Float::class.javaPrimitiveType!!, Handler::class.java,
                Runnable::class.java, Runnable::class.java,
                Runnable::class.java, Runnable::class.java,
                Int::class.javaPrimitiveType!!,
            )
            var found: Method? = try {
                ActivityOptions::class.java
                    .getDeclaredMethod("makeScaleUpDown", *params)
                    .apply { isAccessible = true }
            } catch (t: Throwable) {
                null
            }
            if (found == null) {
                found = try {
                    val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
                    val classArray: Class<*> = emptyArray<Class<*>>().javaClass
                    val getDeclared = Class::class.java.getDeclaredMethod(
                        "getDeclaredMethod", String::class.java, classArray,
                    )
                    @Suppress("UNCHECKED_CAST")
                    val cls = forName.invoke(null, "android.app.ActivityOptions") as Class<*>
                    (getDeclared.invoke(cls, "makeScaleUpDown", *params) as Method)
                        .apply { isAccessible = true }
                } catch (t: Throwable) {
                    Log.w(TAG, "找不到 makeScaleUpDown", t)
                    null
                }
            }
            method = found
            resolved = true
            return found
        }
    }

    /** 仅供调试：当前进程是否拿到了隐藏接口。 */
    @JvmStatic
    fun isHiddenApiAvailable(): Boolean = resolveMethod() != null
}
