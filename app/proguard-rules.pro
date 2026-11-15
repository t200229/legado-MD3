# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# 混合时不使用大小写混合，混合后的类名为小写
-dontusemixedcaseclassnames

# 这句话能够使我们的项目混淆后产生映射文件
# 包含有类名->混淆后类名的映射关系
-verbose

# 保留Annotation不混淆
-keepattributes *Annotation*,InnerClasses

# 避免混淆泛型
-keepattributes Signature

# 指定混淆是采用的算法，后面的参数是一个过滤器
# 这个过滤器是谷歌推荐的算法，一般不做更改
-optimizations !code/simplification/cast,!field/*,!class/merging/*

-flattenpackagehierarchy

#############################################
#
# Android开发中一些需要保留的公共部分
#
#############################################
# 屏蔽错误Unresolved class name
#noinspection ShrinkerUnresolvedReference

# 移除Log类打印各个等级日志的代码，打正式包的时候可以做为禁log使用，这里可以作为禁止log打印的功能使用
# 记得proguard-android.txt中一定不要加-dontoptimize才起作用
# 另外的一种实现方案是通过BuildConfig.DEBUG的变量来控制
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# 保持js引擎调用的java类
-keep class * extends io.legado.app.help.JsExtensions{*;}
# 数据类
-keep class **.data.entities.**{*;}
# Gson反序列化用的数据传输类
-keep class io.legado.app.model.translation.**{*;}
-keep class io.legado.app.domain.model.DictPair{*;}
-keep class io.legado.app.domain.model.BookDictionary{*;}
-keep class io.legado.app.domain.model.TextChunk{*;}
-keep class io.legado.app.domain.model.ModuleDef{*;}
-keep class io.legado.app.domain.model.ModuleItem{*;}
-keep class io.legado.app.domain.model.CustomSetItem{*;}
-keep class io.legado.app.data.repository.GoogleTranslateResponse{*;}
-keep class io.legado.app.data.repository.GoogleSentence{*;}
-keep class io.legado.app.data.repository.GoogleSpell{*;}
-keep class io.legado.app.data.repository.OpenAIResponse{*;}
-keep class io.legado.app.data.repository.OpenAIChoice{*;}
-keep class io.legado.app.data.repository.OpenAIMessage{*;}
# 缓存 Cookie
-keep class **.help.http.CookieStore{*;}
-keep class **.help.CacheManager{*;}
# StrResponse
-keep class **.help.http.StrResponse{*;}

# markwon
-dontwarn org.commonmark.ext.gfm.**

# hutool-core hutool-crypto（书源 JS 通过 Packages.cn.hutool.* 反射调用，需整体保留）
-keep class cn.hutool.crypto.**{*;}
-keep class cn.hutool.core.codec.**{*;}
-keep class cn.hutool.core.util.**{*;}
-dontwarn cn.hutool.**

-keep class okhttp3.*{*;}
-keep class okio.*{*;}
-keep class com.jayway.jsonpath.*{*;}

# LiveEventBus
-keepclassmembers class androidx.lifecycle.LiveData {
    *** mObservers;
    *** mActiveCount;
}
-keepclassmembers class androidx.arch.core.internal.SafeIterableMap {
    *** size();
    *** putIfAbsent(...);
}

## ChangeBookSourceDialog initNavigationView
-keepclassmembers class androidx.appcompat.widget.Toolbar {
    *** mNavButtonView;
}

# FileDocExtensions.kt treeDocumentFileConstructor
-keep class androidx.documentfile.provider.TreeDocumentFile {
    <init>(...);
}

# JsoupXpath
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.AxisSelector{*;}
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.NodeTest{*;}
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.Function{*;}

## JSOUP
-keep class org.jsoup.**{*;}
-dontwarn org.jspecify.annotations.NullMarked

## ExoPlayer 反射设置ua 保证该私有变量不被混淆
-keepclassmembers class androidx.media3.datasource.cache.CacheDataSource$Factory {
    *** upstreamDataSourceFactory;
}
## ExoPlayer 如果还不能播放就取消注释这个
# -keep class com.google.android.exoplayer2.** {*;}

## 对外提供api
-keep class io.legado.app.api.ReturnData{*;}

# Cronet
-keepclassmembers class org.chromium.net.X509Util {
    *** sDefaultTrustManager;
    *** sTestTrustManager;
}

# Throwable
-keepnames class * extends java.lang.Throwable
-keepclassmembernames,allowobfuscation class * extends java.lang.Throwable{*;}
# 忽略 Ktor 在 Android 上对 Java SE 管理类的引用
-dontwarn java.lang.management.**
-dontwarn io.ktor.util.debug.IntellijIdeaDebugDetector
-keep,allowobfuscation class io.ktor.util.debug.** { *; }

# MediaPipe Tasks Vision：原生方法按原始类名绑定（Java_com_google_mediapipe_...），
# R8 一旦改名，so 加载/调用就失败，眼动翻页报 ExceptionInInitializerError。
# tasks-vision AAR 自身不带 consumer 规则，必须由应用保留。
-keep class com.google.mediapipe.** { *; }
# 上面的 keep 让 R8 必须解析 framework 类引用的两个 proto 类，但它们不在
# classpath 里（只被 GraphProfiler.getCalculatorProfiles /
# Graph.loadBinaryGraphTemplate 这两条 FaceLandmarker 根本不走的死代码引用）。
# 这两行是 R8 自己生成的 missing_rules.txt 的原样内容：只消警告，不改行为。
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate

# Flogger（MediaPipe 的 Graph 用 FluentLogger.forEnclosingClass()）：
# 它靠遍历调用栈找调用方，R8 一旦内联/改名，栈里找不到对应帧就抛
# IllegalStateException: no caller found on the stack，导致 Graph
# 静态初始化失败（ExceptionInInitializerError → NoClassDefFoundError）。
-keep class com.google.common.flogger.** { *; }
