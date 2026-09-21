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

        navAll.setOnClickListener(v->{selectedCategory=null;currentPage=PAGE_ALL;renderCurrentPage(0);});
        navGroups.setOnClickListener(v->{selectedCategory=null;currentPage=PAGE_GROUPS;renderCurrentPage(0);});
        navHistory.setOnClickListener(v->{selectedCategory=null;currentPage=PAGE_HISTORY;renderCurrentPage(0);});

        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int b,int c){
                String q=s.toString().trim();
                if(q.isEmpty())renderCurrentPage(0); else renderSearch(q);
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
        String q=search==null?"":search.getText().toString().trim();
        if(q.isEmpty())renderCurrentPage(0); else renderSearch(q);
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
        db.markViewed(d.key);
        Toast.makeText(this,"Загрузка документа…",Toast.LENGTH_SHORT).show();
        executor.submit(() -> {
            try{
                File local=downloadToCache(d);
                Uri localUri=Uri.parse("content://ru.sopnavigator.app.files/"+Uri.encode(local.getName()));
                String mime=d.mime==null||d.mime.trim().isEmpty()?mimeForFile(d.fileName):d.mime;
                runOnUiThread(() -> {
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
                            Toast.makeText(this,"Документ скачан, но на устройстве нет приложения для его открытия.",Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }catch(Exception e){
                String m=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
                runOnUiThread(() -> Toast.makeText(this,"Не удалось скачать документ из Google Drive. "+m,Toast.LENGTH_LONG).show());
            }
        });
    }

    private File downloadToCache(SopDocument d) throws Exception{
        File dir=new File(getFilesDir(),"sop_cache");
        if(!dir.exists()&&!dir.mkdirs())throw new IOException("Не удалось создать локальное хранилище.");
        String safe=safeFileName(d.fileName);
        File target=new File(dir,safe);
        Uri src=Uri.parse(d.uri);

        // Reuse an already downloaded copy while its metadata still matches.
        if(target.isFile()&&target.length()>0&&d.size>0&&target.length()==d.size)return target;

        File tmp=new File(dir,safe+".part");
        if(tmp.exists())tmp.delete();
        try(InputStream in=getContentResolver().openInputStream(src)){
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
                byte[] buf=new byte[64*1024];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
            }
            tmp.delete();
        }
        return target;
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
