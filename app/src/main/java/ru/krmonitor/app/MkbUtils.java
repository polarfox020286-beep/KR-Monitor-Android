package ru.krmonitor.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.regex.*;

public final class MkbUtils {
    private static final Pattern CODE=Pattern.compile("(?i)(?<![A-Z0-9])([A-Z][0-9]{2}(?:\\.[0-9A-Z]{1,2})?)(?![A-Z0-9])");
    private MkbUtils() {}

    public static String extract(Object value) {
        LinkedHashSet<String> out=new LinkedHashSet<>();
        collect(value,out);
        return String.join(", ",out);
    }

    private static void collect(Object value,Set<String> out) {
        if(value==null || value==JSONObject.NULL) return;
        if(value instanceof JSONObject) {
            JSONObject o=(JSONObject)value;
            Iterator<String> it=o.keys();
            while(it.hasNext()) collect(o.opt(it.next()),out);
            return;
        }
        if(value instanceof JSONArray) {
            JSONArray a=(JSONArray)value;
            for(int i=0;i<a.length();i++) collect(a.opt(i),out);
            return;
        }
        Matcher m=CODE.matcher(String.valueOf(value).toUpperCase(Locale.ROOT));
        while(m.find()) out.add(m.group(1));
    }

    public static boolean looksLikeCode(String q) {
        String n=normalize(q);
        return n.matches("[A-Z][0-9]{1,4}[A-Z0-9]*");
    }

    public static boolean matches(String codes,String query) {
        String q=normalize(query);
        if(q.length()<2) return false;
        String c=normalize(codes);
        return !c.isEmpty() && c.contains(q);
    }

    public static String normalize(String s) {
        if(s==null) return "";
        return s.toUpperCase(Locale.ROOT).replace(".","").replace(" ","").replace("-","").replace("–","").replace(",","").replace(";","");
    }
}
