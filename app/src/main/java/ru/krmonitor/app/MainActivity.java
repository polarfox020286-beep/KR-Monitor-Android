package ru.krmonitor.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.net.Uri;
import android.text.*;
import android.view.*;
import android.view.animation.TranslateAnimation;
import android.widget.*;
import android.util.TypedValue;
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
    private LinearLayout bottomNav;
    private List<Recommendation> all=new ArrayList<>();

    private static final int PAGE_ALL=0;
    private static final int PAGE_PROFILES=1;
    private static final int PAGE_HISTORY=2;
    private static final int PAGE_ABOUT=3;
    private static final int PAGE_FAVORITES=4;
    private int currentPage=PAGE_PROFILES;
    private String selectedProfile=null;
    private float swipeX,swipeY;
    private boolean swipeTracking=false;
    private boolean searchShowsMkb=false;
    private volatile boolean syncInProgress=false;

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
        buildUi();
        reload();
        if(b==null) runStartupSyncSilently();
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

    // Adaptive window size classes. The layout is chosen from available dp,
    // so it also works in split-screen, on tablets and on foldables.
    private boolean useTwoPaneUi(){ return screenWidthDp()>=600; }
    private boolean expandedTwoPaneUi(){ return screenWidthDp()>=840; }
    private boolean lowHeightUi(){ return screenHeightDp()<480; }
    private boolean compactSinglePaneUi(){
        return !useTwoPaneUi() && screenWidthDp()>screenHeightDp() && lowHeightUi();
    }
    private boolean compactChromeUi(){ return useTwoPaneUi() || compactSinglePaneUi(); }
    private int clampInt(int value,int min,int max){ return Math.max(min,Math.min(max,value)); }

    private int responsive(int compact,int phone,int tablet) {
        return compactUi()?compact:(tabletUi()?tablet:phone);
    }

    private float systemFontScale(){ return getResources().getConfiguration().fontScale; }
    private boolean largeFontUi(){ return systemFontScale()>1.20f; }
    private boolean veryLargeFontUi(){ return systemFontScale()>1.45f; }
    private boolean narrowPhoneUi(){ return !useTwoPaneUi() && screenWidthDp()<360; }

    private int profileColumns() {
        int w=screenWidthDp();

        // Keep the familiar two-column phone layout whenever the real
        // available window is wide enough. Font scale changes card height
        // and text fitting, not the number of columns by itself.
        if(w<300) return 1;
        if(w<600) return 2;
        if(w<900) return 3;
        return 4;
    }

    private float cappedChromeSp(float baseSp) {
        float scale=Math.max(0.85f,systemFontScale());
        float effective=Math.min(scale,1.25f);
        return baseSp*effective/scale;
    }

    private int profileCardHeightDp() {
        int base=responsive(142,150,156);
        float extra=Math.max(0f,Math.min(0.70f,systemFontScale()-1f));
        return base+Math.round(extra*34f);
    }

    private boolean dialogNeedsBoundedHeight(){
        return lowHeightUi() || screenHeightDp()<650 || systemFontScale()>1.20f;
    }

    private int dialogWidthPx() {
        int w=screenWidthDp();
        int marginDp=w<360?24:40;
        int availableDp=Math.max(260,w-marginDp);
        int maxDp=expandedTwoPaneUi()?520:(useTwoPaneUi()?460:380);
        return dp(Math.min(availableDp,maxDp));
    }

    private int dialogMaxHeightPx() {
        int h=screenHeightDp();
        float share=lowHeightUi()?0.88f:0.78f;
        int byScreen=Math.max(220,Math.round(h*share));
        int maxDp=expandedTwoPaneUi()?640:(useTwoPaneUi()?600:560);
        return dp(Math.min(byScreen,maxDp));
    }

    private void applyAdaptiveDialogWindow(Dialog dialog,boolean boundedHeight) {
        Window w=dialog.getWindow();
        if(w==null) return;
        w.setBackgroundDrawableResource(android.R.color.transparent);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams lp=w.getAttributes();
        lp.width=dialogWidthPx();
        lp.height=boundedHeight?dialogMaxHeightPx():WindowManager.LayoutParams.WRAP_CONTENT;
        lp.dimAmount=0.38f;
        w.setAttributes(lp);
        w.setGravity(Gravity.CENTER);
    }

    private void applyAdaptiveAlertWindow(AlertDialog dialog) {
        Window w=dialog.getWindow();
        if(w==null) return;

        final int width=dialogWidthPx();
        w.setLayout(width,WindowManager.LayoutParams.WRAP_CONTENT);
        w.setGravity(Gravity.CENTER);

        TextView message=dialog.findViewById(android.R.id.message);
        if(message!=null) {
            message.setTextSize(lowHeightUi()?12:14);
            message.setLineSpacing(dp(2),1f);
        }

        // A short informational message must stay compact. Only dialogs whose
        // measured content is genuinely too tall are capped and become scrollable.
        View decor=w.getDecorView();
        decor.post(() -> {
            if(dialog.getWindow()==null) return;
            int maxHeight=dialogMaxHeightPx();
            int measured=decor.getHeight();
            dialog.getWindow().setLayout(
                    width,
                    measured>maxHeight?maxHeight:WindowManager.LayoutParams.WRAP_CONTENT
            );
            dialog.getWindow().setGravity(Gravity.CENTER);
        });
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

    private TextView aboutHeaderButton(float size) {
        TextView info=text("ⓘ",size,BLUE,true);
        info.setContentDescription("О приложении");
        info.setGravity(Gravity.CENTER);
        info.setMinWidth(dp(36));
        info.setMinHeight(dp(36));
        info.setPadding(dp(6),0,dp(6),0);
        info.setClickable(true);
        info.setFocusable(true);
        info.setOnClickListener(v -> {
            currentPage=PAGE_ABOUT;
            selectedProfile=null;
            if(search!=null && search.getText()!=null && search.getText().length()>0) {
                search.setText("");
            } else {
                renderCurrentPage(0);
            }
        });
        return info;
    }

    private LinearLayout buildBottomNavigation() {
        LinearLayout bar=new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(3),dp(3),dp(3),dp(3));
        bar.setBackground(rounded(CARD,LINE,16));
        bottomNav=bar;
        refreshBottomNavigation();

        // The bar may grow when a device uses a taller system font. Never clip
        // labels into a hard-coded 48/58 dp box.
        int minHeight=lowHeightUi()?48:(largeFontUi()?62:58);
        bar.setMinimumHeight(dp(minHeight));
        return bar;
    }

    private void refreshBottomNavigation() {
        if(bottomNav==null) return;
        bottomNav.removeAllViews();

        bottomNav.addView(bottomNavItem("▤","Все КР",PAGE_ALL,currentPage==PAGE_ALL),
                new LinearLayout.LayoutParams(0,-2,1));
        bottomNav.addView(bottomNavItem("▦","Профили",PAGE_PROFILES,currentPage==PAGE_PROFILES),
                new LinearLayout.LayoutParams(0,-2,1));
        bottomNav.addView(bottomNavItem("★","Избранное",PAGE_FAVORITES,currentPage==PAGE_FAVORITES),
                new LinearLayout.LayoutParams(0,-2,1));
        bottomNav.addView(bottomNavItem("◷","История",PAGE_HISTORY,currentPage==PAGE_HISTORY),
                new LinearLayout.LayoutParams(0,-2,1));
    }

    private View bottomNavItem(String icon,String label,int page,boolean selected) {
        LinearLayout item=new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(3),dp(lowHeightUi()?2:4),dp(3),dp(lowHeightUi()?2:4));
        item.setBackground(rounded(selected?BLUE_SOFT:Color.TRANSPARENT,Color.TRANSPARENT,12));
        item.setClickable(true);
        item.setFocusable(true);
        item.setContentDescription(label);
        item.setOnClickListener(v -> navigateBottom(page));

        TextView iconView=text(icon,lowHeightUi()?15:18,selected?BLUE:MUTED,selected);
        iconView.setTextSize(TypedValue.COMPLEX_UNIT_DIP,lowHeightUi()?15:18);
        iconView.setGravity(Gravity.CENTER);
        iconView.setIncludeFontPadding(true);
        item.addView(iconView,new LinearLayout.LayoutParams(-1,-2));

        TextView labelView=text(label,lowHeightUi()?8:10,selected?BLUE:MUTED,selected);
        labelView.setGravity(Gravity.CENTER);
        labelView.setSingleLine(true);
        labelView.setIncludeFontPadding(true);
        LinearLayout.LayoutParams labelLp=new LinearLayout.LayoutParams(-1,-2);
        labelLp.setMargins(0,dp(1),0,0);
        item.addView(labelView,labelLp);
        fitBottomNavigationLabel(labelView);
        return item;
    }

    private void fitSearchField(EditText field,float maxDp,float minDp) {
        if(field==null) return;
        field.post(() -> {
            int inner=field.getWidth()-field.getPaddingLeft()-field.getPaddingRight();
            if(inner<=0) return;

            CharSequence hintValue=field.getHint();
            String hint=hintValue==null?"":hintValue.toString();
            android.graphics.Paint paint=new android.graphics.Paint(field.getPaint());
            float chosen=maxDp;

            for(float size=maxDp;size>=minDp;size-=0.5f) {
                paint.setTextSize(dpFloat(size));
                chosen=size;
                if(paint.measureText(hint)<=inner) break;
            }
            field.setTextSize(TypedValue.COMPLEX_UNIT_DIP,chosen);
        });
    }

    private void fitBottomNavigationLabel(TextView label) {
        if(label==null) return;
        label.post(() -> {
            int inner=label.getWidth()-label.getPaddingLeft()-label.getPaddingRight();
            if(inner<=0) return;

            String value=label.getText()==null?"":label.getText().toString();
            android.graphics.Paint paint=new android.graphics.Paint(label.getPaint());
            float maxDp=lowHeightUi()?9f:10f;
            float minDp=6.5f;
            float chosen=maxDp;

            for(float size=maxDp;size>=minDp;size-=0.5f) {
                paint.setTextSize(dpFloat(size));
                if(paint.measureText(value)<=inner) {
                    chosen=size;
                    break;
                }
                chosen=size;
            }
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP,chosen);
        });
    }

    private void navigateBottom(int page) {
        selectedProfile=null;
        currentPage=page;

        if(search!=null && search.getText()!=null && search.getText().length()>0) {
            search.setText("");
        } else {
            renderCurrentPage(0);
        }
    }

    private void fitSyncButton(TextView button,String preferredLabel) {
        if(button==null) return;
        button.setText(preferredLabel);
        button.setSingleLine(true);
        button.setEllipsize(null);

        button.post(() -> {
            int inner=button.getWidth()-button.getPaddingLeft()-button.getPaddingRight();
            if(inner<=0) return;

            String full=preferredLabel;
            String fallback=full.contains("Провер") ? (full.contains("Проверяю")?"Проверяю…":"Проверить") : full;

            // Measure in physical dp rather than scaled sp so a large system
            // font cannot force the action label outside its button.
            float maxDp=compactUi()?13f:14f;
            float minDp=9f;
            String chosen=full;
            float chosenDp=maxDp;

            android.graphics.Paint paint=new android.graphics.Paint(button.getPaint());
            boolean fits=false;
            for(float size=maxDp;size>=minDp;size-=0.5f) {
                paint.setTextSize(dpFloat(size));
                if(paint.measureText(full)<=inner) {
                    chosenDp=size;
                    fits=true;
                    break;
                }
            }

            if(!fits && !fallback.equals(full)) {
                chosen=fallback;
                for(float size=maxDp;size>=minDp;size-=0.5f) {
                    paint.setTextSize(dpFloat(size));
                    if(paint.measureText(fallback)<=inner) {
                        chosenDp=size;
                        fits=true;
                        break;
                    }
                }
            }

            if(!fits) chosenDp=minDp;
            button.setText(chosen);
            button.setTextSize(TypedValue.COMPLEX_UNIT_DIP,chosenDp);
        });
    }

    private float dpFloat(float value) {
        return value*getResources().getDisplayMetrics().density;
    }

    private void buildUi() {
        if(useTwoPaneUi()) {
            buildAdaptiveTwoPaneUi();
            return;
        }
        if(compactSinglePaneUi()) {
            buildCompactSinglePaneUi();
            return;
        }
        final boolean compact=compactUi();
        final boolean tablet=tabletUi();
        final boolean stackedHeader=screenWidthDp()<300 ||
                (veryLargeFontUi() && screenWidthDp()<320);

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
        header.setOrientation(stackedHeader?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        header.setGravity(stackedHeader?Gravity.LEFT:Gravity.CENTER_VERTICAL);

        LinearLayout heading=new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow=new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("КР Навигатор",responsive(21,23,26),TEXT,true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(cappedChromeSp(responsive(21,23,26)));
        int titleMaxDp=Math.max(118,screenWidthDp()-(compact?170:190));
        title.setMaxWidth(dp(titleMaxDp));
        if(Build.VERSION.SDK_INT>=26) {
            title.setAutoSizeTextTypeUniformWithConfiguration(
                    15,responsive(21,23,26),1,TypedValue.COMPLEX_UNIT_SP
            );
        }
        titleRow.addView(title,new LinearLayout.LayoutParams(-2,-2));
        TextView info=aboutHeaderButton(responsive(17,18,20));
        LinearLayout.LayoutParams infoLp=new LinearLayout.LayoutParams(dp(36),dp(36));
        infoLp.setMargins(dp(5),0,0,0);
        titleRow.addView(info,infoLp);

        TextView subtitle=text("Клинические рекомендации",responsive(11,12,13),MUTED,false);
        subtitle.setTextSize(cappedChromeSp(responsive(11,12,13)));
        subtitle.setPadding(0,dp(2),0,0);
        subtitle.setMaxLines(2);
        heading.addView(titleRow,new LinearLayout.LayoutParams(-1,-2));
        heading.addView(subtitle);

        if(stackedHeader) {
            header.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        } else {
            header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        }

        TextView sync=text("↻  Проверить",responsive(12,13,14),BLUE,true);
        sync.setSingleLine(true);
        sync.setGravity(Gravity.CENTER);
        sync.setPadding(dp(compact?7:10),dp(9),dp(compact?7:10),dp(9));
        sync.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,14));
        sync.setClickable(true);
        sync.setFocusable(true);
        sync.setContentDescription("Проверить обновления");
        sync.setMinHeight(dp(44));

        int syncWidthDp=clampInt(Math.round(screenWidthDp()*0.33f),116,148);
        LinearLayout.LayoutParams syncLp=stackedHeader
                ? new LinearLayout.LayoutParams(-1,-2)
                : new LinearLayout.LayoutParams(dp(syncWidthDp),-2);
        if(stackedHeader) syncLp.setMargins(0,dp(8),0,0);
        header.addView(sync,syncLp);
        fitSyncButton(sync,"↻  Проверить");
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
        TextView recentCaption=text("За последние 48 часов",responsive(10,11,12),MUTED,false);
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
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP,responsive(14,15,16));
        search.setSingleLine(true);
        search.setPadding(dp(responsive(11,14,16)),0,dp(responsive(11,14,16)),0);
        search.setBackground(rounded(CARD,LINE,15));
        search.setMinHeight(dp(responsive(46,48,52)));
        root.addView(search,new LinearLayout.LayoutParams(-1,-2));
        fitSearchField(search,responsive(14,15,16),10f);

        contentHost=new FrameLayout(this);
        LinearLayout.LayoutParams contentLp=new LinearLayout.LayoutParams(-1,0,1);
        contentLp.setMargins(0,dp(6),0,0);
        root.addView(contentHost,contentLp);

        LinearLayout nav=buildBottomNavigation();
        LinearLayout.LayoutParams navLp=new LinearLayout.LayoutParams(-1,-2);
        navLp.setMargins(0,dp(5),0,0);
        root.addView(nav,navLp);

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


    private void buildAdaptiveTwoPaneUi() {
        final boolean expanded=expandedTwoPaneUi();
        final boolean low=lowHeightUi();
        final int widthDp=screenWidthDp();

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
        int outerPad=low?6:(expanded?14:10);
        root.setPadding(dp(outerPad),dp(low?4:7),dp(outerPad),dp(low?4:7));
        root.setBackgroundColor(BG);
        shell.addView(root,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout heading=new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow=new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("КР Навигатор",low?18:(expanded?23:21),TEXT,true);
        title.setIncludeFontPadding(false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        int headingApprox=Math.round(widthDp*(expanded?0.30f:0.34f));
        title.setMaxWidth(dp(Math.max(110,headingApprox-(low?40:46))));
        if(Build.VERSION.SDK_INT>=26) {
            title.setAutoSizeTextTypeUniformWithConfiguration(
                    low?13:15,low?18:(expanded?23:21),1,TypedValue.COMPLEX_UNIT_SP
            );
        }
        titleRow.addView(title,new LinearLayout.LayoutParams(-2,-2));
        TextView info=aboutHeaderButton(low?16:(expanded?19:18));
        LinearLayout.LayoutParams infoLp=new LinearLayout.LayoutParams(dp(low?32:36),dp(low?32:36));
        infoLp.setMargins(dp(3),0,0,0);
        titleRow.addView(info,infoLp);
        heading.addView(titleRow,new LinearLayout.LayoutParams(-1,-2));

        status=text("",low?9:10,MUTED,false);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        if(!low) {
            status.setPadding(0,dp(2),0,0);
            heading.addView(status,new LinearLayout.LayoutParams(-1,-2));
        }

        LinearLayout.LayoutParams headingLp=new LinearLayout.LayoutParams(0,-2,expanded?0.30f:0.34f);
        headingLp.setMargins(0,0,dp(low?5:9),0);
        header.addView(heading,headingLp);

        recentList=text("",low?10:11,TEXT,false);
        recentList.setMaxLines(low?1:2);
        recentList.setEllipsize(TextUtils.TruncateAt.END);
        recentList.setGravity(Gravity.CENTER_VERTICAL);
        recentList.setPadding(dp(low?7:10),dp(low?4:5),dp(low?7:10),dp(low?4:5));
        recentList.setBackground(rounded(CARD,LINE,12));
        recentList.setClickable(true);
        recentList.setFocusable(true);
        recentList.setOnClickListener(v -> showRecentDialog());
        recentList.setMinHeight(dp(low?34:42));
        LinearLayout.LayoutParams recentLp=new LinearLayout.LayoutParams(0,-2,expanded?0.70f:0.66f);
        recentLp.setMargins(0,0,dp(low?5:9),0);
        header.addView(recentList,recentLp);

        TextView sync=text(low?"↻":"↻  Проверить",low?18:12,BLUE,true);
        sync.setGravity(Gravity.CENTER);
        sync.setPadding(dp(low?10:12),dp(7),dp(low?10:12),dp(7));
        sync.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,13));
        sync.setClickable(true);
        sync.setFocusable(true);
        sync.setContentDescription("Проверить обновления");
        sync.setMinHeight(dp(low?34:40));
        header.addView(sync,new LinearLayout.LayoutParams(-2,-2));
        fitSyncButton(sync,low?"↻":"↻  Проверить");
        root.addView(header,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout body=new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp=new LinearLayout.LayoutParams(-1,0,1);
        bodyLp.setMargins(0,dp(low?4:6),0,0);
        root.addView(body,bodyLp);

        int sidebarDp=Math.round(widthDp*(expanded?0.27f:0.32f));
        sidebarDp=clampInt(sidebarDp,expanded?220:190,expanded?320:270);
        // Keep enough room for the recommendation pane even when system bars
        // or split-screen reduce the real pixel width.
        sidebarDp=Math.min(sidebarDp,Math.max(170,widthDp-350));

        LinearLayout leftCard=new LinearLayout(this);
        leftCard.setOrientation(LinearLayout.VERTICAL);
        leftCard.setPadding(dp(low?5:8),dp(low?4:6),dp(low?5:8),dp(low?4:6));
        leftCard.setBackground(rounded(CARD,LINE,15));
        LinearLayout.LayoutParams leftLp=new LinearLayout.LayoutParams(dp(sidebarDp),-1);
        leftLp.setMargins(0,0,dp(low?5:8),0);
        body.addView(leftCard,leftLp);

        TextView profilesTitle=text("Профили",low?13:(expanded?16:15),TEXT,true);
        profilesTitle.setPadding(dp(4),0,dp(4),dp(low?2:4));
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
        body.addView(right,new LinearLayout.LayoutParams(0,-1,1));

        search=new EditText(this);
        search.setHint(widthDp<720?"Название, № КР или МКБ-10":"Название, номер КР или код МКБ-10");
        search.setHintTextColor(Color.rgb(145,153,165));
        search.setTextColor(TEXT);
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP,low?12:(expanded?14:13));
        search.setSingleLine(true);
        search.setPadding(dp(low?9:12),0,dp(low?9:12),0);
        search.setBackground(rounded(CARD,LINE,13));
        search.setMinHeight(dp(low?36:42));
        right.addView(search,new LinearLayout.LayoutParams(-1,-2));
        fitSearchField(search,low?12f:(expanded?14f:13f),9.5f);

        contentHost=new FrameLayout(this);
        LinearLayout.LayoutParams contentLp=new LinearLayout.LayoutParams(-1,0,1);
        contentLp.setMargins(0,dp(low?3:5),0,0);
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

    private void buildCompactSinglePaneUi() {
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
        root.setPadding(dp(8),dp(4),dp(8),dp(4));
        root.setBackgroundColor(BG);
        shell.addView(root,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout heading=new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow=new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("КР Навигатор",19,TEXT,true);
        title.setSingleLine(true);
        title.setIncludeFontPadding(false);
        title.setMaxWidth(dp(Math.max(120,screenWidthDp()-105)));
        if(Build.VERSION.SDK_INT>=26) {
            title.setAutoSizeTextTypeUniformWithConfiguration(
                    13,19,1,TypedValue.COMPLEX_UNIT_SP
            );
        }
        titleRow.addView(title,new LinearLayout.LayoutParams(-2,-2));
        TextView info=aboutHeaderButton(16);
        LinearLayout.LayoutParams infoLp=new LinearLayout.LayoutParams(dp(32),dp(32));
        infoLp.setMargins(dp(3),0,0,0);
        titleRow.addView(info,infoLp);
        heading.addView(titleRow,new LinearLayout.LayoutParams(-1,-2));

        status=text("",9,MUTED,false);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(status,new LinearLayout.LayoutParams(-1,-2));
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));

        TextView sync=text("↻",19,BLUE,true);
        sync.setGravity(Gravity.CENTER);
        sync.setPadding(dp(10),dp(6),dp(10),dp(6));
        sync.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,12));
        sync.setClickable(true);
        sync.setFocusable(true);
        sync.setContentDescription("Проверить обновления");
        LinearLayout.LayoutParams syncLp=new LinearLayout.LayoutParams(-2,-2);
        syncLp.setMargins(dp(6),0,0,0);
        header.addView(sync,syncLp);
        fitSyncButton(sync,"↻");
        root.addView(header,new LinearLayout.LayoutParams(-1,-2));

        recentList=text("",10,TEXT,false);
        recentList.setSingleLine(true);
        recentList.setEllipsize(TextUtils.TruncateAt.END);
        recentList.setGravity(Gravity.CENTER_VERTICAL);
        recentList.setPadding(dp(8),dp(4),dp(8),dp(4));
        recentList.setBackground(rounded(CARD,LINE,11));
        recentList.setClickable(true);
        recentList.setOnClickListener(v -> showRecentDialog());
        LinearLayout.LayoutParams recentLp=new LinearLayout.LayoutParams(-1,-2);
        recentLp.setMargins(0,dp(3),0,dp(3));
        root.addView(recentList,recentLp);

        search=new EditText(this);
        search.setHint("Название, № КР или МКБ-10");
        search.setHintTextColor(Color.rgb(145,153,165));
        search.setTextColor(TEXT);
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP,12);
        search.setSingleLine(true);
        search.setPadding(dp(9),0,dp(9),0);
        search.setBackground(rounded(CARD,LINE,12));
        search.setMinHeight(dp(36));
        root.addView(search,new LinearLayout.LayoutParams(-1,-2));
        fitSearchField(search,12f,9f);

        contentHost=new FrameLayout(this);
        LinearLayout.LayoutParams contentLp=new LinearLayout.LayoutParams(-1,0,1);
        contentLp.setMargins(0,dp(3),0,0);
        root.addView(contentHost,contentLp);

        LinearLayout nav=buildBottomNavigation();
        LinearLayout.LayoutParams navLp=new LinearLayout.LayoutParams(-1,-2);
        navLp.setMargins(0,dp(3),0,0);
        root.addView(nav,navLp);

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
                "★ Избранное",db==null?0:db.favoriteCount(),currentPage==PAGE_FAVORITES,
                v -> {
                    currentPage=PAGE_FAVORITES;
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

    private View landscapeNavRow(String label,int count,boolean selected,View.OnClickListener click) {
        final boolean low=lowHeightUi();

        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(low?7:9),dp(low?5:7),dp(low?6:8),dp(low?5:7));
        row.setBackground(rounded(selected?BLUE_SOFT:Color.TRANSPARENT,Color.TRANSPARENT,11));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(click);

        TextView name=text(label,low?11:(expandedTwoPaneUi()?13:12),selected?BLUE_DARK:TEXT,selected);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setLineSpacing(0,1.0f);
        row.addView(name,new LinearLayout.LayoutParams(0,-2,1));

        if(count>=0) {
            TextView number=text(String.valueOf(count),low?10:11,selected?BLUE:MUTED,selected);
            number.setGravity(Gravity.CENTER);
            number.setPadding(dp(5),0,0,0);
            row.addView(number,new LinearLayout.LayoutParams(-2,-2));
        }

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
        AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle("Изменения за 48 часов")
                .setMessage(sb.toString())
                .setPositiveButton("ОК",null)
                .create();
        dialog.setOnShowListener(d -> applyAdaptiveAlertWindow(dialog));
        dialog.show();
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        // Navigation between main sections is now explicit through the
        // persistent bottom bar. Horizontal swipes remain available to
        // child controls but no longer change the current app section.
        return super.dispatchTouchEvent(e);
    }

    private void swipeRight() {
        selectedProfile=null;
        if(currentPage==PAGE_HISTORY) {
            currentPage=PAGE_PROFILES;
            renderCurrentPage(-1);
        } else if(currentPage==PAGE_PROFILES) {
            currentPage=PAGE_ALL;
            renderCurrentPage(-1);
        }
    }

    private void swipeLeft() {
        selectedProfile=null;
        if(currentPage==PAGE_ALL) {
            currentPage=PAGE_PROFILES;
            renderCurrentPage(1);
        } else if(currentPage==PAGE_PROFILES) {
            currentPage=PAGE_HISTORY;
            renderCurrentPage(1);
        }
    }

    private void reload() {
        all=db.all();
        String last=getSharedPreferences("prefs",MODE_PRIVATE).getString("last_sync","ещё не выполнялась");
        String notifyNote=notificationsEnabled()?"":"\n⚠ Уведомления Android отключены";
        if(compactChromeUi()) {
            status.setText(all.size()+" КР  •  проверено: "+last+(notificationsEnabled()?"":"  •  уведомления выкл."));
        } else {
            status.setText(all.size()+" КР  •  Последняя проверка: "+last+notifyNote);
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
        List<DbHelper.ChangeEvent> recent=changesForLastScan(compactChromeUi()?30:5);
        if(recent.isEmpty()) {
            recentList.setText(compactChromeUi()?"Изменения за 48 ч: нет":"За 48 часов до последней проверки новых или обновлённых КР не обнаружено.");
            recentList.setTextColor(MUTED);
            return;
        }
        recentList.setTextColor(TEXT);

        if(compactChromeUi()) {
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
        refreshBottomNavigation();

        if(useTwoPaneUi()) {
            refreshLandscapeSidebar();
            if(currentPage==PAGE_ALL) showContent(makeListPage("Все КР",all),0);
            else if(currentPage==PAGE_HISTORY) showContent(makeLandscapeHistoryPage(),0);
            else if(currentPage==PAGE_FAVORITES) showContent(makeFavoritesPage(),0);
            else if(currentPage==PAGE_ABOUT) showContent(makeAboutPage(),0);
            else if(selectedProfile!=null) showContent(makeLandscapeProfilePage(selectedProfile),0);
            else showContent(makeListPage("Все КР",all),0);
            return;
        }

        if(currentPage==PAGE_ALL) showContent(makeListPage("Все КР",all),direction);
        else if(currentPage==PAGE_HISTORY) showContent(makeHistoryPage(),direction);
        else if(currentPage==PAGE_FAVORITES) showContent(makeFavoritesPage(),direction);
        else if(currentPage==PAGE_ABOUT) showContent(makeAboutPage(),direction);
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
        Set<String> favorites=db==null?Collections.emptySet():db.favoriteBaseIds();
        if(!favorites.isEmpty()) {
            for(List<Recommendation> bucket:out.values()) {
                bucket.sort((a,b) -> {
                    boolean af=favorites.contains(a.baseId);
                    boolean bf=favorites.contains(b.baseId);
                    if(af!=bf) return af?-1:1;
                    return 0;
                });
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
        if(compactSinglePaneUi()) return makeCompactProfilesPage();

        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout pageHead=new LinearLayout(this);
        pageHead.setOrientation(LinearLayout.HORIZONTAL);
        pageHead.setGravity(Gravity.CENTER_VERTICAL);
        pageHead.addView(pageTitle("Профили"),new LinearLayout.LayoutParams(0,-2,1));
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
        int cardHeight=profileCardHeightDp();
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

    private View makeCompactProfilesPage() {
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head=new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(pageTitle("Профили"),new LinearLayout.LayoutParams(0,-2,1));
        outer.addView(head,new LinearLayout.LayoutParams(-1,-2));

        LinkedHashMap<String,List<Recommendation>> groups=groupWithUserProfiles(all);
        ScrollView scroll=new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout rows=new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows,new ScrollView.LayoutParams(-1,-2));

        for(String p:ProfileClassifier.PROFILES) {
            List<Recommendation> items=groups.get(p);
            if(items==null || items.isEmpty()) continue;
            View row=landscapeNavRow(p,items.size(),false,v -> {
                selectedProfile=p;
                currentPage=PAGE_PROFILES;
                renderCurrentPage(0);
            });
            rows.addView(row);
        }

        outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        return outer;
    }

    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(),0).versionName;
        } catch(Exception e) {
            return "";
        }
    }

    private View aboutSection(String title,String body) {
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad=lowHeightUi()?9:13;
        card.setPadding(dp(pad),dp(lowHeightUi()?7:11),dp(pad),dp(lowHeightUi()?7:11));
        card.setBackground(rounded(CARD,LINE,14));

        TextView heading=text(title,lowHeightUi()?12:14,TEXT,true);
        card.addView(heading);

        TextView textView=text(body,lowHeightUi()?10:12,MUTED,false);
        textView.setPadding(0,dp(4),0,0);
        textView.setLineSpacing(dp(2),1.05f);
        textView.setTextIsSelectable(true);
        card.addView(textView);

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,0,0,dp(lowHeightUi()?5:8));
        card.setLayoutParams(lp);
        return card;
    }

    private View makeAboutPage() {
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head=new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(pageTitle("О приложении"),new LinearLayout.LayoutParams(0,-2,1));

        if(!useTwoPaneUi()) {
            TextView back=text("‹ Профили",11,BLUE,true);
            back.setPadding(dp(8),dp(5),dp(8),dp(5));
            back.setClickable(true);
            back.setOnClickListener(v -> {
                currentPage=PAGE_PROFILES;
                selectedProfile=null;
                renderCurrentPage(0);
            });
            head.addView(back);
        }
        outer.addView(head);

        ScrollView scroll=new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body=new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0,dp(2),0,dp(10));
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));

        SharedPreferences prefs=getSharedPreferences("prefs",MODE_PRIVATE);
        String version=appVersionName();
        String lastSuccess=prefs.getString("last_sync","ещё не выполнялась");
        String lastAttempt=prefs.getString("last_sync_attempt","");
        String syncStatus=prefs.getString("last_sync_status","");
        int checkedCount=prefs.getInt("last_sync_count",0);

        String state;
        if("ERROR".equals(syncStatus)) {
            state="Последняя попытка обновления не удалась. Используется сохранённый локальный каталог.";
            if(lastAttempt!=null && !lastAttempt.isEmpty()) state+="\nПоследняя попытка: "+lastAttempt;
        } else if("OK".equals(syncStatus)) {
            state="Каталог прошёл проверку целостности.";
            if(checkedCount>0) state+=" Проверено записей: "+checkedCount+".";
        } else {
            state="Используется локальный каталог. Проверка состояния ещё не зарегистрирована.";
        }

        body.addView(aboutSection(
                "КР Навигатор",
                "Версия: "+(version==null||version.isEmpty()?"—":version)+
                "\nКР в локальном каталоге: "+all.size()+
                "\nПоследняя успешная синхронизация: "+lastSuccess
        ));

        body.addView(aboutSection("Состояние каталога",state));

        body.addView(aboutSection(
                "Источник данных",
                "Основной источник — официальный Рубрикатор клинических рекомендаций Минздрава России. "+
                "При временной недоступности официального API приложение может использовать резервную копию каталога. "+
                "Перед применением обновления каталог проходит автоматическую проверку целостности."
        ));

        TextView official=dialogAction("Открыть официальный рубрикатор",true);
        official.setGravity(Gravity.CENTER);
        official.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://cr.minzdrav.gov.ru/clin-rec")));
            } catch(Exception e) {
                Toast.makeText(this,"Не удалось открыть браузер",Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams officialLp=new LinearLayout.LayoutParams(-1,-2);
        officialLp.setMargins(0,0,0,dp(8));
        body.addView(official,officialLp);

        body.addView(aboutSection(
                "Конфиденциальность",
                "Приложение не требует учётной записи и не запрашивает ФИО, телефон или e-mail. "+
                "История открытых КР, пользовательское распределение по профилям и другие настройки хранятся локально на устройстве. "+
                "Сетевое соединение используется для получения каталога КР и загрузки документов."
        ));

        body.addView(aboutSection(
                "Важно",
                "КР Навигатор — вспомогательный инструмент для поиска и контроля обновлений. "+
                "Он не является официальным рубрикатором и не заменяет официальный источник. "+
                "При расхождении данных следует руководствоваться актуальной информацией официального Рубрикатора клинических рекомендаций Минздрава России."
        ));

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
        name.setMaxLines(largeFontUi()?7:5);
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
        float minimum=screenWidthDp()<380?8.8f:10.0f;
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
        if(recs==null) recs=Collections.emptyList();
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head=new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView back=text("‹  Профили",compactSinglePaneUi()?11:13,BLUE,true);
        back.setPadding(dp(compactSinglePaneUi()?7:9),dp(compactSinglePaneUi()?5:7),dp(compactSinglePaneUi()?7:9),dp(compactSinglePaneUi()?5:7));
        back.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,12));
        back.setClickable(true);
        back.setOnClickListener(v -> { selectedProfile=null; renderCurrentPage(0); });
        head.addView(back);

        if(compactSinglePaneUi()) {
            TextView name=text(profile,13,TEXT,true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(8),0,dp(6),0);
            head.addView(name,new LinearLayout.LayoutParams(0,-2,1));

            TextView count=text(recs.size()+" КР",10,MUTED,false);
            head.addView(count);
            outer.addView(head,new LinearLayout.LayoutParams(-1,-2));
        } else {
            TextView count=text(recs.size()+" КР",12,MUTED,false);
            LinearLayout.LayoutParams countLp=new LinearLayout.LayoutParams(-2,-2);
            countLp.setMargins(dp(10),0,0,0);
            head.addView(count,countLp);
            outer.addView(head);

            TextView name=pageTitle(profile);
            name.setPadding(dp(2),dp(8),dp(2),dp(5));
            outer.addView(name);
        }

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
        TextView count=text(history.size()+" КР",compactSinglePaneUi()?10:responsive(10,11,12),MUTED,false);
        head.addView(count);
        outer.addView(head);

        if(!compactSinglePaneUi()) {
            TextView caption=text("Все клинические рекомендации, которые вы открывали",12,MUTED,false);
            caption.setPadding(dp(2),0,dp(2),dp(6));
            outer.addView(caption);
        }

        if(history.isEmpty()) {
            TextView empty=text("История пока пуста.",compactSinglePaneUi()?12:14,MUTED,false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8),dp(compactSinglePaneUi()?12:30),dp(8),dp(8));
            outer.addView(empty);
        } else addRecommendationList(outer,history);
        return outer;
    }

    private View makeFavoritesPage() {
        List<Recommendation> favorites=db.favorites();
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head=new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(pageTitle("Избранное"),new LinearLayout.LayoutParams(0,-2,1));

        TextView count=text(favorites.size()+" КР",compactSinglePaneUi()?10:12,MUTED,false);
        count.setPadding(dp(6),0,0,0);
        head.addView(count);
        outer.addView(head);

        if(favorites.isEmpty()) {
            TextView empty=text(
                    "Избранных КР пока нет. Нажмите ☆ рядом с нужной рекомендацией, чтобы добавить её сюда.",
                    compactSinglePaneUi()?12:14,MUTED,false
            );
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16),dp(compactSinglePaneUi()?18:36),dp(16),dp(12));
            empty.setLineSpacing(dp(2),1.05f);
            outer.addView(empty,new LinearLayout.LayoutParams(-1,-2));
        } else {
            TextView caption=text("Сохранённые клинические рекомендации",compactSinglePaneUi()?10:12,MUTED,false);
            caption.setPadding(dp(2),0,dp(2),dp(6));
            outer.addView(caption);
            addRecommendationList(outer,favorites);
        }
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

    private void closeSearchToProfiles() {
        selectedProfile=null;
        currentPage=PAGE_PROFILES;
        if(search!=null && search.getText()!=null && search.getText().length()>0) {
            search.setText("");
        } else {
            renderCurrentPage(0);
        }
    }

    private View makeSearchResultsPage(String heading,List<Recommendation> recs) {
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head=new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView back=text("‹ Профили",compactSinglePaneUi()?10:11,BLUE,true);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(compactSinglePaneUi()?6:8),dp(5),dp(compactSinglePaneUi()?6:8),dp(5));
        back.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,11));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> closeSearchToProfiles());
        LinearLayout.LayoutParams backLp=new LinearLayout.LayoutParams(-2,-2);
        backLp.setMargins(0,0,dp(7),0);
        head.addView(back,backLp);

        TextView title=pageTitle(heading);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0,dp(7),dp(5),dp(7));
        head.addView(title,new LinearLayout.LayoutParams(0,-2,1));

        TextView count=text(recs.size()+" КР",compactSinglePaneUi()?10:12,MUTED,false);
        count.setSingleLine(true);
        head.addView(count);

        outer.addView(head);
        addRecommendationList(outer,recs);
        return outer;
    }

    private void renderSearch(String q) {
        String raw=q==null?"":q.trim();
        boolean idQuery=raw.matches("\\d+(?:_\\d+)?");
        boolean mkbQuery=!idQuery && MkbUtils.looksLikeCode(raw);
        boolean aliasQuery=!idQuery && !mkbQuery && SearchAliases.hasAlias(raw);
        boolean acronymQuery=!idQuery && !mkbQuery && raw.trim().matches("(?iu)[а-яa-z0-9]{2,5}");
        searchShowsMkb=mkbQuery;

        ArrayList<Recommendation> results=new ArrayList<>();

        if(idQuery) {
            for(Recommendation r:all) {
                if(raw.equalsIgnoreCase(r.id) || raw.equalsIgnoreCase(r.baseId)) results.add(r);
            }
            if(results.isEmpty()) {
                for(Recommendation r:all) {
                    if(r.baseId!=null && r.baseId.startsWith(raw)) results.add(r);
                }
            }
        } else {
            final String direct=norm(raw);
            final LinkedHashSet<String> variants=SearchAliases.variants(raw);
            final LinkedHashMap<Recommendation,Integer> scored=new LinkedHashMap<>();

            for(Recommendation r:all) {
                int score=0;
                String title=norm(r.title);

                if(!direct.isEmpty() && titleContainsVariant(title,direct)) score=Math.max(score,100);
                if(MkbUtils.matches(r.mkbCodes,raw)) score=Math.max(score,95);

                if(aliasQuery) {
                    for(String variant:variants) {
                        String v=norm(variant);
                        if(v.equals(direct)) continue;
                        if(titleContainsVariant(title,v)) {
                            score=Math.max(score,80);
                            break;
                        }
                    }
                }

                if(acronymQuery && SearchAliases.titleAcronymMatches(r.title,raw)) {
                    score=Math.max(score,65);
                }

                if(score>0) scored.put(r,score);
            }

            results.addAll(scored.keySet());
            results.sort((a,b) -> {
                int sa=scored.get(a), sb=scored.get(b);
                if(sa!=sb) return Integer.compare(sb,sa);
                return a.title.compareToIgnoreCase(b.title);
            });
        }

        String heading;
        if(idQuery) heading="КР "+raw;
        else if(mkbQuery) heading="МКБ-10: "+raw.toUpperCase(Locale.ROOT);
        else if(aliasQuery || acronymQuery) heading="Результаты: "+raw.toUpperCase(Locale.ROOT);
        else heading="Результаты поиска";

        showContent(makeSearchResultsPage(heading,results),0);
    }

    private void addRecommendationList(LinearLayout outer,List<Recommendation> recs) {
        final Map<String,DbHelper.ProfileRule> profileRules=db.allProfileRules();
        final Set<String> favoriteIds=new LinkedHashSet<>(db.favoriteBaseIds());
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
                boolean dense=useTwoPaneUi() || compactSinglePaneUi();
                int rowHPad=dense?(lowHeightUi()?7:9):responsive(9,12,14);
                int rowVPad=dense?(lowHeightUi()?5:7):responsive(8,10,11);
                row.setPadding(dp(rowHPad),dp(rowVPad),dp(rowHPad),dp(rowVPad));
                row.setBackground(rounded(CARD,LINE,14));

                LinearLayout labels=new LinearLayout(MainActivity.this);
                labels.setOrientation(LinearLayout.VERTICAL);
                TextView name=text(r.title,dense?(lowHeightUi()?12:13):responsive(13,14,15),TEXT,false);
                name.setMaxLines(largeFontUi()?(dense?3:5):(dense?2:3));
                String metaText="КР "+r.id+(PdfManager.isPdf(PdfManager.file(MainActivity.this,r))?"  •  PDF скачан":"");
                if(profileRules.containsKey(r.baseId)) metaText += "  •  ✎ профиль настроен";
                if(searchShowsMkb && r.mkbCodes!=null && !r.mkbCodes.isEmpty()) metaText += "  •  МКБ-10: "+r.mkbCodes;
                TextView meta=text(metaText,(useTwoPaneUi()||compactSinglePaneUi())?10:responsive(10,11,12),MUTED,false);
                meta.setPadding(0,dp((useTwoPaneUi()||compactSinglePaneUi())?2:4),0,0);
                labels.addView(name);
                labels.addView(meta);
                row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

                boolean favorite=favoriteIds.contains(r.baseId);
                TextView star=text(favorite?"★":"☆",favorite?22:24,
                        favorite?Color.rgb(206,145,0):Color.rgb(125,139,157),true);
                star.setGravity(Gravity.CENTER);
                star.setClickable(true);
                star.setFocusable(false);
                star.setContentDescription(favorite?"Убрать из избранного":"Добавить в избранное");
                star.setOnClickListener(v -> {
                    boolean now=db.toggleFavorite(r.baseId);
                    if(now) favoriteIds.add(r.baseId); else favoriteIds.remove(r.baseId);
                    star.setText(now?"★":"☆");
                    star.setTextColor(now?Color.rgb(206,145,0):Color.rgb(125,139,157));
                    star.setContentDescription(now?"Убрать из избранного":"Добавить в избранное");
                    Toast.makeText(MainActivity.this,
                            now?"Добавлено в избранное":"Удалено из избранного",
                            Toast.LENGTH_SHORT).show();
                    refreshAfterProfileChange();
                });
                row.addView(star,new LinearLayout.LayoutParams(dp(38),dp(48)));

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
        float size=compactChromeUi()?(lowHeightUi()?12:13):responsive(13,14,15);
        TextView v=text(label,size,primary?Color.WHITE:TEXT,true);
        v.setGravity(Gravity.CENTER_VERTICAL);
        int hPad=lowHeightUi()?10:14;
        int vPad=lowHeightUi()?8:12;
        v.setPadding(dp(hPad),dp(vPad),dp(hPad),dp(vPad));
        v.setMinHeight(dp(lowHeightUi()?40:46));
        v.setBackground(rounded(primary?BLUE:CARD,primary?Color.TRANSPARENT:LINE,14));
        v.setClickable(true);
        return v;
    }

    private void showRecommendationOptions(Recommendation r) {
        final Dialog dialog=new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        final boolean bounded=dialogNeedsBoundedHeight();
        final boolean dense=lowHeightUi();

        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad=dense?11:18;
        card.setPadding(dp(pad),dp(dense?9:16),dp(pad),dp(dense?9:14));
        card.setBackground(rounded(CARD,LINE,20));

        ScrollView scroll=new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(false);

        LinearLayout body=new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));

        TextView badge=text("КР "+r.id,dense?10:11,BLUE,true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(dense?8:10),dp(dense?4:6),dp(dense?8:10),dp(dense?4:6));
        badge.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,12));
        body.addView(badge,new LinearLayout.LayoutParams(-2,-2));

        TextView title=text("Настроить профиль КР",dense?16:responsive(18,20,21),TEXT,true);
        title.setPadding(0,dp(dense?7:12),0,dp(dense?3:5));
        body.addView(title);

        TextView recTitle=text(r.title,dense?11:responsive(12,13,14),TEXT,false);
        recTitle.setMaxLines(dense?2:4);
        recTitle.setEllipsize(TextUtils.TruncateAt.END);
        recTitle.setPadding(0,0,0,dp(dense?6:10));
        body.addView(recTitle);

        Set<String> auto=ProfileClassifier.groupsFor(r);
        Set<String> effective=effectiveGroups(r);
        DbHelper.ProfileRule rule=db.getProfileRule(r.baseId);

        TextView current=text("Сейчас: "+joinProfiles(effective),dense?10:responsive(11,12,13),MUTED,false);
        current.setLineSpacing(dp(1),1f);
        current.setPadding(0,0,0,dp(2));
        body.addView(current);

        TextView original=text("Автоматически: "+joinProfiles(auto),dense?9:responsive(10,11,12),MUTED,false);
        original.setLineSpacing(dp(1),1f);
        original.setPadding(0,0,0,dp(dense?7:12));
        body.addView(original);

        TextView move=dialogAction("Переместить в другой профиль",true);
        body.addView(move,new LinearLayout.LayoutParams(-1,-2));
        ((LinearLayout.LayoutParams)move.getLayoutParams()).setMargins(0,0,0,dp(dense?5:8));

        TextView add=dialogAction("Добавить ещё в профиль",false);
        body.addView(add,new LinearLayout.LayoutParams(-1,-2));
        ((LinearLayout.LayoutParams)add.getLayoutParams()).setMargins(0,0,0,dp(dense?5:8));

        TextView reset=null;
        if(rule!=null) {
            reset=dialogAction("Вернуть исходное распределение",false);
            body.addView(reset,new LinearLayout.LayoutParams(-1,-2));
            ((LinearLayout.LayoutParams)reset.getLayoutParams()).setMargins(0,0,0,dp(dense?5:8));
        }

        LinearLayout.LayoutParams scrollLp=bounded
                ? new LinearLayout.LayoutParams(-1,0,1)
                : new LinearLayout.LayoutParams(-1,-2);
        card.addView(scroll,scrollLp);

        TextView cancel=dialogAction("Отмена",false);
        cancel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cancelLp=new LinearLayout.LayoutParams(-1,-2);
        cancelLp.setMargins(0,dp(dense?5:8),0,0);
        card.addView(cancel,cancelLp);

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
        dialog.show();
        applyAdaptiveDialogWindow(dialog,bounded);
    }

    private void showProfilePicker(Recommendation r,boolean replaceMode) {
        final Dialog dialog=new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        final boolean dense=lowHeightUi();

        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(dense?10:16),dp(dense?8:15),dp(dense?10:16),dp(dense?8:14));
        card.setBackground(rounded(CARD,LINE,20));

        TextView title=text(replaceMode?"Переместить КР":"Добавить в профиль",
                dense?16:responsive(18,20,21),TEXT,true);
        title.setSingleLine(false);
        card.addView(title);

        TextView caption=text(
                replaceMode?"Выберите новый профиль. КР будет показана только в выбранном профиле."
                           :"Можно выбрать один или несколько дополнительных профилей.",
                dense?10:responsive(11,12,13),MUTED,false);
        caption.setPadding(0,dp(dense?2:4),0,dp(dense?5:10));
        caption.setLineSpacing(dp(1),1f);
        caption.setMaxLines(dense?2:4);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(caption);

        final Set<String> effective=effectiveGroups(r);
        final LinkedHashSet<String> selected=new LinkedHashSet<>();
        final String[] selectedSingle=new String[]{effective.size()==1?effective.iterator().next():null};

        ScrollView scroll=new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        LinearLayout choices=new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(choices,new ScrollView.LayoutParams(-1,-2));

        ArrayList<TextView> rows=new ArrayList<>();
        for(String profile:ProfileClassifier.PROFILES) {
            boolean already=effective.contains(profile);
            String prefix=replaceMode
                    ? (profile.equals(selectedSingle[0])?"●  ":"○  ")
                    : (already?"✓  ":"○  ");
            TextView row=text(prefix+profile,dense?11:responsive(12,13,14),
                    already&&!replaceMode?MUTED:TEXT,false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMaxLines(dense?1:2);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setPadding(dp(dense?8:12),dp(dense?6:10),dp(dense?8:10),dp(dense?6:10));
            row.setMinHeight(dp(dense?36:44));
            row.setBackground(rounded(already?BLUE_SOFT:CARD,LINE,12));
            LinearLayout.LayoutParams rowLp=new LinearLayout.LayoutParams(-1,-2);
            rowLp.setMargins(0,dp(dense?1:3),0,dp(dense?1:3));
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

        card.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout buttons=new LinearLayout(this);
        boolean stackButtons=screenWidthDp()<340 || systemFontScale()>1.30f;
        buttons.setOrientation(stackButtons?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonsLp=new LinearLayout.LayoutParams(-1,-2);
        buttonsLp.setMargins(0,dp(dense?5:10),0,0);
        card.addView(buttons,buttonsLp);

        TextView cancel=dialogAction("Отмена",false);
        cancel.setGravity(Gravity.CENTER);
        TextView save=dialogAction(replaceMode?"Переместить":"Добавить",true);
        save.setGravity(Gravity.CENTER);

        if(stackButtons) {
            LinearLayout.LayoutParams cancelLp=new LinearLayout.LayoutParams(-1,-2);
            cancelLp.setMargins(0,0,0,dp(5));
            buttons.addView(cancel,cancelLp);
            buttons.addView(save,new LinearLayout.LayoutParams(-1,-2));
        } else {
            LinearLayout.LayoutParams cancelLp=new LinearLayout.LayoutParams(0,-2,1);
            cancelLp.setMargins(0,0,dp(5),0);
            buttons.addView(cancel,cancelLp);
            LinearLayout.LayoutParams saveLp=new LinearLayout.LayoutParams(0,-2,1);
            saveLp.setMargins(dp(5),0,0,0);
            buttons.addView(save,saveLp);
        }

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
        dialog.show();
        applyAdaptiveDialogWindow(dialog,true);
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

    private boolean titleContainsVariant(String normalizedTitle,String variant) {
        String needle=norm(variant);
        if(needle.isEmpty()) return false;
        if(needle.length()<=3) return (" "+normalizedTitle+" ").contains(" "+needle+" ");
        return normalizedTitle.contains(needle);
    }

    private String syncIdleLabel(){ return compactChromeUi()&&lowHeightUi()?"↻":"↻  Проверить"; }
    private String syncBusyLabel(){ return compactChromeUi()&&lowHeightUi()?"…":"↻  Проверяю…"; }

    private void runStartupSyncSilently() {
        if(syncInProgress) return;
        syncInProgress=true;

        executor.submit(() -> {
            try {
                SyncEngine.sync(getApplicationContext());
            } catch(Throwable ignored) {
            } finally {
                syncInProgress=false;
            }

            runOnUiThread(() -> {
                if(isFinishing() || isDestroyed()) return;
                try { reload(); } catch(Exception ignored) {}
            });
        });
    }

    private void runSync(TextView b) {
        if(syncInProgress) {
            Toast.makeText(this,"Проверка обновлений уже выполняется",Toast.LENGTH_SHORT).show();
            return;
        }

        syncInProgress=true;
        b.setEnabled(false);
        fitSyncButton(b,syncBusyLabel());
        status.setText("Проверяю обновления…");
        final ProgressDialog progress=new ProgressDialog(this);
        progress.setTitle(lowHeightUi()?"Проверка КР":"Проверка клинических рекомендаций");
        progress.setMessage(lowHeightUi()?"Получаю актуальный каталог…":"Получаю актуальный каталог и проверяю новые и обновлённые КР…");
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
            } finally {
                syncInProgress=false;
            }

            final SyncEngine.Result r=result;
            runOnUiThread(() -> {
                try { if(progress.isShowing()) progress.dismiss(); } catch(Exception ignored) {}
                if(isFinishing() || isDestroyed()) return;
                b.setEnabled(true);
                fitSyncButton(b,syncIdleLabel());
                try { reload(); } catch(Exception ignored) {}
                showSyncResult(r);
            });
        });
    }

    private void showSyncResult(SyncEngine.Result r) {
        if(r.message!=null && r.message.startsWith("Не удалось проверить обновления")) {
            AlertDialog errorDialog=new AlertDialog.Builder(this)
                    .setTitle("Проверка не выполнена")
                    .setMessage(r.message)
                    .setPositiveButton("ОК",null)
                    .create();
            errorDialog.setOnShowListener(d -> applyAdaptiveAlertWindow(errorDialog));
            errorDialog.show();
            return;
        }

        List<DbHelper.ChangeEvent> recent=changesForLastScan(50);
        StringBuilder text=new StringBuilder();
        if(recent.isEmpty()) {
            text.append("За 48 часов до момента этой проверки новых или обновлённых КР не обнаружено.");
        } else {
            text.append("Новые и обновлённые КР за 48 часов до момента проверки:");
            int max=Math.min(lowHeightUi()?8:12,recent.size());
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

        AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle("Результат проверки")
                .setMessage(text.toString())
                .setPositiveButton("ОК",null)
                .create();
        dialog.setOnShowListener(d -> applyAdaptiveAlertWindow(dialog));
        dialog.show();
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
                } else {
                    AlertDialog errorDialog=new AlertDialog.Builder(this)
                            .setTitle("PDF не скачан")
                            .setMessage("Не удалось получить PDF для КР «"+r.title+"» (ID: "+r.id+"). Попробуйте повторить позже.")
                            .setPositiveButton("ОК",null)
                            .create();
                    errorDialog.setOnShowListener(d -> applyAdaptiveAlertWindow(errorDialog));
                    errorDialog.show();
                }
            });
        });
    }

    @Override public void onBackPressed() {
        if(search!=null && search.getText()!=null && search.getText().toString().trim().length()>0) {
            closeSearchToProfiles();
            return;
        }
        if(currentPage==PAGE_ABOUT || currentPage==PAGE_FAVORITES) {
            currentPage=PAGE_PROFILES;
            selectedProfile=null;
            renderCurrentPage(0);
            return;
        }
        showExitDialog();
    }

    private void showExitDialog() {
        final Dialog dialog=new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        final boolean dense=lowHeightUi();
        final boolean stackButtons=screenWidthDp()<340 || systemFontScale()>1.30f;

        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(dense?12:20),dp(dense?10:18),dp(dense?12:20),dp(dense?10:16));
        card.setBackground(rounded(CARD,LINE,20));

        LinearLayout titleRow=new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge=text("КР",dense?10:12,BLUE,true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(dense?7:10),dp(dense?4:6),dp(dense?7:10),dp(dense?4:6));
        badge.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,12));
        titleRow.addView(badge,new LinearLayout.LayoutParams(-2,-2));

        TextView title=text("Закрыть КР Навигатор?",dense?16:responsive(18,20,21),TEXT,true);
        title.setPadding(dp(dense?8:12),0,0,0);
        titleRow.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        card.addView(titleRow,new LinearLayout.LayoutParams(-1,-2));

        TextView message=text("Вы хотите выйти из приложения?",dense?11:responsive(13,14,15),MUTED,false);
        message.setLineSpacing(dp(1),1f);
        message.setPadding(0,dp(dense?5:8),0,0);
        card.addView(message,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout buttons=new LinearLayout(this);
        buttons.setOrientation(stackButtons?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonsLp=new LinearLayout.LayoutParams(-1,-2);
        buttonsLp.setMargins(0,dp(dense?8:16),0,0);
        card.addView(buttons,buttonsLp);

        TextView stay=dialogAction("Вернуться",false);
        stay.setGravity(Gravity.CENTER);
        TextView exit=dialogAction("Выйти",true);
        exit.setGravity(Gravity.CENTER);

        if(stackButtons) {
            LinearLayout.LayoutParams stayLp=new LinearLayout.LayoutParams(-1,-2);
            stayLp.setMargins(0,0,0,dp(5));
            buttons.addView(stay,stayLp);
            buttons.addView(exit,new LinearLayout.LayoutParams(-1,-2));
        } else {
            LinearLayout.LayoutParams stayLp=new LinearLayout.LayoutParams(0,-2,1);
            stayLp.setMargins(0,0,dp(6),0);
            buttons.addView(stay,stayLp);
            LinearLayout.LayoutParams exitLp=new LinearLayout.LayoutParams(0,-2,1);
            exitLp.setMargins(dp(6),0,0,0);
            buttons.addView(exit,exitLp);
        }

        stay.setOnClickListener(v -> dialog.dismiss());
        exit.setOnClickListener(v -> {
            dialog.dismiss();
            finishAndRemoveTask();
        });

        dialog.setContentView(card);
        dialog.show();
        applyAdaptiveDialogWindow(dialog,false);
    }

    private boolean notificationsEnabled() {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return false;
        if(Build.VERSION.SDK_INT>=24) {
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            return nm==null || nm.areNotificationsEnabled();
        }
        return true;
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
