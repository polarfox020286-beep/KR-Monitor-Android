package ru.krmonitor.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class PdfViewerActivity extends Activity {
    private static final int BG=Color.rgb(239,243,248);
    private static final int CARD=Color.WHITE;
    private static final int BLUE=Color.rgb(37,99,199);
    private static final int BLUE_DARK=Color.rgb(25,70,145);
    private static final int BLUE_SOFT=Color.rgb(235,243,255);
    private static final int TEXT=Color.rgb(29,38,52);
    private static final int MUTED=Color.rgb(104,115,132);
    private static final int LINE=Color.rgb(222,228,236);

    private final ExecutorService renderExecutor=Executors.newSingleThreadExecutor();
    private final ExecutorService searchExecutor=Executors.newSingleThreadExecutor();
    private final AtomicInteger renderGeneration=new AtomicInteger();

    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private File pdfFile;
    private Recommendation recommendation;

    private ZoomImageView image;
    private ProgressBar progress;
    private TextView pageLabel;
    private TextView searchStatus;
    private LinearLayout searchPanel;
    private EditText searchInput;
    private TextView previousMatch;
    private TextView nextMatch;

    private int pageCount=0;
    private int currentPage=0;
    private ArrayList<Integer> searchPages=new ArrayList<>();
    private int searchIndex=-1;
    private volatile List<String> pageTexts=null;
    private volatile boolean indexingText=false;

    private String baseId="";
    private String title="";
    private String filename="";
    private String recId="";

    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if(Build.VERSION.SDK_INT>=30) getWindow().setDecorFitsSystemWindows(false);

        Intent intent=getIntent();
        baseId=safe(intent.getStringExtra("base_id"));
        title=safe(intent.getStringExtra("title"));
        filename=safe(intent.getStringExtra("filename"));
        recId=safe(intent.getStringExtra("rec_id"));

        DbHelper db=new DbHelper(this);
        recommendation=db.getByBase(baseId);
        if(recommendation==null && !filename.isEmpty()) {
            recommendation=new Recommendation(baseId,recId,title,filename,"");
        }
        if(recommendation!=null) {
            if(title.isEmpty()) title=recommendation.title;
            if(filename.isEmpty()) filename=recommendation.filename;
            if(recId.isEmpty()) recId=recommendation.id;
            db.markViewed(recommendation.baseId);
        }
        db.close();

        if(filename.isEmpty()) {
            Toast.makeText(this,"Не удалось определить файл КР",Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        pdfFile=new File(PdfManager.pdfDir(this),filename);
        if(!PdfManager.isPdf(pdfFile)) {
            Toast.makeText(this,"PDF не найден или повреждён",Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        PDFBoxResourceLoader.init(getApplicationContext());
        buildUi();

        try {
            descriptor=ParcelFileDescriptor.open(pdfFile,ParcelFileDescriptor.MODE_READ_ONLY);
            renderer=new PdfRenderer(descriptor);
            pageCount=renderer.getPageCount();
            if(pageCount<=0) throw new IOException("В PDF нет страниц");

            int saved=getSharedPreferences("pdf_reader",MODE_PRIVATE)
                    .getInt(pageKey(),0);
            currentPage=Math.max(0,Math.min(saved,pageCount-1));
            updatePageControls();
            renderPage(currentPage);
        } catch(Exception e) {
            Toast.makeText(this,"Не удалось открыть PDF: "+safeMessage(e),Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void buildUi() {
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
        root.setBackgroundColor(BG);
        shell.addView(root,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout toolbar=new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(6),dp(5),dp(6),dp(5));
        toolbar.setBackgroundColor(CARD);

        TextView back=action("‹",28,false);
        back.setContentDescription("Назад");
        back.setOnClickListener(v -> finish());
        toolbar.addView(back,new LinearLayout.LayoutParams(dp(44),dp(44)));

        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView mainTitle=text(title.isEmpty()?"Клиническая рекомендация":title,15,TEXT,true);
        mainTitle.setSingleLine(true);
        mainTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView sub=text(recId.isEmpty()?"PDF":"КР "+recId,10,MUTED,false);
        sub.setPadding(0,dp(1),0,0);
        titles.addView(mainTitle);
        titles.addView(sub);
        LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(0,-2,1);
        titleLp.setMargins(dp(3),0,dp(3),0);
        toolbar.addView(titles,titleLp);

        TextView find=action("⌕",23,false);
        find.setContentDescription("Поиск по тексту");
        find.setOnClickListener(v -> toggleSearch(true));
        toolbar.addView(find,new LinearLayout.LayoutParams(dp(44),dp(44)));

        TextView more=action("⋮",24,false);
        more.setContentDescription("Дополнительные действия");
        more.setOnClickListener(this::showMenu);
        toolbar.addView(more,new LinearLayout.LayoutParams(dp(44),dp(44)));

        root.addView(toolbar,new LinearLayout.LayoutParams(-1,-2));

        View toolbarLine=new View(this);
        toolbarLine.setBackgroundColor(LINE);
        root.addView(toolbarLine,new LinearLayout.LayoutParams(-1,dp(1)));

        searchPanel=buildSearchPanel();
        searchPanel.setVisibility(View.GONE);
        root.addView(searchPanel,new LinearLayout.LayoutParams(-1,-2));

        FrameLayout stage=new FrameLayout(this);
        stage.setBackgroundColor(Color.rgb(223,228,235));

        image=new ZoomImageView(this);
        image.setBackgroundColor(Color.rgb(223,228,235));
        image.setPageSwipeListener(direction -> {
            if(direction<0) goToPage(currentPage+1);
            else goToPage(currentPage-1);
        });
        stage.addView(image,new FrameLayout.LayoutParams(-1,-1));

        progress=new ProgressBar(this);
        FrameLayout.LayoutParams progressLp=new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.CENTER);
        stage.addView(progress,progressLp);

        root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout bottom=new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(8),dp(5),dp(8),dp(5));
        bottom.setBackgroundColor(CARD);

        TextView prev=action("‹",28,false);
        prev.setContentDescription("Предыдущая страница");
        prev.setOnClickListener(v -> goToPage(currentPage-1));
        bottom.addView(prev,new LinearLayout.LayoutParams(dp(52),dp(46)));

        pageLabel=text("— / —",13,TEXT,true);
        pageLabel.setGravity(Gravity.CENTER);
        bottom.addView(pageLabel,new LinearLayout.LayoutParams(0,dp(46),1));

        TextView next=action("›",28,false);
        next.setContentDescription("Следующая страница");
        next.setOnClickListener(v -> goToPage(currentPage+1));
        bottom.addView(next,new LinearLayout.LayoutParams(dp(52),dp(46)));

        root.addView(bottom,new LinearLayout.LayoutParams(-1,-2));

        setContentView(shell);
        if(Build.VERSION.SDK_INT>=30) shell.requestApplyInsets();
    }

    private LinearLayout buildSearchPanel() {
        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8),dp(6),dp(8),dp(6));
        panel.setBackgroundColor(CARD);

        LinearLayout first=new LinearLayout(this);
        first.setOrientation(LinearLayout.HORIZONTAL);
        first.setGravity(Gravity.CENTER_VERTICAL);

        searchInput=new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Найти в документе");
        searchInput.setTextSize(14);
        searchInput.setTextColor(TEXT);
        searchInput.setHintTextColor(Color.rgb(145,153,165));
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setPadding(dp(11),0,dp(11),0);
        searchInput.setBackground(rounded(CARD,LINE,12));
        first.addView(searchInput,new LinearLayout.LayoutParams(0,dp(42),1));

        TextView run=action("Найти",12,true);
        LinearLayout.LayoutParams runLp=new LinearLayout.LayoutParams(-2,dp(42));
        runLp.setMargins(dp(6),0,0,0);
        first.addView(run,runLp);

        TextView close=action("×",24,false);
        close.setContentDescription("Закрыть поиск");
        LinearLayout.LayoutParams closeLp=new LinearLayout.LayoutParams(dp(42),dp(42));
        closeLp.setMargins(dp(3),0,0,0);
        first.addView(close,closeLp);

        panel.addView(first,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout second=new LinearLayout(this);
        second.setOrientation(LinearLayout.HORIZONTAL);
        second.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams secondLp=new LinearLayout.LayoutParams(-1,-2);
        secondLp.setMargins(0,dp(4),0,0);

        searchStatus=text("Введите текст для поиска",11,MUTED,false);
        searchStatus.setSingleLine(true);
        searchStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        second.addView(searchStatus,new LinearLayout.LayoutParams(0,dp(34),1));

        previousMatch=action("‹",24,false);
        previousMatch.setContentDescription("Предыдущее совпадение");
        previousMatch.setEnabled(false);
        second.addView(previousMatch,new LinearLayout.LayoutParams(dp(42),dp(34)));

        nextMatch=action("›",24,false);
        nextMatch.setContentDescription("Следующее совпадение");
        nextMatch.setEnabled(false);
        second.addView(nextMatch,new LinearLayout.LayoutParams(dp(42),dp(34)));

        panel.addView(second,secondLp);

        run.setOnClickListener(v -> performSearch());
        close.setOnClickListener(v -> toggleSearch(false));
        previousMatch.setOnClickListener(v -> moveSearchResult(-1));
        nextMatch.setOnClickListener(v -> moveSearchResult(1));
        searchInput.setOnEditorActionListener((v,action,event) -> {
            if(action==EditorInfo.IME_ACTION_SEARCH) {
                performSearch();
                return true;
            }
            return false;
        });
        return panel;
    }

    private void toggleSearch(boolean show) {
        if(show) {
            searchPanel.setVisibility(View.VISIBLE);
            searchInput.requestFocus();
            searchInput.postDelayed(() -> {
                InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                if(imm!=null) imm.showSoftInput(searchInput,InputMethodManager.SHOW_IMPLICIT);
            },120);
        } else {
            searchPanel.setVisibility(View.GONE);
            InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if(imm!=null) imm.hideSoftInputFromWindow(searchPanel.getWindowToken(),0);
        }
    }

    private void performSearch() {
        final String query=searchInput.getText().toString().trim();
        if(query.isEmpty()) {
            searchStatus.setText("Введите текст для поиска");
            return;
        }
        if(indexingText) {
            searchStatus.setText("Подготавливаю поиск…");
            return;
        }

        indexingText=true;
        previousMatch.setEnabled(false);
        nextMatch.setEnabled(false);
        searchStatus.setText(pageTexts==null?"Извлекаю текст из PDF…":"Ищу…");
        hideKeyboard();

        searchExecutor.submit(() -> {
            try {
                List<String> texts=pageTexts;
                if(texts==null) {
                    texts=extractPageTexts();
                    pageTexts=texts;
                }

                String needle=normalizeSearchText(query);
                ArrayList<Integer> found=new ArrayList<>();
                if(!needle.isEmpty()) {
                    for(int i=0;i<texts.size();i++) {
                        if(texts.get(i).contains(needle)) found.add(i);
                    }
                }

                final ArrayList<Integer> matches=found;
                runOnUiThread(() -> {
                    indexingText=false;
                    searchPages=matches;
                    if(matches.isEmpty()) {
                        searchIndex=-1;
                        searchStatus.setText("Совпадений не найдено");
                        previousMatch.setEnabled(false);
                        nextMatch.setEnabled(false);
                        return;
                    }

                    int index=0;
                    for(int i=0;i<matches.size();i++) {
                        if(matches.get(i)>=currentPage) { index=i; break; }
                    }
                    searchIndex=index;
                    previousMatch.setEnabled(matches.size()>1);
                    nextMatch.setEnabled(matches.size()>1);
                    showCurrentSearchResult();
                });
            } catch(Exception e) {
                runOnUiThread(() -> {
                    indexingText=false;
                    searchPages.clear();
                    searchIndex=-1;
                    searchStatus.setText("Не удалось выполнить поиск");
                    Toast.makeText(this,"Ошибка поиска в PDF: "+safeMessage(e),Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private List<String> extractPageTexts() throws Exception {
        ArrayList<String> texts=new ArrayList<>();
        try(PDDocument document=PDDocument.load(pdfFile)) {
            PDFTextStripper stripper=new PDFTextStripper();
            int pages=document.getNumberOfPages();
            for(int i=1;i<=pages;i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String raw=stripper.getText(document);
                texts.add(normalizeSearchText(raw));
                if(i%10==0 || i==pages) {
                    final int done=i;
                    runOnUiThread(() -> searchStatus.setText("Подготавливаю поиск: "+done+" / "+pages+" стр."));
                }
            }
        }
        return texts;
    }

    private String normalizeSearchText(String value) {
        if(value==null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replace('ё','е')
                .replace("-\r\n","")
                .replace("-\n","")
                .replaceAll("[^а-яa-z0-9]+"," ")
                .replaceAll("\\s+"," ")
                .trim();
    }

    private void moveSearchResult(int delta) {
        if(searchPages.isEmpty()) return;
        searchIndex=(searchIndex+delta+searchPages.size())%searchPages.size();
        showCurrentSearchResult();
    }

    private void showCurrentSearchResult() {
        if(searchIndex<0 || searchIndex>=searchPages.size()) return;
        int page=searchPages.get(searchIndex);
        searchStatus.setText("Совпадение "+(searchIndex+1)+" из "+searchPages.size()+" · стр. "+(page+1));
        goToPage(page);
    }

    private void goToPage(int page) {
        if(pageCount<=0) return;
        int target=Math.max(0,Math.min(page,pageCount-1));
        if(target==currentPage && image.getDrawable()!=null) {
            updatePageControls();
            return;
        }
        currentPage=target;
        saveCurrentPage();
        updatePageControls();
        renderPage(target);
    }

    private void renderPage(final int pageIndex) {
        if(renderer==null || pageIndex<0 || pageIndex>=pageCount) return;
        final int generation=renderGeneration.incrementAndGet();
        progress.setVisibility(View.VISIBLE);

        renderExecutor.submit(() -> {
            Bitmap bitmap=null;
            try {
                PdfRenderer.Page page=renderer.openPage(pageIndex);
                try {
                    int screenWidth=getResources().getDisplayMetrics().widthPixels;
                    int targetWidth=Math.max(900,Math.min(1800,(int)(screenWidth*1.55f)));
                    int targetHeight=Math.max(1,Math.round(targetWidth*(page.getHeight()/(float)page.getWidth())));
                    if(targetHeight>3200) {
                        float ratio=3200f/targetHeight;
                        targetHeight=3200;
                        targetWidth=Math.max(1,Math.round(targetWidth*ratio));
                    }

                    bitmap=Bitmap.createBitmap(targetWidth,targetHeight,Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.WHITE);
                    page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                } finally {
                    page.close();
                }

                final Bitmap ready=bitmap;
                runOnUiThread(() -> {
                    if(generation!=renderGeneration.get() || isFinishing() || isDestroyed()) {
                        if(!ready.isRecycled()) ready.recycle();
                        return;
                    }
                    image.setPageBitmap(ready);
                    progress.setVisibility(View.GONE);
                });
            } catch(Exception e) {
                if(bitmap!=null && !bitmap.isRecycled()) bitmap.recycle();
                runOnUiThread(() -> {
                    if(generation==renderGeneration.get()) progress.setVisibility(View.GONE);
                    Toast.makeText(this,"Не удалось отобразить страницу",Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void updatePageControls() {
        pageLabel.setText(pageCount>0?(currentPage+1)+" / "+pageCount:"— / —");
    }

    private void saveCurrentPage() {
        if(baseId.isEmpty()) return;
        getSharedPreferences("pdf_reader",MODE_PRIVATE)
                .edit()
                .putInt(pageKey(),currentPage)
                .apply();
    }

    private String pageKey() {
        return "page_"+baseId;
    }

    private void showMenu(View anchor) {
        PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add("На первую страницу");
        menu.getMenu().add("Открыть другим приложением");
        menu.setOnMenuItemClickListener(item -> {
            String label=item.getTitle().toString();
            if(label.startsWith("На первую")) {
                goToPage(0);
                return true;
            }
            if(label.startsWith("Открыть другим")) {
                if(recommendation!=null) PdfManager.openExternal(this,recommendation);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void hideKeyboard() {
        InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(imm!=null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(),0);
    }

    private TextView action(String value,float size,boolean primary) {
        TextView t=text(value,size,primary?Color.WHITE:BLUE,true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(9),0,dp(9),0);
        t.setBackground(rounded(primary?BLUE:Color.TRANSPARENT,Color.TRANSPARENT,11));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private TextView text(String value,float size,int color,boolean bold) {
        TextView t=new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if(bold) t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        return t;
    }

    private android.graphics.drawable.GradientDrawable rounded(int fill,int stroke,int radius) {
        android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if(stroke!=Color.TRANSPARENT) d.setStroke(dp(1),stroke);
        return d;
    }

    private String safe(String value){ return value==null?"":value; }
    private String safeMessage(Throwable t) {
        String m=t==null?null:t.getMessage();
        return m==null||m.trim().isEmpty()?(t==null?"Ошибка":t.getClass().getSimpleName()):m;
    }

    @Override public void onBackPressed() {
        if(searchPanel!=null && searchPanel.getVisibility()==View.VISIBLE) {
            toggleSearch(false);
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onPause() {
        saveCurrentPage();
        super.onPause();
    }

    @Override protected void onDestroy() {
        renderGeneration.incrementAndGet();
        renderExecutor.shutdownNow();
        searchExecutor.shutdownNow();
        if(image!=null) image.releaseBitmap();
        try { if(renderer!=null) renderer.close(); } catch(Exception ignored) {}
        try { if(descriptor!=null) descriptor.close(); } catch(Exception ignored) {}
        super.onDestroy();
    }

    public static final class ZoomImageView extends ImageView {
        interface PageSwipeListener { void onSwipe(int direction); }

        private final Matrix drawMatrix=new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;
        private final PointF last=new PointF();
        private float relativeScale=1f;
        private boolean dragging=false;
        private Bitmap ownedBitmap;
        private PageSwipeListener swipeListener;

        public ZoomImageView(Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            setImageMatrix(drawMatrix);
            setClickable(true);

            scaleDetector=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                @Override public boolean onScaleBegin(ScaleGestureDetector detector){ return true; }
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    if(getDrawable()==null) return false;
                    float factor=detector.getScaleFactor();
                    float next=relativeScale*factor;
                    if(next<1f) factor=1f/relativeScale;
                    if(next>4f) factor=4f/relativeScale;
                    relativeScale*=factor;
                    drawMatrix.postScale(factor,factor,detector.getFocusX(),detector.getFocusY());
                    constrain();
                    setImageMatrix(drawMatrix);
                    return true;
                }
            });

            gestureDetector=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
                @Override public boolean onDown(MotionEvent e){ return true; }

                @Override public boolean onDoubleTap(MotionEvent e) {
                    if(getDrawable()==null) return true;
                    if(relativeScale>1.05f) resetZoom();
                    else {
                        relativeScale=2f;
                        drawMatrix.postScale(2f,2f,e.getX(),e.getY());
                        constrain();
                        setImageMatrix(drawMatrix);
                    }
                    return true;
                }

                @Override public boolean onFling(MotionEvent e1,MotionEvent e2,float velocityX,float velocityY) {
                    if(relativeScale>1.08f || swipeListener==null || e1==null || e2==null) return false;
                    float dx=e2.getX()-e1.getX();
                    float dy=e2.getY()-e1.getY();
                    if(Math.abs(dx)>120 && Math.abs(dx)>Math.abs(dy)*1.5f && Math.abs(velocityX)>500) {
                        swipeListener.onSwipe(dx<0?-1:1);
                        return true;
                    }
                    return false;
                }
            });
        }

        public void setPageSwipeListener(PageSwipeListener listener){ swipeListener=listener; }

        public void setPageBitmap(Bitmap bitmap) {
            Bitmap old=ownedBitmap;
            ownedBitmap=bitmap;
            setImageBitmap(bitmap);
            if(old!=null && old!=bitmap && !old.isRecycled()) old.recycle();
            post(this::resetZoom);
        }

        public void releaseBitmap() {
            setImageDrawable(null);
            if(ownedBitmap!=null && !ownedBitmap.isRecycled()) ownedBitmap.recycle();
            ownedBitmap=null;
        }

        public void resetZoom() {
            Drawable d=getDrawable();
            if(d==null || getWidth()<=0 || getHeight()<=0) return;
            relativeScale=1f;
            drawMatrix.reset();

            float dw=d.getIntrinsicWidth();
            float dh=d.getIntrinsicHeight();
            if(dw<=0 || dh<=0) return;

            float base=getWidth()/dw;
            float shownH=dh*base;
            float ty=shownH<getHeight()?(getHeight()-shownH)/2f:0f;
            drawMatrix.postScale(base,base);
            drawMatrix.postTranslate(0,ty);
            constrain();
            setImageMatrix(drawMatrix);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            gestureDetector.onTouchEvent(event);
            scaleDetector.onTouchEvent(event);

            if(event.getPointerCount()==1 && !scaleDetector.isInProgress()) {
                switch(event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        last.set(event.getX(),event.getY());
                        dragging=true;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if(dragging && getDrawable()!=null) {
                            float dx=event.getX()-last.x;
                            float dy=event.getY()-last.y;
                            drawMatrix.postTranslate(dx,dy);
                            constrain();
                            setImageMatrix(drawMatrix);
                            last.set(event.getX(),event.getY());
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        dragging=false;
                        break;
                }
            }
            return true;
        }

        private void constrain() {
            Drawable d=getDrawable();
            if(d==null || getWidth()<=0 || getHeight()<=0) return;
            RectF rect=new RectF(0,0,d.getIntrinsicWidth(),d.getIntrinsicHeight());
            drawMatrix.mapRect(rect);

            float dx=0f,dy=0f;
            if(rect.width()<=getWidth()) dx=getWidth()/2f-rect.centerX();
            else if(rect.left>0) dx=-rect.left;
            else if(rect.right<getWidth()) dx=getWidth()-rect.right;

            if(rect.height()<=getHeight()) dy=getHeight()/2f-rect.centerY();
            else if(rect.top>0) dy=-rect.top;
            else if(rect.bottom<getHeight()) dy=getHeight()-rect.bottom;

            drawMatrix.postTranslate(dx,dy);
        }

        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
            super.onSizeChanged(w,h,oldw,oldh);
            if(getDrawable()!=null) post(this::resetZoom);
        }
    }
}
