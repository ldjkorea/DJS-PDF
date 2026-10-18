package com.ldj.djspdf;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/** Read-only grants are limited to files explicitly prepared under cache/shared. */
public class GenericFileProvider extends ContentProvider {
    public static final String AUTHORITY="com.ldj.djspdf.fileprovider";
    public static Uri getUriForFile(Context context,File file) throws IOException {
        File root=new File(context.getCacheDir(),"shared").getCanonicalFile();
        File safe=FileSafety.within(root,file);
        Uri.Builder uri=new Uri.Builder().scheme("content").authority(AUTHORITY);
        String relative=safe.getPath().substring(root.getPath().length()+1);
        for(String segment:relative.split(java.util.regex.Pattern.quote(File.separator)))uri.appendPath(segment);
        return uri.build();
    }
    private File resolve(Uri uri) throws IOException {
        if(getContext()==null||!"content".equals(uri.getScheme())||!AUTHORITY.equals(uri.getAuthority()))throw new IOException("Invalid URI");
        File root=new File(getContext().getCacheDir(),"shared"),file=root;
        for(String segment:uri.getPathSegments()){
            if(segment.isEmpty()||segment.equals(".")||segment.equals("..")||segment.contains("/")||segment.contains("\\"))throw new IOException("Invalid path");
            file=new File(file,segment);
        }
        File safe=FileSafety.within(root,file);
        String name=safe.getName().toLowerCase(Locale.ROOT);
        if(!safe.isFile()||(!name.endsWith(".pdf")&&!name.endsWith(".apk")))throw new IOException("File not available");
        return safe;
    }
    @Override public boolean onCreate(){return true;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Read only");
        try{return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
        catch(IOException e){throw new FileNotFoundException("File not available");}
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        MatrixCursor cursor=new MatrixCursor(columns);
        try{
            File file=resolve(uri);Object[] values=new Object[columns.length];
            for(int i=0;i<columns.length;i++){
                if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))values[i]=file.getName();
                if(OpenableColumns.SIZE.equals(columns[i]))values[i]=file.length();
            }cursor.addRow(values);
        }catch(IOException ignored){}return cursor;
    }
    @Override public String getType(Uri uri){try{return resolve(uri).getName().toLowerCase(Locale.ROOT).endsWith(".apk")?"application/vnd.android.package-archive":"application/pdf";}catch(IOException ignored){return "application/octet-stream";}}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read only");}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException("Read only");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException("Read only");}
}
