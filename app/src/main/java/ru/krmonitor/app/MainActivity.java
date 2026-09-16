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
import android.widget.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private DbHelper db;
    private TextView status;
    private TextView recentTitle;
    private TextView recentList;
    private ListView list;
    private EditText search;
    private List<Recommendation> all=new ArrayList<>(), shown=new ArrayList<>();

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
        root.addView(sync,new LinearLayout.LayoutParams(-1,-2));

        recentTitle=new TextView(this);
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

        list=new ListView(this);
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);

        sync.setOnClickListener(v -> runSync(sync));
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int b,int c){filter(s.toString());}
            public void afterTextChanged(Editable e){}
        });
        list.setOnItemClickListener((p,v,pos,id)->downloadOrOpen(shown.get(pos)));
    }

    private void reload() {
        all=db.all();
        filter(search==null?"":search.getText().toString());
        String last=getSharedPreferences("prefs",MODE_PRIVATE).getString("last_sync","ещё не выполнялась");
        long next=getSharedPreferences("prefs",MODE_PRIVATE).getLong("next_alarm",AlarmScheduler.nextWeekday7());
        status.setText("КР в реестре: "+all.size()+"   •   Последняя проверка: "+last+"\nСледующая: "+DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(next)));
        renderRecent();
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

    private void filter(String q) {
        String needle=q.trim().toLowerCase(Locale.ROOT);
        shown=new ArrayList<>();
        ArrayList<String> labels=new ArrayList<>();
        for(Recommendation r:all) {
            if(needle.isEmpty()||r.title.toLowerCase(Locale.ROOT).contains(needle)||r.id.toLowerCase(Locale.ROOT).contains(needle)) {
                shown.add(r);
                labels.add(r.title+"\nID: "+r.id+(PdfManager.isPdf(PdfManager.file(this,r))?"   •   PDF скачан":""));
            }
        }
        list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){
            @Override public View getView(int p,View v,android.view.ViewGroup g){
                TextView t=(TextView)super.getView(p,v,g);
                t.setTextSize(15);
                t.setPadding(dp(8),dp(10),dp(8),dp(10));
                return t;
            }
        });
    }

    private void runSync(Button b) {
        b.setEnabled(false);
        status.setText("Проверяю обновления…");
        executor.submit(() -> {
            SyncEngine.Result r=SyncEngine.sync(getApplicationContext());
            runOnUiThread(() -> {
                b.setEnabled(true);
                reload();
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
        if(PdfManager.isPdf(PdfManager.file(this,r))) { PdfManager.open(this,r); return; }
        Toast.makeText(this,"Скачиваю PDF…",Toast.LENGTH_SHORT).show();
        executor.submit(() -> {
            boolean ok=PdfManager.download(getApplicationContext(),r);
            runOnUiThread(() -> {
                if(ok){ reload(); PdfManager.open(this,r); }
                else Toast.makeText(this,"Не удалось скачать PDF",Toast.LENGTH_LONG).show();
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
