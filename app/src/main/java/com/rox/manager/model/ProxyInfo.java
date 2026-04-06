package com.rox.manager.model;

/**
 * Domain model for a single proxy's metadata and latency.
 */
public final class ProxyInfo {
    private final String name;
    private final String type;
    private final int delayMs; // -1 if unavailable

    public ProxyInfo(String name, String type, int delayMs) {
        this.name = name;
        this.type = type;
        this.delayMs = delayMs;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public int getDelayMs() { return delayMs; }

    public String delayDisplay() {
        return delayMs > 0 ? delayMs + " ms" : "- ms";
    }
}
