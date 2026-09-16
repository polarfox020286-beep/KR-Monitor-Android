package ru.krmonitor.app;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;

public class PdfProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/pdf"; }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode)) throw new FileNotFoundException("read only");
        String name=uri.getLastPathSegment();
        if(name==null || name.contains("/") || name.contains("\\") || !name.toLowerCase().endsWith(".pdf")) throw new FileNotFoundException();
        File f=new File(PdfManager.pdfDir(getContext()),name);
        try {
            File allowed=PdfManager.pdfDir(getContext()).getCanonicalFile();
            File requested=f.getCanonicalFile();
            if(!allowed.equals(requested.getParentFile())) throw new FileNotFoundException();
            return ParcelFileDescriptor.open(requested,ParcelFileDescriptor.MODE_READ_ONLY);
        } catch(IOException e) {
            FileNotFoundException ex=new FileNotFoundException("Invalid PDF path");
            ex.initCause(e);
            throw ex;
        }
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String so){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){return 0;}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
}
