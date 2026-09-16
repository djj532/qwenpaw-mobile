package app.qwenpaw.mobile;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.app.DownloadManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.widget.Toast;
import android.Manifest;
import android.content.pm.PackageManager;
import android.util.Log;
import android.util.Base64;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.media.MediaScannerConnection;

import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {
    private static final String TARGET_URL = "https://paw.xdjj.asia/";
    private static final int FILE_CHOOSER_REQUEST = 1001;

    private WebView webView;
    private ProgressBar progressBar;
    private View errorView;
    private ValueCallback<Uri[]> filePathCallback;
    private long pausedAt = 0; // v1.0.8: 记录进入后台的时刻
    private static final long RESUME_HEAL_THRESHOLD_MS = 30 * 1000L; // 后台超过30秒才触发自愈

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        errorView = findViewById(R.id.errorView);

        findViewById(R.id.retryButton).setOnClickListener(v -> loadUrl(TARGET_URL));

        setupWebView();
        setupBackNavigation();
        startKeepAliveService();
        requestNotificationPermission();
        prewarmConnection(); // v1.0.8: 启动预热，DNS+TLS 提前握手

        if (savedInstanceState == null) {
            loadUrl(TARGET_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    /**
     * v1.0.8: 后台线程预建连接 —— DNS 解析 + TLS 握手先行，
     * loadUrl 时直接复用已建好的连接，冷启动更快。
     */
    private void prewarmConnection() {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(TARGET_URL);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(100);
                conn.setRequestMethod("HEAD");
                try { conn.getResponseCode(); } catch (java.net.SocketTimeoutException ignored) {}
                conn.disconnect();
            } catch (Exception ignored) {}
        }, "qp-prewarm").start();
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void saveBlob(String base64Data, String fileName, String mimeType) {
            runOnUiThread(() -> {
                try {
                    String base64 = base64Data.contains(",") ? base64Data.substring(base64Data.indexOf(",") + 1) : base64Data;
                    byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                    String safeName = (fileName != null && !fileName.trim().isEmpty() && !fileName.equals("download")) ? fileName : "产物_" + System.currentTimeMillis() + ".bin";
                    saveFileToDownloads(bytes, safeName, mimeType);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "保存文件失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private void saveFileToDownloads(byte[] bytes, String fileName, String mimeType) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, (mimeType != null && !mimeType.isEmpty()) ? mimeType : "application/octet-stream");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os != null) os.write(bytes);
                    }
                    Toast.makeText(this, "文件已成功保存到【下载】目录:\n" + fileName, Toast.LENGTH_LONG).show();
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, fileName);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(bytes);
                }
                MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, null, null);
                Toast.makeText(this, "文件已成功保存到【下载】目录:\n" + fileName, Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "写入存储失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setupWebView() {
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        String defaultUa = s.getUserAgentString();
        s.setUserAgentString(defaultUa + " QwenPawMobileApp/861129");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);
        cm.setCookie("https://paw.xdjj.asia", "qwenpaw_auth_session=2000000000:78d0377796268a579b447321d5ac58205f8ee3a426d4fd747c3a8aa713938f59; Path=/; Domain=paw.xdjj.asia; Secure; SameSite=None");
        cm.flush();

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new InnerWebViewClient());
        webView.setWebChromeClient(new InnerChromeClient());

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                if (url != null && url.startsWith("blob:")) {
                    String guessName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    String js = "(function() {" +
                        "  try {" +
                        "    var xhr = new XMLHttpRequest();" +
                        "    xhr.open('GET', '" + url + "', true);" +
                        "    xhr.responseType = 'blob';" +
                        "    xhr.onload = function() {" +
                        "      if (this.status === 200) {" +
                        "        var r = new FileReader();" +
                        "        r.onloadend = function() {" +
                        "          var b64 = r.result;" +
                        "          var mime = (b64.split(';')[0] || '').replace('data:', '');" +
                        "          if (window.AndroidBridge) {" +
                        "            window.AndroidBridge.saveBlob(b64, '" + guessName + "', mime);" +
                        "          }" +
                        "        };" +
                        "        r.readAsDataURL(this.response);" +
                        "      }" +
                        "    };" +
                        "    xhr.send();" +
                        "  } catch(e) {}" +
                        "})();";
                    webView.evaluateJavascript(js, null);
                    return;
                }

                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null) {
                        request.addRequestHeader("Cookie", cookies);
                    }
                    request.addRequestHeader("User-Agent", userAgent != null ? userAgent : s.getUserAgentString());
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    request.setTitle(fileName);
                    request.setDescription("QwenPaw 产物文件下载");
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                        Toast.makeText(MainActivity.this, "开始下载: " + fileName, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception ex) {
                    Toast.makeText(MainActivity.this, "下载失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void loadUrl(String url) {
        errorView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();

            // v1.0.8: 后台切回自愈 —— 只有离开超过 30 秒才触发数据刷新
            // （避免频繁切换打扰；短时切换由页面自身的轮询自然恢复）
            long bgMillis = pausedAt > 0 ? System.currentTimeMillis() - pausedAt : 0;
            if (bgMillis > RESUME_HEAL_THRESHOLD_MS) {
                // 延迟注入：切回瞬间 WebView JS 引擎刚解冻，
                // 立即执行会因 React 监听器未就绪而丢失事件
                webView.postDelayed(this::injectResumeHeal, 200);
            }
        }
    }

    private void injectResumeHeal() {
        if (webView == null || pausedAt <= 0) return;
        pausedAt = 0; // 消费掉，防止重复触发
        webView.evaluateJavascript(
            "(function() {" +
            "  try {" +
            "    window.dispatchEvent(new Event('focus'));" +
            "    document.dispatchEvent(new Event('visibilitychange'));" +
            "    window.dispatchEvent(new Event('online'));" +
            // 检查用户是否正在交互（有文本选区/正在输入），有则跳过本次刷新避免打断
            "    var sel = window.getSelection && window.getSelection();" +
            "    var active = document.activeElement;" +
            "    var isTyping = active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.isContentEditable);" +
            "    if ((sel && sel.toString().length > 0) || isTyping) { return; }" +
            "    var m = window.location.pathname.match(/\\/chat\\/([a-zA-Z0-9_-]+)/);" +
            "    if (m && m[1]) {" +
            "      window.dispatchEvent(new CustomEvent('qwenpaw:sidebar-select-session', { detail: { sessionId: m[1] } }));" +
            "    }" +
            "  } catch(e) {}" +
            "})();",
            null
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
            pausedAt = System.currentTimeMillis(); // v1.0.8: 记录进入后台的时间戳
        }
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    private class InnerWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (url.contains("/api/workspace/file-download") || url.contains("/api/artifacts/download") || url.contains("/file-download")) {
                view.loadUrl(url);
                return true;
            }
            if (url.startsWith("https://paw.xdjj.asia")) {
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {}
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            CookieManager.getInstance().flush();
            super.onPageFinished(view, url);
            progressBar.setVisibility(View.GONE);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                progressBar.setVisibility(View.GONE);
                webView.setVisibility(View.GONE);
                errorView.setVisibility(View.VISIBLE);
            }
        }
    }

    private class InnerChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (newProgress < 100) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(newProgress);
            } else {
                progressBar.setVisibility(View.GONE);
            }
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
            if (MainActivity.this.filePathCallback != null) {
                MainActivity.this.filePathCallback.onReceiveValue(null);
            }
            MainActivity.this.filePathCallback = filePathCallback;

            Intent intent = fileChooserParams.createIntent();
            try {
                startActivityForResult(intent, FILE_CHOOSER_REQUEST);
            } catch (Exception e) {
                MainActivity.this.filePathCallback = null;
                return false;
            }
            return true;
        }
    }

    private void startKeepAliveService() {
        try {
            Intent serviceIntent = new Intent(this, QwenPawForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Failed to start keep-alive service", e);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }
}
