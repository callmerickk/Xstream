# Shrink only: unused code is removed, nothing is renamed (safer for reflection / JNI).
-dontobfuscate
-dontoptimize
-ignorewarnings
-dontwarn **
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# App code (small) — keep everything.
-keep class com.example.streamnest.** { *; }

# LibVLC calls back into its Java classes from native code (JNI).
-keep class org.videolan.** { *; }

# Media3 loads HLS / DASH and renderers by reflection.
-keep class androidx.media3.** { *; }

# Trailers talk to a WebView through a JavaScript interface.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

# kotlinx.serialization
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *** INSTANCE; kotlinx.serialization.KSerializer serializer(...); }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp / Coil
-keep class okhttp3.internal.publicsuffix.** { *; }

# Credential Manager finds its Google Play services provider by reflection.
-keep class androidx.credentials.playservices.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# Rhino (JavaScript plugins) uses reflection and optional desktop Java classes.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn javax.lang.model.**

# CloudStream plugins (.cs3) are loaded at run time and call these libraries by name: keep all of them whole.
-keep class com.lagradost.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class com.google.gson.** { *; }
-keep class io.ktor.** { *; }
-keep class androidx.appcompat.** { *; }
-keep class androidx.fragment.** { *; }
-keep class androidx.core.** { *; }
-keep class com.google.android.material.** { *; }

# Vosk wake word (JNA)
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
