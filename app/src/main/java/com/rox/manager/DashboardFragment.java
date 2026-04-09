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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

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
    private View initialLayout, webViewContainer, webHeader, dashHeader, btnLatency, btnOpen, btnService, btnRefreshProviders, proxyGroupsHeader, btnProxyViewMode;
    private MaterialCardView statusCard;
    private TextView statusText, coreText, cpuText, ramText, fabUptimeText;
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

    // Latency Test State
    private volatile boolean isLatencyTestRunning = false;
    private volatile java.util.concurrent.ExecutorService latencyExecutor = null;

    // Proxy View Mode
    private boolean showProxyProviders = false;
    private volatile boolean isProviderHealthcheckRunning = false;

    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPollingActive) return;

            // 1. SERVICE STATUS & UPTIME (Every 1s)
            refreshServiceBaseStatus();

            if (isServiceRunning) {
                currentRuntimeSeconds++;
                updateRuntimeUI(currentRuntimeSeconds);

                // PROXY LIST: Fetch ONCE when service just started
                if (!lastServiceRunningState) {
                    refreshProxies();
                }

                // PROXY LIST: Fetch after config reload from Settings
                if (prefs.getBoolean("reload_config_triggered", false)) {
                    prefs.edit().remove("reload_config_triggered").apply();
                    refreshProxies();
                }

                // STATS ONLY (Connections, Up/Down) - Every 1s real-time
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
        proxyGroupsHeader = view.findViewById(R.id.proxyGroupsHeader);
        btnProxyViewMode = view.findViewById(R.id.btnProxyViewMode);

        clashConnectionsText = view.findViewById(R.id.clashConnectionsText);
        clashDownloadText = view.findViewById(R.id.clashDownloadText);
        clashUploadText = view.findViewById(R.id.clashUploadText);

        statusCard = view.findViewById(R.id.statusCard);
        statusText = view.findViewById(R.id.statusText);
        coreText = view.findViewById(R.id.coreText);
        cpuText = view.findViewById(R.id.cpuText);
        ramText = view.findViewById(R.id.ramText);

        btnOpen = view.findViewById(R.id.btnOpenFullWeb);
        btnLatency = view.findViewById(R.id.btnLatencyDash);
        btnService = view.findViewById(R.id.btnService);
        btnRefreshProviders = view.findViewById(R.id.btnRefreshProviders);
        btnRefreshProviders.setVisibility(View.GONE);

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
        btnProxyViewMode.setOnClickListener(v -> showProxyViewPopupMenu(v));
        btnRefreshProviders.setOnClickListener(v -> {
            if (!isServiceRunning) return;
            refreshProxyProviders();
        });
        btnLatency.setOnClickListener(v -> {
            if (!isServiceRunning) return;
            // Cancel previous test if running
            if (isLatencyTestRunning) {
                cancelLatencyTest();
            }
            testAllProxiesLatency();
        });

        btnOpen.setOnClickListener(v -> showDashboardUrlDialog());
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

    private void showDashboardUrlDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getContext());
        builder.setTitle("Dashboard URL");

        android.widget.EditText input = new android.widget.EditText(getContext());
        String savedUrl = prefs.getString("dash_url", "http://127.0.0.1:9090/ui");
        input.setText(savedUrl);
        input.setHint("Enter dashboard URL");
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);
        builder.setView(input);

        builder.setPositiveButton("Open", (dialog, which) -> {
            String url = input.getText().toString().trim();
            if (url.isEmpty()) url = "http://127.0.0.1:9090/ui";
            prefs.edit().putString("dash_url", url).apply();
            toggleWebView(true, url);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void toggleWebView(boolean open) {
        toggleWebView(open, "http://127.0.0.1:9090/ui");
    }

    private void toggleWebView(boolean open, String url) {
        if (open) {
            stopPolling();
            initialLayout.setVisibility(View.GONE);
            dashHeader.setVisibility(View.GONE);
            webHeader.setVisibility(View.VISIBLE);
            webViewContainer.setVisibility(View.VISIBLE);
            btnService.setVisibility(View.GONE);
            webView.loadUrl(url);
        } else {
            webHeader.setVisibility(View.GONE);
            webViewContainer.setVisibility(View.GONE);
            btnService.setVisibility(View.VISIBLE);
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
        // Check if config was just reloaded from Settings
        if (prefs.getBoolean("reload_config_triggered", false)) {
            prefs.edit().remove("reload_config_triggered").apply();
            refreshProxies();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopPolling();
    }

    @Override
    public void onDestroyView() {
        stopPolling();
        cancelLatencyTest();
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
        if (statusText == null || btnService == null || coreText == null || statusCard == null) return;
        ExtendedFloatingActionButton fab = (ExtendedFloatingActionButton) btnService;
        if (running) {
            statusText.setText(String.format("PID: %s", pid));
            statusText.setTextColor(MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOnTertiaryContainer));
            statusCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(statusCard, com.google.android.material.R.attr.colorTertiaryContainer)));

            coreText.setText(core.toUpperCase(Locale.ROOT));
            currentRuntimeSeconds = parseETimeToSeconds(etime);
            fab.setIconResource(R.drawable.ic_stop);
            fab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(fab, com.google.android.material.R.attr.colorErrorContainer)));
            fab.extend();
            String uptime = String.format(Locale.getDefault(), "%02d:%02d:%02d", currentRuntimeSeconds / 3600, (currentRuntimeSeconds % 3600) / 60, currentRuntimeSeconds % 60);
            fab.post(() -> fab.setText(uptime));
        } else {
            statusText.setText(R.string.status_stopped);
            statusText.setTextColor(MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOnErrorContainer));
            statusCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(statusCard, com.google.android.material.R.attr.colorErrorContainer)));

            fab.setIconResource(R.drawable.ic_play_arrow);
            fab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(fab, com.google.android.material.R.attr.colorPrimaryContainer)));
            fab.shrink();
            fab.setText("");
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
                runOnUI(() -> {
                    showProxyProviders = false;
                    labelProxyGroups.setText(R.string.label_proxy_groups);
                    renderProxyGroups(groups);
                });
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
                dotDrawable.setStroke(ringStroke, color);
            } else {
                dotDrawable.setColor(color);
            }

            View dot = new View(ctx);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dotSize, dotSize);
            params.setMargins(2, 0, 2, 0);
            dot.setLayoutParams(params);
            dot.setBackground(dotDrawable);
            dot.setTag(proxy.getName()); // Tag with proxy name for updates

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
            latencyTxt.setTextColor(getLatencyColor(proxy.getDelayMs()));

            if (proxy.getName().equals(group.getSelected())) {
                int containerColor = MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurfaceContainerHighest, 0xFF353535);
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
                    int containerColor = MaterialColors.getColor(getContext(), com.google.android.material.R.attr.colorSurfaceContainerHighest, 0xFF353535);
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
        // Cancel previous test immediately (yacd boolean guard)
        cancelLatencyTest();
        isLatencyTestRunning = true;

        ThreadManager.runBackgroundTask(() -> {
            ApiResult<List<ProxyGroup>> result = getClashApiService().getProxyGroups();
            if (!result.isSuccess() || result.getData() == null || !isLatencyTestRunning) {
                isLatencyTestRunning = false;
                return;
            }

            // Collect unique proxy names
            java.util.HashSet<String> uniqueProxies = new java.util.HashSet<>();
            for (ProxyGroup group : result.getData()) {
                if (group.getType().equals("Selector") || group.getType().equals("URLTest") || group.getType().equals("Fallback") || group.getType().equals("LoadBalance")) {
                    for (ProxyInfo proxy : group.getProxies()) {
                        uniqueProxies.add(proxy.getName());
                    }
                }
            }

            // Fire-and-forget ALL latency tests in parallel (like yacd's Promise.all)
            int threadCount = Math.min(uniqueProxies.size(), 10);
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);

            for (String proxyName : uniqueProxies) {
                if (proxyName.equalsIgnoreCase("DIRECT") || proxyName.equalsIgnoreCase("REJECT")) {
                    continue;
                }

                executor.submit(() -> {
                    if (!isLatencyTestRunning) return;
                    getClashApiService().testProxyLatency(proxyName);
                    // Fire-and-forget: Clash stores delay internally
                });
            }

            executor.shutdown();

            // Short delay - let fast proxies complete, then fetch whatever we have
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            // Fetch and update ALL proxies at once
            if (isLatencyTestRunning) {
                ApiResult<List<ProxyGroup>> updatedResult = getClashApiService().getProxyGroups();
                if (updatedResult.isSuccess() && updatedResult.getData() != null) {
                    runOnUI(() -> updateLatencyUI(updatedResult.getData()));
                }
            }

            isLatencyTestRunning = false;
        });
    }

    private void updateLatencyUI(List<ProxyGroup> updatedGroups) {
        if (proxyGroupsContainer == null) return;

        // Create a map of proxy name to latency for quick lookup
        java.util.Map<String, Integer> latencyMap = new java.util.HashMap<>();
        for (ProxyGroup group : updatedGroups) {
            for (ProxyInfo proxy : group.getProxies()) {
                latencyMap.put(proxy.getName(), proxy.getDelayMs());
            }
        }

        // Update all existing views (both expanded cards and collapsed dots)
        for (int i = 0; i < proxyGroupsContainer.getChildCount(); i++) {
            View groupView = proxyGroupsContainer.getChildAt(i);
            
            // Update expanded proxy cards
            GridLayout itemsContainer = groupView.findViewById(R.id.proxyItemsContainer);
            if (itemsContainer != null) {
                for (int j = 0; j < itemsContainer.getChildCount(); j++) {
                    View proxyCard = itemsContainer.getChildAt(j);
                    if (proxyCard instanceof MaterialCardView) {
                        TextView nameTxt = proxyCard.findViewById(R.id.proxyName);
                        TextView latencyTxt = proxyCard.findViewById(R.id.proxyLatency);

                        if (nameTxt != null && latencyTxt != null && latencyMap.containsKey(nameTxt.getText().toString())) {
                            int delay = latencyMap.get(nameTxt.getText().toString());
                            latencyTxt.setText(delay > 0 ? delay + " ms" : "- ms");
                            latencyTxt.setTextColor(getLatencyColor(delay));
                        }
                    }
                }
            }
            
            // Update collapsed dots
            LinearLayout dotsContainer = groupView.findViewById(R.id.proxyDotsContainer);
            if (dotsContainer != null && dotsContainer.getVisibility() == View.VISIBLE) {
                int ringStroke = (int) (2 * getContext().getResources().getDisplayMetrics().density);
                for (int j = 0; j < dotsContainer.getChildCount(); j++) {
                    View dot = dotsContainer.getChildAt(j);
                    String proxyName = (String) dot.getTag();
                    if (proxyName != null && latencyMap.containsKey(proxyName)) {
                        int delay = latencyMap.get(proxyName);
                        int color = getLatencyColor(delay);
                        
                        android.graphics.drawable.GradientDrawable bg = (android.graphics.drawable.GradientDrawable) dot.getBackground();
                        if (bg != null) {
                            // Check if ring (selected) by checking if current fill is transparent
                            int fillColor;
                            android.content.res.ColorStateList csl = bg.getColor();
                            if (csl != null) {
                                fillColor = csl.getDefaultColor();
                            } else {
                                fillColor = android.graphics.Color.TRANSPARENT;
                            }
                            if (fillColor == android.graphics.Color.TRANSPARENT) {
                                // Ring style - update stroke
                                bg.setColor(android.graphics.Color.TRANSPARENT);
                                bg.setStroke((int) ringStroke, color);
                            } else {
                                // Solid dot - update fill
                                bg.setColor(color);
                            }
                        }
                    }
                }
            }
        }
    }

    private void cancelLatencyTest() {
        isLatencyTestRunning = false;
    }

    // -- Proxy View Selector -------------------------------------------------

    private void showProxyViewPopupMenu(View anchor) {
        if (getContext() == null) return;

        String[] items = new String[]{"Proxy Groups", "Proxy Providers"};
        int selectedIndex = showProxyProviders ? 1 : 0;
        int headerColor = labelProxyGroups.getCurrentTextColor();
        int popupBgColor = com.google.android.material.color.MaterialColors.getColor(
                getContext(), com.google.android.material.R.attr.colorSurfaceContainer, 0xFF2D2D2D);
        int selectedBgColor = com.google.android.material.color.MaterialColors.getColor(
                getContext(), com.google.android.material.R.attr.colorSurfaceContainerHighest, 0xFF454545);
        int normalTextColor = com.google.android.material.color.MaterialColors.getColor(
                getContext(), android.R.attr.textColorSecondary, 0xFFAAAAAA);
        int cornerRadius = (int) (16 * getContext().getResources().getDisplayMetrics().density);
        int itemRadius = (int) (12 * getContext().getResources().getDisplayMetrics().density);

        android.graphics.drawable.GradientDrawable popupBg = new android.graphics.drawable.GradientDrawable();
        popupBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        popupBg.setColor(popupBgColor);
        popupBg.setCornerRadius(cornerRadius);

        android.widget.ListPopupWindow popupWindow = new android.widget.ListPopupWindow(
                new android.view.ContextThemeWrapper(getContext(), com.google.android.material.R.style.Theme_Material3_DayNight),
                null, android.R.attr.listPopupWindowStyle);
        popupWindow.setAnchorView(anchor);
        popupWindow.setWidth((int) (180 * getContext().getResources().getDisplayMetrics().density));
        popupWindow.setModal(true);
        popupWindow.setBackgroundDrawable(popupBg);
        popupWindow.setHorizontalOffset(-anchor.getWidth());

        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(
                getContext(), android.R.layout.simple_list_item_1, items) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                int padding = (int) (14 * getContext().getResources().getDisplayMetrics().density);

                android.graphics.drawable.GradientDrawable selectedBg = new android.graphics.drawable.GradientDrawable();
                selectedBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                selectedBg.setCornerRadius(itemRadius);

                if (position == selectedIndex) {
                    selectedBg.setColor(selectedBgColor);
                    view.setBackground(selectedBg);
                    if (textView != null) {
                        textView.setTextColor(headerColor);
                        textView.setTypeface(null, android.graphics.Typeface.BOLD);
                    }
                } else {
                    view.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                    if (textView != null) {
                        textView.setTextColor(normalTextColor);
                        textView.setTypeface(null, android.graphics.Typeface.NORMAL);
                    }
                }

                view.setPadding(padding, padding, padding, padding);
                view.setClipToOutline(true);
                return view;
            }
        };
        popupWindow.setAdapter(adapter);
        popupWindow.setOnItemClickListener((parent, view, position, id) -> {
            showProxyProviders = (position == 1);
            btnRefreshProviders.setVisibility(showProxyProviders ? View.VISIBLE : View.GONE);
            if (showProxyProviders) {
                renderProxyProvidersView();
            } else {
                refreshProxies();
            }
            popupWindow.dismiss();
        });
        popupWindow.show();
    }

    private void renderProxyProvidersView() {
        if (proxyGroupsContainer == null) return;
        proxyGroupsContainer.removeAllViews();

        labelProxyGroups.setText("PROXY PROVIDERS ▼");

        ThreadManager.runBackgroundTask(() -> {
            ApiResult<List<String>> result = getClashApiService().getProxyProviderNames();
            if (!result.isSuccess() || result.getData() == null || result.getData().isEmpty()) {
                runOnUI(() -> {
                    TextView emptyText = new TextView(getContext());
                    emptyText.setText("No proxy providers found");
                    emptyText.setTextColor(0xFF9E9E9E);
                    emptyText.setPadding(32, 48, 32, 48);
                    proxyGroupsContainer.addView(emptyText);
                });
                return;
            }

            List<String> providerNames = result.getData();
            runOnUI(() -> {
                for (String providerName : providerNames) {
                    renderProviderCard(providerName);
                }
            });
        });
    }

    private void renderProviderCard(String providerName) {
        ThreadManager.runBackgroundTask(() -> {
            ApiResult<ClashApiService.ProviderInfo> result = getClashApiService().getProviderDetails(providerName);
            if (result.isSuccess() && result.getData() != null) {
                runOnUI(() -> renderProviderCardView(result.getData()));
            }
        });
    }

    private void renderProviderCardView(ClashApiService.ProviderInfo provider) {
        if (getContext() == null) return;
        View cardView = LayoutInflater.from(getContext()).inflate(R.layout.item_proxy_group, proxyGroupsContainer, false);

        TextView nameTxt = cardView.findViewById(R.id.proxyGroupName);
        TextView typeTxt = cardView.findViewById(R.id.proxyGroupType);
        ImageView toggleIcon = cardView.findViewById(R.id.proxyGroupToggle);
        GridLayout itemsContainer = cardView.findViewById(R.id.proxyItemsContainer);
        LinearLayout dotsContainer = cardView.findViewById(R.id.proxyDotsContainer);

        // Header: name + proxy count
        nameTxt.setText(provider.getName());
        typeTxt.setText(provider.getVehicleType() + " • " + provider.getProxyCount() + " proxies");
        toggleIcon.setVisibility(View.GONE);

        // Show proxy dots
        renderProxyDots(dotsContainer, provider.getProxies(), "");
        dotsContainer.setVisibility(View.VISIBLE);
        itemsContainer.setVisibility(View.GONE);

        // Add healthcheck button below the group header
        com.google.android.material.button.MaterialButton btnHealthcheck = new com.google.android.material.button.MaterialButton(getContext());
        btnHealthcheck.setText("Healthcheck");
        btnHealthcheck.setIconResource(R.drawable.ic_bolt);
        btnHealthcheck.setIconSize((int) (16 * getContext().getResources().getDisplayMetrics().density));
        btnHealthcheck.setTextSize(12);
        btnHealthcheck.setPadding(16, 8, 16, 8);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(16, 8, 16, 16);
        btnHealthcheck.setLayoutParams(btnParams);
        btnHealthcheck.setOnClickListener(v -> healthcheckProvider(provider.getName()));

        LinearLayout cardContent = cardView.findViewById(android.R.id.content);
        if (cardContent == null) {
            // Find the root LinearLayout of the card
            cardContent = (LinearLayout) ((ViewGroup) dotsContainer.getParent());
        }
        cardContent.addView(btnHealthcheck);

        proxyGroupsContainer.addView(cardView);
    }

    private void healthcheckProvider(String providerName) {
        if (isProviderHealthcheckRunning) return;
        isProviderHealthcheckRunning = true;

        ThreadManager.runBackgroundTask(() -> {
            getClashApiService().healthcheckProvider(providerName);

            // Small delay then fetch updated provider
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            ApiResult<ClashApiService.ProviderInfo> result = getClashApiService().getProviderDetails(providerName);
            if (result.isSuccess() && result.getData() != null) {
                runOnUI(() -> {
                    // Re-render this provider card with updated latency
                    for (int i = 0; i < proxyGroupsContainer.getChildCount(); i++) {
                        View card = proxyGroupsContainer.getChildAt(i);
                        TextView nameTxt = card.findViewById(R.id.proxyGroupName);
                        if (nameTxt != null && nameTxt.getText().toString().equals(providerName)) {
                            // Re-render this card
                            proxyGroupsContainer.removeViewAt(i);
                            renderProviderCardView(result.getData());
                            break;
                        }
                    }
                    isProviderHealthcheckRunning = false;
                });
            } else {
                runOnUI(() -> isProviderHealthcheckRunning = false);
            }
        });
    }

    private void refreshProxyProviders() {
        if (proxyGroupsContainer == null || !showProxyProviders) return;

        btnRefreshProviders.setEnabled(false);
        btnRefreshProviders.animate().rotationBy(360).setDuration(500).start();

        ThreadManager.runBackgroundTask(() -> {
            ApiResult<List<String>> providersResult = getClashApiService().getProxyProviderNames();
            if (providersResult.isSuccess() && providersResult.getData() != null) {
                List<String> providers = providersResult.getData();
                for (String providerName : providers) {
                    getClashApiService().updateProxyProvider(providerName);
                }
            }

            ApiResult<List<ProxyGroup>> updatedResult = getClashApiService().getProxyGroups();
            runOnUI(() -> {
                if (updatedResult.isSuccess() && updatedResult.getData() != null) {
                    updateLatencyUI(updatedResult.getData());
                }
                renderProxyProvidersView();
                btnRefreshProviders.setEnabled(true);
            });
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

    private int getLatencyColor(int delayMs) {
        if (delayMs > 0 && delayMs < 200) return 0xFF4CAF50; // Green
        if (delayMs >= 200 && delayMs < 400) return 0xFFFFC107; // Yellow
        if (delayMs >= 400) return 0xFFFF5252; // Red
        return 0xFF9E9E9E; // Grey (no data)
    }

    private void runOnUI(Runnable r) {
        if (isAdded() && getActivity() != null) getActivity().runOnUiThread(r);
    }

    private String getApiUrl() {
        return ClashApiService.normalizeBaseUrl(prefs.getString("dash_url", "http://127.0.0.1:9090/ui"));
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        return String.format(Locale.getDefault(), "%.1f %cB", bytes / Math.pow(1024, exp), "KMGTPE".charAt(exp - 1));
    }

    private void updateRuntimeUI(long totalSeconds) {
        if (btnService == null) return;
        ExtendedFloatingActionButton fab = (ExtendedFloatingActionButton) btnService;
        if (fab.getText().length() > 0) {
            String uptime = String.format(Locale.getDefault(), "%02d:%02d:%02d", totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
            fab.setText(uptime);
        }
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
        statusText = coreText = cpuText = ramText = labelProxyGroups = clashConnectionsText = clashDownloadText = clashUploadText = null;
        fabUptimeText = null;
        proxyGroupsContainer = null;
    }
}
