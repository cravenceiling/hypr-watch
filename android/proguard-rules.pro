# R8 rules for scripts/build-android.sh. Activity and Service keeps are generated from
# AndroidManifest.xml by aapt2 --proguard.

# WebView finds bridge methods by name and by their runtime-visible @JavascriptInterface.
-keepattributes RuntimeVisibleAnnotations
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Readable stack traces and exception class names in this local development build.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Compile-time-only nullability annotations referenced by kotlin-stdlib.
-dontwarn org.jetbrains.annotations.**
