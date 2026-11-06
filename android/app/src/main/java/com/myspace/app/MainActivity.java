package com.myspace.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JsResult;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebChromeClient.FileChooserParams;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final String TAG = "MySpace";
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/www/index.html";
    static final String EXTRA_WIDGET_STAR_ID = "com.myspace.app.WIDGET_STAR_ID";
    static final String EXTRA_OPEN_CHAT = "com.myspace.app.OPEN_CHAT";
    private static final int REQUEST_NOTIFICATIONS_PERMISSION = 1003;
    private static final int REQUEST_FILE = 1001;
    private static final int REQUEST_SAVE = 1002;
    private static final int MAX_SAVE_BYTES = 8 * 1024 * 1024;

    private FrameLayout root;
    private WebView webView;
    private float navInsetCss = 0f;
    private GeminiBridge bridge;
    private ValueCallback<Uri[]> pendingFileCallback;
    private PendingSave pendingSave;
    private String pendingWidgetStarId;
    private boolean pendingOpenChat;
    private String pendingReminderCallbackId;
    private int pendingReminderHour;
    private int pendingReminderMinute;
    private String pendingReminderStarName;
    private String pendingReminderMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pendingWidgetStarId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_WIDGET_STAR_ID);
        pendingOpenChat = getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_CHAT, false);

        root = new FrameLayout(this);
        root.setBackgroundColor(getResources().getColor(R.color.space_bg));
        webView = new WebView(this);
        configureWebView();
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        configureWindow();

        if (Build.VERSION.SDK_INT >= 30) {
            applyInsets();
        }

        bridge = new GeminiBridge(this, webView, new SecureKeyStore(this));
        webView.addJavascriptInterface(bridge, "MYSPACE_NATIVE");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null
                        && "GET".equalsIgnoreCase(request.getMethod())
                        && HOST.equals(request.getUrl().getHost())) {
                    WebResourceResponse response = serveAsset(request.getUrl());
                    if (response != null) return response;
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request == null ? null : request.getUrl();
                if (uri == null) return false;
                if (HOST.equals(uri.getHost())) return false;
                openExternalUrl(uri.toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "MYSPACE_PAGE_FINISHED " + url);
                sendNavInset();
                focusWidgetBodyInPage();
                openPendingChatInPage();
                view.postDelayed(() -> view.evaluateJavascript(
                        "(function(){try{"
                        + "var a=document.getElementById('app'),v=document.getElementById('v'),n=document.getElementById('nav');"
                        + "var vr=v?v.getBoundingClientRect():null,nr=n?n.getBoundingClientRect():null;"
                        + "return JSON.stringify({iw:innerWidth,ih:innerHeight,"
                        + "sw:document.documentElement.scrollWidth,sh:document.documentElement.scrollHeight,"
                        + "app:a?{disp:getComputedStyle(a).display,op:getComputedStyle(a).opacity,"
                        + "z:getComputedStyle(a).zIndex,r:a.getBoundingClientRect()}:null,"
                        + "v:v?{kids:v.children.length,txt:(v.innerText||'').slice(0,60),"
                        + "scroll:v.scrollTop,r:vr}:null,"
                        + "n:n?{kids:n.children.length,r:nr}:null});"
                        + "}catch(e){return 'ERR '+e}})()",
                        value -> Log.i(TAG, "MYSPACE_DIAG " + value)), 4000);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    toast("تعذّر تحميل التطبيق. أعد تثبيت الحزمة ثم حاول مجدداً.");
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                return openFileChooser(callback, params);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.i(TAG, "MYSPACE_CONSOLE " + message.messageLevel() + ": "
                        + message.message() + " @" + message.lineNumber());
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("حسناً", (dialog, which) -> result.confirm())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("متابعة", (dialog, which) -> result.confirm())
                        .setNegativeButton("إلغاء", (dialog, which) -> result.cancel())
                        .show();
                return true;
            }
        });

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView.loadUrl(START_URL);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setBackgroundDrawableResource(R.color.space_bg);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= 30) {
            Api30.configureWindow(window);
        } else {
            window.setStatusBarColor(getResources().getColor(R.color.space_bg));
            window.setNavigationBarColor(getResources().getColor(R.color.space_bg));
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " MYSPACE-Android/3.0");

        webView.setBackgroundColor(getResources().getColor(R.color.space_bg));
        if (Build.VERSION.SDK_INT >= 26) {
            webView.setDefaultFocusHighlightEnabled(false);
        }
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setHorizontalScrollBarEnabled(false);
    }

    private void applyInsets() {
        final float density = getResources().getDisplayMetrics().density;
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                navInsetCss = Api30.applyInsets(view, insets, density);
                sendNavInset();
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    private void sendNavInset() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "window.__myspaceInsets&&window.__myspaceInsets(" + navInsetCss + ")",
                null);
    }

    /** Isolated so the API 30+ window classes are never loaded on older devices. */
    private static final class Api30 {
        static void configureWindow(Window window) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        }

        static float applyInsets(View view, WindowInsets insets, float density) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            boolean imeVisible = insets.isVisible(WindowInsets.Type.ime());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            // Keep the content clear of the keyboard, but let the page's own
            // bottom nav paint behind the (transparent) system bar.
            view.setPadding(0, bars.top, 0, imeVisible ? ime.bottom : 0);
            return imeVisible ? 0f : (bars.bottom / density);
        }
    }

    /**
     * Serves the packaged PWA from assets on a stable HTTPS origin without any
     * third-party dependency. Keeping the https origin preserves localStorage,
     * IndexedDB and secure-context behaviour exactly as in a browser.
     */
    private WebResourceResponse serveAsset(Uri uri) {
        String path = uri.getPath();
        if (path == null || !path.startsWith("/assets/")) return null;
        String assetPath = path.substring("/assets/".length());
        if (assetPath.isEmpty()) assetPath = "www/index.html";
        if (assetPath.startsWith("/") || assetPath.contains("..")) return null;

        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control",
                assetPath.endsWith("index.html") ? "no-store" : "no-cache");
        headers.put("X-Content-Type-Options", "nosniff");
        String mime = assetMime(assetPath);
        try {
            InputStream input = getAssets().open(assetPath);
            String encoding = (mime.startsWith("text/")
                    || mime.equals("application/javascript")
                    || mime.equals("application/json")
                    || mime.equals("image/svg+xml")) ? "utf-8" : null;
            return new WebResourceResponse(mime, encoding, 200, "OK", headers, input);
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                    new HashMap<>(), new ByteArrayInputStream(new byte[0]));
        }
    }

    private static String assetMime(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".json") || lower.endsWith(".webmanifest")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }

    private boolean openFileChooser(ValueCallback<Uri[]> callback, FileChooserParams params) {
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(null);
        }
        pendingFileCallback = callback;

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        ArrayList<String> mimeTypes = new ArrayList<>();
        String[] accepted = params == null ? null : params.getAcceptTypes();
        if (accepted != null) {
            for (String group : accepted) {
                if (group == null) continue;
                for (String part : group.split(",")) {
                    String value = part.trim().toLowerCase(Locale.ROOT);
                    if (value.isEmpty()) continue;
                    if (value.startsWith(".")) {
                        String mime = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(value.substring(1));
                        if (mime != null) mimeTypes.add(mime);
                    } else if (value.contains("/")) {
                        mimeTypes.add(value);
                    }
                }
            }
        }
        if (mimeTypes.size() == 1) {
            intent.setType(mimeTypes.get(0));
        } else if (mimeTypes.size() > 1) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toArray(new String[0]));
        }
        if (params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }

        try {
            startActivityForResult(Intent.createChooser(intent, "اختر ملفاً"), REQUEST_FILE);
            return true;
        } catch (ActivityNotFoundException e) {
            pendingFileCallback = null;
            callback.onReceiveValue(null);
            toast("لا يوجد تطبيق لاختيار الملفات على هذا الجهاز.");
            return false;
        }
    }

    public void saveBlob(String name, String mime, String base64) {
        if (base64 == null) return;
        if (pendingSave != null) {
            toast("انتظر إتمام الحفظ السابق.");
            return;
        }
        byte[] bytes;
        try {
            bytes = Base64.decode(base64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            toast("تعذّر تجهيز الملف للحفظ.");
            return;
        }
        if (bytes.length == 0) {
            toast("الملف فارغ.");
            return;
        }
        if (bytes.length > MAX_SAVE_BYTES) {
            toast("الملف أكبر من الحد المسموح (٨ ميغابايت).");
            return;
        }
        String safeName = safeFileName(name);
        String type = mime == null || mime.trim().isEmpty()
                ? "application/octet-stream" : mime.trim();
        pendingSave = new PendingSave(bytes);

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        intent.putExtra(Intent.EXTRA_TITLE, safeName);
        try {
            startActivityForResult(intent, REQUEST_SAVE);
        } catch (ActivityNotFoundException e) {
            pendingSave = null;
            toast("لا يوجد مدير ملفات لحفظ النسخة.");
        }
    }

    public void copyText(String text) {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("MY SPACE",
                    text == null ? "" : text));
        } catch (Exception e) {
            Log.w(TAG, "Clipboard unavailable", e);
        }
    }

    public void requestPlanetWidget() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                AppWidgetManager manager = AppWidgetManager.getInstance(this);
                ComponentName provider = new ComponentName(this, PlanetWidgetProvider.class);
                if (manager.isRequestPinAppWidgetSupported()) {
                    manager.requestPinAppWidget(provider, null, null);
                    toast("اختر «إضافة» ثم عيّن الكوكب/النجم وساعته لكل ودجت.");
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        toast("اضغط مطولاً على الشاشة الرئيسية ← الودجات ← «جرم MY SPACE».");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingWidgetStarId = intent == null ? null : intent.getStringExtra(EXTRA_WIDGET_STAR_ID);
        pendingOpenChat = intent != null && intent.getBooleanExtra(EXTRA_OPEN_CHAT, false);
        focusWidgetBodyInPage();
        openPendingChatInPage();
    }

    private void openPendingChatInPage() {
        if (!pendingOpenChat || webView == null) return;
        pendingOpenChat = false;
        webView.postDelayed(() -> webView.evaluateJavascript(
                "(function(){try{if(typeof setV==='function')setV('chat')}catch(e){}})()", null), 500);
    }

    private void focusWidgetBodyInPage() {
        if (webView == null || pendingWidgetStarId == null || pendingWidgetStarId.isEmpty()) return;
        String id = pendingWidgetStarId;
        pendingWidgetStarId = null;
        String script = "window.__myspaceOpenStar&&window.__myspaceOpenStar("
                + org.json.JSONObject.quote(id) + ")";
        webView.postDelayed(() -> webView.evaluateJavascript(script, null), 450);
    }

    public void configureDailyReminder(String callbackId, boolean enabled,
                                       int hour, int minute, String starName, String mode) {
        if (callbackId == null || callbackId.isEmpty() || callbackId.length() > 80) return;
        if (!enabled) {
            DailyReminderScheduler.disable(this);
            if (bridge != null) bridge.completeDailyReminder(callbackId, 200,
                    "تم إيقاف التذكير اليومي.");
            return;
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            if (bridge != null) bridge.completeDailyReminder(callbackId, 400,
                    "اختر وقتًا صالحًا للتذكير.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            pendingReminderCallbackId = callbackId;
            pendingReminderHour = hour;
            pendingReminderMinute = minute;
            pendingReminderStarName = starName;
            pendingReminderMode = mode;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS_PERMISSION);
            return;
        }
        finishDailyReminder(callbackId, hour, minute, starName, mode);
    }

    private void finishDailyReminder(String callbackId, int hour, int minute, String starName, String mode) {
        if (!DailyReminderScheduler.canPost(this)) {
            if (bridge != null) bridge.completeDailyReminder(callbackId, 403,
                    "إشعارات MY SPACE محظورة في إعدادات Android. اسمح بها ثم حاول مجددًا.");
            return;
        }
        DailyReminderScheduler.enable(this, hour, minute, starName, mode);
        if (bridge != null) bridge.completeDailyReminder(callbackId, 200,
                "تم حفظ التذكير محليًا. قد يتأخر قليلًا بسبب Android وتوفير الطاقة.");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS_PERMISSION) return;
        String id = pendingReminderCallbackId;
        pendingReminderCallbackId = null;
        if (id == null) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            finishDailyReminder(id, pendingReminderHour, pendingReminderMinute,
                    pendingReminderStarName, pendingReminderMode);
        } else if (bridge != null) {
            bridge.completeDailyReminder(id, 403,
                    "لم يُمنح إذن الإشعارات. لم يتم تفعيل التذكير؛ يمكنك تغيير الإذن من إعدادات Android.");
        }
        pendingReminderStarName = null;
        pendingReminderMode = null;
    }

    public void shareText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String safe = text.length() > 6000 ? text.substring(0, 6000) : text;
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, safe);
        try {
            startActivity(Intent.createChooser(send, "مشاركة بطاقة MY SPACE"));
        } catch (ActivityNotFoundException e) {
            toast("لا يوجد تطبيق متاح للمشاركة.");
        }
    }

    public void openExternalUrl(String url) {
        if (url == null || url.isEmpty()) return;
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return;
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            toast("لا يوجد متصفح لفتح الرابط.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILE) {
            if (pendingFileCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    results = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        results[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            pendingFileCallback.onReceiveValue(results);
            pendingFileCallback = null;
            return;
        }
        if (requestCode == REQUEST_SAVE) {
            PendingSave save = pendingSave;
            pendingSave = null;
            if (save == null) return;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try (OutputStream out = getContentResolver()
                        .openOutputStream(data.getData(), "w")) {
                    if (out == null) throw new IOException("no output stream");
                    out.write(save.bytes);
                    out.flush();
                    toast("تم حفظ الملف على الجهاز.");
                } catch (Exception e) {
                    Log.e(TAG, "Unable to write export", e);
                    toast("تعذّر حفظ الملف.");
                }
            } else {
                toast("أُلغي الحفظ.");
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        webView.evaluateJavascript(
                "(function(){try{return window.__myspaceAndroidBack"
                        + "?String(window.__myspaceAndroidBack()):'false'}catch(e){return 'false'}})()",
                value -> {
                    if (!"true".equals(value)) {
                        finish();
                    }
                });
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) {
            bridge.shutdown();
            bridge = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("MYSPACE_NATIVE");
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private static String safeFileName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) value = "MY_SPACE_Backup.json";
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (value.length() > 120) value = value.substring(0, 120);
        return value;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static final class PendingSave {
        final byte[] bytes;

        PendingSave(byte[] bytes) {
            this.bytes = bytes;
        }
    }
}
