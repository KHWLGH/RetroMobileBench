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
    private static final String[] TAB_TITLES_ZH = {"跑分", "成绩", "设备", "更多"};
    private static final String[] TAB_TITLES_EN = {"Run", "History", "Device", "More"};
    private static final String[] HEADER_TITLES_ZH = {"RetroMobileBench", "测试记录", "设备信息", "更多"};
    private static final String[] HEADER_TITLES_EN = {"RetroMobileBench", "Test History", "Device Info", "More"};
    private static final String EXTRA_INITIAL_TAB = "initial_tab";
    private static final String STATE_CURRENT_TAB = "current_tab";
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
    private View cachedAboutPage;
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

    private String tabTitle(int index) {
        return (I18n.isZh(this) ? TAB_TITLES_ZH : TAB_TITLES_EN)[index];
    }

    private String headerTitle(int index) {
        return (I18n.isZh(this) ? HEADER_TITLES_ZH : HEADER_TITLES_EN)[index];
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        I18n.applyLocale(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        runner = BenchmarkRunner.get(this);

        int initialTab = savedInstanceState != null
                ? savedInstanceState.getInt(STATE_CURRENT_TAB, 0)
                : (getIntent() != null ? getIntent().getIntExtra(EXTRA_INITIAL_TAB, 0) : 0);
        if (initialTab < 0 || initialTab > 3) initialTab = 0;

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
        centerFrame.addView(tabScrolls[0], new LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        subPageContainer = new FrameLayout(this);
        subPageContainer.setClickable(true);
        subPageContainer.setVisibility(View.GONE);
        centerFrame.addView(subPageContainer, new LinearLayout.LayoutParams(
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

        switchTab(initialTab);
        scheduleIdleWarmup();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_CURRENT_TAB, currentTab);
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
        backBtn.setText(I18n.t(this, "‹ 返回", "‹ Back"));
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
        titleText.setText(headerTitle(0));
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
            label.setText(tabTitle(i));
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
        boolean zh = I18n.isZh(this);
        LinearLayout col = MiuiSubPages.pageColumn(this);

        // Device summary banner card
        col.addView(MiuiUi.sectionTitle(this, zh ? "当前设备" : "Current Device"));
        LinearLayout devCard = MiuiUi.cardGroup(this);
        String modelLine = Build.MANUFACTURER + " " + Build.MODEL;
        String sysLine = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · " + Build.CPU_ABI;
        MiuiUi.MiuiRowLayout devRow = MiuiUi.navRow(this, modelLine, sysLine,
                zh ? "详情" : "Details", null, 0, () -> switchTab(2));
        TextView deviceSummaryTitle = devRow.titleView();
        deviceSummaryTitle.setSingleLine(true);
        deviceSummaryTitle.setEllipsize(TextUtils.TruncateAt.END);
        deviceSummarySub = devRow.subtitleView();
        deviceSummarySub.setMaxLines(2);
        deviceSummarySub.setEllipsize(TextUtils.TruncateAt.END);
        devCard.addView(devRow);
        col.addView(devCard);

        // Main benchmark console card
        col.addView(MiuiUi.sectionTitle(this, zh ? "跑分控制台" : "Benchmark Console"));
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
        statusHeadline.setText(zh ? "准备就绪" : "Ready");
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
        statusPill = MiuiUi.badge(this, zh ? "就绪" : "Ready", MiuiUi.TONE_OK);
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

        // Primary CTA buttons
        LinearLayout btnBox = new LinearLayout(this);
        btnBox.setOrientation(LinearLayout.HORIZONTAL);
        btnBox.setBaselineAligned(false);
        btnBox.setPadding(pad, MiuiUi.dp(this, 10), pad, MiuiUi.dp(this, 12));

        start = MiuiUi.styledButton(this, zh ? "开始测试" : "Start Test", true, false, runner::startAll);
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
        liveQueueSection.addView(MiuiUi.sectionTitle(this, zh ? "测试进度 (12 阶段)" : "Stage Progress (12 Stages)"));
        LinearLayout queueCard = MiuiUi.cardGroup(this);
        for (int i = 0; i < BenchmarkSuite.DEFAULT.length; i++) {
            if (i > 0) MiuiUi.addDivider(this, queueCard);
            BenchmarkSuite s = BenchmarkSuite.DEFAULT[i];
            String roundLabel = zh ? (s.repetitions() + " 轮") : (s.repetitions() == 1 ? "1 round" : s.repetitions() + " rounds");
            MiuiUi.MiuiRowLayout row = MiuiUi.kvRow(this, (i + 1) + ". " + s.label(),
                    roundLabel, null, zh ? "待执行" : "Pending", MiuiUi.TONE_NEUTRAL);
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
            boolean zh = I18n.isZh(this);
            devicePageColumn.addView(MiuiUi.sectionTitle(this, zh ? "硬件信息" : "Hardware Info"));
            LinearLayout loadingCard = MiuiUi.cardGroup(this);
            loadingCard.addView(MiuiUi.kvRow(this, zh ? "正在采集硬件信息..." : "Collecting hardware info...", null, null, null, 0));
            devicePageColumn.addView(loadingCard);
        }
        return MiuiSubPages.wrapScroll(this, devicePageColumn);
    }

    private ScrollView buildMoreTab() {
        boolean zh = I18n.isZh(this);
        LinearLayout col = MiuiSubPages.pageColumn(this);

        LinearLayout listCard = MiuiUi.cardGroup(this);

        // 1. Language Switcher
        listCard.addView(MiuiUi.navRow(this,
                zh ? "界面语言 / Language" : "Language / 界面语言",
                zh ? "切换应用界面与测试报告语言" : "Switch UI and benchmark report language",
                I18n.currentModeSummary(this), null, 0, this::openLanguageSelector));

        // 2. Suite Protocol
        MiuiUi.addDivider(this, listCard);
        final String suiteTitle = zh ? "测试套件说明" : "Suite Protocol";
        listCard.addView(MiuiUi.navRow(this, suiteTitle,
                zh ? "12 项串行基准测试与统计规则说明" : "12-stage fixed benchmark order & statistics policy",
                zh ? "12 阶段" : "12 Stages", null, 0, () -> {
                    if (cachedSuiteProtocolPage == null) {
                        cachedSuiteProtocolPage = MiuiSubPages.buildSuiteProtocolPage(this);
                    }
                    openSubPage(suiteTitle, cachedSuiteProtocolPage);
                }));

        // 3. Hardware Probe
        MiuiUi.addDivider(this, listCard);
        listCard.addView(MiuiUi.navRow(this,
                zh ? "底层硬件探针" : "Hardware Probe",
                zh ? "CPU 核心、GL 扩展与 JNI 报告" : "Per-core CPU, GL extensions & JNI probe",
                null, null, 0, this::openCpuProbeSubPage));

        // 4. Bundled Open-Source Projects
        MiuiUi.addDivider(this, listCard);
        final String projectsTitle = zh ? "内置开源项目" : "Open-Source Projects";
        listCard.addView(MiuiUi.navRow(this, projectsTitle,
                zh ? "CoreMark、SciMark、FFmpeg、GFXBench 等" : "CoreMark, SciMark, FFmpeg, GFXBench, etc.",
                zh ? "7 项" : "7 Items", null, 0, () -> {
                    if (cachedProjectsPage == null) {
                        cachedProjectsPage = MiuiSubPages.buildOpenSourceProjectsPage(this, this);
                    }
                    openSubPage(projectsTitle, cachedProjectsPage);
                }));

        // 5. Open-Source Licenses
        MiuiUi.addDivider(this, listCard);
        final String licenseTitle = zh ? "开源许可协议全文" : "Open-Source Licenses";
        listCard.addView(MiuiUi.navRow(this, licenseTitle,
                zh ? "第三方库许可与媒体署名" : "Third-party licenses & media attributions",
                null, null, 0, () -> {
                    if (cachedLicenseReaderPage == null) {
                        cachedLicenseReaderPage = MiuiSubPages.buildLicenseReaderPage(this, this);
                    }
                    openSubPage(licenseTitle, cachedLicenseReaderPage);
                }));

        // 6. About
        MiuiUi.addDivider(this, listCard);
        final String aboutTitle = zh ? "关于软件" : "About";
        listCard.addView(MiuiUi.navRow(this, aboutTitle,
                zh ? "软件说明、版本、GitHub 链接与构建标识" : "App info, version, GitHub link & build identity",
                "v" + BuildConfig.VERSION_NAME, null, 0, () -> {
                    if (cachedAboutPage == null) {
                        cachedAboutPage = MiuiSubPages.buildAboutPage(this, this);
                    }
                    openSubPage(aboutTitle, cachedAboutPage);
                }));

        col.addView(listCard);
        return MiuiSubPages.wrapScroll(this, col);
    }

    private void openLanguageSelector() {
        if (runner != null && runner.isBusy()) {
            showToast(I18n.t(this, "测试进行中，暂无法切换语言", "Cannot switch language while benchmark is running"));
            return;
        }
        showModalOverlay(MiuiUi.buildLanguageOverlay(
                this, I18n.getMode(this), this::dismissActiveDialog, this::applyLanguageMode));
    }

    private void applyLanguageMode(String mode) {
        if (mode == null || mode.equals(I18n.getMode(this))) return;
        I18n.setMode(this, mode);
        cachedSuiteProtocolPage = null;
        cachedAboutPage = null;
        cachedProjectsPage = null;
        cachedLicenseReaderPage = null;
        if (getIntent() != null) getIntent().putExtra(EXTRA_INITIAL_TAB, 3);
        if (Build.VERSION.SDK_INT >= 11) {
            Api11.recreate(this);
        } else {
            Intent restart = new Intent(this, MainActivity.class);
            restart.putExtra(EXTRA_INITIAL_TAB, 3);
            startActivity(restart);
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @TargetApi(11)
    private static final class Api11 {
        static void recreate(Activity activity) {
            activity.recreate();
        }
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
        titleText.setText(headerTitle(index));
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
            titleText.setText(headerTitle(currentTab));
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
        boolean zh = I18n.isZh(this);
        showConfirmOverlay(
                zh ? "删除测试记录" : "Delete Test Record",
                zh ? "确认删除这条测试记录？删除后无法恢复。" : "Delete this benchmark record? This cannot be undone.",
                zh ? "删除" : "Delete",
                true,
                () -> runner.deleteHistoryRun(id, (history, error) -> {
                    if (isFinishing()) return;
                    if (error != null) {
                        showToast(I18n.t(this, "删除失败：", "Delete failed: ") + error);
                        return;
                    }
                    cachedHistory = history;
                    if (cachedHistory == null || cachedHistory.length() == 0) manageHistoryMode = false;
                    while (!subPageStack.isEmpty()) popSubPage();
                    renderScoreTab();
                    showToast(I18n.t(this, "已删除", "Deleted"));
                }));
    }

    private void confirmClearAllHistory() {
        if (runner.isBusy()) return;
        boolean zh = I18n.isZh(this);
        showConfirmOverlay(
                zh ? "清空全部记录" : "Clear All History",
                zh ? "确认清空本地保存的所有测试记录？此操作不可撤销。" : "Clear all locally saved benchmark records? This cannot be undone.",
                zh ? "全部清空" : "Clear All",
                true,
                () -> runner.clearAllHistory((history, error) -> {
                    if (isFinishing()) return;
                    if (error != null) {
                        showToast(I18n.t(this, "清空失败：", "Clear failed: ") + error);
                        return;
                    }
                    cachedHistory = history;
                    manageHistoryMode = false;
                    renderScoreTab();
                    showToast(I18n.t(this, "已清空全部记录", "All records cleared"));
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
                            copyText(I18n.t(MainActivity.this, "核心指标摘要", "Key Metrics"),
                                    MiuiSubPages.buildQuickComparisonText(report));
                        } else {
                            EXPORT_EXECUTOR.execute(() -> {
                                try {
                                    String text = ReportExporter.buildCompleteText(report);
                                    runOnUiThread(() -> {
                                        if (!isFinishing()) {
                                            copyText(I18n.t(MainActivity.this, "报告摘要", "Report Summary"), text);
                                        }
                                    });
                                } catch (JSONException e) {
                                    runOnUiThread(() -> {
                                        if (!isFinishing()) {
                                            showToast(I18n.t(MainActivity.this, "生成摘要失败：", "Summary failed: ") + e.getMessage());
                                        }
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
            showToast(I18n.t(this, "系统未提供文件选择器，已改为直接保存到下载目录",
                    "No system file picker available; saving directly to Downloads"));
            performLocalExport(report, format, false);
        }
    }

    private void performLocalExport(final JSONObject report, final ReportExporter.Format format, final boolean shareAfter) {
        showToast(I18n.t(this, "正在生成导出文件...", "Generating export file..."));
        EXPORT_EXECUTOR.execute(() -> {
            final ExportManager.ExportResult result = ExportManager.exportToFile(getApplicationContext(), report, format);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (!result.success) {
                    showToast(I18n.t(MainActivity.this, "导出失败：", "Export failed: ") + result.error);
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
                            () -> copyText(I18n.t(MainActivity.this, "导出路径", "Export Path"), result.displayPath)));
                }
            });
        });
    }

    private void launchShare(ExportManager.ExportResult result) {
        try {
            startActivity(ExportManager.buildShareIntent(result));
        } catch (ActivityNotFoundException e) {
            showToast(I18n.t(this, "未找到可接收分享的应用，文件已保存至：",
                    "No share target app found; file saved to: ") + result.displayPath);
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
                showToast(I18n.t(this, "正在写入文件...", "Writing file..."));
                EXPORT_EXECUTOR.execute(() -> {
                    try {
                        ExportManager.writeToUri(getApplicationContext(), uri, report, format);
                        runOnUiThread(() -> {
                            if (!isFinishing()) {
                                showToast(I18n.t(MainActivity.this, "已保存文件：", "File saved: ") + fileName);
                            }
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            if (!isFinishing()) {
                                showToast(I18n.t(MainActivity.this, "保存失败：", "Save failed: ") + e.getMessage());
                            }
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
        final String probeTitle = I18n.t(this, "底层硬件探针", "Hardware Probe");
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
                openSubPage(probeTitle, MiuiSubPages.buildCpuProbePage(this, cachedDevice, cachedProbeText, this));
            });
            return;
        }
        openSubPage(probeTitle, MiuiSubPages.buildCpuProbePage(this, cachedDevice, cachedProbeText, this));
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
                if (showToast) showToast(I18n.t(this, "硬件信息已刷新", "Hardware info refreshed"));
            } else if (error != null) {
                if (showToast) showToast(I18n.t(this, "刷新失败：", "Refresh failed: ") + error);
            }
        });
    }

    private void updateRunTabDeviceSummary(JSONObject device) {
        if (device == null || deviceSummarySub == null) return;
        boolean zh = I18n.isZh(this);
        JSONObject extra = device.optJSONObject("ui_extra");
        String gpu = "";
        if (extra != null) {
            gpu = extra.optString("gpu_display_name");
            if (gpu.length() == 0) gpu = DeviceHardwareExtra.formatGpuModel(extra.optString("gpu_renderer"));
        }
        CpuSocResolver.Result soc = CpuSocResolver.resolve(device);
        int workers = CpuTopology.workers(device);
        String cpuPart = soc.matched ? soc.socName
                : (workers + (zh ? " 核 " : " cores ") + device.optString("abi"));
        String line = "Android " + device.optString("android_release")
                + " · " + cpuPart
                + (soc.matched ? (zh ? " (" + workers + " 核)" : " (" + workers + " cores)") : "")
                + (gpu.length() > 0 ? " · " + gpu : "");
        deviceSummarySub.setText(line);
    }

    private void renderPreflightRows(JSONObject device) {
        preflightContainer.removeAllViews();
        if (device == null) return;
        boolean zh = I18n.isZh(this);

        int batteryPct = device.optInt("battery_percent", -1);
        boolean plugged = device.optBoolean("plugged", false);
        int tempTenths = device.optInt("battery_temperature_tenths_c", Integer.MIN_VALUE);
        boolean batteryOk = batteryPct < 0 || batteryPct >= 30 || plugged;
        String batVal = (batteryPct >= 0 ? batteryPct + "%" : (zh ? "未知" : "N/A"))
                + (plugged ? (zh ? " 充电" : " Chg") : "")
                + (TemperatureTrace.validTemperature(tempTenths)
                ? String.format(Locale.US, " %.0f\u00A0°C", tempTenths / 10.0) : "");
        preflightContainer.addView(buildPreflightCell(
                zh ? "电源电量" : "Battery", batVal,
                batteryOk ? (zh ? "正常" : "OK") : (zh ? "需充电" : "Low"),
                batteryOk ? MiuiUi.TONE_OK : MiuiUi.TONE_BAD),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        addVerticalPreflightDivider();

        long availMem = device.optLong("available_memory_bytes", -1);
        boolean memOk = !device.optBoolean("low_memory") && availMem >= 192L * 1024 * 1024;
        preflightContainer.addView(buildPreflightCell(
                zh ? "可用内存" : "Free RAM", MiuiUi.formatBytesShort(availMem),
                memOk ? (zh ? "充足" : "OK") : (zh ? "偏低" : "Low"),
                memOk ? MiuiUi.TONE_OK : MiuiUi.TONE_WARN),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        addVerticalPreflightDivider();

        long availStorage = device.optLong("available_storage_bytes", -1);
        boolean storageOk = availStorage >= 48L * 1024 * 1024;
        preflightContainer.addView(buildPreflightCell(
                zh ? "内部存储" : "Storage", MiuiUi.formatBytesShort(availStorage),
                storageOk ? (zh ? "充足" : "OK") : (zh ? "不足" : "Low"),
                storageOk ? MiuiUi.TONE_OK : MiuiUi.TONE_BAD),
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

    private String normalizeUnknown(String value) {
        if (!CpuSocResolver.isKnownArch(value)) return I18n.t(this, "未知", "Unknown");
        return value;
    }

    private void renderDeviceTab(JSONObject device) {
        if (devicePageColumn == null) return;
        devicePageColumn.removeAllViews();
        if (device == null) return;
        boolean zh = I18n.isZh(this);
        String unk = zh ? "未知" : "Unknown";
        JSONObject extra = device.optJSONObject("ui_extra");

        // 1. System & Model
        devicePageColumn.addView(MiuiUi.sectionTitle(this, zh ? "系统与型号" : "System & Model"));
        LinearLayout sysCard = MiuiUi.cardGroup(this);
        String brand = extra != null ? extra.optString("brand") : "";
        String mfg = device.optString("manufacturer");
        String brandText = brand.length() > 0 && !brand.equalsIgnoreCase(mfg) ? mfg + " (" + brand + ")" : mfg;
        sysCard.addView(MiuiUi.kvRow(this, zh ? "品牌 / 制造商" : "Brand / Maker", null, brandText, null, 0));
        MiuiUi.addDivider(this, sysCard);
        sysCard.addView(MiuiUi.kvRow(this, zh ? "设备型号" : "Device Model",
                (zh ? "代号：" : "Codename: ") + device.optString("device"),
                device.optString("model"), null, 0));
        if (extra != null) {
            String board = extra.optString("board");
            String hw = extra.optString("hardware");
            if (board.length() > 0 || hw.length() > 0) {
                MiuiUi.addDivider(this, sysCard);
                sysCard.addView(MiuiUi.kvRow(this, zh ? "主板 / 硬件平台" : "Board / Hardware", null,
                        board + (hw.length() > 0 ? " · " + hw : ""), null, 0));
            }
        }
        MiuiUi.addDivider(this, sysCard);
        String dispId = extra != null ? extra.optString("display_id") : "";
        sysCard.addView(MiuiUi.kvRow(this, zh ? "Android 系统" : "Android OS",
                dispId.length() > 0 ? dispId : null,
                "Android " + device.optString("android_release") + " (API " + device.optInt("sdk") + ")", null, 0));
        if (extra != null) {
            String kernel = extra.optString("kernel_version");
            if (kernel.length() > 0) {
                MiuiUi.addDivider(this, sysCard);
                sysCard.addView(MiuiUi.kvRow(this, zh ? "Linux 内核" : "Linux Kernel",
                        normalizeUnknown(kernel), normalizeUnknown(extra.optString("kernel_arch")), null, 0));
            }
        }
        MiuiUi.addDivider(this, sysCard);
        boolean emu = device.optBoolean("emulator");
        sysCard.addView(MiuiUi.kvRow(this, zh ? "Java 虚拟机" : "Java VM",
                (zh ? "运行环境：" : "Environment: ") + (emu ? (zh ? "模拟器" : "Emulator") : (zh ? "物理真机" : "Physical")),
                "VM " + device.optString("vm"),
                emu ? (zh ? "模拟器" : "Emulator") : (zh ? "真机" : "Device"),
                emu ? MiuiUi.TONE_WARN : MiuiUi.TONE_OK));
        devicePageColumn.addView(sysCard);

        // 2. Processor (CPU)
        devicePageColumn.addView(MiuiUi.sectionTitle(this, zh ? "处理器 (CPU)" : "Processor (CPU)"));
        LinearLayout cpuCard = MiuiUi.cardGroup(this);
        CpuSocResolver.Result soc = CpuSocResolver.resolve(device);
        boolean isEmu = device.optBoolean("emulator");
        String socSub = soc.socName + (soc.sourceNote.length() > 0 ? "\n" + soc.sourceNote : "");
        cpuCard.addView(MiuiUi.kvRow(this, zh ? "处理器型号" : "SoC Model",
                socSub,
                null, isEmu ? (zh ? "模拟器" : "Emulator") : null, isEmu ? MiuiUi.TONE_WARN : 0));
        if (CpuSocResolver.isKnownArch(soc.cpuArch)) {
            MiuiUi.addDivider(this, cpuCard);
            String archSub = soc.cpuArch + (soc.cpuArchDetail.length() > 0 ? "\n" + soc.cpuArchDetail : "");
            cpuCard.addView(MiuiUi.kvRow(this, zh ? "核心微架构" : "Microarchitecture", archSub, null, null, 0));
        }
        MiuiUi.addDivider(this, cpuCard);
        String abi2 = device.optString("abi2");
        String abiText = device.optString("abi") + (abi2.length() > 0 ? " / " + abi2 : "");
        cpuCard.addView(MiuiUi.kvRow(this, zh ? "指令集架构" : "Instruction ABI", null, abiText, null, 0));
        MiuiUi.addDivider(this, cpuCard);
        int workers = CpuTopology.workers(device);
        cpuCard.addView(MiuiUi.kvRow(this, zh ? "核心与在线状态" : "Cores & Online",
                (zh ? "在线：" : "Online: ") + device.optString("cpu_online")
                        + (zh ? " · 呈现：" : " · Present: ") + normalizeUnknown(device.optString("cpu_present")),
                workers + (zh ? "\u00A0核" : "\u00A0cores"), null, 0));
        MiuiUi.addDivider(this, cpuCard);
        cpuCard.addView(MiuiUi.kvRow(this, zh ? "CPU0 频率与调速器" : "CPU0 Clock & Governor",
                (zh ? "调速器：" : "Governor: ") + device.optString("cpu0_governor"),
                MiuiUi.formatKhz(device.optString("cpu0_frequency_khz"))
                        + (zh ? "\n最大 " : "\nMax ") + MiuiUi.formatKhz(device.optString("cpu0_max_frequency_khz")),
                null, 0));
        MiuiUi.addDivider(this, cpuCard);
        String lib = device.optString("native_library", "");
        boolean loaded = lib.length() > 0;
        boolean hasNeon = loaded && lib.contains("librmb.so") && device.optString("abi").startsWith("armeabi");
        boolean isArmNonNeon = loaded && lib.contains("noneon");
        cpuCard.addView(MiuiUi.kvRow(this, zh ? "原生计算库" : "Native Library", null,
                loaded ? lib : (zh ? "按需加载" : "Lazy-loaded"),
                !loaded ? (zh ? "待加载" : "Pending")
                        : (isArmNonNeon ? (zh ? "标量" : "Scalar") : (hasNeon ? "NEON" : (zh ? "原生" : "Native"))),
                !loaded ? MiuiUi.TONE_NEUTRAL : (isArmNonNeon ? MiuiUi.TONE_WARN : MiuiUi.TONE_OK)));
        MiuiUi.addDivider(this, cpuCard);
        cpuCard.addView(MiuiUi.navRow(this,
                zh ? "各核心频率、GL 扩展与 JNI 探针" : "Per-Core Clocks, GL Extensions & JNI Probe",
                null, null, null, 0, this::openCpuProbeSubPage));
        devicePageColumn.addView(cpuCard);

        // 3. Graphics (GPU)
        devicePageColumn.addView(MiuiUi.sectionTitle(this, zh ? "图形处理器 (GPU)" : "Graphics (GPU)"));
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
            String gpuTag = isSoftware ? (zh ? "软件渲染" : "Software")
                    : (isTranslated ? (zh ? "宿主转译" : "Translated") : null);
            int gpuTone = isSoftware ? MiuiUi.TONE_WARN : MiuiUi.TONE_NEUTRAL;
            gpuCard.addView(MiuiUi.kvRow(this, zh ? "GPU 型号" : "GPU Model", gpuDisplayName, null, gpuTag, gpuTone));
            if (!gpuRenderer.equals(gpuDisplayName)) {
                MiuiUi.addDivider(this, gpuCard);
                gpuCard.addView(MiuiUi.kvRow(this, zh ? "GL 渲染器" : "GL Renderer", gpuRenderer, null, null, 0));
            }
            MiuiUi.addDivider(this, gpuCard);
            String vendorSub = !gpuVendor.equals(gpuVendorDisplay) && gpuVendor.length() > 0
                    ? (zh ? "接口提供方：" : "Provider: ") + gpuVendor : null;
            gpuCard.addView(MiuiUi.kvRow(this, zh ? "GPU 厂商" : "GPU Vendor", vendorSub,
                    gpuVendorDisplay.length() > 0 ? gpuVendorDisplay : unk, null, 0));
            MiuiUi.addDivider(this, gpuCard);
            gpuCard.addView(MiuiUi.kvRow(this, zh ? "OpenGL ES 版本" : "OpenGL ES Version",
                    gpuVersion.length() > 0 ? gpuVersion : unk, null, null, 0));
            MiuiUi.addDivider(this, gpuCard);
            String eglVer = normalizeUnknown(extra.optString("egl_version"));
            int maxTex = extra.optInt("gpu_max_texture_size", 0);
            gpuCard.addView(MiuiUi.kvRow(this, zh ? "EGL 与最大纹理" : "EGL & Max Texture",
                    "EGL " + eglVer + (extra.has("egl_vendor") ? " · " + extra.optString("egl_vendor") : ""),
                    maxTex > 0 ? maxTex + "\u00A0px" : unk, null, 0));
            MiuiUi.addDivider(this, gpuCard);
            boolean etc1 = extra.optBoolean("gpu_etc1", false);
            gpuCard.addView(MiuiUi.kvRow(this, zh ? "ETC1 压缩纹理" : "ETC1 Compression", null, null,
                    etc1 ? (zh ? "支持" : "Supported") : (zh ? "不支持" : "Unsupported"),
                    etc1 ? MiuiUi.TONE_OK : MiuiUi.TONE_WARN));
        } else {
            gpuCard.addView(MiuiUi.kvRow(this, zh ? "GPU 型号" : "GPU Model",
                    zh ? "未能通过离线 EGL 上下文探测" : "Offline EGL probe unavailable", unk, null, 0));
        }
        devicePageColumn.addView(gpuCard);

        // 4. Display
        if (extra != null && extra.has("screen_width_px")) {
            devicePageColumn.addView(MiuiUi.sectionTitle(this, zh ? "屏幕显示" : "Display"));
            LinearLayout dispCard = MiuiUi.cardGroup(this);
            int wPx = extra.optInt("screen_width_px");
            int hPx = extra.optInt("screen_height_px");
            float density = (float) extra.optDouble("screen_density", 1.0);
            int wDp = Math.round(wPx / density);
            int hDp = Math.round(hPx / density);
            dispCard.addView(MiuiUi.kvRow(this, zh ? "屏幕分辨率" : "Resolution",
                    wDp + "×" + hDp + "\u00A0dp", wPx + "×" + hPx + "\u00A0px", null, 0));
            MiuiUi.addDivider(this, dispCard);
            int dpi = extra.optInt("screen_density_dpi");
            String inches = extra.optString("screen_inches");
            dispCard.addView(MiuiUi.kvRow(this, zh ? "像素密度与尺寸" : "Density & Size",
                    inches.length() > 0 ? (zh ? "估算尺寸约 " + inches + "\u00A0英寸" : "Est. " + inches + "\u00A0in") : null,
                    dpi + "\u00A0dpi (" + String.format(Locale.US, "%.2fx", density) + ")", null, 0));
            double hz = extra.optDouble("screen_refresh_hz", DisplaySurface.refreshHz);
            if (hz > 0) {
                MiuiUi.addDivider(this, dispCard);
                dispCard.addView(MiuiUi.kvRow(this, zh ? "屏幕刷新率" : "Refresh Rate", null,
                        String.format(Locale.US, "%.1f\u00A0Hz", hz), null, 0));
            }
            devicePageColumn.addView(dispCard);
        }

        // 5. Memory & Storage
        devicePageColumn.addView(MiuiUi.sectionTitle(this, zh ? "内存与存储" : "Memory & Storage"));
        LinearLayout memCard = MiuiUi.cardGroup(this);
        long availMem = device.optLong("available_memory_bytes", 0);
        long totalMem = device.optLong("total_memory_bytes", 0);
        if (totalMem > 0) {
            float usedRatio = Math.max(0f, Math.min(1f, (float) (totalMem - availMem) / (float) totalMem));
            memCard.addView(MiuiUi.metricBarRow(this,
                    zh ? "物理内存 (已用 / 总计)" : "Physical RAM (Used / Total)",
                    MiuiUi.formatBytesShort(totalMem - availMem) + " / " + MiuiUi.formatBytesShort(totalMem),
                    usedRatio, MiuiUi.ORANGE));
            MiuiUi.addDivider(this, memCard);
        }
        long memThreshold = extra != null ? extra.optLong("memory_threshold_bytes", 0) : 0;
        memCard.addView(MiuiUi.kvRow(this, zh ? "可用运行内存" : "Available RAM",
                memThreshold > 0 ? (zh ? "低内存警戒阈值：" : "Low-memory threshold: ") + MiuiUi.formatBytesShort(memThreshold) : null,
                MiuiUi.formatBytesShort(availMem), null, 0));

        long dataTotal = extra != null ? extra.optLong("data_total_bytes", 0) : 0;
        long dataAvail = extra != null ? extra.optLong("data_avail_bytes", device.optLong("available_storage_bytes", 0)) : device.optLong("available_storage_bytes", 0);
        MiuiUi.addDivider(this, memCard);
        if (dataTotal > 0) {
            float usedStore = Math.max(0f, Math.min(1f, (float) (dataTotal - dataAvail) / (float) dataTotal));
            memCard.addView(MiuiUi.metricBarRow(this,
                    zh ? "内部存储 (/data 可用 / 总计)" : "Internal Storage (/data Free / Total)",
                    MiuiUi.formatBytesShort(dataAvail) + " / " + MiuiUi.formatBytesShort(dataTotal),
                    usedStore, MiuiUi.GREEN));
        } else {
            memCard.addView(MiuiUi.kvRow(this, zh ? "内部可用空间" : "Internal Free Space",
                    null, MiuiUi.formatBytesShort(dataAvail), null, 0));
        }
        if (extra != null && extra.has("ext_total_bytes")) {
            long extTotal = extra.optLong("ext_total_bytes", 0);
            long extAvail = extra.optLong("ext_avail_bytes", 0);
            if (extTotal > 0) {
                MiuiUi.addDivider(this, memCard);
                memCard.addView(MiuiUi.kvRow(this, zh ? "外部存储" : "External Storage", null,
                        (zh ? "可用 " : "Free ") + MiuiUi.formatBytesShort(extAvail) + " / " + MiuiUi.formatBytesShort(extTotal), null, 0));
            }
        }
        devicePageColumn.addView(memCard);

        // 6. Battery & Actions
        devicePageColumn.addView(MiuiUi.sectionTitle(this, zh ? "电池与操作" : "Battery & Actions"));
        LinearLayout actionCard = MiuiUi.cardGroup(this);
        int batPct = device.optInt("battery_percent", -1);
        int tempTenths = device.optInt("battery_temperature_tenths_c", Integer.MIN_VALUE);
        boolean plugged = device.optBoolean("plugged");
        StringBuilder batSub = new StringBuilder();
        if (extra != null) {
            int plugType = extra.optInt("battery_plugged_type", 0);
            if (plugged) {
                batSub.append(plugType == BatteryManager.BATTERY_PLUGGED_AC
                        ? (zh ? "AC 充电" : "AC Charging")
                        : (plugType == BatteryManager.BATTERY_PLUGGED_USB
                        ? (zh ? "USB 供电" : "USB Power")
                        : (zh ? "充电中" : "Charging")));
            } else {
                batSub.append(zh ? "电池放电" : "Discharging");
            }
            int mv = extra.optInt("battery_voltage_mv", -1);
            if (mv > 0) batSub.append(String.format(Locale.US, " · %.2f\u00A0V", mv / 1000.0));
            String tech = extra.optString("battery_tech");
            if (tech.length() > 0) batSub.append(" · ").append(tech);
        }
        String batRight = (batPct >= 0 ? batPct + "%" : unk)
                + (TemperatureTrace.validTemperature(tempTenths)
                ? String.format(Locale.US, " · %.1f\u00A0°C", tempTenths / 10.0) : "");
        actionCard.addView(MiuiUi.kvRow(this, zh ? "电池状态" : "Battery Status", batSub.toString(), batRight, null, 0));
        MiuiUi.addDivider(this, actionCard);
        actionCard.addView(MiuiUi.navRow(this, zh ? "刷新硬件信息" : "Refresh Hardware Info",
                null, null, null, 0, () -> refreshDeviceData(true)));
        MiuiUi.addDivider(this, actionCard);
        actionCard.addView(MiuiUi.navRow(this, zh ? "复制设备 JSON" : "Copy Device JSON", null, null, null, 0, () -> {
            String label = zh ? "设备 JSON" : "Device JSON";
            try {
                copyText(label, device.toString(2));
            } catch (JSONException e) {
                copyText(label, device.toString());
            }
        }));
        devicePageColumn.addView(actionCard);
    }

    private void renderRecentQuickCard() {
        recentReportQuickCard.removeAllViews();
        if (activeReport == null) return;
        boolean zh = I18n.isZh(this);
        recentReportQuickCard.addView(MiuiUi.sectionTitle(this, zh ? "最近一次测试" : "Latest Run"));
        LinearLayout card = MiuiUi.cardGroup(this);
        String suiteVer = activeReport.optString("suite_version");
        boolean isSession = BenchmarkSuite.isSession(suiteVer);
        BenchmarkSuite singleSuite = isSession ? null : BenchmarkSuite.fromReport(activeReport);
        String title = isSession ? (zh ? "完整测试套件" : "Full Benchmark Suite")
                : (singleSuite != null ? singleSuite.label() : suiteVer);
        String status = activeReport.optString("status", "failed");

        StringBuilder sub = new StringBuilder();
        if (isSession) {
            JSONArray stages = activeReport.optJSONArray("results");
            int count = stages == null ? 0 : stages.length();
            sub.append(count).append("/").append(BenchmarkSuite.DEFAULT.length)
                    .append(zh ? " 阶段" : " stages");
            long wall = activeReport.optLong("wall_ms", 0);
            if (wall > 0) sub.append(" · ").append(MiuiUi.formatDuration(wall));
        } else if (singleSuite != null) {
            sub.append(MiuiSubPages.stageHeadlineSummary(singleSuite, activeReport));
        }
        card.addView(MiuiUi.navRow(this, title, sub.toString(), zh ? "详情" : "Details",
                BenchmarkReport.statusLabel(status), MiuiUi.statusTone(status),
                () -> openSubPage(title, MiuiSubPages.buildReportDetailPage(this, activeReport, runner.isBusy(), this))));
        recentReportQuickCard.addView(card);
    }

    private void renderScoreTab() {
        if (scorePageColumn == null) return;
        scorePageColumn.removeAllViews();
        boolean zh = I18n.isZh(this);

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
                TextView clearChip = MiuiUi.actionChip(this, zh ? "全部清空" : "Clear All", true, this::confirmClearAllHistory);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = MiuiUi.dp(this, 8);
                headerRow.addView(clearChip, clp);
            }
            TextView manageChip = MiuiUi.actionChip(this,
                    manageHistoryMode ? (zh ? "完成" : "Done") : (zh ? "管理" : "Manage"), false, () -> {
                manageHistoryMode = !manageHistoryMode;
                renderScoreTab();
            });
            headerRow.addView(manageChip);
        }
        scorePageColumn.addView(headerRow);

        if (cachedHistory == null) {
            LinearLayout loading = MiuiUi.cardGroup(this);
            loading.addView(MiuiUi.kvRow(this, zh ? "正在读取测试记录..." : "Loading test history...", null, null, null, 0));
            scorePageColumn.addView(loading);
            return;
        }

        if (totalCount == 0) {
            LinearLayout empty = MiuiUi.cardGroup(this);
            empty.addView(MiuiUi.kvRow(this,
                    zh ? "暂无测试记录" : "No Test History",
                    zh ? "在「跑分」页点击「开始测试」即可生成记录。" : "Tap \"Start Test\" on the Run tab to create a record.",
                    null, null, 0));
            MiuiUi.addDivider(this, empty);
            empty.addView(MiuiUi.navRow(this, zh ? "去跑分" : "Go to Run", null, null, null, 0, () -> switchTab(0)));
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
            final String title = isSession ? (zh ? "完整测试套件" : "Full Benchmark Suite")
                    : (singleSuite != null ? singleSuite.label() : suiteVer);

            String status = item.optString("status", "failed");
            long started = item.optLong("started_ms");
            long wallMs = item.optLong("wall_ms", item.optLong("ended_ms", started) - started);
            String timeStr = started > 0 ? fmt.format(new Date(started)) : (zh ? "未知时间" : "Unknown time");

            StringBuilder sub = new StringBuilder(timeStr);
            if (isSession) {
                JSONArray stages = item.optJSONArray("results");
                int count = stages == null ? 0 : stages.length();
                sub.append(" · ").append(count).append("/").append(BenchmarkSuite.DEFAULT.length)
                        .append(zh ? " 阶段" : " stages");
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
                TextView delBtn = MiuiUi.actionChip(this, zh ? "删除" : "Delete", true, () -> deleteRun(item));
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
        cachedSuiteProtocolPage = null;
        cachedAboutPage = null;
        cachedProjectsPage = null;
        cachedLicenseReaderPage = null;
        super.onDestroy();
    }

    @Override
    public void onState(BenchmarkRunner.State state) {
        boolean zh = I18n.isZh(this);
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
                updateBadge(statusPill, zh ? "运行中" : "Running", MiuiUi.TONE_ACTIVE);
                updateBadge(headerBadge, zh ? "运行中" : "Running", MiuiUi.TONE_ACTIVE);
                lastUiStageIdx = Integer.MIN_VALUE;
            }
            wasBusy = true;

            String headline = zh ? "正在运行固定基准套件" : "Running Fixed Benchmark Suite";
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
                        updateBadge(badge, zh ? "✓ 完成" : "✓ Done", MiuiUi.TONE_OK);
                    } else if (i == state.stageIndex) {
                        String rText = state.repetitions > 0
                                ? (zh ? "第 " + Math.max(1, state.round) + "/" + state.repetitions + " 轮"
                                : "Round " + Math.max(1, state.round) + "/" + state.repetitions)
                                : (zh ? "运行中" : "Running");
                        updateBadge(badge, rText, MiuiUi.TONE_ACTIVE);
                    } else {
                        updateBadge(badge, zh ? "待执行" : "Pending", MiuiUi.TONE_NEUTRAL);
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
                updateBadge(statusPill, zh ? "空闲" : "Idle", MiuiUi.TONE_OK);
                updateBadge(headerBadge, "v" + BuildConfig.VERSION_NAME, MiuiUi.TONE_NEUTRAL);
                statusMessage.setText(R.string.console_subtitle_idle);
                lastUiMessage = null;
                preflightContainer.setVisibility(View.VISIBLE);
                liveProgressBox.setVisibility(View.GONE);
                liveQueueSection.setVisibility(View.GONE);
                idleQuickLinksSection.setVisibility(View.VISIBLE);
            }

            String msg = state.message != null ? state.message : "";
            boolean isHistoryInitMsg = msg.length() == 0
                    || msg.startsWith("本地记录") || msg.startsWith("Local history")
                    || "就绪".equals(msg) || "Ready".equals(msg);
            String headline = isHistoryInitMsg ? (zh ? "准备就绪" : "Ready") : msg;
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
