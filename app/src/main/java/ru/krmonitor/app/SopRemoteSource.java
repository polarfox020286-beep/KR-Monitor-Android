package ru.krmonitor.app;

import android.content.*;
import android.net.Uri;

import org.json.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

final class YandexDiskSource {
    private static final String API="https://cloud-api.yandex.net/v1/disk/public/resources";

    static List<SopDocument> scan(Context context,String publicKey) throws Exception{
        String key=publicKey==null?"":publicKey.trim();
        if(key.isEmpty())throw new IllegalStateException("Публичная ссылка Яндекс Диска не указана.");

        ArrayList<SopDocument> out=new ArrayList<>();
        HashSet<String> visitedFolders=new HashSet<>();
        scanFolder(key,null,out,visitedFolders,0);
        out.sort(Comparator.comparing(d->d.title.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static void scanFolder(String publicKey,String path,List<SopDocument> out,Set<String> visited,int depth) throws Exception{
        if(depth>12)throw new IOException("Слишком большая вложенность папок Яндекс Диска.");
        String marker=path==null?"/":path;
        if(!visited.add(marker))return;

        int offset=0;
        while(true){
            StringBuilder url=new StringBuilder(API)
                    .append("?public_key=").append(SopStreams.enc(publicKey))
                    .append("&limit=100&offset=").append(offset);
            if(path!=null&&!path.isEmpty())url.append("&path=").append(SopStreams.enc(path));

            JSONObject root=SopStreams.getJson(url.toString());
            JSONObject embedded=root.optJSONObject("_embedded");
            if(embedded==null){
                if("file".equals(root.optString("type")))addFile(publicKey,root,out);
                return;
            }

            JSONArray items=embedded.optJSONArray("items");
            if(items==null||items.length()==0)return;

            for(int i=0;i<items.length();i++){
                JSONObject item=items.optJSONObject(i);
                if(item==null)continue;
                String type=item.optString("type","");
                String itemPath=item.optString("path","");
                if("dir".equals(type)){
                    if(!itemPath.isEmpty())scanFolder(publicKey,itemPath,out,visited,depth+1);
                }else if("file".equals(type)){
                    addFile(publicKey,item,out);
                }
            }

            int total=embedded.optInt("total",offset+items.length());
            offset+=items.length();
            if(offset>=total||items.length()<100)return;
        }
    }

    private static void addFile(String publicKey,JSONObject item,List<SopDocument> out){
        String name=item.optString("name","");
        if(name.isEmpty())return;

        String mime=item.optString("mime_type","");
        String low=name.toLowerCase(Locale.ROOT);
        boolean supported=low.endsWith(".pdf")||low.endsWith(".doc")||low.endsWith(".docx")||
                "application/pdf".equals(mime)||"application/msword".equals(mime)||
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime);
        if(!supported)return;

        String path=item.optString("path","");
        if(path.isEmpty())path="/"+name;

        long size=item.optLong("size",0L);
        long modified=parseTime(item.optString("modified",""));

        String title=SopClassifier.realTitle(name);
        String type=SopClassifier.type(name,title);
        String category=SopClassifier.category(name,title);
        String keywords=SopClassifier.keywords(title,category,type);

        Uri ref=new Uri.Builder()
                .scheme("yadisk")
                .authority("public")
                .appendQueryParameter("key",publicKey)
                .appendQueryParameter("path",path)
                .build();

        String stableKey="yadisk:"+path;
        out.add(new SopDocument(stableKey,name,title,category,type,keywords,ref.toString(),mime,modified,size));
    }

    private static long parseTime(String raw){
        if(raw==null||raw.isEmpty())return 0L;
        try{return OffsetDateTime.parse(raw).toInstant().toEpochMilli();}
        catch(Exception ignored){return 0L;}
    }

    private YandexDiskSource(){}
}

final class SopStreams {
    private static final String PUBLIC_DOWNLOAD="https://cloud-api.yandex.net/v1/disk/public/resources/download";

    static InputStream open(Context context,String raw) throws Exception{
        if(raw==null||raw.trim().isEmpty())throw new FileNotFoundException("Пустой адрес документа.");

        if(raw.startsWith("yadisk://")){
            Uri u=Uri.parse(raw);
            String key=u.getQueryParameter("key");
            String path=u.getQueryParameter("path");
            if(key==null||key.isEmpty())throw new IOException("Не найден публичный ключ Яндекс Диска.");

            StringBuilder api=new StringBuilder(PUBLIC_DOWNLOAD)
                    .append("?public_key=").append(enc(key));
            if(path!=null&&!path.isEmpty())api.append("&path=").append(enc(path));

            JSONObject j=getJson(api.toString());
            String href=j.optString("href","");
            if(href.isEmpty())throw new IOException("Яндекс Диск не вернул ссылку для скачивания.");
            return openHttp(href);
        }

        Uri uri=Uri.parse(raw);
        String scheme=uri.getScheme();
        if("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme))return openHttp(raw);

        InputStream in=context.getContentResolver().openInputStream(uri);
        if(in==null)throw new FileNotFoundException("Источник не предоставил поток файла.");
        return in;
    }

    static JSONObject getJson(String url) throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("User-Agent","SOP-Navigator/2");
        int code=c.getResponseCode();
        InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        String body=readAll(in);
        c.disconnect();
        if(code<200||code>=300){
            String message="";
            try{message=new JSONObject(body).optString("message","");}catch(Exception ignored){}
            if(message.isEmpty())message="HTTP "+code;
            throw new IOException("Яндекс Диск: "+message);
        }
        return new JSONObject(body);
    }

    static String enc(String s) throws UnsupportedEncodingException{
        return URLEncoder.encode(s==null?"":s,StandardCharsets.UTF_8.name());
    }

    private static InputStream openHttp(String url) throws Exception{
        final HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent","SOP-Navigator/2");
        int code=c.getResponseCode();
        if(code<200||code>=300){
            String body=readAll(c.getErrorStream());
            c.disconnect();
            throw new IOException("Скачивание не выполнено: HTTP "+code+(body.isEmpty()?"":" · "+body));
        }
        return new FilterInputStream(c.getInputStream()){
            @Override public void close() throws IOException{
                try{super.close();}finally{c.disconnect();}
            }
        };
    }

    private static String readAll(InputStream in) throws IOException{
        if(in==null)return "";
        try(InputStream source=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];
            int n;
            while((n=source.read(buf))!=-1)out.write(buf,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private SopStreams(){}
}
