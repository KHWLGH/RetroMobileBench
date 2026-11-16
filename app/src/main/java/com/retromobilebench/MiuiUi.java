package com.retromobilebench;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

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

    interface ExportChoiceListener {
        void onSaveLocal(ReportExporter.Format format);
        void onSaveAndShare(ReportExporter.Format format);
        void onSaveAsSaf(ReportExporter.Format format);
        void onCopySummary();
    }

    /** In-app MIUI V5 export format & destination selector overlay. */
    static FrameLayout buildExportOptionsOverlay(Context context, boolean hasQuickSummary,
                                                 Runnable onDismiss, ExportChoiceListener listener) {
        FrameLayout scrim = new FrameLayout(context);
        scrim.setBackgroundColor(0x73000000);
        scrim.setClickable(true);
        scrim.setFocusable(true);
        int hMargin = dp(context, 22);
        int vMargin = dp(context, 20);
        scrim.setPadding(hMargin, vMargin, hMargin, vMargin);
        scrim.setOnClickListener(v -> { if (onDismiss != null) onDismiss.run(); });

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(BG_CARD);
        cardBg.setCornerRadius(dp(context, 10));
        cardBg.setStroke(dp(context, 1), BORDER_CARD);
        setBackground(card, cardBg);

        TextView titleView = new TextView(context);
        titleView.setText("导出完整测试数据");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(TEXT_PRIMARY);
        titleView.setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 10));
        card.addView(titleView);

        TextView subView = new TextView(context);
        subView.setText("导出文件包含全部原始采样、硬件快照与温升/电量时间序列曲线，适配下载目录保存、USB/MTP 读取与系统分享。");
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subView.setTextColor(TEXT_SECONDARY);
        subView.setLineSpacing(dp(context, 1.5f), 1.05f);
        subView.setPadding(dp(context, 18), 0, dp(context, 18), dp(context, 12));
        card.addView(subView);

        View divider = new View(context);
        divider.setBackgroundColor(DIVIDER_ROW);
        card.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));

        final ReportExporter.Format[] formats = ReportExporter.Format.values();
        final int[] selectedIndex = {0};
        final Button[] segButtons = new Button[formats.length];

        LinearLayout fmtBox = new LinearLayout(context);
        fmtBox.setOrientation(LinearLayout.VERTICAL);
        fmtBox.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 8));

        TextView fmtLabel = new TextView(context);
        fmtLabel.setText("选择导出格式");
        fmtLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        fmtLabel.setTypeface(Typeface.DEFAULT_BOLD);
        fmtLabel.setTextColor(TEXT_MUTED);
        fmtLabel.setPadding(0, 0, 0, dp(context, 6));
        fmtBox.addView(fmtLabel);

        final TextView fmtHint = new TextView(context);
        fmtHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        fmtHint.setTextColor(TEXT_SECONDARY);
        fmtHint.setPadding(0, dp(context, 6), 0, 0);

        String[] shortLabels = {"完整 JSON", "表格 CSV", "文本报告"};
        String[] hints = {
                "完整 JSON (.json)：含顶部摘要与 100% 原始 raw_report 数据",
                "表格 CSV (.csv)：UTF-8 BOM 编码，Excel 可直接打开查看全量明细与曲线",
                "文本报告 (.txt)：可读中文报告与完整温升/电量采样时间线"
        };

        LinearLayout segRow = new LinearLayout(context);
        segRow.setOrientation(LinearLayout.HORIZONTAL);
        segRow.setBaselineAligned(false);
        for (int i = 0; i < formats.length; i++) {
            final int idx = i;
            Button b = new Button(context);
            b.setText(shortLabels[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            b.setPadding(dp(context, 6), dp(context, 8), dp(context, 6), dp(context, 8));
            b.setOnClickListener(v -> {
                selectedIndex[0] = idx;
                for (int j = 0; j < formats.length; j++) {
                    boolean sel = (j == idx);
                    setBackground(segButtons[j], segmentDrawable(context, sel));
                    segButtons[j].setTextColor(sel ? Color.WHITE : TEXT_PRIMARY);
                }
                fmtHint.setText(hints[idx]);
            });
            segButtons[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(context, 6);
            segRow.addView(b, lp);
        }
        fmtBox.addView(segRow);
        fmtBox.addView(fmtHint);
        card.addView(fmtBox);
        segButtons[0].performClick();

        LinearLayout btnCol = new LinearLayout(context);
        btnCol.setOrientation(LinearLayout.VERTICAL);
        btnCol.setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 14));

        Button saveBtn = styledButton(context, "保存文件到下载目录", true, false, () -> {
            if (onDismiss != null) onDismiss.run();
            if (listener != null) listener.onSaveLocal(formats[selectedIndex[0]]);
        });
        btnCol.addView(saveBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout secondRow = new LinearLayout(context);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);
        secondRow.setBaselineAligned(false);
        LinearLayout.LayoutParams sRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sRowLp.topMargin = dp(context, 8);

        Button shareBtn = styledButton(context, "保存并分享文件", false, false, () -> {
            if (onDismiss != null) onDismiss.run();
            if (listener != null) listener.onSaveAndShare(formats[selectedIndex[0]]);
        });
        secondRow.addView(shareBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (Build.VERSION.SDK_INT >= 19) {
            Button safBtn = styledButton(context, "另存为…", false, false, () -> {
                if (onDismiss != null) onDismiss.run();
                if (listener != null) listener.onSaveAsSaf(formats[selectedIndex[0]]);
            });
            LinearLayout.LayoutParams safLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            safLp.leftMargin = dp(context, 8);
            secondRow.addView(safBtn, safLp);
        }
        btnCol.addView(secondRow, sRowLp);

        LinearLayout thirdRow = new LinearLayout(context);
        thirdRow.setOrientation(LinearLayout.HORIZONTAL);
        thirdRow.setBaselineAligned(false);
        LinearLayout.LayoutParams tRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tRowLp.topMargin = dp(context, 8);

        if (hasQuickSummary) {
            Button copyBtn = styledButton(context, "复制文本摘要", false, false, () -> {
                if (onDismiss != null) onDismiss.run();
                if (listener != null) listener.onCopySummary();
            });
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cLp.rightMargin = dp(context, 8);
            thirdRow.addView(copyBtn, cLp);
        }

        Button cancelBtn = styledButton(context, "取消", false, false, () -> {
            if (onDismiss != null) onDismiss.run();
        });
        thirdRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnCol.addView(thirdRow, tRowLp);

        card.addView(btnCol);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        scrim.addView(card, cardLp);
        return scrim;
    }

    /** Result dialog after file export with Share / Copy Path actions. */
    static FrameLayout buildExportResultOverlay(Context context, String fileName, String displayPath,
                                                String note, Runnable onDismiss,
                                                Runnable onShare, Runnable onCopyPath) {
        FrameLayout scrim = new FrameLayout(context);
        scrim.setBackgroundColor(0x73000000);
        scrim.setClickable(true);
        scrim.setFocusable(true);
        int hMargin = dp(context, 24);
        int vMargin = dp(context, 20);
        scrim.setPadding(hMargin, vMargin, hMargin, vMargin);
        scrim.setOnClickListener(v -> { if (onDismiss != null) onDismiss.run(); });

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(BG_CARD);
        cardBg.setCornerRadius(dp(context, 10));
        cardBg.setStroke(dp(context, 1), BORDER_CARD);
        setBackground(card, cardBg);

        TextView titleView = new TextView(context);
        titleView.setText("导出成功");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(TEXT_PRIMARY);
        titleView.setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 10));
        card.addView(titleView);

        View divider = new View(context);
        divider.setBackgroundColor(DIVIDER_ROW);
        card.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));

        TextView msgView = new TextView(context);
        msgView.setText("文件名：" + fileName + "\n保存位置：" + displayPath
                + (note != null && note.length() > 0 ? "\n\n" + note : ""));
        msgView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        msgView.setTextColor(TEXT_SECONDARY);
        msgView.setLineSpacing(dp(context, 2), 1.15f);
        msgView.setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 16));
        card.addView(msgView);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setBaselineAligned(false);
        actions.setPadding(dp(context, 16), 0, dp(context, 16), dp(context, 14));

        Button copyBtn = styledButton(context, "复制路径", false, false, () -> {
            if (onCopyPath != null) onCopyPath.run();
        });
        actions.addView(copyBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button shareBtn = styledButton(context, "分享文件", true, false, () -> {
            if (onDismiss != null) onDismiss.run();
            if (onShare != null) onShare.run();
        });
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sLp.leftMargin = dp(context, 8);
        actions.addView(shareBtn, sLp);

        Button doneBtn = styledButton(context, "完成", false, false, () -> {
            if (onDismiss != null) onDismiss.run();
        });
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dLp.leftMargin = dp(context, 8);
        actions.addView(doneBtn, dLp);

        card.addView(actions);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        scrim.addView(card, cardLp);
        return scrim;
    }

    /**
     * API 8 Canvas dual-panel curve view displaying battery temperature rise (°C)
     * and battery consumption (%) over test duration, with stage markers and peak annotations.
     */
    @SuppressLint("ViewConstructor")
    static final class TelemetryCurveView extends View {
        private final double[] elapsedSec;
        private final double[] tempC;
        private final boolean[] hasTemp;
        private final double[] batPct;
        private final boolean[] hasBat;
        private final int[] stageBreakIndices;
        private final int sampleCount;
        final boolean hasTempCurve;
        final boolean hasBatCurve;
        private final double startTempC, endTempC, deltaTempC, minTempC, maxTempC, peakTempC;
        private final int peakTempIdx;
        private final double minBatPct, maxBatPct, startBatPct, endBatPct, deltaBatPct;
        private final double startVoltV, endVoltV;
        private final boolean hasVoltage;
        private final boolean pluggedAny;
        private final double totalDurationSec;

        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint();
        private final Paint dividerPaint = new Paint();
        private final Paint stagePaint = new Paint();
        private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rightMainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rightLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path strokePath = new Path();
        private final Path fillPath = new Path();

        TelemetryCurveView(Context context, JSONObject trace) {
            super(context);
            JSONArray samples = trace != null ? trace.optJSONArray("samples") : null;
            int n = samples == null ? 0 : samples.length();
            this.sampleCount = n;
            this.elapsedSec = new double[n];
            this.tempC = new double[n];
            this.hasTemp = new boolean[n];
            this.batPct = new double[n];
            this.hasBat = new boolean[n];

            int tempValidCount = 0;
            int batValidCount = 0;
            double tMin = Double.MAX_VALUE, tMax = -Double.MAX_VALUE;
            double tStart = Double.NaN, tEnd = Double.NaN;
            int tPeakIdx = -1;
            double bMin = Double.MAX_VALUE, bMax = -Double.MAX_VALUE;
            double bStart = Double.NaN, bEnd = Double.NaN;
            int vStart = -1, vEnd = -1;
            boolean anyPlug = trace != null && trace.optBoolean("plugged_any", false);

            int[] breaksTmp = new int[32];
            int breakCount = 0;
            String prevStage = "";
            long firstTs = n > 0 && samples.optJSONObject(0) != null ? samples.optJSONObject(0).optLong("timestamp_ms", 0) : 0;

            for (int i = 0; i < n; i++) {
                JSONObject p = samples.optJSONObject(i);
                if (p == null) continue;
                long ts = p.optLong("timestamp_ms", firstTs);
                elapsedSec[i] = Math.max(0L, ts - firstTs) / 1000.0;
                if (p.optBoolean("plugged", false)) anyPlug = true;

                String stage = p.optString("stage");
                if (i > 0 && stage.length() > 0 && !"阶段间隔".equals(stage) && !stage.equals(prevStage)) {
                    if (breakCount < breaksTmp.length) breaksTmp[breakCount++] = i;
                }
                if (stage.length() > 0 && !"阶段间隔".equals(stage)) prevStage = stage;

                if (p.has("tenths_c")) {
                    double c = p.optInt("tenths_c") / 10.0;
                    tempC[i] = c;
                    hasTemp[i] = true;
                    tempValidCount++;
                    if (Double.isNaN(tStart)) tStart = c;
                    tEnd = c;
                    if (c < tMin) tMin = c;
                    if (c > tMax) {
                        tMax = c;
                        tPeakIdx = i;
                    }
                }
                if (p.has("battery_percent")) {
                    double pct = p.optDouble("battery_percent", -1);
                    if (TemperatureTrace.validBatteryPercent(pct)) {
                        batPct[i] = pct;
                        hasBat[i] = true;
                        batValidCount++;
                        if (Double.isNaN(bStart)) bStart = pct;
                        bEnd = pct;
                        if (pct < bMin) bMin = pct;
                        if (pct > bMax) bMax = pct;
                    }
                }
                int mv = p.optInt("voltage_mv", -1);
                if (mv > 0) {
                    if (vStart <= 0) vStart = mv;
                    vEnd = mv;
                }
            }

            this.stageBreakIndices = new int[breakCount];
            System.arraycopy(breaksTmp, 0, this.stageBreakIndices, 0, breakCount);
            this.hasTempCurve = tempValidCount >= 2;
            this.hasBatCurve = batValidCount >= 2;
            if (trace != null && trace.has("start_tenths_c")) {
                this.startTempC = trace.optInt("start_tenths_c") / 10.0;
                this.endTempC = trace.optInt("end_tenths_c") / 10.0;
                this.deltaTempC = trace.optInt("delta_tenths_c") / 10.0;
                this.peakTempC = trace.optInt("sampled_max_tenths_c", tempValidCount > 0 ? (int) Math.round(tMax * 10) : 0) / 10.0;
            } else {
                this.startTempC = Double.isNaN(tStart) ? 0 : tStart;
                this.endTempC = Double.isNaN(tEnd) ? 0 : tEnd;
                this.deltaTempC = this.endTempC - this.startTempC;
                this.peakTempC = tempValidCount > 0 ? tMax : 0;
            }
            this.minTempC = tempValidCount > 0 ? tMin : 0;
            this.maxTempC = tempValidCount > 0 ? Math.max(tMax, this.peakTempC) : 0;
            this.peakTempIdx = tPeakIdx;
            this.minBatPct = batValidCount > 0 ? bMin : 0;
            this.maxBatPct = batValidCount > 0 ? bMax : 100;
            if (trace != null && trace.has("battery_start_percent")) {
                this.startBatPct = trace.optDouble("battery_start_percent", bStart);
                this.endBatPct = trace.optDouble("battery_end_percent", bEnd);
                this.deltaBatPct = trace.optDouble("battery_delta_percent", this.endBatPct - this.startBatPct);
            } else {
                this.startBatPct = Double.isNaN(bStart) ? 0 : bStart;
                this.endBatPct = Double.isNaN(bEnd) ? 0 : bEnd;
                this.deltaBatPct = this.endBatPct - this.startBatPct;
            }
            if (trace != null && trace.has("voltage_start_mv") && trace.has("voltage_end_mv")) {
                this.hasVoltage = true;
                this.startVoltV = trace.optInt("voltage_start_mv") / 1000.0;
                this.endVoltV = trace.optInt("voltage_end_mv") / 1000.0;
            } else if (vStart > 0 && vEnd > 0) {
                this.hasVoltage = true;
                this.startVoltV = vStart / 1000.0;
                this.endVoltV = vEnd / 1000.0;
            } else {
                this.hasVoltage = false;
                this.startVoltV = 0;
                this.endVoltV = 0;
            }
            this.pluggedAny = anyPlug;
            this.totalDurationSec = n > 0 ? elapsedSec[n - 1] : 0;

            float density = context.getResources().getDisplayMetrics().density;
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(Math.max(2f, 2f * density));
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);

            fillPaint.setStyle(Paint.Style.FILL);

            gridPaint.setStyle(Paint.Style.FILL);
            gridPaint.setColor(0xFFE6E7EC);

            dividerPaint.setStyle(Paint.Style.FILL);
            dividerPaint.setColor(DIVIDER_ROW);

            stagePaint.setStyle(Paint.Style.FILL);
            stagePaint.setColor(0xFFDADCE2);

            titlePaint.setColor(TEXT_PRIMARY);
            titlePaint.setTypeface(Typeface.DEFAULT_BOLD);
            titlePaint.setTextSize(14f * density);
            titlePaint.setTextAlign(Paint.Align.LEFT);

            subPaint.setColor(TEXT_MUTED);
            subPaint.setTextSize(11f * density);
            subPaint.setTextAlign(Paint.Align.LEFT);

            labelPaint.setColor(TEXT_MUTED);
            labelPaint.setTextSize(10f * density);
            labelPaint.setTextAlign(Paint.Align.LEFT);

            rightMainPaint.setColor(TEXT_PRIMARY);
            rightMainPaint.setTypeface(Typeface.DEFAULT_BOLD);
            rightMainPaint.setTextSize(12.5f * density);
            rightMainPaint.setTextAlign(Paint.Align.RIGHT);

            rightLabelPaint.setColor(TEXT_SECONDARY);
            rightLabelPaint.setTextSize(11f * density);
            rightLabelPaint.setTextAlign(Paint.Align.RIGHT);

            dotPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = resolveSize(dp(getContext(), 280), widthMeasureSpec);
            int dpH;
            if (hasTempCurve && hasBatCurve) dpH = 304;
            else if (hasTempCurve || hasBatCurve) dpH = 154;
            else dpH = 44;
            setMeasuredDimension(w, dp(getContext(), dpH));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            Context ctx = getContext();
            int padH = dp(ctx, 14);
            int padV = dp(ctx, 12);

            if (!hasTempCurve && !hasBatCurve) {
                labelPaint.setColor(TEXT_SECONDARY);
                labelPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText("采样点不足（需至少 2 次采样），暂无法绘制曲线", padH, h * 0.5f + dp(ctx, 4), labelPaint);
                return;
            }

            float panelGap = dp(ctx, 22);
            float availH = h - padV * 2;
            if (hasTempCurve && hasBatCurve) {
                float eachH = (availH - panelGap) / 2f;
                drawTempPanel(canvas, padH, padV, w - padH, padV + eachH);
                float divY = padV + eachH + panelGap * 0.5f;
                canvas.drawRect(0, divY, w, divY + 1f, dividerPaint);
                drawBatteryPanel(canvas, padH, padV + eachH + panelGap, w - padH, h - padV);
            } else if (hasTempCurve) {
                drawTempPanel(canvas, padH, padV, w - padH, h - padV);
            } else {
                drawBatteryPanel(canvas, padH, padV, w - padH, h - padV);
            }
        }

        private void drawTempPanel(Canvas canvas, float left, float top, float right, float bottom) {
            Context ctx = getContext();
            float headerH = dp(ctx, 44);
            float axisBottomH = dp(ctx, 18);
            float axisLeftW = dp(ctx, 42);
            float pLeft = left + axisLeftW;
            float pTop = top + headerH;
            float pRight = right - dp(ctx, 4);
            float pBottom = bottom - axisBottomH;
            if (pRight <= pLeft || pBottom <= pTop) return;

            float line1Y = top + dp(ctx, 14);
            float line2Y = top + dp(ctx, 30);

            canvas.drawText("电池温度", left, line1Y, titlePaint);
            canvas.drawText("温升曲线", left, line2Y, subPaint);

            rightMainPaint.setColor(ORANGE_DARK);
            canvas.drawText(String.format(Locale.US, "%.1f → %.1f °C (%+.1f °C)",
                    startTempC, endTempC, deltaTempC), right, line1Y, rightMainPaint);
            rightLabelPaint.setColor(TEXT_SECONDARY);
            canvas.drawText(String.format(Locale.US, "最低 %.1f · 峰值 %.1f °C", minTempC, peakTempC),
                    right, line2Y, rightLabelPaint);

            double span = Math.max(1.0, maxTempC - minTempC);
            double yMin = Math.floor((minTempC - span * 0.15) * 2.0) / 2.0;
            double yMax = Math.ceil((maxTempC + span * 0.20) * 2.0) / 2.0;
            if (yMax - yMin < 1.0) yMax = yMin + 1.0;

            drawGridAndAxes(canvas, left, pLeft, pTop, pRight, pBottom, yMin, yMax, "%.1f°");
            drawCurveSeries(canvas, pLeft, pTop, pRight, pBottom, tempC, hasTemp, yMin, yMax,
                    ORANGE, 0x24FF6700, peakTempIdx);
            drawTimeLabels(canvas, pLeft, pRight, pBottom + dp(ctx, 14));
        }

        private void drawBatteryPanel(Canvas canvas, float left, float top, float right, float bottom) {
            Context ctx = getContext();
            float headerH = dp(ctx, 44);
            float axisBottomH = dp(ctx, 18);
            float axisLeftW = dp(ctx, 42);
            float pLeft = left + axisLeftW;
            float pTop = top + headerH;
            float pRight = right - dp(ctx, 4);
            float pBottom = bottom - axisBottomH;
            if (pRight <= pLeft || pBottom <= pTop) return;

            float line1Y = top + dp(ctx, 14);
            float line2Y = top + dp(ctx, 30);

            canvas.drawText("电池电量", left, line1Y, titlePaint);
            canvas.drawText("耗电曲线", left, line2Y, subPaint);

            int accent = pluggedAny ? 0xFF188AE2 : GREEN;
            rightMainPaint.setColor(accent);
            canvas.drawText(String.format(Locale.US, "%.0f%% → %.0f%% (%+.1f%%)",
                    startBatPct, endBatPct, deltaBatPct), right, line1Y, rightMainPaint);
            rightLabelPaint.setColor(TEXT_SECONDARY);
            String subRight;
            if (hasVoltage) {
                subRight = String.format(Locale.US, "%.2f → %.2f V%s",
                        startVoltV, endVoltV, pluggedAny ? " · 含供电" : "");
            } else {
                subRight = pluggedAny ? "含外接供电" : "纯电池供电";
            }
            canvas.drawText(subRight, right, line2Y, rightLabelPaint);

            double span = Math.max(2.0, maxBatPct - minBatPct);
            double yMin = Math.max(0.0, Math.floor(minBatPct - Math.max(1.0, span * 0.2)));
            double yMax = Math.min(100.0, Math.ceil(maxBatPct + Math.max(1.0, span * 0.2)));
            if (yMax - yMin < 2.0) {
                yMin = Math.max(0.0, yMax - 2.0);
                if (yMax - yMin < 2.0) yMax = Math.min(100.0, yMin + 2.0);
            }

            drawGridAndAxes(canvas, left, pLeft, pTop, pRight, pBottom, yMin, yMax, "%.0f%%");
            drawCurveSeries(canvas, pLeft, pTop, pRight, pBottom, batPct, hasBat, yMin, yMax,
                    accent, pluggedAny ? 0x20188AE2 : 0x201FA650, -1);
            drawTimeLabels(canvas, pLeft, pRight, pBottom + dp(ctx, 14));
        }

        private void drawGridAndAxes(Canvas canvas, float labelLeft, float pLeft, float pTop, float pRight, float pBottom,
                                     double yMin, double yMax, String fmt) {
            float pH = pBottom - pTop;
            float pW = pRight - pLeft;
            rightLabelPaint.setColor(TEXT_MUTED);
            labelPaint.setColor(TEXT_MUTED);

            for (int g = 0; g <= 2; g++) {
                float frac = g / 2f;
                float y = pBottom - frac * pH;
                canvas.drawRect(pLeft, y, pRight, y + 1f, gridPaint);
                double val = yMin + (yMax - yMin) * frac;
                canvas.drawText(String.format(Locale.US, fmt, val), labelLeft, y + dp(getContext(), 3.5f), labelPaint);
            }

            if (totalDurationSec > 0) {
                for (int idx : stageBreakIndices) {
                    if (idx < 0 || idx >= sampleCount) continue;
                    float x = pLeft + (float) (elapsedSec[idx] / totalDurationSec) * pW;
                    canvas.drawRect(x, pTop, x + 1f, pBottom, stagePaint);
                }
            }
        }

        private void drawCurveSeries(Canvas canvas, float pLeft, float pTop, float pRight, float pBottom,
                                     double[] values, boolean[] valid, double yMin, double yMax,
                                     int strokeColor, int fillColor, int highlightIdx) {
            float pW = pRight - pLeft;
            float pH = pBottom - pTop;
            double denom = Math.max(1e-6, yMax - yMin);

            strokePath.reset();
            fillPath.reset();
            boolean started = false;
            float firstX = pLeft, lastX = pLeft, lastY = pBottom;
            float hiX = 0, hiY = 0;
            boolean hasHi = false;

            for (int i = 0; i < sampleCount; i++) {
                if (!valid[i]) continue;
                float xFrac = totalDurationSec > 0
                        ? (float) (elapsedSec[i] / totalDurationSec)
                        : (sampleCount > 1 ? i / (float) (sampleCount - 1) : 0.5f);
                float x = pLeft + Math.min(1f, Math.max(0f, xFrac)) * pW;
                float yFrac = (float) ((values[i] - yMin) / denom);
                float y = pBottom - Math.min(1f, Math.max(0f, yFrac)) * pH;

                if (!started) {
                    strokePath.moveTo(x, y);
                    fillPath.moveTo(x, pBottom);
                    fillPath.lineTo(x, y);
                    firstX = x;
                    started = true;
                } else {
                    strokePath.lineTo(x, y);
                    fillPath.lineTo(x, y);
                }
                lastX = x;
                lastY = y;
                if (i == highlightIdx) {
                    hiX = x;
                    hiY = y;
                    hasHi = true;
                }
            }

            if (!started) return;
            fillPath.lineTo(lastX, pBottom);
            fillPath.lineTo(firstX, pBottom);
            fillPath.close();

            fillPaint.setColor(fillColor);
            canvas.drawPath(fillPath, fillPaint);

            linePaint.setColor(strokeColor);
            canvas.drawPath(strokePath, linePaint);

            float r = dp(getContext(), 2.8f);
            dotPaint.setColor(strokeColor);
            canvas.drawCircle(lastX, lastY, r, dotPaint);
            if (hasHi) {
                dotPaint.setColor(Color.WHITE);
                canvas.drawCircle(hiX, hiY, r * 1.4f, dotPaint);
                dotPaint.setColor(strokeColor);
                canvas.drawCircle(hiX, hiY, r, dotPaint);
            }
        }

        private void drawTimeLabels(Canvas canvas, float pLeft, float pRight, float baselineY) {
            labelPaint.setColor(TEXT_MUTED);
            canvas.drawText("0:00", pLeft, baselineY, labelPaint);
            if (totalDurationSec >= 2.0) {
                rightLabelPaint.setColor(TEXT_MUTED);
                canvas.drawText(formatShortTime(totalDurationSec / 2.0), (pLeft + pRight) * 0.5f + dp(getContext(), 12), baselineY, rightLabelPaint);
                canvas.drawText(formatShortTime(totalDurationSec), pRight, baselineY, rightLabelPaint);
            }
        }

        private static String formatShortTime(double seconds) {
            int total = Math.max(0, (int) Math.round(seconds));
            int mins = total / 60;
            int secs = total % 60;
            return String.format(Locale.US, "%d:%02d", mins, secs);
        }
    }

    static String ellipsizeMiddle(String value, int keepStart, int keepEnd) {
        if (value == null || value.length() <= keepStart + keepEnd + 3) return value == null ? "" : value;
        return value.substring(0, keepStart) + "..." + value.substring(value.length() - keepEnd);
    }
}
