# Keep WebView JavaScript interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Keep Android annotations
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
