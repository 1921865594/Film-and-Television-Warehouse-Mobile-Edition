package com.github.tvbox.osc.ui.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HistoryHelper;

import java.util.ArrayList;

/** 扫描 TVBox 推送端口后打开的端口网页，并在网页上提供本机配置入口。 */
public class PortActivity extends BaseActivity {
    public static final String EXTRA_PORT_URL = "extra_port_url";

    private WebView webView;
    private TextView tvPortAddress;
    private View tvPageError;
    private ProgressBar pageProgress;
    private View panelLocalConfig;
    private LinearLayout localHistoryContainer;
    private String portUrl;

    public static String normalizePortUrl(String value) {
        if (TextUtils.isEmpty(value)) return null;
        String address = value.trim();
        if (address.length() == 0 || address.contains("\n") || address.contains("\r") || address.contains(" ")) {
            return null;
        }
        String url;
        if (address.regionMatches(true, 0, "http://", 0, 7)
                || address.regionMatches(true, 0, "https://", 0, 8)) {
            url = address;
        } else {
            // TVBox 推送二维码通常只包含 IP:端口，默认使用 HTTP。
            if (address.contains("://")) return null;
            url = "http://" + address;
        }
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (TextUtils.isEmpty(uri.getHost())
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return null;
            }
            int port = uri.getPort();
            if (port == 0 || port > 65535) return null;
            return uri.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_scanned_port;
    }

    @Override
    protected boolean shouldRefreshAutoSize() {
        return true;
    }

    @Override
    protected void init() {
        portUrl = normalizePortUrl(getIntent().getStringExtra(EXTRA_PORT_URL));
        if (TextUtils.isEmpty(portUrl)) {
            Toast.makeText(this, "端口地址无效，请重新扫描", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        tvPortAddress = findViewById(R.id.tvPortAddress);
        tvPageError = findViewById(R.id.tvPageError);
        pageProgress = findViewById(R.id.pageProgress);
        webView = findViewById(R.id.portWebView);
        panelLocalConfig = findViewById(R.id.panelLocalConfig);
        localHistoryContainer = findViewById(R.id.localHistoryContainer);
        tvPortAddress.setText(portUrl);

        findViewById(R.id.btnPortBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnPortRefresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                FastClickCheckUtil.check(v);
                if (webView != null) webView.reload();
            }
        });
        findViewById(R.id.btnLocalConfig).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                FastClickCheckUtil.check(v);
                boolean show = panelLocalConfig.getVisibility() != View.VISIBLE;
                panelLocalConfig.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) buildLocalHistory();
            }
        });
        findViewById(R.id.btnCloseLocalConfig).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { panelLocalConfig.setVisibility(View.GONE); }
        });
        findViewById(R.id.btnRetryPortPage).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tvPageError.setVisibility(View.GONE);
                webView.reload();
            }
        });

        configureWebView();
        webView.loadUrl(portUrl);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                pageProgress.setProgress(newProgress);
                pageProgress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                tvPageError.setVisibility(View.GONE);
                pageProgress.setProgress(0);
                pageProgress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageProgress.setVisibility(View.GONE);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri == null ? null : uri.getScheme();
                // 端口页内的 HTTP(S) 链接在当前页面打开，其它 scheme 不交给 WebView。
                return !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                String scheme = uri.getScheme();
                return !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request == null || request.isForMainFrame()) showPageError();
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                showPageError();
            }
        });
    }

    private void showPageError() {
        if (tvPageError != null) tvPageError.setVisibility(View.VISIBLE);
        if (pageProgress != null) pageProgress.setVisibility(View.GONE);
    }

    private void buildLocalHistory() {
        localHistoryContainer.removeAllViews();
        ArrayList<HistoryHelper.ApiHistoryRecord> records = HistoryHelper.getApiHistoryRecords();
        // 点播配置历史始终排在网盘凭据前面；即使没有历史，也保留空状态后继续绘制网盘区域。
        addSectionTitle("点播配置历史");
        if (records == null || records.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无点播历史配置，请先在数据源地址中保存配置");
            empty.setTextColor(getResources().getColor(R.color.ui_muted));
            empty.setTextSize(14);
            empty.setPadding(dp(12), dp(16), dp(12), dp(16));
            localHistoryContainer.addView(empty);
        } else {
            for (HistoryHelper.ApiHistoryRecord record : records) {
                if (record == null) continue;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                row.setBackgroundResource(R.drawable.bg_dropdown_item);

                String remarkText = TextUtils.isEmpty(record.remark) ? "旧配置（未设置备注）" : record.remark;
                TextView remark = new TextView(this);
                remark.setText(remarkText);
                remark.setTextColor(getResources().getColor(R.color.ui_text_primary));
                remark.setTextSize(16);
                remark.setSingleLine(true);
                remark.setEllipsize(android.text.TextUtils.TruncateAt.END);
                remark.setOnLongClickListener(copyListener(record.remark, "备注名称"));
                row.addView(remark, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                TextView address = new TextView(this);
                address.setText(record.address == null ? "" : record.address);
                address.setTextColor(getResources().getColor(R.color.ui_muted));
                address.setTextSize(13);
                address.setPadding(0, dp(4), 0, 0);
                address.setOnLongClickListener(copyListener(record.address, "地址链接"));
                row.addView(address, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = dp(8);
                localHistoryContainer.addView(row, rowLp);
            }
        }
        addCloudCredentialRows();
    }

    private void addCloudCredentialRows() {
        addSectionTitle("网盘登录凭据");
        addCredentialRow(CloudCredentialStore.ALIYUN, "阿里云盘 Token");
        addCredentialRow(CloudCredentialStore.QUARK, "夸克网盘 Cookie");
        addCredentialRow(CloudCredentialStore.UC, "UC 网盘 Cookie");
        addCredentialRow(CloudCredentialStore.BAIDU, "百度网盘 Cookie");
    }

    private void addSectionTitle(String title) {
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(getResources().getColor(R.color.ui_text_primary));
        heading.setTextSize(15);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setPadding(dp(2), dp(10), dp(2), dp(6));
        localHistoryContainer.addView(heading, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addCredentialRow(String provider, String label) {
        String value = CloudCredentialStore.get(provider);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackgroundResource(R.drawable.bg_dropdown_item);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(getResources().getColor(R.color.ui_text_primary));
        name.setTextSize(14);
        row.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView credential = new TextView(this);
        credential.setText(TextUtils.isEmpty(value) ? "尚未获取或导入" : value);
        credential.setTextColor(getResources().getColor(
                TextUtils.isEmpty(value) ? R.color.ui_muted : R.color.ui_text_secondary));
        credential.setTextSize(12);
        credential.setPadding(0, dp(4), 0, 0);
        credential.setMaxLines(5);
        credential.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (!TextUtils.isEmpty(value)) {
            credential.setOnLongClickListener(copyListener(value, label));
        }
        row.addView(credential, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(6);
        localHistoryContainer.addView(row, rowLp);
    }

    private View.OnLongClickListener copyListener(final String text, final String label) {
        return new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (TextUtils.isEmpty(text)) {
                    Toast.makeText(PortActivity.this, label + "为空", Toast.LENGTH_SHORT).show();
                    return true;
                }
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
                Toast.makeText(PortActivity.this, label + "已复制", Toast.LENGTH_SHORT).show();
                return true;
            }
        };
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (panelLocalConfig != null && panelLocalConfig.getVisibility() == View.VISIBLE) {
            panelLocalConfig.setVisibility(View.GONE);
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
