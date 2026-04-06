package com.rox.manager.model;

import java.util.List;

/**
 * Domain model for a proxy group with its member proxies and current selection.
 */
public final class ProxyGroup {
    private final String name;
    private final String type;
    private final String selected;
    private final List<ProxyInfo> proxies;

    public ProxyGroup(String name, String type, String selected, List<ProxyInfo> proxies) {
        this.name = name;
        this.type = type;
        this.selected = selected;
        this.proxies = proxies;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getSelected() { return selected; }
    public List<ProxyInfo> getProxies() { return proxies; }
}
