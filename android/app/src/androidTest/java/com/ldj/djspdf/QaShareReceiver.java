package com.ldj.djspdf;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** A local, separate-UID recipient. Checks the synthetic hash without storing or externally sending bytes. */
public class QaShareReceiver extends Activity {
    private static String digest(InputStream in) throws Exception {
        try (InputStream input=in) {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            byte[] buf=new byte[8192];int n;
            while((n=input.read(buf))!=-1)md.update(buf,0,n);
            StringBuilder out=new StringBuilder();
            for(byte b:md.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));
            return out.toString();
        }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent result=new Intent("com.ldj.djspdf.PHONE_QA_RESULT").setPackage("com.ldj.djspdf");
        try {
            Uri uri=getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            String expected=digest(getAssets().open("phone.pdf"));
            String actual=digest(getContentResolver().openInputStream(uri));
            result.putExtra("hash",actual).putExtra("matched",expected.equals(actual));
            try {
                getContentResolver().openFileDescriptor(uri,"rw").close();
                result.putExtra("writeDenied",false);
            } catch(Exception expectedDenied) { result.putExtra("writeDenied",true); }
        } catch(Exception error) { result.putExtra("error",error.getClass().getSimpleName()); }
        sendBroadcast(result);
        finish();
    }
}
