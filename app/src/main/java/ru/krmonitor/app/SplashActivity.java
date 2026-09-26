package ru.krmonitor.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.animation.AccelerateDecelerateInterpolator;

public class SplashActivity extends Activity {
    private static final int BG_TOP = Color.rgb(248, 253, 255);
    private static final int BG_BOTTOM = Color.rgb(230, 247, 255);
    private static final int NAVY = Color.rgb(7, 49, 112);
    private static final int TEXT_SOFT = Color.rgb(103, 126, 161);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ValueAnimator animator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(BG_TOP);
        window.setNavigationBarColor(BG_BOTTOM);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        );

        SplashView splashView = new SplashView(this);
        setContentView(splashView);

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(3200L);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(a -> splashView.setProgress((Float) a.getAnimatedValue()));
        animator.start();

        handler.postDelayed(this::openMain, 3850L);
    }

    private void openMain() {
        if (isFinishing()) return;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (animator != null) animator.cancel();
        super.onDestroy();
    }

    private static class SplashView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Path path = new Path();
        private final float density;
        private final float scaledDensity;
        private float progress;

        SplashView(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            scaledDensity = getResources().getDisplayMetrics().scaledDensity;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setProgress(float value) {
            progress = value;
            invalidate();
        }

        private float dp(float value) {
            return value * density;
        }

        private float sp(float value) {
            return value * scaledDensity;
        }

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        private float stage(float start, float end) {
            float t = clamp((progress - start) / (end - start), 0f, 1f);
            return t * t * (3f - 2f * t);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            final int w = getWidth();
            final int h = getHeight();
            if (w <= 0 || h <= 0) return;

            drawBackground(canvas, w, h);
            drawDecor(canvas, w, h);
            drawBrand(canvas, w, h);
        }

        private void drawBackground(Canvas canvas, int w, int h) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, 0, 0, h, BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            paint.setColor(Color.argb(22, 55, 182, 239));
            canvas.drawCircle(-w * 0.04f, h * 0.02f, w * 0.42f, paint);
            paint.setColor(Color.argb(15, 49, 183, 239));
            canvas.drawCircle(w * 1.03f, h * 0.04f, w * 0.36f, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.1f));
            paint.setColor(Color.argb(105, 255, 255, 255));
            canvas.drawCircle(-w * 0.03f, h * 0.20f, w * 0.46f, paint);
            canvas.drawCircle(w * 0.53f, h * 0.52f, w * 0.63f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawDecor(Canvas canvas, int w, int h) {
            float wave = (float) Math.sin(progress * Math.PI) * dp(4f);

            path.reset();
            path.moveTo(0, h * 0.77f + wave);
            path.cubicTo(w * 0.22f, h * 0.72f + wave, w * 0.43f, h * 0.82f + wave, w * 0.63f, h * 0.78f + wave);
            path.cubicTo(w * 0.79f, h * 0.75f + wave, w * 0.91f, h * 0.68f + wave, w, h * 0.69f + wave);
            path.lineTo(w, h);
            path.lineTo(0, h);
            path.close();
            paint.setColor(Color.argb(58, 64, 191, 240));
            canvas.drawPath(path, paint);

            path.reset();
            path.moveTo(0, h * 0.84f - wave);
            path.cubicTo(w * 0.21f, h * 0.68f - wave, w * 0.43f, h * 0.72f - wave, w * 0.58f, h * 0.79f - wave);
            path.cubicTo(w * 0.76f, h * 0.88f - wave, w * 0.89f, h * 0.86f - wave, w, h * 0.79f - wave);
            path.lineTo(w, h);
            path.lineTo(0, h);
            path.close();
            paint.setColor(Color.argb(54, 38, 175, 233));
            canvas.drawPath(path, paint);

            float plusX = w * 0.73f;
            float plusY = h * 0.10f;
            float plus = Math.min(w, h) * 0.035f;
            paint.setStyle(Paint.Style.FILL);
            RectF v = new RectF(plusX - plus * 0.28f, plusY - plus, plusX + plus * 0.28f, plusY + plus);
            RectF hr = new RectF(plusX - plus, plusY - plus * 0.28f, plusX + plus, plusY + plus * 0.28f);
            paint.setColor(Color.argb(75, 30, 190, 232));
            canvas.drawRoundRect(v, dp(3), dp(3), paint);
            canvas.drawRoundRect(hr, dp(3), dp(3), paint);

            drawDotGrid(canvas, w * 0.89f, h * 0.09f, dp(11), 4, 5);
            drawDotGrid(canvas, w * 0.03f, h * 0.62f, dp(11), 4, 5);
            drawMolecule(canvas, w * 0.88f, h * 0.29f, Math.min(w, h) * 0.068f);
        }

        private void drawDotGrid(Canvas canvas, float x, float y, float gap, int cols, int rows) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(72, 48, 183, 235));
            float r = dp(1.7f);
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < cols; col++) {
                    canvas.drawCircle(x + col * gap, y + row * gap, r, paint);
                }
            }
        }

        private void drawMolecule(Canvas canvas, float cx, float cy, float r) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.7f));
            paint.setColor(Color.argb(58, 32, 183, 229));
            path.reset();
            for (int i = 0; i < 6; i++) {
                double a = Math.toRadians(-30 + i * 60);
                float x = cx + (float) Math.cos(a) * r;
                float y = cy + (float) Math.sin(a) * r;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 6; i++) {
                double a = Math.toRadians(-30 + i * 60);
                canvas.drawCircle(cx + (float) Math.cos(a) * r,
                        cy + (float) Math.sin(a) * r, dp(4f), paint);
            }
        }

        private void drawBrand(Canvas canvas, int w, int h) {
            float widthDp = w / density;
            boolean compact = widthDp < 360f;
            boolean tablet = widthDp >= 600f;

            float iconAlpha = stage(0.02f, 0.30f);
            float titleAlpha = stage(0.25f, 0.49f);
            float subtitleAlpha = stage(0.43f, 0.68f);
            float sloganAlpha = stage(0.69f, 0.95f);

            float iconSize = Math.min(w * (tablet ? 0.30f : 0.43f), h * 0.24f);
            iconSize = Math.min(iconSize, dp(tablet ? 230f : 185f));
            float centerX = w * 0.5f;
            float centerY = h * (compact ? 0.36f : 0.37f);
            float scale = 0.86f + 0.14f * iconAlpha;

            canvas.save();
            canvas.scale(scale, scale, centerX, centerY);
            drawIcon(canvas, centerX, centerY, iconSize, iconAlpha);
            canvas.restore();

            float titleSize = compact ? 31f : (tablet ? 47f : 38f);
            float subtitleSize = compact ? 15f : (tablet ? 21f : 18f);
            float sloganSize = compact ? 14f : (tablet ? 19f : 16f);

            float titleY = centerY + iconSize * 0.74f + dp(14f) * (1f - titleAlpha);
            drawCenteredText(canvas, "КР Навигатор", centerX, titleY, sp(titleSize), NAVY,
                    Typeface.create("sans-serif", Typeface.BOLD), titleAlpha);

            float subtitleY = titleY + sp(titleSize) * 1.10f + dp(7f) * (1f - subtitleAlpha);
            drawCenteredText(canvas, "Клинические рекомендации", centerX, subtitleY, sp(subtitleSize), TEXT_SOFT,
                    Typeface.create("sans-serif", Typeface.NORMAL), subtitleAlpha);

            float sloganY = h * (compact ? 0.91f : 0.90f) + dp(8f) * (1f - sloganAlpha);
            drawCenteredText(canvas, "Актуальные рекомендации в одном месте", centerX, sloganY,
                    sp(sloganSize), Color.rgb(82, 110, 154), Typeface.create("sans-serif", Typeface.NORMAL), sloganAlpha);
        }

        private void drawIcon(Canvas canvas, float cx, float cy, float s, float alpha) {
            if (alpha <= 0f) return;
            int a = Math.round(255f * alpha);

            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(cx, cy, s * 0.80f,
                    Color.argb(Math.round(65f * alpha), 43, 180, 235),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, s * 0.80f, paint);
            paint.setShader(null);

            float docW = s * 0.78f;
            float docH = s * 0.88f;
            float docL = cx - docW * 0.52f;
            float docT = cy - docH * 0.52f;
            float radius = s * 0.085f;

            paint.setShadowLayer(dp(10f), 0, dp(4f), Color.argb(Math.round(45f * alpha), 34, 130, 190));
            paint.setColor(Color.argb(a, 27, 158, 224));
            canvas.drawRoundRect(new RectF(docL - s * 0.12f, docT + s * 0.04f,
                    docL + s * 0.10f, docT + docH - s * 0.04f), radius, radius, paint);
            paint.clearShadowLayer();

            paint.setColor(Color.argb(a, 19, 193, 211));
            float tabH = s * 0.13f;
            for (int i = 0; i < 3; i++) {
                float ty = docT + s * (0.18f + i * 0.20f);
                canvas.drawRoundRect(new RectF(docL - s * 0.08f, ty, docL + s * 0.05f, ty + tabH),
                        s * 0.025f, s * 0.025f, paint);
            }

            paint.setShadowLayer(dp(9f), 0, dp(5f), Color.argb(Math.round(55f * alpha), 30, 110, 180));
            paint.setColor(Color.argb(a, 251, 253, 255));
            canvas.drawRoundRect(new RectF(docL, docT, docL + docW, docT + docH), radius, radius, paint);
            paint.clearShadowLayer();

            paint.setColor(Color.argb(a, 32, 152, 229));
            canvas.drawRoundRect(new RectF(docL + s * 0.12f, docT + s * 0.12f,
                    docL + docW - s * 0.18f, docT + s * 0.17f), s * 0.025f, s * 0.025f, paint);

            paint.setColor(Color.argb(Math.round(185f * alpha), 125, 178, 222));
            float lineX = docL + s * 0.12f;
            float lineY = docT + s * 0.26f;
            float[] widths = {0.47f, 0.28f, 0.23f, 0.18f, 0.20f, 0.30f};
            for (int i = 0; i < widths.length; i++) {
                canvas.drawRoundRect(new RectF(lineX, lineY + i * s * 0.105f,
                        lineX + s * widths[i], lineY + i * s * 0.105f + s * 0.035f),
                        s * 0.017f, s * 0.017f, paint);
            }

            float lensCx = cx + s * 0.20f;
            float lensCy = cy + s * 0.08f;
            float lensR = s * 0.225f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * 0.068f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setShader(new LinearGradient(lensCx - lensR, lensCy - lensR,
                    lensCx + lensR, lensCy + lensR,
                    Color.argb(a, 17, 192, 215), Color.argb(a, 12, 102, 205), Shader.TileMode.CLAMP));
            canvas.drawCircle(lensCx, lensCy, lensR, paint);
            paint.setShader(null);

            float handleX1 = lensCx + lensR * 0.68f;
            float handleY1 = lensCy + lensR * 0.68f;
            float handleX2 = lensCx + lensR * 1.55f;
            float handleY2 = lensCy + lensR * 1.55f;
            paint.setStrokeWidth(s * 0.09f);
            paint.setColor(Color.argb(a, 14, 110, 208));
            canvas.drawLine(handleX1, handleY1, handleX2, handleY2, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStyle(Paint.Style.FILL);

            paint.setColor(Color.argb(Math.round(50f * alpha), 26, 174, 221));
            canvas.drawCircle(lensCx, lensCy, lensR * 0.72f, paint);

            paint.setColor(Color.argb(a, 18, 194, 210));
            float plus = lensR * 0.48f;
            canvas.drawRoundRect(new RectF(lensCx - plus * 0.20f, lensCy - plus,
                    lensCx + plus * 0.20f, lensCy + plus), s * 0.018f, s * 0.018f, paint);
            canvas.drawRoundRect(new RectF(lensCx - plus, lensCy - plus * 0.20f,
                    lensCx + plus, lensCy + plus * 0.20f), s * 0.018f, s * 0.018f, paint);
        }

        private void drawCenteredText(Canvas canvas, String text, float x, float baselineY,
                                      float sizePx, int color, Typeface typeface, float alpha) {
            if (alpha <= 0f) return;
            textPaint.setShader(null);
            textPaint.setTypeface(typeface);
            textPaint.setTextSize(sizePx);
            textPaint.setColor(color);
            textPaint.setAlpha(Math.round(255f * alpha));
            float width = textPaint.measureText(text);
            canvas.drawText(text, x - width / 2f, baselineY, textPaint);
        }
    }
}
