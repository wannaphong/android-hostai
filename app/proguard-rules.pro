# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.wannaphong.hostai.** { *; }

# Keep native methods (JNI)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep LlamaModel and its native methods
-keep class com.wannaphong.hostai.LlamaModel {
    native <methods>;
    <init>(...);
}
