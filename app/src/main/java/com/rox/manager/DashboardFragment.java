package com.rox.manager;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

import com.rox.manager.model.ApiResult;
import com.rox.manager.model.ClashStats;
import com.rox.manager.model.ProxyGroup;
import com.rox.manager.model.ProxyInfo;
import com.rox.manager.service.ClashApiService;

import java.util.List;
import java.util.Locale;

/**
 * DashboardFragment: The primary interface for service status and proxy management.
 * Optimized for performance with a single unified polling loop.
 *
 * <p>All Clash API interactions go through {@link ClashApiService}, which returns
 * typed domain models ({@link ClashStats}, {@link ProxyGroup}, {@link ProxyInfo}).
 * The UI layer never parses raw JSON.
 */
public class DashboardFragment extends Fragment {
    private static final String TAG = "DashboardFragment";

    // View References
    private View initialLayout, webViewContainer, webHeader, emptyStatsView, dashHeader, btnLatency, btnOpen, clashStatsCard, btnService;
    private MaterialCardView statusCard;
    private TextView statusText, coreText, runtimeText, cpuText, ramText;
    private WebView webView;
    private TextView labelProxyGroups, clashConnectionsText, clashDownloadText, clashUploadText;
    private LinearLayout proxyGroupsContainer;

    // Logic State
    private boolean isServiceRunning = false;
    private boolean lastServiceRunningState = false;
    private boolean isActionRunning = false;
    private boolean showClashStats = false;
    private long currentRuntimeSeconds = 0;
    private long statsCounter = 0;
    private String cachedCoreName = "";

    private SharedPreferences prefs;
    private OnBackPressedCallback backPressedCallback;
    private ClashApiService clashApiService;

    // Unified Polling Handler
    private final Handler pollingHandler = new Handler(Looper.getMainLooper());
    private boolean isPollingActive = false;

    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPollingActive) return;

            // 1. SERVICE STATUS & UPTIME (Every 1s - High Priority for stability)
            refreshServiceBaseStatus();

            if (isServiceRunning) {
                currentRuntimeSeconds++;
                updateRuntimeUI(currentRuntimeSeconds);

                // Auto 1sec refresh if service just started running
                if (!lastServiceRunningState && showClashStats) {
                    refreshProxies();
                }

                // API STATS (Connections, Up/Down) - Every 1s for real-time feel
                if (showClashStats) {
                    refreshClashStats();
                }
            }
            lastServiceRunningState = isServiceRunning;

            // 2. HEAVY STATS (CPU/RAM) - Every 2s to reduce shell and UI load
            if (statsCounter % 2 == 0) {
                if (isServiceRunning && !isActionRunning) {
                    refreshServiceHeavyStats();
                }
            }

            statsCounter++;
            pollingHandler.postDelayed(this, 1000);
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
        proxyGroupsContainer = view.findViewById(R.id.proxyGroupsContainer);
        labelProxyGroups = view.findViewById(R.id.labelProxyGroups);
        clashStatsCard = view.findViewById(R.id.clashStatsCard);

        clashConnectionsText = view.findViewById(R.id.clashConnectionsText);
        clashDownloadText = view.findViewById(R.id.clashDownloadText);
        clashUploadText = view.findViewById(R.id.clashUploadText);

        statusCard = view.findViewById(R.id.statusCard);
        statusText = view.findViewById(R.id.statusText);
        coreText = view.findViewById(R.id.coreText);
        runtimeText = view.findViewById(R.id.runtimeText);
        cpuText = view.findViewById(R.id.cpuText);
        ramText = view.findViewById(R.id.ramText);

        btnOpen = view.findViewById(R.id.btnOpenFullWeb);
        btnLatency = view.findViewById(R.id.btnLatencyDash);
        btnService = view.findViewById(R.id.btnService);

        MaterialButton btnClose = view.findViewById(R.id.btnCloseWeb);
        View btnRefreshWeb = view.findViewById(R.id.btnRefreshWeb);
        View cardConnections = view.findViewById(R.id.cardConnections);

        setupWebView();

        // Listeners
        cardConnections.setOnClickListener(v -> startActivity(new Intent(getActivity(), ConnectionsActivity.class)));
        btnRefreshWeb.setOnClickListener(v -> {
            v.animate().rotationBy(360).setDuration(500).start();
            if (webView != null) webView.reload();
        });

        btnService.setOnClickListener(v -> handleServiceToggle());
        btnLatency.setOnClickListener(v -> {
            if (!isServiceRunning) return;
            testAllProxiesLatency();
        });

        btnOpen.setOnClickListener(v -> toggleWebView(true));
        btnClose.setOnClickListener(v -> toggleWebView(false));

        backPressedCallback = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { toggleWebView(false); }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backPressedCallback);

        return view;
    }

    private ClashApiService getClashApiService() {
        if (clashApiService == null) {
            clashApiService = new ClashApiService(getApiUrl());
        }
        return clashApiService;
    }

    private void handleServiceToggle() {
        if (isActionRunning) return;
        if (isServiceRunning) {
            runServiceAction("/data/adb/box/scripts/box.iptables disable && /data/adb/box/scripts/box.service stop && pkill -f inotifyd", "Stopping...");
        } else {
            runServiceAction("/data/adb/box/scripts/box.service start && /data/adb/box/scripts/box.iptables enable && (pkill -f inotifyd; inotifyd /data/adb/box/scripts/box.inotify /data/adb/modules/box_for_root >/dev/null 2>&1 & inotifyd /data/adb/box/scripts/net.inotify /data/misc/net >/dev/null 2>&1 & inotifyd /data/adb/box/scripts/ctr.inotify /data/misc/net/rt_tables >/dev/null 2>&1 & /data/adb/box/scripts/net.inotify w manual)", "Starting...");
        }
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
        clashApiService = null;
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
        btnLatency.setVisibility(visibility);
        btnOpen.setVisibility(View.VISIBLE);
        emptyStatsView.setVisibility(showClashStats ? View.GONE : View.VISIBLE);
    }

    // -- Service Status (shell-based, not Clash API) --------------------------

    private void refreshServiceBaseStatus() {
        if (isActionRunning) return;

        ThreadManager.runOnShell(() -> {
            if (cachedCoreName.isEmpty() || statsCounter % 30 == 0) {
                String settings = ShellHelper.readRootFileDirect("/data/adb/box/settings.ini");
                if (settings != null) {
                    for (String line : settings.split("\n")) {
                        if (line.trim().startsWith("bin_name=")) {
                            cachedCoreName = line.split("=", 2)[1].replace("\"", "").trim();
                            break;
                        }
                    }
                }
                if (cachedCoreName.isEmpty()) cachedCoreName = "clash";
            }

            String cmd = "PID=$(cat /data/adb/box/run/box.pid 2>/dev/null || echo \"0\"); " +
                         "if [ \"$PID\" != \"0\" ]; then " +
                         "  ETIME=$(ps -p $PID -o etime= 2>/dev/null || echo \"00:00\"); " +
                         "  echo \"$PID|$ETIME\"; " +
                         "else echo \"0|00:00\"; fi";

            String result = ShellHelper.runRootCommand(cmd);

            runOnUI(() -> {
                if (result != null && result.contains("|")) {
                    String[] parts = result.split("\\|");
                    String pid = parts[0].trim();
                    String etime = parts[1].trim();

                    isServiceRunning = !pid.equals("0");
                    updateServiceUI(isServiceRunning, cachedCoreName, pid, etime);

                    if (!isServiceRunning) {
                        ramText.setText("0 MB");
                        cpuText.setText("0%");
                    }
                }
            });
        });
    }

    private void refreshServiceHeavyStats() {
        if (isActionRunning || !isServiceRunning) return;

        ThreadManager.runOnShell(() -> {
            String cmd = "PID=$(cat /data/adb/box/run/box.pid 2>/dev/null || echo \"0\"); " +
                         "if [ \"$PID\" != \"0\" ]; then " +
                         "  RSS=$(grep VmRSS /proc/$PID/status 2>/dev/null | awk '{print $2}' || echo \"0\"); " +
                         "  CPU=$(ps -p $PID -o %cpu= 2>/dev/null || echo \"0\"); " +
                         "  echo \"$RSS|$CPU\"; " +
                         "else echo \"0|0\"; fi";

            String res = ShellHelper.runRootCommand(cmd);
            runOnUI(() -> {
                if (res != null && res.contains("|") && ramText != null && cpuText != null) {
                    String[] parts = res.split("\\|");
                    try {
                        long rssKb = Long.parseLong(parts[0].trim());
                        String cpu = parts[1].trim();
                        ramText.setText(rssKb >= 1024 ? (rssKb / 1024) + " MB" : rssKb + " KB");
                        cpuText.setText(cpu.isEmpty() ? "0%" : cpu + "%");
                    } catch (Exception ignored) {}
                }
            });
        });
    }

    private void updateServiceUI(boolean running, String core, String pid, String etime) {
        if (statusText == null || btnService == null || coreText == null || runtimeText == null || statusCard == null) return;
        FloatingActionButton fab = (FloatingActionButton) btnService;
        if (running) {
            statusText.setText(String.format("PID: %s", pid));
            statusText.setTextColor(MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOnTertiaryContainer));
            statusCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(statusCard, com.google.android.material.R.attr.colorTertiaryContainer)));

            coreText.setText(core.toUpperCase());
            currentRuntimeSeconds = parseETimeToSeconds(etime);
            fab.setImageResource(R.drawable.ic_stop);
            fab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(fab, com.google.android.material.R.attr.colorErrorContainer)));
        } else {
            statusText.setText(R.string.status_stopped);
            statusText.setTextColor(MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOnErrorContainer));
            statusCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(statusCard, com.google.android.material.R.attr.colorErrorContainer)));

            runtimeText.setText("00:00:00");
            fab.setImageResource(R.drawable.ic_play_arrow);
            fab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(fab, com.google.android.material.R.attr.colorPrimaryContainer)));
        }
    }

    // -- Clash API (service layer) --------------------------------------------

    private void refreshClashStats() {
        ThreadManager.runBackgroundTask(() -> {
            ApiResult<ClashStats> result = getClashApiService().getStats();
            if (result.isSuccess() && result.getData() != null) {
                ClashStats stats = result.getData();
                runOnUI(() -> {
                    if (clashDownloadText == null || clashUploadText == null || clashConnectionsText == null) return;
                    clashDownloadText.setText(formatSize(stats.getDownloadTotal()));
                    clashUploadText.setText(formatSize(stats.getUploadTotal()));
                    clashConnectionsText.setText(String.valueOf(stats.getConnectionCount()));
                });
            }
        });
    }

    private void refreshProxies() {
        ThreadManager.runBackgroundTask(() -> {
            ApiResult<List<ProxyGroup>> result = getClashApiService().getProxyGroups();
            if (result.isSuccess() && result.getData() != null) {
                List<ProxyGroup> groups = result.getData();
                runOnUI(() -> renderProxyGroups(groups));
            }
        });
    }

    private void renderProxyGroups(List<ProxyGroup> groups) {
        proxyGroupsContainer.removeAllViews();
        for (ProxyGroup group : groups) {
            renderProxyGroup(group);
        }
    }

    private void renderProxyGroup(ProxyGroup group) {
        View groupView = LayoutInflater.from(getContext()).inflate(R.layout.item_proxy_group, proxyGroupsContainer, false);
        ((TextView) groupView.findViewById(R.id.proxyGroupName)).setText(group.getName());
        ((TextView) groupView.findViewById(R.id.proxyGroupType)).setText(group.getType());
        GridLayout itemsContainer = groupView.findViewById(R.id.proxyItemsContainer);

        for (ProxyInfo proxy : group.getProxies()) {
            View proxyCard = LayoutInflater.from(getContext()).inflate(R.layout.item_proxy, itemsContainer, false);
            TextView nameTxt = proxyCard.findViewById(R.id.proxyName);
            TextView typeTxt = proxyCard.findViewById(R.id.proxyType);
            TextView latencyTxt = proxyCard.findViewById(R.id.proxyLatency);
            MaterialCardView card = (MaterialCardView) proxyCard;

            nameTxt.setText(proxy.getName());
            if (proxy.getName().equalsIgnoreCase("DIRECT") || proxy.getName().equalsIgnoreCase("REJECT")) {
                latencyTxt.setVisibility(View.GONE);
            }

            typeTxt.setText(proxy.getType().isEmpty() ? "Proxy" : proxy.getType());
            latencyTxt.setText(proxy.delayDisplay());

            if (proxy.getName().equals(group.getSelected())) {
                card.setStrokeColor(MaterialColors.getColor(card, android.R.attr.colorPrimary));
                card.setStrokeWidth(4);
            }

            card.setOnClickListener(v -> switchProxy(group.getName(), proxy.getName()));

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
        // 1. Optimistic UI update
        for (int i = 0; i < proxyGroupsContainer.getChildCount(); i++) {
            View groupView = proxyGroupsContainer.getChildAt(i);
            TextView groupNameTxt = groupView.findViewById(R.id.proxyGroupName);
            if (groupNameTxt != null && groupNameTxt.getText().toString().equals(group)) {
                GridLayout itemsContainer = groupView.findViewById(R.id.proxyItemsContainer);
                if (itemsContainer != null) {
                    int primaryColor = MaterialColors.getColor(getContext(), android.R.attr.colorPrimary, android.graphics.Color.BLUE);
                    for (int j = 0; j < itemsContainer.getChildCount(); j++) {
                        View cardView = itemsContainer.getChildAt(j);
                        if (cardView instanceof MaterialCardView) {
                            MaterialCardView card = (MaterialCardView) cardView;
                            TextView proxyNameTxt = card.findViewById(R.id.proxyName);
                            if (proxyNameTxt != null) {
                                if (proxyNameTxt.getText().toString().equals(name)) {
                                    card.setStrokeColor(primaryColor);
                                    card.setStrokeWidth(4);
                                } else if (card.getStrokeWidth() > 0) {
                                    card.setStrokeWidth(0);
                                }
                            }
                        }
                    }
                }
                break;
            }
        }

        // 2. Background API call via service layer
        ThreadManager.runBackgroundTask(() -> {
            boolean success = getClashApiService().switchProxy(group, name);
            if (success) {
                refreshProxies();
            } else {
                runOnUI(this::refreshProxies);
            }
        });
    }

    private void testAllProxiesLatency() {
        ThreadManager.runBackgroundTask(() -> {
            ApiResult<List<ProxyGroup>> result = getClashApiService().getProxyGroups();
            if (!result.isSuccess() || result.getData() == null) return;

            java.util.HashSet<String> uniqueProxies = new java.util.HashSet<>();
            for (ProxyGroup group : result.getData()) {
                if (group.getType().equals("Selector") || group.getType().equals("URLTest") || group.getType().equals("Fallback")) {
                    for (ProxyInfo proxy : group.getProxies()) {
                        uniqueProxies.add(proxy.getName());
                    }
                } else {
                    uniqueProxies.add(group.getName());
                }
            }

            String apiUrl = getApiUrl();
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(15);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(uniqueProxies.size());

            for (String proxyName : uniqueProxies) {
                if (proxyName.equalsIgnoreCase("DIRECT") || proxyName.equalsIgnoreCase("REJECT")) {
                    latch.countDown();
                    continue;
                }

                executor.submit(() -> {
                    java.net.HttpURLConnection conn = null;
                    try {
                        java.net.URL url = new java.net.URL(apiUrl + "/proxies/" + android.net.Uri.encode(proxyName) + "/delay?timeout=5000&url=http%3A%2F%2Fwww.gstatic.com%2Fgenerate_204");
                        conn = (java.net.HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(3000);
                        conn.setReadTimeout(5000);
                        conn.setRequestMethod("GET");
                        conn.getResponseCode();
                    } catch (Exception ignored) {
                    } finally {
                        if (conn != null) conn.disconnect();
                        latch.countDown();
                    }
                });
            }
            executor.shutdown();
            try {
                latch.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            runOnUI(this::refreshProxies);
        });
    }

    // -- Service actions (shell-based) ----------------------------------------

    private void runServiceAction(String command, String msg) {
        isActionRunning = true;
        btnService.setEnabled(false);

        statusText.setText(msg);
        statusText.setTextColor(MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOnSurfaceVariant));
        statusCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(statusCard, com.google.android.material.R.attr.colorSurfaceContainerHigh)));

        ThreadManager.runOnShell(() -> {
            ShellHelper.runRootCommandOneShot(command);
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            runOnUI(() -> {
                isActionRunning = false;
                refreshServiceBaseStatus();
                btnService.setEnabled(true);
            });
        });
    }

    // -- Helpers --------------------------------------------------------------

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
        initialLayout = webViewContainer = webHeader = emptyStatsView = dashHeader = btnLatency = btnOpen = clashStatsCard = btnService = statusCard = null;
        statusText = coreText = runtimeText = cpuText = ramText = labelProxyGroups = clashConnectionsText = clashDownloadText = clashUploadText = null;
        proxyGroupsContainer = null;
    }
}
