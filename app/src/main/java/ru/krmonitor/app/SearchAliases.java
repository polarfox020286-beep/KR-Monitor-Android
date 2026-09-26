package ru.krmonitor.app;

import java.util.*;

/**
 * Expands common clinical abbreviations and everyday medical terms into
 * wording likely to occur in official clinical-recommendation titles.
 * This is only a search aid: it never changes the catalog itself.
 */
public final class SearchAliases {
    private static final LinkedHashMap<String,List<String>> ALIASES=new LinkedHashMap<>();

    static {
        add("тэла",
                "тромбоэмболия легочной артерии",
                "тромбоэмболия легочных артерий",
                "легочная эмболия");
        add("онмк",
                "острое нарушение мозгового кровообращения",
                "инсульт");
        add("ишемический инсульт",
                "инфаркт мозга",
                "ишемический инсульт");
        add("тиа",
                "транзиторная ишемическая атака",
                "транзиторная церебральная ишемическая атака");
        add("окс",
                "острый коронарный синдром",
                "инфаркт миокарда",
                "нестабильная стенокардия");
        add("оим",
                "острый инфаркт миокарда",
                "инфаркт миокарда");
        add("хсн",
                "хроническая сердечная недостаточность",
                "сердечная недостаточность");
        add("осн",
                "острая сердечная недостаточность",
                "сердечная недостаточность");
        add("фп",
                "фибрилляция предсердий",
                "мерцательная аритмия");
        add("мерцательная аритмия",
                "фибрилляция предсердий");
        add("тгв",
                "тромбоз глубоких вен",
                "венозный тромбоз");
        add("втэо",
                "венозные тромбоэмболические осложнения",
                "венозная тромбоэмболия",
                "тромбоз глубоких вен",
                "тромбоэмболия легочной артерии");
        add("чкв",
                "чрескожное коронарное вмешательство",
                "чрескожное вмешательство");
        add("акш",
                "аортокоронарное шунтирование",
                "коронарное шунтирование");

        add("хобл",
                "хроническая обструктивная болезнь легких");
        add("бронхиальная астма",
                "астма бронхиальная",
                "бронхиальная астма");

        add("дка",
                "диабетический кетоацидоз",
                "кетоацидоз");
        add("ггс",
                "гипергликемическое гиперосмолярное состояние",
                "гиперосмолярное состояние");
        add("сд",
                "сахарный диабет");

        add("хбп",
                "хроническая болезнь почек",
                "хроническая почечная болезнь");
        add("опп",
                "острое повреждение почек");

        add("жкк",
                "желудочно кишечное кровотечение",
                "желудочно-кишечное кровотечение",
                "кровотечение желудочно кишечное");
        add("язвенное кровотечение",
                "желудочно кишечное кровотечение",
                "язвенная болезнь");

        add("чмт",
                "черепно мозговая травма",
                "черепно-мозговая травма");
        add("сгм",
                "сотрясение головного мозга");
        add("ддзп",
                "дегенеративно дистрофические заболевания позвоночника",
                "дегенеративно-дистрофические заболевания позвоночника",
                "дорсопатия");

        add("перелом шейки бедра",
                "перелом проксимального отдела бедренной кости",
                "перелом шейки бедренной кости",
                "перелом бедренной кости");
        add("перелом бедра",
                "перелом бедренной кости",
                "перелом проксимального отдела бедренной кости");
        add("перелом плеча",
                "перелом плечевой кости");

        add("оар",
                "анестезиология и реаниматология",
                "интенсивная терапия");
        add("орит",
                "анестезиология и реаниматология",
                "интенсивная терапия");

        add("анафилаксия",
                "анафилактический шок",
                "анафилаксия");
        add("анафилактический шок",
                "анафилаксия",
                "анафилактический шок");

        add("пневмония",
                "пневмония",
                "внебольничная пневмония");
        add("вп",
                "внебольничная пневмония");

        add("цирроз",
                "цирроз печени");
        add("панкреатит",
                "острый панкреатит",
                "хронический панкреатит");
    }

    private SearchAliases() {}

    private static void add(String key,String... variants) {
        ArrayList<String> list=new ArrayList<>();
        for(String v:variants) list.add(v);
        ALIASES.put(norm(key),Collections.unmodifiableList(list));
    }

    public static LinkedHashSet<String> variants(String query) {
        LinkedHashSet<String> out=new LinkedHashSet<>();
        String raw=query==null?"":query.trim();
        if(raw.isEmpty()) return out;

        String normalized=norm(raw);
        out.add(raw);

        List<String> exact=ALIASES.get(normalized);
        if(exact!=null) out.addAll(exact);

        // Also understand queries such as "тэла беременность" or "ХСН лечение":
        // replace the leading alias and keep the remaining words.
        for(Map.Entry<String,List<String>> entry:ALIASES.entrySet()) {
            String key=entry.getKey();
            if(normalized.startsWith(key+" ")) {
                String tail=normalized.substring(key.length()).trim();
                for(String v:entry.getValue()) out.add(v+" "+tail);
            }
        }
        return out;
    }

    public static boolean hasAlias(String query) {
        String q=norm(query);
        if(ALIASES.containsKey(q)) return true;
        for(String key:ALIASES.keySet()) if(q.startsWith(key+" ")) return true;
        return false;
    }

    private static String norm(String s) {
        if(s==null) return "";
        String n=s.toLowerCase(Locale.ROOT)
                .replace('ё','е')
                .replace('–',' ')
                .replace('—',' ')
                .replace('-',' ')
                .replaceAll("[^а-яa-z0-9. ]+"," ")
                .replaceAll("\\s+"," ")
                .trim();
        return n;
    }
}
