package com.rox.manager.model;

/**
 * Domain model for an active network connection.
 */
public final class Connection {
    private final String host;
    private final String network; // TCP, UDP, etc.
    private final String source;
    private final String destination;
    private final String type; // HTTP, HTTPS, etc.
    private final String proxy;
    private final long upload;
    private final long download;

    public Connection(String host, String network, String source, String destination,
                      String type, String proxy, long upload, long download) {
        this.host = host;
        this.network = network;
        this.source = source;
        this.destination = destination;
        this.type = type;
        this.proxy = proxy;
        this.upload = upload;
        this.download = download;
    }

    public String getHost() { return host; }
    public String getNetwork() { return network; }
    public String getSource() { return source; }
    public String getDestination() { return destination; }
    public String getType() { return type; }
    public String getProxy() { return proxy; }
    public long getUpload() { return upload; }
    public long getDownload() { return download; }
}
