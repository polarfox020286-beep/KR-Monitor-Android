package ru.krmonitor.app;

import android.content.*;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;
import java.io.*;
import java.net.*;

public final class PdfManager {
    private PdfManager() {}
    public static File pdfDir(Context c) {
        File root=c.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if(root==null) root=c.getFilesDir();
        File d=new File(root,"KR_Monitor/PDF"); d.mkdirs(); return d;
    }
    public static File archiveDir(Context c) {
        File root=c.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if(root==null) root=c.getFilesDir();
        File d=new File(root,"KR_Monitor/Archive"); d.mkdirs(); return d;
    }
    public static File file(Context c,Recommendation r) { return new File(pdfDir(c),r.filename); }
    public static boolean isPdf(File f) {
        if(!f.isFile()||f.length()<1024) return false;
        try(FileInputStream in=new FileInputStream(f)) { byte[] b=new byte[5]; return in.read(b)==5 && new String(b,"US-ASCII").equals("%PDF-"); }
        catch(Exception e) { return false; }
    }
    public static boolean download(Context c,Recommendation r) {
        File dest=file(c,r); if(isPdf(dest)) return true;
        File tmp=new File(dest.getAbsolutePath()+".part"); if(tmp.exists()) tmp.delete();
        String u="https://apicr.minzdrav.gov.ru/API.ashx?op=GetClinrecPdf&id="+Uri.encode(r.id);
        try {
            HttpURLConnection conn=(HttpURLConnection)new URL(u).openConnection();
            conn.setConnectTimeout(15000); conn.setReadTimeout(180000); conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 15) KR-Monitor/1.0");
            if(conn.getResponseCode()!=200) return false;
            try(InputStream in=conn.getInputStream(); FileOutputStream out=new FileOutputStream(tmp)) {
                byte[] b=new byte[65536]; int n; while((n=in.read(b))>0) out.write(b,0,n);
            } finally { conn.disconnect(); }
            if(!isPdf(tmp)) { tmp.delete(); return false; }
            if(dest.exists()) dest.delete(); return tmp.renameTo(dest);
        } catch(Exception e) { tmp.delete(); return false; }
    }
    public static void open(Context c,Recommendation r) {
        File f=file(c,r); if(!isPdf(f)) { Toast.makeText(c,"PDF ещё не скачан",Toast.LENGTH_SHORT).show(); return; }
        Uri uri=Uri.parse("content://ru.krmonitor.app.pdf/pdf/"+Uri.encode(f.getName()));
        Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); } catch(Exception e) { Toast.makeText(c,"На устройстве нет приложения для просмотра PDF",Toast.LENGTH_LONG).show(); }
    }
}
