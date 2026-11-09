package com.github.tvbox.osc.dlna;

import com.github.tvbox.osc.util.LOG;

import org.fourthline.cling.transport.impl.NetworkAddressFactoryImpl;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;

/**
 * Android LAN network factory for Cling.
 *
 * Cling's AndroidNetworkAddressFactory attempts to modify private InetAddress
 * fields through reflection. On Android versions with hidden-API enforcement
 * this can fail and silently classify every IPv4 address as unusable. The
 * result is an UPnP stack with no bind addresses, so SSDP M-SEARCH packets are
 * never sent/received. This implementation deliberately uses the plain Cling
 * IPv4 validation and enumerates real LAN interfaces, including Ethernet.
 */
public class LanNetworkAddressFactory extends NetworkAddressFactoryImpl {

    public LanNetworkAddressFactory(int streamListenPort) {
        super(streamListenPort);
        logDiscoveredNetwork();
    }

    @Override
    protected boolean isUsableNetworkInterface(NetworkInterface networkInterface) throws Exception {
        if (!super.isUsableNetworkInterface(networkInterface)) {
            return false;
        }

        String name = networkInterface.getName();
        if (name == null) return false;
        String normalized = name.toLowerCase(Locale.ROOT);

        // These interfaces are not the user's normal LAN route. In particular,
        // p2p0 is Wi-Fi Direct and must not win over wlan0/eth0.
        if ("lo".equals(normalized) || normalized.startsWith("lo0")) {
            return false;
        }
        String[] ignoredPrefixes = {
                "p2p", "tun", "tap", "rmnet", "dummy", "ppp",
                "sit", "ip6tnl", "ipsec", "clat", "wg"
        };
        for (String prefix : ignoredPrefixes) {
            if (normalized.startsWith(prefix)) {
                LOG.i("DLNA network: ignoring virtual interface " + name);
                return false;
            }
        }
        return true;
    }

    /**
     * Do not use AndroidNetworkAddressFactory's private-field reflection.
     * The base implementation already filters to non-loopback IPv4 addresses.
     */
    @Override
    protected boolean isUsableAddress(NetworkInterface networkInterface, InetAddress address) {
        return super.isUsableAddress(networkInterface, address);
    }

    /**
     * Resolve an IPv4 source address on the interface receiving the SSDP
     * packet. Android's InterfaceAddress/prefix information is inconsistent
     * across vendor TV firmware, so use the interface's actual address list
     * as a reliable fallback.
     */
    @Override
    public InetAddress getLocalAddress(NetworkInterface networkInterface,
                                       boolean isIPv6,
                                       InetAddress remoteAddress) {
        try {
            Iterator<InetAddress> addresses = Collections.list(networkInterface.getInetAddresses()).iterator();
            while (addresses.hasNext()) {
                InetAddress address = addresses.next();
                if (isIPv6 && address instanceof Inet6Address) {
                    return address;
                }
                if (!isIPv6 && address instanceof Inet4Address
                        && !address.isLoopbackAddress()
                        && !address.isLinkLocalAddress()) {
                    return address;
                }
            }
        } catch (Exception e) {
            LOG.e("DLNA network: cannot enumerate addresses for " + networkInterface.getName()
                    + ": " + e.getMessage());
        }

        throw new IllegalStateException("No usable " + (isIPv6 ? "IPv6" : "IPv4")
                + " address on interface: " + networkInterface.getName());
    }

    private void logDiscoveredNetwork() {
        StringBuilder interfaces = new StringBuilder();
        Iterator<NetworkInterface> iterator = getNetworkInterfaces();
        while (iterator.hasNext()) {
            NetworkInterface networkInterface = iterator.next();
            if (interfaces.length() > 0) interfaces.append("; ");
            interfaces.append(networkInterface.getName()).append('=');
            try {
                Iterator<InetAddress> addresses = Collections.list(networkInterface.getInetAddresses()).iterator();
                boolean first = true;
                while (addresses.hasNext()) {
                    InetAddress address = addresses.next();
                    if (!(address instanceof Inet4Address)) continue;
                    if (!first) interfaces.append(',');
                    interfaces.append(address.getHostAddress());
                    first = false;
                }
            } catch (Exception e) {
                interfaces.append("<error:").append(e.getMessage()).append('>');
            }
        }

        StringBuilder bindAddresses = new StringBuilder();
        Iterator<InetAddress> bindIterator = getBindAddresses();
        while (bindIterator.hasNext()) {
            if (bindAddresses.length() > 0) bindAddresses.append(',');
            bindAddresses.append(bindIterator.next().getHostAddress());
        }
        LOG.i("DLNA network factory: interfaces=[" + interfaces + "], bindIPv4=["
                + bindAddresses + "], usable=" + hasUsableNetwork());
    }
}
