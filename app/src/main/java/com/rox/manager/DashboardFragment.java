package com.rox.manager;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;

/**
 * DashboardFragment: The primary interface for service status and proxy management.
 * Optimized for performance with a single unified polling loop.
 */
public class DashboardFragment extends Fragment {
    private static final String TAG = "DashboardFragment";
    
    // View References
    private View initialLayout, webViewContainer, webHeader, emptyStatsView, dashHeader, btnLatency, btnOpen, cardRules, clashStatsCard, btnService;
    private TextView statusText, coreText, runtimeText, cpuText, ramText;
    private WebView webView;
    private TextView labelProxyGroups, clashConnectionsText, clashDownloadText, clashUploadText;
    private LinearLayout proxyGroupsContainer;
    private MaterialButton btnRefresh;
    private FloatingActionButton btnUpdateProviders;
    
    // Logic State
    private boolean isServiceRunning = false;
    private boolean isActionRunning = false;
    private boolean showClashStats = false;
    private long currentRuntimeSeconds = 0;
    private long statsCounter = 0;
    private int fastPollRemaining = 0;
    
    private SharedPreferences prefs;
    private OnBackPressedCallback backPressedCallback;

    // Unified Polling Handler
    private final Handler pollingHandler = new Handler(Looper.getMainLooper());
    private boolean isPollingActive = false;

    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPollingActive) return;

            // 1. Service Status & Runtime (Every 1s)
            refreshServiceStatus();
            if (isServiceRunning) {
                currentRuntimeSeconds++;
                updateRuntimeUI(currentRuntimeSeconds);
            }

            // 2. Heavy Stats (Every 2s, or 500ms if fast polling)
            boolean isFastPoll = fastPollRemaining > 0;
            if (isFastPoll || statsCounter % 2 == 0) {
                refreshServiceCoreStats();
                if (showClashStats) {
                    refreshClashStats();
                    refreshProxies();
                }
                if (isFastPoll) fastPollRemaining--;
            }

            statsCounter++;
            pollingHandler.postDelayed(this, isFastPoll ? 500 : 1000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dashboard, container, false);
        prefs = getActivity().getSharedPreferences("rox_prefs", Context.MODE_PRIVATE);

        // Bind Views
        initialLayout = view.findViewById(R.id.initialLayout);
        webViewContainer = view.findViewById(R.id.webViewContainer);
        webHeader = view.findViewById(R.id.webHeader);
        dashHeader = view.findViewById(R.id.dashHeader);
        webView = view.findViewById(R.id.dashWebView);
        emptyStatsView = view.findViewById(R.id.emptyStatsView);
        cardRules = view.findViewById(R.id.cardRules);
        proxyGroupsContainer = view.findViewById(R.id.proxyGroupsContainer);
        labelProxyGroups = view.findViewById(R.id.labelProxyGroups);
        clashStatsCard = view.findViewById(R.id.clashStatsCard);
        btnUpdateProviders = view.findViewById(R.id.btnUpdateProviders);
        
        clashConnectionsText = view.findViewById(R.id.clashConnectionsText);
        clashDownloadText = view.findViewById(R.id.clashDownloadText);
        clashUploadText = view.findViewById(R.id.clashUploadText);
        
        statusText = view.findViewById(R.id.statusText);
        coreText = view.findViewById(R.id.coreText);
        runtimeText = view.findViewById(R.id.runtimeText);
        cpuText = view.findViewById(R.id.cpuText);
        ramText = view.findViewById(R.id.ramText);

        btnOpen = view.findViewById(R.id.btnOpenFullWeb);
        btnRefresh = view.findViewById(R.id.btnRefreshDash);
        btnLatency = view.findViewById(R.id.btnLatencyDash);
        btnService = view.findViewById(R.id.btnService);
        
        MaterialButton btnClose = view.findViewById(R.id.btnCloseWeb);
        View btnRefreshWeb = view.findViewById(R.id.btnRefreshWeb);
        View btnRules = view.findViewById(R.id.btnRulesDash);
        View cardConnections = view.findViewById(R.id.cardConnections);

        setupWebView();

        // Listeners
        cardConnections.setOnClickListener(v -> startActivity(new Intent(getActivity(), ConnectionsActivity.class)));
        btnRules.setOnClickListener(v -> startActivity(new Intent(getActivity(), RulesActivity.class)));
        btnRefreshWeb.setOnClickListener(v -> {
            v.animate().rotationBy(360).setDuration(500).start();
            if (webView != null) webView.reload();
        });

        btnService.setOnClickListener(v -> handleServiceToggle());
        btnLatency.setOnClickListener(v -> testAllProxiesLatency());
        btnRefresh.setOnClickListener(v -> triggerManualRefresh(v));
        
        btnUpdateProviders.setOnClickListener(v -> updateAllProviders(v));
        
        btnOpen.setOnClickListener(v -> toggleWebView(true));
        btnClose.setOnClickListener(v -> toggleWebView(false));

        backPressedCallback = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { toggleWebView(false); }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backPressedCallback);

        return view;
    }

    private void updateAllProviders(View btn) {
        btn.animate().rotationBy(360).setDuration(1000).start();
        ThreadManager.runOnShell(() -> {
            String apiUrl = getApiUrl();
            String res = ShellHelper.runCommand("curl -s " + apiUrl + "/providers/proxies");
            if (res == null || res.startsWith("Error")) return;
            try {
                JSONObject root = new JSONObject(res);
                JSONObject providers = root.getJSONObject("providers");
                Iterator<String> keys = providers.keys();
                while (keys.hasNext()) {
                    String name = keys.next();
                    JSONObject provider = providers.getJSONObject(name);
                    String vehicleType = provider.optString("vehicleType", "");
                    if (!vehicleType.equals("Compatible") && !vehicleType.equals("Inline")) {
                        ShellHelper.runCommand("curl -s -X PUT " + apiUrl + "/providers/proxies/" + Uri.encode(name));
                    }
                }
                runOnUI(() -> {
                    if (getView() != null) Snackbar.make(getView(), "Providers updated", Snackbar.LENGTH_SHORT).show();
                    fastPollRemaining = 5;
                });
            } catch (Exception ignored) {}
        });
    }

    private void handleServiceToggle() {
        if (isActionRunning) return;
        if (isServiceRunning) {
            runServiceAction("/data/adb/box/scripts/box.iptables disable && /data/adb/box/scripts/box.service stop && pkill -f inotifyd", "Stopping...");
        } else {
            runServiceAction("/data/adb/box/scripts/box.service start && /data/adb/box/scripts/box.iptables enable && (pkill -f inotifyd; inotifyd /data/adb/box/scripts/box.inotify /data/adb/modules/box_for_root >/dev/null 2>&1 & inotifyd /data/adb/box/scripts/net.inotify /data/misc/net >/dev/null 2>&1 & inotifyd /data/adb/box/scripts/ctr.inotify /data/misc/net/rt_tables >/dev/null 2>&1 & /data/adb/box/scripts/net.inotify w manual)", "Starting...");
        }
    }

    private void triggerManualRefresh(View v) {
        v.animate().rotationBy(360).setDuration(500).start();
        fastPollRemaining = 5;
    }

    private void toggleWebView(boolean open) {
        if (open) {
            stopPolling();
            initialLayout.setVisibility(View.GONE);
            dashHeader.setVisibility(View.GONE);
            webHeader.setVisibility(View.VISIBLE);
            webViewContainer.setVisibility(View.VISIBLE);
            webView.loadUrl("http://127.0.0.1:9090/ui");
        } else {
            webHeader.setVisibility(View.GONE);
            webViewContainer.setVisibility(View.GONE);
            initialLayout.setVisibility(View.VISIBLE);
            dashHeader.setVisibility(View.VISIBLE);
            webView.loadUrl("about:blank");
            startPolling();
        }
        backPressedCallback.setEnabled(open);
    }

    @Override
    public void onResume() {
        super.onResume();
        showClashStats = prefs.getBoolean("enable_clash_api", false);
        updateClashUIVisibility();
        startPolling();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopPolling();
    }

    @Override
    public void onDestroyView() {
        stopPolling();
        cleanupWebView();
        nullifyViews();
        super.onDestroyView();
    }

    private void startPolling() {
        if (!isPollingActive) {
            isPollingActive = true;
            pollingHandler.post(pollingRunnable);
        }
    }

    private void stopPolling() {
        isPollingActive = false;
        pollingHandler.removeCallbacks(pollingRunnable);
    }

    private void updateClashUIVisibility() {
        int visibility = showClashStats ? View.VISIBLE : View.GONE;
        clashStatsCard.setVisibility(visibility);
        labelProxyGroups.setVisibility(visibility);
        cardRules.setVisibility(visibility);
        btnLatency.setVisibility(visibility);
        btnOpen.setVisibility(View.VISIBLE); // Always visible in header
        emptyStatsView.setVisibility(showClashStats ? View.GONE : View.VISIBLE);
        // btnUpdateProviders visibility will be managed dynamically after checking API
        if (!showClashStats) btnUpdateProviders.setVisibility(View.GONE);
    }

    private void refreshServiceStatus() {
        if (isActionRunning) return; // Don't overwrite starting/stopping text
        ThreadManager.runOnShell(() -> {
            String cmd = "PID=$(cat /data/adb/box/run/box.pid 2>/dev/null || echo \"0\"); " +
                         "CORE=$(grep '^bin_name=' /data/adb/box/settings.ini | cut -d '\"' -f 2); " +
                         "ETIME=$(ps -p $PID -o etime= 2>/dev/null || echo \"00:00\"); " +
                         "echo \"$PID|$CORE|$ETIME\"";
            String result = ShellHelper.runRootCommand(cmd);
            
            runOnUI(() -> {
                if (result != null && result.contains("|")) {
                    String[] parts = result.split("\\|");
                    String pid = parts[0].trim();
                    String core = (parts.length > 1) ? parts[1].trim() : "---";
                    String etime = (parts.length > 2) ? parts[2].trim() : "00:00";

                    isServiceRunning = pid.matches("\\d+") && !pid.equals("0");
                    updateServiceUI(isServiceRunning, core, pid, etime);
                }
            });
        });
    }

    private void updateServiceUI(boolean running, String core, String pid, String etime) {
        FloatingActionButton fab = (FloatingActionButton) btnService;
        if (running) {
            statusText.setText(R.string.status_running);
            statusText.setTextColor(MaterialColors.getColor(statusText, android.R.attr.colorPrimary));
            coreText.setText(String.format("%s (%s)", core.toUpperCase(), pid));
            currentRuntimeSeconds = parseETimeToSeconds(etime);
            fab.setImageResource(R.drawable.ic_stop);
            fab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(fab, com.google.android.material.R.attr.colorErrorContainer)));
        } else {
            statusText.setText(R.string.status_stopped);
            statusText.setTextColor(MaterialColors.getColor(statusText, android.R.attr.colorError));
            coreText.setText("---");
            runtimeText.setText("00:00:00");
            fab.setImageResource(R.drawable.ic_play_arrow);
            fab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(fab, com.google.android.material.R.attr.colorPrimaryContainer)));
        }
    }

    private void refreshServiceCoreStats() {
        ThreadManager.runOnShell(() -> {
            String cmd = "PID=$(cat /data/adb/box/run/box.pid 2>/dev/null || echo \"0\"); " +
                         "if [ \"$PID\" != \"0\" ]; then " +
                         "  RSS=$(grep VmRSS /proc/$PID/status | awk '{print $2}'); " +
                         "  CPU=$(ps -p $PID -o %cpu=); " +
                         "  CORE_ID=$(awk '{print $39}' /proc/$PID/stat); " +
                         "  echo \"$RSS|$CPU|$CORE_ID\"; " +
                         "else echo \"0|0|0\"; fi";
            String res = ShellHelper.runRootCommand(cmd);
            runOnUI(() -> {
                if (res != null && res.contains("|")) {
                    String[] parts = res.split("\\|");
                    try {
                        long rssKb = Long.parseLong(parts[0].trim());
                        String cpu = parts[1].trim();
                        String coreId = parts[2].trim();
                        ramText.setText(rssKb > 0 ? (rssKb >= 1024 ? (rssKb / 1024) + " MB" : rssKb + " KB") : "0 MB");
                        cpuText.setText(cpu.isEmpty() ? "0%" : cpu + "%");
                    } catch (Exception ignored) {}
                }
            });
        });
    }

    private void refreshClashStats() {
        ThreadManager.runOnShell(() -> {
            String result = ShellHelper.runCommand("curl -s --connect-timeout 1 " + getApiUrl() + "/connections");
            runOnUI(() -> {
                try {
                    JSONObject root = new JSONObject(result);
                    clashDownloadText.setText(formatSize(root.optLong("downloadTotal", 0)));
                    clashUploadText.setText(formatSize(root.optLong("uploadTotal", 0)));
                    JSONArray conns = root.optJSONArray("connections");
                    clashConnectionsText.setText(String.valueOf(conns != null ? conns.length() : 0));
                } catch (Exception ignored) {}
            });
        });
    }

    private void refreshProxies() {
        ThreadManager.runOnShell(() -> {
            String apiUrl = getApiUrl();
            String res = ShellHelper.runCommand("curl -s --connect-timeout 1 " + apiUrl + "/proxies");
            String provRes = ShellHelper.runCommand("curl -s --connect-timeout 1 " + apiUrl + "/providers/proxies");
            
            runOnUI(() -> {
                renderProxies(res);
                checkProvidersVisibility(provRes);
            });
        });
    }

    private void checkProvidersVisibility(String json) {
        if (json == null || json.startsWith("Error")) return;
        try {
            JSONObject root = new JSONObject(json);
            JSONObject providers = root.optJSONObject("providers");
            boolean hasExternalProviders = false;
            
            if (providers != null) {
                Iterator<String> keys = providers.keys();
                while (keys.hasNext()) {
                    String name = keys.next();
                    JSONObject provider = providers.getJSONObject(name);
                    String vehicleType = provider.optString("vehicleType", "");
                    if (!vehicleType.equals("Compatible") && !vehicleType.equals("Inline")) {
                        hasExternalProviders = true;
                        break;
                    }
                }
            }
            btnUpdateProviders.setVisibility(hasExternalProviders ? View.VISIBLE : View.GONE);
        } catch (Exception ignored) {}
    }

    private void renderProxies(String json) {
        if (json == null || json.startsWith("Error")) return;
        try {
            JSONObject root = new JSONObject(json);
            JSONObject proxies = root.optJSONObject("proxies");
            if (proxies == null) return;
            
            proxyGroupsContainer.removeAllViews();
            Iterator<String> keys = proxies.keys();
            while (keys.hasNext()) {
                String groupName = keys.next();
                JSONObject group = proxies.getJSONObject(groupName);
                if (!group.has("all")) continue;
                
                String type = group.optString("type", "");
                if (type.equals("Pass") || type.equals("Reject") || type.equals("Direct")) continue;

                addProxyGroupView(groupName, type, group.optString("now", ""), group.optJSONArray("all"), proxies);
            }
        } catch (Exception e) {
            Log.e(TAG, "Proxy render error", e);
        }
    }

    private void addProxyGroupView(String groupName, String type, String selected, JSONArray all, JSONObject allProxies) throws Exception {
        View groupView = LayoutInflater.from(getContext()).inflate(R.layout.item_proxy_group, proxyGroupsContainer, false);
        ((TextView) groupView.findViewById(R.id.proxyGroupName)).setText(groupName);
        ((TextView) groupView.findViewById(R.id.proxyGroupType)).setText(type);
        GridLayout itemsContainer = groupView.findViewById(R.id.proxyItemsContainer);
        
        for (int i = 0; i < all.length(); i++) {
            String proxyName = all.getString(i);
            View proxyCard = LayoutInflater.from(getContext()).inflate(R.layout.item_proxy, itemsContainer, false);
            TextView nameTxt = proxyCard.findViewById(R.id.proxyName);
            TextView typeTxt = proxyCard.findViewById(R.id.proxyType);
            TextView latencyTxt = proxyCard.findViewById(R.id.proxyLatency);
            MaterialCardView card = (MaterialCardView) proxyCard;

            nameTxt.setText(proxyName);
            if (proxyName.equalsIgnoreCase("DIRECT") || proxyName.equalsIgnoreCase("REJECT")) latencyTxt.setVisibility(View.GONE);

            JSONObject p = allProxies.optJSONObject(proxyName);
            if (p != null) {
                typeTxt.setText(p.optString("type", "Proxy"));
                JSONArray history = p.optJSONArray("history");
                if (history != null && history.length() > 0) {
                    int delay = history.getJSONObject(history.length() - 1).optInt("delay", 0);
                    latencyTxt.setText(delay > 0 ? delay + " ms" : "- ms");
                } else latencyTxt.setText("- ms");
            }

            if (proxyName.equals(selected)) {
                card.setStrokeColor(MaterialColors.getColor(card, android.R.attr.colorPrimary));
                card.setStrokeWidth(4);
            }

            card.setOnClickListener(v -> switchProxy(groupName, proxyName));
            
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(8, 8, 8, 8);
            proxyCard.setLayoutParams(params);
            
            itemsContainer.addView(proxyCard);
        }
        proxyGroupsContainer.addView(groupView);
    }

    private void switchProxy(String group, String name) {
        ThreadManager.runOnShell(() -> {
            ShellHelper.runCommand("curl -s -X PUT -d '{\"name\":\"" + name + "\"}' " + getApiUrl() + "/proxies/" + Uri.encode(group));
            runOnUI(this::refreshProxies);
        });
    }

    private void runServiceAction(String command, String msg) {
        isActionRunning = true;
        btnService.setEnabled(false);
        
        statusText.setText(msg);
        statusText.setTextColor(MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOutline));

        ThreadManager.runOnShell(() -> {
            ShellHelper.runRootCommandOneShot(command);
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            runOnUI(() -> {
                isActionRunning = false;
                refreshServiceStatus();
                btnService.setEnabled(true);
            });
        });
    }

    private void testAllProxiesLatency() {
        fastPollRemaining = 15;
        ThreadManager.runOnShell(() -> {
            String apiUrl = getApiUrl();
            String res = ShellHelper.runCommand("curl -s " + apiUrl + "/proxies");
            if (res == null || res.startsWith("Error")) return;
            try {
                JSONObject proxies = new JSONObject(res).getJSONObject("proxies");
                java.util.HashSet<String> uniqueProxies = new java.util.HashSet<>();
                
                Iterator<String> keys = proxies.keys();
                while (keys.hasNext()) {
                    String name = keys.next();
                    JSONObject p = proxies.getJSONObject(name);
                    String type = p.optString("type", "");
                    
                    if (type.equals("Selector") || type.equals("URLTest") || type.equals("Fallback")) {
                        JSONArray all = p.optJSONArray("all");
                        if (all != null) {
                            for (int i = 0; i < all.length(); i++) uniqueProxies.add(all.getString(i));
                        }
                    } else {
                        uniqueProxies.add(name);
                    }
                }

                java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(15);
                for (String proxyName : uniqueProxies) {
                    if (proxyName.equalsIgnoreCase("DIRECT") || proxyName.equalsIgnoreCase("REJECT")) continue;
                    
                    executor.submit(() -> {
                        java.net.HttpURLConnection conn = null;
                        try {
                            java.net.URL url = new java.net.URL(apiUrl + "/proxies/" + Uri.encode(proxyName) + "/delay?timeout=5000&url=http%3A%2F%2Fwww.gstatic.com%2Fgenerate_204");
                            conn = (java.net.HttpURLConnection) url.openConnection();
                            conn.setConnectTimeout(3000);
                            conn.setReadTimeout(5000);
                            conn.setRequestMethod("GET");
                            conn.getResponseCode();
                        } catch (Exception ignored) {
                        } finally {
                            if (conn != null) conn.disconnect();
                        }
                    });
                }
                executor.shutdown();
            } catch (Exception ignored) {}
        });
    }

    private void runOnUI(Runnable r) {
        if (isAdded() && getActivity() != null) getActivity().runOnUiThread(r);
    }

    private String getApiUrl() {
        return prefs.getString("dash_url", "http://127.0.0.1:9090/ui").replaceAll("/(ui|dashboard)/?$", "");
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        return String.format(Locale.getDefault(), "%.1f %cB", bytes / Math.pow(1024, exp), "KMGTPE".charAt(exp - 1));
    }

    private void updateRuntimeUI(long totalSeconds) {
        runtimeText.setText(String.format(Locale.getDefault(), "%02d:%02d:%02d", totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60));
    }

    private long parseETimeToSeconds(String etime) {
        try {
            String[] parts = etime.split(":");
            if (parts.length == 2) return Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1]);
            if (parts.length == 3) {
                if (parts[0].contains("-")) {
                    String[] dh = parts[0].split("-");
                    return Long.parseLong(dh[0]) * 86400 + Long.parseLong(dh[1]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2]);
                }
                return Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2]);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
    }

    private void cleanupWebView() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
    }

    private void nullifyViews() {
        initialLayout = webViewContainer = webHeader = emptyStatsView = dashHeader = btnLatency = btnOpen = cardRules = clashStatsCard = btnService = null;
        statusText = coreText = runtimeText = cpuText = ramText = labelProxyGroups = clashConnectionsText = clashDownloadText = clashUploadText = null;
        proxyGroupsContainer = null;
        btnRefresh = null;
        btnUpdateProviders = null;
    }
}
