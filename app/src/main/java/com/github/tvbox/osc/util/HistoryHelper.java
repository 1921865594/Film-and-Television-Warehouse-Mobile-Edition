package com.github.tvbox.osc.util;

import com.orhanobut.hawk.Hawk;
import android.text.TextUtils;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HistoryHelper {
    private static final Integer[] hisNumArray = {30,50,100};
    private static final String API_LINE_SPLIT = "\t";
    private static final String API_HISTORY_V2_PREFIX = "@MBOX_API_HISTORY_V2@";

    /** 一条点播历史配置：用户新增的点播入口地址 + 用户填写的备注。 */
    public static final class ApiHistoryRecord {
        public final String address;
        public final String remark;
        public final String storageValue;

        public ApiHistoryRecord(String address, String remark, String storageValue) {
            this.address = address == null ? "" : address;
            this.remark = remark == null ? "" : remark;
            this.storageValue = storageValue == null ? this.address : storageValue;
        }
    }

    private static String encodeHistoryPart(String value) {
        return Base64.encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String decodeHistoryPart(String value) {
        try {
            return new String(Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String encodeApiHistory(String address, String remark) {
        return API_HISTORY_V2_PREFIX + encodeHistoryPart(address) + "." + encodeHistoryPart(remark);
    }

    public static String getApiHistoryAddress(String storageValue) {
        if (storageValue == null) return "";
        if (!storageValue.startsWith(API_HISTORY_V2_PREFIX)) return storageValue;
        String payload = storageValue.substring(API_HISTORY_V2_PREFIX.length());
        int split = payload.indexOf('.');
        if (split < 0) return "";
        return decodeHistoryPart(payload.substring(0, split));
    }

    public static String getApiHistoryRemark(String storageValue) {
        if (storageValue == null || !storageValue.startsWith(API_HISTORY_V2_PREFIX)) return "";
        String payload = storageValue.substring(API_HISTORY_V2_PREFIX.length());
        int split = payload.indexOf('.');
        if (split < 0 || split >= payload.length() - 1) return "";
        return decodeHistoryPart(payload.substring(split + 1));
    }

    public static ArrayList<ApiHistoryRecord> getApiHistoryRecords() {
        ArrayList<String> values = getNormalizedApiHistory();
        ArrayList<ApiHistoryRecord> records = new ArrayList<>();
        if (values == null) return records;
        for (String value : values) {
            if (TextUtils.isEmpty(value)) continue;
            records.add(new ApiHistoryRecord(getApiHistoryAddress(value), getApiHistoryRemark(value), value));
        }
        return records;
    }

    /**
     * 返回已规范化的点播配置历史。
     * 如果旧版本把当前线路 URL 写入了历史，则在可由已保存线路列表识别时，
     * 将它归并回原始点播入口地址，并按入口地址去重。
     */
    public static ArrayList<String> getNormalizedApiHistory() {
        ArrayList<String> stored = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
        ArrayList<String> result = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return result;

        ArrayList<String> lines = Hawk.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
        String lineSource = Hawk.get(HawkConfig.API_LINE_SOURCE, "");
        if (lines == null) lines = new ArrayList<>();

        // 先把当前线路关系累计到映射表，后续即使切到其他数据源并清空当前线路列表，
        // 历史里旧版本遗留的线路 URL 仍可归并回最初添加的点播入口地址。
        rememberApiLineSource(lineSource, lines);
        LinkedHashMap<String, String> lineHistoryMap = loadApiLineHistoryMap();

        Map<String, String> unique = new LinkedHashMap<>();
        for (String value : stored) {
            if (TextUtils.isEmpty(value)) continue;
            String address = getApiHistoryAddress(value);
            String remark = getApiHistoryRemark(value);
            if (TextUtils.isEmpty(address)) continue;

            String mappedSource = lineHistoryMap.get(address);
            if (!TextUtils.isEmpty(mappedSource)) {
                address = mappedSource;
            } else if (!TextUtils.isEmpty(lineSource)) {
                for (String line : lines) {
                    if (address.equals(getApiLineUrl(line))) {
                        address = lineSource;
                        break;
                    }
                }
            }
            if (!unique.containsKey(address)) {
                String normalizedValue = TextUtils.isEmpty(remark)
                        ? address : encodeApiHistory(address, remark);
                unique.put(address, normalizedValue);
            }
        }

        result.addAll(unique.values());
        if (!result.equals(stored)) {
            Hawk.put(HawkConfig.API_HISTORY, result);
        }
        return result;
    }

    /**
     * 读取线路映射表时不依赖 Hawk 反序列化后的具体 Map 实现。
     * 旧版本/不同 Gson 版本可能将 LinkedHashMap 还原为 HashMap；
     * 直接把 Hawk.get 的结果强转为 LinkedHashMap 会在打开数据源页面时崩溃。
     */
    private static LinkedHashMap<String, String> loadApiLineHistoryMap() {
        Object saved = Hawk.get(HawkConfig.API_LINE_HISTORY_MAP,
                new LinkedHashMap<String, String>());
        LinkedHashMap<String, String> mapping = new LinkedHashMap<>();
        if (saved instanceof Map) {
            Map<?, ?> savedMap = (Map<?, ?>) saved;
            for (Map.Entry<?, ?> entry : savedMap.entrySet()) {
                Object key = entry.getKey();
                Object value = entry.getValue();
                if (key != null && value != null) {
                    mapping.put(String.valueOf(key), String.valueOf(value));
                }
            }
        }
        return mapping;
    }

    /** 保存线路 URL 与其原始点播入口地址的关系，供历史配置清理和去重使用。 */
    public static void rememberApiLineSource(String source, List<String> lines) {
        if (TextUtils.isEmpty(source) || lines == null || lines.isEmpty()) return;
        LinkedHashMap<String, String> mapping = loadApiLineHistoryMap();
        for (String line : lines) {
            String url = getApiLineUrl(line);
            if (!TextUtils.isEmpty(url)) mapping.put(url, source);
        }
        // 避免用户长期切换大量站点后映射表无限增长。
        while (mapping.size() > 500) {
            Iterator<String> iterator = mapping.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        Hawk.put(HawkConfig.API_LINE_HISTORY_MAP, mapping);
    }


    public static String getHistoryNumName(int index){
        Integer value = getHisNum(index);
        return value + "条";
    }

    public static int getHisNum(int index){
        Integer value = null;
        if(index>=0 && index < hisNumArray.length){
            value = hisNumArray[index];
        }else{
            value = hisNumArray[0];
        }
        return value;
    }

    public static void setSearchHistory(String title){
        // 读取历史记录
        ArrayList<String> history = Hawk.get(HawkConfig.SEARCH_HISTORY, new ArrayList<String>());
        history.remove(title);
        history.add(0, title);
        // 保证最多只保留 30 条，超过的就删除最后一条
        if (history.size() > 30) {
            history.remove(history.size() - 1);
        }
        Hawk.put(HawkConfig.SEARCH_HISTORY, history);
    }

    public static void clearSearchHistory(){
        Hawk.put(HawkConfig.SEARCH_HISTORY, new ArrayList<String>());
    }

    public static void setLiveApiHistory(String value){
        ArrayList<String> history = Hawk.get(HawkConfig.LIVE_API_HISTORY, new ArrayList<String>());
        if (!history.contains(value)) {
            history.add(0, value);
        }
        if (history.size() > 30) {
            history.remove(30);
        }
        Hawk.put(HawkConfig.LIVE_API_HISTORY, history);
    }

    /** 新建点播历史必须同时具备地址和备注，避免产生无备注配置。 */
    public static void setApiHistory(String address, String remark){
        if (TextUtils.isEmpty(address) || TextUtils.isEmpty(remark)) return;
        address = address.trim();
        remark = remark.trim();
        if (address.isEmpty() || remark.isEmpty()) return;
        ArrayList<String> history = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
        if (history == null) history = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (address.equals(getApiHistoryAddress(history.get(i)))) {
                history.remove(i);
            }
        }
        history.add(0, encodeApiHistory(address, remark));
        while (history.size() > 30) history.remove(history.size() - 1);
        Hawk.put(HawkConfig.API_HISTORY, history);
    }

    public static String findApiHistoryRemark(String address) {
        if (TextUtils.isEmpty(address)) return "";
        ArrayList<String> history = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
        if (history == null) return "";
        for (String value : history) {
            if (address.equals(getApiHistoryAddress(value))) return getApiHistoryRemark(value);
        }
        return "";
    }

    public static String buildApiLine(String name, String url) {
        String lineName = name == null ? "" : name.trim();
        String lineUrl = url == null ? "" : url.trim();
        if (lineName.isEmpty()) {
            lineName = lineUrl;
        }
        return lineName + API_LINE_SPLIT + lineUrl;
    }

    public static String getApiLineName(String value) {
        if (value == null) return "";
        int splitIndex = value.indexOf(API_LINE_SPLIT);
        String name = splitIndex >= 0 ? value.substring(0, splitIndex) : value;
        return name.trim();
    }

    public static String getApiLineUrl(String value) {
        if (value == null) return "";
        int splitIndex = value.indexOf(API_LINE_SPLIT);
        String url = splitIndex >= 0 ? value.substring(splitIndex + API_LINE_SPLIT.length()) : value;
        return url.trim();
    }

    public static boolean isApiLineUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String trimUrl = url.trim();
        ArrayList<String> apiLines = Hawk.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
        if (apiLines == null) return false;
        for (String apiLine : apiLines) {
            if (trimUrl.equals(getApiLineUrl(apiLine))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isApiLineSource(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String source = Hawk.get(HawkConfig.API_LINE_SOURCE, "");
        return url.trim().equals(source);
    }

    public static boolean isApiLineHistory(String url) {
        return isApiLineSource(url) || isApiLineUrl(url);
    }

    public static void clearApiLineList() {
        Hawk.put(HawkConfig.API_LINE_LIST, new ArrayList<String>());
        Hawk.put(HawkConfig.API_LINE_SOURCE, "");
    }
}
