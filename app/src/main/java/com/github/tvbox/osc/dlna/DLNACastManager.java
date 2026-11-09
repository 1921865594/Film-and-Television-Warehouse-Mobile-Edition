package com.github.tvbox.osc.dlna;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.github.tvbox.osc.util.LOG;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DLNA control point manager.
 *
 * Discovery is performed by SsdpDiscoveryClient directly on each IPv4 LAN
 * interface. This avoids depending on Cling's Android multicast transport and
 * its device registry before a receiver can be shown in the device picker.
 * AVTransport control uses the controlURL resolved from the device descriptor.
 */
public class DLNACastManager {
    private static final DLNACastManager INSTANCE = new DLNACastManager();
    private static final ExecutorService CAST_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "MBox-DLNA-control");
        thread.setDaemon(true);
        return thread;
    });

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, CastDevice> devices = new LinkedHashMap<>();
    private final Map<String, DlnaEndpoint> endpoints = new ConcurrentHashMap<>();
    private SsdpDiscoveryClient discoveryClient;
    private volatile DeviceListener deviceListener;
    private volatile boolean initialized;
    private WifiManager.MulticastLock multicastLock;

    private DLNACastManager() {
    }

    public static DLNACastManager get() {
        return INSTANCE;
    }

    public synchronized void init(Context context) {
        if (initialized) {
            search();
            return;
        }
        Context appContext = context.getApplicationContext();
        acquireMulticastLock(appContext);
        discoveryClient = new SsdpDiscoveryClient(new SsdpDiscoveryClient.Listener() {
            @Override
            public void onEndpointFound(DlnaEndpoint endpoint) {
                onDiscoveredEndpoint(endpoint);
            }
        });
        initialized = true;
        discoveryClient.start();
        LOG.i("DLNA initialized: raw SSDP scanner active (Cling registry is not used for discovery)");
        search();
    }

    public synchronized void release(Context context) {
        initialized = false;
        SsdpDiscoveryClient oldClient = discoveryClient;
        discoveryClient = null;
        if (oldClient != null) oldClient.stop();
        synchronized (devices) {
            devices.clear();
        }
        endpoints.clear();
        releaseMulticastLock();
    }

    private void acquireMulticastLock(Context context) {
        try {
            if (multicastLock != null && multicastLock.isHeld()) return;
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                LOG.i("DLNA multicast lock: Wi-Fi service unavailable; Ethernet scan will continue without Wi-Fi lock");
                return;
            }
            multicastLock = wifiManager.createMulticastLock("mbox_dlna_ssdp");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            LOG.i("DLNA multicast lock acquired");
        } catch (Exception e) {
            LOG.e("DLNA multicast lock acquire failed: " + e.getMessage());
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception e) {
            LOG.e("DLNA multicast lock release failed: " + e.getMessage());
        }
        multicastLock = null;
    }

    public void setDeviceListener(DeviceListener listener) {
        this.deviceListener = listener;
    }

    public void search() {
        SsdpDiscoveryClient client = discoveryClient;
        if (!initialized || client == null) {
            LOG.i("DLNA search skipped: manager is not initialized");
            return;
        }
        LOG.i("DLNA search requested");
        client.search();
    }

    public List<CastDevice> getDevices() {
        synchronized (devices) {
            return new ArrayList<>(devices.values());
        }
    }

    private void onDiscoveredEndpoint(final DlnaEndpoint endpoint) {
        if (!initialized || endpoint == null || endpoint.id == null || endpoint.id.length() == 0) return;
        endpoints.put(endpoint.id, endpoint);
        final CastDevice device = new CastDevice(CastDevice.TYPE_DLNA, endpoint.id, endpoint.name);
        synchronized (devices) {
            devices.put(endpoint.id, device);
        }
        LOG.i("DLNA device available: " + endpoint.name + " [uuid=" + endpoint.id + ", control=" + endpoint.controlUrl + "]");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                DeviceListener listener = deviceListener;
                if (listener != null && initialized) listener.onDeviceChanged();
            }
        });
    }

    public void cast(final CastDevice device, final CastVideo video, final CastCallback callback) {
        if (device == null || video == null) {
            postFail(callback, "投屏参数无效");
            return;
        }
        final DlnaEndpoint endpoint = endpoints.get(device.getId());
        if (endpoint == null) {
            postFail(callback, "设备离线或尚未获取到 AVTransport 控制地址，请刷新设备列表");
            return;
        }
        LOG.i("DLNA cast start: device=" + endpoint.name + ", controlURL=" + endpoint.controlUrl + ", media=" + video.getUrl());
        CAST_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String metadata = buildMetaData(video);
                    String setUri = "<u:SetAVTransportURI xmlns:u=\"" + escapeXml(endpoint.serviceType) + "\">"
                            + "<InstanceID>0</InstanceID>"
                            + "<CurrentURI>" + escapeXml(video.getUrl()) + "</CurrentURI>"
                            + "<CurrentURIMetaData>" + escapeXml(metadata) + "</CurrentURIMetaData>"
                            + "</u:SetAVTransportURI>";
                    postSoapAction(endpoint, "SetAVTransportURI", setUri);

                    String play = "<u:Play xmlns:u=\"" + escapeXml(endpoint.serviceType) + "\">"
                            + "<InstanceID>0</InstanceID><Speed>1</Speed></u:Play>";
                    postSoapAction(endpoint, "Play", play);

                    if (video.getPosition() > 0) {
                        try {
                            String seek = "<u:Seek xmlns:u=\"" + escapeXml(endpoint.serviceType) + "\">"
                                    + "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>"
                                    + formatMs(video.getPosition()) + "</Target></u:Seek>";
                            postSoapAction(endpoint, "Seek", seek);
                        } catch (Exception e) {
                            LOG.i("DLNA Seek ignored: " + e.getMessage());
                        }
                    }
                    LOG.i("DLNA cast completed: " + endpoint.name);
                    postSuccess(callback);
                } catch (Exception e) {
                    LOG.e("DLNA cast failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    postFail(callback, e.getMessage() == null ? "DLNA 投屏失败" : e.getMessage());
                }
            }
        });
    }

    private String postSoapAction(DlnaEndpoint endpoint, String action, String actionXml) throws Exception {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body>" + actionXml + "</s:Body></s:Envelope>";
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint.controlUrl).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            connection.setRequestProperty("SOAPACTION", "\"" + endpoint.serviceType + "#" + action + "\"");
            connection.setRequestProperty("Connection", "close");
            byte[] request = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(request.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
                output.flush();
            }
            int status = connection.getResponseCode();
            InputStream responseStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = responseStream == null ? "" : readResponse(responseStream);
            if (status < 200 || status >= 300) {
                String detail = extractSoapFault(response);
                throw new IllegalStateException("DLNA " + action + " HTTP " + status
                        + (detail.length() == 0 ? "" : ": " + detail));
            }
            LOG.i("DLNA SOAP " + action + " succeeded (HTTP " + status + ")");
            return response;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readResponse(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            int total = 0;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > 256 * 1024) throw new IllegalStateException("DLNA SOAP response too large");
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String extractSoapFault(String xml) {
        if (xml == null || xml.length() == 0) return "";
        String code = extractTag(xml, "errorCode");
        String description = extractTag(xml, "errorDescription");
        if (code.length() == 0 && description.length() == 0) return "";
        return (code.length() == 0 ? "" : "code=" + code) + (description.length() == 0 ? "" : " " + description);
    }

    private String extractTag(String xml, String tag) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?is)<(?:[a-z0-9_]+:)?" + tag
                + "\\b[^>]*>(.*?)</(?:[a-z0-9_]+:)?" + tag + "\\s*>").matcher(xml);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String buildMetaData(CastVideo video) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ");
            sb.append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ");
            sb.append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">");
            sb.append("<item id=\"0\" parentID=\"-1\" restricted=\"0\">");
            sb.append("<dc:title>").append(escapeXml(video.getName())).append("</dc:title>");
            sb.append("<dc:creator></dc:creator>");
            sb.append("<upnp:class>object.item.videoItem</upnp:class>");
            java.util.HashMap<String, String> headers = video.getHeaders();
            if (headers != null && !headers.isEmpty()) {
                sb.append("<dc:description>").append(escapeXml(new Gson().toJson(headers))).append("</dc:description>");
            }
            sb.append("<res protocolInfo=\"http-get:*:video/*:*\">").append(escapeXml(video.getUrl())).append("</res>");
            sb.append("</item></DIDL-Lite>");
            return sb.toString();
        } catch (Exception e) {
            LOG.e("DLNA metadata build failed: " + e.getMessage());
            return "";
        }
    }

    private String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;")
                .replace("\"", "&quot;");
    }

    private String formatMs(long ms) {
        if (ms <= 0) return "00:00:00";
        long seconds = ms / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private void postSuccess(final CastCallback callback) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) callback.onResult(true, "");
            }
        });
    }

    private void postFail(final CastCallback callback, final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) callback.onResult(false, msg == null ? "投屏失败" : msg);
            }
        });
    }

    public interface DeviceListener {
        void onDeviceChanged();
    }

    public interface CastCallback {
        void onResult(boolean success, String msg);
    }
}
