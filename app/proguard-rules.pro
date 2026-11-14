# yt-dlp wrapper: JNI/reflection + Jackson models
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-keep class org.apache.commons.codec.** { *; }
-keep class org.apache.commons.lang3.** { *; }
-keep class org.tukaani.xz.** { *; }
-keep class com.github.luben.zstd.** { *; }
-keep class org.brotli.** { *; }
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions, RuntimeVisibleAnnotations
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.apache.commons.io.**
-dontwarn org.tukaani.**
-dontwarn com.github.luben.**
-dontwarn org.brotli.**
-dontwarn java.lang.management.**
-dontwarn javax.annotation.**
-dontwarn org.w3c.dom.bootstrap.**

# WebView bridge methods are called from JavaScript by name
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

# App code is tiny; keep it as-is so nothing reflective breaks
-keep class com.mrk.seitune.** { *; }

# Vosk speech recognition (JNA)
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**
