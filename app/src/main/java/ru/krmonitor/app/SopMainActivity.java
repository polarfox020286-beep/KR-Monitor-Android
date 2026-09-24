package ru.krmonitor.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.ContentObserver;
import android.database.sqlite.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.*;

import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class SopMainActivity extends Activity {
    public static final String ACTION_SYNC_COMPLETE="ru.sopnavigator.app.SYNC_COMPLETE";
    private static final int REQ_TREE=44;
    private static final int REQ_WRITE_DOWNLOADS=4402;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final BroadcastReceiver syncReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(ACTION_SYNC_COMPLETE.equals(intent.getAction())) reload();
        }
    };

    private SopDbHelper db;
    private SopDocument pendingDownload;
    private TextView status,source,recentList;
    private EditText search;
    private String personnelFilter=null;
    private final LinkedHashMap<String,TextView> personnelChipViews=new LinkedHashMap<>();
    private FrameLayout contentHost;
    private LinearLayout bottomNav;
    private TextView navAll,navGroups,navHistory;
    private List<SopDocument> all=new ArrayList<>();

    private static final int PAGE_ALL=0;
    private static final int PAGE_GROUPS=1;
    private static final int PAGE_HISTORY=2;
    private int currentPage=PAGE_GROUPS;
    private String selectedCategory=null;
    private float swipeX,swipeY;
    private boolean swipeTracking=false;

    private static final int BG=Color.rgb(244,249,252);
    private static final int CARD=Color.WHITE;
    private static final int BLUE=Color.rgb(21,132,224);
    private static final int BLUE_DARK=Color.rgb(10,71,146);
    private static final int CYAN=Color.rgb(48,198,231);
    private static final int BLUE_SOFT=Color.rgb(232,246,252);
    private static final int BLUE_PALE=Color.rgb(241,250,253);
    private static final int TEXT=Color.rgb(22,49,73);
    private static final int MUTED=Color.rgb(101,121,139);
    private static final int LINE=Color.rgb(218,232,240);
    private static final int WARN=Color.rgb(178,103,24);
    private static final int WARN_BG=Color.rgb(255,247,234);
    private static final int SUCCESS=Color.rgb(26,140,112);
    private static final int SUCCESS_BG=Color.rgb(235,249,245);

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

    private GradientDrawable gradient(int left,int right,int radius){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{left,right});
        d.setCornerRadius(dp(radius));
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
        root.setPadding(dp(14),dp(8),dp(14),dp(7));
        root.setBackgroundColor(BG);

        LinearLayout hero=new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(11),dp(8),dp(11),dp(8));
        hero.setBackground(gradient(Color.rgb(239,251,255),Color.rgb(225,243,252),20));
        if(Build.VERSION.SDK_INT>=21)hero.setElevation(dp(1));

        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView mark=text("➤",17,Color.WHITE,true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(BLUE,Color.TRANSPARENT,12));
        LinearLayout.LayoutParams markLp=new LinearLayout.LayoutParams(dp(36),dp(36));
        markLp.setMargins(0,0,dp(9),0);
        header.addView(mark,markLp);

        LinearLayout heading=new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title=text("СОП Навигатор",20,BLUE_DARK,true);
        title.setLetterSpacing(0.01f);
        TextView subtitle=text("Елизаветинская больница · локальные документы",10.2f,MUTED,false);
        subtitle.setPadding(0,dp(1),0,0);
        heading.addView(title);
        heading.addView(subtitle);
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));

        TextView sync=text("↻",20,BLUE_DARK,true);
        sync.setGravity(Gravity.CENTER);
        sync.setContentDescription("Проверить обновления");
        sync.setBackground(rounded(Color.WHITE,LINE,12));
        sync.setClickable(true); sync.setFocusable(true);
        header.addView(sync,new LinearLayout.LayoutParams(dp(36),dp(36)));
        hero.addView(header);

        status=text("",10.2f,MUTED,false);
        status.setLineSpacing(0,0.98f);
        status.setPadding(dp(1),dp(6),dp(1),dp(1));
        hero.addView(status);

        source=text("",10.3f,BLUE_DARK,true);
        source.setPadding(dp(8),dp(5),dp(8),dp(5));
        source.setGravity(Gravity.CENTER_VERTICAL);
        source.setClickable(true); source.setFocusable(true);
        LinearLayout.LayoutParams sourceLp=new LinearLayout.LayoutParams(-1,-2);
        sourceLp.setMargins(0,dp(2),0,0);
        hero.addView(source,sourceLp);

        LinearLayout.LayoutParams heroLp=new LinearLayout.LayoutParams(-1,-2);
        heroLp.setMargins(0,0,0,dp(6));
        root.addView(hero,heroLp);

        LinearLayout updates=new LinearLayout(this);
        updates.setOrientation(LinearLayout.HORIZONTAL);
        updates.setBackground(rounded(CARD,LINE,18));
        if(Build.VERSION.SDK_INT>=21)updates.setElevation(dp(1));

        View accent=new View(this);
        accent.setBackground(rounded(CYAN,Color.TRANSPARENT,3));
        LinearLayout.LayoutParams accentLp=new LinearLayout.LayoutParams(dp(4),-1);
        accentLp.setMargins(dp(7),dp(7),dp(9),dp(7));
        updates.addView(accent,accentLp);

        LinearLayout updateBody=new LinearLayout(this);
        updateBody.setOrientation(LinearLayout.VERTICAL);
        updateBody.setPadding(0,dp(7),dp(10),dp(7));

        LinearLayout recentHeader=new LinearLayout(this);
        recentHeader.setOrientation(LinearLayout.HORIZONTAL);
        recentHeader.setGravity(Gravity.CENTER_VERTICAL);
        recentHeader.addView(text("Обновления",14.5f,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=text("48 часов",10,BLUE_DARK,true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(8),dp(3),dp(8),dp(3));
        badge.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,11));
        recentHeader.addView(badge);
        updateBody.addView(recentHeader,new LinearLayout.LayoutParams(-1,dp(28)));

        ScrollView recentScroll=new ScrollView(this);
        recentScroll.setFillViewport(true);
        recentScroll.setVerticalScrollBarEnabled(true);
        recentScroll.setScrollbarFadingEnabled(true);
        recentScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        recentList=text("",11.5f,TEXT,false);
        recentList.setLineSpacing(dp(1),1.02f);
        recentList.setPadding(0,dp(4),dp(5),dp(3));
        recentScroll.addView(recentList,new ScrollView.LayoutParams(-1,-2));
        updateBody.addView(recentScroll,new LinearLayout.LayoutParams(-1,0,1));

        updates.addView(updateBody,new LinearLayout.LayoutParams(0,-1,1));

        LinearLayout.LayoutParams updatesLp=new LinearLayout.LayoutParams(-1,dp(82));
        updatesLp.setMargins(0,0,0,dp(8));
        root.addView(updates,updatesLp);

        LinearLayout searchBox=new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(8),0,dp(6),0);
        searchBox.setBackground(rounded(CARD,LINE,17));
        if(Build.VERSION.SDK_INT>=21)searchBox.setElevation(dp(1));

        TextView searchIcon=text("⌕",19,BLUE,false);
        searchIcon.setGravity(Gravity.CENTER);
        searchBox.addView(searchIcon,new LinearLayout.LayoutParams(dp(26),dp(44)));

        search=new EditText(this);
        search.setHint("Поиск по названию, разделу и ключевому слову");
        search.setHintTextColor(Color.rgb(139,156,169));
        search.setTextColor(TEXT);
        search.setTextSize(12.2f);
        search.setSingleLine(true);
        search.setPadding(dp(2),0,dp(2),0);
        search.setBackgroundColor(Color.TRANSPARENT);
        searchBox.addView(search,new LinearLayout.LayoutParams(0,dp(44),1));
        root.addView(searchBox,new LinearLayout.LayoutParams(-1,dp(46)));

        HorizontalScrollView personnelScroll=new HorizontalScrollView(this);
        personnelScroll.setHorizontalScrollBarEnabled(false);
        personnelScroll.setFillViewport(false);
        LinearLayout personnelRow=new LinearLayout(this);
        personnelRow.setOrientation(LinearLayout.HORIZONTAL);
        personnelRow.setGravity(Gravity.CENTER_VERTICAL);
        personnelRow.setPadding(0,dp(5),dp(4),dp(1));

        String[] personnelOptions={
                "Врачи",
                "Средний медицинский персонал",
                "Младший медицинский персонал",
                "Немедицинский персонал",
                "Весь персонал"
        };
        for(String option:personnelOptions){
            TextView chip=personnelChip(option);
            personnelChipViews.put(option,chip);
            personnelRow.addView(chip);
        }
        personnelScroll.addView(personnelRow,new HorizontalScrollView.LayoutParams(-2,dp(39)));
        root.addView(personnelScroll,new LinearLayout.LayoutParams(-1,dp(42)));

        contentHost=new FrameLayout(this);
        LinearLayout.LayoutParams contentLp=new LinearLayout.LayoutParams(-1,0,1);
        contentLp.setMargins(0,dp(6),0,dp(7));
        root.addView(contentHost,contentLp);

        bottomNav=new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setPadding(dp(4),dp(4),dp(4),dp(4));
        bottomNav.setBackground(rounded(CARD,LINE,18));
        if(Build.VERSION.SDK_INT>=21)bottomNav.setElevation(dp(2));

        navAll=navButton("Все");
        navGroups=navButton("Разделы");
        navHistory=navButton("История");
        bottomNav.addView(navAll,new LinearLayout.LayoutParams(0,dp(43),1));
        bottomNav.addView(navGroups,new LinearLayout.LayoutParams(0,dp(43),1));
        bottomNav.addView(navHistory,new LinearLayout.LayoutParams(0,dp(43),1));
        root.addView(bottomNav,new LinearLayout.LayoutParams(-1,dp(51)));

        setContentView(root);

        sync.setOnClickListener(v -> {
            if(!hasFolder()) chooseFolder();
            else runSync(sync);
        });
        source.setOnClickListener(v -> chooseFolder());

        navAll.setOnClickListener(v->{personnelFilter=null;updatePersonnelChips();selectedCategory=null;currentPage=PAGE_ALL;renderCurrentPage(0);});
        navGroups.setOnClickListener(v->{personnelFilter=null;updatePersonnelChips();selectedCategory=null;currentPage=PAGE_GROUPS;renderCurrentPage(0);});
        navHistory.setOnClickListener(v->{personnelFilter=null;updatePersonnelChips();selectedCategory=null;currentPage=PAGE_HISTORY;renderCurrentPage(0);});

        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int b,int c){
                applySearchFilters();
            }
            public void afterTextChanged(Editable e){}
        });
    }

    private TextView navButton(String label){
        TextView t=text(label,12.5f,MUTED,true);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);t.setFocusable(true);
        t.setBackground(rounded(Color.TRANSPARENT,Color.TRANSPARENT,14));
        return t;
    }

    private TextView personnelChip(String label){
        TextView t=text(label,11.2f,BLUE_DARK,true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(12),0,dp(12),0);
        t.setBackground(rounded(BLUE_PALE,LINE,14));
        t.setClickable(true);
        t.setFocusable(true);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(32));
        lp.setMargins(0,0,dp(6),0);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            personnelFilter=label.equals(personnelFilter)?null:label;
            selectedCategory=null;
            currentPage=PAGE_ALL;
            updatePersonnelChips();
            applySearchFilters();
        });
        return t;
    }

    private void updatePersonnelChips(){
        for(Map.Entry<String,TextView> e:personnelChipViews.entrySet()){
            boolean active=e.getKey().equals(personnelFilter);
            TextView t=e.getValue();
            t.setTextColor(active?Color.WHITE:BLUE_DARK);
            t.setBackground(active?gradient(BLUE,CYAN,14):rounded(BLUE_PALE,LINE,14));
        }
    }

    private void applySearchFilters(){
        if(search==null)return;
        String q=search.getText().toString().trim();
        if(q.isEmpty()&&personnelFilter==null){
            renderCurrentPage(0);
        }else{
            renderSearch(q);
        }
    }

    private void updateBottomNav(){
        if(navAll==null)return;
        TextView[] items={navAll,navGroups,navHistory};
        int[] pages={PAGE_ALL,PAGE_GROUPS,PAGE_HISTORY};
        for(int i=0;i<items.length;i++){
            boolean active=currentPage==pages[i] && selectedCategory==null;
            items[i].setTextColor(active?BLUE_DARK:MUTED);
            items[i].setBackground(rounded(active?BLUE_SOFT:Color.TRANSPARENT,Color.TRANSPARENT,14));
        }
        if(selectedCategory!=null){
            navGroups.setTextColor(BLUE_DARK);
            navGroups.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,14));
        }
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
        if(button!=null){
            button.setEnabled(false);
            button.setText("…");
            button.setAlpha(0.62f);
        }
        status.setText("Проверяю папку Google Drive…");
        executor.submit(() -> {
            SopSyncEngine.Result result=SopSyncEngine.sync(getApplicationContext());
            runOnUiThread(() -> {
                if(button!=null){
                    button.setEnabled(true);
                    button.setText("↻");
                    button.setAlpha(1f);
                }
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
        status.setText(all.size()+" документов  ·  Проверено: "+lastText+"\nСледующая проверка: "+
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(next))+note);
        if(hasFolder()){
            source.setText("●  Google Drive подключён    Изменить папку ›");
            source.setTextColor(SUCCESS);
            source.setBackground(rounded(SUCCESS_BG,Color.TRANSPARENT,12));
        }else{
            source.setText("＋  Подключить папку СОПов на Google Drive");
            source.setTextColor(WARN);
            source.setBackground(rounded(WARN_BG,Color.TRANSPARENT,12));
        }
        renderRecent();
        applySearchFilters();
    }

    private void renderRecent(){
        List<SopDbHelper.ChangeEvent> recent=db.recentChangesWithinHours(48,0);
        if(recent.isEmpty()){
            recentList.setText("✓  Новых или обновлённых документов нет");
            recentList.setTextColor(SUCCESS);
            return;
        }
        recentList.setTextColor(TEXT);
        StringBuilder sb=new StringBuilder();
        for(int i=0;i<recent.size();i++){
            SopDbHelper.ChangeEvent e=recent.get(i);
            if(i>0)sb.append("\n\n");
            sb.append("NEW".equals(e.type)?"НОВЫЙ  ":"ОБНОВЛЁН  ").append(e.title);
        }
        recentList.setText(sb.toString());
    }

    private void renderCurrentPage(int direction){
        if(contentHost==null)return;
        updateBottomNav();
        if(currentPage==PAGE_ALL)showContent(makeListPage("Все документы",all,true),direction);
        else if(currentPage==PAGE_HISTORY)showContent(makeHistoryPage(),direction);
        else if(selectedCategory!=null)showContent(makeCategoryListPage(selectedCategory),direction);
        else showContent(makeCategoriesPage(),direction);
    }

    private View makeCategoriesPage(){
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout pageHead=new LinearLayout(this);
        pageHead.setOrientation(LinearLayout.HORIZONTAL);
        pageHead.setGravity(Gravity.CENTER_VERTICAL);
        pageHead.addView(pageTitle("Разделы"),new LinearLayout.LayoutParams(0,-2,1));
        TextView total=text(all.size()+" документов",11,MUTED,false);
        total.setPadding(dp(8),dp(4),dp(8),dp(4));
        total.setBackground(rounded(BLUE_PALE,Color.TRANSPARENT,11));
        pageHead.addView(total);
        outer.addView(pageHead);

        TextView intro=text("Выберите направление СМК",11.5f,MUTED,false);
        intro.setPadding(dp(2),0,0,dp(4));
        outer.addView(intro);

        Map<String,List<SopDocument>> groups=group(all);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout rows=new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0,dp(1),0,dp(10));
        scroll.addView(rows,new ScrollView.LayoutParams(-1,-2));

        ArrayList<String> visible=new ArrayList<>();
        for(String category:SopClassifier.CATEGORIES){
            List<SopDocument> docs=groups.get(category);
            if(docs!=null&&!docs.isEmpty())visible.add(category);
        }
        for(int i=0;i<visible.size();i+=2){
            LinearLayout row=new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for(int j=0;j<2;j++){
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(120),1);
                if(j==0)lp.setMargins(0,dp(4),dp(4),dp(4)); else lp.setMargins(dp(4),dp(4),0,dp(4));
                if(i+j<visible.size()){
                    String category=visible.get(i+j);
                    row.addView(categoryCard(category,groups.get(category).size()),lp);
                }else row.addView(new Space(this),lp);
            }
            rows.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
        outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        return outer;
    }

    private View categoryCard(String category,int count){
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(11),dp(10),dp(11),dp(9));
        card.setBackground(rounded(CARD,LINE,18));
        if(Build.VERSION.SDK_INT>=21)card.setElevation(dp(1));
        card.setClickable(true); card.setFocusable(true);

        LinearLayout top=new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        CategoryIconView icon=new CategoryIconView(category);
        icon.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,13));
        top.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));

        TextView number=text(count+"",11,BLUE_DARK,true);
        number.setGravity(Gravity.CENTER);
        number.setBackground(rounded(Color.rgb(240,249,253),Color.TRANSPARENT,12));
        LinearLayout.LayoutParams nlp=new LinearLayout.LayoutParams(dp(34),dp(28));
        nlp.setMargins(dp(7),0,0,0);
        top.addView(new Space(this),new LinearLayout.LayoutParams(0,1,1));
        top.addView(number,nlp);
        card.addView(top);

        TextView name=text(category.replace("-","‑"),12.4f,TEXT,true);
        name.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
        name.setMaxLines(3);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setLineSpacing(dp(1),1f);
        name.setPadding(0,dp(7),0,0);
        card.addView(name,new LinearLayout.LayoutParams(-1,0,1));

        card.setOnClickListener(v->{selectedCategory=category;currentPage=PAGE_GROUPS;renderCurrentPage(0);});
        return card;
    }

    private class CategoryIconView extends View {
        private final String category;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path=new Path();
        private final RectF rect=new RectF();
        private final int red=Color.rgb(208,62,76);

        CategoryIconView(String category){
            super(SopMainActivity.this);
            this.category=category==null?"":category;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float scale=Math.min(getWidth(),getHeight())/100f;
            if(scale<=0)return;
            canvas.save();
            canvas.translate((getWidth()-100f*scale)/2f,(getHeight()-100f*scale)/2f);
            canvas.scale(scale,scale);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5.5f);
            paint.setColor(BLUE_DARK);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            String k=category.toLowerCase(Locale.ROOT);
            if(k.contains("переливания крови")) drawBloodShield(canvas);
            else if(k.contains("преемствен")) drawContinuity(canvas);
            else if(k.contains("лекарствен")) drawPillShield(canvas);
            else if(k.contains("эпидемиолог")) drawMicrobeShield(canvas);
            else if(k.contains("экстренн")) drawEmergency(canvas);
            else if(k.contains("приемное отделение")) drawAdmission(canvas);
            else if(k.contains("доказательн")) drawEvidence(canvas);
            else if(k.contains("хирургическ")) drawScalpelShield(canvas);
            else if(k.contains("идентификац")) drawIdentity(canvas);
            else if(k.contains("паден")) drawFall(canvas);
            else if(k.contains("лаборатор")) drawTestTube(canvas);
            else if(k.contains("управление персоналом")) drawPersonnel(canvas);
            else if(k.contains("управление качеством")) drawQuality(canvas);
            else if(k.contains("сестринский менеджмент")) drawNurse(canvas);
            else if(k.contains("инвазивные")) drawSyringe(canvas);
            else if(k.contains("сестринский уход")) drawCare(canvas);
            else if(k.contains("оборудование")) drawEquipment(canvas);
            else drawFolder(canvas);
            canvas.restore();
        }

        private void shield(Canvas c,float x,float y,float w,float h){
            path.reset();
            path.moveTo(x+w*0.50f,y);
            path.lineTo(x+w*0.90f,y+h*0.16f);
            path.lineTo(x+w*0.84f,y+h*0.63f);
            path.quadTo(x+w*0.72f,y+h*0.88f,x+w*0.50f,y+h);
            path.quadTo(x+w*0.28f,y+h*0.88f,x+w*0.16f,y+h*0.63f);
            path.lineTo(x+w*0.10f,y+h*0.16f);
            path.close();
            c.drawPath(path,paint);
        }

        private void check(Canvas c,float x,float y,float s){
            path.reset();
            path.moveTo(x,y+s*0.48f);
            path.lineTo(x+s*0.34f,y+s*0.82f);
            path.lineTo(x+s,y);
            c.drawPath(path,paint);
        }

        private void cross(Canvas c,float cx,float cy,float s){
            float old=paint.getStrokeWidth();
            paint.setStrokeWidth(6.5f);
            c.drawLine(cx-s,cy,cx+s,cy,paint);
            c.drawLine(cx,cy-s,cx,cy+s,paint);
            paint.setStrokeWidth(old);
        }

        private void drawBloodShield(Canvas c){
            paint.setColor(red);
            path.reset();
            path.moveTo(28,18);
            path.cubicTo(22,31,14,39,14,52);
            path.cubicTo(14,66,24,75,36,75);
            path.cubicTo(48,75,57,66,57,53);
            path.cubicTo(57,40,46,30,28,18);
            path.close();
            c.drawPath(path,paint);
            paint.setColor(BLUE_DARK);
            shield(c,54,25,34,50);
            check(c,63,45,16);
        }

        private void drawContinuity(Canvas c){
            paint.setStrokeWidth(6f);
            c.drawLine(18,36,72,36,paint);
            path.reset(); path.moveTo(72,36);path.lineTo(62,27);path.moveTo(72,36);path.lineTo(62,45);c.drawPath(path,paint);
            c.drawLine(82,64,28,64,paint);
            path.reset(); path.moveTo(28,64);path.lineTo(38,55);path.moveTo(28,64);path.lineTo(38,73);c.drawPath(path,paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(CYAN); c.drawCircle(18,36,5,paint); c.drawCircle(82,64,5,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setColor(BLUE_DARK);
        }

        private void drawPillShield(Canvas c){
            canvasCapsule(c,12,30,48,30,-28f);
            shield(c,55,23,34,52);
            check(c,64,44,15);
        }

        private void canvasCapsule(Canvas c,float x,float y,float w,float h,float angle){
            c.save();
            c.rotate(angle,x+w/2f,y+h/2f);
            rect.set(x,y,x+w,y+h);
            c.drawRoundRect(rect,h/2f,h/2f,paint);
            c.drawLine(x+w/2f,y+2,x+w/2f,y+h-2,paint);
            c.restore();
        }

        private void drawMicrobeShield(Canvas c){
            shield(c,44,18,46,64);
            paint.setColor(red);
            c.drawCircle(36,50,13,paint);
            for(int i=0;i<8;i++){
                double a=Math.PI*2*i/8.0;
                float x1=(float)(36+13*Math.cos(a)),y1=(float)(50+13*Math.sin(a));
                float x2=(float)(36+19*Math.cos(a)),y2=(float)(50+19*Math.sin(a));
                c.drawLine(x1,y1,x2,y2,paint);
            }
            c.drawCircle(31,46,2.5f,paint);c.drawCircle(41,54,2.5f,paint);
            paint.setStrokeWidth(7f);
            c.drawLine(20,72,51,28,paint);
            paint.setStrokeWidth(5.5f);
            paint.setColor(BLUE_DARK);
        }

        private void drawEmergency(Canvas c){
            paint.setColor(CYAN);
            path.reset();
            path.moveTo(34,13);path.lineTo(17,53);path.lineTo(34,53);path.lineTo(25,86);path.lineTo(58,43);path.lineTo(40,43);path.close();
            c.drawPath(path,paint);
            paint.setColor(BLUE_DARK);
            cross(c,72,53,14);
        }

        private void drawAdmission(Canvas c){
            rect.set(17,20,60,82);c.drawRoundRect(rect,3,3,paint);
            c.drawCircle(52,53,2.5f,paint);
            cross(c,77,48,11);
            c.drawLine(62,75,88,75,paint);
        }

        private void drawEvidence(Canvas c){
            path.reset();
            path.moveTo(14,24);path.quadTo(31,18,45,28);path.lineTo(45,78);path.quadTo(30,68,14,74);path.close();c.drawPath(path,paint);
            path.reset();
            path.moveTo(86,24);path.quadTo(69,18,55,28);path.lineTo(55,78);path.quadTo(70,68,86,74);path.close();c.drawPath(path,paint);
            paint.setColor(SUCCESS);
            check(c,60,45,20);
            paint.setColor(BLUE_DARK);
        }

        private void drawScalpelShield(Canvas c){
            c.save();c.rotate(-33,34,52);
            rect.set(10,46,50,58);c.drawRoundRect(rect,5,5,paint);
            path.reset();path.moveTo(50,46);path.lineTo(68,52);path.lineTo(50,58);path.close();c.drawPath(path,paint);
            c.restore();
            shield(c,54,22,35,55);
            check(c,63,44,15);
        }

        private void drawIdentity(Canvas c){
            rect.set(14,20,70,80);c.drawRoundRect(rect,8,8,paint);
            c.drawLine(30,20,30,13,paint);c.drawLine(54,20,54,13,paint);
            c.drawCircle(34,43,9,paint);
            path.reset();path.moveTo(20,67);path.quadTo(34,55,48,67);c.drawPath(path,paint);
            paint.setColor(SUCCESS);check(c,59,52,22);paint.setColor(BLUE_DARK);
        }

        private void drawFall(Canvas c){
            paint.setColor(WARN);
            path.reset();path.moveTo(70,17);path.lineTo(92,78);path.lineTo(48,78);path.close();c.drawPath(path,paint);
            c.drawLine(70,38,70,58,paint);c.drawCircle(70,68,2.5f,paint);
            paint.setColor(BLUE_DARK);
            c.drawCircle(24,31,7,paint);
            c.drawLine(28,39,42,52,paint);c.drawLine(42,52,31,69,paint);
            c.drawLine(38,48,52,40,paint);c.drawLine(32,48,17,57,paint);
            c.drawLine(31,69,18,79,paint);c.drawLine(31,69,46,79,paint);
        }

        private void drawTestTube(Canvas c){
            path.reset();
            path.moveTo(31,17);path.lineTo(69,17);path.moveTo(38,17);path.lineTo(38,63);
            path.quadTo(38,82,50,84);path.quadTo(62,82,62,63);path.lineTo(62,17);c.drawPath(path,paint);
            paint.setColor(CYAN);
            c.drawLine(39,58,61,58,paint);c.drawCircle(46,67,3,paint);c.drawCircle(55,74,2.5f,paint);
            paint.setColor(BLUE_DARK);
        }

        private void drawPersonnel(Canvas c){
            c.drawCircle(36,34,10,paint);c.drawCircle(65,38,8,paint);
            path.reset();path.moveTo(18,72);path.quadTo(36,52,54,72);c.drawPath(path,paint);
            path.reset();path.moveTo(51,72);path.quadTo(65,56,81,72);c.drawPath(path,paint);
        }

        private void drawQuality(Canvas c){
            rect.set(20,20,72,82);c.drawRoundRect(rect,7,7,paint);
            rect.set(33,14,59,27);c.drawRoundRect(rect,5,5,paint);
            c.drawLine(31,42,61,42,paint);c.drawLine(31,54,54,54,paint);
            paint.setColor(SUCCESS);check(c,54,59,23);paint.setColor(BLUE_DARK);
        }

        private void drawNurse(Canvas c){
            c.drawCircle(50,45,16,paint);
            path.reset();path.moveTo(27,78);path.quadTo(50,61,73,78);c.drawPath(path,paint);
            path.reset();path.moveTo(36,29);path.lineTo(40,18);path.lineTo(60,18);path.lineTo(64,29);c.drawPath(path,paint);
            cross(c,50,24,5);
        }

        private void drawSyringe(Canvas c){
            c.save();c.rotate(-35,50,50);
            rect.set(27,39,69,58);c.drawRect(rect,paint);
            c.drawLine(35,39,35,58,paint);c.drawLine(69,48,88,48,paint);
            c.drawLine(88,48,94,48,paint);c.drawLine(20,48,27,48,paint);
            c.drawLine(18,37,18,59,paint);c.drawLine(18,48,27,48,paint);
            c.restore();
        }

        private void drawCare(Canvas c){
            paint.setColor(red);
            path.reset();path.moveTo(50,62);path.cubicTo(25,47,25,27,39,27);path.cubicTo(47,27,50,34,50,34);path.cubicTo(50,34,53,27,61,27);path.cubicTo(75,27,75,47,50,62);c.drawPath(path,paint);
            paint.setColor(BLUE_DARK);
            path.reset();path.moveTo(18,72);path.quadTo(38,58,50,66);path.quadTo(63,58,82,72);c.drawPath(path,paint);
        }

        private void drawEquipment(Canvas c){
            rect.set(14,22,86,68);c.drawRoundRect(rect,7,7,paint);
            path.reset();path.moveTo(23,49);path.lineTo(34,49);path.lineTo(40,38);path.lineTo(48,59);path.lineTo(56,44);path.lineTo(63,49);path.lineTo(77,49);c.drawPath(path,paint);
            c.drawLine(42,68,42,79,paint);c.drawLine(58,68,58,79,paint);c.drawLine(34,79,66,79,paint);
        }

        private void drawFolder(Canvas c){
            path.reset();
            path.moveTo(13,31);path.lineTo(41,31);path.lineTo(49,39);path.lineTo(87,39);path.lineTo(87,78);path.lineTo(13,78);path.close();
            c.drawPath(path,paint);
        }
    }

    private View makeCategoryListPage(String category){
        List<SopDocument> docs=group(all).get(category);
        if(docs==null)docs=Collections.emptyList();
        LinearLayout outer=new LinearLayout(this); outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head=new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView back=text("‹",24,BLUE_DARK,true);
        back.setGravity(Gravity.CENTER);
        back.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,13));
        back.setClickable(true);
        back.setOnClickListener(v->{selectedCategory=null;renderCurrentPage(0);});
        head.addView(back,new LinearLayout.LayoutParams(dp(38),dp(38)));

        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView name=text(category,16,TEXT,true);
        name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        TextView count=text(docs.size()+" документов",11,MUTED,false);
        titles.addView(name);titles.addView(count);
        LinearLayout.LayoutParams tlp=new LinearLayout.LayoutParams(0,-2,1);
        tlp.setMargins(dp(10),0,0,0);
        head.addView(titles,tlp);
        outer.addView(head);

        addDocumentList(outer,docs);
        return outer;
    }

    private View makeHistoryPage(){
        LinearLayout outer=new LinearLayout(this); outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head=new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(pageTitle("История"),new LinearLayout.LayoutParams(0,-2,1));

        List<SopDocument> docs=db.history(100);
        if(!docs.isEmpty()){
            TextView clear=text("Очистить",11.5f,BLUE_DARK,true);
            clear.setGravity(Gravity.CENTER);
            clear.setPadding(dp(10),dp(6),dp(10),dp(6));
            clear.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,12));
            clear.setClickable(true);
            clear.setOnClickListener(v->{db.clearHistory();renderCurrentPage(0);});
            head.addView(clear);
        }
        outer.addView(head);
        addDocumentList(outer,docs);
        return outer;
    }

    private View makeListPage(String title,List<SopDocument> docs,boolean allPage){
        LinearLayout outer=new LinearLayout(this); outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head=new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(pageTitle(title),new LinearLayout.LayoutParams(0,-2,1));
        TextView count=text(docs.size()+"",11,BLUE_DARK,true);
        count.setGravity(Gravity.CENTER);
        count.setBackground(rounded(BLUE_PALE,Color.TRANSPARENT,11));
        head.addView(count,new LinearLayout.LayoutParams(dp(38),dp(28)));
        outer.addView(head);
        addDocumentList(outer,docs);
        return outer;
    }

    private void addDocumentList(LinearLayout outer,List<SopDocument> docs){
        if(docs==null||docs.isEmpty()){
            TextView empty=text(hasFolder()?"Документов нет.":"Сначала подключите папку Google Drive.",14,MUTED,false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(10),dp(50),dp(10),dp(20));
            outer.addView(empty,new LinearLayout.LayoutParams(-1,0,1));
            return;
        }
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0,dp(2),0,dp(10));
        for(SopDocument d:docs)list.addView(documentCard(d));
        scroll.addView(list,new ScrollView.LayoutParams(-1,-2));
        outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }

    private View documentCard(SopDocument d){
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8),dp(10),dp(8),dp(10));
        card.setBackground(rounded(CARD,LINE,17));
        card.setClickable(true); card.setFocusable(true);
        if(Build.VERSION.SDK_INT>=21)card.setElevation(dp(1));

        View accent=new View(this);
        int accentColor="СОП".equalsIgnoreCase(d.type)?BLUE:CYAN;
        accent.setBackground(rounded(accentColor,Color.TRANSPARENT,3));
        LinearLayout.LayoutParams alp=new LinearLayout.LayoutParams(dp(4),-1);
        alp.setMargins(0,dp(2),dp(10),dp(2));
        card.addView(accent,alp);

        LinearLayout body=new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        LinearLayout metaRow=new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView type=text(d.type.toUpperCase(Locale.ROOT),10,BLUE_DARK,true);
        type.setGravity(Gravity.CENTER);
        type.setPadding(dp(8),dp(3),dp(8),dp(3));
        type.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,10));
        metaRow.addView(type);

        TextView category=text(d.category,10.5f,MUTED,false);
        category.setMaxLines(1); category.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams catLp=new LinearLayout.LayoutParams(0,-2,1);
        catLp.setMargins(dp(8),0,0,0);
        metaRow.addView(category,catLp);
        body.addView(metaRow);

        TextView title=text(d.title,14,TEXT,true);
        title.setLineSpacing(dp(1),1.05f);
        title.setPadding(0,dp(7),0,0);
        body.addView(title);

        if(!SopClassifier.similarFileName(d.fileName,d.title)){
            TextView file=text(d.fileName,9.5f,MUTED,false);
            file.setPadding(0,dp(4),0,0);
            file.setMaxLines(1); file.setEllipsize(TextUtils.TruncateAt.END);
            body.addView(file);
        }
        card.addView(body,new LinearLayout.LayoutParams(0,-2,1));

        TextView arrow=text("›",28,Color.rgb(125,151,168),false);
        arrow.setGravity(Gravity.CENTER);
        card.addView(arrow,new LinearLayout.LayoutParams(dp(30),-1));

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(4),0,dp(4));
        card.setLayoutParams(lp);
        card.setOnClickListener(v->openDocument(d));
        return card;
    }

    private void openDocument(SopDocument d){
        if(Build.VERSION.SDK_INT<29&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
            pendingDownload=d;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,Manifest.permission.READ_EXTERNAL_STORAGE},REQ_WRITE_DOWNLOADS);
            return;
        }

        db.markViewed(d.key);
        Toast.makeText(this,"Загрузка в Downloads/СОП Навигатор…",Toast.LENGTH_SHORT).show();
        executor.submit(() -> {
            try{
                Uri localUri=downloadToVisibleStorage(d);
                String mime=d.mime==null||d.mime.trim().isEmpty()?mimeForFile(d.fileName):d.mime;
                runOnUiThread(() -> {
                    Toast.makeText(this,"Файл сохранён в Downloads/СОП Навигатор",Toast.LENGTH_SHORT).show();
                    try{
                        Intent i=new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(localUri,mime);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(i);
                    }catch(Exception e){
                        try{
                            Intent i=new Intent(Intent.ACTION_VIEW);
                            i.setDataAndType(localUri,"*/*");
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(i);
                        }catch(Exception x){
                            Toast.makeText(this,"Файл сохранён, но на устройстве нет приложения для его открытия.",Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }catch(Exception e){
                String m=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
                runOnUiThread(() -> Toast.makeText(this,"Не удалось скачать документ из Google Drive. "+m,Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode!=REQ_WRITE_DOWNLOADS)return;
        SopDocument pending=pendingDownload;
        pendingDownload=null;
        if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED){
            if(pending!=null)openDocument(pending);
        }else{
            Toast.makeText(this,"Для сохранения в папку Downloads нужен доступ к памяти устройства.",Toast.LENGTH_LONG).show();
        }
    }

    private Uri downloadToVisibleStorage(SopDocument d) throws Exception{
        return Build.VERSION.SDK_INT>=29?downloadToMediaStore(d):downloadToLegacyDownloads(d);
    }

    private Uri downloadToMediaStore(SopDocument d) throws Exception{
        ContentResolver resolver=getContentResolver();
        Uri collection=MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String safe=safeFileName(d.fileName);
        String relative=Environment.DIRECTORY_DOWNLOADS+"/СОП Навигатор/";
        String mime=d.mime==null||d.mime.trim().isEmpty()?mimeForFile(d.fileName):d.mime;

        Uri existing=findExistingVisibleDownload(resolver,collection,safe);
        if(existing!=null){
            long existingSize=queryMediaSize(resolver,existing);

            // If the already downloaded file matches the Drive file, just open it.
            if(existingSize>0&&(d.size<=0||existingSize==d.size)){
                rememberDownloadedVersion(d,existing);
                return existing;
            }

            // Same document name, but Drive version changed: overwrite the same
            // MediaStore item instead of inserting "filename (1).pdf".
            boolean written=false;
            try(InputStream in=resolver.openInputStream(Uri.parse(d.uri));
                OutputStream out=resolver.openOutputStream(existing,"wt")){
                if(in==null)throw new IOException("Google Drive не предоставил поток файла.");
                if(out==null)throw new IOException("Не удалось открыть существующий файл для обновления.");
                byte[] buf=new byte[64*1024];
                long total=0;
                int n;
                while((n=in.read(buf))!=-1){
                    out.write(buf,0,n);
                    total+=n;
                }
                out.flush();
                if(total<=0)throw new IOException("Получен пустой файл.");
                written=true;
            }
            if(written){
                ContentValues update=new ContentValues();
                update.put(MediaStore.Downloads.MIME_TYPE,mime);
                resolver.update(existing,update,null,null);
                rememberDownloadedVersion(d,existing);
                return existing;
            }
        }

        ContentValues values=new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME,safe);
        values.put(MediaStore.Downloads.MIME_TYPE,mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH,relative);
        values.put(MediaStore.Downloads.IS_PENDING,1);

        Uri target=resolver.insert(collection,values);
        if(target==null)throw new IOException("Android не создал файл в Downloads.");

        boolean ok=false;
        try(InputStream in=resolver.openInputStream(Uri.parse(d.uri));
            OutputStream out=resolver.openOutputStream(target,"w")){
            if(in==null)throw new IOException("Google Drive не предоставил поток файла.");
            if(out==null)throw new IOException("Не удалось открыть файл в Downloads для записи.");
            byte[] buf=new byte[64*1024];
            long total=0;
            int n;
            while((n=in.read(buf))!=-1){
                out.write(buf,0,n);
                total+=n;
            }
            out.flush();
            if(total<=0)throw new IOException("Получен пустой файл.");
            ok=true;
        }finally{
            if(!ok){
                try{resolver.delete(target,null,null);}catch(Exception ignored){}
            }
        }

        ContentValues done=new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING,0);
        resolver.update(target,done,null,null);
        rememberDownloadedVersion(d,target);
        return target;
    }

    private Uri findExistingVisibleDownload(ContentResolver resolver,Uri collection,String safe){
        // First try the URI saved by this app for the same filename.
        SharedPreferences p=getSharedPreferences("downloaded_files",MODE_PRIVATE);
        String saved=p.getString("uri_"+safe,null);
        if(saved!=null){
            Uri u=Uri.parse(saved);
            try{
                long size=queryMediaSize(resolver,u);
                if(size>=0)return u;
            }catch(Exception ignored){}
        }

        // Search by file name only. Some Android/Drive combinations normalize
        // RELATIVE_PATH differently, which made the previous exact query miss
        // an existing file and create duplicates.
        String[] projection={
                MediaStore.Downloads._ID,
                MediaStore.Downloads.RELATIVE_PATH,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_ADDED
        };
        String selection=MediaStore.Downloads.DISPLAY_NAME+"=?";
        String[] args={safe};

        try(Cursor cur=resolver.query(collection,projection,selection,args,MediaStore.Downloads.DATE_ADDED+" DESC")){
            if(cur==null)return null;
            int idCol=cur.getColumnIndexOrThrow(MediaStore.Downloads._ID);
            int pathCol=cur.getColumnIndex(MediaStore.Downloads.RELATIVE_PATH);
            while(cur.moveToNext()){
                String path=pathCol>=0&&!cur.isNull(pathCol)?cur.getString(pathCol):"";
                String normalized=path==null?"":path.replace('\\','/').toLowerCase(Locale.ROOT);
                if(normalized.contains("соп навигатор")){
                    Uri found=ContentUris.withAppendedId(collection,cur.getLong(idCol));
                    p.edit().putString("uri_"+safe,found.toString()).apply();
                    return found;
                }
            }
        }catch(Exception ignored){}
        return null;
    }

    private long queryMediaSize(ContentResolver resolver,Uri uri){
        try(Cursor cur=resolver.query(uri,new String[]{MediaStore.Downloads.SIZE},null,null,null)){
            if(cur!=null&&cur.moveToFirst()){
                int col=cur.getColumnIndex(MediaStore.Downloads.SIZE);
                if(col>=0&&!cur.isNull(col))return cur.getLong(col);
            }
        }catch(Exception ignored){}
        return -1;
    }

    private void rememberDownloadedVersion(SopDocument d,Uri uri){
        SharedPreferences.Editor e=getSharedPreferences("downloaded_files",MODE_PRIVATE).edit();
        e.putString("uri_"+safeFileName(d.fileName),uri.toString());
        e.putLong("size_"+d.key,d.size);
        e.putLong("modified_"+d.key,d.lastModified);
        e.apply();
    }

    private Uri downloadToLegacyDownloads(SopDocument d) throws Exception{
        File root=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir=new File(root,"СОП Навигатор");
        if(!dir.exists()&&!dir.mkdirs())throw new IOException("Не удалось создать папку Downloads/СОП Навигатор.");

        String safe=safeFileName(d.fileName);
        File target=new File(dir,safe);
        if(target.isFile()&&target.length()>0&&(d.size<=0||target.length()==d.size)){
            return Uri.parse(("content://"+getPackageName()+".files/download/")+Uri.encode(target.getName()));
        }

        File tmp=new File(dir,safe+".part");
        if(tmp.exists())tmp.delete();
        try(InputStream in=getContentResolver().openInputStream(Uri.parse(d.uri))){
            if(in==null)throw new IOException("Google Drive не предоставил поток файла.");
            try(OutputStream out=new FileOutputStream(tmp)){
                byte[] buf=new byte[64*1024];
                int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
                out.flush();
            }
        }
        if(tmp.length()==0){tmp.delete();throw new IOException("Получен пустой файл.");}
        if(target.exists()&&!target.delete()){tmp.delete();throw new IOException("Не удалось заменить локальную копию.");}
        if(!tmp.renameTo(target)){
            try(InputStream in=new FileInputStream(tmp);OutputStream out=new FileOutputStream(target)){
                byte[] buf=new byte[64*1024];
                int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
            }
            tmp.delete();
        }
        return Uri.parse(("content://"+getPackageName()+".files/download/")+Uri.encode(target.getName()));
    }

    private String safeFileName(String name){
        String n=name==null||name.trim().isEmpty()?"document.pdf":name.trim();
        n=n.replaceAll("[\\\\/:*?\\\"<>|\\p{Cntrl}]","_");
        if(n.length()>140){
            int dot=n.lastIndexOf('.');
            String ext=dot>0?n.substring(dot):"";
            n=n.substring(0,Math.max(1,140-ext.length()))+ext;
        }
        return n;
    }

    private String mimeForFile(String name){
        String n=name==null?"":name.toLowerCase(Locale.ROOT);
        if(n.endsWith(".pdf"))return "application/pdf";
        if(n.endsWith(".docx"))return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if(n.endsWith(".doc"))return "application/msword";
        if(n.endsWith(".rtf"))return "application/rtf";
        return "application/octet-stream";
    }

    private void renderSearch(String query){
        String q=SopClassifier.norm(query==null?"":query);
        ArrayList<SopDocument> found=new ArrayList<>();
        for(SopDocument d:all){
            String hay=SopClassifier.norm(d.title+" "+d.fileName+" "+d.category+" "+d.type+" "+d.keywords);
            boolean textOk=q.isEmpty()||hay.contains(q);
            boolean personnelOk=personnelFilter==null||SopPersonnelIndex.matches(d,personnelFilter);
            if(textOk&&personnelOk)found.add(d);
        }
        String title=personnelFilter==null?"Поиск":"По персоналу · "+personnelFilter;
        if(!q.isEmpty()&&personnelFilter!=null)title+=" · поиск";
        showContent(makeListPage(title,found,false),0);
    }

    private Map<String,List<SopDocument>> group(List<SopDocument> docs){
        LinkedHashMap<String,List<SopDocument>> out=new LinkedHashMap<>();
        for(String c:SopClassifier.CATEGORIES)out.put(c,new ArrayList<>());
        for(SopDocument d:docs)out.computeIfAbsent(d.category,k->new ArrayList<>()).add(d);
        return out;
    }

    private TextView pageTitle(String s){
        TextView t=text(s,18,TEXT,true);
        t.setPadding(dp(2),dp(4),dp(2),dp(5));
        t.setLetterSpacing(0.01f);
        return t;
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

    @Override public void onBackPressed(){
        showExitMenu();
    }

    private void showExitMenu(){
        final Dialog dialog=new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout shell=new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(18),dp(18),dp(18),dp(16));
        shell.setBackground(gradient(Color.rgb(242,251,254),Color.rgb(226,244,252),22));

        LinearLayout top=new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView mark=text("➤",20,Color.WHITE,true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(BLUE,Color.TRANSPARENT,14));
        top.addView(mark,new LinearLayout.LayoutParams(dp(44),dp(44)));

        LinearLayout titleBox=new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title=text("СОП Навигатор",20,BLUE_DARK,true);
        TextView subtitle=text("Завершить работу с приложением?",12,MUTED,false);
        subtitle.setPadding(0,dp(2),0,0);
        titleBox.addView(title);
        titleBox.addView(subtitle);
        LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(0,-2,1);
        titleLp.setMargins(dp(11),0,0,0);
        top.addView(titleBox,titleLp);
        shell.addView(top);

        TextView message=text("Вы можете продолжить работу или выйти из приложения.",13,TEXT,false);
        message.setLineSpacing(dp(2),1.03f);
        message.setPadding(dp(2),dp(16),dp(2),dp(15));
        shell.addView(message);

        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        TextView exit=text("Выход",14,BLUE_DARK,true);
        exit.setGravity(Gravity.CENTER);
        exit.setBackground(rounded(Color.WHITE,LINE,15));
        exit.setClickable(true); exit.setFocusable(true);

        TextView continueBtn=text("Продолжить",14,Color.WHITE,true);
        continueBtn.setGravity(Gravity.CENTER);
        continueBtn.setBackground(gradient(BLUE,CYAN,15));
        continueBtn.setClickable(true); continueBtn.setFocusable(true);

        LinearLayout.LayoutParams exitLp=new LinearLayout.LayoutParams(0,dp(48),1);
        exitLp.setMargins(0,0,dp(5),0);
        LinearLayout.LayoutParams continueLp=new LinearLayout.LayoutParams(0,dp(48),1);
        continueLp.setMargins(dp(5),0,0,0);
        actions.addView(exit,exitLp);
        actions.addView(continueBtn,continueLp);
        shell.addView(actions);

        exit.setOnClickListener(v -> {
            dialog.dismiss();
            finishAffinity();
        });
        continueBtn.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(shell);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> {});

        Window w=dialog.getWindow();
        if(w!=null){
            w.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp=new WindowManager.LayoutParams();
            lp.copyFrom(w.getAttributes());
            lp.width=(int)(getResources().getDisplayMetrics().widthPixels*0.88f);
            lp.height=WindowManager.LayoutParams.WRAP_CONTENT;
            lp.dimAmount=0.32f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        if(w!=null){
            WindowManager.LayoutParams lp=w.getAttributes();
            lp.width=(int)(getResources().getDisplayMetrics().widthPixels*0.88f);
            lp.height=WindowManager.LayoutParams.WRAP_CONTENT;
            w.setAttributes(lp);
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

class SopPersonnelIndex {
    static final Set<String> DOCTORS=new HashSet<>(Arrays.asList("10jVUQJxKWE8NK4grtpxd7_t5kHxZ94ap","1Mbl-_YlJfuJqODTmCYnKUpwWcAlTvBhR","19vPYwx1EAa6HDQTvJs0BoKZZ0YIWvC6U","1_AgiLn8IQshQB6rab7XshTznofBs-RB0","1n9v0PSxhZYX1pURGfftGyLl6hIFBGv5O","1saFkG3Hfloljs74iGRspFlD1FuP4kcau","1QRo3CcNL3w6_4liBJxmXhD3klnJLkixW","1xw0P3tnQUCUG907MIJ9Qq_XXEIVMijDR","1fnUks7L2ENAUPKC7vI9J2p_A6L-Mgg8-","19-BZ6Zt3j_FAF9O_UvGVDCIW7rZSidCo","19Icj4YH1umX1599ESuE7foac9p1I10ym","1xdwULVm7l7HORoyxn4WPqqqx-qG1_WD2","1ahL4PHn9C3G2Sve18e-Md2Tv_5rJh2hK","1T5rb7rO25F2mWyyGdNnAjdPjv1FD1k_Y","1Ze-9aEHDhHXg0C1eOWMPV1_7gEskCbjI","1-AG_kI3g0Xzv7zfMUSFDW_93H8-ZEVQI","1X5tp_-CTB_v1nC83PTEFhBBkR9G8Llbo","1CHdmK7_h6vswLhe_QXVydmfwuNgyBhms","1xp3mI_-ie9AJWecz9DYSgK6VPWCe7SlL","1rhQG3QynJq-r2asLRBfEnN0hq8RDicjo","19ndj-H87wTn8eWI9CdsaovUlWvkF3IG7","1uxv4YCRmgQUcDX3v5wKDDVu2wSDKhrJ6","1CdJazrMPA6byxcnBMqCtjpri7Ds_uEUG","1Go3PFjEOjXUhTiaiBzO3WU3YGHS7Ne7v","1Udvl5Z7hKNw40JQgcM40Qwuy2Q1NNpDg","1msb8YgO5gkbhqHJZ-N6WL7knUJ9Feyxg","1PejvKQ7HjvqfRAghipehMCBObsAZFoBE","1APUEBo1xfDOCfvcmMQnsY9YVKuHeA62m","1oXkNgEZOxGJP9BnamWgsq2OdNsSoUp9-","1TYACh-cW5fxdEFp-Stx8ynSpOPrF3UpP","1YwsAAOq516Hy-vh0_bQcBWvFJ-QidV-2","1Q8Lzm1Kk27nu9B6n01BTJyRk8S6IZMXE","116-_4MvlOOU7UQfiiLuptCcXx9wlFeeU","1WTrtG_t3ebM4QXl8rHFc4nkgKaSFYMUp","16x7ywxJ6NxFbwjQ-aI8uxeqtkqnL7te8","1apcC8niUNQeO2EQJWAcHsYIlyMXWLld8","14UThS3AsUUcIPr4qKpjse-FxoO0j0KXY","17pHsm8Z1Jsg0P_QSJiw9bazhegjrYeM5","1vliP9iP4S2A8lmphyz0HaXaVDnYshU7R","17dy1pF_BphYGqBWr9UtHF8AvafAj3Lzy","1g4g0GBXPTTfMMrpEA5TlnPY6mfQ5WZ4o","1tlvxPtx1H45bctZYdodThwO3xUgWqw9T","1ykVuVlLXcNYnyiN5-qKxpapyDdDfaA-Y","12IrqeMvKVtBlC-hiEyzPckSPR83L2jRg","1VYW6s1vJRdlLJ8pOxS8CP7QcaBuri388","1rIKLee_yLIx_hgm2aphsa4_FC5VhsiQU","1RKgxkIH_q_ALTw--SP3L38SXSRCCL8Vm","1nf3xSdrh3HxSrUP20oZJaU7hby_2a68T","1MBjFGSn1EdPfvWZ0lNIUYDxROkRJkCY6","1eqDmGj-Bxz4CJL_OrqbmYVtJ5SE0rV-i","1DBXnyp1uyCdjRgXQ6nbr-SrB0qmQPKt7","1YkisJ-BSxETgZ8KVJhKoVnIzzHwH3ANt","1kdJtj7BAsSiCy_ec2EUi49QlhqFJrt2C","14HTXw0QDfU1U7kSlngYusWgkb1sAhP3x","1EYcsjMpAVdV6ycvurtuFnyCX2zjLhQit","1AV6yUgH-fRImkFUX9fiBBVckjunl1aoK","1nv1jy-TxKNzGhuUewXIuuY5idcqdIUZ5","1lsb8UUhBnBIhH1xzcf0Usbc8BiY4Qhxd","19jzI5j8EggcD2EsacXIGlemqvE-B3En5","1wBu7PVX-b5fKfjlr_SG8sepAAJB4PtCQ","1GL4BFSu1cLrlf_TnBtsNJh0hYPj7ySg-","1q7YH-vb9B7ektHtgbf8qLYwycjtHjBtE","1C27FgwXWIWFCFtgxyqyKK5vo2v2cFfUx","1QFV3O4MqH1zegPqvYFLFBtCJchou18St","1vPGB3c8f99Jt4T2seOpwUD37-X3n5yUC","1dElNpZp9sr9t8nU7JI4PpN3guRyy9z6v","1PfRDfmzjyCbpdJF7iLHpudA6hbhD3Un8","1viEy6njVrvWEqgT0Y1I9zZmy_RXmUjDN","1jATxSUROR1FYJ2uQHAMjHdTnOuHmNHXJ","1gPoz0mVGmTkA7rJ1CKn1uLD6bbaTdqLB","1BWvTkQ7REDg6md6lPh4WDcIEJNi7umkm","1l_QjMShSztch30BSlwTz0maDQDX9KVg_","1_EJ1E4qdwcSEejs1g9jbX7updeTTUXjv","1Uomm9aGOXKh1MdXyJmzamzS5Ha1_0zTD","1-GDZocbcxLeZxcQCeYE97vTKPoHyvkBy","1kt18I4o2NhWWel4a-SHmW_8J5fhL_zke","1_IVa9VjzaFHuebh4jX7_EYSLAxQUUMJ5","1RDgeK0uOL_ok4WvRgkwv-ZNxU7mRfeai","1lGrqyySF1Js_o6yejA_WHkh0102F1qr3","1B4DWqNY3cCxkInPSxfIRluykLkCE0dLN","1CDma0NrnUSLpn1pDzUBtySKluGgyLMpB","1NUJVRpbEM5fNDxq7JcGN-yDd3TZQP9nl","17IownyqQgYLCDtFMbn1N_a32MWfzscV_","1SGTrtClGxFRy7pLwyOMNu3XHlRng2QcH","1C3RaC3DGMDY8fmRX44dITil9583qoYAi","1v89gMxNXfcuvXUM3P1fkS2xwWMg6zhlw","1EDQzBzrxdVu2YiWzIn9O2gra-3NuhS0v","1aQr5uKJa3zNIcpM0IfCXmobZircqY7gz","1g1MSpZ77VFFvJBA3oThb-awc_UDuhqCE","1Msqi_2fEjO77CYgosTFEt0BK2JDXvo15","1I61zk2HU4o9BavvDRv-UftN6-FkU0DgX","17FEAPoxPtD4Nf1wNLC4Ei4godE6CWjIq","19uNp6TOUpZ8Lxq4AOje-Jlz0yq97l-aE","1E6ZxAGUJWzFKsO-bq3D771BEGcSr7AmV","1I9NE6dDDeGB1KNiHr6qgUNNyIsZtnTrC","1ryqhNFulr7v-SQEG4vrGPu3wWFCAAoX1","1QZAUWIDNW8yvbqFzZqyA-8J_cFOsylFC","12Zhgg3Zb8EkxY2LklN_CSiVk3JBxuR_l","1wJ8YsYXA2x6BRxaMi2LmJ5buSpM5AWDb","1JvJUSjab3h5dJ56go-r6z41dBTDcCggR","1tq59Lh2xs6406HXS7CP4OoQuxcm92jfp","1yeZKNFhcxULenpWZtKQnzkpH0zbCMF04","18s-BC98YHcSeUGCdqDsNZS2yNzi-jZIR","1Yi_gBM1L_xV8Eh6tMU9G0R1nY-3iC7Ab","1_NSYG870NnbhuhQsH0rX_RhwyxFy6-j_","1rUORWyXe2VHxz8f4psNiX2_MthdgVR0O","17eRJlQhKBgDj-bIseMBpAOTFIDQh-jTc","1N0AawA-Y66pOodb4Jxz4W3ZVieb1D6g1","1aNeCQvPFnYo5ApzrH9-ygaUX7fgc6Bl7","1iaW5tREmGyHyR_ac_J-18RwoK1P8Ewxw","1KkJVmRl-1tTLa05wFKF7dKmWyMXcJh--","1T0yYjXIBSQMAcy6Av3LmQ80CrkH5-6Wh","1_BqCxr2o6PvzCpHaUNJS9qh5L4LAnVg8","14v1qG5xMnQPm9d_K-aZ-RQKNqPomn5WP","12VTNZCuLP6pz4X0mLj9czl8Q-0EwucYF","1T0QbpXpBx7Ovxc56IlefV_qbdgsfu4Fy","1T7KLDgXNvCfy-z7ezFziGRvqfg3v2Ueb","1KwC8tOe1PziFddHlgMg7B0IffqaklrOO","1bacY3yNb1T18gwbjnZri---VMhGG_C2e","10zwTAeJexX0YTSHEk6eaxht2izJdQ-BA","1DzBXGy9d59iaESJEm-izzWNI2JGDmQh0","1pN9haEWv-SBwuQtMxAujaB3ZpLXS1dWu","1LQ3Vvsf4bUw2FrbULY1CHAVsPWiHb9sc","16DcVwU2txtOkXu4f7To6_38HeeP5KwwG","1QOi3nNoGLt4fFUX3GmPvRwg1nNm8L-gR","1RaUtWI2gXbVQjaUHcU1DOh-PfJuuIdKZ","1Scc1hIQ0dXNPpryrKD7Pv80xZbU0IxO6","1vMwXf00ZD0Dl1c8I1o1f59Z1xa3liqV6","1Xg4WuQwAe0OtuHz96SPffQjZy2_FF_Od","1EDF-L4fET_bzDBHpynGvn4r3EqVSp59z","1SPEDjJODvPueTT7L716bUwNoZfP0BiqU","116pvoRGsdABRxjQhmpxwQ91-GODbsvje","1zCkHPWuGOPvDwgoIBtTGNlTtjwy98XEJ","1GQyGZOUQLnN0-_ZHHXCrUmU75VCpDW93","1FI-HPXm4hpOXlmbb0vy125Bp7MuiPN1f","1Wmb7Fhcr6osEv3TS-BQrXD9ijqrqd2D1","1JuA7B3ipBPs3IHEArC4vofSwHK1v6XTe","1ME0oRtPMHBk-hYCpieWoIlWn6r2ak74K","1gSYgM_0cJFmrzvUU8_XjvmFbuAGbB26v","1uaUkgRq3rKeXnNvzOvRXywEu9NJN9sIs","1UbpRw30iLlux4taMo2t_Us50YbNhRaDf","1IS90OwGdWjfUuQYNIPQFIcMCioB1K2Ng","1jSNF42cyWfgRr8P78QNUoXAG05EXaVCI","15lRshIzPOXlAX8UPEcY6oO0XNldLOMWY","1EMk8iWS-UMLxV5ptRgoqTnBo0al17-o1","1XOjdWII1ijcDa2ufSYL7wJDkM6hMTSwt","1OWZzTw1PLwJAlyogCaOxIudCgGETJtRP","16c-BEZwCpRjOJF367r98dckKxWH9aiyH","1DdGcvoPZFc_MvswJtaeJ-H9sZ86Eh3eB","1uUJAP0q0yZjIaTtcsPjv3qDf0UBfi3My","1FkU5OVPeu16diNEEuHwexrJ73APSRA8d","1q1FzL-fOrUuXGbSzhVdB8Csxgru1SU2x","19PHJJFU-j7jH7VQKlC9vK-0UuS2-04c4","1AHT75T6zJVqMIs_QjozP2KiCiKALgznv","1sEw8QuM93Mp8a6tYWRHjos_pycIxtpCf","1YYCJJB7ZmbUC7ywjhN6vCpyvmknFghHI","1lN4za78rTozmXMroFr5uhjETAFL7M46B","1_-E-g0aF5zo-TcWtwPeBW7YzJARh6LwR","1SI_SzCIpVQgxvL5_9Mg4AHlgWgU1sssY","13hMWS5xVAVISDsI55ZehSUzzwMn8PaZo","1RxcLcpcgo4erof4No6obNp0TbrKs5QUk","1XOO5BotPi0_yCFuR5YivVqDpZMfLXIAb","1dy7BimdI-ufTi5DzU4g6mo0UVaNaJ2nk","1hEai6AcbXvsMNJijs-AfSD4MRni7TKxl","1-O-M0JCztBVBdWDIqd_zn7U0w5PT2T4v","1DZILqNaFILuUAeljhc3abY5XQ2MOhBOv","1Rxb5GYPKCxFHyJsDlFh3V7R2MAyqWubO","1cnhI1CPYEObGDTiTXEYFxjq2_p4cc7eK","1hf50GFoKDAKejARUBQkwpYejs7sE1NKB","1y7G3nn-hOONqEoiVeykubZPTpfroMbrx","1e6n5bS_P8TdL1spp-v-mm6cJ-QC6SiFD","1TZ8HToQtQ-67p9B9ppEDuiGXSMPxuJ48","1p0unDh5o_e8WuXN-XilvrKpNWoefLI7w","1jRa_fcurTqnEqqrcoROEzWJG9zZwf-P4","16KTFaut1GHWM3eCrHpKyrDZRRJdUzi2j","10l9A5XE7Jr3WDybKx72Tv0BQT5_bHPEz","17AWlbFHeoS4nF9BINMU-dIdUnIYpjb-V","1_gSMkr_xh6dcydXOxIxAOYjM9CvCwTue","1_-VR1Ys9paNAiGE9Vy3ncwd_BdNYDoL7","1RJyjES8q9Ki9yoX_9PM4wmpQ_TDc5zQw","11iLVa81U9WD-nRJuN_rtXiefmH4DRdnp","1vtCo5dfLp5LmZpJSVjRQLtgzzSYhcEUD","1oPvjzmVgE03y007zU6AgbbA-CnUj0b-X","1okou4A9dziilD9SYE9Z8WTGeBo6451Yg","1_PHqbULCGIahO2tdeu-7gSdfRYaP0Kyd","1OCUA8wiJWAzQNSo-U3qufRvOypX-fuLn","1jFWFM_qSatMCz9oQ2xnbmQ7qJlIdWJ9B","17fDnwyBqQCcYbNbww-M4Qg_DZyAsepHR"));
    static final Set<String> NURSES=new HashSet<>(Arrays.asList("1viEy6njVrvWEqgT0Y1I9zZmy_RXmUjDN","1YkisJ-BSxETgZ8KVJhKoVnIzzHwH3ANt","1AV6yUgH-fRImkFUX9fiBBVckjunl1aoK","1PejvKQ7HjvqfRAghipehMCBObsAZFoBE","116-_4MvlOOU7UQfiiLuptCcXx9wlFeeU","1l_QjMShSztch30BSlwTz0maDQDX9KVg_","1Mbl-_YlJfuJqODTmCYnKUpwWcAlTvBhR","19vPYwx1EAa6HDQTvJs0BoKZZ0YIWvC6U","1n9v0PSxhZYX1pURGfftGyLl6hIFBGv5O","14UThS3AsUUcIPr4qKpjse-FxoO0j0KXY","1Xg4WuQwAe0OtuHz96SPffQjZy2_FF_Od"));
    static final Set<String> JUNIOR=new HashSet<>(Arrays.asList("116-_4MvlOOU7UQfiiLuptCcXx9wlFeeU","1Mbl-_YlJfuJqODTmCYnKUpwWcAlTvBhR","19vPYwx1EAa6HDQTvJs0BoKZZ0YIWvC6U","1n9v0PSxhZYX1pURGfftGyLl6hIFBGv5O","1PejvKQ7HjvqfRAghipehMCBObsAZFoBE","1q7YH-vb9B7ektHtgbf8qLYwycjtHjBtE"));
    static final Set<String> NONMED=new HashSet<>(Arrays.asList("1saFkG3Hfloljs74iGRspFlD1FuP4kcau","116-_4MvlOOU7UQfiiLuptCcXx9wlFeeU","1xdwULVm7l7HORoyxn4WPqqqx-qG1_WD2","14UThS3AsUUcIPr4qKpjse-FxoO0j0KXY","1YwsAAOq516Hy-vh0_bQcBWvFJ-QidV-2","1APUEBo1xfDOCfvcmMQnsY9YVKuHeA62m","19ndj-H87wTn8eWI9CdsaovUlWvkF3IG7","17dy1pF_BphYGqBWr9UtHF8AvafAj3Lzy","1Udvl5Z7hKNw40JQgcM40Qwuy2Q1NNpDg","1-GDZocbcxLeZxcQCeYE97vTKPoHyvkBy"));
    static final Set<String> ALL_STAFF=new HashSet<>(Arrays.asList("1GL4BFSu1cLrlf_TnBtsNJh0hYPj7ySg-","1EDQzBzrxdVu2YiWzIn9O2gra-3NuhS0v","1rIKLee_yLIx_hgm2aphsa4_FC5VhsiQU","116-_4MvlOOU7UQfiiLuptCcXx9wlFeeU","1OCUA8wiJWAzQNSo-U3qufRvOypX-fuLn","1PfRDfmzjyCbpdJF7iLHpudA6hbhD3Un8","1FI-HPXm4hpOXlmbb0vy125Bp7MuiPN1f","1nv1jy-TxKNzGhuUewXIuuY5idcqdIUZ5","1xdwULVm7l7HORoyxn4WPqqqx-qG1_WD2","1PejvKQ7HjvqfRAghipehMCBObsAZFoBE","14UThS3AsUUcIPr4qKpjse-FxoO0j0KXY","1YwsAAOq516Hy-vh0_bQcBWvFJ-QidV-2","1APUEBo1xfDOCfvcmMQnsY9YVKuHeA62m","1lN4za78rTozmXMroFr5uhjETAFL7M46B","1BWvTkQ7REDg6md6lPh4WDcIEJNi7umkm","1Bta2ALJ8VUKtPLm2BeM0QkpQVpEpry2B","1q7YH-vb9B7ektHtgbf8qLYwycjtHjBtE","19jzI5j8EggcD2EsacXIGlemqvE-B3En5"));


    // Android's Google Drive DocumentsProvider exposes opaque SAF document IDs,
    // not the public Google Drive file IDs used by the personnel index above.
    // Resolve the current display filename back to its real Drive ID so the
    // personnel filters work reliably on-device.
    static final Map<String,String> DRIVE_ID_BY_NAME=buildDriveIdByName();

    private static Map<String,String> buildDriveIdByName(){
        Map<String,String> m=new HashMap<>();
        putDriveName(m,"1g1MSpZ77VFFvJBA3oThb-awc_UDuhqCE","А-1-ВК-20.26 Порядок информирования сотрудников СПб ГБУЗ «Елизаветинская больница» об изменении или выходе новых ОРД, регламентирующих вопросы обеспечения качества и безопасности медицинской деятельно.pdf");
        putDriveName(m,"1QRo3CcNL3w6_4liBJxmXhD3klnJLkixW","А-1-ДМ-28.26 Порядок доступа к клиническим рекомендациям и информирования об обновлениях в клинических рекомендациях медицинских работников.pdf");
        putDriveName(m,"1TYACh-cW5fxdEFp-Stx8ynSpOPrF3UpP","А-1-ЛБ-27.26 Алгоритм вербальных назначений лекарственных препаратов.pdf");
        putDriveName(m,"1YkisJ-BSxETgZ8KVJhKoVnIzzHwH3ANt","А-1-МИ-30.26 Алгоритм  действия персонала при работе с электрокардиографом Валента ЭКГК-01.pdf");
        putDriveName(m,"1tlvxPtx1H45bctZYdodThwO3xUgWqw9T","А-1-ПК-3.25 Алгоритм при экстренной трансфузии по жизненным показаниям.Лодин.pdf");
        putDriveName(m,"1-AG_kI3g0Xzv7zfMUSFDW_93H8-ZEVQI","А-1-ПК-4.25 Алгоритм переливания эритроцитосодержащих компонентов донорской крови.Лодин.pdf");
        putDriveName(m,"1X5tp_-CTB_v1nC83PTEFhBBkR9G8Llbo","А-1-ПК-5.25 Алгоритм переливания свежезамороженной плазмы.pdf");
        putDriveName(m,"19ndj-H87wTn8eWI9CdsaovUlWvkF3IG7","А-1-ПК-6.25 Алгоритм действий при проведении трансфузии компонентами донорской крови реципиентам, с отягощенным трансфузионным анамнезом.pdf");
        putDriveName(m,"1CHdmK7_h6vswLhe_QXVydmfwuNgyBhms","А-1-ПК-7.25 Алгоритм переливания концентрата тромбоцитов.pdf");
        putDriveName(m,"1xp3mI_-ie9AJWecz9DYSgK6VPWCe7SlL","А-1-ПК-8.25 Алгоритм переливания криопреципитата.pdf");
        putDriveName(m,"1oXkNgEZOxGJP9BnamWgsq2OdNsSoUp9-","А-1-ПР-12.26 Алгоритм действий медицинского персонала при определении профильного отделения пациента в спорных случаях.pdf");
        putDriveName(m,"1_AgiLn8IQshQB6rab7XshTznofBs-RB0","А-1-ПР-21.26 Алгоритм ведения пациентов, нуждающихся в сестринском уходе, паллиативной медицинской помощи (ОСУ и ОПМП) и социальном обслуживании.pdf");
        putDriveName(m,"1uxv4YCRmgQUcDX3v5wKDDVu2wSDKhrJ6","А-1-ПР-22.26 Порядок перевода пациентов внутри медицинской организации.pdf");
        putDriveName(m,"14HTXw0QDfU1U7kSlngYusWgkb1sAhP3x","А-1-ПР-25.26 Алгоритм действий персонала при передаче телефонограммы в органы внутренних дел.pdf");
        putDriveName(m,"1Q8Lzm1Kk27nu9B6n01BTJyRk8S6IZMXE","А-1-ПР-26.26 Транспортировка разных категорий пациентов, включая определение медицинского работникаработников для сопровождения (при необходимости).pdf");
        putDriveName(m,"1Ze-9aEHDhHXg0C1eOWMPV1_7gEskCbjI","А-1-ПР-9.26 Порядок перевода пациента в другое медицинское учреждение.pdf");
        putDriveName(m,"1VYW6s1vJRdlLJ8pOxS8CP7QcaBuri388","А-1-СМ-29.26 Оценка риска развития пролежней у пациента.pdf");
        putDriveName(m,"16x7ywxJ6NxFbwjQ-aI8uxeqtkqnL7te8","А-1-СМ-4.26 Порядок внутривенного введения рентгеноконтрастных лекарственных препаратов в рентгеновском отделении.pdf");
        putDriveName(m,"1xw0P3tnQUCUG907MIJ9Qq_XXEIVMijDR","А-1-УП-18.26 Алгоритм допуска врачей-анестезиологов-реаниматологов, врачей-стажеров, принятых на работу, к самостоятельной постановке центрального венозного катетера.pdf");
        putDriveName(m,"1T5rb7rO25F2mWyyGdNnAjdPjv1FD1k_Y","А-1-ХБ-13.26 Алгоритм действий врачей при регистрации и передаче инородных тел, извлеченных из пациента.pdf");
        putDriveName(m,"1Go3PFjEOjXUhTiaiBzO3WU3YGHS7Ne7v","А-1-ХБ-14.26 Алгоритм осуществления контроля работы операционного блока.pdf");
        putDriveName(m,"19Icj4YH1umX1599ESuE7foac9p1I10ym","А-1-ХБ-31.26 Порядок оценки операционно-анестезиологического риска пациентов перед оперативным вмешательством.pdf");
        putDriveName(m,"1msb8YgO5gkbhqHJZ-N6WL7knUJ9Feyxg","А-1-ЭБ-6.26 Приоритетный алгоритм размещения пациентов, нуждающихся в лечении в условиях отделения анестезиологии-реанимации №1.pdf");
        putDriveName(m,"1fnUks7L2ENAUPKC7vI9J2p_A6L-Mgg8-","А-1-ЭМП-10.26 Алгоритм действий медицинского персонала при подозрении на ушиб сердца у пациента.pdf");
        putDriveName(m,"1CdJazrMPA6byxcnBMqCtjpri7Ds_uEUG","А-1-ЭМП-11.26 Алгоритм действий медицинского персонала при подозрении на ушиб легкого у пациента.pdf");
        putDriveName(m,"1EDQzBzrxdVu2YiWzIn9O2gra-3NuhS0v","А-1-ЭМП-15.26 Алгоритм действий персонала при проведении базовой сердечно-легочной реанимации.pdf");
        putDriveName(m,"1ahL4PHn9C3G2Sve18e-Md2Tv_5rJh2hK","А-1-ЭМП-16.26 Алгоритм действий медицинского персонала при поступлении пациента со стенозом гортани, трахеи.pdf");
        putDriveName(m,"1rIKLee_yLIx_hgm2aphsa4_FC5VhsiQU","А-1-ЭМП-17.26 Порядок фиксации и седации пациентов в операционном отделении для противошоковых мероприятий.pdf");
        putDriveName(m,"1QFV3O4MqH1zegPqvYFLFBtCJchou18St","А-1-ЭМП-19.26 Порядок работы врача отделения скорой медицинской помощи при поступлении пациента с абстинентным синдромом и травматологической_соматической патологией.pdf");
        putDriveName(m,"10jVUQJxKWE8NK4grtpxd7_t5kHxZ94ap","А-1-ЭМП-23.26 Порядок диагностики, маршрутизации и применения активной стратегии при оказании помощи пациентам с тромбоэмболией лёгочной артерии.pdf");
        putDriveName(m,"1PejvKQ7HjvqfRAghipehMCBObsAZFoBE","А-1-ЭМП-24.26 Порядок ведения пациента с переломом проксимального отдела бедренной кости.pdf");
        putDriveName(m,"12IrqeMvKVtBlC-hiEyzPckSPR83L2jRg","А-1-ЭМП-8.26 Порядок катетеризации центральных вен.pdf");
        putDriveName(m,"1xdwULVm7l7HORoyxn4WPqqqx-qG1_WD2","А-1-ЭМП-9.25 Алгоритм действий медперсонала при поступлении пациентов с подозрением на диагноз Сочетанная травма.pdf");
        putDriveName(m,"1WTrtG_t3ebM4QXl8rHFc4nkgKaSFYMUp","А-2-ПР-7.26 Алгоритм маршрутизации пациента с желудочно-кишечным кровотечением в ОСМП.pdf");
        putDriveName(m,"17dy1pF_BphYGqBWr9UtHF8AvafAj3Lzy","А-2-ЭМП Алгоритм передачи критических значений по результатам исследований пациентов.pdf");
        putDriveName(m,"13hMWS5xVAVISDsI55ZehSUzzwMn8PaZo","Алгоритм А-5-ЛП-015 ЛП ВР версия 2.pdf");
        putDriveName(m,"17AWlbFHeoS4nF9BINMU-dIdUnIYpjb-V","Алгоритм А-5-ЛП-016 Правила обращения с ЛП ВР версия 2.pdf");
        putDriveName(m,"1EMk8iWS-UMLxV5ptRgoqTnBo0al17-o1","СОП 004.22 орг.погрузо-разгр.работ.pdf");
        putDriveName(m,"1AHT75T6zJVqMIs_QjozP2KiCiKALgznv","СОП 005.22.01ЛП.pdf");
        putDriveName(m,"1jSNF42cyWfgRr8P78QNUoXAG05EXaVCI","СОП 005.24-02МСУ Уход за полостью рта.pdf");
        putDriveName(m,"1PfRDfmzjyCbpdJF7iLHpudA6hbhD3Un8","СОП 008.22.01ЛБ Порядок отпуска лекарственных препаратов.pdf");
        putDriveName(m,"1Bta2ALJ8VUKtPLm2BeM0QkpQVpEpry2B","СОП 009.22.01ЛБ Порядок отпуска медицинских изделий.pdf");
        putDriveName(m,"1_gSMkr_xh6dcydXOxIxAOYjM9CvCwTue","СОП 009.24-01ИВ Выполнение внутримышечной инъекции.pdf");
        putDriveName(m,"1wBu7PVX-b5fKfjlr_SG8sepAAJB4PtCQ","СОП 010_25-01ЛП Выполнение исследования «Определение антител к вирусу гепатита A (Hepatitis A virus)..pdf");
        putDriveName(m,"1_PHqbULCGIahO2tdeu-7gSdfRYaP0Kyd","СОП 013 Выполнение исследования «клинический анализ крови» с использованием автоматического гематологического анализатора MINDRAY BC-5380.pdf");
        putDriveName(m,"17fDnwyBqQCcYbNbww-M4Qg_DZyAsepHR","СОП 014_25-01ЛП Определение кислотоустойчивых микобактерий (КУМ) методом световой микроскопии в преп..pdf");
        putDriveName(m,"12VTNZCuLP6pz4X0mLj9czl8Q-0EwucYF","СОП 016.25-01МСУ Исследования глюкозы крови с помощью глюкометра.pdf");
        putDriveName(m,"1ykVuVlLXcNYnyiN5-qKxpapyDdDfaA-Y","СОП 022 Приготовление суспензии (бариевой взвеси) из рентгеноконтрастного средства Бария сульфат.pdf");
        putDriveName(m,"1MBjFGSn1EdPfvWZ0lNIUYDxROkRJkCY6","СОП 023 Хранение рентгеноконтрастного средства Бария сульфат в рентгеновском отделении.pdf");
        putDriveName(m,"1QOi3nNoGLt4fFUX3GmPvRwg1nNm8L-gR","СОП 024 Наложение электродов и регистрация ЭКГ в 12-ти отведениях.pdf");
        putDriveName(m,"1CDma0NrnUSLpn1pDzUBtySKluGgyLMpB","СОП 025 Подготовка пациента к рентгеноскопическому исследованию с рентгеноконтрастным средством Бария сульфат.pdf");
        putDriveName(m,"1SPEDjJODvPueTT7L716bUwNoZfP0BiqU","СОП 026.23-01МСУ Постановка подкожной инъекции инсулина с помощью шприц-ручки.pdf");
        putDriveName(m,"1_IVa9VjzaFHuebh4jX7_EYSLAxQUUMJ5","СОП 027.23-01МСУ Действия медицинской сестры при подготовке пациента к плановой операции.pdf");
        putDriveName(m,"16DcVwU2txtOkXu4f7To6_38HeeP5KwwG","СОП 028.23-01МСУ Рентгенологическое исследование органов грудной клетки (флюорография).pdf");
        putDriveName(m,"1_-E-g0aF5zo-TcWtwPeBW7YzJARh6LwR","СОП Выполнение подкожной инъекции.pdf");
        putDriveName(m,"1sEw8QuM93Mp8a6tYWRHjos_pycIxtpCf","СОП Гигиена рук медицинского персонала.pdf");
        putDriveName(m,"1kt18I4o2NhWWel4a-SHmW_8J5fhL_zke","СОП Обработка оборудования для УЗИ.pdf");
        putDriveName(m,"1NUJVRpbEM5fNDxq7JcGN-yDd3TZQP9nl","СОП Организация и осуществление дезинсекционных мероприятий.pdf");
        putDriveName(m,"16c-BEZwCpRjOJF367r98dckKxWH9aiyH","СОП порядок проведения генеральной уборки.pdf");
        putDriveName(m,"1l_QjMShSztch30BSlwTz0maDQDX9KVg_","СОП Профилактика анаэробной инфекции.pdf");
        putDriveName(m,"1pN9haEWv-SBwuQtMxAujaB3ZpLXS1dWu","СОП Профилактика педикулеза и чесотки.pdf");
        putDriveName(m,"1DzBXGy9d59iaESJEm-izzWNI2JGDmQh0","СОП Санация трахеобронхиального дерева.pdf");
        putDriveName(m,"17pHsm8Z1Jsg0P_QSJiw9bazhegjrYeM5","СОП Хирургическая обработка кожи опер.поля.pdf");
        putDriveName(m,"1Msqi_2fEjO77CYgosTFEt0BK2JDXvo15","СОП-1-ВК-86.25 Порядок организации и проведения анкетирования пациентов.pdf");
        putDriveName(m,"1saFkG3Hfloljs74iGRspFlD1FuP4kcau","СОП-1-ВК-87.25 Правила оформления, порядок согласования и регистрации СОПов и алгоритмов.pdf");
        putDriveName(m,"1EDF-L4fET_bzDBHpynGvn4r3EqVSp59z","СОП-1-ВК-99.25 Порядок регистрации и управления нежелательными событиями.pdf");
        putDriveName(m,"1YwsAAOq516Hy-vh0_bQcBWvFJ-QidV-2","СОП-1-ПК-90.25 Оценка эффективности трансфузии компонентами донорской крови.pdf");
        putDriveName(m,"1Udvl5Z7hKNw40JQgcM40Qwuy2Q1NNpDg","СОП-1-ПК-91.25 Правила назначения компонентов донорской крови.pdf");
        putDriveName(m,"1APUEBo1xfDOCfvcmMQnsY9YVKuHeA62m","СОП-1-ПК-92.25 Действия медицинского персонала при возникновении реакций и осложнений у реципиентов, связанных с трансфузией.pdf");
        putDriveName(m,"14UThS3AsUUcIPr4qKpjse-FxoO0j0KXY","СОП-1-ПК-93.25 Проведение реинфузии аутоэритроцитов на аппарате Sorin.xtra.pdf");
        putDriveName(m,"1RKgxkIH_q_ALTw--SP3L38SXSRCCL8Vm","СОП-1-ПР-13.26 Порядок действий персонала при обнаружении тела человека без признаков жизни на территории учреждения.pdf");
        putDriveName(m,"1dElNpZp9sr9t8nU7JI4PpN3guRyy9z6v","СОП-1-ПР-2.26 Порядок перевода пациентов в отделения анестезиологии-реанимации.pdf");
        putDriveName(m,"1apcC8niUNQeO2EQJWAcHsYIlyMXWLld8","СОП-1-ПР-98.25 Регламент определения уровня глюкозы.pdf");
        putDriveName(m,"1AV6yUgH-fRImkFUX9fiBBVckjunl1aoK","СОП-1-СМ-16.26 Профилактика пролежней.pdf");
        putDriveName(m,"1EYcsjMpAVdV6ycvurtuFnyCX2zjLhQit","СОП-1-УП-6.26 Регламент работы плановой операционной медсестры.pdf");
        putDriveName(m,"1aQr5uKJa3zNIcpM0IfCXmobZircqY7gz","СОП-1-УП-9.26 Порядок работы старшего врача отделения скорой медицинской помощи.pdf");
        putDriveName(m,"1g4g0GBXPTTfMMrpEA5TlnPY6mfQ5WZ4o","СОП-1-ХБ-5.26 Порядок оформления направления и транспортировки биопсийного (операционного) материала в патологоанатомическое отделение.pdf");
        putDriveName(m,"1nv1jy-TxKNzGhuUewXIuuY5idcqdIUZ5","СОП-1-ХБ-97.25 Контроль обеспечения хирургической безопасности в плановой операционной при оперативных вмешательствах под наркозом.pdf");
        putDriveName(m,"1-GDZocbcxLeZxcQCeYE97vTKPoHyvkBy","СОП-1-ЭБ-1.26 Стандартные определения случаев инфекций, связанных с оказанием медицинской помощи.pdf");
        putDriveName(m,"19jzI5j8EggcD2EsacXIGlemqvE-B3En5","СОП-1-ЭМП-100.25 Порядок лечения пациентов в блоке экзогенной интоксикации отделения скорой медицинской помощи_compressed.pdf");
        putDriveName(m,"1FI-HPXm4hpOXlmbb0vy125Bp7MuiPN1f","СОП-1-ЭМП-101.25  Регламент работы блока экзогенной интоксикации отделения скорой медицинской помощи.pdf");
        putDriveName(m,"1q7YH-vb9B7ektHtgbf8qLYwycjtHjBtE","СОП-1-ЭМП-102.25 Регламент использования Чек-листа обследования пациента в отделении скорой медицинской помощи в СПб ГБУЗ «Елизаветинская больница».pdf");
        putDriveName(m,"1rhQG3QynJq-r2asLRBfEnN0hq8RDicjo","СОП-1-ЭМП-11.26 Порядок действий медицинского персонала при развитии у пациента криза злокачественной гипертермии.pdf");
        putDriveName(m,"1viEy6njVrvWEqgT0Y1I9zZmy_RXmUjDN","СОП-1-ЭМП-4.26 Порядок действий медицинского персонала при анафилактическом шоке.pdf");
        putDriveName(m,"116-_4MvlOOU7UQfiiLuptCcXx9wlFeeU","СОП-2-ИД-12.26 Идентификация личности пациентов.pdf");
        putDriveName(m,"1lsb8UUhBnBIhH1xzcf0Usbc8BiY4Qhxd","СОП-2-ЭБ-15.26 Обеззараживание биологического материала и изделий медицинского назначения, контаминированных микроорганизмами II – IV групп патогенности, а также при проведении работ с ПБА.pdf");
        putDriveName(m,"1nf3xSdrh3HxSrUP20oZJaU7hby_2a68T","СОП-2-ЭБ-7.26 Обработка силового оборудования после хирургического вмешательства.pdf");
        putDriveName(m,"1GL4BFSu1cLrlf_TnBtsNJh0hYPj7ySg-","СОП-3-А-95.25 Порядок хранения лекарственных препаратов.pdf");
        putDriveName(m,"19-BZ6Zt3j_FAF9O_UvGVDCIW7rZSidCo","СОП-3-ЭБ-96.25 Экстренная профилактика парентеральных инфекций.pdf");
        putDriveName(m,"1OCUA8wiJWAzQNSo-U3qufRvOypX-fuLn","СОП-4-ЭБ-14.26 Порядок соблюдения биологической безопасности в клинико-диагностической лаборатории.pdf");
        putDriveName(m,"1vtCo5dfLp5LmZpJSVjRQLtgzzSYhcEUD","СОП002.24-01ЭП Алгоритм осуществления дежурства врачом-хирургом операционного отделения для противошоковых мероприятий.pdf");
        putDriveName(m,"10zwTAeJexX0YTSHEk6eaxht2izJdQ-BA","СОП003.22.02ЛБ Контроль температуры.pdf");
        putDriveName(m,"15lRshIzPOXlAX8UPEcY6oO0XNldLOMWY","СОП003.24-01МО Панорамная рентгенография. Синонимы телерентгенограмма, сшивка.pdf");
        putDriveName(m,"1LQ3Vvsf4bUw2FrbULY1CHAVsPWiHb9sc","СОП004.24-01МО Эксплуатация механического поршневого дозатора.pdf");
        putDriveName(m,"19PHJJFU-j7jH7VQKlC9vK-0UuS2-04c4","СОП005_25-01ЛП Выполнение исследования «Определение антигена гепатита В (HbsAg) в крови (экспресс-тест)» с использованием иммунохроматографических тест-систем.pdf");
        putDriveName(m,"1vPGB3c8f99Jt4T2seOpwUD37-X3n5yUC","СОП006.24-01МО Порядок работы медицинской сестры при промывании нёбных миндалин аппаратом ТОНЗИЛЛОР-ММ.pdf");
        putDriveName(m,"1uUJAP0q0yZjIaTtcsPjv3qDf0UBfi3My","СОП007.24-01МО Порядок работы медицинской сестры при проведении манипуляций в условиях перевязочной АКО.pdf");
        putDriveName(m,"1q1FzL-fOrUuXGbSzhVdB8Csxgru1SU2x","СОП008.24-01ИВ Взятие мазков на степень чистоты влагалища.pdf");
        putDriveName(m,"1GQyGZOUQLnN0-_ZHHXCrUmU75VCpDW93","СОП009.25-01МО Алгоритм обработки видеоэндоскопов «PENTAX» EG-2990K, ED-3490TK, ED-34i10T, ЕС-3890FK, EC-38-i10L, ЕВ19-J10».pdf");
        putDriveName(m,"1zCkHPWuGOPvDwgoIBtTGNlTtjwy98XEJ","СОП010.24-01ИВ Инстилляция мочевого пузыря.pdf");
        putDriveName(m,"1FkU5OVPeu16diNEEuHwexrJ73APSRA8d","СОП011.24-01МСУ Порядок надевания и снятия памперса взрослому пациенту в положении лежа.pdf");
        putDriveName(m,"1okou4A9dziilD9SYE9Z8WTGeBo6451Yg","СОП012.24-01ЭБ Лабораторная диагностика малярии.pdf");
        putDriveName(m,"1OWZzTw1PLwJAlyogCaOxIudCgGETJtRP","СОП014.22-01ЭБ.pdf");
        putDriveName(m,"1bacY3yNb1T18gwbjnZri---VMhGG_C2e","СОП017.25-03ПК Регистрация донора.pdf");
        putDriveName(m,"1ME0oRtPMHBk-hYCpieWoIlWn6r2ak74K","СОП018.25-03ПК Первичное лабораторное обследование донора крови, плазмы и клеток.pdf");
        putDriveName(m,"1gSYgM_0cJFmrzvUU8_XjvmFbuAGbB26v","СОП019.25-01ПКОпределение группы крови, резус-фактора и антигена К  у доноров моноклональными антителами (цоликлонами).pdf");
        putDriveName(m,"1YYCJJB7ZmbUC7ywjhN6vCpyvmknFghHI","СОП020.24-01ИВ Взятие капиллярной крови для определения глюкозы на анализаторах “BIOSEN”.pdf");
        putDriveName(m,"1IS90OwGdWjfUuQYNIPQFIcMCioB1K2Ng","СОП020.25-03ПКОпределение уровня гемоглобина в крови донора на портативном гематологическом анализаторе «Hemo Control».pdf");
        putDriveName(m,"1lN4za78rTozmXMroFr5uhjETAFL7M46B","СОП021..24-01ЛБ Порядок закупки лекарственных препаратов (2).pdf");
        putDriveName(m,"1UbpRw30iLlux4taMo2t_Us50YbNhRaDf","СОП021.25-01ПК Внутрилабораторный контроль качества моноклональных антител (цоликлонов).pdf");
        putDriveName(m,"1uaUkgRq3rKeXnNvzOvRXywEu9NJN9sIs","СОП022.25-01ПК Постановка контроля качества на портативном гематологическом анализаторе «Hemo Control».pdf");
        putDriveName(m,"1T7KLDgXNvCfy-z7ezFziGRvqfg3v2Ueb","СОП023.25-03ПК Определение белковых фракций на анализаторе акустическом АКБа-01 БИОМ.pdf");
        putDriveName(m,"1KwC8tOe1PziFddHlgMg7B0IffqaklrOO","СОП024.25-01ПК Контроль качества гематологических исследований, выполняемых на анализаторе Medonic M-Series.pdf");
        putDriveName(m,"1T0QbpXpBx7Ovxc56IlefV_qbdgsfu4Fy","СОП025.25-03ПК Определение клинического анализа крови на гематологическом анализаторе Medonic M-Series.pdf");
        putDriveName(m,"1B4DWqNY3cCxkInPSxfIRluykLkCE0dLN","СОП026.25-03ПК Прием доноров врачом-трансфузиологом.pdf");
        putDriveName(m,"10l9A5XE7Jr3WDybKx72Tv0BQT5_bHPEz","СОП028.25-01ПК Подготовка и проведение процедуры донации.pdf");
        putDriveName(m,"16KTFaut1GHWM3eCrHpKyrDZRRJdUzi2j","СОП029.25-03ПК Производство эритроцитной взвеси, с удаленным лейкотромбослоем (без ЛТС) и плазмы из дозы консервированной крови, заготовленной в счетверенные гемоконтейнеры производства Terumo.pdf");
        putDriveName(m,"1Rxb5GYPKCxFHyJsDlFh3V7R2MAyqWubO","СОП030.25-03ПК Производство фильтрованной эритроцитной взвеси и фильтрованной плазмы из дозы консервированной крови, заготовленной в счетверенные гемоконтейнеры производства Macopharma.pdf");
        putDriveName(m,"1y7G3nn-hOONqEoiVeykubZPTpfroMbrx","СОП031.25-01ПК Алгоритм действий персонала при разрыве гемакона с кровью во время центрифугирования.pdf");
        putDriveName(m,"1DZILqNaFILuUAeljhc3abY5XQ2MOhBOv","СОП032.25-03ПК Выполнение процедуры сбора тромбоцитного концентрата аферезного фильтрованного на аппарате Trima Accel.pdf");
        putDriveName(m,"1cnhI1CPYEObGDTiTXEYFxjq2_p4cc7eK","СОП033.25-03ПК Выполнение процедуры вирусинактивации тромбоцитного концентрата в добавочном растворе в камере светового облучения Mirasol.pdf");
        putDriveName(m,"1hf50GFoKDAKejARUBQkwpYejs7sE1NKB","СОП034.25-03ПК Выполнение процедуры сбора плазмы на аппарате PCS-2.pdf");
        putDriveName(m,"1RxcLcpcgo4erof4No6obNp0TbrKs5QUk","СОП035.25-03ПК Выполнение процедуры сбора двойной дозы эритроцитов на аппарате MCS+.pdf");
        putDriveName(m,"1TZ8HToQtQ-67p9B9ppEDuiGXSMPxuJ48","СОП036.25-03ПК Выполнение процедуры сбора плазмы на аппарате Autopheresis-C (Вахter).pdf");
        putDriveName(m,"1e6n5bS_P8TdL1spp-v-mm6cJ-QC6SiFD","СОП037.25-03ПК Выполнение процедуры вирусинактивации свежезамороженой плазмы на аппарате Macotronic-B2.pdf");
        putDriveName(m,"1p0unDh5o_e8WuXN-XilvrKpNWoefLI7w","СОП039.25-03ПК Получение отмытых эритроцитов на аппарате Haemonetics ACP 215.pdf");
        putDriveName(m,"1jRa_fcurTqnEqqrcoROEzWJG9zZwf-P4","СОП040.25-03ПК Глицеролизация эритроцитов на аппарате Haemonetics ACP 215.pdf");
        putDriveName(m,"1XOO5BotPi0_yCFuR5YivVqDpZMfLXIAb","СОП041.25-03ПК Деглицеролизация эритроцитов на аппарате Haemonetics ACP 215.pdf");
        putDriveName(m,"1DdGcvoPZFc_MvswJtaeJ-H9sZ86Eh3eB","СОП043.25-01ПК Изготовление криопреципитата из дозы плазмы.pdf");
        putDriveName(m,"1dy7BimdI-ufTi5DzU4g6mo0UVaNaJ2nk","СОП044.25-01ПК Работа на аппарате для стерильного соединения пластиковых магистралей.pdf");
        putDriveName(m,"1-O-M0JCztBVBdWDIqd_zn7U0w5PT2T4v","СОП045.25-01ПК Работа с запаивателем пластиковых магистралей.pdf");
        putDriveName(m,"1Uomm9aGOXKh1MdXyJmzamzS5Ha1_0zTD","СОП046.25-01ПК Оказание помощи донору, в случае возникновения реакции или осложнения.pdf");
        putDriveName(m,"1vliP9iP4S2A8lmphyz0HaXaVDnYshU7R","СОП047.25-01ПК Отбор образцов компонентов крови для проведения исследований по контролю качества.pdf");
        putDriveName(m,"1kdJtj7BAsSiCy_ec2EUi49QlhqFJrt2C","СОП049.25-01ПК Работа с дистиллятором для получения воды очищенной (дистиллированной), используемой в лабораторных процессах.pdf");
        putDriveName(m,"1wJ8YsYXA2x6BRxaMi2LmJ5buSpM5AWDb","СОП050.2501ПК Подсчёт лейкоцитов на гематологическом анализаторе «Адам».pdf");
        putDriveName(m,"1yeZKNFhcxULenpWZtKQnzkpH0zbCMF04","СОП051.25-03ПК Определение группы крови и резус-фактора у доноров с применением моноклональных антител и стандартных эритроцитов.pdf");
        putDriveName(m,"1aNeCQvPFnYo5ApzrH9-ygaUX7fgc6Bl7","СОП052.25-03ПК Фенотипирование крови человека (донора) по групповым системам Резус, Келл (цоликлонами).pdf");
        putDriveName(m,"18s-BC98YHcSeUGCdqDsNZS2yNzi-jZIR","СОП053.25-03ПК Определение иммунных антиэритроцитарных антител (скрининг антител) доноров.pdf");
        putDriveName(m,"1Yi_gBM1L_xV8Eh6tMU9G0R1nY-3iC7Ab","СОП054.25-01ПК Определение группы крови и резус-фактора методом колоночной агглютинации (перекрестный метод) доноров.pdf");
        putDriveName(m,"14v1qG5xMnQPm9d_K-aZ-RQKNqPomn5WP","СОП055.25-03ПК Фенотипирование крови человека по групповым системам Резус, Келл (метод колоночной агглютинации) доноров.pdf");
        putDriveName(m,"1I9NE6dDDeGB1KNiHr6qgUNNyIsZtnTrC","СОП056.25-01ПК Определение иммунных антиэритроцитарных антител (идентификация антител) доноров.pdf");
        putDriveName(m,"1ryqhNFulr7v-SQEG4vrGPu3wWFCAAoX1","СОП057.25-01ПК Определение слабого,вариантного антигена D  в реакции   непрямой пробы Кумбса (НАГТ) доноров.pdf");
        putDriveName(m,"1I61zk2HU4o9BavvDRv-UftN6-FkU0DgX","СОП058.25-01ПК Иммуногематологическое обследование крови доноров на автоматизированном иммуногематологическом анализаторе.pdf");
        putDriveName(m,"1gPoz0mVGmTkA7rJ1CKn1uLD6bbaTdqLB","СОП059.25-01ПК Алгоритм действий персонала в случае повреждения пробирок с кровью в процессе центрифугирования.pdf");
        putDriveName(m,"17FEAPoxPtD4Nf1wNLC4Ei4godE6CWjIq","СОП060.25-01ПК Определение активности фактора VIII на полуавтоматическом коагулометре «Technology Solution 4».pdf");
        putDriveName(m,"1E6ZxAGUJWzFKsO-bq3D771BEGcSr7AmV","СОП061.25-01ПК Определение концентрации фибриногена на полуавтоматическом коагулометре «Technology Solution 4».pdf");
        putDriveName(m,"1QZAUWIDNW8yvbqFzZqyA-8J_cFOsylFC","СОП062.25-01ПК Первичное определение группы крови и резус-фактора пациентов с применением моноклональных антител.pdf");
        putDriveName(m,"19uNp6TOUpZ8Lxq4AOje-Jlz0yq97l-aE","СОП063.25-01ПК Подтверждающее определение группы крови и резус-фактора пациентов с применением моноклональных антител и стандартных эритроцитов.pdf");
        putDriveName(m,"12Zhgg3Zb8EkxY2LklN_CSiVk3JBxuR_l","СОП064.25-01ПК Определение группы крови и резус-фактора методом колоночной агглютинации (перекрестный метод) пациентов.pdf");
        putDriveName(m,"1Wmb7Fhcr6osEv3TS-BQrXD9ijqrqd2D1","СОП065.25-01ПК Фенотипирование крови человека (пациента) по групповым системам Резус, Келл (цоликлонами).pdf");
        putDriveName(m,"1tq59Lh2xs6406HXS7CP4OoQuxcm92jfp","СОП066.25-01ПК Фенотипирование крови человека по групповым системам Резус, Келл (метод колоночной агглютинации) пациентов.pdf");
        putDriveName(m,"17eRJlQhKBgDj-bIseMBpAOTFIDQh-jTc","СОП067.25-01ПК Определение иммунных антиэритроцитарных антител    (скрининг антител) пациентов.pdf");
        putDriveName(m,"1JvJUSjab3h5dJ56go-r6z41dBTDcCggR","СОП068.25-01ПК Определение иммунных антиэритроцитарных антител (идентификация антител) пациентов.pdf");
        putDriveName(m,"1_NSYG870NnbhuhQsH0rX_RhwyxFy6-j_","СОП069.25-01ПК Иммуногематологическое обследование крови пациентов на автоматизированном иммуногематологическом анализаторе.pdf");
        putDriveName(m,"1RDgeK0uOL_ok4WvRgkwv-ZNxU7mRfeai","СОП070.25-01ПК Проведение проб на совместимость крови донора и реципиента.pdf");
        putDriveName(m,"1lGrqyySF1Js_o6yejA_WHkh0102F1qr3","СОП071.25-01ПК Проведение проб на индивидуальную совместимость крови донора и реципиента.pdf");
        putDriveName(m,"1rUORWyXe2VHxz8f4psNiX2_MthdgVR0O","СОП072.25-03ПК Проведение внутрилабораторного контроля качества иммуногематологических исследований.pdf");
        putDriveName(m,"1C27FgwXWIWFCFtgxyqyKK5vo2v2cFfUx","СОП073.25-03ПК Карантинизация свежезамороженной плазмы.pdf");
        putDriveName(m,"1JuA7B3ipBPs3IHEArC4vofSwHK1v6XTe","СОП075.25-01ПК Транспортировка донорской крови и её компонентов между медицинскими учреждениями с помощью термоконтейнеров.pdf");
        putDriveName(m,"1hEai6AcbXvsMNJijs-AfSD4MRni7TKxl","СОП076.25-01ПК Расчет планового запаса компонентов донорской крови.pdf");
        putDriveName(m,"1N0AawA-Y66pOodb4Jxz4W3ZVieb1D6g1","СОП077.25-01ПК Плановое обеспечение компонентами донорской крови.pdf");
        putDriveName(m,"1T0yYjXIBSQMAcy6Av3LmQ80CrkH5-6Wh","СОП078.25-01ПК Экстренное обеспечение компонентами донорской крови.pdf");
        putDriveName(m,"116pvoRGsdABRxjQhmpxwQ91-GODbsvje","СОП079.25-03ПК Заказ и выдача продукции в экспедиции отделения переливания крови.pdf");
        putDriveName(m,"1iaW5tREmGyHyR_ac_J-18RwoK1P8Ewxw","СОП080.25-01ПК Хранение компонентов крови в экспедиции отделения переливания крови.pdf");
        putDriveName(m,"1_BqCxr2o6PvzCpHaUNJS9qh5L4LAnVg8","СОП081.25-03ПК Выпуск готовой продукции, с дальнейшей передачей компонентов крови для хранения в экспедицию или на карантинное хранение.pdf");
        putDriveName(m,"1Scc1hIQ0dXNPpryrKD7Pv80xZbU0IxO6","СОП082.25-01ПК Обеспечение прослеживаемости и идентификации компонента донорской крови.pdf");
        putDriveName(m,"1DBXnyp1uyCdjRgXQ6nbr-SrB0qmQPKt7","СОП083.25-01ПК Макроскопическая оценка компонентов донорской крови.pdf");
        putDriveName(m,"1KkJVmRl-1tTLa05wFKF7dKmWyMXcJh--","СОП084.25-03ПК Подготовка компонентов донорской крови с использованием аппарата для быстрого размораживания, подогрева и хранения в теплом виде плазмы, крови и инфузионных растворов.pdf");
        putDriveName(m,"1vMwXf00ZD0Dl1c8I1o1f59Z1xa3liqV6","СОП085.25-03ПК Возврат компонентов донорской крови в экспедицию.pdf");
        putDriveName(m,"1XOjdWII1ijcDa2ufSYL7wJDkM6hMTSwt","№002 Дезинфекция предметных стекол.pdf");
        putDriveName(m,"1RaUtWI2gXbVQjaUHcU1DOh-PfJuuIdKZ","№003 Техника паразитологической (микроскопической) диагностики малярии. Подготовка предметных стекол..pdf");
        putDriveName(m,"1_-VR1Ys9paNAiGE9Vy3ncwd_BdNYDoL7","№004 Дезинфекция капилляров Панченкова.pdf");
        putDriveName(m,"1SI_SzCIpVQgxvL5_9Mg4AHlgWgU1sssY","№005 Обеззараживание биологического материала и изделий медицинского назначения, контаминированных микроорганизмами II-IV групп патогенности с использованием стерилизатора парового автоматического с во.pdf");
        putDriveName(m,"1RJyjES8q9Ki9yoX_9PM4wmpQ_TDc5zQw","№006 Эпидемиологическая безопасность при эксплуатации пневматической почты.pdf");
        putDriveName(m,"1BWvTkQ7REDg6md6lPh4WDcIEJNi7umkm","№007 Применение системы безведерной уборки СВЕП на базе комплексных тележек ОРИГО 2 от Vileda Professional (Германия) для проведения влажной текущей и генеральной уборок, а также дезинфекции.pdf");
        putDriveName(m,"11iLVa81U9WD-nRJuN_rtXiefmH4DRdnp","№008 Обращение с отходами класса В в клинико-диагностической лаборатории.pdf");
        putDriveName(m,"1eqDmGj-Bxz4CJL_OrqbmYVtJ5SE0rV-i","№009 Обращение с отходами класса Б в клинико-диагностической лаборатории.pdf");
        putDriveName(m,"1oPvjzmVgE03y007zU6AgbbA-CnUj0b-X","№014 Выполнение исследования Качественное выявление антигена коронавируса SARS-CoV-2 в мазках из носоглотки или ротоглотки человека с использованием иммунохроматографических тест-систем.pdf");
        putDriveName(m,"1Xg4WuQwAe0OtuHz96SPffQjZy2_FF_Od","№015 Порядок преаналитического этапа при заборе материала для диагностики  коронавирусной инфекции COVID-19 методом ПЦР.pdf");
        putDriveName(m,"1jATxSUROR1FYJ2uQHAMjHdTnOuHmNHXJ","№253.pdf");
        putDriveName(m,"1jFWFM_qSatMCz9oQ2xnbmQ7qJlIdWJ9B","№361.pdf");
        putDriveName(m,"1SGTrtClGxFRy7pLwyOMNu3XHlRng2QcH","№553 Обработка аппаратов искусственной вентиляции легких  Datex-Ohmeda Engstrom Pro, CARESCAPE R860, Engstrom Carestation.pdf");
        putDriveName(m,"1_EJ1E4qdwcSEejs1g9jbX7updeTTUXjv","№553 Обработка наркозно-дыхательного аппарата GE Carestation 620 A1.pdf");
        putDriveName(m,"1v89gMxNXfcuvXUM3P1fkS2xwWMg6zhlw","№553 Обработка портативных аппаратов искусственной вентиляции легких Drager Carina.pdf");
        putDriveName(m,"1C3RaC3DGMDY8fmRX44dITil9583qoYAi","№553 Обработка портативных аппаратов искусственной вентиляции легких Drager Oxylog 3000 plus.pdf");
        putDriveName(m,"17IownyqQgYLCDtFMbn1N_a32MWfzscV_","№553 Drager Savina, Drager Savina 300, Evita V300.pdf");
        putDriveName(m,"1n9v0PSxhZYX1pURGfftGyLl6hIFBGv5O","№А-1-ПР-1.26 Алгоритм маршрутизации пациента с подозрением на внебольничную пневмонию и острое нарушение мозгового кровообращения.pdf");
        putDriveName(m,"1Mbl-_YlJfuJqODTmCYnKUpwWcAlTvBhR","№А-1-ПР-2.26 Алгоритм маршрутизации пациента с подозрением на острый коронарный синдром и внебольничную пневмонию.pdf");
        putDriveName(m,"19vPYwx1EAa6HDQTvJs0BoKZZ0YIWvC6U","№А-1-ПР-3.26 Алгоритм маршрутизации пациента с подозрением на инфекционный гастроэнтероколит_ОРВИ_COVID-19.pdf");
        return m;
    }

    private static void putDriveName(Map<String,String> m,String driveId,String fileName){
        m.put(canonFileName(fileName),driveId);
    }

    private static String canonFileName(String s){
        String n=SopClassifier.norm(s==null?"":s).replace('_',' ');
        n=n.replaceFirst("\\.(pdf|docx?|rtf)$","");
        return n.replaceAll("\\s+"," ").trim();
    }

    static boolean matches(SopDocument d,String group){
        if(d==null||group==null)return true;
        Set<String> set;
        switch(group){
            case "Врачи": set=DOCTORS; break;
            case "Средний медицинский персонал": set=NURSES; break;
            case "Младший медицинский персонал": set=JUNIOR; break;
            case "Немедицинский персонал": set=NONMED; break;
            case "Весь персонал": set=ALL_STAFF; break;
            default:return true;
        }
        if(set.contains(d.key))return true;
        String mappedDriveId=DRIVE_ID_BY_NAME.get(canonFileName(d.fileName));
        if(mappedDriveId!=null&&set.contains(mappedDriveId))return true;

        // Safe fallback for newly added documents whose title itself explicitly
        // contains the personnel group. Current files are indexed by Drive ID.
        String hay=SopClassifier.norm(d.title+" "+d.fileName+" "+d.keywords);
        String needle=SopClassifier.norm(group);
        if(hay.contains(needle))return true;
        if("Врачи".equals(group)&&(hay.contains("врач")||hay.contains("врачеб")))return true;
        if("Весь персонал".equals(group)&&(hay.contains("весь персонал")||hay.contains("все сотрудники")||hay.contains("все работники")))return true;
        return false;
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
        Uri directory=DocumentsContract.buildDocumentUriUsingTree(tree,parent);
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent);
        ContentResolver resolver=c.getContentResolver();

        String[] projection={
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE
        };

        CountDownLatch providerChanged=new CountDownLatch(1);
        ContentObserver observer=new ContentObserver(new Handler(Looper.getMainLooper())){
            @Override public void onChange(boolean selfChange){
                providerChanged.countDown();
            }
            @Override public void onChange(boolean selfChange,Uri uri){
                providerChanged.countDown();
            }
        };

        try{
            resolver.registerContentObserver(children,true,observer);
        }catch(Exception ignored){}

        ArrayList<SopDocument> latest=null;
        boolean lastLoading=false;

        try{
            // Each pass obtains a brand-new provider client.  This is important for
            // Google Drive: a long-lived SAF provider connection may keep returning
            // the directory snapshot that existed when the app session was opened.
            // Releasing and reacquiring the provider mirrors what happens after
            // leaving and reopening the app.
            for(int attempt=0;attempt<5;attempt++){
                if(attempt>0){
                    long waitMs=attempt==1?1200L:attempt==2?1800L:attempt==3?2500L:3200L;
                    try{
                        if(attempt==1)providerChanged.await(waitMs,TimeUnit.MILLISECONDS);
                        else Thread.sleep(waitMs);
                    }catch(InterruptedException e){
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }

                ContentProviderClient client=null;
                Cursor cur=null;
                ArrayList<SopDocument> current=new ArrayList<>();
                boolean loading=false;
                try{
                    client=resolver.acquireUnstableContentProviderClient(tree.getAuthority());
                    if(client==null)throw new IllegalStateException("Не удалось подключиться к Google Drive.");

                    // User-initiated refresh: explicitly ask the provider to discard /
                    // update stale cloud content before every fresh query.
                    try{client.refresh(directory,Bundle.EMPTY,null);}catch(Exception ignored){}
                    try{client.refresh(children,Bundle.EMPTY,null);}catch(Exception ignored){}

                    cur=client.query(children,projection,Bundle.EMPTY,null);
                    if(cur==null)throw new IllegalStateException("Google Drive не вернул список документов.");

                    Bundle extras=cur.getExtras();
                    loading=extras!=null&&extras.getBoolean(DocumentsContract.EXTRA_LOADING,false);

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
                        current.add(new SopDocument(id,name,title,category,type,keywords,doc.toString(),mime,mod,size));
                    }
                }finally{
                    if(cur!=null)try{cur.close();}catch(Exception ignored){}
                    if(client!=null)try{client.close();}catch(Exception ignored){}
                }

                latest=current;
                lastLoading=loading;

                // If the cloud provider says its network load is complete, still do
                // at least three independent sessions before accepting the result.
                // This avoids the stale first snapshot that Google Drive can return
                // while the app remains open.
                if(!loading&&attempt>=2)break;
            }
        }finally{
            try{resolver.unregisterContentObserver(observer);}catch(Exception ignored){}
        }

        if(lastLoading)throw new IllegalStateException("Google Drive ещё обновляет содержимое папки. Повторите проверку через несколько секунд.");
        if(latest==null)latest=new ArrayList<>();
        latest.sort(Comparator.comparing(d->d.title.toLowerCase(Locale.ROOT)));
        return latest;
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
    int deleteMissing(Set<String> presentKeys){
        SQLiteDatabase d=getWritableDatabase();
        ArrayList<String> missing=new ArrayList<>();
        try(Cursor c=d.query("docs",new String[]{"doc_key"},null,null,null,null,null)){
            while(c.moveToNext()){
                String key=c.getString(0);
                if(!presentKeys.contains(key))missing.add(key);
            }
        }
        for(String key:missing){
            d.delete("history","doc_key=?",new String[]{key});
            d.delete("docs","doc_key=?",new String[]{key});
        }
        return missing.size();
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
    boolean hasRecentChange(String key,int hours){
        long since=System.currentTimeMillis()-hours*60L*60L*1000L;
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT 1 FROM changes WHERE doc_key=? AND changed_at>=? LIMIT 1",
                new String[]{key,Long.toString(since)})){
            return c.moveToFirst();
        }
    }
    static final class ChangeEvent{
        final String type,key,title;final long changedAt;
        ChangeEvent(String type,String key,String title,long changedAt){this.type=type;this.key=key;this.title=title;this.changedAt=changedAt;}
    }
    List<ChangeEvent> recentChangesWithinHours(int hours,int limit){
        long since=System.currentTimeMillis()-hours*60L*60L*1000L;
        ArrayList<ChangeEvent> out=new ArrayList<>();
        String limitSql=limit>0?Integer.toString(limit):null;
        try(Cursor c=getReadableDatabase().query("changes",new String[]{"event_type","doc_key","title","changed_at"},
                "changed_at>=?",new String[]{Long.toString(since)},null,null,"changed_at DESC",limitSql)){
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

            // A baseline is only a truly empty local catalogue.  Do not rely on
            // baseline_done: that preference can be lost or reset while the DB
            // still contains the already-known SOPs.
            boolean baseline=db.count()==0;
            int added=0,updated=0;
            ArrayList<SopDocument> changed=new ArrayList<>();

            HashSet<String> presentKeys=new HashSet<>();
            for(SopDocument d:scanned){
                presentKeys.add(d.key);
                SopDocument old=db.get(d.key);
                if(old==null){
                    db.upsert(d);
                    if(!baseline){
                        db.recordChange("NEW",d);
                        added++;
                        changed.add(d);
                    }
                }else{
                    boolean modified=(d.lastModified>0&&old.lastModified>0&&d.lastModified!=old.lastModified)||
                            (d.size>0&&old.size>0&&d.size!=old.size);
                    db.upsert(d);
                    if(!baseline&&modified){
                        db.recordChange("UPDATED",d);
                        updated++;
                        changed.add(d);
                    }
                }
            }

            // Mirror deletions from the selected Google Drive folder.
            // If a document no longer exists in Drive, remove it from the local
            // catalogue (and history) so counters, search and categories stay exact.
            int removed=baseline?0:db.deleteMissing(presentKeys);

            long now=System.currentTimeMillis();

            // One-time repair for documents that were already added to the local
            // catalogue by the buggy build but never entered in the 48h changes log.
            // A recently modified Drive document without a recent change event is
            // restored as NEW.  This also recovers the four currently missed SOPs.
            boolean repairDone=p.getBoolean("repair_recent_events_v28",false);
            if(!baseline&&!repairDone){
                long since=now-48L*60L*60L*1000L;
                for(SopDocument d:scanned){
                    if(d.lastModified>0&&d.lastModified>=since&&!db.hasRecentChange(d.key,48)){
                        db.recordChange("NEW",d);
                        added++;
                        changed.add(d);
                    }
                }
            }

            p.edit()
                    .putBoolean("baseline_done",true)
                    .putBoolean("repair_recent_events_v28",true)
                    .putLong("last_sync_ms",now)
                    .putString("last_sync_text",new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date(now)))
                    .apply();

            if(!baseline&&added+updated>0)notifyChanges(c,added,updated,changed);
            db.close();

            if(baseline)return new Result(0,0,scanned.size(),"Папка подключена. В каталог добавлено "+scanned.size()+" документов.");
            if(added==0&&updated==0&&removed==0)return new Result(0,0,scanned.size(),"Изменений не найдено.");
            String message="Проверка завершена. Новых: "+added+", обновлено: "+updated;
            if(removed>0)message+=", удалено: "+removed;
            message+=".";
            return new Result(added,updated,scanned.size(),message);
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
