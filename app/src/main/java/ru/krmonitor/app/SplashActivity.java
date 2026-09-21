package ru.krmonitor.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

public class SplashActivity extends Activity {
    private static final long HOLD_MS = 650L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean opened = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int bg = Color.rgb(238, 249, 253);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        );

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);

        ImageView splash = new ImageView(this);
        splash.setImageResource(ru.krmonitor.app.R.drawable.sop_splash);
        splash.setScaleType(ImageView.ScaleType.FIT_CENTER);
        splash.setAdjustViewBounds(false);

        // Initial state: invisible and slightly smaller.
        splash.setAlpha(0f);
        splash.setScaleX(0.965f);
        splash.setScaleY(0.965f);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        );
        root.addView(splash, lp);
        setContentView(root);

        // Calm medical-style entrance: fade-in + gentle scale.
        splash.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(650L)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> handler.postDelayed(() -> {
                    // Smooth disappearance before entering the application.
                    splash.animate()
                            .alpha(0f)
                            .scaleX(1.012f)
                            .scaleY(1.012f)
                            .setDuration(350L)
                            .setInterpolator(new DecelerateInterpolator())
                            .withEndAction(this::openMain)
                            .start();
                }, HOLD_MS))
                .start();
    }

    private void openMain() {
        if (opened || isFinishing()) return;
        opened = true;
        startActivity(new Intent(this, SopMainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
