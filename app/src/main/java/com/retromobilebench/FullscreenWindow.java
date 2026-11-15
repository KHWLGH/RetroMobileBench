package com.retromobilebench;

import android.app.Activity;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;

/** Full-size rendering with controls overlaid, never subtracted from the viewport. */
final class FullscreenWindow {
    static void configure(Activity activity) {
        activity.requestWindowFeature(Window.FEATURE_NO_TITLE);
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive(activity);
    }
    static void immersive(Activity activity) {
        if (Build.VERSION.SDK_INT >= 19) Api19.hideBars(activity.getWindow().getDecorView());
    }
    static void cancel(Activity activity, FrameLayout frame, Runnable action) {
        Button cancel = new Button(activity); cancel.setText("取消测试");
        cancel.setOnClickListener(view -> action.run());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        frame.addView(cancel, params);
    }
    @android.annotation.TargetApi(19) private static final class Api19 {
        static void hideBars(View view) {
            view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }
    private FullscreenWindow() { }
}
