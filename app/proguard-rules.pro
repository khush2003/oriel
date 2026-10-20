# Nothing is renamed: InnerTubeX, QuickJS (via JNI) and kotlinx.serialization reach for
# classes by name. R8 still optimizes Compose, Media3 and coroutines, which is where time goes.
-dontobfuscate

-keep class com.metrolist.innertubex.** { *; }
-keep class com.dokar.quickjs.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.oriel.**$$serializer { *; }
-keepclassmembers class app.oriel.** { *** Companion; }

# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn okhttp3.internal.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn java.lang.management.**
-dontwarn javax.script.**
