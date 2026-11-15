package com.retromobilebench;

import android.app.Activity;
import android.text.ClipboardManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Portrait controls and results; visible benchmark output uses a separate window. */
public class MainActivity extends Activity implements BenchmarkRunner.Listener {
    private BenchmarkRunner runner;
    private TextView status, result;
    private Button start, cancel, history, probe, licenses, copy;
    private String exportJson = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        runner = BenchmarkRunner.get(this);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        TextView heading = new TextView(this);
        heading.setText(getString(R.string.device_heading, BuildConfig.VERSION_NAME, Build.MANUFACTURER,
                Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT));
        column.addView(heading);
        status = new TextView(this); status.setTextSize(16); column.addView(status);
        LinearLayout primary = new LinearLayout(this); column.addView(primary);
        start = button(primary, "开始测试", () -> runner.startAll());
        cancel = button(primary, getString(R.string.cancel_run), () -> runner.cancel());
        LinearLayout auxiliary = new LinearLayout(this); column.addView(auxiliary);
        history = button(auxiliary, "历史", () -> runner.history());
        probe = button(auxiliary, "设备", () -> runner.probe());
        licenses = button(auxiliary, "许可", () -> runner.licenses());
        copy = button(auxiliary, "导出 JSON", () -> {
            ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) { clipboard.setText(exportJson); Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show(); }
        });
        result = new TextView(this); result.setTextSize(14); PlatformCompat.selectable(result);
        ScrollView scroll = new ScrollView(this); scroll.addView(result); column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(column);
        DisplaySurface.refreshHz = getWindowManager().getDefaultDisplay().getRefreshRate();
    }
    private Button button(LinearLayout row, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run());
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1)); return b;
    }
    @Override protected void onStart() {
        super.onStart();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        runner.attach(this);
    }
    @Override protected void onStop() {
        runner.detach(this);
        boolean webHandoff = WebClient.consumeHandoff(), displayHandoff = DisplaySurface.consumeHandoff();
        if (!PlatformCompat.changingConfigurations(this) && runner.isBusy() && !MediaClient.externalPlaybackActive()
                && !GpuClient.externalDisplayActive() && !webHandoff && !displayHandoff) runner.interrupt();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onStop();
    }
    @Override public void onState(BenchmarkRunner.State state) {
        status.setText(state.message); result.setText(state.text); exportJson = state.exportJson;
        start.setEnabled(!state.busy); history.setEnabled(!state.busy); probe.setEnabled(!state.busy); licenses.setEnabled(!state.busy);
        cancel.setEnabled(state.busy); copy.setEnabled(!state.busy && exportJson.length() != 0);
    }
}
