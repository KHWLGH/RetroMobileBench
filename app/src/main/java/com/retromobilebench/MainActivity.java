package com.retromobilebench;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * M0 feasibility screen: loads the native library, runs the probe on a background
 * thread and prints the report. Also logs it under the "RMB" tag so it can be read
 * with `adb logcat -s RMB` on devices without a usable screen.
 */
public class MainActivity extends Activity {

    static final String TAG = "RMB";

    private TextView mText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mText = new TextView(this);
        mText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mText.setTypeface(android.graphics.Typeface.MONOSPACE);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        mText.setPadding(pad, pad, pad, pad);
        mText.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(mText);
        setContentView(scroll);

        mText.setText(header() + "running probe...\n");
        new ProbeTask().execute();
    }

    @SuppressWarnings("deprecation") // CPU_ABI / CPU_ABI2 are the only ABI fields on API 16.
    private static String header() {
        return "RetroMobileBench " + BuildConfig.VERSION_NAME + " (M0 probe)\n"
                + "device: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")\n"
                + "android: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + "\n"
                + "abi: " + Build.CPU_ABI + " " + Build.CPU_ABI2 + "\n"
                + "vm: " + System.getProperty("java.vm.version") + "\n"
                + "fingerprint: " + Build.FINGERPRINT + "\n\n";
    }

    // AsyncTask is the simplest background helper available on API 16 without AndroidX.
    @SuppressWarnings("deprecation")
    private final class ProbeTask extends AsyncTask<Void, Void, String> {
        @Override
        protected String doInBackground(Void... params) {
            StringBuilder sb = new StringBuilder();
            String lib = NativeLoader.load();
            sb.append("native lib chosen: ").append(NativeLoader.chooseLibrary())
                    .append(", loaded: ").append(lib).append('\n');
            Throwable err = NativeLoader.error();
            if (err != null) {
                sb.append("native load problem: ").append(err).append('\n');
            }
            if (lib != null) {
                long t0 = System.nanoTime();
                sb.append(NativeBridge.probeReport());
                sb.append(String.format(java.util.Locale.US, "probe total: %.2f s%n",
                        (System.nanoTime() - t0) / 1e9));
            }
            return sb.toString();
        }

        @Override
        protected void onPostExecute(String report) {
            String full = header() + report;
            mText.setText(full);
            for (String line : full.split("\n")) {
                Log.i(TAG, line);
            }
            Log.i(TAG, "PROBE_DONE");
        }
    }
}
