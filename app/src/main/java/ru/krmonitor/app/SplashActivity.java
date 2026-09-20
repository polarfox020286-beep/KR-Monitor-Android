package ru.krmonitor.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SplashActivity extends Activity {
    private static final int BG_TOP = Color.rgb(247, 252, 255);
    private static final int BG_BOTTOM = Color.rgb(226, 245, 255);
    private static final int BRAND_BLUE = Color.rgb(24, 164, 221);
    private static final int NAME_GRAY = Color.rgb(99, 99, 99);

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(BG_TOP);
        window.setNavigationBarColor(BG_BOTTOM);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BG_TOP, BG_BOTTOM}
        ));

        BackgroundArtView art = new BackgroundArtView(this);
        root.addView(art, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), dp(12), dp(24), dp(12));

        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        root.addView(content, contentLp);

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.hospital_e);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(dp(154), dp(224));
        markLp.bottomMargin = dp(12);
        content.addView(mark, markLp);

        TextView hospitalName = new TextView(this);
        hospitalName.setText("Елизаветинская\nбольница");
        hospitalName.setTextColor(NAME_GRAY);
        hospitalName.setTextSize(34);
        hospitalName.setGravity(Gravity.CENTER);
        hospitalName.setIncludeFontPadding(false);
        hospitalName.setLineSpacing(dp(2), 1.0f);
        hospitalName.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        content.addView(hospitalName, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout sloganHost = new FrameLayout(this);
        LinearLayout.LayoutParams sloganLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(58)
        );
        sloganLp.topMargin = dp(12);
        content.addView(sloganHost, sloganLp);

        TextView slogan = new TextView(this);
        slogan.setText("Эксперты здоровья");
        slogan.setTextColor(BRAND_BLUE);
        slogan.setTextSize(24);
        slogan.setGravity(Gravity.CENTER);
        slogan.setIncludeFontPadding(false);
        slogan.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        slogan.setShadowLayer(dp(4), 0, 0, Color.argb(70, 60, 190, 235));
        FrameLayout.LayoutParams sloganTextLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        );
        sloganHost.addView(slogan, sloganTextLp);

        View shimmer = new View(this);
        GradientDrawable shimmerBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0xD9FFFFFF, 0x00FFFFFF}
        );
        shimmer.setBackground(shimmerBg);
        FrameLayout.LayoutParams shimmerLp = new FrameLayout.LayoutParams(dp(46), dp(46));
        shimmerLp.gravity = Gravity.CENTER_VERTICAL | Gravity.LEFT;
        sloganHost.addView(shimmer, shimmerLp);

        setContentView(root);

        mark.setAlpha(0f);
        mark.setScaleX(0.88f);
        mark.setScaleY(0.88f);

        hospitalName.setAlpha(0f);
        hospitalName.setTranslationY(dp(12));

        sloganHost.setAlpha(0f);
        sloganHost.setScaleX(0.02f);
        shimmer.setAlpha(0f);

        root.post(() -> {
            sloganHost.setPivotX(0f);

            mark.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(250)
                    .setDuration(800)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            hospitalName.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(1050)
                    .setDuration(850)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            sloganHost.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .setStartDelay(1950)
                    .setDuration(950)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .start();

            shimmer.setTranslationX(-dp(48));
            shimmer.animate()
                    .alpha(0.92f)
                    .translationX(Math.max(dp(230), slogan.getWidth() + dp(28)))
                    .setStartDelay(2050)
                    .setDuration(900)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> shimmer.animate().alpha(0f).setDuration(180).start())
                    .start();
        });

        handler.postDelayed(this::openMain, 3850);
    }

    private void openMain() {
        if (isFinishing()) return;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static class BackgroundArtView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BackgroundArtView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;

            paint.setStrokeWidth(28f * d);
            paint.setColor(Color.argb(25, 80, 190, 235));
            canvas.drawCircle(-50f * d, -30f * d, 180f * d, paint);

            paint.setStrokeWidth(38f * d);
            paint.setColor(Color.argb(20, 80, 190, 235));
            canvas.drawCircle(getWidth() + 70f * d, getHeight() + 35f * d, 210f * d, paint);

            paint.setStrokeWidth(2f * d);
            paint.setColor(Color.argb(15, 255, 255, 255));
            canvas.drawCircle(getWidth() * 0.5f, getHeight() * 0.45f, 150f * d, paint);
        }
    }
}
