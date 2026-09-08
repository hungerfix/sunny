# R8/ProGuard rules

-dontwarn io.github.libxposed.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep class com.hazbu.xcam.** { *; }

-keep class io.github.libxposed.api.** { *; }
-keep interface io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.service.** { *; }
-keep interface io.github.libxposed.service.** { *; }

-keep public class com.hazbu.xcam.xposed.XCamModule {
    public <init>(io.github.libxposed.api.XposedInterface, io.github.libxposed.api.XposedModuleInterface$ModuleLoadedParam);
}

# Media3 / ExoPlayer — HLS + OkHttp data sources
-keep class androidx.media3.exoplayer.hls.** { *; }
-keep class androidx.media3.datasource.okhttp.** { *; }
-keep class androidx.media3.exoplayer.source.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-keepattributes *Annotation*,Signature,EnclosingMethod,InnerClasses,SourceFile,LineNumberTable

-dontoptimize

