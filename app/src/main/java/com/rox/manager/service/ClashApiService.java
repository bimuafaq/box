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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Service layer for Clash API interactions.
 * <p>
 * This class is the sole boundary between the transport layer (raw HTTP + JSON)
 * and the domain model layer. UI components should never call {@link ClashApiHelper}
 * directly or parse raw JSON responses.
 */
public final class ClashApiService {

    private static final int TIMEOUT_PROXY = 5000;

    private final String baseUrl;

    public ClashApiService(String baseUrl) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
    }

    /**
     * Tests the latency of a single proxy. Returns the delay in milliseconds.
     * Does NOT fetch full proxy list - only hits the /delay endpoint.
     */
    public ApiResult<Integer> testProxyLatency(String proxyName) {
        HttpURLConnection conn = null;
        try {
            String urlStr = baseUrl + "/proxies/" + Uri.encode(proxyName)
                    + "/delay?timeout=5000&url=http%3A%2F%2Fwww.gstatic.com%2Fgenerate_204";
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_PROXY);
            conn.setReadTimeout(TIMEOUT_PROXY);

            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                return ApiResult.success(json.optInt("delay", -1));
            }
            return ApiResult.error("HTTP " + code);
        } catch (Exception e) {
            return ApiResult.error(e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // -- Stats ----------------------------------------------------------------

    /**
     * Fetches connection statistics (connection count, download/upload totals).
     */
    public ApiResult<ClashStats> getStats() {
        return fetchConnections().map(this::parseStatsOnly);
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
     * Switches the selected proxy within a group.
     */
    public ApiResult<Boolean> switchProxy(String group, String name) {
        try {
            String body = new JSONObject().put("name", name).toString();
            String raw = ClashApiHelper.put(baseUrl + "/proxies/" + Uri.encode(group), body);
            if (raw != null && !raw.startsWith("Error")) {
                return ApiResult.success(true);
            }
            return ApiResult.error(raw != null ? raw : "Unknown error");
        } catch (Exception e) {
            return ApiResult.error(e.getMessage());
        }
    }

    // -- Connections ----------------------------------------------------------

    /**
     * Fetches the current list of active connections.
     */
    public ApiResult<List<Connection>> getConnections() {
        return fetchConnections().map(this::parseConnectionsList);
    }

    /**
     * Closes all active connections.
     */
    public ApiResult<Void> closeAllConnections() {
        String raw = ClashApiHelper.delete(baseUrl + "/connections");
        if (raw != null && raw.startsWith("Error")) {
            return ApiResult.error(raw);
        }
        return ApiResult.success(null);
    }

    /**
     * Flushes the Fake-IP cache.
     */
    public ApiResult<Void> flushFakeIpCache() {
        String raw = ClashApiHelper.post(baseUrl + "/cache/fakeip/flush", "{}");
        if (raw != null && raw.startsWith("Error")) {
            return ApiResult.error(raw);
        }
        return ApiResult.success(null);
    }

    // -- Proxy Providers ------------------------------------------------------

    /**
     * Checks if any proxy providers exist in the config.
     * Returns a list of provider names, or empty list if none.
     * Only returns non-empty list when actual proxy providers (sub YAML files) exist.
     */
    public ApiResult<List<String>> getProxyProviderNames() {
        String raw = ClashApiHelper.get(baseUrl + "/providers/proxies");
        if (raw != null && raw.startsWith("Error")) {
            return ApiResult.error(raw);
        }
        try {
            JSONObject root = new JSONObject(raw != null ? raw : "{}");
            JSONObject providers = root.optJSONObject("providers");
            List<String> names = new ArrayList<>();
            if (providers != null && providers.length() > 0) {
                Iterator<String> keys = providers.keys();
                while (keys.hasNext()) {
                    String name = keys.next();
                    // Validate it's a real provider (has name, type, vehicleType fields)
                    JSONObject provider = providers.optJSONObject(name);
                    if (provider != null && provider.has("name") && provider.has("vehicleType")) {
                        // Only include File/HTTP type providers (not CompatibleProvider with inline proxies)
                        String vehicleType = provider.optString("vehicleType", "");
                        if (vehicleType.equalsIgnoreCase("File") || vehicleType.equalsIgnoreCase("HTTP")) {
                            names.add(name);
                        }
                    }
                }
            }
            return ApiResult.success(names);
        } catch (Exception e) {
            return ApiResult.error("Parse error: " + e.getMessage());
        }
    }

    /**
     * Updates (refreshes) a single proxy provider.
     * This fetches the latest config from the provider URL without restarting Clash.
     */
    public ApiResult<Boolean> updateProxyProvider(String providerName) {
        try {
            String body = "{}";
            String raw = ClashApiHelper.put(baseUrl + "/providers/proxies/" + Uri.encode(providerName), body);
            if (raw != null && !raw.startsWith("Error")) {
                return ApiResult.success(true);
            }
            return ApiResult.error(raw != null ? raw : "Unknown error");
        } catch (Exception e) {
            return ApiResult.error(e.getMessage());
        }
    }

    /**
     * Reloads the main Clash configuration without restarting the core.
     * Equivalent to restarting Clash but much faster.
     */
    public ApiResult<Boolean> reloadConfig() {
        try {
            String body = "{}";
            String raw = ClashApiHelper.put(baseUrl + "/configs", body);
            if (raw != null && !raw.startsWith("Error")) {
                return ApiResult.success(true);
            }
            return ApiResult.error(raw != null ? raw : "Unknown error");
        } catch (Exception e) {
            return ApiResult.error(e.getMessage());
        }
    }

    // -- Private helpers ------------------------------------------------------

    /** Fetches raw /connections response (shared by stats and list methods). */
    private ApiResult<String> fetchConnections() {
        String raw = ClashApiHelper.get(baseUrl + "/connections");
        if (raw == null || raw.startsWith("Error")) {
            return ApiResult.error(raw != null ? raw : "No response");
        }
        return ApiResult.success(raw);
    }

    /** Parses just the stats from a raw /connections response. */
    private ClashStats parseStatsOnly(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray conns = root.optJSONArray("connections");
            return new ClashStats(
                    conns != null ? conns.length() : 0,
                    root.optLong("downloadTotal", 0),
                    root.optLong("uploadTotal", 0)
            );
        } catch (Exception e) {
            return null;
        }
    }

    /** Parses the connection list from a raw /connections response. */
    private List<Connection> parseConnectionsList(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray conns = root.optJSONArray("connections");
            if (conns == null) return new ArrayList<>();

            List<Connection> list = new ArrayList<>();
            for (int i = 0; i < conns.length(); i++) {
                list.add(parseConnection(conns.getJSONObject(i)));
            }
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static Connection parseConnection(JSONObject item) {
        try {
            JSONObject metadata = item.optJSONObject("metadata");
            if (metadata == null) metadata = new JSONObject();

            String network = metadata.optString("network", "TCP").toUpperCase(Locale.ROOT);
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

    /** Normalizes the base URL by stripping UI/dashboard suffixes. */
    public static String normalizeBaseUrl(String url) {
        if (url == null) return "";
        return url.replaceAll("/(ui|dashboard)/?$", "");
    }
}
