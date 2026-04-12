package com.rox.manager;

import android.annotation.SuppressLint;
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
    private View initialLayout, webViewContainer, webHeader, dashHeader, btnLatency, btnOpen, btnService, btnRefreshProviders, proxyGroupsHeader, btnProxyViewMode, btnHealthcheckAll;
    private MaterialCardView statusCard;
    private TextView statusText, coreText, cpuText, ramText, fabUptimeText;
    private WebView webView;
    private TextView labelProxyGroups, clashConnectionsText, clashDownloadText, clashUploadText;
    private LinearLayout proxyGroupsContainer;

    // Track which proxy groups are expanded
    private final Set<String> expandedGroups = new HashSet<>();
    // Track which provider cards are expanded
    private final Set<String> expandedProviders = new HashSet<>();

    // Logic State
    private boolean isServiceRunning = false;
    private boolean isActionRunning = false;
    private long currentRuntimeSeconds = 0;
    private String cachedCoreName = "";

    private SharedPreferences prefs;
    private OnBackPressedCallback backPressedCallback;
    private ClashApiService clashApiService;

    // Unified Polling Handler
    private final Handler pollingHandler = new Handler(Looper.getMainLooper());
    private boolean isPollingActive = false;

    // Latency Test State
    private volatile boolean isLatencyTestRunning = false;

    // Proxy View Mode
    private boolean showProxyProviders = false;
    private volatile boolean isProviderHealthcheckRunning = false;

    // Native /proc tracking
    private long prevCpuTotal = 0;
    private long prevProcTotal = 0;

    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPollingActive) return;

            if (isServiceRunning) {
                currentRuntimeSeconds++;
                updateRuntimeUI(currentRuntimeSeconds);
                refreshClashStats();
                refreshServiceHeavyStats();

                // PROXY LIST: Fetch after config reload from Settings
                if (prefs.getBoolean("reload_config_triggered", false)) {
                    prefs.edit().remove("reload_config_triggered").apply();
                    refreshProxies();
                }
            } else {
                // Reset uptime when service is not running
                if (currentRuntimeSeconds > 0) {
                    currentRuntimeSeconds = 0;
                    updateRuntimeUI(0);
                }
            }

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
        btnHealthcheckAll = view.findViewById(R.id.btnHealthcheckAll);
        btnHealthcheckAll.setVisibility(View.GONE);
        fabUptimeText = view.findViewById(R.id.fabUptimeText);

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
        btnProxyViewMode.setOnClickListener(v -> toggleProxyView());
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
        btnHealthcheckAll.setOnClickListener(v -> {
            if (!isServiceRunning) return;
            healthcheckAllProviders();
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
        // Fetch service status ONCE when fragment opens
        refreshServiceBaseStatus();
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
            // Fetch core name only once, or when Settings signals a change
            if (cachedCoreName.isEmpty() || prefs.getBoolean("core_changed", false)) {
                if (prefs.getBoolean("core_changed", false)) {
                    prefs.edit().remove("core_changed").apply();
                }
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

            // Read PID natively via bridge
            String pidStr = ShellHelper.readRootFileDirect("/data/adb/box/run/box.pid");
            String pid = "0";
            if (pidStr != null && !pidStr.trim().isEmpty()) {
                pid = pidStr.trim();
            }

            String etime = "00:00";
            if (!pid.equals("0")) {
                // Calculate elapsed time from /proc/$PID/stat + /proc/uptime
                String statContent = ShellHelper.readRootFileDirect("/proc/" + pid + "/stat");
                String uptimeContent = ShellHelper.readRootFileDirect("/proc/uptime");
                if (statContent != null && uptimeContent != null) {
                    try {
                        // Field 22 (0-indexed 21) is starttime in jiffies
                        String[] statFields = statContent.split("\\s+");
                        if (statFields.length > 21) {
                            long starttime = Long.parseLong(statFields[21]);
                            // /proc/uptime first field is seconds since boot
                            double uptimeSec = Double.parseDouble(uptimeContent.split("\\s+")[0]);
                            // CLK_TCK is typically 100 on Android
                            long hz = 100;
                            double startedSec = uptimeSec - (starttime / (double) hz);
                            etime = formatSecondsToETime((long) startedSec);
                        }
                    } catch (Exception ignored) {}
                }
            }

            final String finalPid = pid;
            final String finalEtime = etime;
            runOnUI(() -> {
                // Always update core info even if PID check fails
                if (!cachedCoreName.isEmpty() && coreText != null) {
                    coreText.setText(cachedCoreName.toUpperCase(Locale.ROOT));
                }

                boolean running = !finalPid.equals("0");
                isServiceRunning = running;
                updateServiceUI(running, cachedCoreName, finalPid, finalEtime);

                // Fetch proxy list when service is running
                if (isServiceRunning) {
                    refreshProxies();
                }

                if (!isServiceRunning) {
                    if (ramText != null) ramText.setText(R.string.value_empty_mb_upper);
                    if (cpuText != null) cpuText.setText(R.string.value_empty_percent_upper);
                    // Reset CPU delta tracking
                    prevCpuTotal = 0;
                    prevProcTotal = 0;
                }
            });
        });
    }

    private String formatSecondsToETime(long seconds) {
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        long secs = seconds % 60;
        if (days > 0) return String.format(Locale.getDefault(), "%d-%02d:%02d:%02d", days, hours, minutes, secs);
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, secs);
    }

    private void refreshServiceHeavyStats() {
        if (isActionRunning || !isServiceRunning) return;

        ThreadManager.runBackgroundTask(() -> {
            // Read PID from file
            String pidStr = ShellHelper.readRootFileDirect("/data/adb/box/run/box.pid");
            String pid = "0";
            if (pidStr != null && !pidStr.trim().isEmpty()) {
                pid = pidStr.trim();
            }

            if (pid.equals("0")) {
                runOnUI(() -> {
                    if (ramText != null) ramText.setText(R.string.value_empty_mb_upper);
                    if (cpuText != null) cpuText.setText(R.string.value_empty_percent_upper);
                });
                return;
            }

            // Read /proc/$PID/status for VmRSS
            String statusContent = ShellHelper.readRootFileDirect("/proc/" + pid + "/status");
            // Read /proc/$PID/stat for CPU times (fields 14=utime, 15=stime, 22=starttime)
            String statContent = ShellHelper.readRootFileDirect("/proc/" + pid + "/stat");
            // Read /proc/uptime for system uptime
            String uptimeContent = ShellHelper.readRootFileDirect("/proc/uptime");

            long rssKb = 0;
            double cpuPercent = 0;

            if (statusContent != null) {
                // Parse VmRSS line: "VmRSS:    12345 kB"
                for (String line : statusContent.split("\n")) {
                    if (line.startsWith("VmRSS:")) {
                        try {
                            String[] parts = line.trim().split("\\s+");
                            rssKb = Long.parseLong(parts[1]);
                        } catch (Exception ignored) {}
                        break;
                    }
                }
            }

            if (statContent != null && uptimeContent != null) {
                try {
                    String[] statFields = statContent.split("\\s+");
                    if (statFields.length > 21) {
                        long utime = Long.parseLong(statFields[13]);  // field 14 (0-indexed 13)
                        long stime = Long.parseLong(statFields[14]);  // field 15 (0-indexed 14)
                        long starttime = Long.parseLong(statFields[21]);  // field 22 (0-indexed 21)
                        long hz = 100;
                        double uptimeSec = Double.parseDouble(uptimeContent.split("\\s+")[0]);
                        long totalCpu = utime + stime;
                        long totalProc = (long) (uptimeSec * hz);

                        if (prevCpuTotal > 0 && prevProcTotal > 0) {
                            long deltaCpu = totalCpu - prevCpuTotal;
                            long deltaProc = totalProc - prevProcTotal;
                            if (deltaProc > 0) {
                                cpuPercent = (deltaCpu * 100.0) / deltaProc;
                                if (cpuPercent < 0) cpuPercent = 0;
                                if (cpuPercent > 100) cpuPercent = 100;
                            }
                        }
                        prevCpuTotal = totalCpu;
                        prevProcTotal = totalProc;
                    }
                } catch (Exception ignored) {}
            }

            final long finalRss = rssKb;
            final double finalCpu = cpuPercent;
            runOnUI(() -> {
                if (ramText != null) {
                    ramText.setText(finalRss >= 1024 ? (finalRss / 1024) + " MB" : finalRss + " KB");
                }
                if (cpuText != null) {
                    cpuText.setText(String.format(Locale.getDefault(), "%.0f%%", finalCpu));
                }
            });
        });
    }

    private void updateServiceUI(boolean running, String core, String pid, String etime) {
        if (statusText == null || btnService == null || coreText == null || statusCard == null) return;

        MaterialButton serviceBtn = (MaterialButton) btnService;
        if (running) {
            statusText.setText(String.format(Locale.getDefault(), "%s: %s", getString(R.string.label_pid), pid));
            statusText.setTextColor(MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOnSurfaceVariant));
            statusCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(statusCard, com.google.android.material.R.attr.colorSurfaceContainerHigh)));

            coreText.setText(core.toUpperCase(Locale.ROOT));
            currentRuntimeSeconds = parseETimeToSeconds(etime);
            serviceBtn.setText(R.string.btn_stop);
            serviceBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(serviceBtn, com.google.android.material.R.attr.colorErrorContainer)));
            serviceBtn.setTextColor(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(serviceBtn, com.google.android.material.R.attr.colorOnErrorContainer)));
        } else {
            statusText.setText(String.format(Locale.getDefault(), "%s: 0", getString(R.string.label_pid)));
            statusText.setTextColor(MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOnSurfaceVariant));
            statusCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(statusCard, com.google.android.material.R.attr.colorSurfaceContainerHigh)));

            coreText.setText(core.toUpperCase(Locale.ROOT));
            currentRuntimeSeconds = 0;
            serviceBtn.setText(R.string.btn_start);
            serviceBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(serviceBtn, com.google.android.material.R.attr.colorPrimaryContainer)));
            serviceBtn.setTextColor(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(serviceBtn, com.google.android.material.R.attr.colorOnPrimaryContainer)));
        }
        updateRuntimeUI(currentRuntimeSeconds);
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

        applyLatencyUpdates(latencyMap);
    }

    /**
     * Applies latency updates to all existing proxy views (expanded cards + collapsed dots).
     * Shared between proxy groups and provider view updates.
     */
    private void applyLatencyUpdates(java.util.Map<String, Integer> latencyMap) {
        if (proxyGroupsContainer == null || getContext() == null) return;

        int ringStroke = (int) (2 * getContext().getResources().getDisplayMetrics().density);

        for (int i = 0; i < proxyGroupsContainer.getChildCount(); i++) {
            View groupView = proxyGroupsContainer.getChildAt(i);

            // Update expanded proxy cards
            GridLayout itemsContainer = groupView.findViewById(R.id.proxyItemsContainer);
            if (itemsContainer != null && itemsContainer.getVisibility() == View.VISIBLE) {
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
                for (int j = 0; j < dotsContainer.getChildCount(); j++) {
                    View dot = dotsContainer.getChildAt(j);
                    String proxyName = (String) dot.getTag();
                    if (proxyName != null && latencyMap.containsKey(proxyName)) {
                        int delay = latencyMap.get(proxyName);
                        int color = getLatencyColor(delay);

                        android.graphics.drawable.GradientDrawable bg = (android.graphics.drawable.GradientDrawable) dot.getBackground();
                        if (bg != null) {
                            int fillColor;
                            android.content.res.ColorStateList csl = bg.getColor();
                            if (csl != null) {
                                fillColor = csl.getDefaultColor();
                            } else {
                                fillColor = android.graphics.Color.TRANSPARENT;
                            }
                            if (fillColor == android.graphics.Color.TRANSPARENT) {
                                bg.setColor(android.graphics.Color.TRANSPARENT);
                                bg.setStroke((int) ringStroke, color);
                            } else {
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

    // -- Proxy View Toggle ---------------------------------------------------

    private void toggleProxyView() {
        showProxyProviders = !showProxyProviders;
        if (showProxyProviders) {
            btnRefreshProviders.setVisibility(View.VISIBLE);
            btnLatency.setVisibility(View.GONE);
            btnHealthcheckAll.setVisibility(View.VISIBLE);
            renderProxyProvidersView();
        } else {
            btnRefreshProviders.setVisibility(View.GONE);
            btnLatency.setVisibility(View.VISIBLE);
            btnHealthcheckAll.setVisibility(View.GONE);
            refreshProxies();
        }
    }

    private void renderProxyProvidersView() {
        if (proxyGroupsContainer == null) return;
        proxyGroupsContainer.removeAllViews();

        ThreadManager.runBackgroundTask(() -> {
            ApiResult<List<String>> result = getClashApiService().getProxyProviderNames();
            if (!result.isSuccess() || result.getData() == null || result.getData().isEmpty()) {
                runOnUI(() -> {
                    TextView emptyText = new TextView(getContext());
                    emptyText.setText(R.string.no_proxy_providers);
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
        View header = cardView.findViewById(R.id.proxyGroupHeader);

        // Header: name + proxy count
        nameTxt.setText(provider.getName());
        typeTxt.setText(String.format(java.util.Locale.getDefault(),
                getString(R.string.format_provider_info),
                provider.getVehicleType(), provider.getProxyCount()));

        // Check if this provider is expanded
        boolean isExpanded = expandedProviders.contains(provider.getName());
        itemsContainer.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        dotsContainer.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
        toggleIcon.setRotation(isExpanded ? 180 : 0);
        toggleIcon.setVisibility(View.VISIBLE);

        // Render proxy dots for collapsed view
        renderProxyDots(dotsContainer, provider.getProxies(), "");

        // Render detailed proxy cards for expanded view
        if (isExpanded) {
            renderDetailedProxies(itemsContainer, new ProxyGroup(provider.getName(), "", "", provider.getProxies()));
        }

        // Toggle expand/collapse on header click
        header.setOnClickListener(v -> {
            if (expandedProviders.contains(provider.getName())) {
                expandedProviders.remove(provider.getName());
                itemsContainer.setVisibility(View.GONE);
                dotsContainer.setVisibility(View.VISIBLE);
                toggleIcon.animate().rotation(0).setDuration(200).start();
            } else {
                expandedProviders.add(provider.getName());
                itemsContainer.setVisibility(View.VISIBLE);
                dotsContainer.setVisibility(View.GONE);
                toggleIcon.animate().rotation(180).setDuration(200).start();
                renderDetailedProxies(itemsContainer, new ProxyGroup(provider.getName(), "", "", provider.getProxies()));
            }
        });

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

    private void healthcheckAllProviders() {
        ThreadManager.runBackgroundTask(() -> {
            // Fire all healthchecks in parallel (Clash runs them server-side)
            ApiResult<List<String>> result = getClashApiService().getProxyProviderNames();
            if (!result.isSuccess() || result.getData() == null) return;

            List<String> providers = result.getData();
            for (String providerName : providers) {
                getClashApiService().healthcheckProvider(providerName);
            }

            // Fetch all provider details in parallel
            java.util.concurrent.ExecutorService fetchExecutor = java.util.concurrent.Executors.newFixedThreadPool(
                    Math.min(providers.size(), Math.max(4, providers.size())));
            java.util.Map<String, Integer> latencyMap = new java.util.concurrent.ConcurrentHashMap<>();
            java.util.concurrent.CountDownLatch fetchLatch = new java.util.concurrent.CountDownLatch(providers.size());

            for (String providerName : providers) {
                fetchExecutor.submit(() -> {
                    try {
                        ApiResult<ClashApiService.ProviderInfo> providerResult = getClashApiService().getProviderDetails(providerName);
                        if (providerResult.isSuccess() && providerResult.getData() != null) {
                            for (ProxyInfo proxy : providerResult.getData().getProxies()) {
                                latencyMap.put(proxy.getName(), proxy.getDelayMs());
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        fetchLatch.countDown();
                    }
                });
            }
            fetchExecutor.shutdown();
            try { fetchLatch.await(15, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

            runOnUI(() -> applyLatencyUpdates(latencyMap));
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
        if (fabUptimeText == null) return;
        String uptime = String.format(Locale.getDefault(), "%02d:%02d:%02d", totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
        fabUptimeText.setText(uptime);
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
        btnHealthcheckAll = null;
    }
}
