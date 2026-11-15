package com.retromobilebench;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.*;
import android.widget.FrameLayout;
import org.json.JSONObject;

/** Trusted bundled page only; prompt IPC works on API 8 without a JavaScript interface. */
public final class WebBenchmarkActivity extends Activity {
    private static final String URL = "file:///android_asset/web/index.html";
    private WebService.Work request;
    private WebView web;
    private boolean started, ending;
    private int sequence, viewportWidth, viewportHeight;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); request = WebService.current;
        if (request == null || request.terminal || state != null) { finish(); return; }
        FullscreenWindow.configure(this);
        FrameLayout frame = new FrameLayout(this);
        web = new WebView(this); web.setPadding(0, 0, 0, 0); web.setVerticalScrollBarEnabled(false); web.setHorizontalScrollBarEnabled(false);
        frame.addView(web, new FrameLayout.LayoutParams(-1, -1));
        FullscreenWindow.cancel(this, frame, () -> end("cancelled", "user_cancel"));
        setContentView(frame);
        WebSettings settings = web.getSettings(); settings.setJavaScriptEnabled(true); settings.setSupportZoom(false);
        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL); settings.setMinimumFontSize(1); settings.setMinimumLogicalFontSize(1);
        settings.setBuiltInZoomControls(false); settings.setUseWideViewPort(true); settings.setLoadWithOverviewMode(false);
        settings.setDefaultFontSize(12); settings.setDefaultFixedFontSize(12); settings.setTextSize(WebSettings.TextSize.NORMAL);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE); settings.setBlockNetworkLoads(true);
        if (Build.VERSION.SDK_INT >= 14) Api14.configure(settings);
        if (Build.VERSION.SDK_INT >= 16) Api16.configure(settings);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return !url.startsWith("javascript:"); }
            @Override public void onPageFinished(WebView view, String url) { if (BuildConfig.DEBUG) android.util.Log.i("RMB-WEB", "loaded (not a paint metric): " + url); }
            @Override public void onReceivedError(WebView view, int code, String description, String url) { end("error", "web_load:" + code + ":" + description); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                result.confirm("");
                // KitKat Chromium reports the file security origin rather than the document URL.
                if (ending || !(URL.equals(url) || "file:///".equals(url)) || !URL.equals(view.getUrl())) return true;
                try {
                    if ("RMB_WEB_READY".equals(message) && !started) {
                        started = true;
                        web.post(() -> startWhenReady(frame)); return true;
                    }
                    if (!"RMB_WEB_V2".equals(message) || defaultValue == null || defaultValue.length() > 40000) return true;
                    JSONObject event = new JSONObject(defaultValue);
                    if (!request.nonce.equals(event.optString("nonce")) || event.optInt("sequence") != ++sequence) { end("error", "stale_or_out_of_order_js"); return true; }
                    if (web.getWidth() != viewportWidth || web.getHeight() != viewportHeight) { end("interrupted", "fullscreen_viewport_changed"); return true; }
                    String kind = event.getString("kind");
                    if ("prepared".equals(kind)) {
                        event.put("bridge_origin", url).put("physical_width", web.getWidth()).put("physical_height", web.getHeight())
                                .put("window_mode", "fullscreen").put("window_width", frame.getWidth()).put("window_height", frame.getHeight())
                                .put("density", getResources().getDisplayMetrics().density).put("initial_scale_percent", 0)
                                .put("webview_scale", web.getScale()).put("css_to_physical_x", web.getWidth() / (double)event.getInt("viewport_width"))
                                .put("css_to_physical_y", web.getHeight() / (double)event.getInt("viewport_height"))
                                .put("layout_algorithm", "NORMAL")
                                .put("hardware_accelerated", Build.VERSION.SDK_INT >= 11 && Api11.accelerated(web))
                                .put("provider", Build.VERSION.SDK_INT >= 26 ? Api26.provider() : Build.VERSION.SDK_INT >= 21 ? "provider_api_unavailable; see UA and OS" : "bundled-with-os:" + Build.FINGERPRINT)
                                .put("os_fingerprint", Build.FINGERPRINT).put("text_zoom_percent", 100);
                    }
                    request.send(event);
                    if ("done".equals(kind) || "error".equals(kind)) closeWindow();
                } catch (Exception error) { end("error", error.toString()); }
                return true;
            }
            @Override public boolean onConsoleMessage(ConsoleMessage message) { if (BuildConfig.DEBUG) android.util.Log.i("RMB-WEB", "console " + message.message() + " line " + message.lineNumber()); return true; }
            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) { result.cancel(); end("error", "unexpected_alert"); return true; }
        });
        web.loadUrl(URL);
    }
    private void startWhenReady(FrameLayout frame) {
        if (ending || web == null) return;
        int width = web.getWidth(), height = web.getHeight();
        if (!hasWindowFocus() || width <= height || height <= 0 || width != frame.getWidth() || height != frame.getHeight()) {
            web.postDelayed(() -> startWhenReady(frame), 50); return;
        }
        web.postDelayed(() -> {
            if (ending || web == null) return;
            if (!hasWindowFocus() || web.getWidth() != width || web.getHeight() != height) { startWhenReady(frame); return; }
            try {
                viewportWidth = width; viewportHeight = height;
                JSONObject config = new JSONObject().put("nonce", request.nonce).put("fault", request.fault);
                web.loadUrl("javascript:RMB.start(" + config + ")");
            } catch (Exception error) { end("error", error.toString()); }
        }, 150);
    }
    private void end(String kind, String reason) {
        if (ending) return;
        try { if (request != null) request.send(new JSONObject().put("kind", kind).put("reason", reason)); } catch (Exception ignored) { }
        closeWindow();
    }
    private void closeWindow() { ending = true; finish(); }
    @Override public void onBackPressed() { end("cancelled", "back"); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) FullscreenWindow.immersive(this); }
    @Override protected void onStop() { if (!ending) end("interrupted", "worker_window_background"); super.onStop(); }
    @Override protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        super.onDestroy();
    }
    @android.annotation.TargetApi(11) private static final class Api11 { static boolean accelerated(View view) { return view.isHardwareAccelerated(); } }
    @android.annotation.TargetApi(14) private static final class Api14 { static void configure(WebSettings settings) { settings.setTextZoom(100); } }
    @android.annotation.TargetApi(16) private static final class Api16 {
        static void configure(WebSettings settings) { settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false); }
    }
    @android.annotation.TargetApi(26) private static final class Api26 {
        static String provider() { android.content.pm.PackageInfo info = WebView.getCurrentWebViewPackage(); return info == null ? "unknown" : info.packageName + ":" + info.versionName + ":" + info.versionCode; }
    }
}
