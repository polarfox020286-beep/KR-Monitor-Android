package ru.krmonitor.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Native SOP PDF viewer. Canvas drawing keeps the page's original proportions.
 * No nested ScrollViews or FIT_XY image scaling: page origin is always at its top.
 */
public final class PdfViewerActivity extends Activity {
    public static final String EXTRA_URI = "pdf_uri";
    public static final String EXTRA_TITLE = "pdf_title";

    private static final int PAGE_BACKGROUND = Color.rgb(235, 240, 245);
    private static final int DARK = Color.rgb(23, 47, 70);
    private static final int ACCENT = Color.rgb(14, 89, 158);
    private static final float MIN_SCALE = 0.25f;
    private static final float MAX_SCALE = 5.0f;

    private ParcelFileDescriptor descriptor;
    private PdfRenderer pdf;
    private Bitmap rendered;
    private PageCanvas pageCanvas;
    private TextView pageIndicator;
    private TextView zoomIndicator;
    private TextView previous;
    private TextView next;
    private int pageIndex;

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        pageIndex = saved == null ? 0 : saved.getInt("pageIndex", 0);

        String uri = getIntent().getStringExtra(EXTRA_URI);
        if (uri == null || uri.trim().isEmpty()) {
            Toast.makeText(this, "Документ PDF не найден", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        try {
            descriptor = getContentResolver().openFileDescriptor(Uri.parse(uri), "r");
            if (descriptor == null) throw new FileNotFoundException("Нет доступа к документу");
            pdf = new PdfRenderer(descriptor);
            if (pdf.getPageCount() == 0) throw new IOException("В PDF нет страниц");
            pageIndex = Math.max(0, Math.min(pageIndex, pdf.getPageCount() - 1));
        } catch (Exception exception) {
            Toast.makeText(this, "Ошибка открытия PDF: " + message(exception), Toast.LENGTH_LONG).show();
            releasePdf();
            finish();
            return;
        }

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        buildLayout(title == null || title.trim().isEmpty() ? "Документ PDF" : title);
        pageCanvas.post(() -> loadPage());
    }

    private GradientDrawable rounded(int background) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(background);
        drawable.setCornerRadius(dp(9));
        return drawable;
    }

    private TextView action(String symbol, String description, int size) {
        TextView button = new TextView(this);
        button.setText(symbol);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(ACCENT);
        button.setTextSize(size);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setContentDescription(description);
        button.setBackground(rounded(Color.WHITE));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private void buildLayout(String fileTitle) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE_BACKGROUND);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), dp(3), dp(6), dp(3));
        top.setBackgroundColor(Color.WHITE);

        TextView back = action("‹", "Назад к документам", 26);
        top.addView(back, new LinearLayout.LayoutParams(dp(38), dp(50)));

        TextView title = new TextView(this);
        title.setText(fileTitle);
        title.setTextColor(DARK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextSize(13.5f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        nameLp.setMargins(dp(7), 0, dp(3), 0);
        top.addView(title, nameLp);

        TextView fitWidth = action("↔", "Подогнать документ по ширине", 21);
        top.addView(fitWidth, new LinearLayout.LayoutParams(dp(39), dp(48)));
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(56)));

        pageCanvas = new PageCanvas();
        root.addView(pageCanvas, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(7), dp(4), dp(7), dp(4));
        bottom.setBackgroundColor(Color.WHITE);

        previous = action("‹", "Предыдущая страница", 23);
        next = action("›", "Следующая страница", 23);
        TextView minus = action("−", "Уменьшить документ", 22);
        TextView plus = action("+", "Увеличить документ", 22);
        TextView fitPage = action("▣", "Показать всю страницу", 18);

        pageIndicator = new TextView(this);
        pageIndicator.setTextColor(DARK);
        pageIndicator.setTextSize(14);
        pageIndicator.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pageIndicator.setGravity(Gravity.CENTER);

        zoomIndicator = new TextView(this);
        zoomIndicator.setTextColor(DARK);
        zoomIndicator.setTextSize(12);
        zoomIndicator.setGravity(Gravity.CENTER);

        bottom.addView(previous, new LinearLayout.LayoutParams(dp(42), dp(43)));
        bottom.addView(pageIndicator, new LinearLayout.LayoutParams(0, dp(43), 1f));
        bottom.addView(next, new LinearLayout.LayoutParams(dp(42), dp(43)));
        bottom.addView(fitPage, new LinearLayout.LayoutParams(dp(36), dp(43)));
        bottom.addView(minus, new LinearLayout.LayoutParams(dp(38), dp(43)));
        bottom.addView(zoomIndicator, new LinearLayout.LayoutParams(dp(49), dp(43)));
        bottom.addView(plus, new LinearLayout.LayoutParams(dp(38), dp(43)));
        root.addView(bottom, new LinearLayout.LayoutParams(-1, dp(51)));

        setContentView(root);

        back.setOnClickListener(v -> finish());
        fitWidth.setOnClickListener(v -> pageCanvas.fitWidth());
        fitPage.setOnClickListener(v -> pageCanvas.fitPage());
        previous.setOnClickListener(v -> nextPage(-1));
        next.setOnClickListener(v -> nextPage(1));
        minus.setOnClickListener(v -> pageCanvas.zoomStep(0.8f));
        plus.setOnClickListener(v -> pageCanvas.zoomStep(1.25f));
        zoomIndicator.setOnClickListener(v -> pageCanvas.fitWidth());
    }

    private void nextPage(int direction) {
        if (pdf == null) return;
        int index = pageIndex + direction;
        if (index < 0 || index >= pdf.getPageCount()) return;
        pageIndex = index;
        loadPage();
    }

    private void loadPage() {
        if (pdf == null || pageCanvas == null || pageCanvas.getWidth() <= 0) return;
        PdfRenderer.Page page = null;
        Bitmap nextBitmap = null;
        try {
            page = pdf.openPage(pageIndex);
            float aspect = page.getHeight() / (float) Math.max(1, page.getWidth());
            // Render a crisp bitmap, while avoiding huge allocations on long PDFs.
            int targetWidth = Math.min(2400,
                    Math.max(1200, Math.round(pageCanvas.getWidth() * 2.6f)));
            int budgetWidth = Math.max(1, (int) Math.sqrt(9000000.0f / Math.max(0.1f, aspect)));
            int bmpWidth = Math.max(1, Math.min(targetWidth, budgetWidth));
            int bmpHeight = Math.max(1, Math.round(bmpWidth * aspect));
            nextBitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
            nextBitmap.eraseColor(Color.WHITE);
            page.render(nextBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

            Bitmap oldBitmap = rendered;
            rendered = nextBitmap;
            pageCanvas.show(rendered, aspect);
            if (oldBitmap != null && !oldBitmap.isRecycled()) oldBitmap.recycle();
            pageIndicator.setText((pageIndex + 1) + " / " + pdf.getPageCount());
            previous.setEnabled(pageIndex > 0);
            previous.setAlpha(pageIndex > 0 ? 1f : .35f);
            next.setEnabled(pageIndex < pdf.getPageCount() - 1);
            next.setAlpha(pageIndex < pdf.getPageCount() - 1 ? 1f : .35f);
        } catch (Exception exception) {
            if (nextBitmap != null && nextBitmap != rendered) nextBitmap.recycle();
            Toast.makeText(this, "Ошибка отображения: " + message(exception), Toast.LENGTH_LONG).show();
        } finally {
            if (page != null) {
                try { page.close(); } catch (Exception ignored) {}
            }
        }
    }

    private float between(float value, float lower, float upper) {
        return Math.max(lower, Math.min(upper, value));
    }

    private final class PageCanvas extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG
                | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final ScaleGestureDetector pinch;
        private final GestureDetector gestures;
        private float aspect = 1.414f;
        private float zoom = 1f; // 1 == page width fits viewport
        private float panX = 0f;
        private float panY = 0f;
        private final float margin = dp(5);
        private float swipeStartX;
        private float swipeStartY;
        private boolean swipeHadMultiplePointers;

        PageCanvas() {
            super(PdfViewerActivity.this);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            pinch = new ScaleGestureDetector(PdfViewerActivity.this,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                            return true;
                        }
                        @Override public boolean onScale(ScaleGestureDetector d) {
                            zoomAround(zoom * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                            return true;
                        }
                    });
            gestures = new GestureDetector(PdfViewerActivity.this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDown(MotionEvent e) { return true; }
                        @Override public boolean onScroll(MotionEvent first, MotionEvent second,
                                                          float distanceX, float distanceY) {
                            if (pinch.isInProgress()) return true;
                            // At fit-width, horizontal gestures turn pages on finger release.
                            // Do not pan the page sideways or interfere with vertical reading.
                            if (zoom <= 1.04f && Math.abs(distanceX) > Math.abs(distanceY)) return true;
                            panX -= distanceX;
                            panY -= distanceY;
                            limitPan();
                            invalidate();
                            return true;
                        }
                        @Override public boolean onDoubleTap(MotionEvent e) {
                            if (zoom > 1.2f) fitWidth();
                            else zoomAround(2f, e.getX(), e.getY());
                            return true;
                        }
                    });
            setBackgroundColor(PAGE_BACKGROUND);
        }

        private float baseWidth() {
            return Math.max(1f, getWidth() - margin * 2);
        }

        private float pageWidth() {
            return baseWidth() * zoom;
        }

        private float pageHeight() {
            return pageWidth() * aspect;
        }

        private float left() {
            return (getWidth() - pageWidth()) / 2f + panX;
        }

        private float top() {
            // The top edge is never cut off on initial load.
            if (pageHeight() <= getHeight() - margin * 2f)
                return (getHeight() - pageHeight()) / 2f + panY;
            return margin + panY;
        }

        void show(Bitmap bitmap, float pageAspect) {
            this.aspect = pageAspect;
            this.zoom = 1f;
            this.panX = 0f;
            this.panY = 0f;
            announceZoom();
            invalidate();
        }

        void fitWidth() {
            zoom = 1f;
            panX = 0f;
            panY = 0f;
            announceZoom();
            invalidate();
        }

        void fitPage() {
            float availableHeight = Math.max(1f, getHeight() - margin * 2f);
            float fitted = availableHeight / Math.max(1f, baseWidth() * aspect);
            zoom = between(Math.min(1f, fitted), MIN_SCALE, MAX_SCALE);
            panX = 0f;
            panY = 0f;
            announceZoom();
            invalidate();
        }

        void zoomStep(float multiplier) {
            zoomAround(zoom * multiplier, getWidth() / 2f, getHeight() / 2f);
        }

        void zoomAround(float requested, float focusX, float focusY) {
            float newZoom = between(requested, MIN_SCALE, MAX_SCALE);
            float oldWidth = pageWidth();
            float oldHeight = pageHeight();
            float xFraction = (focusX - left()) / oldWidth;
            float yFraction = (focusY - top()) / oldHeight;
            zoom = newZoom;
            float desiredLeft = focusX - xFraction * pageWidth();
            float desiredTop = focusY - yFraction * pageHeight();
            panX = desiredLeft - (getWidth() - pageWidth()) / 2f;
            float baselineTop = pageHeight() <= getHeight() - margin * 2f
                    ? (getHeight() - pageHeight()) / 2f : margin;
            panY = desiredTop - baselineTop;
            limitPan();
            announceZoom();
            invalidate();
        }

        void announceZoom() {
            if (zoomIndicator != null) zoomIndicator.setText(Math.round(zoom * 100f) + "%");
        }

        private void limitPan() {
            float width = pageWidth();
            float height = pageHeight();
            if (width <= getWidth() - margin * 2f) {
                panX = 0f;
            } else {
                float baselineLeft = (getWidth() - width) / 2f;
                float min = getWidth() - margin - width;
                float max = margin;
                panX = between(baselineLeft + panX, min, max) - baselineLeft;
            }

            if (height <= getHeight() - margin * 2f) {
                panY = 0f;
            } else {
                panY = between(panY, getHeight() - margin * 2f - height, 0f);
            }
        }

        @Override protected void onSizeChanged(int width, int height, int oldW, int oldH) {
            super.onSizeChanged(width, height, oldW, oldH);
            panX = 0f;
            panY = 0f;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Bitmap b = rendered;
            if (b == null || b.isRecycled()) return;
            // Uniform width/height scaling preserves text, stamps and page geometry.
            RectF destination = new RectF(left(), top(),
                    left() + pageWidth(), top() + pageHeight());
            canvas.drawBitmap(b, null, destination, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                swipeStartX = event.getX();
                swipeStartY = event.getY();
                swipeHadMultiplePointers = false;
            } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                swipeHadMultiplePointers = true;
            }
            if (event.getPointerCount() > 1) swipeHadMultiplePointers = true;

            pinch.onTouchEvent(event);
            if (event.getPointerCount() == 1 && !pinch.isInProgress()
                    && !swipeHadMultiplePointers) {
                gestures.onTouchEvent(event);
            }

            if (action == MotionEvent.ACTION_UP) {
                // Page turning works only when the entire page fits the viewport
                // width. When zoomed in, swiping pans the document instead.
                if (!swipeHadMultiplePointers && zoom <= 1.04f && rendered != null) {
                    float dx = event.getX() - swipeStartX;
                    float dy = event.getY() - swipeStartY;
                    float minSwipe = Math.max(dp(65), getWidth() * 0.18f);
                    if (Math.abs(dx) >= minSwipe && Math.abs(dx) > Math.abs(dy) * 1.35f) {
                        nextPage(dx < 0 ? 1 : -1);
                    }
                }
                performClick();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                swipeHadMultiplePointers = true;
            }
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("pageIndex", pageIndex);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        if (pageCanvas != null) pageCanvas.setVisibility(View.GONE);
        if (rendered != null && !rendered.isRecycled()) rendered.recycle();
        rendered = null;
        releasePdf();
        super.onDestroy();
    }

    private void releasePdf() {
        if (pdf != null) {
            try { pdf.close(); } catch (Exception ignored) {}
            pdf = null;
        }
        if (descriptor != null) {
            try { descriptor.close(); } catch (Exception ignored) {}
            descriptor = null;
        }
    }

    private String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
