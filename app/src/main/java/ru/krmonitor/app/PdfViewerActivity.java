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
import android.util.TypedValue;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

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
    private TextView favoriteButton;
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
    private volatile String activeSearchQuery="";
    private final Map<String,List<RectF>> highlightCache=new ConcurrentHashMap<>();

    private String baseId="";
    private String title="";
    private String filename="";
    private String recId="";

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

    private float systemFontScale(){ return getResources().getConfiguration().fontScale; }
    private boolean compactReaderUi(){ return screenWidthDp()<360 || screenHeightDp()<480; }
    private int readerControlDp(){ return compactReaderUi()?40:44; }

    private void applyReaderAlertWindow(AlertDialog dialog) {
        Window w=dialog.getWindow();
        if(w==null) return;

        int margin=screenWidthDp()<360?24:40;
        int widthDp=Math.min(Math.max(260,screenWidthDp()-margin),380);
        final int width=dp(widthDp);
        w.setLayout(width,WindowManager.LayoutParams.WRAP_CONTENT);
        w.setGravity(Gravity.CENTER);

        View decor=w.getDecorView();
        decor.post(() -> {
            if(dialog.getWindow()==null) return;
            int capDp=Math.max(220,Math.min(520,Math.round(screenHeightDp()*(screenHeightDp()<480?0.88f:0.78f))));
            int cap=dp(capDp);
            dialog.getWindow().setLayout(
                    width,
                    decor.getHeight()>cap?cap:WindowManager.LayoutParams.WRAP_CONTENT
            );
            dialog.getWindow().setGravity(Gravity.CENTER);
        });
    }

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
        toolbar.setPadding(dp(compactReaderUi()?4:6),dp(compactReaderUi()?3:5),
                dp(compactReaderUi()?4:6),dp(compactReaderUi()?3:5));
        toolbar.setBackgroundColor(CARD);

        TextView back=action("‹",28,false);
        back.setTextSize(TypedValue.COMPLEX_UNIT_DIP,compactReaderUi()?25:28);
        back.setContentDescription("Назад");
        back.setOnClickListener(v -> finish());
        toolbar.addView(back,new LinearLayout.LayoutParams(dp(readerControlDp()),dp(readerControlDp())));

        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView mainTitle=text(title.isEmpty()?"Клиническая рекомендация":title,15,TEXT,true);
        mainTitle.setSingleLine(true);
        mainTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if(Build.VERSION.SDK_INT>=26) {
            mainTitle.setAutoSizeTextTypeUniformWithConfiguration(
                    compactReaderUi()?10:11,15,1,TypedValue.COMPLEX_UNIT_SP
            );
        }
        TextView sub=text(recId.isEmpty()?"PDF":"КР "+recId,10,MUTED,false);
        sub.setPadding(0,dp(1),0,0);
        titles.addView(mainTitle);
        titles.addView(sub);
        LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(0,-2,1);
        titleLp.setMargins(dp(3),0,dp(3),0);
        toolbar.addView(titles,titleLp);

        favoriteButton=action("☆",24,false);
        favoriteButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP,compactReaderUi()?21:24);
        favoriteButton.setContentDescription("Добавить в избранное");
        favoriteButton.setOnClickListener(v -> toggleFavorite());
        toolbar.addView(favoriteButton,new LinearLayout.LayoutParams(dp(readerControlDp()),dp(readerControlDp())));
        refreshFavoriteButton();

        TextView find=action("⌕",23,false);
        find.setTextSize(TypedValue.COMPLEX_UNIT_DIP,compactReaderUi()?20:23);
        find.setContentDescription("Поиск по тексту");
        find.setOnClickListener(v -> toggleSearch(true));
        toolbar.addView(find,new LinearLayout.LayoutParams(dp(readerControlDp()),dp(readerControlDp())));

        TextView more=action("⋮",24,false);
        more.setTextSize(TypedValue.COMPLEX_UNIT_DIP,compactReaderUi()?21:24);
        more.setContentDescription("Дополнительные действия");
        more.setOnClickListener(this::showMenu);
        toolbar.addView(more,new LinearLayout.LayoutParams(dp(readerControlDp()),dp(readerControlDp())));

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
        prev.setTextSize(TypedValue.COMPLEX_UNIT_DIP,compactReaderUi()?25:28);
        prev.setContentDescription("Предыдущая страница");
        prev.setOnClickListener(v -> goToPage(currentPage-1));
        prev.setMinHeight(dp(46));
        bottom.addView(prev,new LinearLayout.LayoutParams(dp(compactReaderUi()?46:52),-2));

        pageLabel=text("— / —",13,TEXT,true);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setClickable(true);
        pageLabel.setFocusable(true);
        pageLabel.setContentDescription("Выбрать страницу");
        pageLabel.setBackground(rounded(BLUE_SOFT,Color.TRANSPARENT,12));
        pageLabel.setOnClickListener(v -> showPagePicker());
        pageLabel.setMinHeight(dp(40));
        if(Build.VERSION.SDK_INT>=26) {
            pageLabel.setAutoSizeTextTypeUniformWithConfiguration(
                    10,13,1,TypedValue.COMPLEX_UNIT_SP
            );
        }
        LinearLayout.LayoutParams pageLp=new LinearLayout.LayoutParams(0,-2,1);
        pageLp.setMargins(dp(compactReaderUi()?4:8),dp(3),dp(compactReaderUi()?4:8),dp(3));
        bottom.addView(pageLabel,pageLp);

        TextView next=action("›",28,false);
        next.setTextSize(TypedValue.COMPLEX_UNIT_DIP,compactReaderUi()?25:28);
        next.setContentDescription("Следующая страница");
        next.setOnClickListener(v -> goToPage(currentPage+1));
        next.setMinHeight(dp(46));
        bottom.addView(next,new LinearLayout.LayoutParams(dp(compactReaderUi()?46:52),-2));

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
        searchInput.setMinHeight(dp(42));
        first.addView(searchInput,new LinearLayout.LayoutParams(0,-2,1));

        TextView run=action("Найти",12,true);
        run.setMinHeight(dp(42));
        LinearLayout.LayoutParams runLp=new LinearLayout.LayoutParams(-2,-2);
        runLp.setMargins(dp(6),0,0,0);
        first.addView(run,runLp);

        TextView close=action("×",24,false);
        close.setTextSize(TypedValue.COMPLEX_UNIT_DIP,22);
        close.setContentDescription("Закрыть поиск");
        close.setMinHeight(dp(42));
        LinearLayout.LayoutParams closeLp=new LinearLayout.LayoutParams(dp(42),-2);
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
        searchStatus.setMinHeight(dp(34));
        second.addView(searchStatus,new LinearLayout.LayoutParams(0,-2,1));

        previousMatch=action("‹",24,false);
        previousMatch.setTextSize(TypedValue.COMPLEX_UNIT_DIP,22);
        previousMatch.setContentDescription("Предыдущее совпадение");
        previousMatch.setEnabled(false);
        previousMatch.setMinHeight(dp(34));
        second.addView(previousMatch,new LinearLayout.LayoutParams(dp(42),-2));

        nextMatch=action("›",24,false);
        nextMatch.setTextSize(TypedValue.COMPLEX_UNIT_DIP,22);
        nextMatch.setContentDescription("Следующее совпадение");
        nextMatch.setEnabled(false);
        nextMatch.setMinHeight(dp(34));
        second.addView(nextMatch,new LinearLayout.LayoutParams(dp(42),-2));

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

            boolean hadHighlight=activeSearchQuery!=null && !activeSearchQuery.isEmpty();
            activeSearchQuery="";
            searchPages.clear();
            searchIndex=-1;
            highlightCache.clear();
            previousMatch.setEnabled(false);
            nextMatch.setEnabled(false);
            if(hadHighlight && renderer!=null && pageCount>0) renderPage(currentPage);
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
        activeSearchQuery="";
        highlightCache.clear();
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
                        activeSearchQuery="";
                        searchStatus.setText("Совпадений не найдено");
                        previousMatch.setEnabled(false);
                        nextMatch.setEnabled(false);
                        if(renderer!=null && pageCount>0) renderPage(currentPage);
                        return;
                    }

                    activeSearchQuery=query;
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
                    activeSearchQuery="";
                    highlightCache.clear();
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

    private static String normalizeSearchText(String value) {
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
        if(page==currentPage) {
            updatePageControls();
            renderPage(page);
        } else {
            goToPage(page);
        }
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

                    String query=activeSearchQuery;
                    if(query!=null && !query.trim().isEmpty()) {
                        List<RectF> highlights=findHighlightRects(pageIndex,query);
                        if(!highlights.isEmpty()) {
                            drawHighlights(bitmap,highlights,
                                    page.getWidth(),page.getHeight());
                        }
                    }
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

    private void drawHighlights(Bitmap bitmap,List<RectF> source,float pageWidth,float pageHeight) {
        if(bitmap==null || source==null || source.isEmpty() || pageWidth<=0 || pageHeight<=0) return;

        float sx=bitmap.getWidth()/pageWidth;
        float sy=bitmap.getHeight()/pageHeight;
        Canvas canvas=new Canvas(bitmap);
        Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.argb(112,255,213,45));

        Paint stroke=new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(2f,2f*getResources().getDisplayMetrics().density));
        stroke.setColor(Color.argb(180,224,155,0));

        for(RectF raw:source) {
            if(raw==null) continue;
            float left=Math.max(0,raw.left*sx-dp(2));
            float top=Math.max(0,raw.top*sy-dp(2));
            float right=Math.min(bitmap.getWidth(),raw.right*sx+dp(2));
            float bottom=Math.min(bitmap.getHeight(),raw.bottom*sy+dp(2));
            if(right<=left || bottom<=top) continue;

            RectF r=new RectF(left,top,right,bottom);
            float radius=Math.max(3f,3f*getResources().getDisplayMetrics().density);
            canvas.drawRoundRect(r,radius,radius,fill);
            canvas.drawRoundRect(r,radius,radius,stroke);
        }
    }

    private List<RectF> findHighlightRects(int pageIndex,String query) {
        String needle=normalizeSearchText(query);
        if(needle.isEmpty()) return Collections.emptyList();

        String key=pageIndex+"|"+needle;
        List<RectF> cached=highlightCache.get(key);
        if(cached!=null) return cached;

        ArrayList<RectF> result=new ArrayList<>();
        try(PDDocument document=PDDocument.load(pdfFile)) {
            HighlightStripper stripper=new HighlightStripper();
            stripper.setStartPage(pageIndex+1);
            stripper.setEndPage(pageIndex+1);
            stripper.setSortByPosition(true);
            stripper.getText(document);
            result.addAll(stripper.findMatches(needle));
        } catch(Exception ignored) {
        }

        List<RectF> safe=Collections.unmodifiableList(result);
        highlightCache.put(key,safe);
        return safe;
    }

    private static char normalizedSearchChar(char ch) {
        char lower=Character.toLowerCase(ch);
        if(lower=='ё') lower='е';
        if((lower>='а' && lower<='я') ||
                (lower>='a' && lower<='z') ||
                (lower>='0' && lower<='9')) return lower;
        return ' ';
    }

    private static final class HighlightStripper extends PDFTextStripper {
        private final StringBuilder normalized=new StringBuilder();
        private final ArrayList<RectF> boxes=new ArrayList<>();

        HighlightStripper() throws IOException {
            super();
            setSortByPosition(true);
        }

        @Override protected void writeString(String text,List<TextPosition> positions) throws IOException {
            appendSeparator();

            if(positions!=null) {
                for(TextPosition p:positions) {
                    if(p==null) continue;
                    String unicode=p.getUnicode();
                    if(unicode==null || unicode.isEmpty()) continue;

                    float x=p.getX();
                    float y=p.getY();
                    float width=Math.max(1f,p.getWidth());
                    float height=Math.max(1f,p.getHeight());
                    RectF box=new RectF(
                            x,
                            Math.max(0f,y-height),
                            x+width,
                            y+Math.max(1f,height*0.15f)
                    );

                    for(int i=0;i<unicode.length();i++) {
                        appendChar(normalizedSearchChar(unicode.charAt(i)),box);
                    }
                }
            }

            super.writeString(text,positions);
        }

        private void appendSeparator() {
            appendChar(' ',null);
        }

        private void appendChar(char ch,RectF rect) {
            if(ch==' ') {
                if(normalized.length()==0 || normalized.charAt(normalized.length()-1)==' ') return;
                normalized.append(' ');
                boxes.add(null);
                return;
            }

            normalized.append(ch);
            boxes.add(rect==null?null:new RectF(rect));
        }

        List<RectF> findMatches(String needle) {
            ArrayList<RectF> out=new ArrayList<>();
            if(needle==null || needle.isEmpty()) return out;

            String haystack=normalized.toString();
            int from=0;
            while(from<haystack.length()) {
                int start=haystack.indexOf(needle,from);
                if(start<0) break;
                int end=Math.min(boxes.size(),start+needle.length());

                RectF current=null;
                for(int i=start;i<end;i++) {
                    RectF r=boxes.get(i);
                    if(r==null) continue;

                    if(current==null) {
                        current=new RectF(r);
                        continue;
                    }

                    float tolerance=Math.max(current.height(),r.height())*0.85f;
                    boolean sameLine=Math.abs(current.centerY()-r.centerY())<=tolerance;
                    boolean closeEnough=r.left<=current.right+Math.max(4f,tolerance);

                    if(sameLine && closeEnough) {
                        current.union(r);
                    } else {
                        if(current.width()>0 && current.height()>0) out.add(current);
                        current=new RectF(r);
                    }
                }

                if(current!=null && current.width()>0 && current.height()>0) out.add(current);
                from=start+Math.max(1,needle.length());
            }
            return out;
        }
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

    private void showPagePicker() {
        if(pageCount<=0) return;

        final EditText input=new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setText(String.valueOf(currentPage+1));
        input.setSelectAllOnFocus(true);
        input.setHint("1–"+pageCount);
        input.setTextSize(18);
        int pad=dp(16);
        input.setPadding(pad,dp(10),pad,dp(10));

        LinearLayout wrap=new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20),dp(2),dp(20),0);

        TextView hint=text("Введите номер страницы от 1 до "+pageCount,12,MUTED,false);
        hint.setPadding(0,0,0,dp(6));
        wrap.addView(hint,new LinearLayout.LayoutParams(-1,-2));
        wrap.addView(input,new LinearLayout.LayoutParams(-1,-2));

        AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle("Перейти к странице")
                .setView(wrap)
                .setNegativeButton("Отмена",null)
                .setPositiveButton("Перейти",null)
                .create();

        dialog.setOnShowListener(d -> {
            applyReaderAlertWindow(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String raw=input.getText().toString().trim();
                int page;
                try {
                    page=Integer.parseInt(raw);
                } catch(Exception e) {
                    input.setError("Введите номер страницы");
                    return;
                }
                if(page<1 || page>pageCount) {
                    input.setError("Доступны страницы 1–"+pageCount);
                    return;
                }
                dialog.dismiss();
                goToPage(page-1);
            });
            input.requestFocus();
            input.postDelayed(() -> {
                InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                if(imm!=null) imm.showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);
            },120);
        });

        input.setOnEditorActionListener((v,action,event) -> {
            if(action==EditorInfo.IME_ACTION_GO && dialog.isShowing()) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                return true;
            }
            return false;
        });

        dialog.show();
    }

    private void showMenu(View anchor) {
        PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add("Перейти к странице");
        menu.getMenu().add("На первую страницу");
        menu.getMenu().add("Открыть другим приложением");
        menu.setOnMenuItemClickListener(item -> {
            String label=item.getTitle().toString();
            if(label.startsWith("Перейти к")) {
                showPagePicker();
                return true;
            }
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

    private void refreshFavoriteButton() {
        if(favoriteButton==null || baseId==null || baseId.isEmpty()) return;
        DbHelper db=new DbHelper(getApplicationContext());
        boolean favorite=db.isFavorite(baseId);
        db.close();

        favoriteButton.setText(favorite?"★":"☆");
        favoriteButton.setTextColor(favorite?Color.rgb(206,145,0):BLUE);
        favoriteButton.setContentDescription(favorite?"Убрать из избранного":"Добавить в избранное");
    }

    private void toggleFavorite() {
        if(baseId==null || baseId.isEmpty()) return;
        DbHelper db=new DbHelper(getApplicationContext());
        boolean favorite=db.toggleFavorite(baseId);
        db.close();

        favoriteButton.setText(favorite?"★":"☆");
        favoriteButton.setTextColor(favorite?Color.rgb(206,145,0):BLUE);
        favoriteButton.setContentDescription(favorite?"Убрать из избранного":"Добавить в избранное");
        Toast.makeText(this,
                favorite?"Добавлено в избранное":"Удалено из избранного",
                Toast.LENGTH_SHORT).show();
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

    @Override protected void onResume() {
        super.onResume();
        if(favoriteButton!=null) refreshFavoriteButton();
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

                    // Horizontal page turn: left = next, right = previous.
                    if(Math.abs(dx)>120 && Math.abs(dx)>Math.abs(dy)*1.25f && Math.abs(velocityX)>500) {
                        swipeListener.onSwipe(dx<0?-1:1);
                        return true;
                    }

                    // Vertical page turn: up = next, down = previous.
                    if(Math.abs(dy)>120 && Math.abs(dy)>Math.abs(dx)*1.25f && Math.abs(velocityY)>500) {
                        swipeListener.onSwipe(dy<0?-1:1);
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
