package com.ldj.djspdf;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String APP_URL = "file:///android_asset/index.html";
    private static final String VERSION_URL = "https://ldjkorea.github.io/DJS-PDF/version.json";
    private static final String INCOMING_FILE_NAME = "current_incoming.pdf";
    private static final int REQUEST_PICK_PDF = 2001;
    private static final int FILE_CHOOSER_REQUEST_CODE = 2002;

    private WebView webView;
    private ValueCallback<Uri[]> uploadMessage;
    private boolean pageLoaded = false;
    private boolean incomingPdfAvailable = false;
    private String incomingPdfName = null;
    private String appVersionName = "1.0.0";
    private long appVersionCode = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Status bar & Navigation bar obsidian styling
        Window window = getWindow();
        window.setStatusBarColor(Color.parseColor("#121214"));
        window.setNavigationBarColor(Color.parseColor("#121214"));

        // Read app package version
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            appVersionName = pInfo.versionName;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                appVersionCode = pInfo.getLongVersionCode();
            } else {
                appVersionCode = pInfo.versionCode;
            }
        } catch (Exception ignored) {}

        // Initialize WebView
        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#121214"));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d("DjsPdfJS", "[" + cm.messageLevel() + "] " + cm.message() + " (" + cm.sourceId() + ":" + cm.lineNumber() + ")");
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }
                uploadMessage = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/pdf");
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                    fallback.addCategory(Intent.CATEGORY_OPENABLE);
                    fallback.setType("application/pdf");
                    try {
                        startActivityForResult(Intent.createChooser(fallback, "PDF 파일 선택"), FILE_CHOOSER_REQUEST_CODE);
                    } catch (Exception ex) {
                        uploadMessage = null;
                        return false;
                    }
                }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri reqUri = request.getUrl();
                String host = reqUri.getHost();
                String path = reqUri.getPath();

                // Intercept incoming PDF request (same origin virtual stream)
                if (path != null && path.toLowerCase().endsWith("incoming.pdf")) {
                    File tempPdf = new File(getCacheDir(), INCOMING_FILE_NAME);
                    if (tempPdf.exists()) {
                        try {
                            FileInputStream fis = new FileInputStream(tempPdf);
                            Map<String, String> headers = new HashMap<>();
                            headers.put("Content-Type", "application/pdf");
                            headers.put("Cache-Control", "no-cache, no-store, must-revalidate");
                            headers.put("Access-Control-Allow-Origin", "*");
                            return new WebResourceResponse("application/pdf", "binary", 200, "OK", headers, fis);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }

                // Offline fallback: serve bundled assets if device has no internet
                if (!isNetworkConnected() && host != null && host.contains("github.io")) {
                    String assetName = getAssetFileName(path);
                    if (assetName != null) {
                        try {
                            InputStream is = getAssets().open(assetName);
                            String mime = getMimeType(assetName);
                            Map<String, String> headers = new HashMap<>();
                            headers.put("Access-Control-Allow-Origin", "*");
                            return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, is);
                        } catch (Exception ignored) {}
                    }
                }

                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                if (failingUrl != null && failingUrl.startsWith("http")) {
                    view.loadUrl("file:///android_asset/index.html");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageLoaded = true;
                if (incomingPdfAvailable && incomingPdfName != null) {
                    notifyPdfToWeb(incomingPdfName);
                }
            }
        });

        // Handle initial intent (e.g. KakaoTalk or File Manager opening a PDF)
        handleIntent(getIntent());

        // Load App
        webView.loadUrl(APP_URL);

        // Background Auto-Update Check
        checkAutoUpdate(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }

        if (uri != null) {
            processIncomingUri(uri);
        }
    }

    private void processIncomingUri(Uri uri) {
        new Thread(() -> {
            try {
                String fileName = resolveFileName(uri);
                File tempPdf = new File(getCacheDir(), INCOMING_FILE_NAME);

                InputStream in = null;
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    in = new FileInputStream(new File(uri.getPath()));
                } else {
                    in = getContentResolver().openInputStream(uri);
                }

                if (in == null) {
                    Log.e("DjsPdf", "InputStream is null for " + uri);
                    return;
                }

                try (InputStream is = in;
                     OutputStream out = new FileOutputStream(tempPdf)) {
                    byte[] buffer = new byte[16384];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                    out.flush();
                }

                incomingPdfAvailable = true;
                incomingPdfName = fileName;
                Log.i("DjsPdf", "Successfully copied PDF (" + tempPdf.length() + " bytes) as " + fileName);

                if (pageLoaded) {
                    runOnUiThread(() -> notifyPdfToWeb(fileName));
                }
            } catch (Exception e) {
                Log.e("DjsPdf", "processIncomingUri failed", e);
                runOnUiThread(() -> Toast.makeText(this, "PDF 파일 읽기 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void notifyPdfToWeb(String fileName) {
        String quotedName = JSONObject.quote(fileName);
        Log.i("DjsPdf", "notifyPdfToWeb invoking openIncomingFromNative for: " + quotedName);
        String js = "(function() {" +
                "  var name = " + quotedName + ";" +
                "  if (window.openIncomingFromNative) {" +
                "    window.openIncomingFromNative(name);" +
                "  } else {" +
                "    var tries = 30;" +
                "    var iv = setInterval(function() {" +
                "      if (window.openIncomingFromNative) {" +
                "        clearInterval(iv);" +
                "        window.openIncomingFromNative(name);" +
                "      } else if (--tries <= 0) {" +
                "        clearInterval(iv);" +
                "        console.error('Timed out waiting for openIncomingFromNative');" +
                "      }" +
                "    }, 100);" +
                "  }" +
                "})();";
        webView.evaluateJavascript(js, value -> {
            Log.i("DjsPdf", "evaluateJavascript callback: " + value);
        });
    }

    private String resolveFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int colIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (colIndex >= 0) {
                        result = cursor.getString(colIndex);
                    }
                }
            } catch (Exception ignored) {}
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        if (result == null || result.trim().isEmpty()) {
            result = "외부문서.pdf";
        }
        if (!result.toLowerCase().endsWith(".pdf")) {
            result = result + ".pdf";
        }
        return result;
    }

    private boolean isNetworkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = cm != null ? cm.getActiveNetworkInfo() : null;
            return netInfo != null && netInfo.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    private String getAssetFileName(String path) {
        if (path == null || path.equals("/") || path.endsWith("/djs-pdf/")) return "index.html";
        if (path.endsWith("pdf.min.js")) return "pdf.min.js";
        if (path.endsWith("pdf.worker.min.js")) return "pdf.worker.min.js";
        if (path.endsWith("manifest.json")) return "manifest.json";
        if (path.endsWith("sw.js")) return "sw.js";
        if (path.endsWith("favicon.png")) return "favicon.png";
        if (path.endsWith("apple-touch-icon.png")) return "apple-touch-icon.png";
        if (path.endsWith("icon-192.png")) return "icon-192.png";
        if (path.endsWith("icon-512.png")) return "icon-512.png";
        if (path.endsWith("icon-maskable-192.png")) return "icon-maskable-192.png";
        if (path.endsWith("icon-maskable-512.png")) return "icon-maskable-512.png";
        return null;
    }

    private String getMimeType(String assetName) {
        if (assetName.endsWith(".html")) return "text/html";
        if (assetName.endsWith(".js")) return "application/javascript";
        if (assetName.endsWith(".json")) return "application/json";
        if (assetName.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("if (window.handleBackPressed) { window.handleBackPressed(); } else { 'N'; }", value -> {
                if (!"\"Y\"".equals(value)) {
                    super.onBackPressed();
                }
            });
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_PDF) {
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    processIncomingUri(uri);
                }
            }
        } else if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (uploadMessage == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            uploadMessage.onReceiveValue(results);
            uploadMessage = null;
        }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public boolean isNativeApp() {
            return true;
        }

        @JavascriptInterface
        public void pickFile() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/pdf");
                try {
                    startActivityForResult(intent, REQUEST_PICK_PDF);
                } catch (Exception e) {
                    Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                    fallback.addCategory(Intent.CATEGORY_OPENABLE);
                    fallback.setType("application/pdf");
                    try {
                        startActivityForResult(Intent.createChooser(fallback, "PDF 파일 선택"), REQUEST_PICK_PDF);
                    } catch (Exception ex) {
                        Toast.makeText(MainActivity.this, "파일 선택기를 열 수 없습니다.", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean hasIncomingPdf() {
            return incomingPdfAvailable;
        }

        @JavascriptInterface
        public String getIncomingPdfName() {
            return incomingPdfName != null ? incomingPdfName : "문서.pdf";
        }

        @JavascriptInterface
        public void onPdfLoaded() {
            incomingPdfAvailable = false;
        }

        @JavascriptInterface
        public String getAppVersion() {
            return appVersionName;
        }

        @JavascriptInterface
        public String getIncomingPdfBase64() {
            File tempPdf = new File(getCacheDir(), INCOMING_FILE_NAME);
            if (!tempPdf.exists()) return "";
            try (FileInputStream fis = new FileInputStream(tempPdf);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                int len;
                while ((len = fis.read(buf)) != -1) {
                    bos.write(buf, 0, len);
                }
                return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                Log.e("DjsPdf", "getIncomingPdfBase64 failed", e);
                return "";
            }
        }

        @JavascriptInterface
        public void checkUpdate() {
            runOnUiThread(() -> checkAutoUpdate(true));
        }
    }

    /* ── 자동 업데이트 기능 ── */
    private void checkAutoUpdate(boolean userInitiated) {
        new Thread(() -> {
            try {
                URL url = new URL(VERSION_URL + "?t=" + System.currentTimeMillis());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestMethod("GET");
                int respCode = conn.getResponseCode();

                if (respCode == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();

                    JSONObject json = new JSONObject(sb.toString());
                    int remoteVersionCode = json.optInt("versionCode", 1);
                    String remoteVersionName = json.optString("versionName", "1.0.0");
                    String apkUrl = json.optString("apkUrl", "");
                    String releaseNotes = json.optString("releaseNotes", "");

                    if (remoteVersionCode > appVersionCode) {
                        runOnUiThread(() -> showUpdateDialog(remoteVersionName, apkUrl, releaseNotes));
                    } else if (userInitiated) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "현재 최신 버전(v" + appVersionName + ")입니다.", Toast.LENGTH_SHORT).show());
                    }
                } else if (userInitiated) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "업데이트 정보를 확인할 수 없습니다. (HTTP " + respCode + ")", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                if (userInitiated) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "업데이트 확인 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }
        }).start();
    }

    private void showUpdateDialog(String newVersion, String apkUrl, String notes) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("DJS PDF 최신 업데이트 (v" + newVersion + ")")
                .setMessage((notes.isEmpty() ? "새로운 기능과 성능 개선이 포함된 업데이트가 있습니다." : notes) + "\n\n지금 설치하시겠습니까?")
                .setPositiveButton("지금 업데이트", (dialog, which) -> startApkDownload(apkUrl))
                .setNegativeButton("나중에", null)
                .show();
    }

    private void startApkDownload(String apkUrl) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(this)
                        .setTitle("설치 권한 필요")
                        .setMessage("앱 업데이트를 설치하려면 '이 출처의 앱 허용' 권한이 필요합니다. 설정 화면으로 이동하시겠습니까?")
                        .setPositiveButton("설정으로 이동", (d, w) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("취소", null)
                        .show();
                return;
            }
        }

        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setTitle("DJS PDF 업데이트 다운로드");
        progressDialog.setMessage("최신 설치 파일을 다운로드하고 있습니다…");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setCancelable(false);
        progressDialog.show();

        new Thread(() -> {
            File apkFile = new File(getCacheDir(), "djs-pdf-update.apk");
            try {
                URL url = new URL(apkUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.connect();
                int fileLength = conn.getContentLength();

                try (InputStream input = new BufferedInputStream(conn.getInputStream());
                     OutputStream output = new FileOutputStream(apkFile)) {
                    byte[] data = new byte[8192];
                    long total = 0;
                    int count;
                    while ((count = input.read(data)) != -1) {
                        total += count;
                        if (fileLength > 0) {
                            int progress = (int) (total * 100 / fileLength);
                            runOnUiThread(() -> progressDialog.setProgress(progress));
                        }
                        output.write(data, 0, count);
                    }
                    output.flush();
                }

                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    installApk(apkFile);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(MainActivity.this, "다운로드 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void installApk(File apkFile) {
        try {
            Uri apkUri = GenericFileProvider.getUriForFile(this, apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "설치 실행 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
