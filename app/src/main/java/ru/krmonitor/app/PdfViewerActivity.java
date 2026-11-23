package ru.krmonitor.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfViewerActivity extends Activity {
    public static final String EXTRA_PATH="pdf_path";
    public static final String EXTRA_TITLE="pdf_title";

    private ParcelFileDescriptor descriptor;
    private PdfRenderer renderer;
    private ExecutorService renderExecutor;
    private PdfPageAdapter adapter;
    private TextView pageIndicator;

    private int dp(int v){
        return (int)(v*getResources().getDisplayMetrics().density+0.5f);
    }

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        String path=getIntent().getStringExtra(EXTRA_PATH);
        String title=getIntent().getStringExtra(EXTRA_TITLE);
        if(path==null||path.trim().isEmpty()){
            Toast.makeText(this,"Не удалось открыть PDF.",Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        try{
            File file=new File(path).getCanonicalFile();
            File cacheRoot=new File(getCacheDir(),"pdf_viewer").getCanonicalFile();
            String cachePath=cacheRoot.getPath()+File.separator;
            if(!file.getPath().startsWith(cachePath)||!file.isFile()){
                throw new IllegalArgumentException("Недопустимый путь PDF");
            }

            descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
            renderer=new PdfRenderer(descriptor);
            renderExecutor=Executors.newSingleThreadExecutor();

            LinearLayout root=new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Color.rgb(244,249,252));

            LinearLayout toolbar=new LinearLayout(this);
            toolbar.setOrientation(LinearLayout.HORIZONTAL);
            toolbar.setGravity(Gravity.CENTER_VERTICAL);
            toolbar.setPadding(dp(8),dp(6),dp(8),dp(6));
            toolbar.setBackgroundColor(Color.WHITE);

            TextView back=new TextView(this);
            back.setText("‹");
            back.setTextSize(34);
            back.setTextColor(Color.rgb(10,71,146));
            back.setGravity(Gravity.CENTER);
            back.setContentDescription("Назад");
            back.setOnClickListener(v->finish());
            toolbar.addView(back,new LinearLayout.LayoutParams(dp(44),dp(44)));

            TextView heading=new TextView(this);
            heading.setText(title==null||title.trim().isEmpty()?"Документ":title);
            heading.setTextSize(15);
            heading.setTextColor(Color.rgb(22,49,73));
            heading.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
            heading.setSingleLine(true);
            heading.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(0,-2,1);
            titleLp.setMargins(dp(4),0,dp(8),0);
            toolbar.addView(heading,titleLp);

            pageIndicator=new TextView(this);
            pageIndicator.setText(renderer.getPageCount()>0?"1 / "+renderer.getPageCount():"0 / 0");
            pageIndicator.setTextSize(12);
            pageIndicator.setTextColor(Color.rgb(101,121,139));
            pageIndicator.setGravity(Gravity.CENTER);
            toolbar.addView(pageIndicator,new LinearLayout.LayoutParams(dp(72),dp(44)));

            root.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(56)));

            ListView list=new ListView(this);
            list.setBackgroundColor(Color.rgb(226,233,238));
            list.setDividerHeight(dp(8));
            list.setPadding(dp(6),dp(6),dp(6),dp(6));
            list.setClipToPadding(false);
            list.setFastScrollEnabled(renderer.getPageCount()>20);
            adapter=new PdfPageAdapter();
            list.setAdapter(adapter);
            list.setOnScrollListener(new AbsListView.OnScrollListener(){
                @Override public void onScrollStateChanged(AbsListView view,int scrollState){}
                @Override public void onScroll(AbsListView view,int firstVisibleItem,int visibleItemCount,int totalItemCount){
                    if(pageIndicator!=null&&totalItemCount>0){
                        pageIndicator.setText((firstVisibleItem+1)+" / "+totalItemCount);
                    }
                }
            });
            root.addView(list,new LinearLayout.LayoutParams(-1,0,1));

            setContentView(root);
        }catch(Exception e){
            Toast.makeText(this,"Не удалось открыть PDF: "+safeMessage(e),Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private String safeMessage(Exception e){
        String m=e.getMessage();
        return m==null||m.trim().isEmpty()?e.getClass().getSimpleName():m;
    }

    @Override protected void onDestroy(){
        if(renderExecutor!=null)renderExecutor.shutdownNow();
        if(adapter!=null)adapter.clear();
        try{if(renderer!=null)renderer.close();}catch(Exception ignored){}
        try{if(descriptor!=null)descriptor.close();}catch(Exception ignored){}
        super.onDestroy();
    }

    private final class PdfPageAdapter extends BaseAdapter {
        // Do not call Bitmap.recycle() on eviction: a visible ImageView can still
        // be drawing that bitmap even after it leaves the small memory cache.
        private final android.util.LruCache<Integer,Bitmap> cache=new android.util.LruCache<>(4);

        @Override public int getCount(){
            return renderer==null?0:renderer.getPageCount();
        }

        @Override public Object getItem(int position){return position;}
        @Override public long getItemId(int position){return position;}

        @Override public View getView(int position,View convertView,ViewGroup parent){
            FrameLayout shell;
            ImageView image;
            ProgressBar progress;

            if(convertView instanceof FrameLayout){
                shell=(FrameLayout)convertView;
                image=(ImageView)shell.getChildAt(0);
                progress=(ProgressBar)shell.getChildAt(1);
            }else{
                shell=new FrameLayout(PdfViewerActivity.this);
                shell.setBackgroundColor(Color.WHITE);

                image=new ImageView(PdfViewerActivity.this);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setAdjustViewBounds(true);
                shell.addView(image,new FrameLayout.LayoutParams(-1,-2));

                progress=new ProgressBar(PdfViewerActivity.this);
                FrameLayout.LayoutParams pLp=new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER);
                shell.addView(progress,pLp);
            }

            image.setTag(position);
            Bitmap cached=cache.get(position);
            if(cached!=null&&!cached.isRecycled()){
                progress.setVisibility(View.GONE);
                image.setImageBitmap(cached);
            }else{
                image.setImageDrawable(null);
                progress.setVisibility(View.VISIBLE);
                final ImageView target=image;
                final ProgressBar spinner=progress;
                renderExecutor.submit(() -> render(position,target,spinner));
            }
            return shell;
        }

        private void render(int position,ImageView target,ProgressBar spinner){
            Bitmap bitmap=null;
            PdfRenderer.Page page=null;
            try{
                if(renderer==null||position<0||position>=renderer.getPageCount())return;
                page=renderer.openPage(position);
                int screenWidth=getResources().getDisplayMetrics().widthPixels-dp(12);
                int targetWidth=Math.max(dp(280),Math.min(screenWidth,1600));
                float scale=targetWidth/(float)page.getWidth();
                int targetHeight=Math.max(1,Math.round(page.getHeight()*scale));
                bitmap=Bitmap.createBitmap(targetWidth,targetHeight,Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                cache.put(position,bitmap);
                Bitmap ready=bitmap;
                runOnUiThread(() -> {
                    Object tag=target.getTag();
                    if(tag instanceof Integer&&((Integer)tag)==position&&!isFinishing()){
                        target.setImageBitmap(ready);
                        spinner.setVisibility(View.GONE);
                    }
                });
            }catch(Exception ignored){
                if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();
                runOnUiThread(() -> {
                    Object tag=target.getTag();
                    if(tag instanceof Integer&&((Integer)tag)==position&&!isFinishing()){
                        spinner.setVisibility(View.GONE);
                    }
                });
            }finally{
                try{if(page!=null)page.close();}catch(Exception ignored){}
            }
        }

        void clear(){
            cache.evictAll();
        }
    }
}
