package ru.krmonitor.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/**
 * Native PDF viewer. A document fits the screen width by default; image
 * rasterization is intentionally higher-resolution than its display size.
 * Both pan and pinch-to-zoom stay inside SOP Navigator.
 */
public class PdfViewerActivity extends Activity {
    public static final String EXTRA_URI = "pdf_uri";
    public static final String EXTRA_TITLE = "pdf_title";

    private static final int BACKGROUND = Color.rgb(237, 242, 247);
    private static final int INK = Color.rgb(26, 47, 68);
    private static final int BLUE = Color.rgb(15, 89, 160);
    private static final int BORDER = Color.rgb(219, 228, 235);
    private static final float MIN_ZOOM = 1f;
    private static final float MAX_ZOOM = 3f;

    private ParcelFileDescriptor descriptor;
    private PdfRenderer renderer;
    private Bitmap bitmap;
    private ImageView image;
    private TextView pageCounter, previousButton, nextButton, zoomLabel;
    private HorizontalScrollView horizontal;
    private ScrollView vertical;
    private ScaleGestureDetector pinchDetector;
    private GestureDetector tapDetector;
    private float gestureZoom = 1f;
    private int pageIndex = 0;
    private float zoom = 1f;

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            pageIndex = savedInstanceState.getInt("page", 0);
            zoom = clamp(savedInstanceState.getFloat("zoom", 1f));
        }
        String rawUri = getIntent().getStringExtra(EXTRA_URI);
        String documentTitle = getIntent().getStringExtra(EXTRA_TITLE);
        if (rawUri == null || rawUri.isEmpty()) {
            Toast.makeText(this, "Файл PDF не найден", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        try {
            descriptor = getContentResolver().openFileDescriptor(Uri.parse(rawUri), "r");
            if (descriptor == null) throw new FileNotFoundException("Нет доступа к файлу");
            renderer = new PdfRenderer(descriptor);
            if (renderer.getPageCount() == 0) throw new IOException("Документ не содержит страниц");
            pageIndex = Math.max(0, Math.min(renderer.getPageCount() - 1, pageIndex));
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть PDF: " + safeMessage(e), Toast.LENGTH_LONG).show();
            closeResources();
            finish();
            return;
        }
        buildInterface(documentTitle == null || documentTitle.trim().isEmpty() ? "Документ PDF" : documentTitle);
        horizontal.post(() -> renderPage(false, zoom));
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView control(String text, String description) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(19);
        v.setTextColor(BLUE);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setContentDescription(description);
        v.setBackground(rounded(Color.WHITE, 10));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private void buildInterface(String documentTitle) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Color.WHITE);
        header.setPadding(dp(7), dp(4), dp(7), dp(4));

        TextView back = control("‹", "Назад к списку документов");
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        TextView title = new TextView(this);
        title.setText(documentTitle);
        title.setTextColor(INK);
        title.setTextSize(13.5f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        titleLp.setMargins(dp(7), 0, dp(6), 0);
        header.addView(title, titleLp);

        TextView fit = control("⤢", "По ширине экрана");
        fit.setTextSize(23);
        header.addView(fit, new LinearLayout.LayoutParams(dp(42), dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(58)));

        pinchDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                gestureZoom = zoom;
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                gestureZoom = clamp(gestureZoom * detector.getScaleFactor());
                zoomLabel.setText(String.format(Locale.ROOT, "%d%%", Math.round(gestureZoom * 100)));
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector detector) {
                if (Math.abs(gestureZoom - zoom) > 0.025f) {
                    float oldZoom = zoom;
                    zoom = gestureZoom;
                    renderPage(true, oldZoom);
                } else {
                    updateControls();
                }
            }
        });
        tapDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                float oldZoom = zoom;
                zoom = zoom > 1.05f ? MIN_ZOOM : 2f;
                renderPage(zoom != MIN_ZOOM, oldZoom);
                return true;
            }
        });

        horizontal = new HorizontalScrollView(this) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                if (pinchDetector != null) pinchDetector.onTouchEvent(event);
                if (tapDetector != null) tapDetector.onTouchEvent(event);
                // During multi-touch avoid forwarding scaling gestures to scroll views.
                if (event.getPointerCount() > 1 || (pinchDetector != null && pinchDetector.isInProgress())) {
                    return true;
                }
                return super.dispatchTouchEvent(event);
            }
        };
        horizontal.setFillViewport(true);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setBackgroundColor(BACKGROUND);

        vertical = new ScrollView(this);
        vertical.setFillViewport(false);
        vertical.setVerticalScrollBarEnabled(true);
        vertical.setBackgroundColor(BACKGROUND);

        image = new ImageView(this);
        image.setBackgroundColor(Color.WHITE);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setAdjustViewBounds(false);
        vertical.addView(image, new ScrollView.LayoutParams(-2, -2));
        horizontal.addView(vertical, new HorizontalScrollView.LayoutParams(-2, -1));
        root.addView(horizontal, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(Color.WHITE);
        toolbar.setPadding(dp(8), dp(4), dp(8), dp(4));

        previousButton = control("‹", "Предыдущая страница");
        nextButton = control("›", "Следующая страница");
        TextView minus = control("−", "Уменьшить");
        TextView plus = control("+", "Увеличить");
        minus.setTextSize(21);
        plus.setTextSize(21);

        pageCounter = new TextView(this);
        pageCounter.setTextColor(INK);
        pageCounter.setTextSize(14);
        pageCounter.setGravity(Gravity.CENTER);
        pageCounter.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        zoomLabel = new TextView(this);
        zoomLabel.setTextColor(INK);
        zoomLabel.setTextSize(12);
        zoomLabel.setGravity(Gravity.CENTER);
        zoomLabel.setOnClickListener(v -> {
            float old = zoom;
            zoom = MIN_ZOOM;
            renderPage(false, old);
        });

        toolbar.addView(previousButton, new LinearLayout.LayoutParams(dp(40), dp(44)));
        toolbar.addView(pageCounter, new LinearLayout.LayoutParams(0, dp(44), 1f));
        toolbar.addView(nextButton, new LinearLayout.LayoutParams(dp(40), dp(44)));
        View separator = new View(this);
        separator.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams sep = new LinearLayout.LayoutParams(dp(1), dp(26));
        sep.setMargins(dp(6), 0, dp(6), 0);
        toolbar.addView(separator, sep);
        toolbar.addView(minus, new LinearLayout.LayoutParams(dp(36), dp(44)));
        toolbar.addView(zoomLabel, new LinearLayout.LayoutParams(dp(50), dp(44)));
        toolbar.addView(plus, new LinearLayout.LayoutParams(dp(36), dp(44)));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(52)));

        setContentView(root);

        back.setOnClickListener(v -> finish());
        fit.setOnClickListener(v -> {
            float previous = zoom;
            zoom = MIN_ZOOM;
            renderPage(false, previous);
        });
        previousButton.setOnClickListener(v -> changePage(-1));
        nextButton.setOnClickListener(v -> changePage(1));
        minus.setOnClickListener(v -> changeZoom(-0.25f));
        plus.setOnClickListener(v -> changeZoom(0.25f));
    }

    private float clamp(float value) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
    }

    private void changeZoom(float difference) {
        float oldZoom = zoom;
        zoom = clamp(zoom + difference);
        if (Math.abs(oldZoom - zoom) > 0.001f) renderPage(zoom != MIN_ZOOM, oldZoom);
    }

    private void changePage(int direction) {
        if (renderer == null) return;
        int target = pageIndex + direction;
        if (target < 0 || target >= renderer.getPageCount()) return;
        pageIndex = target;
        renderPage(false, zoom);
    }

    private void updateControls() {
        if (renderer == null || pageCounter == null) return;
        pageCounter.setText((pageIndex + 1) + " / " + renderer.getPageCount());
        zoomLabel.setText(Math.round(zoom * 100f) + "%");
        previousButton.setEnabled(pageIndex > 0);
        previousButton.setAlpha(pageIndex > 0 ? 1f : 0.3f);
        nextButton.setEnabled(pageIndex < renderer.getPageCount() - 1);
        nextButton.setAlpha(pageIndex < renderer.getPageCount() - 1 ? 1f : 0.3f);
    }

    /**
     * Render at up to 2x physical display resolution for readable text.
     * Bound bitmap pixels to prevent OOM on very large medical scans.
     */
    private void renderPage(boolean preservePosition, float previousZoom) {
        if (renderer == null || horizontal == null || image == null) return;
        int oldX = horizontal.getScrollX();
        int oldY = vertical.getScrollY();
        int viewportWidth = Math.max(dp(250), horizontal.getWidth());
        int viewportHeight = Math.max(dp(250), horizontal.getHeight());

        PdfRenderer.Page pdfPage = null;
        Bitmap nextBitmap = null;
        try {
            pdfPage = renderer.openPage(pageIndex);
            float aspect = pdfPage.getHeight() / (float) Math.max(1, pdfPage.getWidth());
            int displayWidth = Math.round(Math.max(dp(220), viewportWidth - dp(4)) * zoom);
            int displayHeight = Math.max(1, Math.round(displayWidth * aspect));

            int bitmapWidth = Math.min(3500, Math.round(displayWidth * 2f));
            bitmapWidth = Math.min(bitmapWidth, Math.max(1, (int) Math.sqrt(10000000.0 / Math.max(0.05f, aspect))));
            int bitmapHeight = Math.max(1, Math.round(bitmapWidth * aspect));

            nextBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
            nextBitmap.eraseColor(Color.WHITE);
            pdfPage.render(nextBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

            Bitmap previousBitmap = bitmap;
            bitmap = nextBitmap;
            image.setImageBitmap(bitmap);
            image.setLayoutParams(new ScrollView.LayoutParams(displayWidth, displayHeight));
            if (previousBitmap != null && previousBitmap != bitmap && !previousBitmap.isRecycled()) {
                previousBitmap.recycle();
            }
            updateControls();

            final float scale = previousZoom > 0 ? zoom / previousZoom : 1f;
            final int newX = preservePosition ? Math.round((oldX + viewportWidth / 2f) * scale - viewportWidth / 2f) : 0;
            final int newY = preservePosition ? Math.round((oldY + viewportHeight / 2f) * scale - viewportHeight / 2f) : 0;
            horizontal.post(() -> horizontal.scrollTo(Math.max(0, newX), 0));
            vertical.post(() -> vertical.scrollTo(0, Math.max(0, newY)));
        } catch (Exception e) {
            if (nextBitmap != null && nextBitmap != bitmap && !nextBitmap.isRecycled()) nextBitmap.recycle();
            Toast.makeText(this, "Не удалось показать страницу: " + safeMessage(e), Toast.LENGTH_LONG).show();
        } finally {
            if (pdfPage != null) {
                try { pdfPage.close(); } catch (Exception ignored) {}
            }
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("page", pageIndex);
        out.putFloat("zoom", zoom);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        if (image != null) image.setImageDrawable(null);
        if (bitmap != null) {
            if (!bitmap.isRecycled()) bitmap.recycle();
            bitmap = null;
        }
        closeResources();
        super.onDestroy();
    }

    private void closeResources() {
        if (renderer != null) {
            try { renderer.close(); } catch (Exception ignored) {}
            renderer = null;
        }
        if (descriptor != null) {
            try { descriptor.close(); } catch (Exception ignored) {}
            descriptor = null;
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
