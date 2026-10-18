package com.ldj.djspdf;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.KeyEvent;
import android.webkit.WebView;
import java.io.*;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** seed/fixed: dedicated emulator only. Explicit phone-* phases use the preservation-safe phone runner. */
public class QaInstrumentation extends Instrumentation {
    private Bundle arguments;
    private MainActivity activity;
    private WebView web;
    private int checks;
    @Override public void onCreate(Bundle args){super.onCreate(args);arguments=args;start();}
    private void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    private Object field(String name) throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private String eval(String code) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();
        runOnMainSync(()->web.evaluateJavascript(code,result->{value.set(result);done.countDown();}));
        if(!done.await(10,TimeUnit.SECONDS))throw new AssertionError("WebView response timeout");return value.get();
    }
    private void waitFor(String condition) throws Exception {
        long until=System.currentTimeMillis()+20000;
        while(System.currentTimeMillis()<until){if("true".equals(eval(condition)))return;Thread.sleep(150);}
        throw new AssertionError("Condition failed: "+condition+"; toast="+eval("document.querySelector('#toast').textContent"));
    }
    private byte[] fixture(String name) throws Exception {
        try(InputStream in=getContext().getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);return out.toByteArray();
        }
    }
    private String hash(byte[] bytes) throws Exception {
        StringBuilder text=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();
    }
    private void incoming(String fixture,String name) throws Exception {
        File dir=new File(getTargetContext().getCacheDir(),"shared/input-"+java.util.UUID.randomUUID());check(dir.mkdirs(),"test input directory");
        File file=new File(dir,name);try(OutputStream out=new FileOutputStream(file)){out.write(fixture(fixture));}
        Uri uri=GenericFileProvider.getUriForFile(getTargetContext(),file);
        Intent intent=new Intent(Intent.ACTION_VIEW).setClass(getTargetContext(),MainActivity.class).setDataAndType(uri,"application/pdf");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);getTargetContext().startActivity(intent);
    }
    private void seedLegacy() throws Exception {
        String base64=Base64.encodeToString(fixture("normal.pdf"),Base64.NO_WRAP);
        eval("(async()=>{const data=Uint8Array.from(atob('"+base64+"'),c=>c.charCodeAt(0)).buffer;const key='pdf::legacy.pdf::'+data.byteLength;await idbSaveFile(key,data);await idbSaveMeta({key,name:'legacy.pdf',page:4,totalPages:12,rotation:90,filter:'filter-sepia',bookmarks:[{page:3,time:1}],pinned:true,updatedAt:1});window.qaSeed=true;})()");
        waitFor("window.qaSeed===true");check(true,"legacy seeded without replacing original data");
    }
    private void fixed() throws Exception {
        waitFor("document.querySelector('#recentList').textContent.includes('legacy')");
        eval("document.querySelector('.recent-item').click()");
        waitFor("typeof activeDocument!=='undefined' && activeDocument!==null && pageNum===4 && docKey.startsWith('sha256:')");
        check("true".equals(eval("curBookmarks.length===1&&curBookmarks[0].page===3&&curRotation===90&&activeDocument.meta.pinned===true")),"upgrade preserved page, bookmark, rotation, pin");
        eval("(async()=>{window.qaLegacy=!!(await idbGetFile('pdf::legacy.pdf::'+activeDocument.buffer.byteLength));})()");
        waitFor("window.qaLegacy===true");check(true,"legacy recovery bytes retained");
        runOnMainSync(()->{
            check(!web.getSettings().getAllowUniversalAccessFromFileURLs(),"universal file access disabled");
            check(!web.getSettings().getAllowContentAccess(),"content URL access disabled");
        });

        incoming("a.pdf","same.pdf");waitFor("document.querySelector('#textLayer').textContent.includes('DOCUMENT A')");
        check("true".equals(eval("docKey==='sha256:"+hash(fixture("a.pdf"))+"'")),"native incoming bytes match A");
        eval("if(curBookmarks.length===0)document.querySelector('#bookmarkBtn').click()");waitFor("curBookmarks.length===1");
        incoming("b.pdf","same.pdf");waitFor("document.querySelector('#textLayer').textContent.includes('DOCUMENT B')");
        check("true".equals(eval("curBookmarks.length===0&&docKey==='sha256:"+hash(fixture("b.pdf"))+"'")),"same-name native B is distinct and has no A bookmark");
        incoming("invalid.pdf","broken.pdf");waitFor("document.querySelector('#toast').textContent.includes('손상된 PDF')");
        check("true".equals(eval("curName==='same.pdf'&&document.querySelector('#textLayer').textContent.includes('DOCUMENT B')")),"bad native open leaves B active");
        eval("(async()=>{window.qaNoBad=!(await idbGetAllMeta()).some(m=>m.name==='broken.pdf');})()");waitFor("window.qaNoBad===true");check(true,"bad input creates no history row");

        MainActivity.AndroidBridge bridge=activity.new AndroidBridge();
        eval("gotoPage(1)");waitFor("pageNum===1");
        bridge.setVolumeKeyNavEnabled(true);
        runOnMainSync(()->check(activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_DOWN)),"volume event handled"));
        waitFor("pageNum===2");bridge.setVolumeKeyNavEnabled(false);
        runOnMainSync(()->check(!activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_DOWN)),"volume event disabled"));
        check("true".equals(eval("pageNum===2")),"disabled volume keeps page");

        byte[] bytes=fixture("b.pdf");
        check(bridge.beginShare("0".repeat(64),"../same.pdf",bytes.length),"bounded share starts");
        check(bridge.appendShareChunk(Base64.encodeToString(bytes,Base64.NO_WRAP)),"share accepts chunk");
        check(!bridge.finishShare(),"share rejects wrong hash");
        check(bridge.beginShare(hash(bytes),"../same.pdf",bytes.length),"current document share starts");
        check(bridge.appendShareChunk(Base64.encodeToString(bytes,Base64.NO_WRAP)),"current document chunk saved");
        File prepared=(File)field("shareFile");
        check(bridge.finishShare(),"share digest verified");
        check(Arrays.equals(java.nio.file.Files.readAllBytes(prepared.toPath()),bytes),"shared bytes exactly equal visible B");
        check(prepared.getCanonicalPath().startsWith(new File(getTargetContext().getCacheDir(),"shared").getCanonicalPath()+File.separator),"share filename cannot escape directory");
        Uri uri=GenericFileProvider.getUriForFile(getTargetContext(),prepared);
        try(android.os.ParcelFileDescriptor fd=getTargetContext().getContentResolver().openFileDescriptor(uri,"r")){check(fd!=null,"read grant URI resolves");}
        try{getTargetContext().getContentResolver().openFileDescriptor(uri,"rw");throw new AssertionError("write accepted");}catch(FileNotFoundException expected){check(true,"write grant denied");}
        Uri attack=Uri.parse("content://com.ldj.djspdf.fileprovider/%2e%2e%2fprivate.db");
        try{getTargetContext().getContentResolver().openFileDescriptor(attack,"r");throw new AssertionError("traversal accepted");}catch(FileNotFoundException expected){check(true,"encoded traversal denied");}
    }
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            if(arguments.getString("phase", "").startsWith("phone-")) {
                result.putString("stream", new PhysicalPhoneChecks(this).run(arguments.getString("phase")));
                finish(Activity.RESULT_OK,result); return;
            }
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            web=(WebView)field("webView");waitFor("typeof pdfjsLib!=='undefined' && typeof idbGetAllMeta==='function'");
            if("seed".equals(arguments.getString("phase")))seedLegacy();else fixed();
            result.putString("stream","DJS_PDF_QA_PASS phase="+arguments.getString("phase")+" checks="+checks+"\\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("stream","DJS_PDF_QA_FAIL "+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);}
    }
}
