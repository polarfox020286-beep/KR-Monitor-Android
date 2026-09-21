package ru.krmonitor.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class SplashActivity extends Activity {
    private static final long SPLASH_MS = 3000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
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

        webView = new WebView(this);
        webView.setBackgroundColor(bg);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient());
        setContentView(webView);

        String html =
                "<!doctype html><html><head>" +
                "<meta name='viewport' content='width=device-width,height=device-height,initial-scale=1,maximum-scale=1,user-scalable=no'>" +
                "<style>" +
                "html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#EEF9FD;}" +
                "body{display:flex;align-items:center;justify-content:center;}" +
                "img{display:block;width:100%;height:100%;object-fit:contain;}" +
                "</style></head><body>" +
                "<img src='sop_splash.webp' alt='СОП Навигатор'>" +
                "</body></html>";

        webView.loadDataWithBaseURL(
                "file:///android_asset/",
                html,
                "text/html",
                "UTF-8",
                null
        );

        handler.postDelayed(this::openMain, SPLASH_MS);
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
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
