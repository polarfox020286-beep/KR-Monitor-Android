package ru.krmonitor.app;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.*;

public class SopFileProvider extends ContentProvider {
    @Override public boolean onCreate(){ return true; }

    @Override public String getType(Uri uri){
        String n=uri.getLastPathSegment();
        if(n==null)return "application/octet-stream";
        n=n.toLowerCase();
        if(n.endsWith(".pdf"))return "application/pdf";
        if(n.endsWith(".docx"))return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if(n.endsWith(".doc"))return "application/msword";
        if(n.endsWith(".rtf"))return "application/rtf";
        return "application/octet-stream";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("read only");
        String name=uri.getLastPathSegment();
        if(name==null||name.contains("/")||name.contains("\\"))throw new FileNotFoundException("invalid name");
        try{
            File dir=new File(getContext().getFilesDir(),"sop_cache").getCanonicalFile();
            File requested=new File(dir,name).getCanonicalFile();
            if(!dir.equals(requested.getParentFile()))throw new FileNotFoundException("outside cache");
            if(!requested.isFile())throw new FileNotFoundException("not found");
            return ParcelFileDescriptor.open(requested,ParcelFileDescriptor.MODE_READ_ONLY);
        }catch(IOException e){
            FileNotFoundException x=new FileNotFoundException("invalid path");
            x.initCause(e);
            throw x;
        }
    }

    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String so){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){return 0;}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
}
