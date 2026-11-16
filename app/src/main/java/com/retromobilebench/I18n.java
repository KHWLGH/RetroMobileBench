package com.retromobilebench;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/**
 * Lightweight bilingual localization manager (auto / zh / en) with API 8+ compatibility
 * and zero external dependencies. Safe for main-thread UI and background worker formatting.
 */
final class I18n {
    static final String MODE_AUTO = "auto";
    static final String MODE_ZH = "zh";
    static final String MODE_EN = "en";

    private static final String PREFS_NAME = "rmb_prefs";
    private static final String KEY_LANGUAGE = "ui_language";
    private static final Locale SYSTEM_LOCALE_FALLBACK = Locale.getDefault();

    private static volatile Context appContext;
    private static volatile String cachedMode;

    private I18n() { }

    static void init(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (app != null) appContext = app;
        if (cachedMode == null) {
            Context src = app != null ? app : context;
            SharedPreferences prefs = src.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            cachedMode = normalizeMode(prefs.getString(KEY_LANGUAGE, MODE_AUTO));
        }
    }

    static String getMode(Context context) {
        init(context);
        return cachedMode != null ? cachedMode : MODE_AUTO;
    }

    @SuppressLint("ApplySharedPref")
    static void setMode(Context context, String mode) {
        String normalized = normalizeMode(mode);
        cachedMode = normalized;
        if (context != null) {
            init(context);
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_LANGUAGE, normalized).commit();
            applyLocale(context.getApplicationContext());
        }
    }

    static boolean isZh() {
        String mode = cachedMode;
        if (mode == null && appContext != null) {
            mode = getMode(appContext);
        }
        if (MODE_ZH.equals(mode)) return true;
        if (MODE_EN.equals(mode)) return false;
        return isSystemLocaleZh();
    }

    static boolean isZh(Context context) {
        init(context);
        return isZh();
    }

    static String t(String zh, String en) {
        return isZh() ? zh : en;
    }

    static String t(Context context, String zh, String en) {
        return isZh(context) ? zh : en;
    }

    static String modeLabel(Context context, String mode) {
        boolean zh = isZh(context);
        if (MODE_ZH.equals(mode)) return "简体中文";
        if (MODE_EN.equals(mode)) return "English";
        return zh ? "跟随系统" : "Follow System";
    }

    static String currentModeSummary(Context context) {
        String mode = getMode(context);
        if (MODE_ZH.equals(mode)) return "简体中文";
        if (MODE_EN.equals(mode)) return "English";
        boolean sysZh = isSystemLocaleZh();
        return isZh(context)
                ? "跟随系统 (" + (sysZh ? "简体中文" : "English") + ")"
                : "Follow System (" + (sysZh ? "简体中文" : "English") + ")";
    }

    @SuppressWarnings("deprecation")
    static void applyLocale(Context context) {
        if (context == null) return;
        init(context);
        Locale target = targetLocale();
        Locale.setDefault(target);
        Resources res = context.getResources();
        Configuration cfg = new Configuration(res.getConfiguration());
        if (!target.equals(cfg.locale)) {
            cfg.locale = target;
            res.updateConfiguration(cfg, res.getDisplayMetrics());
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean isSystemLocaleZh() {
        Locale sys = null;
        try {
            Resources sysRes = Resources.getSystem();
            if (sysRes != null && sysRes.getConfiguration() != null) {
                sys = sysRes.getConfiguration().locale;
            }
        } catch (RuntimeException ignored) { }
        if (sys == null) sys = SYSTEM_LOCALE_FALLBACK;
        return sys != null && "zh".equalsIgnoreCase(sys.getLanguage());
    }

    private static Locale targetLocale() {
        String mode = cachedMode != null ? cachedMode : MODE_AUTO;
        if (MODE_ZH.equals(mode)) return Locale.SIMPLIFIED_CHINESE;
        if (MODE_EN.equals(mode)) return Locale.US;
        return isSystemLocaleZh() ? Locale.SIMPLIFIED_CHINESE : Locale.US;
    }

    private static String normalizeMode(String mode) {
        if (MODE_ZH.equals(mode)) return MODE_ZH;
        if (MODE_EN.equals(mode)) return MODE_EN;
        return MODE_AUTO;
    }
}
