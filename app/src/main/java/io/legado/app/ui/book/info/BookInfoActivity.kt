package io.legado.app.ui.book.info

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toDrawable
import io.legado.app.base.BaseComposeActivity
import io.legado.app.domain.gateway.AppUiConfigurationGateway
import io.legado.app.domain.model.settings.customColors
import io.legado.app.ui.login.SourceLoginType
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.MainIntent
import io.legado.app.ui.theme.AppThemeMode
import io.legado.app.ui.theme.ComposeEngine
import io.legado.app.ui.theme.ThemeEngine
import io.legado.app.ui.theme.ThemeResolver
import io.legado.app.ui.theme.UserColorPalette
import io.legado.app.ui.theme.generateColorScheme
import kotlinx.coroutines.delay
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * 书详情页的独立 Activity 宿主（一镜到底启动动画的落点）。
 *
 * 薄宿主，只承载已有的 [BookInfoRouteScreen]，不复制详情页之外的业务逻辑。
 * 打开方式：一镜到底启动器（[BookInfoOneTake]）从书架 / 发现页卡片起飞；
 * 也可以直接 startActivity（无动画）。
 *
 * 闪白 / 闪一下的根因（AOSP TransitionAnimation.java 现行源码，已实测）：
 * 进场 morph 由两层合成——进入窗口从卡片矩形放大到全屏（只有 ScaleAnimation，
 * 全程不透明，activity-open 分支时长取 config_shortAnimTime = 200ms）；
 * 起飞缩略图同步放大、同时从不透明渐隐到透明（AlphaAnimation(1→0)，
 * 336ms、mThumbnailFadeOutInterpolator 在半程即 168ms 处已褪尽）。
 * 所以缩略图褪尽、窗口放大结束在 200ms 前后就都完成了；之前 250ms 的门
 * 落在 200→250ms 的空档里：门内是透明窗口 → 透出书架，闪一下；门内是
 * 近白主题底色 → 白板，闪白。前四轮修法都没动“门开之前窗口里没有真内容”
 * 这一层，所以都没修掉。
 * 修法（第 5 轮，真无缝）：窗口从第一帧就是不透明的真详情页——
 * 1. onCreate 同步涂窗口背景（SyncWindowBackground 此后接管同步）；
 * 2. presetFromIntent 用 Intent 里的真实书名/作者/来源/封面预置首帧，
 *    与 initData 回包是同一批字符串，首帧即终态，不换值、不触发
 *    AnimatedTextLine 换值动画和背景 800ms Crossfade；
 * 3. 首帧直接组合 BookInfoRouteScreen，不再用 250ms 门挡整页；
 * 4. 只有简介（多行正文，窗口形变时量字会有一帧重叠）留到 morph 跑完
 *    （250ms）再画——只 gate 文字，不 gate 整页。
 * 字体不是乱码的根源：自定义字体走进程级缓存，主界面已预热过，新
 * Activity 首帧就能拿到；没设自定义字体时更不会换字体。
 *
 * 返回时系统自动播反向缩小动画（启动时用的就是系统窗口放大动画），不需要另外写返回动画。
 * 详情页里跳到阅读 / 目录 / 搜索等目标时，走 MainActivity 的 Intent
 *（`FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP` 复用已有 MainActivity，
 * 本 Activity 由 CLEAR_TOP 顺带结束），不在这里复制 Nav3 栈。
 */
class BookInfoActivity : BaseComposeActivity() {

    private val viewModel: BookInfoViewModel by viewModel()
    private val appUiConfigurationGateway: AppUiConfigurationGateway by inject()

    /** 编辑书籍信息的结果：保存后刷新详情并留在详情页，取消也留在详情页。 */
    private val editBookLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onInfoEdited()
        }
    }

    private var bookUrl: String = ""
    private var bookName: String? = null
    private var bookAuthor: String? = null
    private var bookOrigin: String? = null
    private var bookCoverPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookUrl = intent.getStringExtra(MainIntent.EXTRA_BOOK_URL).orEmpty()
        bookName = intent.getStringExtra(MainIntent.EXTRA_BOOK_NAME)
        bookAuthor = intent.getStringExtra(MainIntent.EXTRA_BOOK_AUTHOR)
        bookOrigin = intent.getStringExtra(MainIntent.EXTRA_BOOK_ORIGIN)
        bookCoverPath = intent.getStringExtra(MainIntent.EXTRA_BOOK_COVER)
        if (bookUrl.isBlank()) {
            // 没给 bookUrl 就没法打开详情，直接关掉，不要白屏
            finish()
            return
        }
        // 真无缝（见类头注释）：窗口从第一帧就是不透明的真详情页，顺序不能换：
        // 1. 窗口背景同步涂成不透明主题底色 —— 盖住首帧组合前的窗口（默认
        //    Material 白底），SyncWindowBackground 此后接管同步；
        // 2. presetFromIntent() —— 首帧状态先用 Intent 真实值预置好
        //    （书名/作者/来源/封面），不再是空数据白底，也不再是脱敏空值；
        // 3. initData() —— 异步拉真实数据，回来覆盖预置态（通常在缩略图
        //    褪尽前就换好了，首帧与回包是同一批字符串，不换值、不闪烁）。
        // 注意：不要再把窗口设透明——透明窗口在 morph 跑完、内容未出之间
        // 会透出书架，正是第 4 轮的“闪一下”。
        if (!hasWindowBgImage) {
            paintWindowBackground()
        }
        viewModel.presetFromIntent(bookUrl, bookName, bookAuthor, bookOrigin, bookCoverPath)
        viewModel.initData(bookUrl, bookName, bookAuthor, bookOrigin, bookCoverPath)
    }

    /**
     * 把窗口背景涂成不透明的主题底色。在 onCreate 里同步调用一次（首帧组合
     * 前的窗口不再是默认 Material 白底），之后由 SyncWindowBackground
     *（transparent 已改回默认 false）在首帧组合时接管同步。
     * 取色与 AppThemeActual 用同一套解析逻辑（AppTheme.kt 里
     * remember(colorScheme) 的分支），颜色与详情页 AppScaffold 一致；
     * 有背景图时不动（upBackgroundImage 已处理）；Miuix 引擎取 surface，
     * 其余取 background，与 SyncWindowBackground 的取色口径一致。
     * 算不出来就别挡路：内容本身不透明，窗口背景不可见。
     */
    private fun paintWindowBackground() {
        if (hasWindowBgImage) return
        try {
            val configuration = appUiConfigurationGateway.currentConfiguration
            val themeSettings = configuration.theme
            val darkTheme = configuration.isDarkTheme
            val appThemeMode = ThemeResolver.resolveThemeMode(themeSettings.appTheme)
            val composeEngine =
                ThemeResolver.resolveComposeEngine(configuration.appShell.composeEngine)
            val useBuiltInPalette = composeEngine == ComposeEngine.Coui &&
                !themeSettings.useMiuixMonet && appThemeMode == AppThemeMode.Dynamic
            val customColors = themeSettings.customColors(darkTheme)
            val colorScheme = if (appThemeMode == AppThemeMode.Custom &&
                themeSettings.enableDeepPersonalization && customColors.hasCustomColor
            ) {
                generateColorScheme(
                    UserColorPalette(
                        primaryColor = if (customColors.primary != 0) Color(customColors.primary)
                        else Color(0xFF6750A4),
                        secondaryColor = if (customColors.secondary != 0) Color(customColors.secondary)
                        else Color(0xFF625B71),
                        backgroundColor = if (customColors.background != 0) Color(customColors.background)
                        else Color(0xFFFEF7FF),
                        primaryFontColor = if (customColors.primaryText != 0) Color(customColors.primaryText)
                        else Color(0xFF1C1B1F),
                        secondaryFontColor = if (customColors.secondaryText != 0) Color(customColors.secondaryText)
                        else Color(0xFF49454F),
                        labelContainerColor = if (customColors.labelContainer != 0) Color(customColors.labelContainer)
                        else Color(0xFFF7F2FA),
                    ),
                    darkTheme,
                )
            } else {
                val customSeedColor =
                    if (darkTheme) themeSettings.customNightPrimary else themeSettings.customPrimary
                ThemeEngine.getColorScheme(
                    context = this,
                    mode = appThemeMode,
                    darkTheme = darkTheme,
                    isAmoled = themeSettings.isPureBlack,
                    paletteStyle = themeSettings.paletteStyle,
                    materialVersion = themeSettings.materialVersion,
                    customSeedColor = customSeedColor,
                    customContrast = themeSettings.customContrast,
                    composeEngine = composeEngine,
                    useBuiltInPalette = useBuiltInPalette,
                )
            }
            val background =
                if (composeEngine == ComposeEngine.Miuix) colorScheme.surface
                else colorScheme.background
            window.setBackgroundDrawable(background.toArgb().toDrawable())
        } catch (_: Exception) {
            // 算不出来就别挡路：窗口背景仍是默认色，但内容本身不透明，会盖满窗口
        }
    }

    @Composable
    override fun Content() {
        if (bookUrl.isBlank()) return
        // 真无缝：首帧直接画真详情页（封面/书名/作者已由 presetFromIntent
        // 用 Intent 真实值预置好），不再用 250ms 门挡整页——门开之前窗口
        // 里没有真内容，正是前四轮白屏 / 闪一下的根源（见类头注释）。
        // 只有简介留到 morph 跑完再画：简介是多行正文，在窗口形变时量字
        // 会有一帧文字重叠；250ms 后窗口已定型，再画就一次到位。只 gate
        // 文字，不 gate 整页。rememberSaveable：旋转重建时不再重复等一次。
        var morphSettled by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(MORPH_SETTLE_DELAY_MS)
            morphSettled = true
        }
        BookInfoRouteScreen(
            bookUrl = bookUrl,
            name = bookName,
            author = bookAuthor,
            origin = bookOrigin,
            coverPath = bookCoverPath,
            viewModel = viewModel,
            deferIntro = !morphSettled,
            onBack = {
                finish()
                true
            },
            onFinish = { resultCode, _ ->
                // 与 Nav3 里 onFinish 的等价行为：阅读页进来时回传 result，
                // 其余情况就是正常关掉（阅读页进详情仍走 Nav3，这里一般不会带 result）。
                if (resultCode != null) setResult(resultCode)
                finish()
                true
            },
            onOpenToc = { url ->
                openInMain(MainActivity.createTocIntent(this, url))
            },
            onOpenInfoEdit = { url ->
                // 用 startActivityForResult 打开编辑页，不 finish 本页；
                // 编辑页所在的 MainActivity 保存后 setResult(RESULT_OK) 并结束，
                // 回到这里刷新详情。取消时也回到这里（不刷新）。
                editBookLauncher.launch(MainActivity.createBookInfoEditIntent(this, url))
            },
            onOpenSearch = { keyword ->
                openInMain(MainActivity.createSearchIntent(this, keyword))
            },
            onOpenBookSourceEdit = { sourceUrl ->
                openInMain(MainActivity.createBookSourceEditIntent(this, sourceUrl))
            },
            onOpenSourceLogin = { sourceUrl ->
                openInMain(
                    MainActivity.createSourceLoginIntent(
                        this, SourceLoginType.BookSource, sourceKey = sourceUrl
                    )
                )
            },
            onOpenSettings = {
                openInMain(MainActivity.createSettingsPrivateIntent(this))
            },
            onOpenReader = { url, inBookshelf, chapterChanged ->
                openInMain(
                    MainActivity.createReadBookIntent(
                        this, url, inBookshelf = inBookshelf, chapterChanged = chapterChanged,
                        fromBookInfo = true, bookName = bookName, bookAuthor = bookAuthor,
                        bookOrigin = bookOrigin, bookCoverPath = bookCoverPath,
                    )
                )
            },
            onOpenMangaReader = { url, inBookshelf, chapterChanged ->
                openInMain(
                    MainActivity.createReadMangaIntent(
                        this, url, inBookshelf = inBookshelf, chapterChanged = chapterChanged,
                        fromBookInfo = true, bookName = bookName, bookAuthor = bookAuthor,
                        bookOrigin = bookOrigin, bookCoverPath = bookCoverPath,
                    )
                )
            },
            onOpenAudioPlay = { url, inBookshelf ->
                openInMain(MainActivity.createAudioPlayIntent(this, url, inBookshelf = inBookshelf))
            },
            onOpenVideoPlayer = { url, inBookshelf ->
                openInMain(MainActivity.createVideoPlayIntent(this, url, inBookshelf = inBookshelf))
            },
            onNavigateToBookInfo = { name, author, url, origin, coverPath ->
                // 详情页内再跳详情：开一个新的详情 Activity（无起飞锚点，走普通启动），
                // 返回时回到当前详情页，与 Nav3 里详情叠详情一致。
                startActivity(createIntent(this, name, author, url, origin, coverPath))
            },
            onNavigateToExploreShow = { title, sourceUrl, exploreUrl, usePageParamFallback ->
                openInMain(
                    MainActivity.createExploreShowIntent(
                        this, title, sourceUrl, exploreUrl, usePageParamFallback
                    )
                )
            },
            onOpenCharacterDetail = { url, characterId ->
                openInMain(MainActivity.createBookCharacterDetailIntent(this, url, characterId))
            },
            onOpenCharacterNetwork = { url ->
                openInMain(MainActivity.createBookCharacterNetworkIntent(this, url))
            },
            onOpenCharacterList = { url ->
                openInMain(MainActivity.createBookCharacterListIntent(this, url))
            },
            onOpenKnowledgeList = { url ->
                openInMain(MainActivity.createBookKnowledgeListIntent(this, url))
            },
            onOpenEventList = { url ->
                openInMain(MainActivity.createBookEventListIntent(this, url))
            },
        )
    }

    /**
     * 在 MainActivity 里打开目标页：复用已有 MainActivity（CLEAR_TOP 会把本 Activity
     * 顺带结束），再显式 finish 一次兜底（MainActivity 不在栈里新建实例时也能关掉本页）。
     */
    private fun openInMain(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    companion object {
        /**
         * 一镜到底进场动画约 200ms（窗口放大，activity-open 分支）到 336ms
         *（缩略图，半程 168ms 已褪尽）。250ms 只 gate 简介：morph 跑完、
         * 窗口定型后多行正文再量字，一次到位。若简介乱码回来，把这个数调大
         * 再另想办法；不要拿它去挡整页——整页从首帧就画，挡整页就是白屏 /
         * 闪一下的根源（见类头注释）。
         */
        private const val MORPH_SETTLE_DELAY_MS = 250L

        fun createIntent(
            context: Context,
            name: String? = null,
            author: String? = null,
            bookUrl: String,
            origin: String? = null,
            coverPath: String? = null,
        ): Intent = Intent(context, BookInfoActivity::class.java).apply {
            putExtra(MainIntent.EXTRA_BOOK_NAME, name)
            putExtra(MainIntent.EXTRA_BOOK_AUTHOR, author)
            putExtra(MainIntent.EXTRA_BOOK_URL, bookUrl)
            putExtra(MainIntent.EXTRA_BOOK_ORIGIN, origin)
            putExtra(MainIntent.EXTRA_BOOK_COVER, coverPath)
        }
    }
}
