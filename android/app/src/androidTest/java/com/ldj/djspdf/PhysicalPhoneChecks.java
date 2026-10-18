package com.ldj.djspdf;

import android.app.Instrumentation;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;
import org.json.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in phone checks: snapshot real records read-only; exercise and remove only a unique synthetic PDF. */
final class PhysicalPhoneChecks {
    private final Instrumentation host;
    private MainActivity activity;
    private WebView web;
    private int checks;
    private final String name="DJS-PDF-PHONE-QA-SYNTHETIC.pdf";
    private final File beforeFile;
    PhysicalPhoneChecks(Instrumentation host) {
        this.host=host;
        beforeFile=new File(host.getTargetContext().getFilesDir(),"djs-phone-qa-before.json");
    }
    private void check(boolean ok,String message) {
        if(!ok)throw new AssertionError(message);
        checks++;
    }
    private String eval(String code) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();
        host.runOnMainSync(()->web.evaluateJavascript(code,result->{value.set(result);done.countDown();}));
        if(!done.await(10,TimeUnit.SECONDS))throw new AssertionError("WebView timeout");
        return value.get();
    }
    private void waitFor(String condition) throws Exception {
        long until=System.currentTimeMillis()+30000;
        while(System.currentTimeMillis()<until) {
            if("true".equals(eval(condition)))return;
            Thread.sleep(150);
        }
        throw new AssertionError("Phone condition timeout: "+condition);
    }
    private JSONObject snapshot() throws Exception {
        eval("window.phoneSnapshot=null;window.phoneSnapshotError=null;(async()=>{try{"+
             "const dbs=await indexedDB.databases();if(!dbs.some(d=>d.name==='DjsPdfDB_v2'))throw Error('Existing database absent');"+
             "const db=await new Promise((r,j)=>{const q=indexedDB.open('DjsPdfDB_v2');q.onsuccess=()=>r(q.result);q.onerror=()=>j(q.error);});"+
             "const tx=db.transaction(['files','meta'],'readonly');"+
             "const read=s=>new Promise((r,j)=>{const q=tx.objectStore(s).getAll();q.onsuccess=()=>r(q.result);q.onerror=()=>j(q.error);});"+
             "const [files,meta]=await Promise.all([read('files'),read('meta')]);db.close();"+
             "const hex=b=>Array.from(new Uint8Array(b),x=>x.toString(16).padStart(2,'0')).join('');"+
             "const hashed=await Promise.all(files.map(async f=>({key:f.key,size:f.data.byteLength,sha256:hex(await crypto.subtle.digest('SHA-256',f.data))})));"+
             "const settings={};for(let i=0;i<localStorage.length;i++){const k=localStorage.key(i);settings[k]=localStorage.getItem(k);}"+
             "window.phoneSnapshot=JSON.stringify({files:hashed,meta,settings});"+
             "}catch(e){window.phoneSnapshotError=e.message;}})();");
        waitFor("window.phoneSnapshot!==null||window.phoneSnapshotError!==null");
        check("null".equals(eval("window.phoneSnapshotError")),"read-only database snapshot");
        return new JSONObject((String)new JSONTokener(eval("window.phoneSnapshot")).nextValue());
    }
    private String canonical(Object value) throws Exception {
        if(value instanceof JSONObject) {
            JSONObject obj=(JSONObject)value;List<String> keys=new ArrayList<>();obj.keys().forEachRemaining(keys::add);Collections.sort(keys);
            StringBuilder out=new StringBuilder("{");
            for(String key:keys)out.append(JSONObject.quote(key)).append(':').append(canonical(obj.get(key))).append(',');
            return out.append('}').toString();
        }
        if(value instanceof JSONArray) {
            StringBuilder out=new StringBuilder("[");JSONArray arr=(JSONArray)value;
            for(int i=0;i<arr.length();i++)out.append(canonical(arr.get(i))).append(',');
            return out.append(']').toString();
        }
        return String.valueOf(value);
    }
    private String hash(byte[] data) throws Exception {
        StringBuilder out=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(data))out.append(String.format(Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
    private byte[] fixture() throws Exception {
        try(InputStream in=host.getContext().getAssets().open("phone.pdf");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            in.transferTo(out);return out.toByteArray();
        }
    }
    private void key(int code) throws Exception {
        host.sendKeyDownUpSync(code);host.waitForIdleSync();Thread.sleep(300);
    }
    private boolean clickLocalReceiver(AccessibilityNodeInfo node) {
        if(node==null)return false;
        CharSequence text=node.getText();
        CharSequence description=node.getContentDescription();
        if((text!=null&&text.toString().contains("DJS PDF QA"))||(description!=null&&description.toString().contains("DJS PDF QA"))) {
            for(AccessibilityNodeInfo target=node;target!=null;target=target.getParent())
                if(target.isClickable())return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        for(int i=0;i<node.getChildCount();i++)if(clickLocalReceiver(node.getChild(i)))return true;
        return false;
    }
    private boolean scrollShareTargets(AccessibilityNodeInfo node) {
        if(node==null)return false;
        for(int i=node.getChildCount()-1;i>=0;i--)if(scrollShareTargets(node.getChild(i)))return true;
        CharSequence cls=node.getClassName();
        return node.isScrollable()&&cls!=null&&(cls.toString().contains("RecyclerView")||cls.toString().contains("ScrollView"))&&
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }
    String run(String phase) throws Exception {
        android.accessibilityservice.AccessibilityServiceInfo info=host.getUiAutomation().getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        host.getUiAutomation().setServiceInfo(info);
        for(int i=0;i<4;i++) {
            AccessibilityNodeInfo root=host.getUiAutomation().getRootInActiveWindow();
            if(root==null){Thread.sleep(400);continue;}
            if(root!=null&&"com.android.intentresolver".contentEquals(root.getPackageName())) {
                host.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                Thread.sleep(400);
            } else break;
        }
        activity=(MainActivity)host.startActivitySync(new Intent(host.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Field field=MainActivity.class.getDeclaredField("webView");field.setAccessible(true);web=(WebView)field.get(activity);
        waitFor("typeof pdfjsLib!=='undefined'&&typeof idbGetAllMeta==='function'");
        JSONObject current=snapshot();
        if("phone-before".equals(phase)) {
            check(!beforeFile.exists(),"no earlier phone snapshot overwritten");
            Files.write(beforeFile.toPath(),current.toString().getBytes(StandardCharsets.UTF_8));
            return stats("PHONE_BEFORE_PASS",current);
        }
        check(beforeFile.isFile(),"pre-update manifest retained across update");
        JSONObject before=new JSONObject(new String(Files.readAllBytes(beforeFile.toPath()),StandardCharsets.UTF_8));
        check(canonical(before).equals(canonical(current)),"all original bytes, metadata, bookmarks and settings unchanged after update");
        if("phone-after".equals(phase))return stats("PHONE_AFTER_PASS",current);
        if("phone-finish".equals(phase)) {
            String expected=hash(fixture());
            File[] directories=new File(activity.getCacheDir(),"shared").listFiles();
            if(directories!=null)for(File dir:directories) {
                File candidate=new File(dir,name);
                if(candidate.isFile()&&hash(Files.readAllBytes(candidate.toPath())).equals(expected)) {
                    check(candidate.delete(),"remove exact synthetic share cache");
                    String[] remaining=dir.list();if(remaining!=null&&remaining.length==0)check(dir.delete(),"remove empty synthetic cache directory");
                }
            }
            check(beforeFile.delete(),"remove only this test's temporary manifest");
            return stats("PHONE_FINAL_PASS",current);
        }
        check("phone-test".equals(phase),"explicit physical test phase required");
        final byte[] bytes=fixture();final String testHash=hash(bytes),testKey="sha256:"+testHash;
        for(int i=0;i<before.getJSONArray("files").length();i++)
            check(!before.getJSONArray("files").getJSONObject(i).getString("key").equals(testKey),"unique synthetic document cannot collide with original");
        String priorVol=eval("volNavEnabled");
        File input=new File(activity.getCacheDir(),"shared/phone-qa-"+UUID.randomUUID()+"/"+name);
        check(input.getParentFile().mkdirs(),"create isolated synthetic input");Files.write(input.toPath(),bytes);
        Uri uri=GenericFileProvider.getUriForFile(activity,input);
        Intent open=new Intent(Intent.ACTION_VIEW).setClass(activity,MainActivity.class).setDataAndType(uri,"application/pdf");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(open);
        try {
            waitFor("typeof activeDocument!=='undefined'&&activeDocument&&docKey==='"+testKey+"'&&totalPages===12&&document.querySelector('#textLayer').textContent.includes('DEVICE QA ONLY')");
            check("true".equals(eval("document.querySelector('#pdfCanvas').width>0")),"physical PDF canvas rendered");
            eval("gotoPage(3)");waitFor("pageNum===3");
            eval("document.querySelector('#bookmarkBtn').click()");waitFor("curBookmarks.some(b=>b.page===3)");
            try(android.os.ParcelFileDescriptor capture=host.getUiAutomation().executeShellCommand("screencap -p /data/local/tmp/djs-pdf-phone-qa.png");
                InputStream output=new android.os.ParcelFileDescriptor.AutoCloseInputStream(capture)) { while(output.read()!=-1){} }
            eval("goHome();renderRecent('"+name+"')");waitFor("Array.from(document.querySelectorAll('.recent-item')).some(e=>e.textContent.includes('DJS-PDF-PHONE-QA-SYNTHETIC'))");
            eval("Array.from(document.querySelectorAll('.recent-item')).find(e=>e.textContent.includes('DJS-PDF-PHONE-QA-SYNTHETIC')).click()");
            waitFor("pageNum===3&&curBookmarks.some(b=>b.page===3)&&docKey==='"+testKey+"'");check(true,"synthetic bookmark and page restored after reopening");
            eval("if(!volNavEnabled)document.querySelector('#volNavToggle').click()");
            AtomicReference<Boolean> focused=new AtomicReference<>(false);
            host.runOnMainSync(()->focused.set(activity.getWindow().getDecorView().hasWindowFocus()));
            check(focused.get(),"PDF activity owns physical phone input focus");
            key(KeyEvent.KEYCODE_VOLUME_DOWN);waitFor("pageNum===4");check(true,"Android volume-down key dispatch advances physical phone page");
            key(KeyEvent.KEYCODE_VOLUME_UP);waitFor("pageNum===3");check(true,"Android volume-up key dispatch goes to previous page");
            eval("gotoPage(1)");waitFor("pageNum===1");key(KeyEvent.KEYCODE_VOLUME_UP);
            check("true".equals(eval("pageNum===1")),"volume key bounded at first page");
            eval("gotoPage(12)");waitFor("pageNum===12");key(KeyEvent.KEYCODE_VOLUME_DOWN);
            check("true".equals(eval("pageNum===12")),"volume key bounded at last page");
            eval("document.querySelector('#volNavToggle').click();gotoPage(3)");waitFor("pageNum===3&&!volNavEnabled");
            // Dispatch through the activity to avoid changing the user's device volume during this OFF assertion.
            host.runOnMainSync(()->check(!activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_DOWN)),"OFF delegates volume key to Android"));
            check("true".equals(eval("pageNum===3")),"OFF leaves page unchanged");
            eval("if(volNavEnabled!=="+priorVol+")document.querySelector('#volNavToggle').click()");

            AtomicReference<Intent> received=new AtomicReference<>();
            BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){received.set(intent);}};
            activity.registerReceiver(receiver,new IntentFilter("com.ldj.djspdf.PHONE_QA_RESULT"),Context.RECEIVER_EXPORTED);
            try {
                eval("document.querySelector('#shareBtn').click()");
                long until=System.currentTimeMillis()+15000;boolean clicked=false;
                while(System.currentTimeMillis()<until&&!clicked){
                    AccessibilityNodeInfo root=host.getUiAutomation().getRootInActiveWindow();
                    clicked=clickLocalReceiver(root);
                    if(!clicked){Thread.sleep(700);scrollShareTargets(root);}
                }
                if(!clicked) {
                    // Some Samsung share lists hide an installed test-only recipient. Keep the real
                    // chooser assertion, then deliver the exact prepared URI explicitly to that local UID.
                    AccessibilityNodeInfo root=host.getUiAutomation().getRootInActiveWindow();
                    check(root!=null&&"com.android.intentresolver".contentEquals(root.getPackageName()),"Samsung system share sheet opened by actual share button");
                    host.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                    File[] directories=new File(activity.getCacheDir(),"shared").listFiles();File ready=null;
                    if(directories!=null)for(File dir:directories){File candidate=new File(dir,name);if(candidate.isFile()&&hash(Files.readAllBytes(candidate.toPath())).equals(testHash))ready=candidate;}
                    check(ready!=null,"actual share button prepared exact synthetic file");
                    Uri shared=GenericFileProvider.getUriForFile(activity,ready);
                    Intent local=new Intent(Intent.ACTION_SEND).setClassName("com.ldj.djspdf.test","com.ldj.djspdf.QaShareReceiver").setType("application/pdf");
                    local.putExtra(Intent.EXTRA_STREAM,shared);
                    local.setClipData(ClipData.newRawUri("PDF",shared));
                    local.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(local);
                    Bundle status=new Bundle();status.putString("stream","PHONE_SHARE_LOCAL_EXPLICIT (Samsung chooser hid test recipient)\n");host.sendStatus(0,status);
                } else {check(true,"Samsung system share sheet selected only local synthetic QA receiver");}
                until=System.currentTimeMillis()+10000;
                while(received.get()==null&&System.currentTimeMillis()<until)Thread.sleep(100);
                check(received.get()!=null,"separate-UID recipient delivered result");
                check(received.get().getBooleanExtra("matched",false)&&testHash.equals(received.get().getStringExtra("hash")),"external app UID reads exact displayed PDF via granted content URI");
                check(received.get().getBooleanExtra("writeDenied",false),"external recipient has read-only grant");
            } finally { activity.unregisterReceiver(receiver); }
        } finally {
            // Remove only this run's exact synthetic key. Restore original localStorage, including absent keys.
            eval("(async()=>{try{if(typeof activeDocument!=='undefined'&&activeDocument?.key==='"+testKey+"'){goHome();await saveCurrentMeta();activeDocument=null;}await idbDelete('"+testKey+"');"+
                 "const saved="+before.getJSONObject("settings").toString()+";for(let i=localStorage.length-1;i>=0;i--){const k=localStorage.key(i);if(!(k in saved))localStorage.removeItem(k);}for(const [k,v]of Object.entries(saved))localStorage.setItem(k,v);"+
                 "await renderRecent();window.phoneClean=true;}catch(e){window.phoneCleanError=e.message;}})()");
            waitFor("window.phoneClean===true");
            Files.deleteIfExists(input.toPath());Files.deleteIfExists(input.getParentFile().toPath());
        }
        JSONObject after=snapshot();
        check(canonical(before).equals(canonical(after)),"after phone tests every original PDF hash, bookmark, metadata and setting remains identical");
        return stats("PHONE_TEST_PASS",after);
    }
    private String stats(String marker,JSONObject snapshot) throws Exception {
        int bookmarks=0;JSONArray meta=snapshot.getJSONArray("meta");
        for(int i=0;i<meta.length();i++){JSONArray list=meta.getJSONObject(i).optJSONArray("bookmarks");if(list!=null)bookmarks+=list.length();}
        return marker+" checks="+checks+" originalFiles="+snapshot.getJSONArray("files").length()+" originalMeta="+meta.length()+" originalBookmarks="+bookmarks+" manifestSha256="+hash(canonical(snapshot).getBytes(StandardCharsets.UTF_8))+"\n";
    }
}
