# The app has no reflection-based serialization; keep the JS bridge explicit.
-keepclassmembers class com.myspace.app.GeminiBridge {
    @android.webkit.JavascriptInterface <methods>;
}
