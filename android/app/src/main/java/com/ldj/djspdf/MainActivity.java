package com.ldj.djspdf;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.security.MessageDigest;
import java.io.IOException;
import javax.net.ssl.HttpsURLConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;


public class MainActivity extends Activity {
    private static final String APP_URL = "file:///android_asset/index.html";
    private static final String VERSION_URL = "https://ldjkorea.github.io/DJS-PDF/version.json";
    private static final int REQUEST_PICK_PDF = 2001;
    private static final int FILE_CHOOSER_REQUEST_CODE = 2002;

    private WebView webView;
    private ValueCallback<Uri[]> uploadMessage;
    private boolean pageLoaded = false;
    private static final class IncomingDocument {
        final String id,name;
        IncomingDocument(String id,String name){this.id=id;this.name=name;}
    }
    private volatile IncomingDocument incomingDocument;

    private final Map<String, File> incomingFiles = new ConcurrentHashMap<>();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong incomingSequence = new AtomicLong();
    private UpdateInfo pendingUpdate;
    private boolean downloadRunning = false;
    private FileOutputStream shareOutput;
    private File shareFile;
    private MessageDigest shareDigest;
    private String shareExpectedHash;
    private long shareExpectedSize, shareWritten;
    private static final class UpdateInfo {
        final String name, url, hash, notes; final long code;
        UpdateInfo(String name, String url, String hash, String notes, long code) {
            this.name=name; this.url=url; this.hash=hash; this.notes=notes; this.code=code;
        }
    }
    private String appVersionName = "1.0.0";
    private long appVersionCode = 1;
    private volatile boolean isViewerActive = false;
    private volatile boolean volumeKeyNavEnabled = true;

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
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) Log.d("DjsPdfJS", cm.messageLevel().toString());
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
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme()) && "/android_asset/incoming.pdf".equals(uri.getPath())) {
                    String id=uri.getQueryParameter("id"); File file=id==null?null:incomingFiles.get(id);
                    if (file != null) try {
                        Map<String,String> headers=new HashMap<>();
                        headers.put("Content-Length",String.valueOf(file.length()));headers.put("Cache-Control","no-store");
                        return new WebResourceResponse("application/pdf",null,200,"OK",headers,new FileInputStream(file));
                    } catch (IOException ignored) { }
                    return blockedResource();
                }
                if ("file".equals(uri.getScheme()) && uri.getQuery()==null && uri.getPath()!=null && uri.getPath().startsWith("/android_asset/")) {
                    String asset=uri.getPath().substring("/android_asset/".length());
                    if (getAssetFileName(asset)!=null) return super.shouldInterceptRequest(view,request);
                }
                // Remote or arbitrary private files never inherit this WebView's bridge.
                return blockedResource();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !APP_URL.equals(request.getUrl().toString());
            }



            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageLoaded = true;
                IncomingDocument incoming=incomingDocument;
                if(incoming!=null&&incomingFiles.containsKey(incoming.id))notifyPdfToWeb(incoming.name,incoming.id);
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

    @Override
    protected void onPause() {
        super.onPause();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isViewerActive) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (pendingUpdate != null && getPackageManager().canRequestPackageInstalls()) {
            UpdateInfo update=pendingUpdate; pendingUpdate=null; startApkDownload(update);
        }
    }

    @Override protected void onDestroy() {
        incomingSequence.incrementAndGet(); fileExecutor.shutdownNow();
        if (webView != null) { webView.removeJavascriptInterface("AndroidBridge"); webView.destroy(); webView=null; }
        super.onDestroy();
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
        final long sequence=incomingSequence.incrementAndGet();
        fileExecutor.execute(() -> {
            File copy=null;
            try {
                String scheme=uri.getScheme();
                if (!"content".equals(scheme) && !"file".equals(scheme)) throw new IOException("지원하지 않는 문서 주소");
                String id=UUID.randomUUID().toString();
                File dir=new File(getCacheDir(),"incoming"); if(!dir.exists()&&!dir.mkdirs())throw new IOException("임시 공간 없음");
                copy=new File(dir,id+".pdf");
                try (InputStream input="file".equals(scheme)?openExternalFile(uri):getContentResolver().openInputStream(uri);
                     OutputStream output=new FileOutputStream(copy)) {
                    if(input==null)throw new IOException("문서를 읽을 수 없습니다");
                    byte[] buffer=new byte[16384];int count;long total=0;
                    while((count=input.read(buffer))!=-1){
                        total+=count;if(total>FileSafety.MAX_BYTES)throw new IOException("256MB 이하 PDF를 열어 주세요");
                        output.write(buffer,0,count);
                    }
                }
                if(sequence!=incomingSequence.get()){copy.delete();return;}
                final String name=FileSafety.pdfName(resolveFileName(uri));
                incomingFiles.put(id,copy);incomingDocument=new IncomingDocument(id,name);
                if(pageLoaded)runOnUiThread(()->notifyPdfToWeb(name,id));
            }catch(Exception e){if(copy!=null)copy.delete();runOnUiThread(()->Toast.makeText(this,"PDF 파일 읽기 실패: "+e.getMessage(),Toast.LENGTH_LONG).show());}
        });
    }

    private InputStream openExternalFile(Uri uri) throws IOException {
        File file=new File(uri.getPath()).getCanonicalFile();
        String privateRoot=new File(getApplicationInfo().dataDir).getCanonicalPath()+File.separator;
        if(file.getPath().startsWith(privateRoot))throw new IOException("앱 내부 파일은 열 수 없습니다");
        return new FileInputStream(file);
    }

    private void notifyPdfToWeb(String fileName,String id) {
        if(webView==null||id==null)return;
        webView.evaluateJavascript("window.openIncomingFromNative && window.openIncomingFromNative("
                +JSONObject.quote(fileName)+","+JSONObject.quote(id)+");",null);
    }

    private WebResourceResponse blockedResource() {
        return new WebResourceResponse("text/plain","UTF-8",403,"Forbidden",new HashMap<>(),new java.io.ByteArrayInputStream(new byte[0]));
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
        if (!result.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            result = result + ".pdf";
        }
        return result;
    }

    private String getAssetFileName(String path) {
        return Arrays.asList("index.html","app.js","reader-core.js","reader-store.js","pdf.min.js","pdf.worker.min.js","manifest.json","sw.js","version.json","favicon.png","apple-touch-icon.png","icon-192.png","icon-512.png","icon-maskable-192.png","icon-maskable-512.png").contains(path)?path:null;
    }



    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isViewerActive && volumeKeyNavEnabled) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (webView != null) {
                    webView.evaluateJavascript("if (window.gotoPage && typeof window.pageNum === 'number') { window.gotoPage(window.pageNum - 1); }", null);
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (webView != null) {
                    webView.evaluateJavascript("if (window.gotoPage && typeof window.pageNum === 'number') { window.gotoPage(window.pageNum + 1); }", null);
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isViewerActive && volumeKeyNavEnabled) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
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
            IncomingDocument incoming=incomingDocument;return incoming!=null&&incomingFiles.containsKey(incoming.id);
        }

        @JavascriptInterface
        public String getIncomingPdfName() {
            IncomingDocument incoming=incomingDocument;return incoming!=null?incoming.name:"문서.pdf";
        }

        @JavascriptInterface public String getIncomingPdfId() { IncomingDocument incoming=incomingDocument;return incoming!=null?incoming.id:""; }
        @JavascriptInterface public String getIncomingPdfInfo() {
            IncomingDocument incoming=incomingDocument;
            if(incoming==null||!incomingFiles.containsKey(incoming.id))return "null";
            return "{\"id\":"+JSONObject.quote(incoming.id)+",\"name\":"+JSONObject.quote(incoming.name)+"}";
        }
        @JavascriptInterface public void onPdfLoaded(String id) {
            File file=incomingFiles.remove(id);if(file!=null)file.delete();
        }
        @JavascriptInterface public String getAppVersion() { return appVersionName; }

        @JavascriptInterface
        public void checkUpdate() {
            runOnUiThread(() -> checkAutoUpdate(true));
        }

        @JavascriptInterface
        public void setKeepScreenOn(boolean keepOn) {
            runOnUiThread(() -> {
                if (keepOn) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        @JavascriptInterface
        public void setViewerActive(boolean active) {
            isViewerActive = active;
        }

        @JavascriptInterface
        public void setVolumeKeyNavEnabled(boolean enabled) {
            volumeKeyNavEnabled = enabled;
        }

        @JavascriptInterface public synchronized boolean beginShare(String hash,String name,long size) {
            cancelShare();
            try {
                if(hash==null||!hash.matches("[0-9a-f]{64}")||size<=0||size>FileSafety.MAX_BYTES)return false;
                File dir=new File(getCacheDir(),"shared/"+UUID.randomUUID());
                if(!dir.mkdirs())return false;
                shareFile=FileSafety.within(dir,new File(dir,FileSafety.pdfName(name)));
                shareOutput=new FileOutputStream(shareFile);shareDigest=MessageDigest.getInstance("SHA-256");
                shareExpectedHash=hash;shareExpectedSize=size;shareWritten=0;return true;
            }catch(Exception e){cancelShare();return false;}
        }
        @JavascriptInterface public synchronized boolean appendShareChunk(String chunk) {
            try {
                if(shareOutput==null||chunk==null||chunk.length()>65536)throw new IOException("잘못된 공유 데이터");
                byte[] bytes=Base64.decode(chunk,Base64.NO_WRAP);shareWritten+=bytes.length;
                if(shareWritten>shareExpectedSize)throw new IOException("파일 크기 불일치");
                shareOutput.write(bytes);shareDigest.update(bytes);return true;
            }catch(Exception e){cancelShare();return false;}
        }
        @JavascriptInterface public synchronized boolean finishShare() {
            try {
                if(shareOutput==null) return false;
                shareOutput.close();shareOutput=null;
                if(shareWritten!=shareExpectedSize||!hex(shareDigest.digest()).equals(shareExpectedHash))throw new IOException("파일 해시 불일치");
                final File ready=shareFile;shareFile=null;shareDigest=null;
                runOnUiThread(()->{
                    try {
                        Uri uri=GenericFileProvider.getUriForFile(MainActivity.this,ready);
                        Intent intent=new Intent(Intent.ACTION_SEND).setType("application/pdf");
                        intent.putExtra(Intent.EXTRA_STREAM,uri);intent.putExtra(Intent.EXTRA_SUBJECT,ready.getName());
                        intent.setClipData(android.content.ClipData.newRawUri("PDF",uri));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(intent,"PDF 문서 공유"));
                    }catch(Exception e){Toast.makeText(MainActivity.this,"문서 공유 실패",Toast.LENGTH_LONG).show();}
                });return true;
            }catch(Exception e){cancelShare();return false;}
        }
        @JavascriptInterface public synchronized void cancelShare() {
            try{if(shareOutput!=null)shareOutput.close();}catch(IOException ignored){}
            if(shareFile!=null)shareFile.delete();shareOutput=null;shareFile=null;shareDigest=null;
        }
    }

    private static String hex(byte[] data) {
        StringBuilder text=new StringBuilder();for(byte b:data)text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();
    }

    /* Updates require an approved repository URL, digest, package, version and installed signer. */
    private void checkAutoUpdate(boolean userInitiated) {
        new Thread(()->{
            HttpsURLConnection connection=null;
            try {
                connection=openHttps(VERSION_URL+"?t="+System.currentTimeMillis(),false);
                StringBuilder jsonText=new StringBuilder();
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))){
                    String line;while((line=reader.readLine())!=null){jsonText.append(line);if(jsonText.length()>65536)throw new IOException("업데이트 정보 크기 초과");}
                }
                JSONObject json=new JSONObject(jsonText.toString());long code=json.optLong("versionCode",0);
                if(code<=appVersionCode){if(userInitiated)notice("현재 최신 공개 버전입니다. (앱 v"+appVersionName+")");return;}
                String url=json.optString("apkUrl"),hash=json.optString("sha256").toLowerCase(Locale.ROOT);
                if(!FileSafety.apkUrl(url)||!hash.matches("[0-9a-f]{64}"))throw new IOException("업데이트 무결성 정보가 올바르지 않습니다");
                UpdateInfo update=new UpdateInfo(json.getString("versionName"),url,hash,json.optString("releaseNotes"),code);
                runOnUiThread(()->showUpdateDialog(update));
            }catch(Exception e){if(userInitiated)notice("업데이트 확인 실패: "+e.getMessage());}
            finally{if(connection!=null)connection.disconnect();}
        },"pdf-update-check").start();
    }
    private void notice(String message) {
        runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())Toast.makeText(this,message,Toast.LENGTH_LONG).show();});
    }
    private HttpsURLConnection openHttps(String address,boolean asset) throws IOException {
        URL url=new URL(address);
        for(int redirects=0;redirects<6;redirects++){
            String host=url.getHost();
            boolean allowed=asset?Arrays.asList("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com").contains(host):"ldjkorea.github.io".equals(host);
            if(!"https".equals(url.getProtocol())||!allowed||url.getUserInfo()!=null||(url.getPort()!=-1&&url.getPort()!=443))throw new IOException("허용되지 않은 다운로드 주소");
            HttpsURLConnection conn=(HttpsURLConnection)url.openConnection();conn.setConnectTimeout(10000);conn.setReadTimeout(15000);conn.setInstanceFollowRedirects(false);
            int status=conn.getResponseCode();
            if(status>=300&&status<400){String location=conn.getHeaderField("Location");conn.disconnect();if(location==null)throw new IOException("잘못된 리디렉션");url=new URL(url,location);continue;}
            if(status!=200){conn.disconnect();throw new IOException("HTTP "+status);}return conn;
        }
        throw new IOException("리디렉션 횟수 초과");
    }
    private void showUpdateDialog(UpdateInfo update) {
        if(isFinishing()||isDestroyed())return;
        new AlertDialog.Builder(this).setTitle("DJS PDF 업데이트 (v"+update.name+")")
            .setMessage(update.notes+"\n\n지금 설치하시겠습니까?")
            .setPositiveButton("지금 업데이트",(dialog,which)->startApkDownload(update)).setNegativeButton("나중에",null).show();
    }
    private void startApkDownload(UpdateInfo update) {
        if(downloadRunning||isFinishing()||isDestroyed())return;
        if(!getPackageManager().canRequestPackageInstalls()){
            new AlertDialog.Builder(this).setTitle("설치 권한 필요").setMessage("앱 업데이트를 설치하려면 이 출처의 앱 설치를 허용해 주세요.")
                .setPositiveButton("설정으로 이동",(dialog,which)->{
                    pendingUpdate=update;startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));
                }).setNegativeButton("취소",null).show();return;
        }
        downloadRunning=true;
        ProgressDialog progress=new ProgressDialog(this);progress.setTitle("DJS PDF 업데이트");progress.setMessage("설치 파일 다운로드 및 무결성 확인 중…");progress.setIndeterminate(true);progress.setCancelable(false);progress.show();
        new Thread(()->{
            File apk=null;HttpsURLConnection conn=null;
            try {
                if(!FileSafety.apkUrl(update.url))throw new IOException("허용되지 않은 업데이트 주소");
                File dir=new File(getCacheDir(),"shared/"+UUID.randomUUID());if(!dir.mkdirs())throw new IOException("저장 공간 없음");
                apk=new File(dir,"djs-pdf-update.apk");conn=openHttps(update.url,true);long length=conn.getContentLengthLong();
                if(length>FileSafety.MAX_BYTES)throw new IOException("설치 파일 크기 초과");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
                try(InputStream in=conn.getInputStream();OutputStream out=new FileOutputStream(apk)){
                    byte[] bytes=new byte[16384];int count;
                    while((count=in.read(bytes))!=-1){total+=count;if(total>FileSafety.MAX_BYTES)throw new IOException("설치 파일 크기 초과");digest.update(bytes,0,count);out.write(bytes,0,count);}
                }
                if(total==0||(length>=0&&length!=total)||!hex(digest.digest()).equals(update.hash))throw new IOException("설치 파일 해시 또는 크기 불일치");
                verifyApk(apk,update.code);final File ready=apk;
                runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()){progress.dismiss();installApk(ready);}downloadRunning=false;});
            }catch(Exception e){if(apk!=null)apk.delete();runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()){progress.dismiss();Toast.makeText(this,"업데이트 중단: "+e.getMessage(),Toast.LENGTH_LONG).show();}downloadRunning=false;});}
            finally{if(conn!=null)conn.disconnect();}
        },"pdf-update-download").start();
    }
    private Set<String> signers(PackageInfo info) throws Exception {
        Signature[] signatures=Build.VERSION.SDK_INT>=28&&info.signingInfo!=null?info.signingInfo.getApkContentsSigners():info.signatures;
        if(signatures==null||signatures.length==0)throw new IOException("APK 서명 없음");
        Set<String> result=new HashSet<>();for(Signature sig:signatures)result.add(hex(MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())));return result;
    }
    private void verifyApk(File file,long expectedCode) throws Exception {
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo archive=getPackageManager().getPackageArchiveInfo(file.getPath(),flags);
        if(archive==null||!getPackageName().equals(archive.packageName))throw new IOException("업데이트 패키지 불일치");
        long code=Build.VERSION.SDK_INT>=28?archive.getLongVersionCode():archive.versionCode;
        if(code!=expectedCode||code<=appVersionCode)throw new IOException("업데이트 버전 불일치");
        if(archive.applicationInfo==null||(archive.applicationInfo.flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0)throw new IOException("디버그 앱은 업데이트로 설치할 수 없습니다");
        if(!signers(archive).equals(signers(getPackageManager().getPackageInfo(getPackageName(),flags))))throw new IOException("기존 앱과 APK 서명이 다릅니다");
    }
    private void installApk(File apk) {
        try{
            Uri uri=GenericFileProvider.getUriForFile(this,apk);
            Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive");
            intent.setClipData(android.content.ClipData.newRawUri("APK",uri));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(intent);
        }catch(Exception e){notice("설치 화면을 열지 못했습니다");}
    }
}
