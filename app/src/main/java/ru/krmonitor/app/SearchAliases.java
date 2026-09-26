package ru.krmonitor.app;

import java.util.*;

/**
 * Search helpers for common Russian medical abbreviations, synonyms and
 * automatically derived title acronyms. This affects search only and never
 * changes the official catalog.
 */
public final class SearchAliases {
    private static final LinkedHashMap<String,List<String>> ALIASES=new LinkedHashMap<>();
    private static final Set<String> ACRONYM_STOP_WORDS=new HashSet<>(Arrays.asList(
            "и","или","с","со","в","во","на","по","при","для","до","после","без",
            "из","от","к","ко","под","над","у","о","об","про","между",
            "взрослых","взрослого","детей","ребенка","детского","возраста",
            "клинические","рекомендации"
    ));

    static {
        // Cardiovascular
        add("тэла",
                "тромбоэмболия легочной артерии",
                "тромбоэмболия легочных артерий",
                "легочная эмболия");
        add("окс",
                "острый коронарный синдром",
                "инфаркт миокарда",
                "нестабильная стенокардия");
        add("оим","острый инфаркт миокарда","инфаркт миокарда");
        add("ибс","ишемическая болезнь сердца");
        add("аг","артериальная гипертензия","гипертоническая болезнь");
        add("гб","гипертоническая болезнь","артериальная гипертензия");
        add("хсн","хроническая сердечная недостаточность","сердечная недостаточность");
        add("осн","острая сердечная недостаточность","сердечная недостаточность");
        add("фп","фибрилляция предсердий","мерцательная аритмия");
        add("мерцательная аритмия","фибрилляция предсердий");
        add("тгв","тромбоз глубоких вен","венозный тромбоз");
        add("втэо",
                "венозные тромбоэмболические осложнения",
                "венозная тромбоэмболия",
                "тромбоз глубоких вен",
                "тромбоэмболия легочной артерии");
        add("иэ","инфекционный эндокардит","инфекционный эндокардит клапана");
        add("чкв","чрескожное коронарное вмешательство","чрескожное вмешательство");
        add("акш","аортокоронарное шунтирование","коронарное шунтирование");

        // Neurology / neurosurgery
        add("онмк","острое нарушение мозгового кровообращения","инсульт");
        add("тиа","транзиторная ишемическая атака","транзиторная церебральная ишемическая атака");
        add("ишемический инсульт","инфаркт мозга","ишемический инсульт");
        add("сак","субарахноидальное кровоизлияние");
        add("вмк","внутримозговое кровоизлияние","внутричерепное кровоизлияние");
        add("сдг","субдуральная гематома");
        add("эдг","эпидуральная гематома");
        add("рс","рассеянный склероз");
        add("бп","болезнь паркинсона","паркинсонизм");
        add("бас","боковой амиотрофический склероз");

        // Pulmonology / intensive care
        add("хобл","хроническая обструктивная болезнь легких");
        add("ба","бронхиальная астма","астма бронхиальная");
        add("бронхиальная астма","астма бронхиальная","бронхиальная астма");
        add("ордс","острый респираторный дистресс синдром","респираторный дистресс синдром");
        add("одн","острая дыхательная недостаточность","дыхательная недостаточность");
        add("слр","сердечно легочная реанимация","сердечно-легочная реанимация");
        add("сш","септический шок","сепсис");

        // Endocrinology
        add("дка","диабетический кетоацидоз","кетоацидоз");
        add("ггс","гипергликемическое гиперосмолярное состояние","гиперосмолярное состояние");
        add("сд","сахарный диабет");
        add("сд1","сахарный диабет 1 типа","сахарный диабет первого типа");
        add("сд2","сахарный диабет 2 типа","сахарный диабет второго типа");

        // Nephrology
        add("хбп","хроническая болезнь почек","хроническая почечная болезнь");
        add("опп","острое повреждение почек");
        add("опн","острое повреждение почек","острая почечная недостаточность");
        add("хпн","хроническая болезнь почек","хроническая почечная недостаточность");

        // Gastroenterology / surgery
        add("окн","острая кишечная непроходимость","кишечная непроходимость");
        add("жкк",
                "желудочно кишечное кровотечение",
                "желудочно-кишечное кровотечение",
                "кровотечение желудочно кишечное");
        add("гэрб",
                "гастроэзофагеальная рефлюксная болезнь",
                "гастроэзофагеальная рефлюксная болезнь");
        add("жкб","желчнокаменная болезнь","желчно каменная болезнь");
        add("няк","язвенный колит","неспецифический язвенный колит");
        add("бк","болезнь крона");
        add("взк","воспалительные заболевания кишечника","язвенный колит","болезнь крона");
        add("ябж","язвенная болезнь желудка","язва желудка");
        add("ябдпк",
                "язвенная болезнь двенадцатиперстной кишки",
                "язва двенадцатиперстной кишки");
        add("язвенное кровотечение","желудочно кишечное кровотечение","язвенная болезнь");
        add("цирроз","цирроз печени");
        add("панкреатит","острый панкреатит","хронический панкреатит");

        // Trauma / spine
        add("чмт","черепно мозговая травма","черепно-мозговая травма");
        add("сгм","сотрясение головного мозга");
        add("ддзп",
                "дегенеративно дистрофические заболевания позвоночника",
                "дегенеративно-дистрофические заболевания позвоночника",
                "дорсопатия");
        add("перелом шейки бедра",
                "перелом проксимального отдела бедренной кости",
                "перелом шейки бедренной кости",
                "перелом бедренной кости");
        add("перелом бедра","перелом бедренной кости","перелом проксимального отдела бедренной кости");
        add("перелом плеча","перелом плечевой кости");

        // Infectious diseases
        add("орви","острая респираторная вирусная инфекция","острые респираторные вирусные инфекции");
        add("вич","вич инфекция","вич-инфекция");
        add("ковид","новая коронавирусная инфекция","covid 19","covid-19");
        add("covid","новая коронавирусная инфекция","covid 19","covid-19");

        // Department / workflow vocabulary sometimes used by clinicians
        add("оар","анестезиология и реаниматология","интенсивная терапия");
        add("орит","анестезиология и реаниматология","интенсивная терапия");

        // Common everyday terms
        add("анафилаксия","анафилактический шок","анафилаксия");
        add("анафилактический шок","анафилаксия","анафилактический шок");
        add("пневмония","пневмония","внебольничная пневмония");
        add("вп","внебольничная пневмония");
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

        // Understand queries such as "тэла беременность" or "ХСН лечение".
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

    /**
     * Matches abbreviations automatically from consecutive meaningful words
     * in the official title. Examples:
     * "Острая кишечная непроходимость" -> ОКН,
     * "Стабильная ишемическая болезнь сердца" contains -> ИБС,
     * "Острый респираторный дистресс-синдром" -> ОРДС.
     */
    public static boolean titleAcronymMatches(String title,String query) {
        String q=acronymKey(query);
        if(q.length()<2 || q.length()>5) return false;
        if(query==null || query.trim().contains(" ")) return false;
        if(!q.matches("[а-яa-z0-9]+")) return false;

        String normalizedTitle=norm(title);
        if(normalizedTitle.isEmpty()) return false;

        ArrayList<String> words=new ArrayList<>();
        for(String word:normalizedTitle.split("\\s+")) {
            if(word.isEmpty() || ACRONYM_STOP_WORDS.contains(word)) continue;
            if(word.matches("\\d+")) continue;
            words.add(word);
        }

        int needed=q.length();
        if(words.size()<needed) return false;

        for(int start=0;start+needed<=words.size();start++) {
            StringBuilder candidate=new StringBuilder();
            for(int i=0;i<needed;i++) {
                String w=words.get(start+i);
                if(w.isEmpty()) break;
                candidate.append(w.charAt(0));
            }
            if(q.equals(candidate.toString())) return true;
        }
        return false;
    }

    private static String acronymKey(String s) {
        if(s==null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace('ё','е')
                .replaceAll("[^а-яa-z0-9]","");
    }

    private static String norm(String s) {
        if(s==null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace('ё','е')
                .replace('–',' ')
                .replace('—',' ')
                .replace('-',' ')
                .replaceAll("[^а-яa-z0-9. ]+"," ")
                .replaceAll("\\s+"," ")
                .trim();
    }
}
