package com.rox.manager.service;

import android.net.Uri;

import com.rox.manager.ClashApiHelper;
import com.rox.manager.model.ApiResult;
import com.rox.manager.model.ClashStats;
import com.rox.manager.model.Connection;
import com.rox.manager.model.ProxyGroup;
import com.rox.manager.model.ProxyInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Service layer for Clash API interactions.
 * <p>
 * This class is the sole boundary between the transport layer (raw HTTP + JSON)
 * and the domain model layer. UI components should never call {@link ClashApiHelper}
 * directly or parse raw JSON responses.
 */
public final class ClashApiService {

    private final String baseUrl;

    public ClashApiService(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/(ui|dashboard)/?$", "");
    }

    // -- Stats ----------------------------------------------------------------

    /**
     * Fetches connection statistics (connection count, download/upload totals).
     */
    public ApiResult<ClashStats> getStats() {
        String raw = ClashApiHelper.get(baseUrl + "/connections");
        if (raw == null || raw.startsWith("Error")) {
            return ApiResult.error(raw != null ? raw : "No response");
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray conns = root.optJSONArray("connections");
            ClashStats stats = new ClashStats(
                    conns != null ? conns.length() : 0,
                    root.optLong("downloadTotal", 0),
                    root.optLong("uploadTotal", 0)
            );
            return ApiResult.success(stats);
        } catch (Exception e) {
            return ApiResult.error("Parse error: " + e.getMessage());
        }
    }

    // -- Proxies --------------------------------------------------------------

    /**
     * Fetches all proxy groups with their members, types, and latency data.
     */
    public ApiResult<List<ProxyGroup>> getProxyGroups() {
        String raw = ClashApiHelper.get(baseUrl + "/proxies");
        if (raw == null || raw.startsWith("Error")) {
            return ApiResult.error(raw != null ? raw : "No response");
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject proxies = root.optJSONObject("proxies");
            if (proxies == null) {
                return ApiResult.error("No proxies in response");
            }

            List<ProxyGroup> groups = new ArrayList<>();
            Iterator<String> keys = proxies.keys();
            while (keys.hasNext()) {
                String groupName = keys.next();
                JSONObject group = proxies.optJSONObject(groupName);
                if (group == null || !group.has("all")) continue;

                String type = group.optString("type", "");
                // Skip non-selectable system entries
                if (type.equals("Pass") || type.equals("Reject") || type.equals("Direct")) continue;

                String selected = group.optString("now", "");
                JSONArray all = group.optJSONArray("all");
                if (all == null) continue;

                List<ProxyInfo> proxyList = new ArrayList<>();
                for (int i = 0; i < all.length(); i++) {
                    String name = all.getString(i);
                    if (name.equalsIgnoreCase("DIRECT") || name.equalsIgnoreCase("REJECT")) {
                        proxyList.add(new ProxyInfo(name, "", -1));
                        continue;
                    }
                    JSONObject p = proxies.optJSONObject(name);
                    if (p != null) {
                        String pType = p.optString("type", "");
                        int delay = -1;
                        JSONArray history = p.optJSONArray("history");
                        if (history != null && history.length() > 0) {
                            delay = history.getJSONObject(history.length() - 1).optInt("delay", -1);
                        }
                        proxyList.add(new ProxyInfo(name, pType, delay));
                    } else {
                        proxyList.add(new ProxyInfo(name, "", -1));
                    }
                }
                groups.add(new ProxyGroup(groupName, type, selected, proxyList));
            }
            return ApiResult.success(groups);
        } catch (Exception e) {
            return ApiResult.error("Parse error: " + e.getMessage());
        }
    }

    /**
     * Switches the selected proxy within a group. Returns true on success.
     */
    public boolean switchProxy(String group, String name) {
        try {
            URL url = new URL(baseUrl + "/proxies/" + Uri.encode(group));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(2000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            String body = "{\"name\":\"" + name + "\"}";
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }

    // -- Connections ----------------------------------------------------------

    /**
     * Fetches the current list of active connections.
     */
    public ApiResult<List<Connection>> getConnections() {
        String raw = ClashApiHelper.get(baseUrl + "/connections");
        if (raw == null || raw.startsWith("Error")) {
            return ApiResult.error(raw != null ? raw : "No response");
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray conns = root.optJSONArray("connections");
            if (conns == null) {
                return ApiResult.success(new ArrayList<>());
            }

            List<Connection> list = new ArrayList<>();
            for (int i = 0; i < conns.length(); i++) {
                JSONObject item = conns.getJSONObject(i);
                list.add(parseConnection(item));
            }
            return ApiResult.success(list);
        } catch (Exception e) {
            return ApiResult.error("Parse error: " + e.getMessage());
        }
    }

    /**
     * Closes all active connections.
     */
    public void closeAllConnections() {
        ClashApiHelper.delete(baseUrl + "/connections");
    }

    /**
     * Flushes the Fake-IP cache.
     */
    public void flushFakeIpCache() {
        ClashApiHelper.post(baseUrl + "/cache/fakeip/flush", null);
    }

    // -- Private helpers ------------------------------------------------------

    private static Connection parseConnection(JSONObject item) {
        try {
            JSONObject metadata = item.optJSONObject("metadata");
            if (metadata == null) metadata = new JSONObject();

            String network = metadata.optString("network", "TCP").toUpperCase();
            String host = metadata.optString("host", "");
            String destIp = metadata.optString("destinationIP", "");
            String destPort = metadata.optString("destinationPort", "");
            String sourceIp = metadata.optString("sourceIP", "");
            String sourcePort = metadata.optString("sourcePort", "");
            String type = metadata.optString("type", "HTTP");

            if (host.isEmpty()) {
                host = destIp;
                if (!destPort.isEmpty()) host += ":" + destPort;
            } else if (!destPort.isEmpty()) {
                host += ":" + destPort;
            }

            String src = sourceIp;
            if (!sourcePort.isEmpty()) src += ":" + sourcePort;
            String dest = destIp;
            if (!destPort.isEmpty()) dest += ":" + destPort;

            JSONArray chain = item.optJSONArray("chains");
            String proxy = (chain != null && chain.length() > 0) ? chain.optString(0, "DIRECT") : "DIRECT";

            return new Connection(
                    host, network, src, dest, type, proxy,
                    item.optLong("upload", 0),
                    item.optLong("download", 0)
            );
        } catch (Exception e) {
            return new Connection("", "", "", "", "", "", 0, 0);
        }
    }
}
