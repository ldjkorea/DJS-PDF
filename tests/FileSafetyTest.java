package com.ldj.djspdf;
import java.io.File;

public final class FileSafetyTest {
    static int checks=0;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("Check "+checks);}
    public static void main(String[] args) throws Exception {
        File root=new File(args[0],"shared");
        check(FileSafety.within(root,new File(root,"folder/document.pdf")).getName().equals("document.pdf"));
        try{FileSafety.within(root,new File(root,"../private.db"));throw new AssertionError("Traversal accepted");}catch(java.io.IOException expected){checks++;}
        try{FileSafety.within(root,root);throw new AssertionError("Root accepted");}catch(java.io.IOException expected){checks++;}
        check(FileSafety.pdfName("../../outside.pdf").indexOf('/')<0);
        check(FileSafety.pdfName("name\\sub.pdf").indexOf('\\')<0);
        check(FileSafety.pdfName("\u0000test.PDF").equals("_test.PDF"));
        check(FileSafety.pdfName(null).endsWith(".pdf"));
        check(FileSafety.apkUrl("https://github.com/ldjkorea/DJS-PDF/releases/download/v1.2.1/DJS-PDF-v1.2.1.apk"));
        for(String invalid:new String[]{
            "http://github.com/ldjkorea/DJS-PDF/releases/download/v1.2.1/app.apk",
            "https://github.com.evil.example/ldjkorea/DJS-PDF/releases/download/v1.2.1/app.apk",
            "https://github.com/other/repo/releases/download/v1.2.1/app.apk",
            "https://user@github.com/ldjkorea/DJS-PDF/releases/download/v1.2.1/app.apk",
            "https://github.com/ldjkorea/DJS-PDF/releases/download/v1.2.1/%2e%2e.apk",
            "https://github.com/ldjkorea/DJS-PDF/releases/download/v1.2.1/app.apk?redirect=1"
        })check(!FileSafety.apkUrl(invalid));
        System.out.println("FileSafety checks passed: "+checks);
    }
}
