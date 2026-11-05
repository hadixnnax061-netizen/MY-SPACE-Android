package com.myspace.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
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
    private static final int REQUEST_FILE = 1001;
    private static final int REQUEST_SAVE = 1002;
    private static final int MAX_SAVE_BYTES = 8 * 1024 * 1024;

    private WebView webView;
    private GeminiBridge bridge;
    private ValueCallback<Uri[]> pendingFileCallback;
    private PendingSave pendingSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        configureWebView();
        setContentView(webView);
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
        settings.setUserAgentString(settings.getUserAgentString() + " MYSPACE-Android/16.1");

        webView.setBackgroundColor(getResources().getColor(R.color.space_bg));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setHorizontalScrollBarEnabled(false);
    }

    private void applyInsets() {
        webView.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Api30.applyInsets(view, insets);
            }
            return insets;
        });
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

        static void applyInsets(View view, WindowInsets insets) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom));
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
