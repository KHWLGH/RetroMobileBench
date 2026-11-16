package com.retromobilebench;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Pure MIUI V5 (Android 4.1 era) system-settings & security-center visual toolkit.
 * Uses only API 8 Framework View / Drawable / Canvas primitives with zero external dependencies.
 */
final class MiuiUi {
    // MIUI V5 core color palette
    static final int BG_PAGE = 0xFFEFEFF4;
    static final int BG_CARD = 0xFFFFFFFF;
    static final int BG_CARD_PRESSED = 0xFFF2F3F7;
    static final int BG_SUBTLE = 0xFFF7F7FA;
    static final int BORDER_CARD = 0xFFD9D9DE;
    static final int DIVIDER_ROW = 0xFFE8E8EC;

    static final int TITLE_TOP = 0xFF2F3237;
    static final int TITLE_BOTTOM = 0xFF232529;
    static final int TITLE_BORDER = 0xFF16171A;

    static final int TAB_TOP = 0xFFFAFAFC;
    static final int TAB_BOTTOM = 0xFFEEEEF2;
    static final int TAB_BORDER = 0xFFD2D2D8;

    static final int ORANGE = 0xFFFF6700;
    static final int ORANGE_DARK = 0xFFE05500;
    static final int ORANGE_BG = 0xFFFFF2E8;
    static final int ORANGE_BORDER = 0xFFFFC299;

    static final int TEXT_PRIMARY = 0xFF262626;
    static final int TEXT_SECONDARY = 0xFF59595E;
    static final int TEXT_MUTED = 0xFF8C8C94;
    static final int CHEVRON = 0xFFB4B7BF;

    static final int TONE_NEUTRAL = 0;
    static final int TONE_OK = 1;
    static final int TONE_ACTIVE = 2;
    static final int TONE_WARN = 3;
    static final int TONE_BAD = 4;

    static final int GREEN = 0xFF1FA650;
    static final int GREEN_BG = 0xFFE9F8EE;
    static final int GREEN_BORDER = 0xFFA3E2B6;

    static final int AMBER = 0xFFD48300;
    static final int AMBER_BG = 0xFFFFF6E5;
    static final int AMBER_BORDER = 0xFFF6D28B;

    static final int RED = 0xFFDF3832;
    static final int RED_BG = 0xFFFDECEE;
    static final int RED_BORDER = 0xFFF3B2B0;

    private MiuiUi() { }

    static int dp(Context context, float value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.max(value > 0 ? 1 : 0, (int) (value * density + 0.5f));
    }

    static Drawable titleBarBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{TITLE_TOP, TITLE_BOTTOM});
    }

    static Drawable bottomBarBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{TAB_TOP, TAB_BOTTOM});
    }

    static Drawable cardDrawable(Context context) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(BG_CARD);
        shape.setCornerRadius(dp(context, 6));
        shape.setStroke(dp(context, 1), BORDER_CARD);
        return shape;
    }

    private static final Paint SHARED_FILL_PAINT = new Paint();
    private static final Paint SHARED_STROKE_PAINT = new Paint();
    private static final Paint SHARED_BADGE_FILL_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint SHARED_BADGE_STROKE_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint SHARED_CHEVRON_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF SHARED_RECT = new RectF();

    static {
        SHARED_FILL_PAINT.setStyle(Paint.Style.FILL);
        SHARED_STROKE_PAINT.setStyle(Paint.Style.FILL);
        SHARED_BADGE_FILL_PAINT.setStyle(Paint.Style.FILL);
        SHARED_BADGE_STROKE_PAINT.setStyle(Paint.Style.STROKE);
        SHARED_CHEVRON_PAINT.setStyle(Paint.Style.STROKE);
        SHARED_CHEVRON_PAINT.setColor(CHEVRON);
        SHARED_CHEVRON_PAINT.setStrokeCap(Paint.Cap.ROUND);
        SHARED_CHEVRON_PAINT.setStrokeJoin(Paint.Join.ROUND);
    }

    static Drawable badgeDrawable(Context context, int tone) {
        return new BadgeDrawable(tone, dp(context, 10), dp(context, 1));
    }

    /** Lightweight shared-Paint badge pill drawable (eliminates per-badge GradientDrawable allocations). */
    private static final class BadgeDrawable extends Drawable {
        private final int bg;
        private final int border;
        private final float radius;
        private final float stroke;

        BadgeDrawable(int tone, float radius, float stroke) {
            if (tone == TONE_OK) { bg = GREEN_BG; border = GREEN_BORDER; }
            else if (tone == TONE_ACTIVE) { bg = ORANGE_BG; border = ORANGE_BORDER; }
            else if (tone == TONE_WARN) { bg = AMBER_BG; border = AMBER_BORDER; }
            else if (tone == TONE_BAD) { bg = RED_BG; border = RED_BORDER; }
            else { bg = BG_SUBTLE; border = BORDER_CARD; }
            this.radius = radius;
            this.stroke = stroke;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            float inset = stroke * 0.5f;
            SHARED_RECT.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            SHARED_BADGE_FILL_PAINT.setColor(bg);
            canvas.drawRoundRect(SHARED_RECT, radius, radius, SHARED_BADGE_FILL_PAINT);
            SHARED_BADGE_STROKE_PAINT.setColor(border);
            SHARED_BADGE_STROKE_PAINT.setStrokeWidth(stroke);
            canvas.drawRoundRect(SHARED_RECT, radius, radius, SHARED_BADGE_STROKE_PAINT);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static int badgeTextColor(int tone) {
        if (tone == TONE_OK) return GREEN;
        if (tone == TONE_ACTIVE) return ORANGE;
        if (tone == TONE_WARN) return AMBER;
        if (tone == TONE_BAD) return RED;
        return TEXT_SECONDARY;
    }

    static int statusTone(String status) {
        if ("completed".equals(status)) return TONE_OK;
        if ("running".equals(status)) return TONE_ACTIVE;
        if ("partial".equals(status) || "skipped".equals(status) || "unsupported".equals(status)) return TONE_WARN;
        if ("cancelled".equals(status) || "interrupted".equals(status) || "failed".equals(status) || "timeout".equals(status)) return TONE_BAD;
        return TONE_NEUTRAL;
    }

    static Drawable headerBackButtonDrawable(Context context) {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x18FFFFFF);
        normal.setCornerRadius(dp(context, 4));
        normal.setStroke(dp(context, 1), 0x38FFFFFF);

        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0x36FFFFFF);
        pressed.setCornerRadius(dp(context, 4));
        pressed.setStroke(dp(context, 1), 0x55FFFFFF);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{}, normal);
        return states;
    }

    static Drawable buttonDrawable(Context context, boolean primary, boolean danger) {
        int r = dp(context, 6);
        GradientDrawable disabled = new GradientDrawable();
        disabled.setColor(0xFFDCDCE0);
        disabled.setCornerRadius(r);
        disabled.setStroke(dp(context, 1), 0xFFC8C8CE);

        GradientDrawable normal;
        GradientDrawable pressed;
        if (danger) {
            normal = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFFF04842, 0xFFD9342E});
            normal.setCornerRadius(r);
            normal.setStroke(dp(context, 1), 0xFFB82620);
            pressed = new GradientDrawable();
            pressed.setColor(0xFFC22B25);
            pressed.setCornerRadius(r);
        } else if (primary) {
            normal = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFFFF761A, ORANGE});
            normal.setCornerRadius(r);
            normal.setStroke(dp(context, 1), ORANGE_DARK);
            pressed = new GradientDrawable();
            pressed.setColor(ORANGE_DARK);
            pressed.setCornerRadius(r);
        } else {
            normal = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFFFFFFFF, 0xFFF2F2F6});
            normal.setCornerRadius(r);
            normal.setStroke(dp(context, 1), 0xFFCCCCD2);
            pressed = new GradientDrawable();
            pressed.setColor(0xFFE4E5EA);
            pressed.setCornerRadius(r);
            pressed.setStroke(dp(context, 1), 0xFFBFBFC6);
        }

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, disabled);
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{}, normal);
        return states;
    }

    static Drawable rowClickDrawable() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(BG_CARD_PRESSED));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    static Drawable segmentDrawable(Context context, boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(context, 5));
        if (selected) {
            shape.setColor(ORANGE);
        } else {
            shape.setColor(BG_CARD);
            shape.setStroke(dp(context, 1), BORDER_CARD);
        }
        return shape;
    }

    @SuppressWarnings("deprecation")
    static void setBackground(View view, Drawable drawable) {
        view.setBackgroundDrawable(drawable);
    }

    static TextView sectionTitle(Context context, String label) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(TEXT_MUTED);
        int hPad = dp(context, 16);
        tv.setPadding(hPad, dp(context, 12), hPad, dp(context, 5));
        return tv;
    }

    static LinearLayout cardGroup(Context context) {
        CardLayout card = new CardLayout(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int marginH = dp(context, 12);
        lp.setMargins(marginH, 0, marginH, dp(context, 4));
        card.setLayoutParams(lp);
        return card;
    }

    static void addDivider(Context context, LinearLayout card) {
        if (card instanceof CardLayout) {
            ((CardLayout) card).markDivider();
            return;
        }
        View line = new View(context);
        line.setBackgroundColor(DIVIDER_ROW);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(context, 14);
        card.addView(line, lp);
    }

    /**
     * Flat MIUI V5 card container: draws crisp card fill, 1px hairline border, and inline row
     * dividers via a single Canvas pass (zero GradientDrawable path masks and zero divider Views).
     */
    static final class CardLayout extends LinearLayout {
        private final int borderPx;
        private final int dividerInsetPx;
        private int[] dividerAfterIndex = new int[16];
        private int dividerCount;

        CardLayout(Context context) {
            super(context);
            setOrientation(LinearLayout.VERTICAL);
            setBaselineAligned(false);
            setWillNotDraw(false);
            borderPx = Math.max(1, dp(context, 1));
            dividerInsetPx = dp(context, 14);
            setPadding(borderPx, borderPx, borderPx, borderPx);
        }

        void markDivider() {
            int childIdx = getChildCount();
            if (childIdx <= 0) return;
            if (dividerCount == dividerAfterIndex.length) {
                int[] grown = new int[dividerAfterIndex.length * 2];
                System.arraycopy(dividerAfterIndex, 0, grown, 0, dividerCount);
                dividerAfterIndex = grown;
            }
            dividerAfterIndex[dividerCount++] = childIdx;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            SHARED_FILL_PAINT.setColor(BG_CARD);
            canvas.drawRect(borderPx, borderPx, w - borderPx, h - borderPx, SHARED_FILL_PAINT);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            int childCount = getChildCount();
            if (dividerCount > 0) {
                SHARED_STROKE_PAINT.setColor(DIVIDER_ROW);
                int right = w - borderPx;
                for (int i = 0; i < dividerCount; i++) {
                    int idx = dividerAfterIndex[i];
                    if (idx > 0 && idx < childCount) {
                        View child = getChildAt(idx);
                        if (child.getVisibility() != GONE) {
                            int y = child.getTop();
                            canvas.drawRect(dividerInsetPx, y, right, y + 1, SHARED_STROKE_PAINT);
                        }
                    }
                }
            }
            SHARED_STROKE_PAINT.setColor(BORDER_CARD);
            canvas.drawRect(0, 0, w, borderPx, SHARED_STROKE_PAINT);
            canvas.drawRect(0, h - borderPx, w, h, SHARED_STROKE_PAINT);
            canvas.drawRect(0, borderPx, borderPx, h - borderPx, SHARED_STROKE_PAINT);
            canvas.drawRect(w - borderPx, borderPx, w, h - borderPx, SHARED_STROKE_PAINT);
        }
    }

    static TextView badge(Context context, String text, int tone) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(badgeTextColor(tone));
        setBackground(tv, badgeDrawable(context, tone));
        int h = dp(context, 8), v = dp(context, 2);
        tv.setPadding(h, v, h, v);
        tv.setSingleLine(true);
        return tv;
    }

    static Button styledButton(Context context, String text, boolean primary, boolean danger, Runnable action) {
        Button b = new Button(context);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary || danger ? 15 : 13);
        b.setTypeface(primary || danger ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        b.setTextColor(primary || danger ? Color.WHITE : TEXT_PRIMARY);
        setBackground(b, buttonDrawable(context, primary, danger));
        int vPad = dp(context, primary || danger ? 10 : 8);
        int hPad = dp(context, 12);
        b.setPadding(hPad, vPad, hPad, vPad);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        if (action != null) b.setOnClickListener(v -> action.run());
        return b;
    }

    static TextView actionChip(Context context, String text, boolean danger, Runnable onClick) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(danger ? RED : ORANGE);
        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(context, 4));
        normal.setColor(danger ? RED_BG : ORANGE_BG);
        normal.setStroke(dp(context, 1), danger ? RED_BORDER : ORANGE_BORDER);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setCornerRadius(dp(context, 4));
        pressed.setColor(danger ? 0xFFF9D6D9 : 0xFFFFE2CC);
        pressed.setStroke(dp(context, 1), danger ? RED : ORANGE);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, pressed);
        bg.addState(new int[]{}, normal);
        setBackground(tv, bg);
        int h = dp(context, 10), v = dp(context, 4);
        tv.setPadding(h, v, h, v);
        tv.setSingleLine(true);
        if (onClick != null) {
            tv.setClickable(true);
            tv.setOnClickListener(view -> onClick.run());
        }
        return tv;
    }

    /** Standard MIUI V5 key-value setting item row inside a card (flat single-level ViewGroup). */
    static MiuiRowLayout kvRow(Context context, String title, String subtitle, String rightValue, String badgeText, int badgeTone) {
        return buildRow(context, title, subtitle, rightValue, badgeText, badgeTone, false, false, null);
    }

    /** Full-width MIUI V5 summary row for multi-metric benchmark highlights (never clips units). */
    static LinearLayout summaryRow(Context context, String title, String resultText, String workloadSubtitle) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBaselineAligned(false);
        int hPad = dp(context, 14), vPad = dp(context, 10);
        box.setPadding(hPad, vPad, hPad, vPad);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(TEXT_PRIMARY);
        box.addView(titleView);

        if (resultText != null && resultText.length() > 0) {
            TextView resultView = new TextView(context);
            resultView.setText(resultText);
            resultView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            resultView.setTypeface(Typeface.DEFAULT_BOLD);
            resultView.setTextColor(ORANGE_DARK);
            resultView.setLineSpacing(dp(context, 2), 1f);
            resultView.setPadding(0, dp(context, 3), 0, 0);
            box.addView(resultView);
        }

        if (workloadSubtitle != null && workloadSubtitle.length() > 0) {
            TextView subView = new TextView(context);
            subView.setText(workloadSubtitle);
            subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            subView.setTextColor(TEXT_MUTED);
            subView.setLineSpacing(dp(context, 1.5f), 1f);
            subView.setPadding(0, dp(context, 3), 0, 0);
            box.addView(subView);
        }
        return box;
    }

    /** Compact single-metric sub-row with non-wrapping right-aligned value+unit. */
    static MiuiRowLayout compactMetricRow(Context context, String label, String valueText) {
        return buildRow(context, label, null, valueText, null, 0, true, false, null);
    }

    /** Clickable MIUI V5 drill-down row with right-hand summary, optional badge, and inline › chevron. */
    static MiuiRowLayout navRow(Context context, String title, String subtitle, String rightValue,
                                String badgeText, int badgeTone, Runnable onClick) {
        return buildRow(context, title, subtitle, rightValue, badgeText, badgeTone, false, true, onClick);
    }

    @SuppressLint("RtlHardcoded")
    private static MiuiRowLayout buildRow(Context context, String title, String subtitle, String rightValue,
                                          String badgeText, int badgeTone, boolean compact, boolean showChevron,
                                          Runnable onClick) {
        TextView titleView = new TextView(context);
        titleView.setText(title);
        if (compact) {
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            titleView.setTextColor(TEXT_SECONDARY);
        } else {
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            titleView.setTextColor(TEXT_PRIMARY);
        }
        titleView.setLineSpacing(dp(context, 1.5f), 1f);

        TextView subView = null;
        if (!compact && subtitle != null && subtitle.length() > 0) {
            subView = new TextView(context);
            subView.setText(subtitle);
            subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            subView.setTextColor(TEXT_MUTED);
            subView.setLineSpacing(dp(context, 1.5f), 1f);
        }

        TextView valView = null;
        if (rightValue != null && rightValue.length() > 0) {
            valView = new TextView(context);
            valView.setText(rightValue);
            valView.setGravity(Gravity.RIGHT);
            if (compact) {
                valView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                valView.setTypeface(Typeface.MONOSPACE);
                valView.setTextColor(TEXT_PRIMARY);
                valView.setSingleLine(true);
            } else {
                valView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                valView.setTextColor(TEXT_SECONDARY);
                valView.setLineSpacing(dp(context, 1.5f), 1f);
            }
        }

        TextView pill = (badgeText != null && badgeText.length() > 0) ? badge(context, badgeText, badgeTone) : null;
        int rightMaxW = compact ? dp(context, 220) : dp(context, (subView != null) ? 180 : 200);

        MiuiRowLayout row = new MiuiRowLayout(context, titleView, subView, valView, pill, compact, showChevron, rightMaxW);
        if (onClick != null) {
            row.setClickable(true);
            row.setOnClickListener(v -> onClick.run());
        }
        return row;
    }

    /**
     * Single-level flat row ViewGroup: eliminates inner LinearLayout, layout_weight dual-pass
     * measurement, StateListDrawable per-row objects, and separate chevron TextViews.
     */
    @SuppressLint("ViewConstructor")
    static final class MiuiRowLayout extends ViewGroup {
        private final TextView titleView;
        private final TextView subtitleView;
        private final TextView rightView;
        private final TextView badgeView;
        private final boolean showChevron;
        private final int hPad, vPad, subTopGap, rightGap, badgeGap, chevronGap, chevronW, chevronH, rightMaxW;
        private final float chevronStroke;

        MiuiRowLayout(Context context, TextView titleView, TextView subtitleView, TextView rightView,
                      TextView badgeView, boolean compact, boolean showChevron, int rightMaxW) {
            super(context);
            this.titleView = titleView;
            this.subtitleView = subtitleView;
            this.rightView = rightView;
            this.badgeView = badgeView;
            this.showChevron = showChevron;
            this.rightMaxW = rightMaxW;
            this.hPad = dp(context, 14);
            this.vPad = dp(context, compact ? 4 : 10);
            this.subTopGap = dp(context, 2);
            this.rightGap = dp(context, 8);
            this.badgeGap = dp(context, 6);
            this.chevronGap = dp(context, 8);
            this.chevronW = dp(context, 6);
            this.chevronH = dp(context, 10);
            this.chevronStroke = Math.max(1.5f, dp(context, 1.6f));

            addView(titleView);
            if (subtitleView != null) addView(subtitleView);
            if (rightView != null) addView(rightView);
            if (badgeView != null) addView(badgeView);
            if (showChevron) setWillNotDraw(false);
        }

        TextView titleView() { return titleView; }
        TextView subtitleView() { return subtitleView; }
        TextView badgeView() { return badgeView; }

        @Override
        public void setClickable(boolean clickable) {
            super.setClickable(clickable);
            if (clickable) setWillNotDraw(false);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (isClickable()) invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int totalW = MeasureSpec.getSize(widthMeasureSpec);
            int availW = Math.max(0, totalW - hPad * 2);
            int rightUsed = showChevron ? (chevronW + chevronGap) : 0;

            int rightH = 0;
            if (badgeView != null && badgeView.getVisibility() != GONE) {
                int maxBadge = Math.max(0, availW - rightUsed);
                badgeView.measure(MeasureSpec.makeMeasureSpec(maxBadge, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                rightUsed += badgeView.getMeasuredWidth() + badgeGap;
                rightH = Math.max(rightH, badgeView.getMeasuredHeight());
            }
            if (rightView != null && rightView.getVisibility() != GONE) {
                int maxVal = Math.min(rightMaxW, Math.max(0, availW - rightUsed));
                rightView.measure(MeasureSpec.makeMeasureSpec(maxVal, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                rightUsed += rightView.getMeasuredWidth() + rightGap;
                rightH = Math.max(rightH, rightView.getMeasuredHeight());
            }

            int leftW = Math.max(0, availW - rightUsed);
            int leftWSpec = MeasureSpec.makeMeasureSpec(leftW, MeasureSpec.EXACTLY);
            int unspec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            titleView.measure(leftWSpec, unspec);
            int leftH = titleView.getMeasuredHeight();
            if (subtitleView != null && subtitleView.getVisibility() != GONE) {
                subtitleView.measure(leftWSpec, unspec);
                leftH += subTopGap + subtitleView.getMeasuredHeight();
            }

            int contentH = Math.max(leftH, Math.max(rightH, showChevron ? chevronH : 0));
            setMeasuredDimension(totalW, resolveSize(vPad * 2 + contentH, heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, h = b - t;
            int contentTop = vPad;
            int contentBottom = h - vPad;
            int contentCenterY = (contentTop + contentBottom) / 2;

            int leftH = titleView.getMeasuredHeight()
                    + (subtitleView != null && subtitleView.getVisibility() != GONE
                    ? subTopGap + subtitleView.getMeasuredHeight() : 0);
            int y = contentTop + Math.max(0, (contentBottom - contentTop - leftH) / 2);
            titleView.layout(hPad, y, hPad + titleView.getMeasuredWidth(), y + titleView.getMeasuredHeight());
            if (subtitleView != null && subtitleView.getVisibility() != GONE) {
                int sy = y + titleView.getMeasuredHeight() + subTopGap;
                subtitleView.layout(hPad, sy, hPad + subtitleView.getMeasuredWidth(), sy + subtitleView.getMeasuredHeight());
            }

            int rx = w - hPad;
            if (showChevron) rx -= chevronW + chevronGap;
            if (badgeView != null && badgeView.getVisibility() != GONE) {
                int bw = badgeView.getMeasuredWidth(), bh = badgeView.getMeasuredHeight();
                int by = contentCenterY - bh / 2;
                badgeView.layout(rx - bw, by, rx, by + bh);
                rx -= bw + badgeGap;
            }
            if (rightView != null && rightView.getVisibility() != GONE) {
                int vw = rightView.getMeasuredWidth(), vh = rightView.getMeasuredHeight();
                int vy = contentCenterY - vh / 2;
                rightView.layout(rx - vw, vy, rx, vy + vh);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (isClickable() && isPressed()) {
                SHARED_FILL_PAINT.setColor(BG_CARD_PRESSED);
                canvas.drawRect(0, 0, w, h, SHARED_FILL_PAINT);
            }
            if (showChevron) {
                float rightX = w - hPad;
                float leftX = rightX - chevronW;
                float cy = h * 0.5f;
                float halfH = chevronH * 0.5f;
                SHARED_CHEVRON_PAINT.setStrokeWidth(chevronStroke);
                canvas.drawLine(leftX, cy - halfH, rightX, cy, SHARED_CHEVRON_PAINT);
                canvas.drawLine(rightX, cy, leftX, cy + halfH, SHARED_CHEVRON_PAINT);
            }
        }
    }

    /** Metric row with label, numeric value + unit, and inline Canvas progress bar (flat 2-ViewGroup-free layout). */
    @SuppressLint("RtlHardcoded")
    static View metricBarRow(Context context, String label, String valueText, float fraction, int barColor) {
        TextView left = new TextView(context);
        left.setText(label);
        left.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        left.setTextColor(TEXT_PRIMARY);
        left.setMaxLines(2);
        left.setEllipsize(TextUtils.TruncateAt.END);
        left.setLineSpacing(dp(context, 1.5f), 1f);

        TextView right = new TextView(context);
        right.setText(valueText);
        right.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        right.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        right.setTextColor(TEXT_PRIMARY);
        right.setGravity(Gravity.RIGHT);
        right.setSingleLine(true);

        return new MiuiMetricBarLayout(context, left, right, fraction, barColor);
    }

    @SuppressLint("ViewConstructor")
    private static final class MiuiMetricBarLayout extends ViewGroup {
        private final TextView left;
        private final TextView right;
        private final float fraction;
        private final int fillColor;
        private final int hPad, vPad, gapPx, barTopMargin, barHeightPx;

        MiuiMetricBarLayout(Context context, TextView left, TextView right, float fraction, int fillColor) {
            super(context);
            this.left = left;
            this.right = right;
            this.fraction = Math.min(1f, Math.max(0f, fraction));
            this.fillColor = fillColor;
            this.hPad = dp(context, 14);
            this.vPad = dp(context, 10);
            this.gapPx = dp(context, 8);
            this.barTopMargin = dp(context, 6);
            this.barHeightPx = dp(context, 6);
            setWillNotDraw(false);
            addView(left);
            addView(right);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int totalW = MeasureSpec.getSize(widthMeasureSpec);
            int availW = Math.max(0, totalW - hPad * 2);
            int unspec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            right.measure(MeasureSpec.makeMeasureSpec(availW, MeasureSpec.AT_MOST), unspec);
            int leftW = Math.max(0, availW - right.getMeasuredWidth() - gapPx);
            left.measure(MeasureSpec.makeMeasureSpec(leftW, MeasureSpec.EXACTLY), unspec);
            int topH = Math.max(left.getMeasuredHeight(), right.getMeasuredHeight());
            int totalH = vPad * 2 + topH + barTopMargin + barHeightPx;
            setMeasuredDimension(totalW, resolveSize(totalH, heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l;
            int topH = Math.max(left.getMeasuredHeight(), right.getMeasuredHeight());
            int ly = vPad + (topH - left.getMeasuredHeight()) / 2;
            left.layout(hPad, ly, hPad + left.getMeasuredWidth(), ly + left.getMeasuredHeight());
            int rw = right.getMeasuredWidth();
            int ry = vPad + (topH - right.getMeasuredHeight()) / 2;
            right.layout(w - hPad - rw, ry, w - hPad, ry + right.getMeasuredHeight());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= hPad * 2 || h <= 0) return;
            float top = h - vPad - barHeightPx;
            float bottom = h - vPad;
            float r = barHeightPx * 0.5f;
            SHARED_RECT.set(hPad, top, w - hPad, bottom);
            SHARED_BADGE_FILL_PAINT.setColor(0xFFE6E7EC);
            canvas.drawRoundRect(SHARED_RECT, r, r, SHARED_BADGE_FILL_PAINT);
            if (fraction > 0f) {
                float trackW = w - hPad * 2;
                float fillW = Math.max(barHeightPx, trackW * fraction);
                SHARED_RECT.set(hPad, top, Math.min(w - hPad, hPad + fillW), bottom);
                SHARED_BADGE_FILL_PAINT.setColor(fillColor);
                canvas.drawRoundRect(SHARED_RECT, r, r, SHARED_BADGE_FILL_PAINT);
            }
        }
    }

    /** Lightweight API 8 horizontal rounded progress bar drawn via Canvas. */
    @SuppressLint("ViewConstructor")
    static final class ProgressBarView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float fraction;
        private int fillColor = ORANGE;
        private int lastFillPx = -1;
        private final int barHeightPx;

        ProgressBarView(Context context, int barHeightPx) {
            super(context);
            this.barHeightPx = barHeightPx;
        }

        void setProgress(float value, int color) {
            float clamped = Math.min(1f, Math.max(0f, value));
            int w = getWidth();
            int fillPx = w > 0 ? Math.round(clamped * w) : Math.round(clamped * 1000f);
            if (fillPx != lastFillPx || fillColor != color) {
                fraction = clamped;
                fillColor = color;
                lastFillPx = fillPx;
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = resolveSize(dp(getContext(), 120), widthMeasureSpec);
            setMeasuredDimension(w, barHeightPx);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = h / 2f;
            rect.set(0, 0, w, h);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFE6E7EC);
            canvas.drawRoundRect(rect, r, r, paint);
            if (fraction > 0f) {
                float fillW = Math.max(h, w * fraction);
                rect.set(0, 0, Math.min(w, fillW), h);
                paint.setColor(fillColor);
                canvas.drawRoundRect(rect, r, r, paint);
            }
        }
    }

    /** Custom MIUI V5 vector-style line icon for the 4 bottom navigation tabs. */
    @SuppressLint("ViewConstructor")
    static final class TabIconView extends View {
        static final int ICON_RUN = 0;
        static final int ICON_SCORE = 1;
        static final int ICON_DEVICE = 2;
        static final int ICON_MORE = 3;

        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private boolean active;

        TabIconView(Context context, int kind) {
            super(context);
            this.kind = kind;
        }

        void setActive(boolean value) {
            if (active != value) {
                active = value;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            int color = active ? ORANGE : 0xFF7B7E87;
            float stroke = Math.max(2f, w * 0.095f);
            paint.setColor(color);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            float pad = stroke * 1.4f;
            if (kind == ICON_RUN) {
                // Speedometer gauge
                paint.setStyle(Paint.Style.STROKE);
                oval.set(pad, pad, w - pad, h - pad);
                canvas.drawArc(oval, 145, 250, false, paint);
                float cx = w * 0.5f, cy = h * 0.56f;
                float nx = w * 0.72f, ny = h * 0.30f;
                canvas.drawLine(cx, cy, nx, ny, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, stroke * 0.9f, paint);
            } else if (kind == ICON_SCORE) {
                // 3-bar ascending chart
                paint.setStyle(Paint.Style.STROKE);
                oval.set(pad, pad, w - pad, h - pad);
                canvas.drawRoundRect(oval, stroke * 1.5f, stroke * 1.5f, paint);
                float bottom = h - pad * 1.8f;
                canvas.drawLine(w * 0.32f, bottom, w * 0.32f, h * 0.58f, paint);
                canvas.drawLine(w * 0.50f, bottom, w * 0.50f, h * 0.42f, paint);
                canvas.drawLine(w * 0.68f, bottom, w * 0.68f, h * 0.28f, paint);
            } else if (kind == ICON_DEVICE) {
                // Smartphone / hardware outline
                paint.setStyle(Paint.Style.STROKE);
                oval.set(w * 0.24f, pad, w * 0.76f, h - pad);
                canvas.drawRoundRect(oval, stroke * 1.4f, stroke * 1.4f, paint);
                canvas.drawLine(w * 0.42f, pad + stroke * 1.4f, w * 0.58f, pad + stroke * 1.4f, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(w * 0.5f, h - pad - stroke * 1.5f, stroke * 0.65f, paint);
            } else if (kind == ICON_MORE) {
                // 2x2 rounded grid (MIUI more/settings blocks)
                paint.setStyle(Paint.Style.STROKE);
                float midX = w * 0.5f, midY = h * 0.5f, gap = stroke * 0.9f;
                oval.set(pad, pad, midX - gap, midY - gap);
                canvas.drawRoundRect(oval, stroke, stroke, paint);
                oval.set(midX + gap, pad, w - pad, midY - gap);
                canvas.drawRoundRect(oval, stroke, stroke, paint);
                oval.set(pad, midY + gap, midX - gap, h - pad);
                canvas.drawRoundRect(oval, stroke, stroke, paint);
                oval.set(midX + gap, midY + gap, w - pad, h - pad);
                canvas.drawRoundRect(oval, stroke, stroke, paint);
            }
        }
    }

    static String formatBytes(long bytes) {
        if (bytes <= 0) return "未知";
        double mib = bytes / (1024.0 * 1024.0);
        if (mib >= 1024.0) return String.format(java.util.Locale.US, "%.2f\u00A0GiB (%.0f\u00A0MiB)", mib / 1024.0, mib);
        return String.format(java.util.Locale.US, "%.1f\u00A0MiB", mib);
    }

    static String formatBytesShort(long bytes) {
        if (bytes <= 0) return "未知";
        double mib = bytes / (1024.0 * 1024.0);
        if (mib >= 1024.0) return String.format(java.util.Locale.US, "%.2f\u00A0GiB", mib / 1024.0);
        return String.format(java.util.Locale.US, "%.0f\u00A0MiB", mib);
    }

    static String formatDuration(long ms) {
        if (ms <= 0) return "—";
        if (ms < 1000) return ms + "\u00A0ms";
        double totalSeconds = ms / 1000.0;
        int minutes = (int) (totalSeconds / 60);
        double seconds = totalSeconds - minutes * 60;
        if (minutes > 0) return String.format(java.util.Locale.US, "%d\u00A0分\u00A0%.1f\u00A0秒", minutes, seconds);
        return String.format(java.util.Locale.US, "%.2f\u00A0秒", totalSeconds);
    }

    static String formatKhz(String khzText) {
        if (khzText == null || "unknown".equals(khzText) || khzText.length() == 0) return "未知";
        try {
            long khz = Long.parseLong(khzText.trim());
            if (khz <= 0) return khzText;
            return String.format(java.util.Locale.US, "%.0f\u00A0MHz", khz / 1000.0);
        } catch (NumberFormatException e) {
            return khzText;
        }
    }

    static Drawable toastBackground(Context context) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xEB26282E);
        shape.setCornerRadius(dp(context, 18));
        shape.setStroke(dp(context, 1), 0x2EFFFFFF);
        return shape;
    }

    /**
     * In-app MIUI V5 modal confirmation overlay hosted inside the Activity's root FrameLayout,
     * completely avoiding separate Dialog window surfaces and legacy black-frame artifacts.
     */
    static FrameLayout buildConfirmOverlay(Context context, String title, String message,
                                           String confirmLabel, boolean danger,
                                           Runnable onDismiss, Runnable onConfirm) {
        FrameLayout scrim = new FrameLayout(context);
        scrim.setBackgroundColor(0x73000000);
        scrim.setClickable(true);
        scrim.setFocusable(true);
        int hMargin = dp(context, 28);
        int vMargin = dp(context, 24);
        scrim.setPadding(hMargin, vMargin, hMargin, vMargin);
        scrim.setOnClickListener(v -> {
            if (onDismiss != null) onDismiss.run();
        });

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(BG_CARD);
        cardBg.setCornerRadius(dp(context, 10));
        cardBg.setStroke(dp(context, 1), BORDER_CARD);
        setBackground(card, cardBg);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(TEXT_PRIMARY);
        titleView.setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 12));
        card.addView(titleView);

        View divider = new View(context);
        divider.setBackgroundColor(DIVIDER_ROW);
        card.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));

        TextView msgView = new TextView(context);
        msgView.setText(message);
        msgView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        msgView.setTextColor(TEXT_SECONDARY);
        msgView.setLineSpacing(dp(context, 2), 1.15f);
        msgView.setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 18));
        card.addView(msgView);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setBaselineAligned(false);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(context, 16), 0, dp(context, 16), dp(context, 16));

        int btnHeight = dp(context, 42);
        Button cancelBtn = styledButton(context, "取消", false, false, () -> {
            if (onDismiss != null) onDismiss.run();
        });
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cancelBtn.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        actions.addView(cancelBtn, new LinearLayout.LayoutParams(0, btnHeight, 1f));

        View spacer = new View(context);
        actions.addView(spacer, new LinearLayout.LayoutParams(dp(context, 10), 1));

        Button confirmBtn = styledButton(context, confirmLabel, !danger, danger, () -> {
            if (onDismiss != null) onDismiss.run();
            if (onConfirm != null) onConfirm.run();
        });
        confirmBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        confirmBtn.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        actions.addView(confirmBtn, new LinearLayout.LayoutParams(0, btnHeight, 1f));

        card.addView(actions);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        scrim.addView(card, cardLp);
        return scrim;
    }

    static String ellipsizeMiddle(String value, int keepStart, int keepEnd) {
        if (value == null || value.length() <= keepStart + keepEnd + 3) return value == null ? "" : value;
        return value.substring(0, keepStart) + "..." + value.substring(value.length() - keepEnd);
    }
}
