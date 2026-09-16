package ru.krmonitor.app;

import android.text.Html;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public final class CatalogFetcher {
    private static final String URL="https://www.endoexpert.ru/dokumenty-i-prikazy/rubrikatorkr/?itape=1053";
    private CatalogFetcher() {}

    public static Map<String,Recommendation> fetch() throws Exception {
        String html=get(URL);
        LinkedHashMap<String,Recommendation> result=new LinkedHashMap<>();
        Pattern li=Pattern.compile("<li\\b[^>]*>(.*?)</li>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
        Matcher lm=li.matcher(html);
        Pattern idp=Pattern.compile("\\bID\\s*[:№]?\\s*(\\d+_\\d+)\\b",Pattern.CASE_INSENSITIVE);
        Pattern datep=Pattern.compile("размещено\\s+\\d{2}\\.\\d{2}\\.\\d{4}",Pattern.CASE_INSENSITIVE);
        Pattern ap=Pattern.compile("<a\\b[^>]*>(.*?)</a>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
        while(lm.find()) {
            String block=lm.group(1);
            String plain=toText(block);
            Matcher im=idp.matcher(plain);
            if(!im.find() || !datep.matcher(plain).find()) continue;
            String id=im.group(1);
            String title="";
            Matcher am=ap.matcher(block);
            while(am.find()) {
                String cand=toText(am.group(1));
                if(cand.length()>title.length() && !cand.equalsIgnoreCase("скачать") && !cand.equalsIgnoreCase("подробнее")) title=cand;
            }
            if(title.length()<3) continue;
            String base=id.split("_",2)[0];
            Recommendation prev=result.get(base);
            Recommendation cur=new Recommendation(base,id,title,"KR"+id+".pdf");
            if(prev==null || version(id)>version(prev.id)) result.put(base,cur);
        }
        if(result.size()<500) throw new IOException("Онлайн-каталог распознан не полностью: "+result.size()+" записей");
        return result;
    }
    private static int version(String id) {
        int p=id.indexOf('_'); if(p<0) return 0;
        try { return Integer.parseInt(id.substring(p+1)); } catch(Exception e) { return 0; }
    }
    private static String toText(String html) {
        return Html.fromHtml(html,Html.FROM_HTML_MODE_LEGACY).toString().replace('\u00a0',' ').replaceAll("\\s+"," ").trim();
    }
    private static String get(String u) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new java.net.URL(u).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(60000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 15) KR-Monitor/1.0");
        c.setRequestProperty("Accept-Language","ru-RU,ru;q=0.9");
        int code=c.getResponseCode(); if(code!=200) throw new IOException("HTTP "+code);
        try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[16384]; int n; while((n=in.read(b))>0) out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally { c.disconnect(); }
    }
}
