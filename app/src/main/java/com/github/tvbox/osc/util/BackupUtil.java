package com.github.tvbox.osc.util;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.Base64;

import com.github.tvbox.osc.api.ApiConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 「备份与同步」的完整配置快照。
 * 导出设置项及必要的外观资源；导入时先校验备份，再完整替换配置白名单，
 * 备份中缺少的配置会删除旧值并恢复应用默认值。
 * 不包含播放历史、收藏、媒体数据库及云盘登录凭据。
 */
public final class BackupUtil {

    public static final int FORMAT_VERSION = 2;
    private static final String APP_ID = "tvboxos";
    private static final String WALLPAPER_FILE = "wp";
    private static final int MAX_WALLPAPER_BYTES = 12 * 1024 * 1024;

    private static final Gson GSON = new Gson();
    private static final Type STRING_LIST_TYPE = new TypeToken<ArrayList<String>>() { }.getType();
    private static final Type STRING_MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() { }.getType();
    private static final Type NESTED_STRING_MAP_TYPE =
            new TypeToken<LinkedHashMap<String, LinkedHashMap<String, String>>>() { }.getType();

    /** 仅包含用户配置；会话状态、播放进度、播放历史与收藏不在此白名单内。 */
    private static final String[] KEYS = {
            // 数据源地址、线路配置、EPG / DoH / 远程控制地址
            HawkConfig.API_URL, HawkConfig.API_HISTORY, HawkConfig.API_LINE_LIST,
            HawkConfig.API_LINE_SOURCE, HawkConfig.API_LINE_HISTORY_MAP,
            HawkConfig.HOME_API, HawkConfig.EPG_URL, HawkConfig.EPG_HISTORY,
            HawkConfig.DOH_URL, HawkConfig.DOH_JSON, HawkConfig.REMOTE_TVBOX,
            // 直播源地址、源列表、当前直播分组请求头
            HawkConfig.LIVE_API_URL, HawkConfig.LIVE_API_HISTORY,
            HawkConfig.LIVE_GROUP_LIST, HawkConfig.LIVE_WEB_HEADER,
            // 短剧源配置（用户清单、当前选择及已扫描标记）
            HawkConfig.SHORT_DRAMA_SOURCES, HawkConfig.SHORT_DRAMA_SELECTED,
            HawkConfig.SHORT_DRAMA_SCANNED,
            // 外观主题与壁纸颜色
            HawkConfig.UI_MODE, HawkConfig.WALLPAPER_COLOR,
            // 首页布局、导航与分类顺序
            HawkConfig.HOME_TABS, HawkConfig.HOME_REC, HawkConfig.HOME_REC_STYLE,
            HawkConfig.HOME_HIDE_SHORT_DRAMA, HawkConfig.HOME_HIDE_LIVE,
            HawkConfig.SEARCH_VIEW, HawkConfig.DEFAULT_LOAD_LIVE, HawkConfig.UI_ADVANCED,
            HawkConfig.DEBUG_OPEN,
            // 界面与搜索偏好（不包含搜索历史内容）
            HawkConfig.HISTORY_NUM, HawkConfig.HISTORY_MERGE,
            HawkConfig.FAST_SEARCH_MODE, HawkConfig.SOURCES_FOR_SEARCH,
            // 点播播放设定
            HawkConfig.PLAY_TYPE, HawkConfig.PLAY_RENDER, HawkConfig.PLAY_SCALE,
            HawkConfig.IJK_CODEC, HawkConfig.PLAY_TIME_STEP, HawkConfig.IJK_CACHE_PLAY,
            HawkConfig.AUTO_SWITCH_LINE, HawkConfig.M3U8_PURIFY, HawkConfig.SHOW_PREVIEW,
            HawkConfig.DEFAULT_PARSE, HawkConfig.PARSE_WEBVIEW, HawkConfig.SCREEN_DISPLAY,
            // 字幕设定
            HawkConfig.SUBTITLE_TEXT_SIZE, HawkConfig.SUBTITLE_TIME_DELAY,
            HawkConfig.SUBTITLE_EXO_SCALE, HawkConfig.SUBTITLE_EXO_POSITION,
            // 直播播放及显示偏好
            HawkConfig.LIVE_PLAY_TYPE, HawkConfig.LIVE_PLAY_SCALE,
            HawkConfig.LIVE_CONNECT_TIMEOUT, HawkConfig.LIVE_SHOW_NET_SPEED,
            HawkConfig.LIVE_SHOW_RESOLUTION, HawkConfig.LIVE_SHOW_TIME,
            HawkConfig.LIVE_CHANNEL_REVERSE, HawkConfig.LIVE_CROSS_GROUP,
            // 弹幕设定（包含是否使用内置接口）
            HawkConfig.DANMU_OPEN, HawkConfig.DANMU_MAX_LINE, HawkConfig.DANMU_SPEED,
            HawkConfig.DANMU_ALPHA, HawkConfig.DANMU_SIZE_SCALE,
            HawkConfig.DANMU_RANDOM_COLOR, HawkConfig.DANMU_API,
            HawkConfig.DANMU_API_USE_DEFAULT,
            // 播放手势
            HawkConfig.GESTURE_SPEED_BOOST, HawkConfig.GESTURE_MAX_SPEED,
            HawkConfig.GESTURE_SPEED_BOOST_LOCKED, HawkConfig.GESTURE_DOUBLE_TAP_PLAY,
            HawkConfig.GESTURE_DOUBLE_TAP_SEEK, HawkConfig.GESTURE_PORTRAIT_SWIPE
    };

    public static final class RestoreResult {
        public int count;
        public int clearedCount;
        public boolean uiModeChanged;
        public boolean playerChanged;
        public boolean danmuChanged;
        public boolean wallpaperChanged;
        public boolean apiConfigChanged;
        public boolean shortDramaChanged;
        public boolean homeTabsChanged;
    }

    private BackupUtil() {
    }

    /** 向后兼容不带资源文件的调用。正式导出请使用 buildJson(Context)。 */
    public static String buildJson() {
        return buildJson(null);
    }

    /** 导出完整配置；若存在自定义壁纸，将图片内容一并写入备份。 */
    public static String buildJson(Context context) {
        JSONObject root = new JSONObject();
        JSONArray entries = new JSONArray();
        try {
            Set<String> keys = new LinkedHashSet<>();
            for (String key : KEYS) keys.add(key);
            // 直播分组索引按直播源 URL 分开保存，必须随当前直播源一起备份。
            keys.add(ApiConfig.getLiveGroupIndexKey());

            for (String key : keys) {
                Object value;
                try {
                    value = Hawk.get(key);
                } catch (Throwable ignored) {
                    continue;
                }
                if (value == null) continue;
                String type = typeName(key, value);
                if (type == null) continue;
                JSONObject entry = new JSONObject();
                entry.put("k", key);
                entry.put("t", type);
                if ("json".equals(type)) {
                    entry.put("v", GSON.toJson(value));
                } else {
                    entry.put("v", value);
                }
                entries.put(entry);
            }

            root.put("format", FORMAT_VERSION);
            root.put("app", APP_ID);
            root.put("time", System.currentTimeMillis());
            root.put("replace", "all");
            root.put("full_replace", true);
            root.put("entries", entries);
            JSONObject assets = new JSONObject();
            if (context != null) {
                File wallpaper = new File(context.getFilesDir(), WALLPAPER_FILE);
                if (wallpaper.isFile() && wallpaper.length() > 0) {
                    if (wallpaper.length() > MAX_WALLPAPER_BYTES) {
                        throw new IOException("壁纸文件过大，无法备份");
                    }
                    assets.put("wallpaper", Base64.encodeToString(readFile(wallpaper), Base64.NO_WRAP));
                }
            }
            root.put("assets", assets);
        } catch (Exception e) {
            throw new IllegalStateException("构建备份失败：" + e.getMessage(), e);
        }
        return root.toString();
    }

    /**
     * 完整恢复配置。数据先完整解析和校验，通过后再清理旧值，避免格式错误的备份造成半恢复。
     */
    public static RestoreResult restore(String json, Context context) throws JSONException, IOException {
        if (json == null || json.trim().isEmpty()) {
            throw new JSONException("empty backup");
        }
        JSONObject root = new JSONObject(json);
        int version = root.optInt("format", 1);
        if (version < 1 || version > FORMAT_VERSION) {
            throw new JSONException("unsupported backup version: " + version);
        }
        String app = root.optString("app", APP_ID);
        if (!APP_ID.equals(app)) {
            throw new JSONException("backup belongs to another app");
        }
        JSONArray entries = root.optJSONArray("entries");
        if (entries == null) throw new JSONException("no entries");

        // 预解析全部可识别配置，任何已知字段类型错误都会在写入前中止。
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String key = entry.optString("k", "");
            if (!isBackupKey(key)) continue;
            if (values.containsKey(key)) throw new JSONException("duplicate backup key: " + key);
            String type = entry.optString("t", "");
            Object raw = entry.opt("v");
            if (raw == null || raw == JSONObject.NULL) continue;
            try {
                values.put(key, parseEntry(key, type, raw));
            } catch (Throwable e) {
                throw new JSONException("invalid value for " + key + ": " + e.getMessage());
            }
        }

        String importedLiveUrl = stringValue(values.get(HawkConfig.LIVE_API_URL));
        String importedIndexKey = null;
        for (String key : values.keySet()) {
            if (isLiveGroupIndexKey(key)) {
                importedIndexKey = key;
                break;
            }
        }
        String expectedIndexKey = liveGroupIndexKey(importedLiveUrl);
        if (importedIndexKey != null && !expectedIndexKey.equals(importedIndexKey)) {
            throw new JSONException("live group index does not match live source URL");
        }

        // 壁纸也先解码与校验，确认整份文件可用后才触碰本地配置。
        byte[] wallpaperBytes = null;
        JSONObject assets = root.optJSONObject("assets");
        if (assets != null && assets.has("wallpaper") && !assets.isNull("wallpaper")) {
            String encoded = assets.optString("wallpaper", "");
            if (!encoded.isEmpty()) {
                try {
                    wallpaperBytes = Base64.decode(encoded, Base64.DEFAULT);
                } catch (Throwable e) {
                    throw new JSONException("invalid wallpaper asset");
                }
                if (wallpaperBytes.length == 0 || wallpaperBytes.length > MAX_WALLPAPER_BYTES
                        || !isImage(wallpaperBytes)) {
                    throw new JSONException("invalid wallpaper image");
                }
            }
        }

        RestoreResult result = new RestoreResult();
        String oldLiveIndexKey = ApiConfig.getLiveGroupIndexKey();
        WallpaperRestorePlan wallpaperPlan = context == null ? null
                : prepareWallpaperRestore(context, wallpaperBytes);

        try {
            // 完整覆盖：先删除白名单内全部旧值，再写入备份中实际存在的值。
            for (String key : KEYS) {
                Hawk.delete(key);
                result.clearedCount++;
            }
            if (isLiveGroupIndexKey(oldLiveIndexKey)) {
                Hawk.delete(oldLiveIndexKey);
                result.clearedCount++;
            }

            for (Map.Entry<String, Object> entry : values.entrySet()) {
                Hawk.put(entry.getKey(), (Object) entry.getValue());
            }

            // 目标直播源的分组索引若不在备份中，则回退为 0，而不是沿用本机旧索引。
            String newLiveIndexKey = ApiConfig.getLiveGroupIndexKey();
            if (!values.containsKey(newLiveIndexKey)) {
                Hawk.delete(newLiveIndexKey);
                result.clearedCount++;
            }

            // 新版备份缺少壁纸即代表「无自定义图片」，完整覆盖时删除本机旧壁纸。
            if (wallpaperPlan != null) {
                commitWallpaperRestore(wallpaperPlan);
                result.wallpaperChanged = true;
            }
        } catch (Throwable failure) {
            if (wallpaperPlan != null) rollbackWallpaperRestore(wallpaperPlan);
            if (failure instanceof IOException) throw (IOException) failure;
            throw new JSONException("failed to apply backup: " + failure.getMessage());
        }

        // 全量配置均可能影响当前运行中的缓存或页面，交由 Activity 触发即时刷新/提示重启。
        result.uiModeChanged = true;
        result.playerChanged = true;
        result.danmuChanged = true;
        result.apiConfigChanged = true;
        result.shortDramaChanged = true;
        result.homeTabsChanged = true;
        result.count = KEYS.length + 1; // 配置白名单 + 当前直播分组索引
        ShortDramaConfig.markChanged();
        HomeTabConfig.markChanged();
        return result;
    }

    private static Object parseEntry(String key, String type, Object raw) throws Exception {
        String expectedType = expectedTypeForKey(key);
        if (expectedType == null || !expectedType.equals(type)) {
            throw new JSONException("unexpected type " + type);
        }
        if ("bool".equals(type) && raw instanceof Boolean) return raw;
        if ("int".equals(type) && raw instanceof Number) return ((Number) raw).intValue();
        if ("long".equals(type) && raw instanceof Number) return ((Number) raw).longValue();
        if ("float".equals(type) && raw instanceof Number) return ((Number) raw).floatValue();
        if ("double".equals(type) && raw instanceof Number) return ((Number) raw).doubleValue();
        if ("string".equals(type) && raw instanceof String) return raw;
        if (!"json".equals(type) || !(raw instanceof String)) {
            throw new JSONException("type mismatch");
        }

        String value = (String) raw;
        if (isStringListKey(key)) {
            ArrayList<String> list = GSON.fromJson(value, STRING_LIST_TYPE);
            if (list == null) throw new JSONException("null list");
            return list;
        }
        if (HawkConfig.API_LINE_HISTORY_MAP.equals(key) || HawkConfig.LIVE_WEB_HEADER.equals(key)) {
            LinkedHashMap<String, String> map = GSON.fromJson(value, STRING_MAP_TYPE);
            if (map == null) throw new JSONException("null map");
            return map;
        }
        if (HawkConfig.SOURCES_FOR_SEARCH.equals(key)) {
            LinkedHashMap<String, LinkedHashMap<String, String>> map =
                    GSON.fromJson(value, NESTED_STRING_MAP_TYPE);
            if (map == null) throw new JSONException("null nested map");
            return map;
        }
        if (HawkConfig.LIVE_GROUP_LIST.equals(key)) {
            JsonArray array = GSON.fromJson(value, JsonArray.class);
            if (array == null) throw new JSONException("null json array");
            return array;
        }
        throw new JSONException("unsupported complex key");
    }

    private static String typeName(String key, Object value) {
        String type = expectedTypeForKey(key);
        if (type == null) return null;
        if ("bool".equals(type)) return value instanceof Boolean ? type : null;
        if ("int".equals(type)) return value instanceof Number ? type : null;
        if ("float".equals(type)) return value instanceof Number ? type : null;
        if ("string".equals(type)) return value instanceof String ? type : null;
        if ("json".equals(type)) return (value instanceof Map || value instanceof Iterable
                || value instanceof JsonArray) ? type : null;
        return null;
    }

    /** 类型由配置 key 决定，避免错误类型的备份值写入后触发 ClassCastException。 */
    private static String expectedTypeForKey(String key) {
        if (isStringListKey(key) || HawkConfig.API_LINE_HISTORY_MAP.equals(key)
                || HawkConfig.LIVE_WEB_HEADER.equals(key)
                || HawkConfig.SOURCES_FOR_SEARCH.equals(key)
                || HawkConfig.LIVE_GROUP_LIST.equals(key)) return "json";

        if (HawkConfig.HOME_REC_STYLE.equals(key)
                || HawkConfig.HOME_HIDE_SHORT_DRAMA.equals(key)
                || HawkConfig.HOME_HIDE_LIVE.equals(key)
                || HawkConfig.DEFAULT_LOAD_LIVE.equals(key)
                || HawkConfig.UI_ADVANCED.equals(key)
                || HawkConfig.DEBUG_OPEN.equals(key)
                || HawkConfig.HISTORY_MERGE.equals(key)
                || HawkConfig.FAST_SEARCH_MODE.equals(key)
                || HawkConfig.IJK_CACHE_PLAY.equals(key)
                || HawkConfig.AUTO_SWITCH_LINE.equals(key)
                || HawkConfig.M3U8_PURIFY.equals(key)
                || HawkConfig.SHOW_PREVIEW.equals(key)
                || HawkConfig.PARSE_WEBVIEW.equals(key)
                || HawkConfig.LIVE_SHOW_NET_SPEED.equals(key)
                || HawkConfig.LIVE_SHOW_RESOLUTION.equals(key)
                || HawkConfig.LIVE_SHOW_TIME.equals(key)
                || HawkConfig.LIVE_CHANNEL_REVERSE.equals(key)
                || HawkConfig.LIVE_CROSS_GROUP.equals(key)
                || HawkConfig.DANMU_OPEN.equals(key)
                || HawkConfig.DANMU_RANDOM_COLOR.equals(key)
                || HawkConfig.DANMU_API_USE_DEFAULT.equals(key)
                || HawkConfig.GESTURE_SPEED_BOOST.equals(key)
                || HawkConfig.GESTURE_SPEED_BOOST_LOCKED.equals(key)
                || HawkConfig.GESTURE_DOUBLE_TAP_PLAY.equals(key)
                || HawkConfig.GESTURE_PORTRAIT_SWIPE.equals(key)) return "bool";

        if (HawkConfig.GESTURE_MAX_SPEED.equals(key)
                || HawkConfig.DANMU_SPEED.equals(key)
                || HawkConfig.DANMU_ALPHA.equals(key)
                || HawkConfig.DANMU_SIZE_SCALE.equals(key)
                || HawkConfig.SUBTITLE_EXO_POSITION.equals(key)) return "float";

        if (HawkConfig.UI_MODE.equals(key)
                || HawkConfig.WALLPAPER_COLOR.equals(key)
                || HawkConfig.HOME_REC.equals(key)
                || HawkConfig.SEARCH_VIEW.equals(key)
                || HawkConfig.HISTORY_NUM.equals(key)
                || HawkConfig.DOH_URL.equals(key)
                || HawkConfig.PLAY_TYPE.equals(key)
                || HawkConfig.PLAY_RENDER.equals(key)
                || HawkConfig.PLAY_SCALE.equals(key)
                || HawkConfig.PLAY_TIME_STEP.equals(key)
                || HawkConfig.SCREEN_DISPLAY.equals(key)
                || HawkConfig.SUBTITLE_TEXT_SIZE.equals(key)
                || HawkConfig.SUBTITLE_TIME_DELAY.equals(key)
                || HawkConfig.SUBTITLE_EXO_SCALE.equals(key)
                || HawkConfig.LIVE_PLAY_TYPE.equals(key)
                || HawkConfig.LIVE_PLAY_SCALE.equals(key)
                || HawkConfig.LIVE_CONNECT_TIMEOUT.equals(key)
                || HawkConfig.DANMU_MAX_LINE.equals(key)
                || HawkConfig.GESTURE_DOUBLE_TAP_SEEK.equals(key)
                || isLiveGroupIndexKey(key)) return "int";

        if (isBackupKey(key)) return "string";
        return null;
    }


    private static boolean isStringListKey(String key) {
        return HawkConfig.API_HISTORY.equals(key)
                || HawkConfig.API_LINE_LIST.equals(key)
                || HawkConfig.LIVE_API_HISTORY.equals(key)
                || HawkConfig.EPG_HISTORY.equals(key);
    }

    private static boolean isBackupKey(String key) {
        for (String allowed : KEYS) {
            if (allowed.equals(key)) return true;
        }
        return isLiveGroupIndexKey(key);
    }

    private static boolean isLiveGroupIndexKey(String key) {
        return HawkConfig.LIVE_GROUP_INDEX.equals(key)
                || (key != null && key.startsWith(HawkConfig.LIVE_GROUP_INDEX + "_")
                && key.length() <= 4096);
    }

    private static String liveGroupIndexKey(String liveApiUrl) {
        return liveApiUrl == null || liveApiUrl.isEmpty()
                ? HawkConfig.LIVE_GROUP_INDEX
                : HawkConfig.LIVE_GROUP_INDEX + "_" + liveApiUrl;
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : "";
    }

    private static byte[] readFile(File file) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                if (output.size() > MAX_WALLPAPER_BYTES) {
                    throw new IOException("壁纸文件过大，无法备份");
                }
            }
        } finally {
            input.close();
        }
        return output.toByteArray();
    }

    private static boolean isImage(byte[] data) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        return options.outWidth > 0 && options.outHeight > 0
                && options.outWidth <= 10000 && options.outHeight <= 10000;
    }

    private static final class WallpaperRestorePlan {
        final File destination;
        final File temp;
        final File previous;
        final boolean hadPrevious;
        final boolean hasNewImage;
        boolean committed;

        WallpaperRestorePlan(File destination, File temp, File previous,
                             boolean hadPrevious, boolean hasNewImage) {
            this.destination = destination;
            this.temp = temp;
            this.previous = previous;
            this.hadPrevious = hadPrevious;
            this.hasNewImage = hasNewImage;
        }
    }

    private static WallpaperRestorePlan prepareWallpaperRestore(Context context, byte[] bytes)
            throws IOException {
        File dir = context.getFilesDir();
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建壁纸目录");
        File destination = new File(dir, WALLPAPER_FILE);
        File temp = new File(dir, WALLPAPER_FILE + ".restore.tmp");
        File previous = new File(dir, WALLPAPER_FILE + ".restore.bak");
        if (temp.exists() && !temp.delete()) throw new IOException("无法清理临时壁纸文件");
        if (previous.exists() && !previous.delete()) throw new IOException("无法清理旧壁纸备份");

        if (bytes != null) {
            FileOutputStream output = new FileOutputStream(temp);
            try {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            } finally {
                output.close();
            }
        }

        boolean hadPrevious = destination.exists();
        if (hadPrevious && !destination.renameTo(previous)) {
            temp.delete();
            throw new IOException("无法暂存旧壁纸");
        }
        return new WallpaperRestorePlan(destination, temp, previous, hadPrevious, bytes != null);
    }

    private static void commitWallpaperRestore(WallpaperRestorePlan plan) throws IOException {
        if (plan.hasNewImage && !plan.temp.renameTo(plan.destination)) {
            if (plan.hadPrevious) plan.previous.renameTo(plan.destination);
            throw new IOException("保存壁纸失败");
        }
        if (plan.previous.exists() && !plan.previous.delete()) {
            // 新壁纸已经生效，旧备份清理失败不应判定整个恢复失败。
        }
        plan.committed = true;
    }

    private static void rollbackWallpaperRestore(WallpaperRestorePlan plan) {
        if (plan.committed) return;
        if (plan.temp.exists()) plan.temp.delete();
        if (plan.hadPrevious && plan.previous.exists() && !plan.destination.exists()) {
            plan.previous.renameTo(plan.destination);
        }
    }

}
