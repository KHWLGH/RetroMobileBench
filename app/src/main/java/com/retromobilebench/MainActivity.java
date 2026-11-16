package com.retromobilebench;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.text.ClipboardManager;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Portrait controls and structured results in pure MIUI V5 system-utility style.
 * Visible GPU, Web, and Video benchmark output continues to use dedicated landscape windows.
 */
@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements BenchmarkRunner.Listener, MiuiSubPages.Navigator {
    private static final String[] TAB_TITLES = {"跑分", "成绩", "设备", "更多"};
    private static final String[] HEADER_TITLES = {"RetroMobileBench", "测试记录", "设备信息", "更多"};
    private static final int REQ_WRITE_STORAGE = 1001;
    private static final int REQ_CREATE_DOC = 1002;
    private static final ExecutorService EXPORT_EXECUTOR = Executors.newSingleThreadExecutor();

    private static final class SubPageEntry {
        final String title;
        final View view;
        SubPageEntry(String title, View view) {
            this.title = title;
            this.view = view;
        }
    }

    private BenchmarkRunner runner;
    private JSONObject activeReport;
    private JSONObject lastRenderedQuickReport;
    private String lastParsedExportJson;
    private JSONObject cachedDevice;
    private String cachedProbeText = "";
    private boolean nativeProbeLoaded;
    private JSONArray cachedHistory;
    private boolean deviceLoading;
    private boolean historyLoading;
    private boolean manageHistoryMode;
    private boolean wasBusy;
    private JSONObject pendingExportReport;
    private ReportExporter.Format pendingExportFormat;
    private boolean pendingExportShare;

    // Top Title Bar
    private TextView backBtn;
    private TextView titleText;
    private TextView headerBadge;

    // Navigation & Content Containers
    private int currentTab = 0;
    private final ArrayList<SubPageEntry> subPageStack = new ArrayList<>();
    private FrameLayout centerFrame;
    private FrameLayout subPageContainer;
    private LinearLayout bottomBar;
    private final ScrollView[] tabScrolls = new ScrollView[4];
    private final MiuiUi.TabIconView[] tabIcons = new MiuiUi.TabIconView[4];
    private final TextView[] tabLabels = new TextView[4];
    private final View[] tabIndicators = new View[4];

    private MessageQueue.IdleHandler idleSetupHandler;
    private int idleSetupStep = 0;
    private View cachedSuiteProtocolPage;
    private View cachedVersionPage;
    private View cachedProjectsPage;
    private View cachedLicenseReaderPage;

    // Dirty-checking cache for zero-interference onState updates
    private Boolean lastUiBusy;
    private String lastUiHeadline;
    private String lastUiMessage;
    private String lastUiProgressText;
    private int lastUiStageIdx = Integer.MIN_VALUE;
    private int lastUiCompletedStages = Integer.MIN_VALUE;
    private int lastUiRound = Integer.MIN_VALUE;
    private int lastUiReps = Integer.MIN_VALUE;

    // Tab 0 (Run Console) widgets
    private TextView deviceSummarySub;
    private TextView statusHeadline;
    private TextView statusMessage;
    private TextView statusPill;
    private LinearLayout preflightContainer;
    private LinearLayout liveProgressBox;
    private MiuiUi.ProgressBarView liveProgressBar;
    private TextView liveProgressText;
    private Button start;
    private Button cancel;
    private LinearLayout liveQueueSection;
    private final TextView[] stageQueueBadges = new TextView[BenchmarkSuite.DEFAULT.length];
    private LinearLayout idleQuickLinksSection;
    private LinearLayout recentReportQuickCard;

    // Tab 1 (Run History List) container
    private LinearLayout scorePageColumn;

    // Tab 2 (Device Info) container
    private LinearLayout devicePageColumn;

    // In-app MIUI V5 floating toast & modal confirmation overlay (zero separate Window black-frame bugs)
    private FrameLayout overlayRoot;
    private TextView toastView;
    private final Handler toastHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideToastRunnable = this::fadeOutToast;
    private FrameLayout activeDialogOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        runner = BenchmarkRunner.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        root.addView(buildTitleBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, MiuiUi.dp(this, 48)));

        View titleBorder = new View(this);
        titleBorder.setBackgroundColor(MiuiUi.TITLE_BORDER);
        root.addView(titleBorder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));

        centerFrame = new FrameLayout(this);

        tabScrolls[0] = buildRunTab();
        centerFrame.addView(tabScrolls[0], new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        subPageContainer = new FrameLayout(this);
        subPageContainer.setClickable(true);
        subPageContainer.setVisibility(View.GONE);
        centerFrame.addView(subPageContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(centerFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        bottomBar = buildBottomTabBar();
        root.addView(bottomBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        overlayRoot = new FrameLayout(this);
        overlayRoot.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        toastView = new TextView(this);
        toastView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        toastView.setTextColor(Color.WHITE);
        toastView.setGravity(Gravity.CENTER);
        toastView.setMaxWidth(MiuiUi.dp(this, 280));
        toastView.setMaxLines(2);
        toastView.setEllipsize(TextUtils.TruncateAt.END);
        toastView.setPadding(MiuiUi.dp(this, 18), MiuiUi.dp(this, 9), MiuiUi.dp(this, 18), MiuiUi.dp(this, 9));
        MiuiUi.setBackground(toastView, MiuiUi.toastBackground(this));
        toastView.setClickable(false);
        toastView.setFocusable(false);
        toastView.setVisibility(View.GONE);
        FrameLayout.LayoutParams toastLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        toastLp.bottomMargin = MiuiUi.dp(this, 68);
        overlayRoot.addView(toastView, toastLp);

        setContentView(overlayRoot);
        DisplaySurface.refreshHz = getWindowManager().getDefaultDisplay().getRefreshRate();

        switchTab(0);
        scheduleIdleWarmup();
    }

    private void scheduleIdleWarmup() {
        idleSetupStep = 0;
        idleSetupHandler = () -> {
            if (isFinishing() || (runner != null && runner.isBusy())) {
                idleSetupHandler = null;
                return false;
            }
            if (idleSetupStep == 0) {
                idleSetupStep = 1;
                if (cachedDevice == null) refreshDeviceData(false);
                MiuiSubPages.prewarmLicenseAssets(MainActivity.this);
                return true;
            }
            if (idleSetupStep <= 3) {
                ensureTabBuilt(idleSetupStep);
                idleSetupStep++;
                return true;
            }
            if (cachedHistory == null) refreshHistoryData();
            idleSetupHandler = null;
            return false;
        };
        Looper.myQueue().addIdleHandler(idleSetupHandler);
    }

    private void ensureTabBuilt(int index) {
        if (index < 0 || index >= 4 || tabScrolls[index] != null || centerFrame == null) return;
        ScrollView sv;
        if (index == 0) sv = buildRunTab();
        else if (index == 1) sv = buildScoreTab();
        else if (index == 2) sv = buildDeviceTab();
        else sv = buildMoreTab();
        sv.setVisibility(index == currentTab && subPageStack.isEmpty() ? View.VISIBLE : View.GONE);
        tabScrolls[index] = sv;
        int subIdx = centerFrame.indexOfChild(subPageContainer);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        if (subIdx >= 0) centerFrame.addView(sv, subIdx, lp);
        else centerFrame.addView(sv, lp);
    }

    private View buildTitleBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBaselineAligned(false);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        MiuiUi.setBackground(bar, MiuiUi.titleBarBackground());
        int hPad = MiuiUi.dp(this, 12);
        bar.setPadding(hPad, 0, hPad, 0);

        backBtn = new TextView(this);
        backBtn.setText("‹ 返回");
        backBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        backBtn.setTextColor(Color.WHITE);
        MiuiUi.setBackground(backBtn, MiuiUi.headerBackButtonDrawable(this));
        int bh = MiuiUi.dp(this, 10), bv = MiuiUi.dp(this, 5);
        backBtn.setPadding(bh, bv, bh, bv);
        backBtn.setVisibility(View.GONE);
        backBtn.setOnClickListener(v -> popSubPage());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        backLp.rightMargin = MiuiUi.dp(this, 10);
        bar.addView(backBtn, backLp);

        titleText = new TextView(this);
        titleText.setText(HEADER_TITLES[0]);
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        titleText.setTypeface(Typeface.DEFAULT_BOLD);
        titleText.setTextColor(Color.WHITE);
        titleText.setShadowLayer(1.5f, 0f, -1f, 0x80000000);
        titleText.setSingleLine(true);
        titleText.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(titleText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        headerBadge = MiuiUi.badge(this, "v" + BuildConfig.VERSION_NAME, MiuiUi.TONE_NEUTRAL);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.leftMargin = MiuiUi.dp(this, 8);
        bar.addView(headerBadge, badgeLp);
        return bar;
    }

    private LinearLayout buildBottomTabBar() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        MiuiUi.setBackground(wrapper, MiuiUi.bottomBarBackground());

        View border = new View(this);
        border.setBackgroundColor(MiuiUi.TAB_BORDER);
        wrapper.addView(border, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        for (int i = 0; i < 4; i++) {
            final int tabIndex = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            MiuiUi.setBackground(item, MiuiUi.rowClickDrawable());
            item.setOnClickListener(v -> switchTab(tabIndex));

            View indicator = new View(this);
            indicator.setBackgroundColor(Color.TRANSPARENT);
            tabIndicators[i] = indicator;
            item.addView(indicator, new LinearLayout.LayoutParams(
                    MiuiUi.dp(this, 28), MiuiUi.dp(this, 2.5f)));

            MiuiUi.TabIconView icon = new MiuiUi.TabIconView(this, i);
            tabIcons[i] = icon;
            int iconSize = MiuiUi.dp(this, 20);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
            iconLp.topMargin = MiuiUi.dp(this, 5);
            item.addView(icon, iconLp);

            TextView label = new TextView(this);
            label.setText(TAB_TITLES[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            label.setPadding(0, MiuiUi.dp(this, 2), 0, MiuiUi.dp(this, 5));
            tabLabels[i] = label;
            item.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            row.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        wrapper.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return wrapper;
    }

    private ScrollView buildRunTab() {
        LinearLayout col = MiuiSubPages.pageColumn(this);

        // Device summary banner card
        col.addView(MiuiUi.sectionTitle(this, "当前设备"));
        LinearLayout devCard = MiuiUi.cardGroup(this);
        String modelLine = Build.MANUFACTURER + " " + Build.MODEL;
        String sysLine = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · " + Build.CPU_ABI;
        MiuiUi.MiuiRowLayout devRow = MiuiUi.navRow(this, modelLine, sysLine, "详情", null, 0, () -> switchTab(2));
        TextView deviceSummaryTitle = devRow.titleView();
        deviceSummaryTitle.setSingleLine(true);
        deviceSummaryTitle.setEllipsize(TextUtils.TruncateAt.END);
        deviceSummarySub = devRow.subtitleView();
        deviceSummarySub.setMaxLines(2);
        deviceSummarySub.setEllipsize(TextUtils.TruncateAt.END);
        devCard.addView(devRow);
        col.addView(devCard);

        // Main benchmark console card
        col.addView(MiuiUi.sectionTitle(this, "跑分控制台"));
        LinearLayout consoleCard = MiuiUi.cardGroup(this);

        LinearLayout statusHeader = new LinearLayout(this);
        statusHeader.setOrientation(LinearLayout.HORIZONTAL);
        statusHeader.setBaselineAligned(false);
        statusHeader.setGravity(Gravity.CENTER_VERTICAL);
        int pad = MiuiUi.dp(this, 14);
        statusHeader.setPadding(pad, MiuiUi.dp(this, 12), pad, MiuiUi.dp(this, 10));

        LinearLayout statusTitles = new LinearLayout(this);
        statusTitles.setOrientation(LinearLayout.VERTICAL);
        statusHeadline = new TextView(this);
        statusHeadline.setText("准备就绪");
        statusHeadline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusHeadline.setTypeface(Typeface.DEFAULT_BOLD);
        statusHeadline.setTextColor(MiuiUi.TEXT_PRIMARY);
        statusHeadline.setSingleLine(true);
        statusHeadline.setEllipsize(TextUtils.TruncateAt.END);
        statusTitles.addView(statusHeadline);

        statusMessage = new TextView(this);
        statusMessage.setText(R.string.console_subtitle_ready);
        statusMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusMessage.setTextColor(MiuiUi.TEXT_SECONDARY);
        statusMessage.setSingleLine(true);
        statusMessage.setEllipsize(TextUtils.TruncateAt.END);
        statusMessage.setPadding(0, MiuiUi.dp(this, 3), 0, 0);
        statusTitles.addView(statusMessage);

        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titlesLp.rightMargin = MiuiUi.dp(this, 8);
        statusHeader.addView(statusTitles, titlesLp);
        statusPill = MiuiUi.badge(this, "就绪", MiuiUi.TONE_OK);
        statusHeader.addView(statusPill);
        consoleCard.addView(statusHeader);

        MiuiUi.addDivider(this, consoleCard);

        // Compact 3-column preflight status strip
        preflightContainer = new LinearLayout(this);
        preflightContainer.setOrientation(LinearLayout.HORIZONTAL);
        preflightContainer.setBaselineAligned(false);
        preflightContainer.setGravity(Gravity.CENTER_VERTICAL);
        consoleCard.addView(preflightContainer);

        // Live progress box (visible while busy)
        liveProgressBox = new LinearLayout(this);
        liveProgressBox.setOrientation(LinearLayout.VERTICAL);
        liveProgressBox.setPadding(pad, MiuiUi.dp(this, 10), pad, MiuiUi.dp(this, 6));
        liveProgressBox.setVisibility(View.GONE);

        liveProgressText = new TextView(this);
        liveProgressText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        liveProgressText.setTextColor(MiuiUi.TEXT_SECONDARY);
        liveProgressBox.addView(liveProgressText);

        liveProgressBar = new MiuiUi.ProgressBarView(this, MiuiUi.dp(this, 8));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, MiuiUi.dp(this, 8));
        barLp.topMargin = MiuiUi.dp(this, 8);
        liveProgressBox.addView(liveProgressBar, barLp);
        consoleCard.addView(liveProgressBox);

        // Primary CTA buttons ("开始测试" & "取消当前测试")
        LinearLayout btnBox = new LinearLayout(this);
        btnBox.setOrientation(LinearLayout.HORIZONTAL);
        btnBox.setBaselineAligned(false);
        btnBox.setPadding(pad, MiuiUi.dp(this, 10), pad, MiuiUi.dp(this, 12));

        start = MiuiUi.styledButton(this, "开始测试", true, false, runner::startAll);
        btnBox.addView(start, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        cancel = MiuiUi.styledButton(this, getString(R.string.cancel_run), false, true, () -> runner.cancel());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cancelLp.leftMargin = MiuiUi.dp(this, 10);
        btnBox.addView(cancel, cancelLp);
        consoleCard.addView(btnBox);
        col.addView(consoleCard);

        // Live 12-stage checklist (visible when busy)
        liveQueueSection = new LinearLayout(this);
        liveQueueSection.setOrientation(LinearLayout.VERTICAL);
        liveQueueSection.setVisibility(View.GONE);
        liveQueueSection.addView(MiuiUi.sectionTitle(this, "测试进度 (12 阶段)"));
        LinearLayout queueCard = MiuiUi.cardGroup(this);
        for (int i = 0; i < BenchmarkSuite.DEFAULT.length; i++) {
            if (i > 0) MiuiUi.addDivider(this, queueCard);
            BenchmarkSuite s = BenchmarkSuite.DEFAULT[i];
            MiuiUi.MiuiRowLayout row = MiuiUi.kvRow(this, (i + 1) + ". " + s.label,
                    s.repetitions() + " 轮", null, "待执行", MiuiUi.TONE_NEUTRAL);
            stageQueueBadges[i] = row.badgeView();
            queueCard.addView(row);
        }
        liveQueueSection.addView(queueCard);
        col.addView(liveQueueSection);

        // Idle latest run quick card (visible when not busy & history exists)
        idleQuickLinksSection = new LinearLayout(this);
        idleQuickLinksSection.setOrientation(LinearLayout.VERTICAL);
        recentReportQuickCard = new LinearLayout(this);
        recentReportQuickCard.setOrientation(LinearLayout.VERTICAL);
        idleQuickLinksSection.addView(recentReportQuickCard);
        col.addView(idleQuickLinksSection);

        return MiuiSubPages.wrapScroll(this, col);
    }

    private ScrollView buildScoreTab() {
        scorePageColumn = MiuiSubPages.pageColumn(this);
        renderScoreTab();
        return MiuiSubPages.wrapScroll(this, scorePageColumn);
    }

    private ScrollView buildDeviceTab() {
        devicePageColumn = MiuiSubPages.pageColumn(this);
        if (cachedDevice != null) {
            renderDeviceTab(cachedDevice);
        } else {
            devicePageColumn.addView(MiuiUi.sectionTitle(this, "硬件信息"));
            LinearLayout loadingCard = MiuiUi.cardGroup(this);
            loadingCard.addView(MiuiUi.kvRow(this, "正在采集硬件信息...", null, null, null, 0));
            devicePageColumn.addView(loadingCard);
        }
        return MiuiSubPages.wrapScroll(this, devicePageColumn);
    }

    private ScrollView buildMoreTab() {
        LinearLayout col = MiuiSubPages.pageColumn(this);

        col.addView(MiuiUi.sectionTitle(this, "测试与流程"));
        LinearLayout group1 = MiuiUi.cardGroup(this);
        group1.addView(MiuiUi.navRow(this, "测试套件说明", "12 项串行基准测试与明确测试量说明",
                "12 阶段", null, 0, () -> {
                    if (cachedSuiteProtocolPage == null) {
                        cachedSuiteProtocolPage = MiuiSubPages.buildSuiteProtocolPage(this);
                    }
                    openSubPage("测试套件说明", cachedSuiteProtocolPage);
                }));
        col.addView(group1);

        col.addView(MiuiUi.sectionTitle(this, "版本与探针"));
        LinearLayout group2 = MiuiUi.cardGroup(this);
        group2.addView(MiuiUi.navRow(this, "版本与构建信息", "应用版本、编译工具链与构建标识",
                "v" + BuildConfig.VERSION_NAME, null, 0, () -> {
                    if (cachedVersionPage == null) {
                        cachedVersionPage = MiuiSubPages.buildVersionPage(this, this);
                    }
                    openSubPage("版本与构建信息", cachedVersionPage);
                }));
        MiuiUi.addDivider(this, group2);
        group2.addView(MiuiUi.navRow(this, "底层硬件探针", "CPU 核心、GL 扩展与 JNI 报告",
                null, null, 0, this::openCpuProbeSubPage));
        col.addView(group2);

        col.addView(MiuiUi.sectionTitle(this, "开源与许可"));
        LinearLayout group3 = MiuiUi.cardGroup(this);
        group3.addView(MiuiUi.navRow(this, "内置开源项目", "CoreMark、SciMark、FFmpeg、GFXBench 等",
                "7 项", null, 0, () -> {
                    if (cachedProjectsPage == null) {
                        cachedProjectsPage = MiuiSubPages.buildOpenSourceProjectsPage(this, this);
                    }
                    openSubPage("内置开源项目", cachedProjectsPage);
                }));
        MiuiUi.addDivider(this, group3);
        group3.addView(MiuiUi.navRow(this, "开源许可协议全文", "第三方库许可与媒体署名",
                null, null, 0, () -> {
                    if (cachedLicenseReaderPage == null) {
                        cachedLicenseReaderPage = MiuiSubPages.buildLicenseReaderPage(this, this);
                    }
                    openSubPage("开源许可协议全文", cachedLicenseReaderPage);
                }));
        col.addView(group3);

        return MiuiSubPages.wrapScroll(this, col);
    }

    private void switchTab(int index) {
        dismissActiveDialog();
        subPageStack.clear();
        subPageContainer.removeAllViews();
        subPageContainer.setVisibility(View.GONE);
        bottomBar.setVisibility(View.VISIBLE);
        backBtn.setVisibility(View.GONE);

        currentTab = index;
        ensureTabBuilt(index);
        titleText.setText(HEADER_TITLES[index]);
        for (int i = 0; i < 4; i++) {
            boolean sel = (i == index);
            if (tabScrolls[i] != null) {
                tabScrolls[i].setVisibility(sel ? View.VISIBLE : View.GONE);
            }
            tabIcons[i].setActive(sel);
            tabLabels[i].setTextColor(sel ? MiuiUi.ORANGE : MiuiUi.TEXT_MUTED);
            tabLabels[i].setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tabIndicators[i].setBackgroundColor(sel ? MiuiUi.ORANGE : Color.TRANSPARENT);
        }
        if (index == 1 && !runner.isBusy()) {
            refreshHistoryData();
        }
        if (index == 2 && cachedDevice == null && !runner.isBusy()) {
            refreshDeviceData(false);
        }
    }

    @Override
    public void openSubPage(String title, View content) {
        if (content != null && content.getParent() instanceof ViewGroup) {
            ((ViewGroup) content.getParent()).removeView(content);
        }
        if (content instanceof ScrollView) {
            content.scrollTo(0, 0);
        }
        subPageStack.add(new SubPageEntry(title, content));
        renderSubPageTop();
    }

    private void popSubPage() {
        if (!subPageStack.isEmpty()) {
            subPageStack.remove(subPageStack.size() - 1);
        }
        if (!subPageStack.isEmpty()) {
            renderSubPageTop();
        } else {
            subPageContainer.removeAllViews();
            subPageContainer.setVisibility(View.GONE);
            if (tabScrolls[currentTab] != null) tabScrolls[currentTab].setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.VISIBLE);
            backBtn.setVisibility(View.GONE);
            titleText.setText(HEADER_TITLES[currentTab]);
        }
    }

    private void renderSubPageTop() {
        SubPageEntry top = subPageStack.get(subPageStack.size() - 1);
        if (tabScrolls[currentTab] != null) tabScrolls[currentTab].setVisibility(View.GONE);
        subPageContainer.removeAllViews();
        if (top.view.getParent() instanceof ViewGroup) {
            ((ViewGroup) top.view.getParent()).removeView(top.view);
        }
        subPageContainer.addView(top.view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        subPageContainer.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.GONE);
        backBtn.setVisibility(View.VISIBLE);
        titleText.setText(top.title);
    }

    @Override
    public void onBackPressed() {
        if (activeDialogOverlay != null) {
            dismissActiveDialog();
            return;
        }
        if (!subPageStack.isEmpty() && !runner.isBusy()) {
            popSubPage();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void copyText(String label, String content) {
        if (content == null || content.length() == 0) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setText(content);
            showToast(getString(R.string.copied));
        }
    }

    @Override
    public void deleteRun(JSONObject report) {
        if (report == null || !report.has("id") || runner.isBusy()) return;
        final long id = report.optLong("id", -1);
        if (id < 0) return;
        showConfirmOverlay(
                "删除测试记录",
                "确认删除这条测试记录？删除后无法恢复。",
                "删除",
                true,
                () -> runner.deleteHistoryRun(id, (history, error) -> {
                    if (isFinishing()) return;
                    if (error != null) {
                        showToast("删除失败：" + error);
                        return;
                    }
                    cachedHistory = history;
                    if (cachedHistory == null || cachedHistory.length() == 0) manageHistoryMode = false;
                    while (!subPageStack.isEmpty()) popSubPage();
                    renderScoreTab();
                    showToast("已删除");
                }));
    }

    private void confirmClearAllHistory() {
        if (runner.isBusy()) return;
        showConfirmOverlay(
                "清空全部记录",
                "确认清空本地保存的所有测试记录？此操作不可撤销。",
                "全部清空",
                true,
                () -> runner.clearAllHistory((history, error) -> {
                    if (isFinishing()) return;
                    if (error != null) {
                        showToast("清空失败：" + error);
                        return;
                    }
                    cachedHistory = history;
                    manageHistoryMode = false;
                    renderScoreTab();
                    showToast("已清空全部记录");
                }));
    }

    @Override
    public void exportReport(final JSONObject report) {
        if (report == null || isFinishing()) return;
        final boolean isSession = BenchmarkSuite.isSession(report.optString("suite_version"));
        showModalOverlay(MiuiUi.buildExportOptionsOverlay(this, isSession, this::dismissActiveDialog,
                new MiuiUi.ExportChoiceListener() {
                    @Override
                    public void onSaveLocal(ReportExporter.Format format) {
                        startLocalExport(report, format, false);
                    }

                    @Override
                    public void onSaveAndShare(ReportExporter.Format format) {
                        startLocalExport(report, format, true);
                    }

                    @Override
                    public void onSaveAsSaf(ReportExporter.Format format) {
                        startSafExport(report, format);
                    }

                    @Override
                    public void onCopySummary() {
                        if (isSession) {
                            copyText("核心指标摘要", MiuiSubPages.buildQuickComparisonText(report));
                        } else {
                            EXPORT_EXECUTOR.execute(() -> {
                                try {
                                    String text = ReportExporter.buildCompleteText(report);
                                    runOnUiThread(() -> {
                                        if (!isFinishing()) copyText("报告摘要", text);
                                    });
                                } catch (JSONException e) {
                                    runOnUiThread(() -> {
                                        if (!isFinishing()) showToast("生成摘要失败：" + e.getMessage());
                                    });
                                }
                            });
                        }
                    }
                }));
    }

    private void startLocalExport(JSONObject report, ReportExporter.Format format, boolean shareAfter) {
        if (PlatformCompat.shouldRequestWriteExternalStorage(this)) {
            pendingExportReport = report;
            pendingExportFormat = format;
            pendingExportShare = shareAfter;
            PlatformCompat.requestWriteExternalStorage(this, REQ_WRITE_STORAGE);
            return;
        }
        performLocalExport(report, format, shareAfter);
    }

    private void startSafExport(JSONObject report, ReportExporter.Format format) {
        String fileName = ReportExporter.defaultFileName(report, format);
        Intent intent = PlatformCompat.createDocumentIntent(format.mimeType, fileName);
        if (intent == null) {
            performLocalExport(report, format, false);
            return;
        }
        pendingExportReport = report;
        pendingExportFormat = format;
        try {
            startActivityForResult(intent, REQ_CREATE_DOC);
        } catch (ActivityNotFoundException e) {
            pendingExportReport = null;
            pendingExportFormat = null;
            showToast("系统未提供文件选择器，已改为直接保存到下载目录");
            performLocalExport(report, format, false);
        }
    }

    private void performLocalExport(final JSONObject report, final ReportExporter.Format format, final boolean shareAfter) {
        showToast("正在生成导出文件...");
        EXPORT_EXECUTOR.execute(() -> {
            final ExportManager.ExportResult result = ExportManager.exportToFile(getApplicationContext(), report, format);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (!result.success) {
                    showToast("导出失败：" + result.error);
                    return;
                }
                if (shareAfter) {
                    launchShare(result);
                } else {
                    showModalOverlay(MiuiUi.buildExportResultOverlay(
                            MainActivity.this,
                            result.fileName,
                            result.displayPath,
                            result.storageNote,
                            MainActivity.this::dismissActiveDialog,
                            () -> launchShare(result),
                            () -> copyText("导出路径", result.displayPath)));
                }
            });
        });
    }

    private void launchShare(ExportManager.ExportResult result) {
        try {
            startActivity(ExportManager.buildShareIntent(result));
        } catch (ActivityNotFoundException e) {
            showToast("未找到可接收分享的应用，文件已保存至：" + result.displayPath);
        }
    }

    @Override
    @TargetApi(23)
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (Build.VERSION.SDK_INT >= 23) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
        if (requestCode == REQ_WRITE_STORAGE && pendingExportReport != null && pendingExportFormat != null) {
            JSONObject report = pendingExportReport;
            ReportExporter.Format format = pendingExportFormat;
            boolean shareAfter = pendingExportShare;
            pendingExportReport = null;
            pendingExportFormat = null;
            pendingExportShare = false;
            performLocalExport(report, format, shareAfter);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CREATE_DOC) {
            final JSONObject report = pendingExportReport;
            final ReportExporter.Format format = pendingExportFormat;
            pendingExportReport = null;
            pendingExportFormat = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && report != null && format != null) {
                final Uri uri = data.getData();
                final String fileName = ReportExporter.defaultFileName(report, format);
                showToast("正在写入文件...");
                EXPORT_EXECUTOR.execute(() -> {
                    try {
                        ExportManager.writeToUri(getApplicationContext(), uri, report, format);
                        runOnUiThread(() -> {
                            if (!isFinishing()) showToast("已保存文件：" + fileName);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            if (!isFinishing()) showToast("保存失败：" + e.getMessage());
                        });
                    }
                });
            }
        }
    }

    private void showConfirmOverlay(String title, String message, String confirmLabel,
                                    boolean danger, Runnable onConfirm) {
        showModalOverlay(MiuiUi.buildConfirmOverlay(
                this, title, message, confirmLabel, danger, this::dismissActiveDialog, onConfirm));
    }

    private void showModalOverlay(FrameLayout overlay) {
        if (overlayRoot == null || overlay == null || isFinishing()) return;
        dismissActiveDialog();
        activeDialogOverlay = overlay;
        int toastIdx = overlayRoot.indexOfChild(toastView);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        if (toastIdx >= 0) overlayRoot.addView(activeDialogOverlay, toastIdx, lp);
        else overlayRoot.addView(activeDialogOverlay, lp);
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(140);
        activeDialogOverlay.startAnimation(fadeIn);
    }

    private void dismissActiveDialog() {
        if (activeDialogOverlay != null) {
            activeDialogOverlay.clearAnimation();
            if (overlayRoot != null) overlayRoot.removeView(activeDialogOverlay);
            activeDialogOverlay = null;
        }
    }

    private void showToast(String message) {
        if (toastView == null || message == null || message.length() == 0 || isFinishing()) return;
        toastHandler.removeCallbacks(hideToastRunnable);
        toastView.clearAnimation();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) toastView.getLayoutParams();
        int targetMargin = (bottomBar != null && bottomBar.getVisibility() == View.VISIBLE)
                ? MiuiUi.dp(this, 68)
                : MiuiUi.dp(this, 28);
        if (lp.bottomMargin != targetMargin) {
            lp.bottomMargin = targetMargin;
            toastView.setLayoutParams(lp);
        }
        toastView.setText(message);
        toastView.setVisibility(View.VISIBLE);
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(150);
        toastView.startAnimation(fadeIn);
        toastHandler.postDelayed(hideToastRunnable, 1800);
    }

    private void fadeOutToast() {
        if (toastView == null || toastView.getVisibility() != View.VISIBLE) return;
        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
        fadeOut.setDuration(220);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation animation) { }
            @Override public void onAnimationRepeat(Animation animation) { }
            @Override public void onAnimationEnd(Animation animation) {
                if (toastView != null) toastView.setVisibility(View.GONE);
            }
        });
        toastView.startAnimation(fadeOut);
    }

    private void openCpuProbeSubPage() {
        if (cachedDevice == null || !nativeProbeLoaded) {
            if (runner.isBusy()) return;
            runner.loadDeviceSnapshot(true, (device, probeText, error) -> {
                if (isFinishing()) return;
                if (device != null) {
                    cachedDevice = device;
                    cachedProbeText = probeText != null ? probeText : "";
                    nativeProbeLoaded = true;
                    updateRunTabDeviceSummary(cachedDevice);
                    renderPreflightRows(cachedDevice);
                    renderDeviceTab(cachedDevice);
                } else if (error != null) {
                    cachedProbeText = error;
                }
                openSubPage("底层硬件探针", MiuiSubPages.buildCpuProbePage(this, cachedDevice, cachedProbeText, this));
            });
            return;
        }
        openSubPage("底层硬件探针", MiuiSubPages.buildCpuProbePage(this, cachedDevice, cachedProbeText, this));
    }

    private void refreshHistoryData() {
        if (runner == null || runner.isBusy() || historyLoading) return;
        historyLoading = true;
        runner.loadAllHistory((history, error) -> {
            historyLoading = false;
            if (isFinishing()) return;
            if (error == null && history != null) {
                cachedHistory = history;
                if (cachedHistory.length() == 0) manageHistoryMode = false;
                renderScoreTab();
            }
        });
    }

    private void refreshDeviceData(boolean showToast) {
        if (runner == null || runner.isBusy() || deviceLoading) return;
        deviceLoading = true;
        final boolean includeNative = nativeProbeLoaded;
        runner.loadDeviceSnapshot(includeNative, (device, probeText, error) -> {
            deviceLoading = false;
            if (isFinishing()) return;
            if (device != null) {
                cachedDevice = device;
                if (includeNative) {
                    cachedProbeText = probeText != null ? probeText : "";
                }
                updateRunTabDeviceSummary(cachedDevice);
                renderPreflightRows(cachedDevice);
                renderDeviceTab(cachedDevice);
                if (showToast) showToast("硬件信息已刷新");
            } else if (error != null) {
                if (showToast) showToast("刷新失败：" + error);
            }
        });
    }

    private void updateRunTabDeviceSummary(JSONObject device) {
        if (device == null || deviceSummarySub == null) return;
        JSONObject extra = device.optJSONObject("ui_extra");
        String gpu = "";
        if (extra != null) {
            gpu = extra.optString("gpu_display_name");
            if (gpu.length() == 0) gpu = DeviceHardwareExtra.formatGpuModel(extra.optString("gpu_renderer"));
        }
        CpuSocResolver.Result soc = CpuSocResolver.resolve(device);
        int workers = CpuTopology.workers(device);
        String cpuPart = soc.matched ? soc.socName : (workers + " 核 " + device.optString("abi"));
        String line = "Android " + device.optString("android_release")
                + " · " + cpuPart
                + (soc.matched ? " (" + workers + " 核)" : "")
                + (gpu.length() > 0 ? " · " + gpu : "");
        deviceSummarySub.setText(line);
    }

    private void renderPreflightRows(JSONObject device) {
        preflightContainer.removeAllViews();
        if (device == null) return;

        int batteryPct = device.optInt("battery_percent", -1);
        boolean plugged = device.optBoolean("plugged", false);
        int tempTenths = device.optInt("battery_temperature_tenths_c", Integer.MIN_VALUE);
        boolean batteryOk = batteryPct < 0 || batteryPct >= 30 || plugged;
        String batVal = (batteryPct >= 0 ? batteryPct + "%" : "未知")
                + (plugged ? " 充电" : "")
                + (TemperatureTrace.validTemperature(tempTenths)
                ? String.format(Locale.US, " %.0f\u00A0°C", tempTenths / 10.0) : "");
        preflightContainer.addView(buildPreflightCell("电源电量", batVal,
                batteryOk ? "正常" : "需充电", batteryOk ? MiuiUi.TONE_OK : MiuiUi.TONE_BAD),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        addVerticalPreflightDivider();

        long availMem = device.optLong("available_memory_bytes", -1);
        boolean memOk = !device.optBoolean("low_memory") && availMem >= 192L * 1024 * 1024;
        preflightContainer.addView(buildPreflightCell("可用内存", MiuiUi.formatBytesShort(availMem),
                memOk ? "充足" : "偏低", memOk ? MiuiUi.TONE_OK : MiuiUi.TONE_WARN),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        addVerticalPreflightDivider();

        long availStorage = device.optLong("available_storage_bytes", -1);
        boolean storageOk = availStorage >= 48L * 1024 * 1024;
        preflightContainer.addView(buildPreflightCell("内部存储", MiuiUi.formatBytesShort(availStorage),
                storageOk ? "充足" : "不足", storageOk ? MiuiUi.TONE_OK : MiuiUi.TONE_BAD),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    private View buildPreflightCell(String label, String value, String badgeText, int tone) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        MiuiUi.setBackground(cell, MiuiUi.rowClickDrawable());
        int h = MiuiUi.dp(this, 6), v = MiuiUi.dp(this, 9);
        cell.setPadding(h, v, h, v);
        cell.setOnClickListener(view -> switchTab(2));

        TextView k = new TextView(this);
        k.setText(label);
        k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        k.setTextColor(MiuiUi.TEXT_MUTED);
        k.setSingleLine(true);
        k.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(k, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setTextColor(MiuiUi.TEXT_PRIMARY);
        val.setSingleLine(true);
        val.setEllipsize(TextUtils.TruncateAt.END);
        val.setPadding(0, MiuiUi.dp(this, 3), 0, MiuiUi.dp(this, 4));
        cell.addView(val, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        cell.addView(MiuiUi.badge(this, badgeText, tone), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    private void addVerticalPreflightDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(MiuiUi.DIVIDER_ROW);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(1, MiuiUi.dp(this, 46));
        int m = MiuiUi.dp(this, 6);
        lp.topMargin = m;
        lp.bottomMargin = m;
        preflightContainer.addView(divider, lp);
    }

    private void renderDeviceTab(JSONObject device) {
        if (devicePageColumn == null) return;
        devicePageColumn.removeAllViews();
        if (device == null) return;
        JSONObject extra = device.optJSONObject("ui_extra");

        // 1. System & Model
        devicePageColumn.addView(MiuiUi.sectionTitle(this, "系统与型号"));
        LinearLayout sysCard = MiuiUi.cardGroup(this);
        String brand = extra != null ? extra.optString("brand") : "";
        String mfg = device.optString("manufacturer");
        String brandText = brand.length() > 0 && !brand.equalsIgnoreCase(mfg) ? mfg + " (" + brand + ")" : mfg;
        sysCard.addView(MiuiUi.kvRow(this, "品牌 / 制造商", null, brandText, null, 0));
        MiuiUi.addDivider(this, sysCard);
        sysCard.addView(MiuiUi.kvRow(this, "设备型号", "代号：" + device.optString("device"),
                device.optString("model"), null, 0));
        if (extra != null) {
            String board = extra.optString("board");
            String hw = extra.optString("hardware");
            if (board.length() > 0 || hw.length() > 0) {
                MiuiUi.addDivider(this, sysCard);
                sysCard.addView(MiuiUi.kvRow(this, "主板 / 硬件平台", null,
                        board + (hw.length() > 0 ? " · " + hw : ""), null, 0));
            }
        }
        MiuiUi.addDivider(this, sysCard);
        String dispId = extra != null ? extra.optString("display_id") : "";
        sysCard.addView(MiuiUi.kvRow(this, "Android 系统", dispId.length() > 0 ? dispId : null,
                "Android " + device.optString("android_release") + " (API " + device.optInt("sdk") + ")", null, 0));
        if (extra != null) {
            String kernel = extra.optString("kernel_version");
            if (kernel.length() > 0) {
                MiuiUi.addDivider(this, sysCard);
                sysCard.addView(MiuiUi.kvRow(this, "Linux 内核", kernel, extra.optString("kernel_arch"), null, 0));
            }
        }
        MiuiUi.addDivider(this, sysCard);
        boolean emu = device.optBoolean("emulator");
        sysCard.addView(MiuiUi.kvRow(this, "Java 虚拟机", "运行环境：" + (emu ? "模拟器" : "物理真机"),
                "VM " + device.optString("vm"), emu ? "模拟器" : "真机", emu ? MiuiUi.TONE_WARN : MiuiUi.TONE_OK));
        devicePageColumn.addView(sysCard);

        // 2. Processor (CPU)
        devicePageColumn.addView(MiuiUi.sectionTitle(this, "处理器 (CPU)"));
        LinearLayout cpuCard = MiuiUi.cardGroup(this);
        CpuSocResolver.Result soc = CpuSocResolver.resolve(device);
        boolean isEmu = device.optBoolean("emulator");
        String socSub = soc.socName + (soc.sourceNote.length() > 0 ? "\n" + soc.sourceNote : "");
        cpuCard.addView(MiuiUi.kvRow(this, "处理器型号",
                socSub,
                null, isEmu ? "模拟器" : null, isEmu ? MiuiUi.TONE_WARN : 0));
        if (soc.cpuArch.length() > 0 && !"未知".equals(soc.cpuArch)) {
            MiuiUi.addDivider(this, cpuCard);
            String archSub = soc.cpuArch + (soc.cpuArchDetail.length() > 0 ? "\n" + soc.cpuArchDetail : "");
            cpuCard.addView(MiuiUi.kvRow(this, "核心微架构", archSub, null, null, 0));
        }
        MiuiUi.addDivider(this, cpuCard);
        String abi2 = device.optString("abi2");
        String abiText = device.optString("abi") + (abi2.length() > 0 ? " / " + abi2 : "");
        cpuCard.addView(MiuiUi.kvRow(this, "指令集架构", null, abiText, null, 0));
        MiuiUi.addDivider(this, cpuCard);
        int workers = CpuTopology.workers(device);
        cpuCard.addView(MiuiUi.kvRow(this, "核心与在线状态",
                "在线：" + device.optString("cpu_online") + " · 呈现：" + device.optString("cpu_present", "未知"),
                workers + "\u00A0核", null, 0));
        MiuiUi.addDivider(this, cpuCard);
        cpuCard.addView(MiuiUi.kvRow(this, "CPU0 频率与调速器",
                "调速器：" + device.optString("cpu0_governor"),
                MiuiUi.formatKhz(device.optString("cpu0_frequency_khz")) + "\n最大 " + MiuiUi.formatKhz(device.optString("cpu0_max_frequency_khz")),
                null, 0));
        MiuiUi.addDivider(this, cpuCard);
        String lib = device.optString("native_library", "");
        boolean loaded = lib.length() > 0;
        boolean hasNeon = loaded && lib.contains("librmb.so") && device.optString("abi").startsWith("armeabi");
        boolean isArmNonNeon = loaded && lib.contains("noneon");
        cpuCard.addView(MiuiUi.kvRow(this, "原生计算库", null, loaded ? lib : "按需加载",
                !loaded ? "待加载" : (isArmNonNeon ? "标量" : (hasNeon ? "NEON" : "原生")),
                !loaded ? MiuiUi.TONE_NEUTRAL : (isArmNonNeon ? MiuiUi.TONE_WARN : MiuiUi.TONE_OK)));
        MiuiUi.addDivider(this, cpuCard);
        cpuCard.addView(MiuiUi.navRow(this, "各核心频率、GL 扩展与 JNI 探针", null,
                null, null, 0, this::openCpuProbeSubPage));
        devicePageColumn.addView(cpuCard);

        // 3. Graphics (GPU)
        devicePageColumn.addView(MiuiUi.sectionTitle(this, "图形处理器 (GPU)"));
        LinearLayout gpuCard = MiuiUi.cardGroup(this);
        String gpuRenderer = extra != null ? extra.optString("gpu_renderer") : "";
        String gpuDisplayName = extra != null ? extra.optString("gpu_display_name") : "";
        if (gpuDisplayName.length() == 0 && gpuRenderer.length() > 0) {
            gpuDisplayName = DeviceHardwareExtra.formatGpuModel(gpuRenderer);
        }
        String gpuVendor = extra != null ? extra.optString("gpu_vendor") : "";
        String gpuVendorDisplay = extra != null ? extra.optString("gpu_vendor_display") : "";
        if (gpuVendorDisplay.length() == 0 && gpuVendor.length() > 0) {
            gpuVendorDisplay = DeviceHardwareExtra.formatGpuVendor(gpuVendor);
        }
        String gpuVersion = extra != null ? extra.optString("gpu_version") : "";
        if (gpuRenderer.length() > 0) {
            boolean isTranslated = extra.optBoolean("gpu_translated")
                    || DeviceHardwareExtra.isEmulatorTranslatedGpu(gpuRenderer);
            boolean isSoftware = extra.optBoolean("gpu_software")
                    || DeviceHardwareExtra.isSoftwareGpu(gpuRenderer);
            String gpuTag = isSoftware ? "软件渲染" : (isTranslated ? "宿主转译" : null);
            int gpuTone = isSoftware ? MiuiUi.TONE_WARN : MiuiUi.TONE_NEUTRAL;
            gpuCard.addView(MiuiUi.kvRow(this, "GPU 型号", gpuDisplayName, null, gpuTag, gpuTone));
            if (!gpuRenderer.equals(gpuDisplayName)) {
                MiuiUi.addDivider(this, gpuCard);
                gpuCard.addView(MiuiUi.kvRow(this, "GL 渲染器", gpuRenderer, null, null, 0));
            }
            MiuiUi.addDivider(this, gpuCard);
            String vendorSub = !gpuVendor.equals(gpuVendorDisplay) && gpuVendor.length() > 0
                    ? "接口提供方：" + gpuVendor : null;
            gpuCard.addView(MiuiUi.kvRow(this, "GPU 厂商", vendorSub,
                    gpuVendorDisplay.length() > 0 ? gpuVendorDisplay : "未知", null, 0));
            MiuiUi.addDivider(this, gpuCard);
            gpuCard.addView(MiuiUi.kvRow(this, "OpenGL ES 版本", gpuVersion.length() > 0 ? gpuVersion : "未知", null, null, 0));
            MiuiUi.addDivider(this, gpuCard);
            String eglVer = extra.optString("egl_version", "未知");
            int maxTex = extra.optInt("gpu_max_texture_size", 0);
            gpuCard.addView(MiuiUi.kvRow(this, "EGL 与最大纹理", "EGL " + eglVer + (extra.has("egl_vendor") ? " · " + extra.optString("egl_vendor") : ""),
                    maxTex > 0 ? maxTex + "\u00A0px" : "未知", null, 0));
            MiuiUi.addDivider(this, gpuCard);
            boolean etc1 = extra.optBoolean("gpu_etc1", false);
            gpuCard.addView(MiuiUi.kvRow(this, "ETC1 压缩纹理", null, null, etc1 ? "支持" : "不支持",
                    etc1 ? MiuiUi.TONE_OK : MiuiUi.TONE_WARN));
        } else {
            gpuCard.addView(MiuiUi.kvRow(this, "GPU 型号", "未能通过离线 EGL 上下文探测", "未知", null, 0));
        }
        devicePageColumn.addView(gpuCard);

        // 4. Display
        if (extra != null && extra.has("screen_width_px")) {
            devicePageColumn.addView(MiuiUi.sectionTitle(this, "屏幕显示"));
            LinearLayout dispCard = MiuiUi.cardGroup(this);
            int wPx = extra.optInt("screen_width_px");
            int hPx = extra.optInt("screen_height_px");
            float density = (float) extra.optDouble("screen_density", 1.0);
            int wDp = Math.round(wPx / density);
            int hDp = Math.round(hPx / density);
            dispCard.addView(MiuiUi.kvRow(this, "屏幕分辨率", wDp + "×" + hDp + "\u00A0dp", wPx + "×" + hPx + "\u00A0px", null, 0));
            MiuiUi.addDivider(this, dispCard);
            int dpi = extra.optInt("screen_density_dpi");
            String inches = extra.optString("screen_inches");
            dispCard.addView(MiuiUi.kvRow(this, "像素密度与尺寸",
                    inches.length() > 0 ? "估算尺寸约 " + inches + "\u00A0英寸" : null,
                    dpi + "\u00A0dpi (" + String.format(Locale.US, "%.2fx", density) + ")", null, 0));
            double hz = extra.optDouble("screen_refresh_hz", DisplaySurface.refreshHz);
            if (hz > 0) {
                MiuiUi.addDivider(this, dispCard);
                dispCard.addView(MiuiUi.kvRow(this, "屏幕刷新率", null, String.format(Locale.US, "%.1f\u00A0Hz", hz), null, 0));
            }
            devicePageColumn.addView(dispCard);
        }

        // 5. Memory & Storage
        devicePageColumn.addView(MiuiUi.sectionTitle(this, "内存与存储"));
        LinearLayout memCard = MiuiUi.cardGroup(this);
        long availMem = device.optLong("available_memory_bytes", 0);
        long totalMem = device.optLong("total_memory_bytes", 0);
        if (totalMem > 0) {
            float usedRatio = Math.max(0f, Math.min(1f, (float) (totalMem - availMem) / (float) totalMem));
            memCard.addView(MiuiUi.metricBarRow(this, "物理内存 (已用 / 总计)",
                    MiuiUi.formatBytesShort(totalMem - availMem) + " / " + MiuiUi.formatBytesShort(totalMem),
                    usedRatio, MiuiUi.ORANGE));
            MiuiUi.addDivider(this, memCard);
        }
        long memThreshold = extra != null ? extra.optLong("memory_threshold_bytes", 0) : 0;
        memCard.addView(MiuiUi.kvRow(this, "可用运行内存",
                memThreshold > 0 ? "低内存警戒阈值：" + MiuiUi.formatBytesShort(memThreshold) : null,
                MiuiUi.formatBytesShort(availMem), null, 0));

        long dataTotal = extra != null ? extra.optLong("data_total_bytes", 0) : 0;
        long dataAvail = extra != null ? extra.optLong("data_avail_bytes", device.optLong("available_storage_bytes", 0)) : device.optLong("available_storage_bytes", 0);
        MiuiUi.addDivider(this, memCard);
        if (dataTotal > 0) {
            float usedStore = Math.max(0f, Math.min(1f, (float) (dataTotal - dataAvail) / (float) dataTotal));
            memCard.addView(MiuiUi.metricBarRow(this, "内部存储 (/data 可用 / 总计)",
                    MiuiUi.formatBytesShort(dataAvail) + " / " + MiuiUi.formatBytesShort(dataTotal),
                    usedStore, MiuiUi.GREEN));
        } else {
            memCard.addView(MiuiUi.kvRow(this, "内部可用空间", null, MiuiUi.formatBytesShort(dataAvail), null, 0));
        }
        if (extra != null && extra.has("ext_total_bytes")) {
            long extTotal = extra.optLong("ext_total_bytes", 0);
            long extAvail = extra.optLong("ext_avail_bytes", 0);
            if (extTotal > 0) {
                MiuiUi.addDivider(this, memCard);
                memCard.addView(MiuiUi.kvRow(this, "外部存储", null,
                        "可用 " + MiuiUi.formatBytesShort(extAvail) + " / " + MiuiUi.formatBytesShort(extTotal), null, 0));
            }
        }
        devicePageColumn.addView(memCard);

        // 6. Battery & Actions
        devicePageColumn.addView(MiuiUi.sectionTitle(this, "电池与操作"));
        LinearLayout actionCard = MiuiUi.cardGroup(this);
        int batPct = device.optInt("battery_percent", -1);
        int tempTenths = device.optInt("battery_temperature_tenths_c", Integer.MIN_VALUE);
        boolean plugged = device.optBoolean("plugged");
        StringBuilder batSub = new StringBuilder();
        if (extra != null) {
            int plugType = extra.optInt("battery_plugged_type", 0);
            if (plugged) {
                batSub.append(plugType == BatteryManager.BATTERY_PLUGGED_AC ? "AC 充电"
                        : (plugType == BatteryManager.BATTERY_PLUGGED_USB ? "USB 供电" : "充电中"));
            } else {
                batSub.append("电池放电");
            }
            int mv = extra.optInt("battery_voltage_mv", -1);
            if (mv > 0) batSub.append(String.format(Locale.US, " · %.2f\u00A0V", mv / 1000.0));
            String tech = extra.optString("battery_tech");
            if (tech.length() > 0) batSub.append(" · ").append(tech);
        }
        String batRight = (batPct >= 0 ? batPct + "%" : "未知")
                + (TemperatureTrace.validTemperature(tempTenths)
                ? String.format(Locale.US, " · %.1f\u00A0°C", tempTenths / 10.0) : "");
        actionCard.addView(MiuiUi.kvRow(this, "电池状态", batSub.toString(), batRight, null, 0));
        MiuiUi.addDivider(this, actionCard);
        actionCard.addView(MiuiUi.navRow(this, "刷新硬件信息", null, null, null, 0, () -> refreshDeviceData(true)));
        MiuiUi.addDivider(this, actionCard);
        actionCard.addView(MiuiUi.navRow(this, "复制设备 JSON", null, null, null, 0, () -> {
            try {
                copyText("设备 JSON", device.toString(2));
            } catch (JSONException e) {
                copyText("设备 JSON", device.toString());
            }
        }));
        devicePageColumn.addView(actionCard);
    }

    private void renderRecentQuickCard() {
        recentReportQuickCard.removeAllViews();
        if (activeReport == null) return;
        recentReportQuickCard.addView(MiuiUi.sectionTitle(this, "最近一次测试"));
        LinearLayout card = MiuiUi.cardGroup(this);
        String suiteVer = activeReport.optString("suite_version");
        boolean isSession = BenchmarkSuite.isSession(suiteVer);
        BenchmarkSuite singleSuite = isSession ? null : BenchmarkSuite.fromReport(activeReport);
        String title = isSession ? "完整测试套件"
                : (singleSuite != null ? singleSuite.label : suiteVer);
        String status = activeReport.optString("status", "failed");

        StringBuilder sub = new StringBuilder();
        if (isSession) {
            JSONArray stages = activeReport.optJSONArray("results");
            int count = stages == null ? 0 : stages.length();
            sub.append(count).append("/").append(BenchmarkSuite.DEFAULT.length).append(" 阶段");
            long wall = activeReport.optLong("wall_ms", 0);
            if (wall > 0) sub.append(" · ").append(MiuiUi.formatDuration(wall));
        } else if (singleSuite != null) {
            sub.append(MiuiSubPages.stageHeadlineSummary(singleSuite, activeReport));
        }
        card.addView(MiuiUi.navRow(this, title, sub.toString(), "详情",
                BenchmarkReport.statusLabel(status), MiuiUi.statusTone(status),
                () -> openSubPage(title, MiuiSubPages.buildReportDetailPage(this, activeReport, runner.isBusy(), this))));
        recentReportQuickCard.addView(card);
    }

    private void renderScoreTab() {
        if (scorePageColumn == null) return;
        scorePageColumn.removeAllViews();

        int totalCount = cachedHistory != null ? cachedHistory.length() : 0;

        // Top section header + Manage / Clear actions
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setBaselineAligned(false);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        int hPad = MiuiUi.dp(this, 16);
        headerRow.setPadding(hPad, MiuiUi.dp(this, 10), MiuiUi.dp(this, 12), MiuiUi.dp(this, 6));

        TextView countTitle = new TextView(this);
        countTitle.setText(getString(R.string.history_count, totalCount));
        countTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        countTitle.setTypeface(Typeface.DEFAULT_BOLD);
        countTitle.setTextColor(MiuiUi.TEXT_MUTED);
        headerRow.addView(countTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (totalCount > 0 && !runner.isBusy()) {
            if (manageHistoryMode) {
                TextView clearChip = MiuiUi.actionChip(this, "全部清空", true, this::confirmClearAllHistory);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = MiuiUi.dp(this, 8);
                headerRow.addView(clearChip, clp);
            }
            TextView manageChip = MiuiUi.actionChip(this, manageHistoryMode ? "完成" : "管理", false, () -> {
                manageHistoryMode = !manageHistoryMode;
                renderScoreTab();
            });
            headerRow.addView(manageChip);
        }
        scorePageColumn.addView(headerRow);

        if (cachedHistory == null) {
            LinearLayout loading = MiuiUi.cardGroup(this);
            loading.addView(MiuiUi.kvRow(this, "正在读取测试记录...", null, null, null, 0));
            scorePageColumn.addView(loading);
            return;
        }

        if (totalCount == 0) {
            LinearLayout empty = MiuiUi.cardGroup(this);
            empty.addView(MiuiUi.kvRow(this, "暂无测试记录", "在「跑分」页点击「开始测试」即可生成记录。", null, null, 0));
            MiuiUi.addDivider(this, empty);
            empty.addView(MiuiUi.navRow(this, "去跑分", null, null, null, 0, () -> switchTab(0)));
            scorePageColumn.addView(empty);
            return;
        }

        LinearLayout listCard = MiuiUi.cardGroup(this);
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        for (int i = 0; i < totalCount; i++) {
            final JSONObject item = cachedHistory.optJSONObject(i);
            if (item == null) continue;
            if (i > 0) MiuiUi.addDivider(this, listCard);

            String suiteVer = item.optString("suite_version");
            boolean isSession = BenchmarkSuite.isSession(suiteVer);
            BenchmarkSuite singleSuite = isSession ? null : BenchmarkSuite.fromReport(item);
            final String title = isSession ? "完整测试套件"
                    : (singleSuite != null ? singleSuite.label : suiteVer);

            String status = item.optString("status", "failed");
            long started = item.optLong("started_ms");
            long wallMs = item.optLong("wall_ms", item.optLong("ended_ms", started) - started);
            String timeStr = started > 0 ? fmt.format(new Date(started)) : "未知时间";

            StringBuilder sub = new StringBuilder(timeStr);
            if (isSession) {
                JSONArray stages = item.optJSONArray("results");
                int count = stages == null ? 0 : stages.length();
                sub.append(" · ").append(count).append("/").append(BenchmarkSuite.DEFAULT.length).append(" 阶段");
                if (wallMs > 0) sub.append(" · ").append(MiuiUi.formatDuration(wallMs));
            } else {
                if (singleSuite != null) {
                    String hl = MiuiSubPages.stageHeadlineSummary(singleSuite, item);
                    if (!hl.equals(BenchmarkReport.statusLabel(status))) {
                        sub.append(" · ").append(hl);
                    }
                }
                if (wallMs > 0) sub.append(" · ").append(MiuiUi.formatDuration(wallMs));
            }

            if (manageHistoryMode) {
                LinearLayout manageRow = new LinearLayout(this);
                manageRow.setOrientation(LinearLayout.HORIZONTAL);
                manageRow.setBaselineAligned(false);
                manageRow.setGravity(Gravity.CENTER_VERTICAL);
                MiuiUi.MiuiRowLayout row = MiuiUi.kvRow(this, title, sub.toString(), null,
                        BenchmarkReport.statusLabel(status), MiuiUi.statusTone(status));
                manageRow.addView(row, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView delBtn = MiuiUi.actionChip(this, "删除", true, () -> deleteRun(item));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                dlp.rightMargin = MiuiUi.dp(this, 14);
                manageRow.addView(delBtn, dlp);
                listCard.addView(manageRow);
            } else {
                listCard.addView(MiuiUi.navRow(this, title, sub.toString(), null,
                        BenchmarkReport.statusLabel(status), MiuiUi.statusTone(status),
                        () -> openSubPage(title, MiuiSubPages.buildReportDetailPage(this, item, runner.isBusy(), this))));
            }
        }
        scorePageColumn.addView(listCard);
    }

    @Override
    protected void onStart() {
        super.onStart();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        runner.attach(this);
    }

    @Override
    protected void onStop() {
        runner.detach(this);
        boolean webHandoff = WebClient.consumeHandoff(), displayHandoff = DisplaySurface.consumeHandoff();
        if (!PlatformCompat.changingConfigurations(this) && runner.isBusy() && !MediaClient.externalPlaybackActive()
                && !GpuClient.externalDisplayActive() && !webHandoff && !displayHandoff) runner.interrupt();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (idleSetupHandler != null) {
            Looper.myQueue().removeIdleHandler(idleSetupHandler);
            idleSetupHandler = null;
        }
        toastHandler.removeCallbacks(hideToastRunnable);
        dismissActiveDialog();
        super.onDestroy();
    }

    @Override
    public void onState(BenchmarkRunner.State state) {
        boolean busyChanged = lastUiBusy == null || lastUiBusy != state.busy;
        if (busyChanged) {
            lastUiBusy = state.busy;
            start.setEnabled(!state.busy);
            cancel.setEnabled(state.busy);
        }

        if (state.busy) {
            if (!wasBusy) {
                dismissActiveDialog();
                manageHistoryMode = false;
                if (currentTab != 0 || !subPageStack.isEmpty()) switchTab(0);
                preflightContainer.setVisibility(View.GONE);
                liveProgressBox.setVisibility(View.VISIBLE);
                liveQueueSection.setVisibility(View.VISIBLE);
                idleQuickLinksSection.setVisibility(View.GONE);
                updateBadge(statusPill, "运行中", MiuiUi.TONE_ACTIVE);
                updateBadge(headerBadge, "运行中", MiuiUi.TONE_ACTIVE);
                lastUiStageIdx = Integer.MIN_VALUE;
            }
            wasBusy = true;

            String headline = "正在运行固定基准套件";
            if (!headline.equals(lastUiHeadline)) {
                lastUiHeadline = headline;
                statusHeadline.setText(headline);
            }
            String msgText = state.message != null ? state.message : "";
            if (!msgText.equals(lastUiMessage)) {
                lastUiMessage = msgText;
                statusMessage.setText(msgText);
            }

            int totalStages = BenchmarkSuite.DEFAULT.length;
            int doneStages = Math.max(0, state.completedStages);
            float roundPart = state.repetitions > 0 ? (float) state.savedRounds / (float) state.repetitions : 0f;
            float progress = (doneStages + roundPart) / (float) totalStages;
            liveProgressBar.setProgress(progress, MiuiUi.ORANGE);

            String pText = state.text != null && state.text.length() > 0 ? state.text : msgText;
            if (!pText.equals(lastUiProgressText)) {
                lastUiProgressText = pText;
                liveProgressText.setText(pText);
            }

            if (state.stageIndex != lastUiStageIdx || doneStages != lastUiCompletedStages
                    || state.round != lastUiRound || state.repetitions != lastUiReps) {
                lastUiStageIdx = state.stageIndex;
                lastUiCompletedStages = doneStages;
                lastUiRound = state.round;
                lastUiReps = state.repetitions;
                for (int i = 0; i < totalStages; i++) {
                    TextView badge = stageQueueBadges[i];
                    if (badge == null) continue;
                    if (i < doneStages) {
                        updateBadge(badge, "✓ 完成", MiuiUi.TONE_OK);
                    } else if (i == state.stageIndex) {
                        String rText = state.repetitions > 0
                                ? "第 " + Math.max(1, state.round) + "/" + state.repetitions + " 轮"
                                : "运行中";
                        updateBadge(badge, rText, MiuiUi.TONE_ACTIVE);
                    } else {
                        updateBadge(badge, "待执行", MiuiUi.TONE_NEUTRAL);
                    }
                }
            }
        } else {
            boolean prevBusy = wasBusy;
            wasBusy = false;
            lastUiStageIdx = Integer.MIN_VALUE;
            lastUiCompletedStages = Integer.MIN_VALUE;
            lastUiRound = Integer.MIN_VALUE;
            lastUiReps = Integer.MIN_VALUE;
            lastUiProgressText = null;

            if (busyChanged || prevBusy) {
                updateBadge(statusPill, "空闲", MiuiUi.TONE_OK);
                updateBadge(headerBadge, "v" + BuildConfig.VERSION_NAME, MiuiUi.TONE_NEUTRAL);
                statusMessage.setText(R.string.console_subtitle_idle);
                lastUiMessage = null;
                preflightContainer.setVisibility(View.VISIBLE);
                liveProgressBox.setVisibility(View.GONE);
                liveQueueSection.setVisibility(View.GONE);
                idleQuickLinksSection.setVisibility(View.VISIBLE);
            }

            String msg = state.message != null ? state.message : "";
            boolean isHistoryInitMsg = msg.length() == 0 || msg.startsWith("本地记录") || "就绪".equals(msg);
            String headline = isHistoryInitMsg ? "准备就绪" : msg;
            if (!headline.equals(lastUiHeadline)) {
                lastUiHeadline = headline;
                statusHeadline.setText(headline);
            }

            parseActiveReportFromState(state);
            if (activeReport != lastRenderedQuickReport) {
                lastRenderedQuickReport = activeReport;
                renderRecentQuickCard();
            }
            if (prevBusy || (currentTab == 1 && cachedHistory == null)) {
                refreshHistoryData();
            }
        }
    }

    private void parseActiveReportFromState(BenchmarkRunner.State state) {
        if (state.latestReport != null) {
            activeReport = state.latestReport;
            lastParsedExportJson = state.exportJson;
            return;
        }
        String json = state.exportJson;
        if (json == null || json.length() == 0) {
            activeReport = null;
            lastParsedExportJson = "";
            return;
        }
        if (json.equals(lastParsedExportJson)) return;
        lastParsedExportJson = json;
        String trimmed = json.trim();
        try {
            if (trimmed.startsWith("{")) {
                JSONObject obj = new JSONObject(trimmed);
                if (obj.has("suite_version") || obj.has("samples") || obj.has("results")) {
                    activeReport = obj;
                }
            } else if (trimmed.startsWith("[")) {
                JSONArray arr = new JSONArray(trimmed);
                activeReport = arr.length() > 0 ? arr.optJSONObject(0) : null;
            }
        } catch (JSONException ignored) { }
    }

    private void updateBadge(TextView pill, String text, int tone) {
        String key = tone + ":" + text;
        if (key.equals(pill.getTag())) return;
        pill.setTag(key);
        pill.setText(text);
        pill.setTextColor(MiuiUi.badgeTextColor(tone));
        MiuiUi.setBackground(pill, MiuiUi.badgeDrawable(this, tone));
        int h = MiuiUi.dp(this, 8), v = MiuiUi.dp(this, 2);
        pill.setPadding(h, v, h, v);
    }
}
