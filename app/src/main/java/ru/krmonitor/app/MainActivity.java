package ru.krmonitor.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
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

    private void buildUi() {
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(14),dp(14),dp(10));
        root.setBackgroundColor(Color.WHITE);

        TextView title=new TextView(this);
        title.setText("Монитор клинических рекомендаций");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(31,78,120));
        title.setTypeface(null,1);
        root.addView(title);

        TextView subtitle=new TextView(this);
        subtitle.setText("Автоматическая проверка: каждый будний день в 07:00");
        subtitle.setTextSize(14);
        subtitle.setPadding(0,dp(4),0,dp(8));
        root.addView(subtitle);

        status=new TextView(this);
        status.setTextSize(14);
        status.setPadding(0,0,0,dp(8));
        root.addView(status);

        Button sync=new Button(this);
        sync.setText("Проверить сейчас");
        sync.setAllCaps(false);
        root.addView(sync,new LinearLayout.LayoutParams(-1,-2));

        TextView recentTitle=new TextView(this);
        recentTitle.setText("Недавно добавленные КР");
        recentTitle.setTextSize(17);
        recentTitle.setTypeface(null,1);
        recentTitle.setTextColor(Color.rgb(31,78,120));
        recentTitle.setPadding(0,dp(10),0,dp(4));
        root.addView(recentTitle);

        recentList=new TextView(this);
        recentList.setTextSize(14);
        recentList.setPadding(dp(6),0,dp(6),dp(8));
        root.addView(recentList);

        search=new EditText(this);
        search.setHint("Поиск по названию или ID");
        search.setSingleLine(true);
        root.addView(search,new LinearLayout.LayoutParams(-1,-2));

        contentHost=new FrameLayout(this);
        root.addView(contentHost,new LinearLayout.LayoutParams(-1,0,1));
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
        status.setText("КР в реестре: "+all.size()+"   •   Последняя проверка: "+last+"\nСледующая: "+DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(next)));
        renderRecent();
        String q=search==null?"":search.getText().toString().trim();
        if(q.isEmpty()) renderCurrentPage(0); else renderSearch(q);
    }

    private void renderRecent() {
        List<Recommendation> recent=db.recentAdded(5);
        if(recent.isEmpty()) {
            recentList.setText("Новых КР после установки приложения пока не обнаружено.");
            return;
        }
        StringBuilder sb=new StringBuilder();
        for(int i=0;i<recent.size();i++) {
            Recommendation r=recent.get(i);
            if(i>0) sb.append("\n");
            sb.append("• ").append(r.title).append("  (ID: ").append(r.id).append(")");
        }
        recentList.setText(sb.toString());
    }

    private void renderCurrentPage(int direction) {
        if(contentHost==null) return;
        if(currentPage==PAGE_ALL) showContent(makeListPage("Все клинические рекомендации",all,false),direction);
        else if(currentPage==PAGE_HISTORY) showContent(makeHistoryPage(),direction);
        else if(selectedProfile!=null) showContent(makeProfileListPage(selectedProfile),direction);
        else showContent(makeProfilesPage(),direction);
    }

    private View makeProfilesPage() {
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(pageTitle("Профили клинических рекомендаций"));
        TextView hint=new TextView(this);
        hint.setText("Свайп вправо — все КР   •   свайп влево — история");
        hint.setTextSize(12);
        hint.setTextColor(Color.DKGRAY);
        hint.setPadding(0,0,0,dp(5));
        outer.addView(hint);

        LinkedHashMap<String,List<Recommendation>> groups=ProfileClassifier.group(all);
        ScrollView scroll=new ScrollView(this);
        LinearLayout rows=new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows,new ScrollView.LayoutParams(-1,-2));

        ArrayList<String> visible=new ArrayList<>();
        for(String p:ProfileClassifier.PROFILES) if(!groups.get(p).isEmpty()) visible.add(p);
        for(int i=0;i<visible.size();i+=2) {
            LinearLayout row=new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0,dp(2),0,dp(2));
            for(int j=0;j<2;j++) {
                if(i+j<visible.size()) {
                    String p=visible.get(i+j);
                    Button b=profileButton(p,groups.get(p).size());
                    row.addView(b,new LinearLayout.LayoutParams(0,-2,1));
                } else {
                    Space s=new Space(this);
                    row.addView(s,new LinearLayout.LayoutParams(0,1,1));
                }
            }
            rows.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
        outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        return outer;
    }

    private Button profileButton(String profile,int count) {
        Button b=new Button(this);
        b.setAllCaps(false);
        b.setText(profile+"\n"+count+" КР");
        b.setTextSize(12);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(5),dp(5),dp(5),dp(5));
        b.setOnClickListener(v -> { selectedProfile=profile; renderCurrentPage(0); });
        return b;
    }

    private View makeProfileListPage(String profile) {
        List<Recommendation> recs=ProfileClassifier.group(all).get(profile);
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        Button back=new Button(this);
        back.setText("← Профили");
        back.setAllCaps(false);
        back.setTextSize(13);
        back.setMinHeight(0);
        back.setMinimumHeight(0);
        back.setPadding(dp(4),dp(3),dp(4),dp(3));
        back.setOnClickListener(v -> { selectedProfile=null; renderCurrentPage(0); });
        outer.addView(back,new LinearLayout.LayoutParams(-1,-2));
        outer.addView(pageTitle(profile+" — "+recs.size()+" КР"));
        addRecommendationList(outer,recs,false);
        return outer;
    }

    private View makeHistoryPage() {
        List<Recommendation> history=db.history(200);
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(pageTitle("История просмотренных КР"));
        TextView hint=new TextView(this);
        hint.setText("Здесь сохраняются КР, найденные через поиск и открытые вами.");
        hint.setTextSize(12);
        hint.setTextColor(Color.DKGRAY);
        hint.setPadding(0,0,0,dp(5));
        outer.addView(hint);
        if(history.isEmpty()) {
            TextView empty=new TextView(this);
            empty.setText("История пока пуста.");
            empty.setTextSize(15);
            empty.setPadding(dp(8),dp(16),dp(8),dp(8));
            outer.addView(empty);
        } else addRecommendationList(outer,history,false);
        return outer;
    }

    private View makeListPage(String heading,List<Recommendation> recs,boolean fromSearch) {
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(pageTitle(heading+" — "+recs.size()));
        addRecommendationList(outer,recs,fromSearch);
        return outer;
    }

    private void renderSearch(String q) {
        String needle=norm(q);
        ArrayList<Recommendation> results=new ArrayList<>();
        for(Recommendation r:all) {
            if(norm(r.title).contains(needle)||norm(r.id).contains(needle)) results.add(r);
        }
        showContent(makeListPage("Результаты поиска",results,true),0);
    }

    private void addRecommendationList(LinearLayout outer,List<Recommendation> recs,boolean fromSearch) {
        ListView list=new ListView(this);
        ArrayList<String> labels=new ArrayList<>();
        for(Recommendation r:recs) labels.add(r.title+"\nID: "+r.id+(PdfManager.isPdf(PdfManager.file(this,r))?"   •   PDF скачан":""));
        list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){
            @Override public View getView(int p,View v,android.view.ViewGroup g){
                TextView t=(TextView)super.getView(p,v,g);
                t.setTextSize(15);
                t.setPadding(dp(8),dp(10),dp(8),dp(10));
                return t;
            }
        });
        list.setOnItemClickListener((p,v,pos,id)->downloadOrOpen(recs.get(pos),fromSearch));
        outer.addView(list,new LinearLayout.LayoutParams(-1,0,1));
    }

    private TextView pageTitle(String text) {
        TextView t=new TextView(this);
        t.setText(text);
        t.setTextSize(17);
        t.setTypeface(null,1);
        t.setTextColor(Color.rgb(31,78,120));
        t.setPadding(dp(2),dp(7),dp(2),dp(6));
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

    private void runSync(Button b) {
        b.setEnabled(false);
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

    private void downloadOrOpen(Recommendation r,boolean fromSearch) {
        if(PdfManager.isPdf(PdfManager.file(this,r))) {
            if(fromSearch) db.markViewed(r.baseId);
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
                    if(fromSearch) db.markViewed(r.baseId);
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
