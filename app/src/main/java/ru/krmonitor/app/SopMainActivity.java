package ru.krmonitor.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.animation.TranslateAnimation;
import android.widget.*;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class SopMainActivity extends Activity {
    public static final String ACTION_SYNC_COMPLETE="ru.sopnavigator.app.SYNC_COMPLETE";
    private static final int REQ_TREE=44;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final BroadcastReceiver syncReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(ACTION_SYNC_COMPLETE.equals(intent.getAction())) reload();
        }
    };

    private SopDbHelper db;
    private TextView status,source,recentList;
    private EditText search;
    private FrameLayout contentHost;
    private List<SopDocument> all=new ArrayList<>();

    private static final int PAGE_ALL=0;
    private static final int PAGE_GROUPS=1;
    private static final int PAGE_HISTORY=2;
    private int currentPage=PAGE_GROUPS;
    private String selectedCategory=null;
    private float swipeX,swipeY;
    private boolean swipeTracking=false;

    private static final int BG=Color.rgb(247,249,252);
    private static final int CARD=Color.WHITE;
    private static final int BLUE=Color.rgb(37,99,199);
    private static final int BLUE_DARK=Color.rgb(25,70,145);
    private static final int BLUE_SOFT=Color.rgb(235,243,255);
    private static final int TEXT=Color.rgb(29,38,52);
    private static final int MUTED=Color.rgb(104,115,132);
    private static final int LINE=Color.rgb(228,233,240);
    private static final int WARN=Color.rgb(180,92,20);
    private static final int WARN_BG=Color.rgb(255,246,233);

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        db=new SopDbHelper(this);
        SopAlarmScheduler.scheduleNext(this);
        requestNotifyPermission();
        requestExactAlarmPermission();
        buildUi();
        reload();
        if(!hasFolder()){
            source.postDelayed(() -> {
                if(!isFinishing()) Toast.makeText(this,"Выберите папку СОПов на Google Drive один раз",Toast.LENGTH_LONG).show();
            },400);
        }
    }

    @Override protected void onStart(){
        super.onStart();
        IntentFilter f=new IntentFilter(ACTION_SYNC_COMPLETE);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(syncReceiver,f,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(syncReceiver,f);
    }

    @Override protected void onStop(){
        try{unregisterReceiver(syncReceiver);}catch(Exception ignored){}
        super.onStop();
    }

    @Override protected void onDestroy(){
        executor.shutdownNow();
        db.close();
        super.onDestroy();
    }

    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}

    private GradientDrawable rounded(int fill,int stroke,int radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill); d.setCornerRadius(dp(radius));
        if(stroke!=Color.TRANSPARENT) d.setStroke(dp(1),stroke);
        return d;
    }

    private TextView text(String value,float size,int color,boolean bold){
        TextView t=new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(14),dp(16),dp(10));
        root.setBackgroundColor(BG);

        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout heading=new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title=text("СОП Навигатор",23,TEXT,true);
        TextView subtitle=text("Автоматическая проверка новых и обновлённых документов в 07:00",12,MUTED,false);
        subtitle.setPadding(0,dp(2),0,0);
        heading.addView(title); heading.addView(subtitle);
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));

        TextView sync=text("↻  Проверить",13,BLUE,true);
        sync.setGravity(Gravity.CENTER);
        sync.setPadding(dp(12),dp(9),dp(12),dp(9));
        sync.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,14));
        sync.setClickable(true); sync.setFocusable(true);
        header.addView(sync);
        root.addView(header);

        status=text("",12,MUTED,false);
        status.setPadding(0,dp(9),0,dp(3));
        root.addView(status);

        source=text("",12,BLUE,true);
        source.setPadding(dp(10),dp(7),dp(10),dp(7));
        source.setGravity(Gravity.CENTER_VERTICAL);
        source.setClickable(true); source.setFocusable(true);
        LinearLayout.LayoutParams sourceLp=new LinearLayout.LayoutParams(-1,-2);
        sourceLp.setMargins(0,0,0,dp(9));
        root.addView(source,sourceLp);

        LinearLayout recentHeader=new LinearLayout(this);
        recentHeader.setOrientation(LinearLayout.HORIZONTAL);
        recentHeader.setGravity(Gravity.CENTER_VERTICAL);
        recentHeader.addView(text("Изменения",15,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        recentHeader.addView(text("последние 48 ч",11,MUTED,false));
        root.addView(recentHeader);

        recentList=text("",13,TEXT,false);
        recentList.setLineSpacing(dp(2),1f);
        recentList.setPadding(dp(11),dp(9),dp(11),dp(9));
        recentList.setBackground(rounded(CARD,LINE,14));
        LinearLayout.LayoutParams recentLp=new LinearLayout.LayoutParams(-1,-2);
        recentLp.setMargins(0,dp(5),0,dp(10));
        root.addView(recentList,recentLp);

        search=new EditText(this);
        search.setHint("Название, раздел СМК или ключевое слово");
        search.setHintTextColor(Color.rgb(145,153,165));
        search.setTextColor(TEXT); search.setTextSize(15); search.setSingleLine(true);
        search.setPadding(dp(14),0,dp(14),0);
        search.setBackground(rounded(CARD,LINE,15));
        root.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));

        contentHost=new FrameLayout(this);
        LinearLayout.LayoutParams contentLp=new LinearLayout.LayoutParams(-1,0,1);
        contentLp.setMargins(0,dp(6),0,0);
        root.addView(contentHost,contentLp);
        setContentView(root);

        sync.setOnClickListener(v -> {
            if(!hasFolder()) chooseFolder();
            else runSync(sync);
        });
        source.setOnClickListener(v -> chooseFolder());
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int b,int c){
                String q=s.toString().trim();
                if(q.isEmpty())renderCurrentPage(0); else renderSearch(q);
            }
            public void afterTextChanged(Editable e){}
        });
    }

    private boolean hasFolder(){
        return !getSharedPreferences("prefs",MODE_PRIVATE).getString("tree_uri","").isEmpty();
    }

    private void chooseFolder(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i,REQ_TREE);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=REQ_TREE || resultCode!=RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData();
        try{
            int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri,flags);
        }catch(Exception ignored){}
        getSharedPreferences("prefs",MODE_PRIVATE).edit()
                .putString("tree_uri",uri.toString())
                .putBoolean("baseline_done",false)
                .remove("last_sync_text").remove("last_sync_ms").apply();
        db.clearAll();
        reload();
        runSync(null);
    }

    private void runSync(TextView button){
        if(!hasFolder()){chooseFolder();return;}
        if(button!=null){button.setEnabled(false);button.setText("Проверка…");}
        status.setText("Проверяю папку Google Drive…");
        executor.submit(() -> {
            SopSyncEngine.Result result=SopSyncEngine.sync(getApplicationContext());
            runOnUiThread(() -> {
                if(button!=null){button.setEnabled(true);button.setText("↻  Проверить");}
                Toast.makeText(this,result.message,Toast.LENGTH_LONG).show();
                reload();
            });
        });
    }

    private void reload(){
        all=db.all();
        SharedPreferences p=getSharedPreferences("prefs",MODE_PRIVATE);
        long last=p.getLong("last_sync_ms",0);
        long next=p.getLong("next_alarm",SopAlarmScheduler.nextWeekday7());
        String lastText=last==0?"ещё не выполнялась":DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(last));
        String note=notificationsEnabled()?"":"\n⚠ Уведомления Android отключены";
        status.setText(all.size()+" документов  •  Последняя проверка: "+lastText+"\nСледующая: "+
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(next))+note);
        if(hasFolder()){
            source.setText("Google Drive подключён  ·  нажмите, чтобы изменить папку");
            source.setTextColor(BLUE);
            source.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,12));
        }else{
            source.setText("Подключить папку СОПов на Google Drive");
            source.setTextColor(WARN);
            source.setBackground(rounded(WARN_BG,Color.TRANSPARENT,12));
        }
        renderRecent();
        String q=search==null?"":search.getText().toString().trim();
        if(q.isEmpty())renderCurrentPage(0); else renderSearch(q);
    }

    private void renderRecent(){
        List<SopDbHelper.ChangeEvent> recent=db.recentChangesWithinHours(48,8);
        if(recent.isEmpty()){
            recentList.setText("За последние 48 часов новых или обновлённых документов не обнаружено.");
            recentList.setTextColor(MUTED); return;
        }
        recentList.setTextColor(TEXT);
        StringBuilder sb=new StringBuilder();
        int max=Math.min(3,recent.size());
        for(int i=0;i<max;i++){
            SopDbHelper.ChangeEvent e=recent.get(i);
            if(i>0)sb.append("\n");
            sb.append("NEW".equals(e.type)?"НОВЫЙ  ":"ОБНОВЛЁН  ").append(e.title);
        }
        if(recent.size()>max)sb.append("\nЕщё ").append(recent.size()-max).append("…");
        recentList.setText(sb.toString());
    }

    private void renderCurrentPage(int direction){
        if(contentHost==null)return;
        if(currentPage==PAGE_ALL)showContent(makeListPage("Все документы",all,true),direction);
        else if(currentPage==PAGE_HISTORY)showContent(makeHistoryPage(),direction);
        else if(selectedCategory!=null)showContent(makeCategoryListPage(selectedCategory),direction);
        else showContent(makeCategoriesPage(),direction);
    }

    private View makeCategoriesPage(){
        LinearLayout outer=new LinearLayout(this); outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout pageHead=new LinearLayout(this); pageHead.setOrientation(LinearLayout.HORIZONTAL); pageHead.setGravity(Gravity.CENTER_VERTICAL);
        pageHead.addView(pageTitle("Разделы реестра СМК"),new LinearLayout.LayoutParams(0,-2,1));
        pageHead.addView(text("← Все     История →",11,MUTED,false));
        outer.addView(pageHead);

        Map<String,List<SopDocument>> groups=group(all);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout rows=new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); rows.setPadding(0,dp(2),0,dp(10));
        scroll.addView(rows,new ScrollView.LayoutParams(-1,-2));

        ArrayList<String> visible=new ArrayList<>();
        for(String c:SopClassifier.CATEGORIES){
            List<SopDocument> docs=groups.get(c);
            if(docs!=null&&!docs.isEmpty())visible.add(c);
        }
        for(int i=0;i<visible.size();i+=2){
            LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            for(int j=0;j<2;j++){
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(126),1);
                if(j==0)lp.setMargins(0,dp(4),dp(4),dp(4)); else lp.setMargins(dp(4),dp(4),0,dp(4));
                if(i+j<visible.size()){
                    String c=visible.get(i+j);
                    row.addView(categoryCard(c,groups.get(c).size()),lp);
                }else row.addView(new Space(this),lp);
            }
            rows.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
        outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        return outer;
    }

    private View categoryCard(String category,int count){
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER);
        card.setPadding(dp(9),dp(9),dp(9),dp(8));
        card.setBackground(rounded(CARD,LINE,16));
        if(Build.VERSION.SDK_INT>=21)card.setElevation(dp(1));
        card.setClickable(true); card.setFocusable(true);

        TextView icon=text(SopClassifier.icon(category),24,BLUE_DARK,true);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon,new LinearLayout.LayoutParams(-1,dp(38)));

        TextView name=text(category.replace("-","‑"),12.5f,TEXT,true);
        name.setGravity(Gravity.CENTER); name.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        name.setIncludeFontPadding(false); name.setMaxLines(4); name.setLineSpacing(dp(1),1.0f);
        if(Build.VERSION.SDK_INT>=23){
            name.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
            name.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        }
        card.addView(name,new LinearLayout.LayoutParams(-1,0,1));

        TextView number=text(count+" док.",11,MUTED,false);
        number.setGravity(Gravity.CENTER); number.setPadding(0,dp(4),0,0);
        card.addView(number);
        card.setOnClickListener(v->{selectedCategory=category;renderCurrentPage(0);});
        return card;
    }

    private View makeCategoryListPage(String category){
        List<SopDocument> docs=group(all).get(category);
        if(docs==null)docs=Collections.emptyList();
        LinearLayout outer=new LinearLayout(this); outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head=new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=text("‹  Разделы",13,BLUE,true);
        back.setPadding(dp(9),dp(7),dp(9),dp(7)); back.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,12)); back.setClickable(true);
        back.setOnClickListener(v->{selectedCategory=null;renderCurrentPage(0);});
        head.addView(back);
        TextView count=text(docs.size()+" док.",12,MUTED,false);
        LinearLayout.LayoutParams clp=new LinearLayout.LayoutParams(-2,-2); clp.setMargins(dp(10),0,0,0); head.addView(count,clp);
        outer.addView(head);
        TextView name=pageTitle(category); name.setPadding(dp(2),dp(8),dp(2),dp(5)); outer.addView(name);
        addDocumentList(outer,docs);
        return outer;
    }

    private View makeHistoryPage(){
        LinearLayout outer=new LinearLayout(this); outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head=new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(pageTitle("История"),new LinearLayout.LayoutParams(0,-2,1));
        TextView hint=text("← Разделы",11,MUTED,false); head.addView(hint);
        outer.addView(head);

        List<SopDocument> docs=db.history(100);
        if(!docs.isEmpty()){
            TextView clear=text("Очистить историю",12,BLUE,true); clear.setGravity(Gravity.RIGHT); clear.setPadding(0,dp(4),0,dp(4)); clear.setClickable(true);
            clear.setOnClickListener(v->{db.clearHistory();renderCurrentPage(0);});
            outer.addView(clear);
        }
        addDocumentList(outer,docs);
        return outer;
    }

    private View makeListPage(String title,List<SopDocument> docs,boolean allPage){
        LinearLayout outer=new LinearLayout(this); outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head=new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(pageTitle(title),new LinearLayout.LayoutParams(0,-2,1));
        if(allPage)head.addView(text("Разделы →",11,MUTED,false));
        outer.addView(head);
        addDocumentList(outer,docs);
        return outer;
    }

    private void addDocumentList(LinearLayout outer,List<SopDocument> docs){
        if(docs==null||docs.isEmpty()){
            TextView empty=text(hasFolder()?"Документов нет.":"Сначала подключите папку Google Drive.",14,MUTED,false);
            empty.setGravity(Gravity.CENTER); empty.setPadding(dp(10),dp(50),dp(10),dp(20));
            outer.addView(empty,new LinearLayout.LayoutParams(-1,0,1)); return;
        }
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(0,dp(2),0,dp(10));
        for(SopDocument d:docs)list.addView(documentCard(d));
        scroll.addView(list,new ScrollView.LayoutParams(-1,-2));
        outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }

    private View documentCard(SopDocument d){
        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12),dp(10),dp(12),dp(10)); card.setBackground(rounded(CARD,LINE,14)); card.setClickable(true);
        TextView title=text(d.title,14,TEXT,true); title.setLineSpacing(dp(1),1.05f); card.addView(title);
        TextView meta=text(d.type+"  ·  "+d.category,11,MUTED,false); meta.setPadding(0,dp(5),0,0); card.addView(meta);
        if(!SopClassifier.similarFileName(d.fileName,d.title)){
            TextView file=text("Файл: "+d.fileName,10,MUTED,false); file.setPadding(0,dp(3),0,0); file.setMaxLines(1); file.setEllipsize(TextUtils.TruncateAt.END); card.addView(file);
        }
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,dp(4),0,dp(4)); card.setLayoutParams(lp);
        card.setOnClickListener(v->openDocument(d));
        return card;
    }

    private void openDocument(SopDocument d){
        db.markViewed(d.key);
        try{
            Uri uri=Uri.parse(d.uri);
            Intent i=new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri,d.mime==null||d.mime.isEmpty()?"application/pdf":d.mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        }catch(Exception e){
            try{
                Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse(d.uri)); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(i);
            }catch(Exception x){
                Toast.makeText(this,"Не удалось открыть документ. Проверьте доступ к Google Drive.",Toast.LENGTH_LONG).show();
            }
        }
    }

    private void renderSearch(String query){
        String q=SopClassifier.norm(query);
        ArrayList<SopDocument> found=new ArrayList<>();
        for(SopDocument d:all){
            String hay=SopClassifier.norm(d.title+" "+d.fileName+" "+d.category+" "+d.type+" "+d.keywords);
            if(hay.contains(q))found.add(d);
        }
        showContent(makeListPage("Поиск · "+found.size(),found,false),0);
    }

    private Map<String,List<SopDocument>> group(List<SopDocument> docs){
        LinkedHashMap<String,List<SopDocument>> out=new LinkedHashMap<>();
        for(String c:SopClassifier.CATEGORIES)out.put(c,new ArrayList<>());
        for(SopDocument d:docs)out.computeIfAbsent(d.category,k->new ArrayList<>()).add(d);
        return out;
    }

    private TextView pageTitle(String s){
        TextView t=text(s,18,TEXT,true); t.setPadding(dp(2),dp(5),dp(2),dp(5)); return t;
    }

    private void showContent(View next,int direction){
        contentHost.removeAllViews(); contentHost.addView(next,new FrameLayout.LayoutParams(-1,-1));
        if(direction!=0){
            float from=direction>0?1f:-1f;
            TranslateAnimation a=new TranslateAnimation(Animation.RELATIVE_TO_SELF,from,Animation.RELATIVE_TO_SELF,0,
                    Animation.RELATIVE_TO_SELF,0,Animation.RELATIVE_TO_SELF,0);
            a.setDuration(160); next.startAnimation(a);
        }
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e){
        if(contentHost!=null){
            int action=e.getActionMasked();
            if(action==android.view.MotionEvent.ACTION_DOWN){
                swipeTracking=e.getY()>=contentHost.getTop(); swipeX=e.getX(); swipeY=e.getY();
            }else if(action==android.view.MotionEvent.ACTION_UP&&swipeTracking){
                float dx=e.getX()-swipeX,dy=e.getY()-swipeY; swipeTracking=false;
                if(search.getText().toString().trim().isEmpty()&&Math.abs(dx)>dp(90)&&Math.abs(dx)>Math.abs(dy)*1.35f){
                    if(dx>0)swipeRight();else swipeLeft(); return true;
                }
            }
        }
        return super.dispatchTouchEvent(e);
    }

    private void swipeRight(){
        selectedCategory=null;
        if(currentPage>PAGE_ALL){currentPage--;renderCurrentPage(-1);}
    }
    private void swipeLeft(){
        selectedCategory=null;
        if(currentPage<PAGE_HISTORY){currentPage++;renderCurrentPage(1);}
    }

    private boolean notificationsEnabled(){
        NotificationManager nm=(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        return Build.VERSION.SDK_INT<24||nm.areNotificationsEnabled();
    }

    private void requestNotifyPermission(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},3301);
    }

    private void requestExactAlarmPermission(){
        if(Build.VERSION.SDK_INT>=31){
            AlarmManager am=(AlarmManager)getSystemService(Context.ALARM_SERVICE);
            if(!am.canScheduleExactAlarms()&&!getSharedPreferences("prefs",MODE_PRIVATE).getBoolean("asked_exact_alarm",false)){
                getSharedPreferences("prefs",MODE_PRIVATE).edit().putBoolean("asked_exact_alarm",true).apply();
                try{startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));}catch(Exception ignored){}
            }
        }
    }
}

class SopDocument {
    final String key,fileName,title,category,type,keywords,uri,mime;
    final long lastModified,size;
    SopDocument(String key,String fileName,String title,String category,String type,String keywords,String uri,String mime,long lastModified,long size){
        this.key=key;this.fileName=fileName;this.title=title;this.category=category;this.type=type;this.keywords=keywords;
        this.uri=uri;this.mime=mime;this.lastModified=lastModified;this.size=size;
    }
}

class SopClassifier {
    static final String OTHER="Другое";
    static final List<String> CATEGORIES=Collections.unmodifiableList(Arrays.asList(
            "Экстренная медицинская помощь и приемное отделение",
            "Безопасность переливания крови",
            "Эпидемиологическая безопасность",
            "Преемственность",
            "Хирургическая безопасность",
            "Лекарственная безопасность",
            "Управление персоналом",
            "Управление качеством и безопасностью медицинской деятельности",
            "Доказательная медицина",
            "Идентификация безопасной медицинской помощи",
            "Сестринский менеджмент",
            "Лабораторный процесс",
            "Инвазивные вмешательства",
            "Медицинский сестринский уход",
            "Медицинское оборудование",
            OTHER
    ));

    private static final Map<String,String> TITLES=new HashMap<>();
    static{
        putTitle("СОП014.22-01ЭБ.pdf","Микробиологический мониторинг");
        putTitle("СОП 005.22.01ЛП.pdf","Выполнение коагулометрических исследований с использованием автоматических коагулометрических анализаторов для in vitro диагностики ACL TOP 350 CTS, 550 CTS");
        putTitle("№361.pdf","Экстренная профилактика столбняка");
        putTitle("№253.pdf","Процедура взятия крови из вены с помощью вакуумных систем");
        putTitle("№52 Ширай.pdf","Организация антирабической помощи");
        putTitle("№263.pdf","Бельевой режим в клинических и диагностических отделениях");
        putTitle("СОП016.22.01ЛБ КИ.pdf","Процедуры получения, учета, хранения, движения, утилизации исследуемых лекарственных препаратов, препаратов сравнения и/или сопутствующей терапии, медицинских изделий");
        putTitle("СОП003.22.02ЛБ Контроль температуры.pdf","Порядок контроля параметров температуры и влажности воздуха в помещениях аптеки и температурного режима в холодильном оборудовании");
        putTitle("СОП 004.22 орг.погрузо-разгр.работ.pdf","Организация погрузочно-разгрузочных работ в аптеке");
        putTitle("Приказ ТДП.docx","Внедрение методических рекомендаций «Обеспечение проходимости верхних дыхательных путей у взрослых пациентов в стационаре»");
    }
    private static void putTitle(String file,String title){TITLES.put(file.toLowerCase(Locale.ROOT),title);}

    static String realTitle(String fileName){
        if(fileName==null)return "Документ";
        String manual=TITLES.get(fileName.toLowerCase(Locale.ROOT));
        if(manual!=null)return manual;
        String s=fileName.replaceFirst("(?iu)\\.(pdf|docx?|rtf)$","").replace("_compressed","");
        if(s.toLowerCase(Locale.ROOT).startsWith("приказ ")){
            s=s.substring(7).trim();
            s=s.replaceFirst("(?iu)^№?\\s*[^\\s]*\\d[^\\s]*\\s+","");
            return clean(s);
        }
        if(s.startsWith("№")){
            s=s.substring(1).trim();
            s=s.replaceFirst("^\\d+[A-Za-zА-Яа-яЁё0-9_./-]*\\s+","");
            return clean(s);
        }
        if(s.toUpperCase(Locale.ROOT).startsWith("СОП")){
            s=s.substring(3).trim();
            if(!s.isEmpty()&&s.charAt(0)=='-')s=s.substring(1).trim();
            if(s.matches("^[^\\s]*\\d[^\\s]*\\s+.*"))s=s.replaceFirst("^[^\\s]+\\s+","");
            return clean(s);
        }
        if(s.matches("(?iu)^А[-–—].*")){
            s=s.replaceFirst("^[^\\s]+\\s+","");
            return clean(s);
        }
        return clean(s);
    }

    private static String clean(String s){
        s=s.replace('_',' ').replaceAll("\\s+"," ").trim();
        return s.isEmpty()?"Документ":s;
    }

    static String type(String raw,String title){
        String t=norm(raw+" "+title);
        if(t.startsWith("соп")||norm(raw).startsWith("соп"))return "СОП";
        if(norm(raw).matches("^а[-–—].*")||t.contains(" алгоритм ")||t.startsWith("алгоритм"))return "Алгоритм";
        if(norm(raw).startsWith("№")||norm(raw).startsWith("приказ"))return "Приказ";
        if(t.startsWith("положение")||t.contains(" положение "))return "Положение";
        return "Документ";
    }

    static String category(String raw,String title){
        String u=(raw==null?"":raw).toUpperCase(Locale.ROOT).replace('Ё','Е').replace('–','-').replace('—','-');
        String t=norm(title+" "+raw);

        if(u.contains("-ДМ-"))return "Доказательная медицина";
        if(u.contains("-ИД-"))return "Идентификация безопасной медицинской помощи";
        if(u.contains("-ЭМП-")||u.contains("-ЭП-"))return "Экстренная медицинская помощь и приемное отделение";
        if(u.contains("-ПР-"))return "Преемственность";
        if(u.contains("-ХБ-"))return "Хирургическая безопасность";
        if(u.contains("-УП-"))return "Управление персоналом";
        if(u.contains("-ВК-"))return "Управление качеством и безопасностью медицинской деятельности";
        if(u.contains("-ЛБ-"))return "Лекарственная безопасность";
        if(u.contains("-СМ-"))return "Сестринский менеджмент";
        if(u.contains("-ЭБ-"))return "Эпидемиологическая безопасность";
        if(u.matches(".*\\d+ПК\\b.*")||u.contains("-ПК-"))return "Безопасность переливания крови";
        if(u.matches(".*\\d+ЛП\\b.*")&&!u.matches(".*А-\\d+-ЛП-.*"))return "Лабораторный процесс";
        if(u.matches(".*\\d+ИВ\\b.*"))return "Инвазивные вмешательства";
        if(u.matches(".*\\d+МСУ\\b.*"))return "Медицинский сестринский уход";
        if(u.matches(".*\\d+МО\\b.*"))return "Медицинское оборудование";
        if(u.matches(".*СОП-\\d+-А-.*"))return "Лекарственная безопасность";

        if(any(t,"перелив","трансфуз","донорск","компонент донорской крови","криопрецип","свежезаморож","эритроцит","тромбоцит","иммуногематолог","реципиент","карантинизац","кроводач","регистрация донора","донаци"))
            return "Безопасность переливания крови";
        if(any(t,"дезинф","стерилиз","обеззараж","медицинск отход","отход класса","гигиена рук","генеральн уборк","эпидемиолог","санитарно-гигиен","противоэпидем","педикул","чесот","маляри","пба","микробиологический мониторинг","парентеральн инфекц","sars-cov","covid","паразитолог","дезинсек","анаэробн инфекц","обработка аппаратов искусственной вентиляции","обработка портативных аппаратов","обработка оборудования для узи"))
            return "Эпидемиологическая безопасность";
        if(any(t,"лабораторн","анализатор","иммунофермент","иммунологическ исследован","клинический анализ крови","копролог","микроскоп","гематологическ анализатор","коагулометр","забор материала для лабораторного","исследование антител","антиген гепатита"))
            return "Лабораторный процесс";
        if(any(t,"лекарственн препарат","лекарственн безопасност","отпуск лекар","хранен лекар","вербальн назначен","фармаконадзор","аптек","правила обращения с лп"))
            return "Лекарственная безопасность";
        if(any(t,"маршрутизац","перевод пациент","транспортировк пациент","передач пациент","профильного отделения","другое медицинское учреждение"))
            return "Преемственность";
        if(any(t,"операционн блок","хирургическ безопас","операционн поле","биопсийн","инородн тел","оперативн вмешательств","планов операц"))
            return "Хирургическая безопасность";
        if(any(t,"внутренн аудит","нежелательн событ","оформлен","регистрац соп","контроль качества и безопасности","анкетирован"))
            return "Управление качеством и безопасностью медицинской деятельности";
        if(any(t,"наставнич","допуск врач","врач-стаж","старшего врача","управление персонал"))
            return "Управление персоналом";
        if(any(t,"клиническ рекомендац","доказательн медицин"))
            return "Доказательная медицина";
        if(any(t,"идентификац пациент","идентификац личност"))
            return "Идентификация безопасной медицинской помощи";
        if(any(t,"сестринск менедж","старшая медицинская сестра"))
            return "Сестринский менеджмент";
        if(any(t,"подкожн инъекц","внутримышечн инъекц","венозн катетер","катетеризац","взятие крови","взятие мазк","инстилляц","санация трахеобронхиального"))
            return "Инвазивные вмешательства";
        if(any(t,"профилактика паден","памперс","уход за полостью рта","сестринск уход","подготовке пациента к плановой операции"))
            return "Медицинский сестринский уход";
        if(any(t,"эксплуатация","рентгенограф","флюорограф","рентгеноскоп","бария сульфат","экг","тонзиллор","механического поршневого дозатора"))
            return "Медицинское оборудование";
        if(any(t,"анафилакт","реанимац","сердечно-легочн","ушиб легк","ушиб сердц","стеноз горт","сочетанн травм","экзогенн интоксикац","противошок","злокачественн гипертерм","тромбоэмбол","перелом проксимального"))
            return "Экстренная медицинская помощь и приемное отделение";
        return OTHER;
    }

    static String keywords(String title,String category,String type){
        String n=norm(title+" "+category+" "+type);
        StringBuilder k=new StringBuilder(n);
        if(any(n,"тромбоэмбол","легочной артер"))k.append(" тэла");
        if(any(n,"сердечно-легочн","реанимац"))k.append(" слр");
        if(any(n,"коронарн"))k.append(" окс");
        if(any(n,"мозгов","инсульт"))k.append(" онмк");
        if(any(n,"желудочно-кишечн","кровотеч"))k.append(" жкк");
        if(any(n,"электрокарди","экг"))k.append(" экг");
        if(any(n,"искусственной вентиляции","ивл"))k.append(" ивл");
        if(any(n,"перелив","трансфуз","донорск","компонент крови"))k.append(" трансфузия донорская кровь");
        if("СОП".equals(type))k.append(" стандартная операционная процедура соп");
        return k.toString();
    }

    static boolean similarFileName(String file,String title){
        String f=norm(realTitle(file)),t=norm(title);
        return f.equals(t)||f.contains(t)||t.contains(f);
    }

    static String icon(String c){
        if(c.equals("Экстренная медицинская помощь и приемное отделение"))return "✚";
        if(c.equals("Безопасность переливания крови"))return "●";
        if(c.equals("Эпидемиологическая безопасность"))return "✣";
        if(c.equals("Преемственность"))return "↔";
        if(c.equals("Хирургическая безопасность"))return "✦";
        if(c.equals("Лекарственная безопасность"))return "Rx";
        if(c.equals("Управление персоналом"))return "◎";
        if(c.equals("Управление качеством и безопасностью медицинской деятельности"))return "✓";
        if(c.equals("Доказательная медицина"))return "Σ";
        if(c.equals("Идентификация безопасной медицинской помощи"))return "ID";
        if(c.equals("Сестринский менеджмент"))return "N";
        if(c.equals("Лабораторный процесс"))return "⌬";
        if(c.equals("Инвазивные вмешательства"))return "↧";
        if(c.equals("Медицинский сестринский уход"))return "♡";
        if(c.equals("Медицинское оборудование"))return "⚙";
        return "…";
    }

    static String norm(String s){
        return (s==null?"":s).toLowerCase(Locale.ROOT).replace('ё','е').replace('–','-').replace('—','-').replaceAll("\\s+"," ").trim();
    }
    private static boolean any(String s,String...parts){for(String p:parts)if(s.contains(p))return true;return false;}
}

class SopScanner {
    static List<SopDocument> scan(Context c) throws Exception{
        SharedPreferences p=c.getSharedPreferences("prefs",Context.MODE_PRIVATE);
        String raw=p.getString("tree_uri","");
        if(raw.isEmpty())throw new IllegalStateException("Папка Google Drive не подключена.");
        Uri tree=Uri.parse(raw);
        String parent=DocumentsContract.getTreeDocumentId(tree);
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent);
        String[] projection={
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE
        };
        ArrayList<SopDocument> out=new ArrayList<>();
        try(Cursor cur=c.getContentResolver().query(children,projection,null,null,null)){
            if(cur==null)throw new IllegalStateException("Google Drive не вернул список документов.");
            int iId=cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int iName=cur.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int iMime=cur.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int iMod=cur.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            int iSize=cur.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            while(cur.moveToNext()){
                String id=iId>=0?cur.getString(iId):null;
                String name=iName>=0?cur.getString(iName):null;
                String mime=iMime>=0?cur.getString(iMime):null;
                if(id==null||name==null||DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))continue;
                String low=name.toLowerCase(Locale.ROOT);
                if(!(low.endsWith(".pdf")||low.endsWith(".doc")||low.endsWith(".docx")||
                        "application/pdf".equals(mime)||"application/msword".equals(mime)||
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime)))continue;
                long mod=iMod>=0&&!cur.isNull(iMod)?cur.getLong(iMod):0L;
                long size=iSize>=0&&!cur.isNull(iSize)?cur.getLong(iSize):0L;
                Uri doc=DocumentsContract.buildDocumentUriUsingTree(tree,id);
                String title=SopClassifier.realTitle(name);
                String type=SopClassifier.type(name,title);
                String category=SopClassifier.category(name,title);
                String keywords=SopClassifier.keywords(title,category,type);
                out.add(new SopDocument(id,name,title,category,type,keywords,doc.toString(),mime,mod,size));
            }
        }
        out.sort(Comparator.comparing(d->d.title.toLowerCase(Locale.ROOT)));
        return out;
    }
}

class SopDbHelper extends SQLiteOpenHelper {
    private static final String DB="sop_navigator.db";
    SopDbHelper(Context c){super(c,DB,null,1);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE docs(doc_key TEXT PRIMARY KEY,file_name TEXT NOT NULL,title TEXT NOT NULL,category TEXT NOT NULL,type TEXT NOT NULL,keywords TEXT NOT NULL,uri TEXT NOT NULL,mime TEXT,last_modified INTEGER NOT NULL DEFAULT 0,size INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE changes(id INTEGER PRIMARY KEY AUTOINCREMENT,event_type TEXT NOT NULL,doc_key TEXT NOT NULL,title TEXT NOT NULL,changed_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE history(doc_key TEXT PRIMARY KEY,viewed_at INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int o,int n){}

    int count(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM docs",null)){c.moveToFirst();return c.getInt(0);}}
    SopDocument get(String key){
        try(Cursor c=getReadableDatabase().query("docs",null,"doc_key=?",new String[]{key},null,null,null)){
            return c.moveToFirst()?from(c):null;
        }
    }
    void upsert(SopDocument d){
        ContentValues v=new ContentValues();
        v.put("doc_key",d.key);v.put("file_name",d.fileName);v.put("title",d.title);v.put("category",d.category);
        v.put("type",d.type);v.put("keywords",d.keywords);v.put("uri",d.uri);v.put("mime",d.mime);
        v.put("last_modified",d.lastModified);v.put("size",d.size);
        getWritableDatabase().insertWithOnConflict("docs",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    List<SopDocument> all(){
        ArrayList<SopDocument> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("docs",null,null,null,null,null,"title COLLATE NOCASE")){
            while(c.moveToNext())out.add(from(c));
        }
        return out;
    }
    void recordChange(String type,SopDocument d){
        ContentValues v=new ContentValues();v.put("event_type",type);v.put("doc_key",d.key);v.put("title",d.title);v.put("changed_at",System.currentTimeMillis());
        getWritableDatabase().insert("changes",null,v);
    }
    static final class ChangeEvent{
        final String type,key,title;final long changedAt;
        ChangeEvent(String type,String key,String title,long changedAt){this.type=type;this.key=key;this.title=title;this.changedAt=changedAt;}
    }
    List<ChangeEvent> recentChangesWithinHours(int hours,int limit){
        long since=System.currentTimeMillis()-hours*60L*60L*1000L;
        ArrayList<ChangeEvent> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("changes",new String[]{"event_type","doc_key","title","changed_at"},
                "changed_at>=?",new String[]{Long.toString(since)},null,null,"changed_at DESC",Integer.toString(limit))){
            while(c.moveToNext())out.add(new ChangeEvent(c.getString(0),c.getString(1),c.getString(2),c.getLong(3)));
        }
        return out;
    }
    void markViewed(String key){
        ContentValues v=new ContentValues();v.put("doc_key",key);v.put("viewed_at",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("history",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    List<SopDocument> history(int limit){
        ArrayList<SopDocument> out=new ArrayList<>();
        String sql="SELECT d.* FROM history h JOIN docs d ON d.doc_key=h.doc_key ORDER BY h.viewed_at DESC LIMIT ?";
        try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{Integer.toString(limit)})){
            while(c.moveToNext())out.add(from(c));
        }
        return out;
    }
    void clearHistory(){getWritableDatabase().delete("history",null,null);}
    void clearAll(){
        SQLiteDatabase d=getWritableDatabase();d.delete("history",null,null);d.delete("changes",null,null);d.delete("docs",null,null);
    }
    private SopDocument from(Cursor c){
        return new SopDocument(
                c.getString(c.getColumnIndexOrThrow("doc_key")),
                c.getString(c.getColumnIndexOrThrow("file_name")),
                c.getString(c.getColumnIndexOrThrow("title")),
                c.getString(c.getColumnIndexOrThrow("category")),
                c.getString(c.getColumnIndexOrThrow("type")),
                c.getString(c.getColumnIndexOrThrow("keywords")),
                c.getString(c.getColumnIndexOrThrow("uri")),
                c.getString(c.getColumnIndexOrThrow("mime")),
                c.getLong(c.getColumnIndexOrThrow("last_modified")),
                c.getLong(c.getColumnIndexOrThrow("size")));
    }
}

class SopSyncEngine {
    static final class Result{
        final int added,updated,total;final String message;
        Result(int a,int u,int total,String m){added=a;updated=u;this.total=total;message=m;}
    }
    static Result sync(Context c){
        try{
            List<SopDocument> scanned=SopScanner.scan(c);
            SopDbHelper db=new SopDbHelper(c);
            SharedPreferences p=c.getSharedPreferences("prefs",Context.MODE_PRIVATE);
            boolean baseline=!p.getBoolean("baseline_done",false);
            int added=0,updated=0;
            ArrayList<SopDocument> changed=new ArrayList<>();
            for(SopDocument d:scanned){
                SopDocument old=db.get(d.key);
                if(old==null){
                    db.upsert(d);
                    if(!baseline){db.recordChange("NEW",d);added++;changed.add(d);}
                }else{
                    boolean modified=(d.lastModified>0&&old.lastModified>0&&d.lastModified!=old.lastModified)||
                            (d.size>0&&old.size>0&&d.size!=old.size);
                    db.upsert(d);
                    if(!baseline&&modified){db.recordChange("UPDATED",d);updated++;changed.add(d);}
                }
            }
            long now=System.currentTimeMillis();
            p.edit().putBoolean("baseline_done",true).putLong("last_sync_ms",now)
                    .putString("last_sync_text",new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date(now))).apply();
            if(!baseline&&added+updated>0)notifyChanges(c,added,updated,changed);
            db.close();
            if(baseline)return new Result(0,0,scanned.size(),"Папка подключена. В каталог добавлено "+scanned.size()+" документов.");
            if(added==0&&updated==0)return new Result(0,0,scanned.size(),"Новых или обновлённых документов не найдено.");
            return new Result(added,updated,scanned.size(),"Проверка завершена. Новых: "+added+", обновлено: "+updated+".");
        }catch(Exception e){
            String m=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            return new Result(0,0,0,"Не удалось проверить папку Google Drive.\n"+m);
        }
    }

    private static void notifyChanges(Context c,int added,int updated,List<SopDocument> changed){
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel("sop_updates","Обновления СОПов",NotificationManager.IMPORTANCE_DEFAULT));
        Intent intent=new Intent(c,SopMainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(c,0,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        String body="Новых: "+added+", обновлено: "+updated;
        if(!changed.isEmpty())body=(added>0?"Новый документ: ":"Обновлён документ: ")+changed.get(0).title+(changed.size()>1?" и ещё "+(changed.size()-1):"");
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,"sop_updates"):new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Изменения в СОП Навигаторе")
                .setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body)).setContentIntent(pi).setAutoCancel(true);
        try{nm.notify(2101,b.build());}catch(SecurityException ignored){}
    }
}

class SopAlarmScheduler {
    static long nextWeekday7(){
        Calendar now=Calendar.getInstance(),next=(Calendar)now.clone();
        next.set(Calendar.HOUR_OF_DAY,7);next.set(Calendar.MINUTE,0);next.set(Calendar.SECOND,0);next.set(Calendar.MILLISECOND,0);
        int dow=next.get(Calendar.DAY_OF_WEEK);
        boolean weekday=dow>=Calendar.MONDAY&&dow<=Calendar.FRIDAY;
        if(!weekday||!next.after(now))next.add(Calendar.DAY_OF_MONTH,1);
        while(next.get(Calendar.DAY_OF_WEEK)==Calendar.SATURDAY||next.get(Calendar.DAY_OF_WEEK)==Calendar.SUNDAY)next.add(Calendar.DAY_OF_MONTH,1);
        next.set(Calendar.HOUR_OF_DAY,7);next.set(Calendar.MINUTE,0);next.set(Calendar.SECOND,0);next.set(Calendar.MILLISECOND,0);
        return next.getTimeInMillis();
    }
    static void scheduleNext(Context c){
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        Intent i=new Intent(c,SopAlarmReceiver.class);
        PendingIntent pi=PendingIntent.getBroadcast(c,2710,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        long when=nextWeekday7();
        if(Build.VERSION.SDK_INT>=31&&!am.canScheduleExactAlarms())am.setWindow(AlarmManager.RTC_WAKEUP,when,15*60*1000L,pi);
        else if(Build.VERSION.SDK_INT>=23)am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);
        else am.setExact(AlarmManager.RTC_WAKEUP,when,pi);
        c.getSharedPreferences("prefs",Context.MODE_PRIVATE).edit().putLong("next_alarm",when).apply();
    }
}
