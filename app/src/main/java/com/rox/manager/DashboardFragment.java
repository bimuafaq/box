package com.rox.manager;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.Locale;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.GridLayout;
import android.widget.ImageView;
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

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private View initialLayout, webViewContainer, webHeader, dashHeader, btnLatency, btnOpen, btnService;
    private MaterialCardView statusCard;
    private TextView statusText, coreText, runtimeText, cpuText, ramText;
    private WebView webView;
    private TextView labelProxyGroups, clashConnectionsText, clashDownloadText, clashUploadText;
    private LinearLayout proxyGroupsContainer;

    // Track which proxy groups are expanded
    private final Set<String> expandedGroups = new HashSet<>();

    // Logic State
    private boolean isServiceRunning = false;
    private boolean lastServiceRunningState = false;
    private boolean isActionRunning = false;
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
                if (!lastServiceRunningState) {
                    refreshProxies();
                }

                // API STATS (Connections, Up/Down) - Every 1s for real-time feel
                refreshClashStats();
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
        proxyGroupsContainer = view.findViewById(R.id.proxyGroupsContainer);
        labelProxyGroups = view.findViewById(R.id.labelProxyGroups);

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
        cardConnections.setOnClickListener(v -> {
            if (isAdded()) {
                startActivity(new Intent(getActivity(), ConnectionsActivity.class));
            }
        });
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
                        ramText.setText(R.string.value_empty_mb_upper);
                        cpuText.setText(R.string.value_empty_percent_upper);
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

            coreText.setText(core.toUpperCase(Locale.ROOT));
            currentRuntimeSeconds = parseETimeToSeconds(etime);
            fab.setImageResource(R.drawable.ic_stop);
            fab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(fab, com.google.android.material.R.attr.colorErrorContainer)));
        } else {
            statusText.setText(R.string.status_stopped);
            statusText.setTextColor(MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOnErrorContainer));
            statusCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(statusCard, com.google.android.material.R.attr.colorErrorContainer)));

            runtimeText.setText(R.string.value_empty_time);
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
        LinearLayout dotsContainer = groupView.findViewById(R.id.proxyDotsContainer);
        ImageView toggleIcon = groupView.findViewById(R.id.proxyGroupToggle);
        View header = groupView.findViewById(R.id.proxyGroupHeader);

        boolean isExpanded = expandedGroups.contains(group.getName());
        itemsContainer.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        dotsContainer.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
        toggleIcon.setRotation(isExpanded ? 180 : 0);

        // Render colored dots (always rendered but visibility toggled)
        renderProxyDots(dotsContainer, group.getProxies(), group.getSelected());

        // Render detailed cards (only if expanded)
        if (isExpanded) {
            renderDetailedProxies(itemsContainer, group);
        }

        // Toggle expand/collapse on header click
        header.setOnClickListener(v -> {
            if (expandedGroups.contains(group.getName())) {
                expandedGroups.remove(group.getName());
                itemsContainer.setVisibility(View.GONE);
                dotsContainer.setVisibility(View.VISIBLE);
                toggleIcon.animate().rotation(0).setDuration(200).start();
            } else {
                expandedGroups.add(group.getName());
                itemsContainer.setVisibility(View.VISIBLE);
                dotsContainer.setVisibility(View.GONE);
                toggleIcon.animate().rotation(180).setDuration(200).start();
                renderDetailedProxies(itemsContainer, group);
            }
        });

        proxyGroupsContainer.addView(groupView);
    }

    private void renderProxyDots(LinearLayout dotsContainer, List<ProxyInfo> proxies, String selectedName) {
        dotsContainer.removeAllViews();
        Context ctx = getContext();
        if (ctx == null) return;

        int dotSize = (int) (12 * ctx.getResources().getDisplayMetrics().density);
        int ringSize = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        int ringStroke = (int) (2 * ctx.getResources().getDisplayMetrics().density);

        for (ProxyInfo proxy : proxies) {
            // Color based on latency
            int delay = proxy.getDelayMs();
            int color;
            if (delay > 0 && delay < 200) {
                color = 0xFF4CAF50; // Green
            } else if (delay >= 200 && delay < 400) {
                color = 0xFFFFC107; // Yellow
            } else if (delay >= 400) {
                color = 0xFFFF5252; // Red
            } else {
                color = 0xFF9E9E9E; // Grey (no data)
            }

            boolean isSelected = proxy.getName().equals(selectedName);

            android.graphics.drawable.GradientDrawable dotDrawable = new android.graphics.drawable.GradientDrawable();
            dotDrawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            
            if (isSelected) {
                dotDrawable.setColor(android.graphics.Color.TRANSPARENT);
                dotDrawable.setStroke((int) (2 * ctx.getResources().getDisplayMetrics().density), color);
            } else {
                dotDrawable.setColor(color);
            }

            View dot = new View(ctx);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dotSize, dotSize);
            params.setMargins(2, 0, 2, 0);
            dot.setLayoutParams(params);
            dot.setBackground(dotDrawable);

            dotsContainer.addView(dot);
        }
    }

    private void renderDetailedProxies(GridLayout itemsContainer, ProxyGroup group) {
        itemsContainer.removeAllViews();
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

            String displayType = proxy.getType().isEmpty() ? proxy.getName() : proxy.getType();
            if (displayType.equalsIgnoreCase("DIRECT")) displayType = "Direct";
            else if (displayType.equalsIgnoreCase("REJECT")) displayType = "Reject";
            typeTxt.setText(displayType);
            latencyTxt.setText(proxy.delayDisplay());

            // Color latency text to match dot colors
            int delay = proxy.getDelayMs();
            int latencyColor;
            if (delay > 0 && delay < 200) {
                latencyColor = 0xFF4CAF50; // Green
            } else if (delay >= 200 && delay < 400) {
                latencyColor = 0xFFFFC107; // Yellow
            } else if (delay >= 400) {
                latencyColor = 0xFFFF5252; // Red
            } else {
                latencyColor = 0xFF9E9E9E; // Grey
            }
            latencyTxt.setTextColor(latencyColor);

            if (proxy.getName().equals(group.getSelected())) {
                int containerColor = MaterialColors.getColor(card, com.google.android.material.R.attr.colorSecondaryContainer, 0xFF1E1E1E);
                card.setCardBackgroundColor(containerColor);
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
    }

    private void switchProxy(String group, String name) {
        // 1. Optimistic UI update
        for (int i = 0; i < proxyGroupsContainer.getChildCount(); i++) {
            View groupView = proxyGroupsContainer.getChildAt(i);
            TextView groupNameTxt = groupView.findViewById(R.id.proxyGroupName);
            if (groupNameTxt != null && groupNameTxt.getText().toString().equals(group)) {
                GridLayout itemsContainer = groupView.findViewById(R.id.proxyItemsContainer);
                if (itemsContainer != null) {
                    int containerColor = MaterialColors.getColor(getContext(), com.google.android.material.R.attr.colorSecondaryContainer, 0xFF1E1E1E);
                    int defaultColor = MaterialColors.getColor(getContext(), com.google.android.material.R.attr.colorSurfaceContainerLow, 0xFF1E1E1E);
                    for (int j = 0; j < itemsContainer.getChildCount(); j++) {
                        View cardView = itemsContainer.getChildAt(j);
                        if (cardView instanceof MaterialCardView) {
                            MaterialCardView card = (MaterialCardView) cardView;
                            TextView proxyNameTxt = card.findViewById(R.id.proxyName);
                            if (proxyNameTxt != null) {
                                if (proxyNameTxt.getText().toString().equals(name)) {
                                    card.setCardBackgroundColor(containerColor);
                                } else {
                                    card.setCardBackgroundColor(defaultColor);
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
            ApiResult<Boolean> result = getClashApiService().switchProxy(group, name);
            if (result.isSuccess()) {
                refreshProxies();
            } else {
                Log.w(TAG, "Proxy switch failed: " + result.getErrorMessage());
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

    @SuppressLint("SetJavaScriptEnabled")
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
        initialLayout = webViewContainer = webHeader = dashHeader = btnLatency = btnOpen = btnService = statusCard = null;
        statusText = coreText = runtimeText = cpuText = ramText = labelProxyGroups = clashConnectionsText = clashDownloadText = clashUploadText = null;
        proxyGroupsContainer = null;
    }
}
