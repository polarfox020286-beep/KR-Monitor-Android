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
    private final BroadcastReceiver syncReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if("ru.krmonitor.app.SYNC_COMPLETE".equals(intent.getAction())) reload();
        }
    };
    private DbHelper db;
    private TextView status;
    private TextView recentList;
    private EditText search;
    private FrameLayout contentHost;
    private LinearLayout landscapeSidebar;
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
        if(Build.VERSION.SDK_INT>=30) getWindow().setDecorFitsSystemWindows(false);
        db=new DbHelper(this);
        try { SeedImporter.ensureSeeded(this); } catch(Exception e) { Toast.makeText(this,"Ошибка исходного реестра: "+e.getMessage(),Toast.LENGTH_LONG).show(); }
        AlarmScheduler.scheduleNext(this);
        requestNotifyPermission();
        requestExactAlarmPermission();
        buildUi();
        reload();
    }

    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }

    private int screenWidthDp() {
        int value=getResources().getConfiguration().screenWidthDp;
        if(value>0) return value;
        return Math.round(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);
    }

    private int screenHeightDp() {
        int value=getResources().getConfiguration().screenHeightDp;
        if(value>0) return value;
        return Math.round(getResources().getDisplayMetrics().heightPixels/getResources().getDisplayMetrics().density);
    }

    private boolean compactUi(){ return screenWidthDp()<360; }
    private boolean tabletUi(){ return screenWidthDp()>=600; }
    private boolean isLandscapeUi(){ return screenWidthDp()>screenHeightDp(); }

    private int responsive(int compact,int phone,int tablet) {
        return compactUi()?compact:(tabletUi()?tablet:phone);
    }

    private int profileColumns() {
        int w=screenWidthDp();
        if(w<300) return 1;
        if(w<600) return 2;
        if(w<900) return 3;
        return 4;
    }

    private int dialogWidthPx() {
        int available=getResources().getDisplayMetrics().widthPixels-dp(24);
        return Math.min(available,dp(420));
    }

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
        if(isLandscapeUi()) {
            buildLandscapeUi();
            return;
        }
        final boolean compact=compactUi();
        final boolean tablet=tabletUi();

        FrameLayout shell=new FrameLayout(this);
        shell.setBackgroundColor(BG);
        if(Build.VERSION.SDK_INT>=30) {
            shell.setOnApplyWindowInsetsListener((v,insets) -> {
                android.graphics.Insets bars=insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                );
                v.setPadding(bars.left,bars.top,bars.right,bars.bottom);
                return insets;
            });
        }

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int hPad=responsive(10,16,24);
        root.setPadding(dp(hPad),dp(responsive(10,14,18)),dp(hPad),dp(10));
        root.setBackgroundColor(BG);

        int maxWidthDp=tablet?760:screenWidthDp();
        int rootWidth=tablet?Math.min(getResources().getDisplayMetrics().widthPixels-dp(32),dp(maxWidthDp)):FrameLayout.LayoutParams.MATCH_PARENT;
        FrameLayout.LayoutParams rootLp=new FrameLayout.LayoutParams(rootWidth,FrameLayout.LayoutParams.MATCH_PARENT,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        shell.addView(root,rootLp);

        LinearLayout header=new LinearLayout(this);
        header.setOrientation(compact?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        header.setGravity(compact?Gravity.LEFT:Gravity.CENTER_VERTICAL);

        LinearLayout heading=new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title=text("КР Навигатор",responsive(21,23,26),TEXT,true);
        TextView subtitle=text("Автоматическая проверка новых и обновлённых КР в 07:00",responsive(11,12,13),MUTED,false);
        subtitle.setPadding(0,dp(2),0,0);
        subtitle.setMaxLines(2);
        heading.addView(title);
        heading.addView(subtitle);

        if(compact) {
            header.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        } else {
            header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        }

        TextView sync=text("↻  Проверить",responsive(12,13,14),BLUE,true);
        sync.setGravity(Gravity.CENTER);
        sync.setPadding(dp(12),dp(9),dp(12),dp(9));
        sync.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,14));
        sync.setClickable(true);
        sync.setFocusable(true);
        LinearLayout.LayoutParams syncLp=compact
                ? new LinearLayout.LayoutParams(-1,dp(44))
                : new LinearLayout.LayoutParams(-2,-2);
        if(compact) syncLp.setMargins(0,dp(8),0,0);
        header.addView(sync,syncLp);
        root.addView(header);

        status=text("",responsive(11,12,13),MUTED,false);
        status.setPadding(0,dp(responsive(7,9,10)),0,dp(responsive(7,9,10)));
        status.setLineSpacing(dp(1),1f);
        root.addView(status);

        LinearLayout recentHeader=new LinearLayout(this);
        recentHeader.setOrientation(compact?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        recentHeader.setGravity(compact?Gravity.LEFT:Gravity.CENTER_VERTICAL);
        TextView recentTitle=text("Изменения",responsive(14,15,16),TEXT,true);
        recentHeader.addView(recentTitle,compact
                ? new LinearLayout.LayoutParams(-1,-2)
                : new LinearLayout.LayoutParams(0,-2,1));
        TextView recentCaption=text("48 ч до последней проверки",responsive(10,11,12),MUTED,false);
        if(compact) recentCaption.setPadding(0,dp(2),0,0);
        recentHeader.addView(recentCaption);
        root.addView(recentHeader);

        recentList=text("",responsive(12,13,14),TEXT,false);
        recentList.setLineSpacing(dp(2),1f);
        recentList.setPadding(dp(responsive(9,11,13)),dp(9),dp(responsive(9,11,13)),dp(9));
        recentList.setBackground(rounded(CARD,LINE,14));
        LinearLayout.LayoutParams recentLp=new LinearLayout.LayoutParams(-1,-2);
        recentLp.setMargins(0,dp(5),0,dp(responsive(8,10,12)));
        root.addView(recentList,recentLp);

        search=new EditText(this);
        search.setHint(compact?"Название, № КР или МКБ-10":"Название, номер КР или код МКБ-10");
        search.setHintTextColor(Color.rgb(145,153,165));
        search.setTextColor(TEXT);
        search.setTextSize(responsive(14,15,16));
        search.setSingleLine(true);
        search.setPadding(dp(responsive(11,14,16)),0,dp(responsive(11,14,16)),0);
        search.setBackground(rounded(CARD,LINE,15));
        root.addView(search,new LinearLayout.LayoutParams(-1,dp(responsive(46,48,52))));

        contentHost=new FrameLayout(this);
        LinearLayout.LayoutParams contentLp=new LinearLayout.LayoutParams(-1,0,1);
        contentLp.setMargins(0,dp(6),0,0);
        root.addView(contentHost,contentLp);
        setContentView(shell);
        if(Build.VERSION.SDK_INT>=30) shell.requestApplyInsets();

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


    private void buildLandscapeUi() {
        FrameLayout shell=new FrameLayout(this);
        shell.setBackgroundColor(BG);
        if(Build.VERSION.SDK_INT>=30) {
            shell.setOnApplyWindowInsetsListener((v,insets) -> {
                android.graphics.Insets bars=insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                );
                v.setPadding(bars.left,bars.top,bars.right,bars.bottom);
                return insets;
            });
        }

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12),dp(7),dp(12),dp(7));
        root.setBackgroundColor(BG);
        shell.addView(root,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout heading=new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title=text("КР Навигатор",22,TEXT,true);
        title.setIncludeFontPadding(false);
        heading.addView(title);

        status=text("",10,MUTED,false);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setPadding(0,dp(2),0,0);
        heading.addView(status,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout.LayoutParams headingLp=new LinearLayout.LayoutParams(0,-2,0.34f);
        headingLp.setMargins(0,0,dp(10),0);
        header.addView(heading,headingLp);

        recentList=text("",11,TEXT,false);
        recentList.setMaxLines(2);
        recentList.setEllipsize(TextUtils.TruncateAt.END);
        recentList.setGravity(Gravity.CENTER_VERTICAL);
        recentList.setPadding(dp(10),dp(5),dp(10),dp(5));
        recentList.setBackground(rounded(CARD,LINE,12));
        recentList.setClickable(true);
        recentList.setOnClickListener(v -> showRecentDialog());
        LinearLayout.LayoutParams recentLp=new LinearLayout.LayoutParams(0,dp(46),0.66f);
        recentLp.setMargins(0,0,dp(10),0);
        header.addView(recentList,recentLp);

        TextView sync=text("↻  Проверить",12,BLUE,true);
        sync.setGravity(Gravity.CENTER);
        sync.setPadding(dp(12),0,dp(12),0);
        sync.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,13));
        sync.setClickable(true);
        sync.setFocusable(true);
        header.addView(sync,new LinearLayout.LayoutParams(-2,dp(42)));
        root.addView(header,new LinearLayout.LayoutParams(-1,dp(50)));

        LinearLayout body=new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp=new LinearLayout.LayoutParams(-1,0,1);
        bodyLp.setMargins(0,dp(6),0,0);
        root.addView(body,bodyLp);

        LinearLayout leftCard=new LinearLayout(this);
        leftCard.setOrientation(LinearLayout.VERTICAL);
        leftCard.setPadding(dp(8),dp(6),dp(8),dp(6));
        leftCard.setBackground(rounded(CARD,LINE,15));
        LinearLayout.LayoutParams leftLp=new LinearLayout.LayoutParams(0,-1,0.31f);
        leftLp.setMargins(0,0,dp(8),0);
        body.addView(leftCard,leftLp);

        TextView profilesTitle=text("Профили",15,TEXT,true);
        profilesTitle.setPadding(dp(4),0,dp(4),dp(4));
        leftCard.addView(profilesTitle,new LinearLayout.LayoutParams(-1,-2));

        ScrollView profileScroll=new ScrollView(this);
        profileScroll.setFillViewport(false);
        profileScroll.setVerticalScrollBarEnabled(false);
        landscapeSidebar=new LinearLayout(this);
        landscapeSidebar.setOrientation(LinearLayout.VERTICAL);
        profileScroll.addView(landscapeSidebar,new ScrollView.LayoutParams(-1,-2));
        leftCard.addView(profileScroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout right=new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        body.addView(right,new LinearLayout.LayoutParams(0,-1,0.69f));

        search=new EditText(this);
        search.setHint("Название, номер КР или код МКБ-10");
        search.setHintTextColor(Color.rgb(145,153,165));
        search.setTextColor(TEXT);
        search.setTextSize(13);
        search.setSingleLine(true);
        search.setPadding(dp(12),0,dp(12),0);
        search.setBackground(rounded(CARD,LINE,13));
        right.addView(search,new LinearLayout.LayoutParams(-1,dp(42)));

        contentHost=new FrameLayout(this);
        LinearLayout.LayoutParams contentLp=new LinearLayout.LayoutParams(-1,0,1);
        contentLp.setMargins(0,dp(5),0,0);
        right.addView(contentHost,contentLp);

        setContentView(shell);
        if(Build.VERSION.SDK_INT>=30) shell.requestApplyInsets();

        sync.setOnClickListener(v -> runSync(sync));
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int count,int after){}
            public void onTextChanged(CharSequence s,int st,int before,int count){
                String q=s.toString().trim();
                if(q.isEmpty()) renderCurrentPage(0); else renderSearch(q);
            }
            public void afterTextChanged(Editable e){}
        });
    }

    private void refreshLandscapeSidebar() {
        if(landscapeSidebar==null) return;
        landscapeSidebar.removeAllViews();

        LinkedHashMap<String,List<Recommendation>> groups=groupWithUserProfiles(all);
        if(currentPage==PAGE_PROFILES && selectedProfile==null) {
            for(String p:ProfileClassifier.PROFILES) {
                List<Recommendation> items=groups.get(p);
                if(items!=null && !items.isEmpty()) {
                    selectedProfile=p;
                    break;
                }
            }
        }

        landscapeSidebar.addView(landscapeNavRow(
                "Все КР",all.size(),currentPage==PAGE_ALL,
                v -> {
                    currentPage=PAGE_ALL;
                    selectedProfile=null;
                    search.setText("");
                    renderCurrentPage(0);
                }
        ));

        landscapeSidebar.addView(landscapeNavRow(
                "История",db==null?0:db.history(200).size(),currentPage==PAGE_HISTORY,
                v -> {
                    currentPage=PAGE_HISTORY;
                    selectedProfile=null;
                    search.setText("");
                    renderCurrentPage(0);
                }
        ));

        View divider=new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams dividerLp=new LinearLayout.LayoutParams(-1,dp(1));
        dividerLp.setMargins(dp(4),dp(4),dp(4),dp(5));
        landscapeSidebar.addView(divider,dividerLp);

        for(String p:ProfileClassifier.PROFILES) {
            List<Recommendation> items=groups.get(p);
            if(items==null || items.isEmpty()) continue;
            boolean selected=currentPage==PAGE_PROFILES && p.equals(selectedProfile);
            landscapeSidebar.addView(landscapeNavRow(
                    p,items.size(),selected,
                    v -> {
                        currentPage=PAGE_PROFILES;
                        selectedProfile=p;
                        search.setText("");
                        renderCurrentPage(0);
                    }
            ));
        }
    }

    private TextView landscapeNavRow(String label,int count,boolean selected,View.OnClickListener click) {
        TextView row=text(label+"   "+count,12,selected?BLUE_DARK:TEXT,selected);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMaxLines(2);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setPadding(dp(10),dp(7),dp(9),dp(7));
        row.setBackground(rounded(selected?BLUE_SOFT:Color.TRANSPARENT,Color.TRANSPARENT,11));
        row.setClickable(true);
        row.setOnClickListener(click);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(1),0,dp(1));
        row.setLayoutParams(lp);
        return row;
    }

    private View makeLandscapeProfilePage(String profile) {
        List<Recommendation> recs=groupWithUserProfiles(all).get(profile);
        if(recs==null) recs=Collections.emptyList();
        return makeListPage(profile,recs);
    }

    private View makeLandscapeHistoryPage() {
        List<Recommendation> history=db.history(200);
        return makeListPage("История",history);
    }

    private void showRecentDialog() {
        List<DbHelper.ChangeEvent> recent=changesForLastScan(30);
        StringBuilder sb=new StringBuilder();
        if(recent.isEmpty()) {
            sb.append("За 48 часов до последней проверки новых или обновлённых КР не обнаружено.");
        } else {
            for(int i=0;i<recent.size();i++) {
                DbHelper.ChangeEvent e=recent.get(i);
                if(i>0) sb.append("\n\n");
                if("NEW".equals(e.type)) {
                    sb.append("НОВАЯ — ").append(e.title).append(" (").append(e.newId).append(")");
                } else {
                    sb.append("ОБНОВЛЕНА — ").append(e.title).append(" (");
                    if(e.oldId!=null && !e.oldId.isEmpty()) sb.append(e.oldId).append(" → ");
                    sb.append(e.newId).append(")");
                }
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Изменения за 48 часов")
                .setMessage(sb.toString())
                .setPositiveButton("ОК",null)
                .show();
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if(isLandscapeUi()) return super.dispatchTouchEvent(e);
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
        String notifyNote=notificationsEnabled()?"":"\n⚠ Уведомления Android отключены";
        if(isLandscapeUi()) {
            status.setText(all.size()+" КР  •  проверено: "+last+(notificationsEnabled()?"":"  •  уведомления выкл."));
        } else {
            status.setText(all.size()+" КР  •  Последняя проверка: "+last+"\nСледующая: "+DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(next))+notifyNote);
        }
        renderRecent();
        String q=search==null?"":search.getText().toString().trim();
        if(q.isEmpty()) renderCurrentPage(0); else renderSearch(q);
    }

    private List<DbHelper.ChangeEvent> changesForLastScan(int limit) {
        String last=getSharedPreferences("prefs",MODE_PRIVATE).getString("last_sync","");
        if(last==null || last.trim().isEmpty()) return Collections.emptyList();
        return db.recentChangesWithinHoursAt(last,48,limit);
    }

    private void renderRecent() {
        List<DbHelper.ChangeEvent> recent=changesForLastScan(isLandscapeUi()?30:5);
        if(recent.isEmpty()) {
            recentList.setText(isLandscapeUi()?"Изменения за 48 ч: нет":"За 48 часов до последней проверки новых или обновлённых КР не обнаружено.");
            recentList.setTextColor(MUTED);
            return;
        }
        recentList.setTextColor(TEXT);

        if(isLandscapeUi()) {
            DbHelper.ChangeEvent e=recent.get(0);
            StringBuilder compact=new StringBuilder("Изменения 48 ч: ");
            if("NEW".equals(e.type)) compact.append("НОВАЯ — ");
            else compact.append("ОБНОВЛЕНА — ");
            compact.append(e.title).append(" · ").append(e.newId);
            if(recent.size()>1) compact.append("   +").append(recent.size()-1);
            recentList.setText(compact.toString());
            return;
        }

        StringBuilder sb=new StringBuilder();
        int max=Math.min(3,recent.size());
        for(int i=0;i<max;i++) {
            DbHelper.ChangeEvent e=recent.get(i);
            if(i>0) sb.append("\n");
            if("NEW".equals(e.type)) {
                sb.append("НОВАЯ  ").append(e.title).append("  ·  ").append(e.newId);
            } else {
                sb.append("ОБНОВЛЕНА  ").append(e.title).append("  ·  ");
                if(e.oldId!=null && !e.oldId.isEmpty()) sb.append(e.oldId).append(" → ");
                sb.append(e.newId);
            }
        }
        if(recent.size()>max) sb.append("\nЕщё ").append(recent.size()-max).append("…");
        recentList.setText(sb.toString());
    }

    private void renderCurrentPage(int direction) {
        if(contentHost==null) return;
        searchShowsMkb=false;

        if(isLandscapeUi()) {
            refreshLandscapeSidebar();
            if(currentPage==PAGE_ALL) showContent(makeListPage("Все КР",all),0);
            else if(currentPage==PAGE_HISTORY) showContent(makeLandscapeHistoryPage(),0);
            else if(selectedProfile!=null) showContent(makeLandscapeProfilePage(selectedProfile),0);
            else showContent(makeListPage("Все КР",all),0);
            return;
        }

        if(currentPage==PAGE_ALL) showContent(makeListPage("Все КР",all),direction);
        else if(currentPage==PAGE_HISTORY) showContent(makeHistoryPage(),direction);
        else if(selectedProfile!=null) showContent(makeProfileListPage(selectedProfile),direction);
        else showContent(makeProfilesPage(),direction);
    }

    private Set<String> effectiveGroups(Recommendation r) {
        LinkedHashSet<String> out=new LinkedHashSet<>(ProfileClassifier.groupsFor(r));
        DbHelper.ProfileRule rule=db==null?null:db.getProfileRule(r.baseId);
        if(rule==null) return out;

        LinkedHashSet<String> valid=new LinkedHashSet<>();
        for(String p:rule.profiles) if(ProfileClassifier.PROFILES.contains(p)) valid.add(p);
        if("REPLACE".equals(rule.mode)) {
            if(!valid.isEmpty()) return valid;
            return out;
        }
        out.addAll(valid);
        return out;
    }

    private LinkedHashMap<String,List<Recommendation>> groupWithUserProfiles(List<Recommendation> recs) {
        LinkedHashMap<String,List<Recommendation>> out=new LinkedHashMap<>();
        for(String p:ProfileClassifier.PROFILES) out.put(p,new ArrayList<>());

        Map<String,DbHelper.ProfileRule> rules=db==null?Collections.emptyMap():db.allProfileRules();
        for(Recommendation r:recs) {
            LinkedHashSet<String> groups=new LinkedHashSet<>(ProfileClassifier.groupsFor(r));
            DbHelper.ProfileRule rule=rules.get(r.baseId);
            if(rule!=null) {
                LinkedHashSet<String> valid=new LinkedHashSet<>();
                for(String p:rule.profiles) if(ProfileClassifier.PROFILES.contains(p)) valid.add(p);
                if("REPLACE".equals(rule.mode) && !valid.isEmpty()) groups=valid;
                else groups.addAll(valid);
            }
            for(String p:groups) {
                List<Recommendation> bucket=out.get(p);
                if(bucket!=null) bucket.add(r);
            }
        }
        return out;
    }

    private String joinProfiles(Collection<String> profiles) {
        StringBuilder out=new StringBuilder();
        if(profiles!=null) {
            for(String p:profiles) {
                if(out.length()>0) out.append(", ");
                out.append(p);
            }
        }
        return out.toString();
    }

    private void refreshAfterProfileChange() {
        String q=search==null?"":search.getText().toString().trim();
        if(q.isEmpty()) renderCurrentPage(0); else renderSearch(q);
    }

    private View makeProfilesPage() {
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout pageHead=new LinearLayout(this);
        pageHead.setOrientation(LinearLayout.HORIZONTAL);
        pageHead.setGravity(Gravity.CENTER_VERTICAL);
        pageHead.addView(pageTitle("Профили"),new LinearLayout.LayoutParams(0,-2,1));
        TextView hint=text(compactUi()?"← Все   История →":"← Все КР     История →",responsive(10,11,12),MUTED,false);
        pageHead.addView(hint);
        outer.addView(pageHead);

        LinkedHashMap<String,List<Recommendation>> groups=groupWithUserProfiles(all);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout rows=new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0,dp(2),0,dp(10));
        scroll.addView(rows,new ScrollView.LayoutParams(-1,-2));

        ArrayList<String> visible=new ArrayList<>();
        for(String p:ProfileClassifier.PROFILES) if(!groups.get(p).isEmpty()) visible.add(p);
        int columns=profileColumns();
        int gap=responsive(3,4,6);
        int cardHeight=responsive(142,150,156);
        for(int i=0;i<visible.size();i+=columns) {
            LinearLayout row=new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for(int j=0;j<columns;j++) {
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(cardHeight),1);
                lp.setMargins(dp(gap),dp(gap),dp(gap),dp(gap));
                if(i+j<visible.size()) {
                    String p=visible.get(i+j);
                    row.addView(profileCard(p,groups.get(p).size()),lp);
                } else {
                    Space spacer=new Space(this);
                    row.addView(spacer,lp);
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
        int cardPad=responsive(7,9,11);
        card.setPadding(dp(cardPad),dp(cardPad),dp(cardPad),dp(responsive(7,8,9)));
        card.setBackground(rounded(CARD,LINE,16));
        if(Build.VERSION.SDK_INT>=21) card.setElevation(dp(1));
        card.setClickable(true);
        card.setFocusable(true);

        ProfileIconView icon=new ProfileIconView(this,profile);
        int iconSize=responsive(36,40,46);
        LinearLayout.LayoutParams iconLp=new LinearLayout.LayoutParams(dp(iconSize),dp(iconSize));
        iconLp.setMargins(0,0,0,dp(6));
        card.addView(icon,iconLp);

        String displayProfile=profile.replace("-","\u2011");
        TextView name=text(displayProfile,responsive(12,13,14),TEXT,true);
        name.setGravity(Gravity.CENTER);
        name.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        name.setIncludeFontPadding(false);
        name.setMaxLines(5);
        name.setEllipsize(null);
        name.setHorizontallyScrolling(false);
        name.setLineSpacing(dp(1),1.0f);
        if(Build.VERSION.SDK_INT>=23) {
            name.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
            name.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        }
        card.addView(name,new LinearLayout.LayoutParams(-1,-2));

        TextView number=text(count+" КР",responsive(10,11,12),MUTED,false);
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
        float size=responsive(12,13,14);
        String[] words=value.split("\\s+");
        float minimum=screenWidthDp()<380?9.5f:10.5f;
        while(size>minimum) {
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
        List<Recommendation> recs=groupWithUserProfiles(all).get(profile);
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
        TextView hint=text("← Профили",responsive(10,11,12),MUTED,false);
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
        final Map<String,DbHelper.ProfileRule> profileRules=db.allProfileRules();
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
                row.setPadding(dp(responsive(9,12,14)),dp(responsive(8,10,11)),dp(responsive(8,9,12)),dp(responsive(8,10,11)));
                row.setBackground(rounded(CARD,LINE,14));

                LinearLayout labels=new LinearLayout(MainActivity.this);
                labels.setOrientation(LinearLayout.VERTICAL);
                TextView name=text(r.title,responsive(13,14,15),TEXT,false);
                name.setMaxLines(3);
                String metaText="КР "+r.id+(PdfManager.isPdf(PdfManager.file(MainActivity.this,r))?"  •  PDF скачан":"");
                if(profileRules.containsKey(r.baseId)) metaText += "  •  ✎ профиль настроен";
                if(searchShowsMkb && r.mkbCodes!=null && !r.mkbCodes.isEmpty()) metaText += "  •  МКБ-10: "+r.mkbCodes;
                TextView meta=text(metaText,responsive(10,11,12),MUTED,false);
                meta.setPadding(0,dp(4),0,0);
                labels.addView(name);
                labels.addView(meta);
                row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

                TextView menu=text("⋮",24,Color.rgb(125,139,157),true);
                menu.setGravity(Gravity.CENTER);
                menu.setClickable(true);
                menu.setFocusable(false);
                menu.setContentDescription("Настроить профиль КР");
                menu.setOnClickListener(v -> showRecommendationOptions(r));
                row.addView(menu,new LinearLayout.LayoutParams(dp(38),dp(48)));
                wrap.addView(row,new LinearLayout.LayoutParams(-1,-2));
                return wrap;
            }
        });
        list.setOnItemClickListener((p,v,pos,id)->downloadOrOpen(recs.get(pos)));
        list.setOnItemLongClickListener((p,v,pos,id) -> {
            showRecommendationOptions(recs.get(pos));
            return true;
        });
        outer.addView(list,new LinearLayout.LayoutParams(-1,0,1));
    }

    private TextView dialogAction(String label,boolean primary) {
        TextView v=text(label,responsive(13,14,15),primary?Color.WHITE:TEXT,true);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(14),dp(12),dp(14),dp(12));
        v.setBackground(rounded(primary?BLUE:CARD,primary?Color.TRANSPARENT:LINE,14));
        v.setClickable(true);
        return v;
    }

    private void showRecommendationOptions(Recommendation r) {
        final Dialog dialog=new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18),dp(16),dp(18),dp(14));
        card.setBackground(rounded(CARD,LINE,20));

        TextView badge=text("КР "+r.id,11,BLUE,true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10),dp(6),dp(10),dp(6));
        badge.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,12));
        card.addView(badge,new LinearLayout.LayoutParams(-2,dp(34)));

        TextView title=text("Настроить профиль КР",responsive(18,20,21),TEXT,true);
        title.setPadding(0,dp(12),0,dp(5));
        card.addView(title);

        TextView recTitle=text(r.title,responsive(12,13,14),TEXT,false);
        recTitle.setMaxLines(4);
        recTitle.setPadding(0,0,0,dp(10));
        card.addView(recTitle);

        Set<String> auto=ProfileClassifier.groupsFor(r);
        Set<String> effective=effectiveGroups(r);
        DbHelper.ProfileRule rule=db.getProfileRule(r.baseId);

        TextView current=text("Сейчас: "+joinProfiles(effective),responsive(11,12,13),MUTED,false);
        current.setLineSpacing(dp(2),1f);
        current.setPadding(0,0,0,dp(3));
        card.addView(current);

        TextView original=text("Автоматически: "+joinProfiles(auto),responsive(10,11,12),MUTED,false);
        original.setLineSpacing(dp(2),1f);
        original.setPadding(0,0,0,dp(14));
        card.addView(original);

        TextView move=dialogAction("Переместить в другой профиль",true);
        card.addView(move,new LinearLayout.LayoutParams(-1,dp(48)));
        ((LinearLayout.LayoutParams)move.getLayoutParams()).setMargins(0,0,0,dp(8));

        TextView add=dialogAction("Добавить ещё в профиль",false);
        card.addView(add,new LinearLayout.LayoutParams(-1,dp(48)));
        ((LinearLayout.LayoutParams)add.getLayoutParams()).setMargins(0,0,0,dp(8));

        TextView reset=null;
        if(rule!=null) {
            reset=dialogAction("Вернуть исходное распределение",false);
            card.addView(reset,new LinearLayout.LayoutParams(-1,dp(48)));
            ((LinearLayout.LayoutParams)reset.getLayoutParams()).setMargins(0,0,0,dp(8));
        }

        TextView cancel=dialogAction("Отмена",false);
        cancel.setGravity(Gravity.CENTER);
        card.addView(cancel,new LinearLayout.LayoutParams(-1,dp(46)));

        move.setOnClickListener(v -> {
            dialog.dismiss();
            showProfilePicker(r,true);
        });
        add.setOnClickListener(v -> {
            dialog.dismiss();
            showProfilePicker(r,false);
        });
        if(reset!=null) {
            reset.setOnClickListener(v -> {
                db.clearProfileRule(r.baseId);
                dialog.dismiss();
                Toast.makeText(this,"Исходное распределение восстановлено",Toast.LENGTH_SHORT).show();
                refreshAfterProfileChange();
            });
        }
        cancel.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(card);
        Window w=dialog.getWindow();
        dialog.show();
        if(w!=null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp=w.getAttributes();
            lp.width=dialogWidthPx();
            lp.height=WindowManager.LayoutParams.WRAP_CONTENT;
            lp.dimAmount=0.38f;
            w.setAttributes(lp);
            w.setGravity(Gravity.CENTER);
        }
    }

    private void showProfilePicker(Recommendation r,boolean replaceMode) {
        final Dialog dialog=new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16),dp(15),dp(16),dp(14));
        card.setBackground(rounded(CARD,LINE,20));

        TextView title=text(replaceMode?"Переместить КР":"Добавить в профиль",responsive(18,20,21),TEXT,true);
        card.addView(title);

        TextView caption=text(
                replaceMode?"Выберите новый профиль. КР будет показана только в выбранном профиле."
                           :"Можно выбрать один или несколько дополнительных профилей.",
                responsive(11,12,13),MUTED,false);
        caption.setPadding(0,dp(4),0,dp(10));
        caption.setLineSpacing(dp(2),1f);
        card.addView(caption);

        final Set<String> effective=effectiveGroups(r);
        final LinkedHashSet<String> selected=new LinkedHashSet<>();
        final String[] selectedSingle=new String[]{effective.size()==1?effective.iterator().next():null};

        ScrollView scroll=new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout choices=new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(choices,new ScrollView.LayoutParams(-1,-2));

        ArrayList<TextView> rows=new ArrayList<>();
        for(String profile:ProfileClassifier.PROFILES) {
            boolean already=effective.contains(profile);
            String prefix=replaceMode
                    ? (profile.equals(selectedSingle[0])?"●  ":"○  ")
                    : (already?"✓  ":"○  ");
            TextView row=text(prefix+profile,responsive(12,13,14),already&&!replaceMode?MUTED:TEXT,false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12),dp(10),dp(10),dp(10));
            row.setBackground(rounded(already?BLUE_SOFT:CARD,LINE,12));
            LinearLayout.LayoutParams rowLp=new LinearLayout.LayoutParams(-1,-2);
            rowLp.setMargins(0,dp(3),0,dp(3));
            choices.addView(row,rowLp);
            rows.add(row);

            if(replaceMode) {
                row.setOnClickListener(v -> {
                    selectedSingle[0]=profile;
                    for(int i=0;i<rows.size();i++) {
                        String p=ProfileClassifier.PROFILES.get(i);
                        TextView rv=rows.get(i);
                        boolean on=p.equals(selectedSingle[0]);
                        rv.setText((on?"●  ":"○  ")+p);
                        rv.setBackground(rounded(on?BLUE_SOFT:CARD,LINE,12));
                    }
                });
            } else if(!already) {
                row.setOnClickListener(v -> {
                    if(selected.contains(profile)) selected.remove(profile); else selected.add(profile);
                    boolean on=selected.contains(profile);
                    row.setText((on?"●  ":"○  ")+profile);
                    row.setBackground(rounded(on?BLUE_SOFT:CARD,LINE,12));
                });
            }
        }

        LinearLayout.LayoutParams scrollLp=new LinearLayout.LayoutParams(-1,0,1);
        card.addView(scroll,scrollLp);

        LinearLayout buttons=new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonsLp=new LinearLayout.LayoutParams(-1,-2);
        buttonsLp.setMargins(0,dp(10),0,0);
        card.addView(buttons,buttonsLp);

        TextView cancel=dialogAction("Отмена",false);
        cancel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cancelLp=new LinearLayout.LayoutParams(0,dp(46),1);
        cancelLp.setMargins(0,0,dp(5),0);
        buttons.addView(cancel,cancelLp);

        TextView save=dialogAction(replaceMode?"Переместить":"Добавить",true);
        save.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams saveLp=new LinearLayout.LayoutParams(0,dp(46),1);
        saveLp.setMargins(dp(5),0,0,0);
        buttons.addView(save,saveLp);

        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            if(replaceMode) {
                if(selectedSingle[0]==null) {
                    Toast.makeText(this,"Выберите профиль",Toast.LENGTH_SHORT).show();
                    return;
                }
                db.saveProfileRule(r.baseId,"REPLACE",Collections.singleton(selectedSingle[0]));
                Toast.makeText(this,"КР перемещена в профиль «"+selectedSingle[0]+"»",Toast.LENGTH_SHORT).show();
            } else {
                if(selected.isEmpty()) {
                    Toast.makeText(this,"Выберите хотя бы один дополнительный профиль",Toast.LENGTH_SHORT).show();
                    return;
                }
                DbHelper.ProfileRule existing=db.getProfileRule(r.baseId);
                LinkedHashSet<String> saveProfiles=new LinkedHashSet<>();
                String mode="ADD";
                if(existing!=null) {
                    saveProfiles.addAll(existing.profiles);
                    if("REPLACE".equals(existing.mode)) mode="REPLACE";
                }
                saveProfiles.addAll(selected);
                db.saveProfileRule(r.baseId,mode,saveProfiles);
                Toast.makeText(this,"Дополнительные профили добавлены",Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
            refreshAfterProfileChange();
        });

        dialog.setContentView(card);
        Window w=dialog.getWindow();
        dialog.show();
        if(w!=null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp=w.getAttributes();
            lp.width=dialogWidthPx();
            lp.height=Math.min(getResources().getDisplayMetrics().heightPixels-dp(48),dp(680));
            lp.dimAmount=0.38f;
            w.setAttributes(lp);
            w.setGravity(Gravity.CENTER);
        }
    }

    private TextView pageTitle(String value) {
        TextView t=text(value,responsive(16,18,20),TEXT,true);
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
        progress.setMessage("Получаю актуальный каталог и проверяю новые и обновлённые КР…");
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        executor.submit(() -> {
            SyncEngine.Result result;
            try {
                result=SyncEngine.sync(getApplicationContext());
            } catch(Throwable t) {
                String m=t.getMessage()==null?t.getClass().getSimpleName():t.getMessage();
                result=new SyncEngine.Result(0,0,0,0,0,"Ошибка проверки: "+m,Collections.emptyList(),Collections.emptyList());
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
        List<DbHelper.ChangeEvent> recent=changesForLastScan(50);
        StringBuilder text=new StringBuilder();
        if(recent.isEmpty()) {
            text.append("За 48 часов до момента этой проверки новых или обновлённых КР не обнаружено.");
        } else {
            text.append("Новые и обновлённые КР за 48 часов до момента проверки:");
            int max=Math.min(12,recent.size());
            for(int i=0;i<max;i++) {
                DbHelper.ChangeEvent e=recent.get(i);
                text.append("\n\n• ");
                if("NEW".equals(e.type)) {
                    text.append("НОВАЯ — ").append(e.title).append(" (ID: ").append(e.newId).append(")");
                } else {
                    text.append("ОБНОВЛЕНА — ").append(e.title).append(" (");
                    if(e.oldId!=null && !e.oldId.isEmpty()) text.append(e.oldId).append(" → ");
                    text.append(e.newId).append(")");
                }
            }
            if(recent.size()>max) text.append("\n\n…и ещё ").append(recent.size()-max);
        }
        if(r.downloaded>0) text.append("\n\nPDF скачано: ").append(r.downloaded).append(".");
        if(r.downloadFailed>0) text.append("\nНе удалось скачать PDF: ").append(r.downloadFailed).append(".");
        new AlertDialog.Builder(this)
                .setTitle("Результат проверки")
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

    @Override public void onBackPressed() {
        showExitDialog();
    }

    private void showExitDialog() {
        final Dialog dialog=new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20),dp(18),dp(20),dp(16));
        card.setBackground(rounded(CARD,LINE,20));

        TextView badge=text("КР",12,BLUE,true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10),dp(6),dp(10),dp(6));
        badge.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,12));
        LinearLayout.LayoutParams badgeLp=new LinearLayout.LayoutParams(dp(48),dp(34));
        badgeLp.bottomMargin=dp(12);
        card.addView(badge,badgeLp);

        TextView title=text("Закрыть КР Навигатор?",responsive(18,20,21),TEXT,true);
        title.setPadding(0,0,0,dp(6));
        card.addView(title,new LinearLayout.LayoutParams(-1,-2));

        TextView message=text("Вы хотите выйти из приложения?",responsive(13,14,15),MUTED,false);
        message.setLineSpacing(dp(2),1f);
        card.addView(message,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout buttons=new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonsLp=new LinearLayout.LayoutParams(-1,-2);
        buttonsLp.setMargins(0,dp(18),0,0);
        card.addView(buttons,buttonsLp);

        TextView stay=text("Вернуться",14,BLUE,true);
        stay.setGravity(Gravity.CENTER);
        stay.setPadding(dp(12),dp(11),dp(12),dp(11));
        stay.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,14));
        LinearLayout.LayoutParams stayLp=new LinearLayout.LayoutParams(0,dp(46),1);
        stayLp.setMargins(0,0,dp(6),0);
        buttons.addView(stay,stayLp);

        TextView exit=text("Выйти",14,Color.WHITE,true);
        exit.setGravity(Gravity.CENTER);
        exit.setPadding(dp(12),dp(11),dp(12),dp(11));
        exit.setBackground(rounded(BLUE,Color.TRANSPARENT,14));
        LinearLayout.LayoutParams exitLp=new LinearLayout.LayoutParams(0,dp(46),1);
        exitLp.setMargins(dp(6),0,0,0);
        buttons.addView(exit,exitLp);

        stay.setOnClickListener(v -> dialog.dismiss());
        exit.setOnClickListener(v -> {
            dialog.dismiss();
            finishAndRemoveTask();
        });

        dialog.setContentView(card);
        Window w=dialog.getWindow();
        if(w!=null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp=new WindowManager.LayoutParams();
            lp.copyFrom(w.getAttributes());
            lp.width=dialogWidthPx();
            lp.height=WindowManager.LayoutParams.WRAP_CONTENT;
            lp.dimAmount=0.38f;
            w.setAttributes(lp);
            w.setGravity(Gravity.CENTER);
        }
        dialog.show();
        if(w!=null) {
            WindowManager.LayoutParams lp=w.getAttributes();
            lp.width=dialogWidthPx();
            lp.height=WindowManager.LayoutParams.WRAP_CONTENT;
            w.setAttributes(lp);
        }
    }

    private boolean notificationsEnabled() {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return false;
        if(Build.VERSION.SDK_INT>=24) {
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            return nm==null || nm.areNotificationsEnabled();
        }
        return true;
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

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter=new IntentFilter("ru.krmonitor.app.SYNC_COMPLETE");
        if(Build.VERSION.SDK_INT>=33) registerReceiver(syncReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(syncReceiver,filter);
    }

    @Override protected void onResume() {
        super.onResume();
        if(db!=null && recentList!=null) reload();
    }

    @Override protected void onStop() {
        try { unregisterReceiver(syncReceiver); } catch(Exception ignored) {}
        super.onStop();
    }

    @Override protected void onDestroy(){
        super.onDestroy();
        executor.shutdownNow();
        if(db!=null) db.close();
    }
}
