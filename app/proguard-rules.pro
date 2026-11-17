# The page calls these through the WebView bridge by name.
-keepclassmembers class com.barcodewa.app.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
