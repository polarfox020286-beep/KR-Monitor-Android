package ru.krmonitor.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class SeedImporter {
    private static final int REPAIR_VERSION=2;
    private SeedImporter() {}

    public static void ensureSeeded(Context context) throws Exception {
        SharedPreferences p=context.getSharedPreferences("prefs",Context.MODE_PRIVATE);
        int repaired=p.getInt("catalog_repair_version",0);
        DbHelper db=new DbHelper(context);

        // Versions of the app distributed before repair v2 contained a bad seed
        // with synthetic entries such as 439_1 "RFR и iFR". Rebuild metadata once.
        if(repaired<REPAIR_VERSION) {
            db.clearAll();
            importAsset(context,db);
            File bad=new File(PdfManager.pdfDir(context),"KR439_1.pdf");
            if(bad.exists()) bad.delete();
            File badPart=new File(PdfManager.pdfDir(context),"KR439_1.pdf.part");
            if(badPart.exists()) badPart.delete();
            p.edit().putInt("catalog_repair_version",REPAIR_VERSION).apply();
            return;
        }

        if(db.count()==0) importAsset(context,db);
    }

    private static void importAsset(Context context,DbHelper db) throws Exception {
        String json=readAsset(context,"seed_catalog.json");
        JSONArray a=new JSONArray(json);
        if(a.length()<500) throw new IOException("Встроенный реестр КР неполный");
        for(int i=0;i<a.length();i++) {
            JSONObject o=a.getJSONObject(i);
            String id=o.getString("id").trim();
            String title=o.getString("title").trim();
            if(!id.matches("\\d+_\\d+") || title.length()<3) continue;
            String base=id.split("_",2)[0];
            db.upsert(new Recommendation(base,id,title,"KR"+id+".pdf"),"");
        }
    }

    private static String readAsset(Context c,String name) throws IOException {
        try(InputStream in=c.getAssets().open(name); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[8192];
            int n;
            while((n=in.read(b))>0) out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
