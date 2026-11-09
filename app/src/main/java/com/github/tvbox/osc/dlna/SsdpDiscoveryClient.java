package com.github.tvbox.osc.dlna;

import com.github.tvbox.osc.util.LOG;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * A small, independent SSDP control-point discovery client.
 *
 * It intentionally does not use Cling's Android multicast receiver. Each
 * request socket is bound to a concrete IPv4 LAN address (including Ethernet),
 * so the unicast M-SEARCH replies return to the exact socket which sent them.
 */
final class SsdpDiscoveryClient {
    private static final String SSDP_GROUP = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final String AV_TRANSPORT_PREFIX = "urn:schemas-upnp-org:service:AVTransport:";
    private static final int RESPONSE_WINDOW_MS = 2600;
    private static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;

    interface Listener {
        void onEndpointFound(DlnaEndpoint endpoint);
    }

    private static final class LanAddress {
        final NetworkInterface networkInterface;
        final Inet4Address address;

        LanAddress(NetworkInterface networkInterface, Inet4Address address) {
            this.networkInterface = networkInterface;
            this.address = address;
        }
    }

    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MBox-SSDP-discovery");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final Set<MulticastSocket> activeSockets = Collections.newSetFromMap(new ConcurrentHashMap<MulticastSocket, Boolean>());
    private final Set<String> resolvedLocations = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private volatile boolean active;

    SsdpDiscoveryClient(Listener listener) {
        this.listener = listener;
    }

    void start() {
        active = true;
    }

    void search() {
        if (!active) return;
        if (!scanning.compareAndSet(false, true)) {
            LOG.i("DLNA SSDP scan already running; duplicate request ignored");
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    scanAllLanAddresses();
                } catch (Throwable error) {
                    LOG.e("DLNA SSDP scan crashed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
                } finally {
                    scanning.set(false);
                }
            });
        } catch (RuntimeException error) {
            scanning.set(false);
            LOG.e("DLNA SSDP executor rejected scan: " + error.getMessage());
        }
    }

    void stop() {
        active = false;
        for (MulticastSocket socket : activeSockets) {
            try { socket.close(); } catch (Exception ignored) { }
        }
        activeSockets.clear();
        resolvedLocations.clear();
        executor.shutdownNow();
    }

    private void scanAllLanAddresses() {
        List<LanAddress> addresses = enumerateLanAddresses();
        if (addresses.isEmpty()) {
            LOG.e("DLNA SSDP: no usable non-loopback IPv4 LAN interface found");
            return;
        }
        LOG.i("DLNA SSDP: scanning " + addresses.size() + " IPv4 LAN address(es): " + describeAddresses(addresses));
        for (LanAddress lan : addresses) {
            if (!active) return;
            scanOnAddress(lan);
        }
    }

    private List<LanAddress> enumerateLanAddresses() {
        List<LanAddress> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return result;
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                try {
                    if (!networkInterface.isUp() || networkInterface.isLoopback() || isVirtual(networkInterface.getName())) continue;
                    Enumeration<InetAddress> values = networkInterface.getInetAddresses();
                    while (values.hasMoreElements()) {
                        InetAddress address = values.nextElement();
                        if (address instanceof Inet4Address && !address.isLoopbackAddress()
                                && !address.isLinkLocalAddress() && !address.isAnyLocalAddress()) {
                            result.add(new LanAddress(networkInterface, (Inet4Address) address));
                        }
                    }
                } catch (Exception e) {
                    LOG.e("DLNA SSDP: cannot inspect interface " + networkInterface.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.e("DLNA SSDP: enumerate interfaces failed: " + e.getMessage());
        }
        Collections.sort(result, (left, right) -> interfacePriority(left.networkInterface.getName())
                - interfacePriority(right.networkInterface.getName()));
        return result;
    }

    private boolean isVirtual(String name) {
        if (name == null) return true;
        String value = name.toLowerCase(Locale.US);
        String[] prefixes = {"lo", "p2p", "tun", "tap", "rmnet", "dummy", "sit", "ip6tnl", "ipsec", "clat", "wg", "veth", "docker"};
        for (String prefix : prefixes) if (value.startsWith(prefix)) return true;
        return false;
    }

    private int interfacePriority(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.US);
        if (value.startsWith("eth") || value.startsWith("en")) return 0;
        if (value.startsWith("wlan") || value.startsWith("wifi")) return 1;
        return 2;
    }

    private String describeAddresses(List<LanAddress> addresses) {
        StringBuilder value = new StringBuilder();
        for (LanAddress address : addresses) {
            if (value.length() > 0) value.append(", ");
            value.append(address.networkInterface.getName()).append('=').append(address.address.getHostAddress());
        }
        return value.toString();
    }

    private void scanOnAddress(LanAddress lan) {
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket(null);
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(lan.address, 0));
            try {
                socket.setNetworkInterface(lan.networkInterface);
            } catch (Exception e) {
                // Binding the socket to the interface's IPv4 address still pins
                // the route on Android builds whose setNetworkInterface is limited.
                LOG.i("DLNA SSDP: setNetworkInterface(" + lan.networkInterface.getName() + ") unavailable: " + e.getMessage());
            }
            socket.setTimeToLive(2);
            socket.setSoTimeout(250);
            activeSockets.add(socket);
            LOG.i("DLNA SSDP: bound search socket to " + lan.networkInterface.getName() + "/"
                    + lan.address.getHostAddress() + ":" + socket.getLocalPort());

            String[] targets = new String[] {
                    "ssdp:all",
                    "upnp:rootdevice",
                    MEDIA_RENDERER,
                    AV_TRANSPORT_PREFIX + "1"
            };
            InetAddress group = InetAddress.getByName(SSDP_GROUP);
            for (String target : targets) {
                if (!active) return;
                String request = "M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: " + SSDP_GROUP + ":" + SSDP_PORT + "\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: 2\r\n"
                        + "ST: " + target + "\r\n"
                        + "USER-AGENT: Android/9 UPnP/1.1 MBox/3.0\r\n\r\n";
                byte[] bytes = request.getBytes(StandardCharsets.ISO_8859_1);
                socket.send(new DatagramPacket(bytes, bytes.length, group, SSDP_PORT));
                LOG.i("DLNA SSDP: M-SEARCH sent from " + lan.address.getHostAddress() + " ST=" + target);
            }

            long deadline = System.currentTimeMillis() + RESPONSE_WINDOW_MS;
            byte[] buffer = new byte[8192];
            Map<String, String[]> discoveredLocations = new LinkedHashMap<>();
            while (active && System.currentTimeMillis() < deadline && !socket.isClosed()) {
                int remaining = (int) Math.max(100L, Math.min(400L, deadline - System.currentTimeMillis()));
                socket.setSoTimeout(remaining);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (java.net.SocketTimeoutException timeout) {
                    continue;
                }
                String response = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.ISO_8859_1);
                if (!response.regionMatches(true, 0, "HTTP/1.1 200", 0, 12)
                        && !response.regionMatches(true, 0, "HTTP/1.0 200", 0, 12)) {
                    continue;
                }
                String location = header(response, "LOCATION");
                String st = header(response, "ST");
                String usn = header(response, "USN");
                if (location == null || location.length() == 0) {
                    LOG.i("DLNA SSDP: ignored response without LOCATION from " + packet.getAddress().getHostAddress());
                    continue;
                }
                LOG.i("DLNA SSDP: response from " + packet.getAddress().getHostAddress() + ":"
                        + packet.getPort() + " ST=" + st + " USN=" + usn + " LOCATION=" + location);
                if (!resolvedLocations.contains(location) && !discoveredLocations.containsKey(location)) {
                    discoveredLocations.put(location, new String[]{st == null ? "" : st, usn == null ? "" : usn});
                }
            }
            // Resolve only after the UDP receive window closes. A slow HTTP
            // descriptor must not block responses from other renderers.
            LOG.i("DLNA SSDP: receive window complete on " + lan.networkInterface.getName()
                    + "; unique LOCATION count=" + discoveredLocations.size());
            for (Map.Entry<String, String[]> item : discoveredLocations.entrySet()) {
                if (!active) return;
                DlnaEndpoint endpoint = resolveDescriptor(item.getKey(), item.getValue()[0], item.getValue()[1]);
                if (endpoint != null) resolvedLocations.add(item.getKey());
            }
        } catch (Exception e) {
            if (active) LOG.e("DLNA SSDP: search on " + lan.networkInterface.getName() + "/"
                    + lan.address.getHostAddress() + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (socket != null) {
                activeSockets.remove(socket);
                try { socket.close(); } catch (Exception ignored) { }
            }
        }
    }

    private String header(String message, String wanted) {
        String[] lines = message.split("\\r?\\n");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (line.substring(0, colon).trim().equalsIgnoreCase(wanted)) return line.substring(colon + 1).trim();
        }
        return null;
    }

    private DlnaEndpoint resolveDescriptor(String location, String st, String usn) {
        HttpURLConnection connection = null;
        try {
            URL descriptorUrl = new URL(location);
            connection = (HttpURLConnection) descriptorUrl.openConnection();
            connection.setConnectTimeout(4500);
            connection.setReadTimeout(4500);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "text/xml, application/xml, */*");
            connection.setRequestProperty("Connection", "close");
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                LOG.e("DLNA descriptor HTTP " + code + " for " + location);
                return null;
            }
            byte[] xml;
            try (InputStream input = connection.getInputStream()) {
                xml = readLimited(input, MAX_DESCRIPTOR_BYTES);
            }
            DlnaEndpoint endpoint = parseDeviceDescription(xml, descriptorUrl, location, usn);
            if (endpoint == null) {
                LOG.i("DLNA descriptor is not a supported MediaRenderer/AVTransport: " + location + " (ST=" + st + ")");
                return null;
            }
            LOG.i("DLNA endpoint resolved: name=" + endpoint.name + ", uuid=" + endpoint.id
                    + ", controlURL=" + endpoint.controlUrl + ", serviceType=" + endpoint.serviceType);
            if (active && listener != null) listener.onEndpointFound(endpoint);
            return endpoint;
        } catch (Exception e) {
            LOG.e("DLNA descriptor parse/fetch failed for " + location + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private byte[] readLimited(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) throw new IllegalStateException("UPnP descriptor exceeds " + maxBytes + " bytes");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private DlnaEndpoint parseDeviceDescription(byte[] xml, URL descriptorUrl, String location, String usn) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) { }
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) { }
        try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) { }
        try { factory.setXIncludeAware(false); } catch (Exception ignored) { }
        factory.setExpandEntityReferences(false);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

        String urlBaseText = firstText(document.getDocumentElement(), "URLBase");
        URL baseUrl = urlBaseText.length() == 0 ? descriptorUrl : new URL(descriptorUrl, urlBaseText);
        NodeList devices = elements(document.getDocumentElement(), "device");
        for (int i = 0; i < devices.getLength(); i++) {
            Node node = devices.item(i);
            if (!(node instanceof Element)) continue;
            Element device = (Element) node;
            String deviceType = firstText(device, "deviceType");
            if (!deviceType.toLowerCase(Locale.US).contains(":device:mediarenderer:")) continue;

            String friendlyName = firstText(device, "friendlyName");
            String udn = firstText(device, "UDN");
            if (udn.length() == 0) udn = uuidFromUsn(usn);
            if (udn.regionMatches(true, 0, "uuid:", 0, 5)) udn = udn.substring(5);
            if (udn.length() == 0) continue;

            NodeList services = elements(device, "service");
            for (int s = 0; s < services.getLength(); s++) {
                Node serviceNode = services.item(s);
                if (!(serviceNode instanceof Element)) continue;
                Element service = (Element) serviceNode;
                String serviceType = firstText(service, "serviceType");
                if (!serviceType.toLowerCase(Locale.US).startsWith(AV_TRANSPORT_PREFIX.toLowerCase(Locale.US))) continue;
                String control = firstText(service, "controlURL");
                if (control.length() == 0) continue;
                URL controlUrl = new URL(baseUrl, control);
                String name = friendlyName.length() == 0 ? "DLNA " + descriptorUrl.getHost() : friendlyName;
                return new DlnaEndpoint(udn, name, location, controlUrl.toExternalForm(), serviceType);
            }
        }
        return null;
    }

    private NodeList elements(Element root, String name) {
        NodeList nodes = root.getElementsByTagNameNS("*", name);
        if (nodes != null && nodes.getLength() > 0) return nodes;
        return root.getElementsByTagName(name);
    }

    private String firstText(Element root, String name) {
        NodeList nodes = elements(root, name);
        if (nodes == null || nodes.getLength() == 0) return "";
        Node node = nodes.item(0);
        return node == null || node.getTextContent() == null ? "" : node.getTextContent().trim();
    }

    private String uuidFromUsn(String usn) {
        if (usn == null) return "";
        String value = usn.trim();
        if (value.regionMatches(true, 0, "uuid:", 0, 5)) value = value.substring(5);
        int separator = value.indexOf("::");
        return separator >= 0 ? value.substring(0, separator) : value;
    }
}
