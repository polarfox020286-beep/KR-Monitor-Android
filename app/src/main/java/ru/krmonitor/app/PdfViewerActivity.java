package ru.krmonitor.app;

import android.app.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

import java.io.*;

public class PdfViewerActivity extends Activity {
    public static final String EXTRA_URI="pdf_uri";
    public static final String EXTRA_TITLE="pdf_title";

    private ParcelFileDescriptor descriptor;
    private PdfRenderer renderer;
    private PdfRenderer.Page page;
    private Bitmap bitmap;
    private ImageView image;
    private TextView counter,prev,next,zoomText;
    private ScrollView vertical;
    private HorizontalScrollView horizontal;
    private int pageIndex=0;
    private float zoom=1.0f;

    private static final int BG=Color.rgb(244,249,252);
    private static final int BLUE_DARK=Color.rgb(10,71,146);
    private static final int TEXT=Color.rgb(22,49,73);
    private static final int LINE=Color.rgb(218,232,240);

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        if(state!=null){
            pageIndex=state.getInt("page",0);
            zoom=state.getFloat("zoom",1f);
        }

        String raw=getIntent().getStringExtra(EXTRA_URI);
        String title=getIntent().getStringExtra(EXTRA_TITLE);
        if(raw==null||raw.isEmpty()){
            Toast.makeText(this,"Не удалось открыть PDF.",Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        try{
            descriptor=getContentResolver().openFileDescriptor(Uri.parse(raw),"r");
            if(descriptor==null)throw new FileNotFoundException("Файл не найден");
            renderer=new PdfRenderer(descriptor);
            if(renderer.getPageCount()==0)throw new IOException("В PDF нет страниц");
            if(pageIndex>=renderer.getPageCount())pageIndex=0;
        }catch(Exception e){
            Toast.makeText(this,"Не удалось открыть PDF: "+safeMessage(e),Toast.LENGTH_LONG).show();
            closeRenderer();
            finish();
            return;
        }

        buildUi(title==null||title.trim().isEmpty()?"Документ PDF":title);
        renderPage();
    }

    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}

    private GradientDrawable rounded(int fill,int stroke,int radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if(stroke!=Color.TRANSPARENT)d.setStroke(dp(1),stroke);
        return d;
    }

    private TextView button(String text){
        TextView v=new TextView(this);
        v.setText(text);
        v.setTextSize(16);
        v.setTextColor(BLUE_DARK);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        v.setBackground(rounded(Color.WHITE,LINE,13));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private void buildUi(String documentTitle){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout top=new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10),dp(8),dp(10),dp(8));
        top.setBackgroundColor(Color.WHITE);

        TextView back=button("‹");
        top.addView(back,new LinearLayout.LayoutParams(dp(44),dp(44)));

        TextView title=new TextView(this);
        title.setText(documentTitle);
        title.setTextSize(15);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(0,-2,1);
        titleLp.setMargins(dp(10),0,dp(8),0);
        top.addView(title,titleLp);

        TextView minus=button("−");
        zoomText=button("100%");
        zoomText.setTextSize(11);
        TextView plus=button("+");
        top.addView(minus,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout.LayoutParams zlp=new LinearLayout.LayoutParams(dp(54),dp(42));
        zlp.setMargins(dp(5),0,dp(5),0);
        top.addView(zoomText,zlp);
        top.addView(plus,new LinearLayout.LayoutParams(dp(42),dp(42)));
        root.addView(top,new LinearLayout.LayoutParams(-1,-2));

        horizontal=new HorizontalScrollView(this);
        horizontal.setFillViewport(true);
        horizontal.setBackgroundColor(Color.rgb(229,235,239));

        vertical=new ScrollView(this);
        vertical.setFillViewport(true);

        image=new ImageView(this);
        image.setBackgroundColor(Color.WHITE);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        vertical.addView(image,new ScrollView.LayoutParams(-2,-2));
        horizontal.addView(vertical,new HorizontalScrollView.LayoutParams(-2,-1));
        root.addView(horizontal,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout bottom=new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(10),dp(7),dp(10),dp(7));
        bottom.setBackgroundColor(Color.WHITE);

        prev=button("‹");
        next=button("›");
        counter=new TextView(this);
        counter.setTextColor(TEXT);
        counter.setTextSize(13);
        counter.setGravity(Gravity.CENTER);
        counter.setTypeface(Typeface.DEFAULT,Typeface.BOLD);

        bottom.addView(prev,new LinearLayout.LayoutParams(dp(52),dp(44)));
        bottom.addView(counter,new LinearLayout.LayoutParams(0,dp(44),1));
        bottom.addView(next,new LinearLayout.LayoutParams(dp(52),dp(44)));
        root.addView(bottom,new LinearLayout.LayoutParams(-1,-2));

        setContentView(root);

        back.setOnClickListener(v->finish());
        prev.setOnClickListener(v->{if(pageIndex>0){pageIndex--;renderPage();}});
        next.setOnClickListener(v->{if(renderer!=null&&pageIndex<renderer.getPageCount()-1){pageIndex++;renderPage();}});
        minus.setOnClickListener(v->{if(zoom>0.75f){zoom=Math.max(0.75f,zoom-0.25f);renderPage();}});
        plus.setOnClickListener(v->{if(zoom<2.0f){zoom=Math.min(2.0f,zoom+0.25f);renderPage();}});
    }

    private void renderPage(){
        if(renderer==null)return;
        try{
            closePage();
            page=renderer.openPage(pageIndex);

            int screen=getResources().getDisplayMetrics().widthPixels;
            int width=(int)Math.max(dp(320),Math.min(2200,(screen-dp(24))*zoom));
            int height=Math.round(width*(page.getHeight()/(float)page.getWidth()));
            if(height>3400){
                height=3400;
                width=Math.round(height*(page.getWidth()/(float)page.getHeight()));
            }

            recycleBitmap();
            bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

            image.setImageBitmap(bitmap);
            image.setLayoutParams(new ScrollView.LayoutParams(width,height));
            counter.setText((pageIndex+1)+" / "+renderer.getPageCount());
            zoomText.setText(Math.round(zoom*100)+"%");
            prev.setEnabled(pageIndex>0);
            prev.setAlpha(pageIndex>0?1f:0.35f);
            next.setEnabled(pageIndex<renderer.getPageCount()-1);
            next.setAlpha(pageIndex<renderer.getPageCount()-1?1f:0.35f);
            horizontal.post(()->horizontal.scrollTo(0,0));
            vertical.post(()->vertical.scrollTo(0,0));
        }catch(Exception e){
            Toast.makeText(this,"Не удалось отобразить страницу: "+safeMessage(e),Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onSaveInstanceState(Bundle out){
        out.putInt("page",pageIndex);
        out.putFloat("zoom",zoom);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy(){
        closePage();
        recycleBitmap();
        closeRenderer();
        super.onDestroy();
    }

    private void closePage(){
        if(page!=null){
            try{page.close();}catch(Exception ignored){}
            page=null;
        }
    }

    private void recycleBitmap(){
        if(bitmap!=null){
            try{bitmap.recycle();}catch(Exception ignored){}
            bitmap=null;
        }
    }

    private void closeRenderer(){
        if(renderer!=null){
            try{renderer.close();}catch(Exception ignored){}
            renderer=null;
        }
        if(descriptor!=null){
            try{descriptor.close();}catch(Exception ignored){}
            descriptor=null;
        }
    }

    private String safeMessage(Exception e){
        String m=e.getMessage();
        return m==null||m.trim().isEmpty()?e.getClass().getSimpleName():m;
    }
}
