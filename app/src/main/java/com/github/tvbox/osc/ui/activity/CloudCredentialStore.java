package com.github.tvbox.osc.ui.activity;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 持久化保存网盘登录凭据。
 * 数据保存在应用私有 SharedPreferences 中，不会写入日志或上传到服务器。
 */
public final class CloudCredentialStore {
    public static final String ALIYUN = "aliyun";
    public static final String QUARK = "quark";
    public static final String UC = "uc";
    public static final String BAIDU = "baidu";

    private static final String PREFS_NAME = "cloud_login_credentials";
    private static final String[] PROVIDERS = {ALIYUN, QUARK, UC, BAIDU};
    private static final Map<String, String> VALUES = new HashMap<>();
    private static SharedPreferences preferences;

    private CloudCredentialStore() {
    }

    /** Must be called once from Application.onCreate before any credential screen is used. */
    public static synchronized void init(Context context) {
        if (preferences != null || context == null) return;
        Context appContext = context.getApplicationContext();
        preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        for (String provider : PROVIDERS) {
            String value = preferences.getString(provider, "");
            if (!TextUtils.isEmpty(value)) VALUES.put(provider, value);
        }
    }

    /** Store a newly acquired or explicitly imported credential. Empty input deletes it. */
    public static synchronized boolean put(String provider, String value) {
        if (TextUtils.isEmpty(provider)) return false;
        String normalized = value == null ? "" : value.trim();
        if (preferences == null) {
            // Defensive fallback for tests or unusual startup paths.
            return false;
        }
        if (normalized.equals(VALUES.get(provider))) return true;
        SharedPreferences.Editor editor = preferences.edit();
        if (normalized.isEmpty()) {
            if (!editor.remove(provider).commit()) return false;
            VALUES.remove(provider);
        } else {
            if (!editor.putString(provider, normalized).commit()) return false;
            VALUES.put(provider, normalized);
        }
        return true;
    }

    public static synchronized String get(String provider) {
        String value = VALUES.get(provider);
        return value == null ? "" : value;
    }

    public static synchronized boolean has(String provider) {
        return !TextUtils.isEmpty(VALUES.get(provider));
    }

    public static String getDisplayName(String provider) {
        if (ALIYUN.equals(provider)) return "阿里云盘 Token";
        if (QUARK.equals(provider)) return "夸克网盘 Cookie";
        if (UC.equals(provider)) return "UC 网盘 Cookie";
        if (BAIDU.equals(provider)) return "百度网盘 Cookie";
        return "网盘凭据";
    }

    public static synchronized Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(VALUES));
    }
}
