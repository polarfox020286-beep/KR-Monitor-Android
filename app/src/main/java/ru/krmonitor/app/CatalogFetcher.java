package ru.krmonitor.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CatalogFetcher {
    private static final String URL="https://raw.githubusercontent.com/polarfox020286-beep/KR-Monitor-Android/main/data/catalog.json";
    private CatalogFetcher() {}

    public static Map<String,Recommendation> fetch() throws Exception {
        String json=get(URL);
        JSONArray a=new JSONArray(json);
        LinkedHashMap<String,Recommendation> result=new LinkedHashMap<>();
        for(int i=0;i<a.length();i++) {
            JSONObject o=a.getJSONObject(i);
            String id=o.optString("id","").trim();
            String title=o.optString("title","").trim();
            if(!id.matches("\\d+_\\d+") || title.length()<3) continue;
            // Historical bad seed contained this non-clinical-recommendation document.
            if(id.equals("439_1") && (title.equalsIgnoreCase("RFR и iFR") || title.toLowerCase(Locale.ROOT).contains("rfr"))) continue;
            String base=id.split("_",2)[0];
            Recommendation prev=result.get(base);
            Recommendation cur=new Recommendation(base,id,title,"KR"+id+".pdf");
            if(prev==null || version(id)>version(prev.id)) result.put(base,cur);
        }
        if(result.size()<500) throw new IOException("Каталог обновлений временно недоступен или неполный");
        return result;
    }

    private static int version(String id) {
        int p=id.indexOf('_'); if(p<0) return 0;
        try { return Integer.parseInt(id.substring(p+1)); } catch(Exception e) { return 0; }
    }

    private static String get(String u) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new java.net.URL(u).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent","KR-Monitor-Android/1.2");
        c.setRequestProperty("Accept","application/json,text/plain,*/*");
        int code=c.getResponseCode();
        if(code!=200) throw new IOException("Не удалось получить каталог обновлений: HTTP "+code);
        try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[16384]; int n; while((n=in.read(b))>0) out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally { c.disconnect(); }
    }
}
