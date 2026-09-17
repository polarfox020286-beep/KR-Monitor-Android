package ru.krmonitor.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CatalogFetcher {
    private static final String OFFICIAL_URL="https://apicr.minzdrav.gov.ru/api.ashx?op=GetJsonClinrecsFilterV2";
    private static final String FALLBACK_URL="https://raw.githubusercontent.com/polarfox020286-beep/KR-Monitor-Android/main/data/catalog.json";
    private static final int PAGE_SIZE=2000;
    private static final int MIN_COUNT=500;

    private CatalogFetcher() {}

    public static Map<String,Recommendation> fetch() throws Exception {
        Exception officialError=null;
        try {
            Map<String,Recommendation> result=fetchOfficial();
            validate(result,"официальный реестр Минздрава");
            return result;
        } catch(Exception e) {
            officialError=e;
        }

        try {
            Map<String,Recommendation> result=fetchFallback();
            validate(result,"резервный каталог");
            return result;
        } catch(Exception fallbackError) {
            IOException out=new IOException("Не удалось получить актуальный реестр КР ни с сайта Минздрава, ни из резервного каталога: "+fallbackError.getMessage(),fallbackError);
            if(officialError!=null) out.addSuppressed(officialError);
            throw out;
        }
    }

    private static Map<String,Recommendation> fetchOfficial() throws Exception {
        LinkedHashMap<String,Recommendation> result=new LinkedHashMap<>();
        int page=1;
        int loaded=0;
        int total=Integer.MAX_VALUE;

        while(loaded<total) {
            JSONObject body=new JSONObject();
            JSONArray filters=new JSONArray();
            JSONObject status=new JSONObject();
            status.put("fieldName","status");
            status.put("filterType",1);
            status.put("filterValueType",2);
            status.put("value1",0);
            status.put("value2","");
            status.put("values",new JSONArray());
            filters.put(status);
            body.put("filters",filters);

            JSONObject sort=new JSONObject();
            sort.put("fieldName","publishdate");
            sort.put("sortType",2);
            body.put("sortOption",sort);
            body.put("pageSize",PAGE_SIZE);
            body.put("currentPage",page);
            body.put("useANDoperator",true);
            body.put("columns",new JSONArray());

            JSONObject root=new JSONObject(post(OFFICIAL_URL,body.toString()));
            JSONArray data=root.optJSONArray("Data");
            if(data==null) data=root.optJSONArray("data");
            if(data==null) throw new IOException("Официальный реестр вернул ответ без списка Data");

            total=root.optInt("TotalRecords",root.optInt("totalRecords",data.length()));
            if(total<MIN_COUNT) throw new IOException("Официальный реестр содержит подозрительно мало записей: "+total);

            for(int i=0;i<data.length();i++) addOfficial(result,data.getJSONObject(i));
            loaded+=data.length();
            if(data.length()==0 || loaded>=total) break;
            page++;
            if(page>20) throw new IOException("Слишком много страниц при загрузке официального реестра");
        }
        return result;
    }

    private static void addOfficial(Map<String,Recommendation> result,JSONObject o) {
        String id=o.optString("CodeVersion",o.optString("codeversion","")).trim();
        if(!id.matches("\\d+_\\d+")) {
            int code=o.optInt("Code",o.optInt("code",-1));
            int ver=o.optInt("Version",o.optInt("version",-1));
            if(code>0 && ver>0) id=code+"_"+ver;
        }
        String title=o.optString("Name",o.optString("name","")).trim();
        add(result,id,title,MkbUtils.extract(o.opt("Mkbs")));
    }

    private static Map<String,Recommendation> fetchFallback() throws Exception {
        JSONArray a=new JSONArray(get(FALLBACK_URL));
        LinkedHashMap<String,Recommendation> result=new LinkedHashMap<>();
        for(int i=0;i<a.length();i++) {
            JSONObject o=a.getJSONObject(i);
            add(result,o.optString("id","").trim(),o.optString("title","").trim(),o.optString("mkb","").trim());
        }
        return result;
    }

    private static void add(Map<String,Recommendation> result,String id,String title,String mkb) {
        if(!id.matches("\\d+_\\d+") || title.length()<3) return;
        // Defense against an old synthetic seed that once contained a non-KR record.
        if(id.equals("439_1") && (title.equalsIgnoreCase("RFR и iFR") || title.toLowerCase(Locale.ROOT).contains("rfr"))) return;
        String base=id.split("_",2)[0];
        Recommendation prev=result.get(base);
        Recommendation cur=new Recommendation(base,id,title,"KR"+id+".pdf",mkb);
        if(prev==null || version(id)>version(prev.id)) result.put(base,cur);
    }

    private static void validate(Map<String,Recommendation> result,String source) throws IOException {
        if(result.size()<MIN_COUNT) throw new IOException(source+" временно недоступен или неполный: "+result.size()+" записей");
    }

    private static int version(String id) {
        int p=id.indexOf('_'); if(p<0) return 0;
        try { return Integer.parseInt(id.substring(p+1)); } catch(Exception e) { return 0; }
    }

    private static String post(String u,String body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(35000);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android) KR-Monitor/1.3");
        c.setRequestProperty("Referer","https://cr.minzdrav.gov.ru/clin-rec");
        c.setRequestProperty("Origin","https://cr.minzdrav.gov.ru");
        c.setRequestProperty("Accept","application/json,text/plain,*/*");
        c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try(OutputStream out=c.getOutputStream()) { out.write(bytes); }
        int code=c.getResponseCode();
        if(code!=200) {
            String error=read(c.getErrorStream());
            throw new IOException("Официальный реестр Минздрава: HTTP "+code+(error.isEmpty()?"":" — "+error.substring(0,Math.min(160,error.length()))));
        }
        try(InputStream in=c.getInputStream()) { return read(in); }
        finally { c.disconnect(); }
    }

    private static String get(String u) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent","KR-Monitor-Android/1.3");
        c.setRequestProperty("Accept","application/json,text/plain,*/*");
        int code=c.getResponseCode();
        if(code!=200) throw new IOException("Резервный каталог: HTTP "+code);
        try(InputStream in=c.getInputStream()) { return read(in); }
        finally { c.disconnect(); }
    }

    private static String read(InputStream in) throws IOException {
        if(in==null) return "";
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[32768]; int n;
            while((n=in.read(b))>0) out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
