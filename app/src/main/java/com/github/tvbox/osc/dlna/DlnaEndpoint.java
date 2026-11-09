package com.github.tvbox.osc.dlna;

/** Resolved UPnP MediaRenderer endpoint used for discovery and SOAP control. */
final class DlnaEndpoint {
    final String id;
    final String name;
    final String location;
    final String controlUrl;
    final String serviceType;

    DlnaEndpoint(String id, String name, String location, String controlUrl, String serviceType) {
        this.id = id;
        this.name = name;
        this.location = location;
        this.controlUrl = controlUrl;
        this.serviceType = serviceType;
    }
}
