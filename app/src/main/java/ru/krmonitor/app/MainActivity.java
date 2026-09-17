package ru.krmonitor.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.net.Uri;
import android.text.*;
import android.view.*;
import android.view.animation.TranslateAnimation;
import android.widget.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private DbHelper db;
    private TextView status;
    private TextView recentList;
    private EditText search;
    private FrameLayout contentHost;
    private List<Recommendation> all=new ArrayList<>();

    private static final int PAGE_ALL=0;
    private static final int PAGE_PROFILES=1;
    private static final int PAGE_HISTORY=2;
    private int currentPage=PAGE_PROFILES;
    private String selectedProfile=null;
    private float swipeX,swipeY;
    private boolean swipeTracking=false;
    private boolean searchShowsMkb=false;

    private static final int BG=Color.rgb(247,249,252);
    private static final int CARD=Color.WHITE;
    private static final int BLUE=Color.rgb(37,99,199);
    private static final int BLUE_DARK=Color.rgb(25,70,145);
    private static final int BLUE_SOFT=Color.rgb(235,243,255);
    private static final int TEXT=Color.rgb(29,38,52);
    private static final int MUTED=Color.rgb(104,115,132);
    private static final int LINE=Color.rgb(228,233,240);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        db=new DbHelper(this);
        try { SeedImporter.ensureSeeded(this); } catch(Exception e) { Toast.makeText(this,"Ошибка исходного реестра: "+e.getMessage(),Toast.LENGTH_LONG).show(); }
        AlarmScheduler.scheduleNext(this);
        requestNotifyPermission();
        requestExactAlarmPermission();
        buildUi();
        reload();
    }

    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }

    private GradientDrawable rounded(int fill,int stroke,int radius) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if(stroke!=Color.TRANSPARENT) d.setStroke(dp(1),stroke);
        return d;
    }

    private TextView text(String value,float size,int color,boolean bold) {
        TextView t=new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if(bold) t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }

    private void buildUi() {
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(14),dp(16),dp(10));
        root.setBackgroundColor(BG);

        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout heading=new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title=text("КР Навигатор",23,TEXT,true);
        TextView subtitle=text("Автоматическая проверка новых и обновлённых КР в 07:00",12,MUTED,false);
        subtitle.setPadding(0,dp(2),0,0);
        heading.addView(title);
        heading.addView(subtitle);
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));

        TextView sync=text("↻  Проверить",13,BLUE,true);
        sync.setGravity(Gravity.CENTER);
        sync.setPadding(dp(12),dp(9),dp(12),dp(9));
        sync.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,14));
        sync.setClickable(true);
        sync.setFocusable(true);
        header.addView(sync,new LinearLayout.LayoutParams(-2,-2));
        root.addView(header);

        status=text("",12,MUTED,false);
        status.setPadding(0,dp(9),0,dp(9));
        root.addView(status);

        LinearLayout recentHeader=new LinearLayout(this);
        recentHeader.setOrientation(LinearLayout.HORIZONTAL);
        recentHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView recentTitle=text("Новое",15,TEXT,true);
        recentHeader.addView(recentTitle,new LinearLayout.LayoutParams(0,-2,1));
        TextView recentCaption=text("последние добавления",11,MUTED,false);
        recentHeader.addView(recentCaption);
        root.addView(recentHeader);

        recentList=text("",13,TEXT,false);
        recentList.setLineSpacing(dp(2),1f);
        recentList.setPadding(dp(11),dp(9),dp(11),dp(9));
        recentList.setBackground(rounded(CARD,LINE,14));
        LinearLayout.LayoutParams recentLp=new LinearLayout.LayoutParams(-1,-2);
        recentLp.setMargins(0,dp(5),0,dp(10));
        root.addView(recentList,recentLp);

        search=new EditText(this);
        search.setHint("Название, номер КР или код МКБ-10");
        search.setHintTextColor(Color.rgb(145,153,165));
        search.setTextColor(TEXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setPadding(dp(14),0,dp(14),0);
        search.setBackground(rounded(CARD,LINE,15));
        root.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));

        contentHost=new FrameLayout(this);
        LinearLayout.LayoutParams contentLp=new LinearLayout.LayoutParams(-1,0,1);
        contentLp.setMargins(0,dp(6),0,0);
        root.addView(contentHost,contentLp);
        setContentView(root);

        sync.setOnClickListener(v -> runSync(sync));
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int b,int c){
                String q=s.toString().trim();
                if(q.isEmpty()) renderCurrentPage(0); else renderSearch(q);
            }
            public void afterTextChanged(Editable e){}
        });
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if(contentHost!=null) {
            int action=e.getActionMasked();
            if(action==android.view.MotionEvent.ACTION_DOWN) {
                swipeTracking=e.getY()>=contentHost.getTop();
                swipeX=e.getX(); swipeY=e.getY();
            } else if(action==android.view.MotionEvent.ACTION_UP && swipeTracking) {
                float dx=e.getX()-swipeX, dy=e.getY()-swipeY;
                swipeTracking=false;
                if(search.getText().toString().trim().isEmpty() && Math.abs(dx)>dp(90) && Math.abs(dx)>Math.abs(dy)*1.35f) {
                    if(dx>0) swipeRight(); else swipeLeft();
                    return true;
                }
            }
        }
        return super.dispatchTouchEvent(e);
    }

    private void swipeRight() {
        selectedProfile=null;
        if(currentPage>PAGE_ALL) {
            currentPage--;
            renderCurrentPage(-1);
        }
    }

    private void swipeLeft() {
        selectedProfile=null;
        if(currentPage<PAGE_HISTORY) {
            currentPage++;
            renderCurrentPage(1);
        }
    }

    private void reload() {
        all=db.all();
        String last=getSharedPreferences("prefs",MODE_PRIVATE).getString("last_sync","ещё не выполнялась");
        long next=getSharedPreferences("prefs",MODE_PRIVATE).getLong("next_alarm",AlarmScheduler.nextWeekday7());
        status.setText(all.size()+" КР  •  Последняя проверка: "+last+"\nСледующая: "+DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(next)));
        renderRecent();
        String q=search==null?"":search.getText().toString().trim();
        if(q.isEmpty()) renderCurrentPage(0); else renderSearch(q);
    }

    private void renderRecent() {
        List<Recommendation> recent=db.recentAdded(5);
        if(recent.isEmpty()) {
            recentList.setText("Новых КР после установки приложения пока не обнаружено.");
            recentList.setTextColor(MUTED);
            return;
        }
        recentList.setTextColor(TEXT);
        StringBuilder sb=new StringBuilder();
        int max=Math.min(3,recent.size());
        for(int i=0;i<max;i++) {
            Recommendation r=recent.get(i);
            if(i>0) sb.append("\n");
            sb.append("НОВАЯ  ").append(r.title).append("  ·  ").append(r.id);
        }
        if(recent.size()>max) sb.append("\nЕщё ").append(recent.size()-max).append("…");
        recentList.setText(sb.toString());
    }

    private void renderCurrentPage(int direction) {
        if(contentHost==null) return;
        searchShowsMkb=false;
        if(currentPage==PAGE_ALL) showContent(makeListPage("Все КР",all),direction);
        else if(currentPage==PAGE_HISTORY) showContent(makeHistoryPage(),direction);
        else if(selectedProfile!=null) showContent(makeProfileListPage(selectedProfile),direction);
        else showContent(makeProfilesPage(),direction);
    }

    private View makeProfilesPage() {
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout pageHead=new LinearLayout(this);
        pageHead.setOrientation(LinearLayout.HORIZONTAL);
        pageHead.setGravity(Gravity.CENTER_VERTICAL);
        pageHead.addView(pageTitle("Профили"),new LinearLayout.LayoutParams(0,-2,1));
        TextView hint=text("← Все КР     История →",11,MUTED,false);
        pageHead.addView(hint);
        outer.addView(pageHead);

        LinkedHashMap<String,List<Recommendation>> groups=ProfileClassifier.group(all);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout rows=new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0,dp(2),0,dp(10));
        scroll.addView(rows,new ScrollView.LayoutParams(-1,-2));

        ArrayList<String> visible=new ArrayList<>();
        for(String p:ProfileClassifier.PROFILES) if(!groups.get(p).isEmpty()) visible.add(p);
        for(int i=0;i<visible.size();i+=2) {
            LinearLayout row=new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for(int j=0;j<2;j++) {
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(118),1);
                if(j==0) lp.setMargins(0,dp(4),dp(4),dp(4));
                else lp.setMargins(dp(4),dp(4),0,dp(4));
                if(i+j<visible.size()) {
                    String p=visible.get(i+j);
                    row.addView(profileCard(p,groups.get(p).size()),lp);
                } else {
                    Space s=new Space(this);
                    row.addView(s,lp);
                }
            }
            rows.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
        outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        return outer;
    }

    private View profileCard(String profile,int count) {
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(9),dp(9),dp(9),dp(8));
        card.setBackground(rounded(CARD,LINE,16));
        if(Build.VERSION.SDK_INT>=21) card.setElevation(dp(1));
        card.setClickable(true);
        card.setFocusable(true);

        ProfileIconView icon=new ProfileIconView(this,profile);
        LinearLayout.LayoutParams iconLp=new LinearLayout.LayoutParams(dp(44),dp(44));
        iconLp.setMargins(0,0,0,dp(6));
        card.addView(icon,iconLp);

        String displayProfile=profile.replace("-","\u2011");
        TextView name=text(displayProfile,13,TEXT,true);
        name.setGravity(Gravity.CENTER);
        name.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        name.setIncludeFontPadding(false);
        name.setMaxLines(4);
        name.setEllipsize(null);
        name.setHorizontallyScrolling(false);
        name.setLineSpacing(dp(1),1.0f);
        if(Build.VERSION.SDK_INT>=23) {
            name.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
            name.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        }
        card.addView(name,new LinearLayout.LayoutParams(-1,0,1));

        TextView number=text(count+" КР",11,MUTED,false);
        number.setGravity(Gravity.CENTER);
        number.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        number.setPadding(0,dp(5),0,0);
        card.addView(number,new LinearLayout.LayoutParams(-1,-2));

        name.post(() -> fitProfileName(name,displayProfile));
        card.setOnClickListener(v -> { selectedProfile=profile; renderCurrentPage(0); });
        return card;
    }

    private void fitProfileName(TextView view,String value) {
        int available=view.getWidth()-dp(2);
        if(available<=0) return;
        float size=13f;
        String[] words=value.split("\\s+");
        while(size>9.5f) {
            view.setTextSize(size);
            float widest=0f;
            for(String word:words) widest=Math.max(widest,view.getPaint().measureText(word));
            if(widest<=available) break;
            size-=0.5f;
        }
        view.setTextSize(size);
    }

    private String profileIcon(String p) {
        if(p.equals("Кардиология")) return "♥";
        if(p.equals("Сердечно-сосудистая хирургия")) return "↔";
        if(p.equals("Неврология")) return "≋";
        if(p.equals("Нейрохирургия")) return "✦";
        if(p.equals("Травматология и ортопедия")) return "⌁";
        if(p.equals("Хирургия")) return "✚";
        if(p.equals("Гастроэнтерология")) return "◒";
        if(p.equals("Пульмонология")) return "≈";
        if(p.equals("Эндокринология")) return "◈";
        if(p.equals("Инфекционные болезни")) return "✣";
        if(p.equals("Гематология")) return "●";
        if(p.equals("Ревматология")) return "◇";
        if(p.equals("Нефрология")) return "◉";
        if(p.equals("Урология")) return "∪";
        if(p.equals("Акушерство и гинекология")) return "♀";
        if(p.equals("Педиатрия и неонатология")) return "★";
        if(p.equals("Онкология")) return "✦";
        if(p.equals("Офтальмология")) return "◉";
        if(p.equals("Оториноларингология")) return "♪";
        if(p.equals("Дерматология")) return "✧";
        if(p.equals("Психиатрия и наркология")) return "Ψ";
        if(p.equals("Аллергология и иммунология")) return "✤";
        if(p.equals("Стоматология и ЧЛХ")) return "◆";
        if(p.equals("Анестезиология и реаниматология")) return "+";
        if(p.equals("Медицинская реабилитация")) return "↻";
        return "•";
    }

    private View makeProfileListPage(String profile) {
        List<Recommendation> recs=ProfileClassifier.group(all).get(profile);
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head=new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=text("‹  Профили",13,BLUE,true);
        back.setPadding(dp(9),dp(7),dp(9),dp(7));
        back.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,12));
        back.setClickable(true);
        back.setOnClickListener(v -> { selectedProfile=null; renderCurrentPage(0); });
        head.addView(back);
        TextView count=text(recs.size()+" КР",12,MUTED,false);
        LinearLayout.LayoutParams countLp=new LinearLayout.LayoutParams(-2,-2);
        countLp.setMargins(dp(10),0,0,0);
        head.addView(count,countLp);
        outer.addView(head);

        TextView name=pageTitle(profile);
        name.setPadding(dp(2),dp(8),dp(2),dp(5));
        outer.addView(name);
        addRecommendationList(outer,recs);
        return outer;
    }

    private View makeHistoryPage() {
        List<Recommendation> history=db.history(200);
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head=new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(pageTitle("История"),new LinearLayout.LayoutParams(0,-2,1));
        TextView hint=text("← Профили",11,MUTED,false);
        head.addView(hint);
        outer.addView(head);

        TextView caption=text("Все клинические рекомендации, которые вы открывали",12,MUTED,false);
        caption.setPadding(dp(2),0,dp(2),dp(6));
        outer.addView(caption);
        if(history.isEmpty()) {
            TextView empty=text("История пока пуста.",14,MUTED,false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8),dp(30),dp(8),dp(8));
            outer.addView(empty);
        } else addRecommendationList(outer,history);
        return outer;
    }

    private View makeListPage(String heading,List<Recommendation> recs) {
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head=new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(pageTitle(heading),new LinearLayout.LayoutParams(0,-2,1));
        TextView count=text(recs.size()+" КР",12,MUTED,false);
        head.addView(count);
        outer.addView(head);
        addRecommendationList(outer,recs);
        return outer;
    }

    private void renderSearch(String q) {
        String raw=q==null?"":q.trim();
        String needle=norm(raw);
        boolean idQuery=raw.matches("\\d+(?:_\\d+)?");
        boolean mkbQuery=!idQuery && MkbUtils.looksLikeCode(raw);
        searchShowsMkb=mkbQuery;
        ArrayList<Recommendation> results=new ArrayList<>();

        if(idQuery) {
            for(Recommendation r:all) {
                if(raw.equalsIgnoreCase(r.id) || raw.equalsIgnoreCase(r.baseId)) results.add(r);
            }
            // Only when there is no exact ID, allow prefix suggestions for an unfinished number.
            if(results.isEmpty()) {
                for(Recommendation r:all) {
                    if(r.baseId!=null && r.baseId.startsWith(raw)) results.add(r);
                }
            }
        } else {
            for(Recommendation r:all) {
                if(norm(r.title).contains(needle) || MkbUtils.matches(r.mkbCodes,raw)) results.add(r);
            }
        }

        String heading=idQuery ? "КР "+raw : (mkbQuery ? "МКБ-10: "+raw.toUpperCase(Locale.ROOT) : "Результаты поиска");
        showContent(makeListPage(heading,results),0);
    }

    private void addRecommendationList(LinearLayout outer,List<Recommendation> recs) {
        ListView list=new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setClipToPadding(false);
        list.setPadding(0,0,0,dp(8));
        list.setAdapter(new BaseAdapter(){
            @Override public int getCount(){ return recs.size(); }
            @Override public Object getItem(int p){ return recs.get(p); }
            @Override public long getItemId(int p){ return p; }
            @Override public View getView(int p,View convert,ViewGroup parent){
                Recommendation r=recs.get(p);
                LinearLayout wrap=new LinearLayout(MainActivity.this);
                wrap.setPadding(0,dp(3),0,dp(3));

                LinearLayout row=new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12),dp(10),dp(9),dp(10));
                row.setBackground(rounded(CARD,LINE,14));

                LinearLayout labels=new LinearLayout(MainActivity.this);
                labels.setOrientation(LinearLayout.VERTICAL);
                TextView name=text(r.title,14,TEXT,false);
                name.setMaxLines(3);
                String metaText="КР "+r.id+(PdfManager.isPdf(PdfManager.file(MainActivity.this,r))?"  •  PDF скачан":"");
                if(searchShowsMkb && r.mkbCodes!=null && !r.mkbCodes.isEmpty()) metaText += "  •  МКБ-10: "+r.mkbCodes;
                TextView meta=text(metaText,11,MUTED,false);
                meta.setPadding(0,dp(4),0,0);
                labels.addView(name);
                labels.addView(meta);
                row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

                TextView arrow=text("›",23,Color.rgb(170,179,192),false);
                arrow.setGravity(Gravity.CENTER);
                row.addView(arrow,new LinearLayout.LayoutParams(dp(22),-1));
                wrap.addView(row,new LinearLayout.LayoutParams(-1,-2));
                return wrap;
            }
        });
        list.setOnItemClickListener((p,v,pos,id)->downloadOrOpen(recs.get(pos)));
        outer.addView(list,new LinearLayout.LayoutParams(-1,0,1));
    }

    private TextView pageTitle(String value) {
        TextView t=text(value,18,TEXT,true);
        t.setPadding(dp(2),dp(7),dp(2),dp(7));
        return t;
    }

    private void showContent(View view,int direction) {
        contentHost.removeAllViews();
        contentHost.addView(view,new FrameLayout.LayoutParams(-1,-1));
        if(direction!=0) {
            float width=getResources().getDisplayMetrics().widthPixels;
            float from=direction>0?width:-width;
            TranslateAnimation a=new TranslateAnimation(from,0,0,0);
            a.setDuration(180);
            view.startAnimation(a);
        }
    }

    private String norm(String s) {
        return (s==null?"":s).toLowerCase(Locale.ROOT).replace('ё','е').trim();
    }

    private void runSync(TextView b) {
        b.setEnabled(false);
        b.setText("↻  Проверяю…");
        status.setText("Проверяю обновления…");
        final ProgressDialog progress=new ProgressDialog(this);
        progress.setTitle("Проверка клинических рекомендаций");
        progress.setMessage("Получаю актуальный каталог и проверяю новые КР…");
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        executor.submit(() -> {
            SyncEngine.Result result;
            try {
                result=SyncEngine.sync(getApplicationContext());
            } catch(Throwable t) {
                String m=t.getMessage()==null?t.getClass().getSimpleName():t.getMessage();
                result=new SyncEngine.Result(0,0,0,0,0,"Ошибка проверки: "+m,Collections.emptyList());
            }
            final SyncEngine.Result r=result;
            runOnUiThread(() -> {
                try { if(progress.isShowing()) progress.dismiss(); } catch(Exception ignored) {}
                b.setEnabled(true);
                b.setText("↻  Проверить");
                try { reload(); } catch(Exception ignored) {}
                showSyncResult(r);
            });
        });
    }

    private void showSyncResult(SyncEngine.Result r) {
        StringBuilder text=new StringBuilder(r.message);
        if(!r.newRecommendations.isEmpty()) {
            text.append("\n\nНайдены новые КР:");
            int max=Math.min(8,r.newRecommendations.size());
            for(int i=0;i<max;i++) {
                Recommendation rec=r.newRecommendations.get(i);
                text.append("\n• ").append(rec.title).append(" (ID: ").append(rec.id).append(")");
            }
            if(r.newRecommendations.size()>max) text.append("\n…и ещё ").append(r.newRecommendations.size()-max);
        }
        new AlertDialog.Builder(this)
                .setTitle(r.added>0?"Найдены новые КР":"Результат проверки")
                .setMessage(text.toString())
                .setPositiveButton("ОК",null)
                .show();
    }

    private void downloadOrOpen(Recommendation r) {
        if(PdfManager.isPdf(PdfManager.file(this,r))) {
            db.markViewed(r.baseId);
            PdfManager.open(this,r);
            return;
        }
        final ProgressDialog progress=new ProgressDialog(this);
        progress.setMessage("Скачиваю PDF…");
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();
        executor.submit(() -> {
            boolean ok=PdfManager.download(getApplicationContext(),r);
            runOnUiThread(() -> {
                try { if(progress.isShowing()) progress.dismiss(); } catch(Exception ignored) {}
                if(ok){
                    db.markViewed(r.baseId);
                    reload();
                    PdfManager.open(this,r);
                } else new AlertDialog.Builder(this).setTitle("PDF не скачан").setMessage("Не удалось получить PDF для КР «"+r.title+"» (ID: "+r.id+"). Попробуйте повторить позже.").setPositiveButton("ОК",null).show();
            });
        });
    }

    private void requestExactAlarmPermission() {
        if(Build.VERSION.SDK_INT>=31) {
            android.app.AlarmManager am=(android.app.AlarmManager)getSystemService(ALARM_SERVICE);
            boolean prompted=getSharedPreferences("prefs",MODE_PRIVATE).getBoolean("exact_prompted",false);
            if(!am.canScheduleExactAlarms() && !prompted) {
                getSharedPreferences("prefs",MODE_PRIVATE).edit().putBoolean("exact_prompted",true).apply();
                try {
                    Intent i=new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:"+getPackageName()));
                    startActivity(i);
                } catch(Exception ignored) {}
            }
        }
    }

    private void requestNotifyPermission() {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},44);
    }

    @Override protected void onDestroy(){
        super.onDestroy();
        executor.shutdownNow();
        if(db!=null) db.close();
    }
}
