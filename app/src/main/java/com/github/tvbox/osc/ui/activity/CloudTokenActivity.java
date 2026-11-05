package com.github.tvbox.osc.ui.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.util.FastClickCheckUtil;

/** 网盘登录入口及 Token/Cookie 凭据管理页。 */
public class CloudTokenActivity extends BaseActivity {
    private static final String[] PROVIDER_KEYS = {
            CloudCredentialStore.ALIYUN,
            CloudCredentialStore.QUARK,
            CloudCredentialStore.UC,
            CloudCredentialStore.BAIDU
    };
    private static final String[] PROVIDER_NAMES = {"阿里云盘", "夸克网盘", "UC网盘", "百度网盘"};

    private TextView tvAliyunStatus;
    private TextView tvQuarkStatus;
    private TextView tvUcStatus;
    private TextView tvBaiduStatus;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_cloud_token;
    }

    @Override
    protected boolean shouldRefreshAutoSize() {
        return true;
    }

    @Override
    protected void init() {
        findViewById(R.id.ivBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                onBackPressed();
            }
        });

        tvAliyunStatus = findViewById(R.id.tvAliyunStatus);
        tvQuarkStatus = findViewById(R.id.tvQuarkStatus);
        tvUcStatus = findViewById(R.id.tvUcStatus);
        tvBaiduStatus = findViewById(R.id.tvBaiduStatus);

        bindProviderCard(R.id.cardAliyun, CloudCredentialStore.ALIYUN);
        bindProviderCard(R.id.cardQuark, CloudCredentialStore.QUARK);
        bindProviderCard(R.id.cardUc, CloudCredentialStore.UC);
        bindProviderCard(R.id.cardBaidu, CloudCredentialStore.BAIDU);

        findViewById(R.id.btnManualImportCredential).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                showProviderPicker();
            }
        });
        refreshCredentialStatus();
    }

    private void bindProviderCard(int viewId, final String provider) {
        View card = findViewById(viewId);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                Intent intent = new Intent(CloudTokenActivity.this, CloudLoginActivity.class);
                intent.putExtra(CloudLoginActivity.EXTRA_PROVIDER, provider);
                startActivity(intent);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                copyCredential(provider);
                return true;
            }
        });
    }

    private void showProviderPicker() {
        new AlertDialog.Builder(this)
                .setTitle("选择要导入的网盘凭据")
                .setItems(PROVIDER_NAMES, (dialog, which) -> {
                    if (which >= 0 && which < PROVIDER_KEYS.length) {
                        showCredentialEditor(PROVIDER_KEYS[which]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCredentialEditor(final String provider) {
        final EditText input = new EditText(this);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(4);
        input.setMaxLines(8);
        input.setTextSize(14);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("粘贴从官方渠道取得的 " + CloudCredentialStore.getDisplayName(provider));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        String existing = CloudCredentialStore.get(provider);
        if (!TextUtils.isEmpty(existing)) input.setText(existing);

        new AlertDialog.Builder(this)
                .setTitle("导入" + CloudCredentialStore.getDisplayName(provider))
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String value = input.getText() == null ? "" : input.getText().toString();
                    boolean saved = CloudCredentialStore.put(provider, value);
                    if (!saved) {
                        Toast.makeText(this, "凭据保存失败，请重试", Toast.LENGTH_SHORT).show();
                    } else if (TextUtils.isEmpty(value.trim())) {
                        Toast.makeText(this, "已清除该网盘凭据", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "凭据已导入并持久保存", Toast.LENGTH_SHORT).show();
                    }
                    refreshCredentialStatus();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyCredential(String provider) {
        String value = CloudCredentialStore.get(provider);
        if (TextUtils.isEmpty(value)) {
            Toast.makeText(this, "尚未导入" + CloudCredentialStore.getDisplayName(provider), Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(CloudCredentialStore.getDisplayName(provider), value));
            Toast.makeText(this, CloudCredentialStore.getDisplayName(provider) + "已复制", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "无法访问剪贴板", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshCredentialStatus() {
        setStatus(tvAliyunStatus, CloudCredentialStore.ALIYUN);
        setStatus(tvQuarkStatus, CloudCredentialStore.QUARK);
        setStatus(tvUcStatus, CloudCredentialStore.UC);
        setStatus(tvBaiduStatus, CloudCredentialStore.BAIDU);
    }

    private void setStatus(TextView target, String provider) {
        if (target == null) return;
        if (CloudCredentialStore.has(provider)) {
            target.setText(CloudCredentialStore.ALIYUN.equals(provider)
                    ? "已获取 · 长按复制" : "已保存 · 长按复制");
        } else {
            target.setText("未获取");
        }
        target.setTextColor(getResources().getColor(
                CloudCredentialStore.has(provider) ? R.color.ui_accent : R.color.ui_muted));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCredentialStatus();
    }

    @Override
    public void finish() {
        applyMineBackTransition();
    }
}
