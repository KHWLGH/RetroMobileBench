package com.retromobilebench;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Minimal Holo control surface for the local benchmark backend, API 16+. */
public class MainActivity extends Activity implements BenchmarkRunner.Listener {
    private BenchmarkRunner runner;
    private TextView status;
    private TextView result;
    private Button cpuSingle;
    private Button cpuFull;
    private Button licenses;
    private Button single;
    private Button full;
    private Button cancel;
    private Button history;
    private Button probe;
    private Button copy;
    private String exportJson = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        runner = BenchmarkRunner.get(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12,
                getResources().getDisplayMetrics());
        column.setPadding(pad, pad, pad, pad);
        TextView heading = new TextView(this);
        heading.setText(getString(R.string.device_heading, BuildConfig.VERSION_NAME,
                Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        column.addView(heading);
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        column.addView(status);
        cpuSingle = button(column, R.string.cpu_single, () -> runner.startCpu(false));
        cpuFull = button(column, R.string.cpu_full, () -> runner.startCpu(true));
        single = button(column, R.string.storage_single, () -> runner.startStorage(false));
        full = button(column, R.string.storage_full, () -> runner.startStorage(true));
        cancel = button(column, R.string.cancel_run, () -> runner.cancel());
        history = button(column, R.string.history, () -> runner.history());
        probe = button(column, R.string.probe, () -> runner.probe());
        copy = button(column, R.string.copy_json, () -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("RetroMobileBench", exportJson));
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
            }
        });
        licenses = button(column, R.string.licenses, () -> runner.licenses());
        result = new TextView(this);
        result.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        result.setTextIsSelectable(true);
        column.addView(result);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);
    }

    private Button button(LinearLayout column, int label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        column.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return button;
    }

    @Override
    protected void onStart() {
        super.onStart();
        runner.attach(this);
    }

    @Override
    protected void onStop() {
        runner.detach(this);
        // Foreground benchmarks only. Rotation recreates the UI and reattaches to
        // the same worker; leaving the app cooperatively cancels the active suite.
        if (!isChangingConfigurations() && runner.isBusy()) runner.cancel();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onStop();
    }

    @Override
    public void onState(BenchmarkRunner.State state) {
        status.setText(state.message);
        result.setText(state.text);
        exportJson = state.exportJson;
        cpuSingle.setEnabled(!state.busy);
        cpuFull.setEnabled(!state.busy);
        licenses.setEnabled(!state.busy);
        single.setEnabled(!state.busy);
        full.setEnabled(!state.busy);
        history.setEnabled(!state.busy);
        probe.setEnabled(!state.busy);
        cancel.setEnabled(state.busy);
        copy.setEnabled(!state.busy && !exportJson.isEmpty());
        if (state.busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
