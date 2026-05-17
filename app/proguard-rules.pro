# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
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

# Keep Log.e() for crash debugging in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
}

# Suppress warnings from Android framework and libraries
-dontwarn android.**
-dontwarn androidx.**
-dontwarn com.google.**
-dontwarn com.android.**
-dontwarn org.jetbrains.**
-dontwarn kotlin.**
-dontwarn javax.**
-dontwarn java.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn coil.**
-dontwarn org.json.**
-dontwarn ai.onnxruntime.**

# === JGSS (referenced from Apache HTTP Client used by Google API) ===
-dontwarn org.ietf.jgss.**

# === ML Kit ===
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }

# === Coil Image Loader ===
-keep class coil.** { *; }

# === ONNX Runtime ===
-keep class ai.onnxruntime.** { *; }

# === Gson (models used for JSON serialization/deserialization) ===
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.example.ocrmanga.data.models.** { *; }
-keep class com.example.ocrmanga.data.ocr.models.** { *; }
-keep class com.example.ocrmanga.ml.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }

# === OpenCV JNI ===
-keep class org.opencv.** { *; }

# === Room Database ===
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * extends androidx.room.** { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.**

# === Compose Runtime ===
-keep class androidx.compose.** { *; }

# === Kotlin Serialization ===
-keepattributes InnerClasses, EnclosingMethod
-keep class kotlinx.serialization.** { *; }

# === Google Drive API & Google API Client ===
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.services.drive.model.** { *; }

# === Google Gemini AI SDK ===
-keep class com.google.ai.client.generativeai.** { *; }

# === Google Play Services Auth ===
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.auth.api.signin.** { *; }

# === OkHttp ===
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# === AndroidX Datastore ===
-keep class androidx.datastore.** { *; }

# === AndroidX Navigation ===
-keep class androidx.navigation.** { *; }

# === Kotlinx Coroutines ===
-keep class kotlinx.coroutines.** { *; }

# === AndroidX ExifInterface ===
-keep class androidx.exifinterface.** { *; }