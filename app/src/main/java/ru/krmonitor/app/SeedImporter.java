package ru.krmonitor.app;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class SeedImporter {
    private SeedImporter() {}
    public static void ensureSeeded(Context context) throws Exception {
        DbHelper db=new DbHelper(context);
        if(db.count()>0) return;
        String json=readAsset(context,"seed_catalog.json");
        JSONArray a=new JSONArray(json);
        for(int i=0;i<a.length();i++) {
            JSONObject o=a.getJSONObject(i);
            String id=o.getString("id");
            String title=o.getString("title");
            String base=id.split("_",2)[0];
            db.upsert(new Recommendation(base,id,title,"KR"+id+".pdf"),"");
        }
    }
    private static String readAsset(Context c,String name) throws IOException {
        try(InputStream in=c.getAssets().open(name); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[8192]; int n; while((n=in.read(b))>0) out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
