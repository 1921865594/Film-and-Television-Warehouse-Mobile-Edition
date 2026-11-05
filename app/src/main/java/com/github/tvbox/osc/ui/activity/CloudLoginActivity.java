package com.github.tvbox.osc.ui.activity;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;

/** Opens the official cloud-drive login page with the requested browser user agent. */
public class CloudLoginActivity extends BaseActivity {
    public static final String EXTRA_PROVIDER = "extra_cloud_provider";

    private static final Pattern CHROME_VERSION = Pattern.compile("Chrome/([0-9.]+)");
    private static final long TOKEN_POLL_INTERVAL_MS = 1000L;
    private static final int MAX_TOKEN_POLL_ATTEMPTS = 600;
    private static final long COOKIE_POLL_INTERVAL_MS = 1000L;
    private static final int MAX_COOKIE_POLL_ATTEMPTS = 600;

    /**
     * Reads only the refresh_token field from the official Aliyun Drive page's own
     * localStorage. The scan prioritizes localStorage.token, then checks other keys
     * because the web client may change its storage wrapper between releases.
     */
    private static final String ALIYUN_TOKEN_EXTRACT_SCRIPT =
            "(function(){" +
            "try{" +
            "if(!window.localStorage)return {refreshToken:''};" +
            "function findToken(value,depth){" +
            "if(depth>8||value===null||typeof value==='undefined')return '';" +
            "if(typeof value==='string'){" +
            "var text=value.trim();if(!text)return '';" +
            // Preserve long digit-only token values as text instead of allowing JSON.parse
            // to round them through JavaScript's Number representation.
            "var rawMatch=/\"refresh_token\"\\s*:\\s*(\"[^\"]*\"|-?\\d+)/i.exec(text);" +
            "if(rawMatch){var rawValue=rawMatch[1];if(rawValue.charAt(0)===String.fromCharCode(34)){" +
            "try{rawValue=JSON.parse(rawValue);}catch(ignoreRaw){rawValue=rawValue.slice(1,-1);}}" +
            "rawValue=String(rawValue).trim();if(rawValue)return rawValue;}" +
            "if(text.charAt(0)==='{'||text.charAt(0)==='['||" +
            "(text.charAt(0)==='\"'&&text.charAt(text.length-1)==='\"')){" +
            "try{var parsed=JSON.parse(text);if(parsed!==value)return findToken(parsed,depth+1);}catch(ignore){}" +
            "}" +
            "return '';" +
            "}" +
            "if(Array.isArray(value)){for(var a=0;a<value.length;a++){var av=findToken(value[a],depth+1);if(av)return av;}return ''; }" +
            "if(typeof value==='object'){" +
            "var keys=Object.keys(value);" +
            "for(var i=0;i<keys.length;i++){if(String(keys[i]).toLowerCase()==='refresh_token'){" +
            "var candidate=value[keys[i]];if(typeof candidate==='string'||typeof candidate==='number'){" +
            "candidate=String(candidate).trim();if(candidate)return candidate;}}}" +
            "for(var j=0;j<keys.length;j++){var nested=findToken(value[keys[j]],depth+1);if(nested)return nested;}" +
            "}" +
            "return '';" +
            "}" +
            "var direct=localStorage.getItem('refresh_token');if(direct&&direct.trim())return {refreshToken:direct.trim()};" +
            "var primary=findToken(localStorage.getItem('token'),0);if(primary)return {refreshToken:primary};" +
            "for(var k=0;k<localStorage.length;k++){var key=localStorage.key(k);" +
            "if(key==='token'||key==='refresh_token')continue;" +
            "var found=findToken(localStorage.getItem(key),0);if(found)return {refreshToken:found};" +
            "}" +
            "return {refreshToken:''};" +
            "}catch(error){return {refreshToken:''};}" +
            "})();";

    private WebView webView;
    private ProgressBar progressBar;
    private View errorPanel;
    private String provider;
    private String startUrl;
    private final Handler tokenHandler = new Handler(Looper.getMainLooper());
    private int tokenPollAttempts;
    private int cookiePollAttempts;
    private boolean aliyunTokenAcquired;
    private boolean cookieCredentialAcquired;
    private boolean activityResumed;

    /**
     * Capture the complete WebView cookie header after an authenticated session appears.
     * Existing saved values are never cleared by an anonymous/pre-login page.
     */
    private final Runnable cookiePollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!activityResumed || webView == null || isFinishing() || isDestroyed()
                    || !isCookieProvider(provider) || cookieCredentialAcquired
                    || cookiePollAttempts >= MAX_COOKIE_POLL_ATTEMPTS) {
                return;
            }
            cookiePollAttempts++;
            if (!isProviderPage(webView.getUrl())) {
                tokenHandler.postDelayed(this, COOKIE_POLL_INTERVAL_MS);
                return;
            }
            // For Baidu, allow Network API requests to provide the complete outgoing
            // Cookie header first. CookieManager remains a delayed fallback if the
            // WebView does not expose a usable request header on this WebView build.
            if (CloudCredentialStore.BAIDU.equals(provider) && cookiePollAttempts <= 12) {
                tokenHandler.postDelayed(this, COOKIE_POLL_INTERVAL_MS);
                return;
            }

            String cookie = readProviderCookie(provider);
            if (hasAuthenticatedCookie(provider, cookie)) {
                String previous = CloudCredentialStore.get(provider);
                if (isNewAuthenticatedSession(provider, cookie, previous)) {
                    if (CloudCredentialStore.put(provider, cookie)) {
                        cookieCredentialAcquired = true;
                        CookieManager.getInstance().flush();
                        tokenHandler.removeCallbacks(this);
                        Toast.makeText(CloudLoginActivity.this,
                                CloudCredentialStore.getDisplayName(provider) + " 已自动获取并持久保存，可返回后长按图标复制",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                // If the browser still has the old saved login, keep watching. A later
                // explicit re-login can rotate the authentication cookies and replace it.
            }
            tokenHandler.postDelayed(this, COOKIE_POLL_INTERVAL_MS);
        }
    };

    private final Runnable tokenPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!activityResumed || webView == null || isFinishing() || isDestroyed()
                    || !CloudCredentialStore.ALIYUN.equals(provider)
                    || aliyunTokenAcquired || tokenPollAttempts >= MAX_TOKEN_POLL_ATTEMPTS) {
                return;
            }
            tokenPollAttempts++;
            if (!isAliyunPage(webView.getUrl())) {
                tokenHandler.postDelayed(this, TOKEN_POLL_INTERVAL_MS);
                return;
            }
            webView.evaluateJavascript(ALIYUN_TOKEN_EXTRACT_SCRIPT, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String result) {
                    if (!activityResumed || webView == null || isFinishing() || isDestroyed() || aliyunTokenAcquired) return;
                    if (!isAliyunPage(webView.getUrl())) return;
                    String refreshToken = parseRefreshTokenResult(result);
                    if (!refreshToken.isEmpty()) {
                        boolean changed = !refreshToken.equals(CloudCredentialStore.get(CloudCredentialStore.ALIYUN));
                        if (CloudCredentialStore.put(CloudCredentialStore.ALIYUN, refreshToken)) {
                            aliyunTokenAcquired = true;
                            tokenHandler.removeCallbacks(tokenPollRunnable);
                            Toast.makeText(CloudLoginActivity.this,
                                    changed ? "阿里云盘 refresh_token 已自动获取并持久保存，可返回后长按图标复制"
                                            : "已确认阿里云盘 refresh_token，原有值保持不变",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                    tokenHandler.postDelayed(tokenPollRunnable, TOKEN_POLL_INTERVAL_MS);
                }
            });
        }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_cloud_login;
    }

    @Override
    protected boolean shouldRefreshAutoSize() {
        return true;
    }

    @Override
    protected void init() {
        provider = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PROVIDER);
        startUrl = getStartUrl(provider);
        if (startUrl == null) {
            Toast.makeText(this, "不支持的网盘类型", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        TextView title = findViewById(R.id.tvCloudLoginTitle);
        progressBar = findViewById(R.id.cloudLoginProgress);
        errorPanel = findViewById(R.id.cloudLoginError);
        webView = findViewById(R.id.cloudLoginWebView);
        title.setText(getPageTitle(provider));

        findViewById(R.id.btnCloudLoginBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        });
        findViewById(R.id.btnCloudLoginRefresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (webView != null) webView.reload();
            }
        });
        findViewById(R.id.btnCloudLoginRetry).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                errorPanel.setVisibility(View.GONE);
                webView.loadUrl(startUrl);
            }
        });

        configureWebView();
        webView.loadUrl(startUrl);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        String deviceUserAgent = settings.getUserAgentString();
        settings.setUserAgentString(CloudCredentialStore.ALIYUN.equals(provider)
                ? buildMobileUserAgent(deviceUserAgent)
                : buildDesktopUserAgent(deviceUserAgent));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // Use the official pages' WebView session. Auth cookies are read only after
        // provider-specific login markers appear, then saved to app-private preferences.
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            /**
             * UC and Baidu may attach the usable Cookie header to their cloud-drive API
             * requests, where it is not always exposed reliably by CookieManager. Capture
             * the outgoing Request Headers directly. WebView invokes this callback on a
             * background thread; persistence and UI work are dispatched to the main thread.
             */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (CloudCredentialStore.UC.equals(provider)) {
                    captureUcRequestCookie(request);
                } else if (CloudCredentialStore.BAIDU.equals(provider)) {
                    captureBaiduRequestCookie(request);
                }
                return super.shouldInterceptRequest(view, request);
            }

            @SuppressWarnings("deprecation")
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                // Legacy callback does not expose request headers; the API-21+ callback handles it.
                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                tokenHandler.removeCallbacks(tokenPollRunnable);
                tokenHandler.removeCallbacks(cookiePollRunnable);
                tokenPollAttempts = 0;
                cookiePollAttempts = 0;
                errorPanel.setVisibility(View.GONE);
                progressBar.setProgress(0);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                if (CloudCredentialStore.ALIYUN.equals(provider) && isAliyunPage(url)
                        && !aliyunTokenAcquired) {
                    tokenHandler.removeCallbacks(tokenPollRunnable);
                    tokenPollAttempts = 0;
                    tokenHandler.post(tokenPollRunnable);
                } else if (isCookieProvider(provider) && isProviderPage(url)
                        && !cookieCredentialAcquired) {
                    tokenHandler.removeCallbacks(cookiePollRunnable);
                    cookiePollAttempts = 0;
                    tokenHandler.post(cookiePollRunnable);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request == null ? null : request.getUrl();
                String scheme = uri == null ? null : uri.getScheme();
                return !isHttpScheme(scheme);
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                return !isHttpScheme(uri.getScheme());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request == null || request.isForMainFrame()) showError();
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                showError();
            }
        });
    }

    private String buildMobileUserAgent(String deviceUserAgent) {
        if (deviceUserAgent == null) return "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.0.0 Mobile Safari/537.36";
        // Keep the installed Android System WebView's actual Chrome version and device details.
        return deviceUserAgent.replace("; wv", "").replace(" Version/4.0", "");
    }

    private String buildDesktopUserAgent(String deviceUserAgent) {
        String version = "100.0.0.0";
        if (deviceUserAgent != null) {
            Matcher matcher = CHROME_VERSION.matcher(deviceUserAgent);
            if (matcher.find()) version = matcher.group(1);
        }
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + version + " Safari/537.36";
    }

    private boolean isAliyunPage(String url) {
        if (url == null) return false;
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            return "aliyundrive.com".equals(host) || host.endsWith(".aliyundrive.com")
                    || "alipan.com".equals(host) || host.endsWith(".alipan.com");
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Read Cookie from UC's outgoing Network request headers. Only UC-owned hosts and
     * cloud-drive/API-style requests are considered; no request data is logged.
     */
    private void captureUcRequestCookie(WebResourceRequest request) {
        if (request == null || !CloudCredentialStore.UC.equals(provider)) return;
        Uri uri = request.getUrl();
        if (!isUcNetworkHost(uri)) return;

        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        boolean apiOrMainRequest = host.contains("api") || path.contains("/api/")
                || path.contains("/clouddrive/") || path.contains("/main");
        if (!apiOrMainRequest) return;

        Map<String, String> headers = request.getRequestHeaders();
        if (headers == null || headers.isEmpty()) return;
        String cookie = null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && "cookie".equalsIgnoreCase(entry.getKey())) {
                cookie = entry.getValue();
                break;
            }
        }
        if (TextUtils.isEmpty(cookie) || !hasAuthenticatedCookie(CloudCredentialStore.UC, cookie)) return;

        final String capturedCookie = cookie.trim();
        tokenHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()
                        || !CloudCredentialStore.UC.equals(provider)
                        || !hasAuthenticatedCookie(CloudCredentialStore.UC, capturedCookie)) {
                    return;
                }
                String previous = CloudCredentialStore.get(CloudCredentialStore.UC);
                if (!isNewAuthenticatedSession(CloudCredentialStore.UC, capturedCookie, previous)) return;
                if (CloudCredentialStore.put(CloudCredentialStore.UC, capturedCookie)) {
                    cookieCredentialAcquired = true;
                    CookieManager.getInstance().flush();
                    tokenHandler.removeCallbacks(cookiePollRunnable);
                    Toast.makeText(CloudLoginActivity.this,
                            "UC 网盘已从 Network 请求头自动获取 Cookie 并持久保存，可返回后长按图标复制",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /**
     * Capture the full Cookie header sent with Baidu Netdisk API/main requests.
     * This mirrors the value visible in DevTools Network -> Headers -> Request Headers.
     * The header is kept in memory only until its authentication markers are checked,
     * then persisted locally without ever logging the credential.
     */
    private void captureBaiduRequestCookie(WebResourceRequest request) {
        if (request == null || !CloudCredentialStore.BAIDU.equals(provider)) return;
        Uri uri = request.getUrl();
        if (!isBaiduDriveNetworkRequest(uri, request)) return;

        Map<String, String> headers = request.getRequestHeaders();
        if (headers == null || headers.isEmpty()) return;
        String cookie = null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && "cookie".equalsIgnoreCase(entry.getKey())) {
                cookie = entry.getValue();
                break;
            }
        }
        if (TextUtils.isEmpty(cookie) || !hasAuthenticatedCookie(CloudCredentialStore.BAIDU, cookie)) return;

        final String capturedCookie = cookie.trim();
        tokenHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()
                        || !CloudCredentialStore.BAIDU.equals(provider)
                        || cookieCredentialAcquired
                        || !hasAuthenticatedCookie(CloudCredentialStore.BAIDU, capturedCookie)) {
                    return;
                }
                String previous = CloudCredentialStore.get(CloudCredentialStore.BAIDU);
                if (isNewAuthenticatedSession(CloudCredentialStore.BAIDU, capturedCookie, previous)) {
                    if (!CloudCredentialStore.put(CloudCredentialStore.BAIDU, capturedCookie)) return;
                    Toast.makeText(CloudLoginActivity.this,
                            "百度网盘已从 Network 请求头自动获取并更新 Cookie，已持久保存，可返回后长按图标复制",
                            Toast.LENGTH_LONG).show();
                } else if (!TextUtils.isEmpty(previous)) {
                    // The request belongs to the already-saved authenticated session.
                    // Do not overwrite it merely because a page/API request was repeated.
                    Toast.makeText(CloudLoginActivity.this,
                            "已检测到百度网盘登录 Cookie，原有凭据保持不变",
                            Toast.LENGTH_LONG).show();
                } else {
                    return;
                }
                cookieCredentialAcquired = true;
                CookieManager.getInstance().flush();
                tokenHandler.removeCallbacks(cookiePollRunnable);
            }
        });
    }

    /**
     * Match Baidu Netdisk's own web/API hosts and useful API paths. Typical entries
     * include pan.baidu.com/api/list and pan.baidu.com/rest/2.0/xpan/file.
     */
    private boolean isBaiduDriveNetworkRequest(Uri uri, WebResourceRequest request) {
        if (uri == null || !isHttpScheme(uri.getScheme())) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        boolean netdiskHost = "pan.baidu.com".equals(host)
                || host.endsWith(".pan.baidu.com")
                || "yun.baidu.com".equals(host)
                || host.endsWith(".yun.baidu.com")
                || "pcs.baidu.com".equals(host)
                || host.endsWith(".pcs.baidu.com")
                || "baidupcs.com".equals(host)
                || host.endsWith(".baidupcs.com");
        if (!netdiskHost) return false;

        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        boolean apiRequest = path.contains("/api") || path.contains("/rest/")
                || path.contains("/xpan/") || path.contains("/pcs/")
                || path.contains("/main");
        return apiRequest || (request != null && request.isForMainFrame());
    }

    /** UC web-drive and API requests use subdomains of uc.cn (not unrelated domains). */
    private boolean isUcNetworkHost(Uri uri) {
        if (uri == null || !isHttpScheme(uri.getScheme())) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return "uc.cn".equals(host) || host.endsWith(".uc.cn");
    }

    private boolean isCookieProvider(String key) {
        return CloudCredentialStore.QUARK.equals(key)
                || CloudCredentialStore.UC.equals(key)
                || CloudCredentialStore.BAIDU.equals(key);
    }

    private boolean isProviderPage(String url) {
        if (url == null) return false;
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (CloudCredentialStore.QUARK.equals(provider)) {
                return "quark.cn".equals(host) || host.endsWith(".quark.cn");
            }
            if (CloudCredentialStore.UC.equals(provider)) {
                return "uc.cn".equals(host) || host.endsWith(".uc.cn");
            }
            if (CloudCredentialStore.BAIDU.equals(provider)) {
                return "baidu.com".equals(host) || host.endsWith(".baidu.com");
            }
            return isAliyunPage(url);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Read cookies natively so HttpOnly authentication cookies are included. */
    private String readProviderCookie(String key) {
        String[] urls;
        if (CloudCredentialStore.QUARK.equals(key)) {
            urls = new String[]{
                    // Quark login may set host-only cookies on the user/passport domain;
                    // cookies from that host are not necessarily returned for pan.quark.cn.
                    "https://pan.quark.cn/", "https://drive.quark.cn/",
                    "https://drive-pc.quark.cn/", "https://passport.quark.cn/",
                    "https://user.quark.cn/", "https://uop.quark.cn/",
                    "https://su.quark.cn/"
            };
        } else if (CloudCredentialStore.UC.equals(key)) {
            urls = new String[]{
                    // pc-api.uc.cn is the cloud-drive API host; its Cookie scope may
                    // differ from the visible drive.uc.cn page.
                    "https://pc-api.uc.cn/", "https://drive.uc.cn/",
                    "https://drive-pc.uc.cn/", "https://api.uc.cn/",
                    "https://uop.uc.cn/", "https://passport.uc.cn/"
            };
        } else if (CloudCredentialStore.BAIDU.equals(key)) {
            urls = new String[]{
                    "https://pan.baidu.com/", "https://passport.baidu.com/",
                    "https://yun.baidu.com/"
            };
        } else {
            return "";
        }

        Map<String, String> cookies = new LinkedHashMap<>();
        CookieManager manager = CookieManager.getInstance();
        for (String url : urls) {
            String header = manager.getCookie(url);
            if (TextUtils.isEmpty(header)) continue;
            String[] pairs = header.split(";");
            for (String pair : pairs) {
                String item = pair == null ? "" : pair.trim();
                int equals = item.indexOf('=');
                if (equals <= 0) continue;
                String name = item.substring(0, equals).trim();
                String value = item.substring(equals + 1).trim();
                if (!name.isEmpty() && !value.isEmpty()) {
                    // Follow the requested QC00/AUTH extraction rule, while retaining
                    // Quark's documented session cookies used by current cloud clients.
                    if (!CloudCredentialStore.QUARK.equals(key) || isQuarkCredentialCookieName(name)) {
                        cookies.put(name.toLowerCase(Locale.ROOT), name + "=" + value);
                    }
                }
            }
        }
        if (cookies.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (String pair : cookies.values()) {
            if (result.length() > 0) result.append("; ");
            result.append(pair);
        }
        return result.toString();
    }

    /** Reject tracking-only cookie sets; require a provider authentication marker. */
    private boolean hasAuthenticatedCookie(String key, String cookie) {
        if (TextUtils.isEmpty(cookie)) return false;
        Map<String, String> values = parseCookieValues(cookie);
        if (CloudCredentialStore.QUARK.equals(key)) {
            // The supplied extraction recipe selects cookie names containing QC00/AUTH.
            // Quark's commonly documented web-session cookies are included as a fallback.
            for (String name : values.keySet()) {
                if (isQuarkAuthCookieName(name)) return true;
            }
            boolean primary = !TextUtils.isEmpty(values.get("__pus"));
            boolean session = !TextUtils.isEmpty(values.get("__puus"))
                    || !TextUtils.isEmpty(values.get("__kps"))
                    || !TextUtils.isEmpty(values.get("__uid"));
            return primary && session;
        }
        if (CloudCredentialStore.UC.equals(key)) {
            // UC web-drive API requests commonly carry __puus as the decisive session
            // cookie. Some builds also send the older/multi-cookie combination.
            boolean puus = !TextUtils.isEmpty(values.get("__puus"));
            boolean primary = !TextUtils.isEmpty(values.get("__pus"));
            boolean session = !TextUtils.isEmpty(values.get("__kps"))
                    || !TextUtils.isEmpty(values.get("_up_a4a_11_"))
                    || !TextUtils.isEmpty(values.get("__uid"));
            return puus || (primary && session);
        }
        if (CloudCredentialStore.BAIDU.equals(key)) {
            // BDUSS is the primary Baidu account session cookie; some web builds
            // expose its BFESS variant instead. STOKEN alone is not sufficient.
            return !TextUtils.isEmpty(values.get("bduss"))
                    || !TextUtils.isEmpty(values.get("bduss_bfess"));
        }
        return false;
    }

    /** Compare only authentication cookies, so rotating analytics cookies cannot replace saved credentials. */
    private boolean isNewAuthenticatedSession(String key, String currentCookie, String previousCookie) {
        if (TextUtils.isEmpty(previousCookie)) return true;
        Map<String, String> current = parseCookieValues(currentCookie);
        Map<String, String> previous = parseCookieValues(previousCookie);
        String[] authNames;
        if (CloudCredentialStore.QUARK.equals(key)) {
            authNames = new String[]{"__pus", "__puus", "__kps", "_up_a4a_11_", "__kp", "__uid", "qc005", "qy_auth"};
        } else if (CloudCredentialStore.UC.equals(key)) {
            authNames = new String[]{"__pus", "__puus", "__kps", "_up_a4a_11_", "__kp", "__uid"};
        } else if (CloudCredentialStore.BAIDU.equals(key)) {
            authNames = new String[]{"bduss", "bduss_bfess", "stoken", "ptoken"};
        } else {
            return false;
        }
        boolean hasCurrentAuthValue = false;
        for (String name : authNames) {
            String currentValue = current.get(name);
            if (!TextUtils.isEmpty(currentValue)) hasCurrentAuthValue = true;
            if (CloudCredentialStore.BAIDU.equals(key)) {
                // A request can omit optional cookies such as STOKEN. Missing fields
                // must not be treated as a rotation and must never erase saved values.
                if (!TextUtils.isEmpty(currentValue)
                        && !currentValue.equals(previous.get(name))) return true;
            } else if (!equalsNullable(currentValue, previous.get(name))) {
                return true;
            }
        }
        if (CloudCredentialStore.QUARK.equals(key)) {
            // Also compare all dynamic QC00*/ *AUTH cookie names returned by the
            // requested name filter; these names can vary across Quark web releases.
            for (Map.Entry<String, String> entry : current.entrySet()) {
                if (isQuarkAuthCookieName(entry.getKey())) {
                    hasCurrentAuthValue = true;
                    if (!equalsNullable(entry.getValue(), previous.get(entry.getKey()))) return true;
                }
            }
        }
        return hasCurrentAuthValue && !hasAuthenticatedCookie(key, previousCookie);
    }

    private boolean isQuarkAuthCookieName(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("qc00") || normalized.contains("auth");
    }

    private boolean isQuarkCredentialCookieName(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String normalized = name.toLowerCase(Locale.ROOT);
        return isQuarkAuthCookieName(normalized)
                || "__pus".equals(normalized)
                || "__puus".equals(normalized)
                || "__kps".equals(normalized)
                || "__uid".equals(normalized)
                || "__kp".equals(normalized);
    }

    private Map<String, String> parseCookieValues(String cookie) {
        Map<String, String> values = new LinkedHashMap<>();
        if (TextUtils.isEmpty(cookie)) return values;
        String[] pairs = cookie.split(";");
        for (String pair : pairs) {
            String item = pair == null ? "" : pair.trim();
            int equals = item.indexOf('=');
            if (equals > 0) {
                values.put(item.substring(0, equals).trim().toLowerCase(Locale.ROOT),
                        item.substring(equals + 1).trim());
            }
        }
        return values;
    }

    private boolean equalsNullable(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private String parseRefreshTokenResult(String result) {
        if (result == null || result.isEmpty() || "null".equals(result)) return "";
        try {
            JSONObject response = new JSONObject(result);
            String token = response.optString("refreshToken", "");
            return token == null ? "" : token.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isHttpScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private void showError() {
        if (errorPanel != null) errorPanel.setVisibility(View.VISIBLE);
        if (progressBar != null) progressBar.setVisibility(View.GONE);
    }

    private String getStartUrl(String key) {
        if (CloudCredentialStore.ALIYUN.equals(key)) return "https://www.aliyundrive.com/sign/in";
        if (CloudCredentialStore.QUARK.equals(key)) return "https://pan.quark.cn/";
        if (CloudCredentialStore.UC.equals(key)) return "https://drive.uc.cn/";
        if (CloudCredentialStore.BAIDU.equals(key)) return "https://pan.baidu.com/login";
        return null;
    }

    private String getPageTitle(String key) {
        if (CloudCredentialStore.ALIYUN.equals(key)) return "阿里云盘登录";
        if (CloudCredentialStore.QUARK.equals(key)) return "夸克网盘登录";
        if (CloudCredentialStore.UC.equals(key)) return "UC 网盘登录";
        return "百度网盘登录";
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        tokenHandler.removeCallbacks(tokenPollRunnable);
        tokenHandler.removeCallbacks(cookiePollRunnable);
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (webView != null) {
            webView.onResume();
            if (CloudCredentialStore.ALIYUN.equals(provider) && !aliyunTokenAcquired
                    && isAliyunPage(webView.getUrl())) {
                tokenHandler.removeCallbacks(tokenPollRunnable);
                tokenHandler.post(tokenPollRunnable);
            } else if (isCookieProvider(provider) && !cookieCredentialAcquired
                    && isProviderPage(webView.getUrl())) {
                tokenHandler.removeCallbacks(cookiePollRunnable);
                tokenHandler.post(cookiePollRunnable);
            }
        }
    }

    @Override
    protected void onDestroy() {
        tokenHandler.removeCallbacksAndMessages(null);
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
