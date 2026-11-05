package com.github.tvbox.osc.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.text.InputType;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.dialog.ConfirmClearDialog;
import com.github.tvbox.osc.ui.tv.QRCodeGen;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.ShortDramaConfig;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 「数据源地址」页面：编辑点播 / 直播地址、扫码导入、本地配置文件导入，以及历史配置列表。
 * 历史配置记录在 {@link HawkConfig#API_HISTORY} 与 {@link HawkConfig#LIVE_API_HISTORY}，
 * 最新的排在最前面，可直接点选切换。
 */
public class ApiSettingActivity extends BaseActivity {

    private static final int REQUEST_LOCAL_CONFIG = 3001;

    private EditText inputApi;
    private EditText inputLive;
    private LinearLayout historyContainer;
    private LinearLayout apiLinePanel;
    private LinearLayout apiLineContainer;
    private TextView tvApiLineTitle;
    private TextView tvApiLineSelected;
    private TextView tvDramaSourceValue;
    private boolean selectLocalLive;
    private boolean resolvingApiSource;
    private String apiLineSource = "";
    private ArrayList<String> apiLines = new ArrayList<>();
    private boolean apiLinesExpanded = false;
    private String pendingApiRemark = "";
    /** 只有通过“新增点播地址”流程进入线路选择时，才在选中线路后写入历史。 */
    private boolean pendingApiHistoryRecord = false;
    /** 待写入历史的原始点播入口地址；不能使用用户最终选中的线路 URL。 */
    private String pendingApiHistoryAddress = "";

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_api_setting;
    }

    @Override
    public void finish() {
        // 从「我的」页进来的共享轴返回转场（BaseActivity 里统一管理）
        applyMineBackTransition();
    }

    @Override
    protected boolean shouldRefreshAutoSize() {
        return true;
    }

    @Override
    protected void init() {
        inputApi = findViewById(R.id.inputApi);
        inputLive = findViewById(R.id.inputLive);
        historyContainer = findViewById(R.id.historyContainer);
        apiLinePanel = findViewById(R.id.apiLinePanel);
        apiLineContainer = findViewById(R.id.apiLineContainer);
        tvApiLineTitle = findViewById(R.id.tvApiLineTitle);
        tvApiLineSelected = findViewById(R.id.tvApiLineSelected);
        tvApiLineTitle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (apiLineContainer == null || apiLineContainer.getChildCount() == 0) return;
                apiLinesExpanded = !apiLinesExpanded;
                apiLineContainer.setVisibility(apiLinesExpanded ? View.VISIBLE : View.GONE);
                updateApiLineHeader();
            }
        });
        findViewById(R.id.ivBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                onBackPressed();
            }
        });
        findViewById(R.id.tvSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                save();
            }
        });
        findViewById(R.id.btnPickApi).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                openLocalConfig(false);
            }
        });
        findViewById(R.id.btnPickLive).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                openLocalConfig(true);
            }
        });
        findViewById(R.id.btnPermission).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                requestStoragePermission();
            }
        });
        findViewById(R.id.tvClearHistory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                clearHistory();
            }
        });

        inputApi.setText(Hawk.get(HawkConfig.API_URL, ""));
        inputLive.setText(Hawk.get(HawkConfig.LIVE_API_URL, ""));
        loadSavedApiLines();

        tvDramaSourceValue = findViewById(R.id.tvDramaSourceValue);
        findViewById(R.id.rowDramaSource).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                jumpActivity(ShortDramaSourceActivity.class);
            }
        });
        refreshDramaSourceValue();

        refreshQRCode();
        buildHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「短剧源」页回来时数量可能变了
        refreshDramaSourceValue();
    }

    private void refreshDramaSourceValue() {
        if (tvDramaSourceValue == null) return;
        int count = ShortDramaConfig.enabledCount();
        tvDramaSourceValue.setText(count > 0
                ? getString(R.string.short_drama_entry_value, count)
                : getString(R.string.short_drama_entry_none));
    }

    /* ============================ 保存 / 切换 ============================ */

    private void save() {
        if (resolvingApiSource) {
            Toast.makeText(this, "正在获取线路，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        String api = inputApi.getText().toString().trim();
        String live = inputLive.getText().toString().trim();
        if (TextUtils.isEmpty(api)) {
            Toast.makeText(this, "点播地址不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        // 已识别的线路是当前点播源下的一个播放入口，不属于新增点播地址，不询问备注、不单独记历史。
        if (HistoryHelper.isApiLineUrl(api)) {
            applyApi(api, live, false, "");
            return;
        }
        // 已存在的点播地址沿用原备注；只有真正新增的点播地址才弹出备注输入框。
        String oldRemark = HistoryHelper.findApiHistoryRemark(api);
        if (!TextUtils.isEmpty(oldRemark)) {
            resolveApiAddress(api, live, false, oldRemark);
        } else {
            promptApiRemark(api, live);
        }
    }

    private void promptApiRemark(final String api, final String live) {
        final EditText remarkInput = new EditText(this);
        remarkInput.setSingleLine(true);
        remarkInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        remarkInput.setHint("例如：主线路、家庭线路、备用线路");
        String oldRemark = HistoryHelper.findApiHistoryRemark(api);
        if (!TextUtils.isEmpty(oldRemark)) remarkInput.setText(oldRemark);
        int horizontal = dp(20);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.setPadding(horizontal, dp(8), horizontal, 0);
        wrap.addView(remarkInput, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("点播地址备注")
                .setMessage("请为这个点播地址填写备注名称，确认后才会保存到历史配置。")
                .setView(wrap)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认", null)
                .create();
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface dialogInterface) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String remark = remarkInput.getText() == null ? "" : remarkInput.getText().toString().trim();
                        if (TextUtils.isEmpty(remark)) {
                            remarkInput.setError("备注不能为空");
                            remarkInput.requestFocus();
                            return;
                        }
                        pendingApiRemark = remark;
                        dialog.dismiss();
                        resolveApiAddress(api, live, true, remark);
                    }
                });
            }
        });
        dialog.show();
    }

    /** 回填当前配置所对应的线路（若当前 API_URL 就是线路集合地址，也可直接在此选择）。 */
    private void loadSavedApiLines() {
        ArrayList<String> saved = Hawk.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
        String savedSource = Hawk.get(HawkConfig.API_LINE_SOURCE, "");
        String currentApi = Hawk.get(HawkConfig.API_URL, "");
        if (saved == null || saved.isEmpty() || TextUtils.isEmpty(savedSource)) return;
        if (currentApi.equals(savedSource) || HistoryHelper.isApiLineUrl(currentApi)) {
            apiLines = new ArrayList<>(saved);
            apiLineSource = savedSource;
            refreshApiLineList();
        }
    }

    /**
     * 新增或从历史选择点播地址时，先请求地址本身：若返回 urls 线路集合就展开线路，
     * 不自动使用第一条；普通站源配置仍直接应用。
     */
    private void resolveApiAddress(final String api, final String live, final boolean record, final String remark) {
        final String address = api == null ? "" : api.trim();
        if (TextUtils.isEmpty(address)) return;
        inputApi.setText(address);

        // 已知线路地址无需再次探测；已知的集合地址也无需重复请求。
        if (HistoryHelper.isApiLineUrl(address)) {
            clearPendingApiHistoryRecord();
            applyApi(address, live, false, remark);
            return;
        }
        if ((address.equals(apiLineSource) || HistoryHelper.isApiLineSource(address)) && !apiLines.isEmpty()) {
            pendingApiRemark = remark == null ? "" : remark;
            pendingApiHistoryRecord = record;
            pendingApiHistoryAddress = record ? address : "";
            apiLineSource = address;
            apiLinesExpanded = false;
            refreshApiLineList();
            Toast.makeText(this, "请选择一条线路加载首页", Toast.LENGTH_SHORT).show();
            return;
        }

        resolvingApiSource = true;
        Toast.makeText(this, "正在获取点播线路…", Toast.LENGTH_SHORT).show();
        ApiConfig.get().fetchApiLines(address, new ApiConfig.ApiLinesCallback() {
            @Override
            public void success(ArrayList<String> lines) {
                resolvingApiSource = false;
                if (lines != null && !lines.isEmpty()) {
                    apiLines = new ArrayList<>(lines);
                    apiLineSource = address;
                    pendingApiRemark = remark == null ? "" : remark;
                    pendingApiHistoryRecord = record;
                    pendingApiHistoryAddress = record ? address : "";
                    apiLinesExpanded = false;
                    refreshApiLineList();
                    Toast.makeText(ApiSettingActivity.this,
                            "已获取 " + apiLines.size() + " 条线路，请选择后加载首页", Toast.LENGTH_LONG).show();
                } else {
                    clearPendingApiHistoryRecord();
                    applyApi(address, live, record, remark);
                }
            }

            @Override
            public void error(String message) {
                resolvingApiSource = false;
                Toast.makeText(ApiSettingActivity.this,
                        "线路获取失败，按普通点播地址尝试加载", Toast.LENGTH_SHORT).show();
                clearPendingApiHistoryRecord();
                applyApi(address, live, record, remark);
            }
        });
    }

    /** 将获取到的线路完整展开在点播地址下方，用户点选后才切换当前配置。 */
    private void refreshApiLineList() {
        if (apiLinePanel == null || apiLineContainer == null || tvApiLineTitle == null) return;
        apiLineContainer.removeAllViews();
        if (apiLines == null || apiLines.isEmpty() || TextUtils.isEmpty(apiLineSource)) {
            apiLinePanel.setVisibility(View.GONE);
            return;
        }
        String current = Hawk.get(HawkConfig.API_URL, "");
        for (int i = 0; i < apiLines.size(); i++) {
            final String item = apiLines.get(i);
            final String url = HistoryHelper.getApiLineUrl(item);
            if (TextUtils.isEmpty(url)) continue;
            if (i > 0) apiLineContainer.addView(makeApiLineDivider());

            final boolean selected = current.equals(url);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));
            row.setBackgroundResource(R.drawable.bg_dropdown_item);
            row.setClickable(true);
            row.setFocusable(true);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            TextView name = new TextView(this);
            String lineName = HistoryHelper.getApiLineName(item);
            name.setText(TextUtils.isEmpty(lineName) ? ("线路" + (i + 1)) : lineName);
            name.setTextColor(getResources().getColor(selected ? R.color.ui_accent : R.color.ui_text_primary));
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.ts_16));
            if (selected) name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            TextView address = new TextView(this);
            address.setText(url);
            address.setTextColor(getResources().getColor(R.color.ui_muted));
            address.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.ts_14));
            address.setSingleLine(true);
            address.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams addressLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            addressLp.topMargin = dp(2);
            texts.addView(name);
            texts.addView(address, addressLp);
            row.addView(texts, textsLp);

            TextView state = new TextView(this);
            state.setText(selected ? "当前线路" : "选择");
            state.setTextColor(getResources().getColor(selected ? R.color.ui_accent : R.color.ui_link));
            state.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.ts_14));
            row.addView(state, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    selectApiLine(item);
                }
            });
            apiLineContainer.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        boolean hasLines = apiLineContainer.getChildCount() > 0;
        apiLinePanel.setVisibility(hasLines ? View.VISIBLE : View.GONE);
        apiLineContainer.setVisibility(hasLines && apiLinesExpanded ? View.VISIBLE : View.GONE);
        updateApiLineHeader();
    }

    private void updateApiLineHeader() {
        if (tvApiLineTitle == null || tvApiLineSelected == null) return;
        int count = apiLines == null ? 0 : apiLines.size();
        String current = Hawk.get(HawkConfig.API_URL, "");
        String selectedName = "尚未选择线路";
        String selectedUrl = "";
        for (int i = 0; i < count; i++) {
            String item = apiLines.get(i);
            String url = HistoryHelper.getApiLineUrl(item);
            if (current.equals(url)) {
                selectedName = HistoryHelper.getApiLineName(item);
                selectedUrl = url;
                break;
            }
        }
        if (TextUtils.isEmpty(selectedName)) selectedName = "当前线路";
        tvApiLineTitle.setText("线路列表（" + count + " 条）" + (apiLinesExpanded ? "  ▲" : "  ▼"));
        tvApiLineSelected.setText(TextUtils.isEmpty(selectedUrl)
                ? selectedName
                : "当前选择：" + selectedName + "\n" + selectedUrl);
    }

    private View makeApiLineDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(getResources().getColor(R.color.ui_divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        divider.setLayoutParams(lp);
        return divider;
    }

    private void selectApiLine(String item) {
        String url = HistoryHelper.getApiLineUrl(item);
        if (TextUtils.isEmpty(url)) return;
        if (apiLines == null || apiLines.isEmpty() || TextUtils.isEmpty(apiLineSource)) return;
        // 只有用户明确点选后才将线路配置写入当前点播地址。
        Hawk.put(HawkConfig.API_LINE_LIST, new ArrayList<>(apiLines));
        Hawk.put(HawkConfig.API_LINE_SOURCE, apiLineSource);
        HistoryHelper.rememberApiLineSource(apiLineSource, apiLines);
        String live = inputLive.getText().toString().trim();
        String remark = pendingApiRemark;
        // 新增的是“点播入口地址”（例如返回 urls 的聚合地址），不是列表中的某条线路。
        // 备注仅在新增入口时填写一次；切换线路永远不弹备注框，也不把线路 URL 写入历史。
        if (pendingApiHistoryRecord
                && !TextUtils.isEmpty(pendingApiHistoryAddress)
                && !TextUtils.isEmpty(remark)) {
            HistoryHelper.setApiHistory(pendingApiHistoryAddress, remark);
            if (!TextUtils.isEmpty(live)) {
                HistoryHelper.setLiveApiHistory(live);
            }
        }
        clearPendingApiHistoryRecord();
        applyApi(url, live, false, remark);
    }

    private void clearPendingApiHistoryRecord() {
        pendingApiHistoryRecord = false;
        pendingApiHistoryAddress = "";
    }

    /**
     * @param record 是否写入历史配置
     */
    private void applyApi(final String api, String live, boolean record, String remark) {
        if (record && TextUtils.isEmpty(remark)) {
            Toast.makeText(this, "请先填写点播地址备注", Toast.LENGTH_SHORT).show();
            return;
        }
        String oldApi = Hawk.get(HawkConfig.API_URL, "");
        if (!HistoryHelper.isApiLineHistory(api)) {
            HistoryHelper.clearApiLineList();
            apiLines.clear();
            apiLineSource = "";
            refreshApiLineList();
        }
        Hawk.put(HawkConfig.API_URL, api);
        Hawk.put(HawkConfig.LIVE_API_URL, TextUtils.isEmpty(live) ? api : live);
        if (record) {
            // 防止其他入口误把当前线路地址写入配置历史；线路集合对应的历史地址始终是入口地址。
            String historyAddress = HistoryHelper.isApiLineUrl(api) && !TextUtils.isEmpty(apiLineSource)
                    ? apiLineSource : api;
            HistoryHelper.setApiHistory(historyAddress, remark);
            if (!TextUtils.isEmpty(live)) {
                HistoryHelper.setLiveApiHistory(live);
            }
        }
        clearPendingApiHistoryRecord();
        inputApi.setText(api);
        inputLive.setText(Hawk.get(HawkConfig.LIVE_API_URL, ""));
        pendingApiRemark = TextUtils.isEmpty(remark) ? "" : remark;
        buildHistory();
        if (!oldApi.equals(api)) {
            restartAppAfterConfigChanged();
        } else {
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        }
    }

    private void restartAppAfterConfigChanged() {
        Toast.makeText(this, "配置已切换，即将重新加载", Toast.LENGTH_SHORT).show();
        try {
            SourceViewModel.clearRuntimeCache();
        } catch (Throwable ignore) {
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                    if (intent == null) return;
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                } catch (Throwable ignore) {
                }
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, 1200);
    }

    /* ============================ 历史配置 ============================ */

    private void buildHistory() {
        if (historyContainer == null) return;
        historyContainer.removeAllViews();
        // 将旧版本曾保存的线路 URL 归并到其点播入口地址，避免历史列表出现线路内容。
        ArrayList<String> history = HistoryHelper.getNormalizedApiHistory();
        String current = Hawk.get(HawkConfig.API_URL, "");
        if (HistoryHelper.isApiLineUrl(current)) {
            String lineSource = Hawk.get(HawkConfig.API_LINE_SOURCE, "");
            if (!TextUtils.isEmpty(lineSource)) current = lineSource;
        }
        if (history == null) history = new ArrayList<>();
        if (history.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有历史配置，保存一次后会自动记录");
            empty.setTextColor(getResources().getColor(R.color.ui_muted));
            empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.ts_14));
            int pad = dp(12);
            empty.setPadding(pad, pad, pad, pad);
            historyContainer.addView(empty);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < history.size(); i++) {
            final String storageValue = history.get(i);
            final String api = HistoryHelper.getApiHistoryAddress(storageValue);
            final String remark = HistoryHelper.getApiHistoryRemark(storageValue);
            if (i > 0) historyContainer.addView(makeDivider());
            View row = inflater.inflate(R.layout.item_api_history_row, historyContainer, false);
            ((TextView) row.findViewById(R.id.tvApi)).setText(api);
            TextView tvRemark = row.findViewById(R.id.tvRemark);
            tvRemark.setText(TextUtils.isEmpty(remark) ? "旧配置（未设置备注）" : remark);
            row.findViewById(R.id.tvCurrent).setVisibility(api.equals(current) ? View.VISIBLE : View.GONE);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    pendingApiRemark = remark;
                    resolveApiAddress(api, "", false, remark);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    removeHistory(storageValue);
                    return true;
                }
            });
            row.findViewById(R.id.ivDel).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    removeHistory(storageValue);
                }
            });
            historyContainer.addView(row);
        }
    }

    private void removeHistory(String storageValue) {
        ArrayList<String> history = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
        if (history != null && history.remove(storageValue)) {
            Hawk.put(HawkConfig.API_HISTORY, history);
        }
        buildHistory();
    }

    private void clearHistory() {
        ArrayList<String> history = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
        if (history == null || history.isEmpty()) {
            Toast.makeText(this, "历史配置为空", Toast.LENGTH_SHORT).show();
            return;
        }
        ConfirmClearDialog dialog = new ConfirmClearDialog(mContext, "ApiHistory");
        dialog.setMessage("确定清空历史配置 ?");
        dialog.setOnConfirm(new Runnable() {
            @Override
            public void run() {
                Hawk.put(HawkConfig.API_HISTORY, new ArrayList<String>());
                buildHistory();
            }
        });
        dialog.show();
    }

    private View makeDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(getResources().getColor(R.color.ui_divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        divider.setLayoutParams(lp);
        return divider;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /* ============================ 扫码 / 本地文件 ============================ */

    private void refreshQRCode() {
        ImageView ivQRCode = findViewById(R.id.ivQRCode);
        TextView tvAddress = findViewById(R.id.tvAddress);
        try {
            String address = ControlManager.get().getAddress(false);
            tvAddress.setText(String.format("手机或电脑浏览器访问\n%s", address));
            ivQRCode.setImageBitmap(QRCodeGen.generateBitmap(address + "api.html", dp(96), dp(96)));
        } catch (Throwable th) {
            tvAddress.setText("服务未启动，无法扫码导入");
            ivQRCode.setImageDrawable(null);
        }
    }

    private void requestStoragePermission() {
        if (XXPermissions.isGranted(this, Permission.Group.STORAGE)) {
            Toast.makeText(this, "已获得存储权限", Toast.LENGTH_SHORT).show();
            return;
        }
        XXPermissions.with(this)
                .permission(Permission.Group.STORAGE)
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(List<String> permissions, boolean all) {
                        Toast.makeText(ApiSettingActivity.this, "已获得存储权限", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onDenied(List<String> permissions, boolean never) {
                        if (never) {
                            Toast.makeText(ApiSettingActivity.this, "获取存储权限失败,请在系统设置中开启", Toast.LENGTH_SHORT).show();
                            XXPermissions.startPermissionActivity(ApiSettingActivity.this, permissions);
                        } else {
                            Toast.makeText(ApiSettingActivity.this, "获取存储权限失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private void openLocalConfig(boolean live) {
        selectLocalLive = live;
        if (!XXPermissions.isGranted(this, Permission.Group.STORAGE)) {
            Toast.makeText(this, "请选择文件前需要先授予存储权限", Toast.LENGTH_SHORT).show();
            XXPermissions.with(this)
                    .permission(Permission.Group.STORAGE)
                    .request(new OnPermissionCallback() {
                        @Override
                        public void onGranted(List<String> permissions, boolean all) {
                            if (all) openLocalFileActivity(selectLocalLive);
                        }

                        @Override
                        public void onDenied(List<String> permissions, boolean never) {
                            if (never) {
                                Toast.makeText(ApiSettingActivity.this, "获取存储权限失败,请在系统设置中开启", Toast.LENGTH_SHORT).show();
                                XXPermissions.startPermissionActivity(ApiSettingActivity.this, permissions);
                            } else {
                                Toast.makeText(ApiSettingActivity.this, "获取存储权限失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
            return;
        }
        openLocalFileActivity(live);
    }

    private void openLocalFileActivity(boolean live) {
        Intent intent = new Intent(mContext, LocalFileActivity.class);
        intent.putExtra(LocalFileActivity.EXTRA_LIVE, live);
        startActivityForResult(intent, REQUEST_LOCAL_CONFIG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LOCAL_CONFIG || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        String api = localConfigToApi(data.getData());
        if (TextUtils.isEmpty(api)) {
            Toast.makeText(this, "读取本地配置失败", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectLocalLive) {
            inputLive.setText(api);
        } else {
            inputApi.setText(api);
        }
    }

    private String localConfigToApi(Uri uri) {
        String path = getPathFromUri(uri);
        if (TextUtils.isEmpty(path)) {
            path = copyUriToLocalConfig(uri);
        }
        if (TextUtils.isEmpty(path)) return "";
        String storageRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (path.startsWith(storageRoot)) {
            return "clan://localhost/" + path.substring(storageRoot.length()).replaceFirst("^/+", "");
        }
        path = copyUriToLocalConfig(uri);
        if (path != null && path.startsWith(storageRoot)) {
            return "clan://localhost/" + path.substring(storageRoot.length()).replaceFirst("^/+", "");
        }
        return "";
    }

    private String getPathFromUri(Uri uri) {
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return uri.getPath();
            }
            if (DocumentsContract.isDocumentUri(this, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                    String[] split = docId.split(":");
                    if (split.length > 1 && "primary".equalsIgnoreCase(split[0])) {
                        return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + split[1];
                    }
                }
                if ("com.android.providers.downloads.documents".equals(uri.getAuthority()) && docId.startsWith("raw:")) {
                    return docId.substring(4);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String copyUriToLocalConfig(Uri uri) {
        InputStream input = null;
        FileOutputStream output = null;
        try {
            input = getContentResolver().openInputStream(uri);
            if (input == null) return "";
            File dir = new File(FileUtils.getExternalCachePath(), "config");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, getDisplayName(uri));
            output = new FileOutputStream(file);
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return file.getAbsolutePath();
        } catch (Throwable th) {
            return "";
        } finally {
            try {
                if (output != null) output.close();
            } catch (Throwable ignored) {
            }
            try {
                if (input != null) input.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private String getDisplayName(Uri uri) {
        String name = "local_config.json";
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String displayName = cursor.getString(index);
                    if (!TextUtils.isEmpty(displayName)) name = displayName;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return name;
    }
}
