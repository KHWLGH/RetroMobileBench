package com.retromobilebench;

import android.annotation.TargetApi;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies zero orphan/truncated units across 320dp+ viewports and virtualized UI performance
 * for the license reader, raw report viewer, and full 12-stage report detail page.
 */
final class UiLayoutAndPerfChecks {
    private static final List<String> UNITS = Arrays.asList(
            "iterations/s", "iter/s", "MTexels/s", "MFLOPS", "MB/s", "IOPS",
            "FPS", "TPS", "ms", "ns", "MiB", "GiB", "MHz", "°C", "px", "dp", "dpi", "Hz",
            "frames", "次", "核", "线程", "秒"
    );

    static String run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        MainActivity activity = (MainActivity) test.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.waitForIdleSync();

        JSONObject session = buildRealisticSession();
        int widthPx = Math.round(320 * context.getResources().getDisplayMetrics().density);
        int heightPx = Math.round(533 * context.getResources().getDisplayMetrics().density);
        int wSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY);
        int hSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.AT_MOST);

        MiuiSubPages.Navigator noopNav = new MiuiSubPages.Navigator() {
            @Override public void openSubPage(String title, View content) { }
            @Override public void copyText(String label, String content) { }
            @Override public void deleteRun(JSONObject report) { }
        };

        final AtomicReference<String> summary = new AtomicReference<>("");
        final AtomicReference<Throwable> error = new AtomicReference<>();

        test.runOnMainSync(() -> {
            try {
                // 1. Deferred JSON/raw serialization timing on full 12-stage session
                long t0 = SystemClock.uptimeMillis();
                View reportPage = MiuiSubPages.buildReportDetailPage(activity, session, false, noopNav);
                long reportBuildMs = SystemClock.uptimeMillis() - t0;
                check(reportBuildMs < 150, "buildReportDetailPage must build fast (took " + reportBuildMs + "ms)");

                layoutAtSize(reportPage, wSpec, hSpec);
                int checkedTexts = verifyNoOrphanUnitsOrValueEllipsis(reportPage, "reportDetail");
                int flatChecks = verifyFlatCardsAndNoRedundantRootBg(activity, reportPage);

                // 2. Check all 12 stage detail pages at narrow 320dp width
                JSONArray results = session.getJSONArray("results");
                for (int i = 0; i < results.length(); i++) {
                    JSONObject st = results.getJSONObject(i);
                    BenchmarkSuite suite = BenchmarkSuite.DEFAULT[i];
                    View stagePage = MiuiSubPages.buildStageDetailPage(activity, suite, st, noopNav, true);
                    layoutAtSize(stagePage, wSpec, hSpec);
                    checkedTexts += verifyNoOrphanUnitsOrValueEllipsis(stagePage, "stage-" + suite.name());
                }

                // 3. Check Activity root (Run tab + lazy-built tabs)
                View decor = activity.getWindow().getDecorView();
                layoutAtSize(decor, wSpec, hSpec);
                checkedTexts += verifyNoOrphanUnitsOrValueEllipsis(decor, "mainActivity");

                // 4. License reader virtualization + build timing
                long l0 = SystemClock.uptimeMillis();
                View licensePage = MiuiSubPages.buildLicenseReaderPage(activity, noopNav);
                long licenseBuildMs = SystemClock.uptimeMillis() - l0;
                check(licenseBuildMs < 150, "buildLicenseReaderPage must build immediately (took " + licenseBuildMs + "ms)");
                ListView lv = findListView(licensePage);
                check(lv != null, "license reader must use virtualized ListView");

                summary.set("reportBuildMs=" + reportBuildMs + ", licenseBuildMs=" + licenseBuildMs
                        + ", checkedTextViews=" + checkedTexts + ", flatCards=" + flatChecks);
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() != null) throw new AssertionError(error.get());

        // 5. Exercise all 3 license tabs asynchronously and verify chunking & StaticLayout (non-selectable)
        final View[] licenseHolder = new View[1];
        test.runOnMainSync(() -> licenseHolder[0] = MiuiSubPages.buildLicenseReaderPage(activity, noopNav));
        ListView lv = findListView(licenseHolder[0]);
        List<Button> tabButtons = findButtons(licenseHolder[0]);
        check(tabButtons.size() >= 3, "license reader has 3 tab buttons");

        StringBuilder tabStats = new StringBuilder();
        for (int t = 0; t < 3; t++) {
            final int tabIdx = t;
            test.runOnMainSync(() -> tabButtons.get(tabIdx).performClick());
            long deadline = SystemClock.uptimeMillis() + 5000;
            int count = 0;
            while (SystemClock.uptimeMillis() < deadline) {
                final int[] c = new int[1];
                final String[] first = new String[1];
                test.runOnMainSync(() -> {
                    c[0] = lv.getAdapter().getCount();
                    first[0] = c[0] > 0 ? String.valueOf(lv.getAdapter().getItem(0)) : "";
                });
                if (c[0] > 1 || (c[0] == 1 && !first[0].startsWith("正在"))) {
                    count = c[0];
                    break;
                }
                SystemClock.sleep(25);
            }
            check(count > 0, "license tab " + t + " finished loading");
            if (t == 1) check(count >= 15, "benchmark-notices.txt must be virtualized into >=15 chunks, got " + count);

            final int finalCount = count;
            test.runOnMainSync(() -> {
                layoutAtSize(licenseHolder[0], wSpec, View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY));
                for (int i = 0; i < Math.min(3, finalCount); i++) {
                    View itemView = lv.getAdapter().getView(i, null, lv);
                    check(itemView instanceof TextView, "chunk item is TextView");
                    TextView tv = (TextView) itemView;
                    if (Build.VERSION.SDK_INT >= 11) {
                        check(!Api11.isSelectable(tv), "chunk TextView must NOT use expensive selectable DynamicLayout");
                    }
                    tv.measure(View.MeasureSpec.makeMeasureSpec(widthPx - 40, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    tv.layout(0, 0, tv.getMeasuredWidth(), tv.getMeasuredHeight());
                    Layout layout = tv.getLayout();
                    check(layout != null && layout.getLineCount() <= 45,
                            "chunk lines bounded to keep GPU texture tiny, got " + (layout == null ? -1 : layout.getLineCount()));
                }
            });
            tabStats.append(t == 0 ? "" : ",").append(count);
        }

        // 6. Verify Raw Report page virtualization
        final View[] rawHolder = new View[1];
        test.runOnMainSync(() -> rawHolder[0] = MiuiSubPages.buildRawReportPage(activity, session, noopNav));
        ListView rawLv = findListView(rawHolder[0]);
        check(rawLv != null, "raw report page must use virtualized ListView");
        long rawDeadline = SystemClock.uptimeMillis() + 5000;
        int rawCount = 0;
        while (SystemClock.uptimeMillis() < rawDeadline) {
            final int[] c = new int[1];
            final String[] first = new String[1];
            test.runOnMainSync(() -> {
                c[0] = rawLv.getAdapter().getCount();
                first[0] = c[0] > 0 ? String.valueOf(rawLv.getAdapter().getItem(0)) : "";
            });
            if (c[0] > 1 || (c[0] == 1 && !first[0].startsWith("正在"))) {
                rawCount = c[0];
                break;
            }
            SystemClock.sleep(25);
        }
        check(rawCount >= 5, "raw report virtualized into chunks, got " + rawCount);

        return summary.get() + ", licenseChunks=[" + tabStats + "], rawChunks=" + rawCount;
    }

    private static void layoutAtSize(View view, int wSpec, int hSpec) {
        view.measure(wSpec, hSpec);
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private static int verifyNoOrphanUnitsOrValueEllipsis(View root, String tag) {
        int count = 0;
        ArrayList<TextView> tvs = new ArrayList<>();
        collectTextViews(root, tvs);
        for (TextView tv : tvs) {
            if (tv.getVisibility() != View.VISIBLE) continue;
            Layout layout = tv.getLayout();
            if (layout == null || layout.getLineCount() == 0) continue;
            CharSequence text = tv.getText();
            if (text == null || text.length() == 0) continue;
            count++;

            String full = text.toString();
            int lines = layout.getLineCount();
            for (int line = 0; line < lines; line++) {
                // Check for value truncation (ellipsis) on numeric/unit values
                if (layout.getEllipsisCount(line) > 0 && containsUnit(full)) {
                    // Only allow ellipsis on long single-line labels/titles, never on right-aligned metric values
                    if ((tv.getGravity() & android.view.Gravity.RIGHT) == android.view.Gravity.RIGHT
                            || full.contains("\u00A0")) {
                        // Metric bar left titles may have MiB in "(8MiB读+8MiB写)" - ignore left labels with parens
                        if (!full.contains("复制带宽") && !full.contains("填充带宽") && !full.contains("延迟")
                                && !full.contains("顺序") && !full.contains("随机") && !full.contains("综合平均")) {
                            throw new AssertionError(tag + " truncated unit text: [" + full + "]");
                        }
                    }
                }

                String lineStr = full.substring(layout.getLineStart(line), layout.getLineEnd(line)).trim();
                for (String unit : UNITS) {
                    if (lineStr.equals(unit) || lineStr.startsWith(unit + " ") || lineStr.startsWith(unit + ")")
                            || lineStr.startsWith(unit + " ·")) {
                        throw new AssertionError(tag + " orphan unit at start of line " + line + ": '" + lineStr + "' in [" + full + "]");
                    }
                }
                if (line > 0) {
                    String prevLine = full.substring(layout.getLineStart(line - 1), layout.getLineEnd(line - 1)).trim();
                    if (prevLine.endsWith("iter/") || prevLine.endsWith("iterations/") || prevLine.endsWith("MB/")
                            || prevLine.endsWith("MTexels/")) {
                        throw new AssertionError(tag + " split slash unit across lines: '" + prevLine + "' / '" + lineStr + "' in [" + full + "]");
                    }
                }
            }
        }
        return count;
    }

    private static int verifyFlatCardsAndNoRedundantRootBg(MainActivity activity, View reportPage) {
        ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
        if (content != null && content.getChildCount() > 0) {
            View overlayRoot = content.getChildAt(0);
            check(overlayRoot.getBackground() == null, "overlayRoot must not draw redundant BG_PAGE");
            if (overlayRoot instanceof ViewGroup && ((ViewGroup) overlayRoot).getChildCount() > 0) {
                View root = ((ViewGroup) overlayRoot).getChildAt(0);
                check(root.getBackground() == null, "root LinearLayout must not draw redundant BG_PAGE");
                if (root instanceof ViewGroup && ((ViewGroup) root).getChildCount() > 2) {
                    View centerFrame = ((ViewGroup) root).getChildAt(2);
                    check(centerFrame.getBackground() == null, "centerFrame must not draw redundant BG_PAGE");
                }
            }
        }
        int[] counts = new int[2]; // [cards, rows]
        checkFlatCardsRecursive(reportPage, counts);
        check(counts[0] >= 5, "expected >=5 CardLayout groups in reportPage, got " + counts[0]);
        check(counts[1] >= 12, "expected >=12 flat MiuiRowLayout rows in reportPage, got " + counts[1]);
        return counts[0];
    }

    private static void checkFlatCardsRecursive(View v, int[] counts) {
        if (v instanceof MiuiUi.CardLayout) {
            counts[0]++;
            ViewGroup card = (ViewGroup) v;
            for (int i = 0; i < card.getChildCount(); i++) {
                View child = card.getChildAt(i);
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                if (child.getClass() == View.class && lp != null && lp.height == 1) {
                    throw new AssertionError("CardLayout must draw dividers inline, found 1px divider View");
                }
            }
        }
        if (v instanceof MiuiUi.MiuiRowLayout) {
            counts[1]++;
            ViewGroup row = (ViewGroup) v;
            check(row.getChildCount() <= 4, "MiuiRowLayout must stay flat (<=4 children), got " + row.getChildCount());
            for (int i = 0; i < row.getChildCount(); i++) {
                check(!(row.getChildAt(i) instanceof ViewGroup), "MiuiRowLayout must not nest ViewGroups");
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                checkFlatCardsRecursive(vg.getChildAt(i), counts);
            }
        }
    }

    private static boolean containsUnit(String s) {
        for (String u : UNITS) if (s.contains(u)) return true;
        return false;
    }

    private static void collectTextViews(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) collectTextViews(vg.getChildAt(i), out);
        }
    }

    private static ListView findListView(View v) {
        if (v instanceof ListView) return (ListView) v;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                ListView found = findListView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<Button> findButtons(View v) {
        ArrayList<Button> out = new ArrayList<>();
        if (v instanceof Button) out.add((Button) v);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) out.addAll(findButtons(vg.getChildAt(i)));
        }
        return out;
    }

    private static JSONObject buildRealisticSession() throws Exception {
        long now = System.currentTimeMillis();
        JSONObject session = new JSONObject()
                .put("schema_version", 2)
                .put("suite_version", BenchmarkSuite.SESSION_VERSION)
                .put("session_id", "ui-check-session-123456")
                .put("status", "completed")
                .put("started_ms", now - 905000)
                .put("ended_ms", now)
                .put("wall_ms", 905000)
                .put("temperature", new JSONObject()
                        .put("available", true)
                        .put("start_tenths_c", 255)
                        .put("end_tenths_c", 312)
                        .put("sampled_max_tenths_c", 324)
                        .put("delta_tenths_c", 57));

        JSONArray results = new JSONArray();
        for (BenchmarkSuite suite : BenchmarkSuite.DEFAULT) {
            JSONObject stage = new JSONObject()
                    .put("schema_version", 1)
                    .put("suite_version", suite.version)
                    .put("status", "completed")
                    .put("started_ms", now - 60000)
                    .put("ended_ms", now)
                    .put("repetitions", suite.repetitions())
                    .put("temperature", session.getJSONObject("temperature"));

            JSONArray samples = new JSONArray();
            if (suite == BenchmarkSuite.VIDEO) {
                JSONArray clips = new JSONArray();
                String[] clipIds = {"h263-240p", "mpeg4-360p", "h264-480p", "h264-720p", "h264-1080p", "vp8-720p", "vp9-720p"};
                for (String id : clipIds) {
                    clips.put(new JSONObject().put("id", id)
                            .put("software", new JSONObject().put("status", "completed").put("fps", 119.51).put("realtime", true).put("decoder", "ffmpeg-scalar"))
                            .put("system", new JSONObject().put("status", "completed").put("fps", 64.20).put("realtime", true).put("decoder", "OMX.qcom.video.decoder.avc"))
                            .put("playback", new JSONObject().put("status", "completed").put("fps", 29.97).put("realtime", true).put("decoder", "MediaPlayer")));
                }
                samples.put(new JSONObject().put("status", "completed").put("results", clips));
            } else {
                JSONObject sample = new JSONObject()
                        .put("status", "completed")
                        .put("parallel_threads", 2)
                        .put("native_library", "librmb.so")
                        .put("native_abi", "armeabi-v7a")
                        .put("native_build_id", BuildConfig.NATIVE_BUILD_ID);
                if (suite == BenchmarkSuite.COREMARK) {
                    sample.put("single_iterations_per_second", 6010.40).put("parallel_iterations_per_second", 10151.70);
                } else if (suite == BenchmarkSuite.SCIMARK) {
                    sample.put("single_composite_mflops", 338.90).put("parallel_composite_mflops", 565.70)
                            .put("fft_mflops", 210.5).put("sor_mflops", 510.2).put("monte_carlo_mflops", 120.4)
                            .put("sparse_mflops", 340.1).put("lu_mflops", 513.3)
                            .put("parallel_fft_mflops", 380.1).put("parallel_sor_mflops", 890.4)
                            .put("parallel_monte_carlo_mflops", 220.8).put("parallel_sparse_mflops", 560.2)
                            .put("parallel_lu_mflops", 877.0);
                } else if (suite == BenchmarkSuite.MEMORY) {
                    sample.put("buffer_bytes", 8 * 1024 * 1024).put("copy_mbps", 4573.12).put("fill_mbps", 5120.44)
                            .put("latency_ns", 142.20).put("single_triad_mbps", 2110.30).put("parallel_triad_mbps", 2953.60);
                } else if (suite == BenchmarkSuite.STORAGE) {
                    sample.put("file_bytes", 32L * 1024 * 1024).put("random_operations", 256).put("read_mode", "cached")
                            .put("sequential_write_mbps", 10.29).put("sequential_read_mbps", 73.29)
                            .put("random_write_iops", 888.40).put("random_read_iops", 2881.10);
                } else if (suite == BenchmarkSuite.SQLITE) {
                    sample.put("sqlite_version", "3.53.1").put("transactions", 300)
                            .put("composite_tps", 37.42).put("insert_tps", 40.12)
                            .put("update_tps", 27.34).put("delete_tps", 44.80);
                } else if (suite == BenchmarkSuite.WEB) {
                    sample.put("viewport_width", 360).put("viewport_height", 640)
                            .put("physical_width", 720).put("physical_height", 1280).put("dpr", 2.0)
                            .put("clock_source", "performance.now").put("clock_resolution_ms", 0.1)
                            .put("host_build_id", BuildConfig.WEB_BUILD_ID)
                            .put("total_ms", 6362.10).put("feed_ms", 5692.40).put("image_ms", 379.50)
                            .put("page_ms", 1950.20).put("todo_ms", 1840.10).put("text_ms", 1902.10);
                } else if (suite.isGpu()) {
                    sample.put("vendor", "Qualcomm").put("renderer", "Adreno (TM) 225")
                            .put("render_width", suite.gpuTest().endsWith("_off") ? 1920 : 720)
                            .put("render_height", suite.gpuTest().endsWith("_off") ? 1080 : 1280)
                            .put("successful_window_swaps", 3420L)
                            .put("score", suite == BenchmarkSuite.GPU_FILL ? 691.50 : 2840.0)
                            .put("fps", suite == BenchmarkSuite.GPU_FILL ? 0.0 : 26.34);
                }
                for (int r = 0; r < suite.repetitions(); r++) {
                    samples.put(new JSONObject(sample.toString()));
                }
                stage.put("median", new JSONObject(sample.toString()));
            }
            stage.put("samples", samples);
            results.put(stage);
        }
        session.put("results", results);
        return session;
    }

    @TargetApi(11)
    private static final class Api11 {
        static boolean isSelectable(TextView tv) { return tv.isTextSelectable(); }
    }

    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
}
