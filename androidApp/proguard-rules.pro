# Preserve Kotlin metadata, generic signatures, annotations, and inner-class
# structure needed by kotlinx.serialization, Navigation Compose, and Supabase.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class kotlin.Metadata { *; }

# ------------------------------------------------------------------------------
# kotlinx.serialization (official rules + Navigation Compose type-safe routes)
# ------------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault

-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.badmintontracker.**$$serializer { *; }
-keepclassmembers class com.badmintontracker.** {
    *** Companion;
}
-keepclasseswithmembers class com.badmintontracker.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class **
-keepclasseswithmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keep class <1>$$serializer { *; }

# Keep serializable model/route/DTO types in our own packages intact so
# Postgrest, Storage, Navigation Compose, and local JSON caches never break.
-keep @kotlinx.serialization.Serializable class com.badmintontracker.** { *; }
-keep class com.badmintontracker.android.nav.Route** { *; }
-keep class com.badmintontracker.shared.model.** { *; }
-keep class com.badmintontracker.shared.scoring.** { *; }
-keep class com.badmintontracker.shared.players.** { *; }
-keep class com.badmintontracker.shared.localvideo.** { *; }
-keep class com.badmintontracker.shared.local.** { *; }
-keep class com.badmintontracker.shared.prefs.** { *; }
-keep class com.badmintontracker.analysis.** { *; }

# ------------------------------------------------------------------------------
# Supabase Kotlin SDK
# ------------------------------------------------------------------------------
-keep class io.github.jan.supabase.** { *; }
-dontwarn io.github.jan.supabase.**

# ------------------------------------------------------------------------------
# Ktor Client (CIO engine & plugins)
# ------------------------------------------------------------------------------
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn java.lang.instrument.**
-dontwarn javax.naming.**
-dontwarn com.sun.**
-dontwarn sun.misc.**

# ------------------------------------------------------------------------------
# ONNX Runtime Android (JNI invokes Java classes, fields, and constructors)
# ------------------------------------------------------------------------------
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ------------------------------------------------------------------------------
# AndroidX Media3 (ExoPlayer)
# ------------------------------------------------------------------------------
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ------------------------------------------------------------------------------
# Coil / OkHttp / Okio optional JVM-only references
# ------------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn coil3.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn javax.annotation.**

# ------------------------------------------------------------------------------
# multiplatform-settings
# ------------------------------------------------------------------------------
-dontwarn com.russhwolf.settings.**
