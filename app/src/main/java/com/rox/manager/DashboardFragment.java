package com.rox.manager;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.button.MaterialButton;
import androidx.activity.OnBackPressedCallback;
import android.content.Context;
import android.content.SharedPreferences;

import android.os.Handler;
import android.os.Looper;
import java.util.Locale;
import com.google.android.material.card.MaterialCardView;

import android.widget.LinearLayout;
import android.widget.GridLayout;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import android.net.Uri;
import android.util.Log;
import com.google.android.material.snackbar.Snackbar;
import android.content.Intent;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.Iterator;

public class DashboardFragment extends Fragment {
    private View initialLayout, webViewContainer, webHeader, emptyStatsView, dashHeader, btnLatency, btnOpen, cardRules, clashStatsCard, btnService;
    private TextView statusText, coreText, runtimeText, cpuText, ramText, idCoreText;
    private boolean isServiceRunning = false;
    private boolean isActionRunning = false;
    
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private long currentRuntimeSeconds = 0;
    private boolean isTimerRunning = false;
    
    private final Handler serviceStatsHandler = new Handler(Looper.getMainLooper());
    private boolean isServiceStatsRunning = false;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTimerRunning) {
                currentRuntimeSeconds++;
                updateRuntimeUI(currentRuntimeSeconds);
                timerHandler.postDelayed(this, 1000);
            }
        }
    };

    private final Runnable serviceStatsRunnable = new Runnable() {
        @Override
        public void run() {
            if (isServiceStatsRunning) {
                refreshServiceCoreStats();
                serviceStatsHandler.postDelayed(this, 2000);
            }
        }
    };
    private WebView webView;
    private TextView title, labelProxyGroups, clashConnectionsText, clashDownloadText, clashUploadText;
    private OnBackPressedCallback backPressedCallback;
    private SharedPreferences prefs;
    private LinearLayout proxyGroupsContainer;
    private MaterialButton btnRefresh;
    private boolean showClashStats = false;
    
    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private boolean isStatsRunning = false;
    private long statsCounter = 0;
    private int fastPollRemaining = 0; // Cycles of 500ms polling

    private final Runnable statsRunnable = new Runnable() {
        @Override
        public void run() {
            if (isStatsRunning) {
                refreshClashStats();
                
                // Normal: every 2s (if fastPollRemaining == 0)
                // Fast: every 500ms (if fastPollRemaining > 0)
                if (fastPollRemaining > 0) {
                    refreshProxies();
                    fastPollRemaining--;
                    statsHandler.postDelayed(this, 500);
                } else {
                    if (statsCounter % 2 == 0) refreshProxies();
                    statsCounter++;
                    statsHandler.postDelayed(this, 2000);
                }
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dashboard, container, false);
        
        prefs = getActivity().getSharedPreferences("rox_prefs", Context.MODE_PRIVATE);

        initialLayout = view.findViewById(R.id.initialLayout);
        webViewContainer = view.findViewById(R.id.webViewContainer);
        webHeader = view.findViewById(R.id.webHeader);
        dashHeader = view.findViewById(R.id.dashHeader);
        webView = view.findViewById(R.id.dashWebView);
        title = view.findViewById(R.id.dashTitle);
        emptyStatsView = view.findViewById(R.id.emptyStatsView);
        cardRules = view.findViewById(R.id.cardRules);
        
        proxyGroupsContainer = view.findViewById(R.id.proxyGroupsContainer);
        labelProxyGroups = view.findViewById(R.id.labelProxyGroups);

        clashStatsCard = view.findViewById(R.id.clashStatsCard);
        View cardConnections = view.findViewById(R.id.cardConnections);
        cardConnections.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), ConnectionsActivity.class);
            startActivity(intent);
        });
        clashConnectionsText = view.findViewById(R.id.clashConnectionsText);
        clashDownloadText = view.findViewById(R.id.clashDownloadText);
        clashUploadText = view.findViewById(R.id.clashUploadText);
        
        statusText = view.findViewById(R.id.statusText);
        coreText = view.findViewById(R.id.coreText);
        runtimeText = view.findViewById(R.id.runtimeText);
        cpuText = view.findViewById(R.id.cpuText);
        ramText = view.findViewById(R.id.ramText);
        idCoreText = view.findViewById(R.id.idCoreText);

        btnOpen = view.findViewById(R.id.btnOpenFullWeb);
        btnRefresh = view.findViewById(R.id.btnRefreshDash);
        btnLatency = view.findViewById(R.id.btnLatencyDash);
        btnService = view.findViewById(R.id.btnService);
        View btnRules = view.findViewById(R.id.btnRulesDash);
        MaterialButton btnClose = view.findViewById(R.id.btnCloseWeb);
        
        setupWebView();

        btnService.setOnClickListener(v -> {
            if (isActionRunning) return;
            if (isServiceRunning) {
                runServiceAction("/data/adb/box/scripts/box.iptables disable && /data/adb/box/scripts/box.service stop && pkill -f inotifyd", "Stopping Box...");
            } else {
                runServiceAction("/data/adb/box/scripts/box.service start && /data/adb/box/scripts/box.iptables enable && " +
                        "(pkill -f inotifyd; " +
                        "inotifyd /data/adb/box/scripts/box.inotify /data/adb/modules/box_for_root >/dev/null 2>&1 & " +
                        "inotifyd /data/adb/box/scripts/net.inotify /data/misc/net >/dev/null 2>&1 & " +
                        "inotifyd /data/adb/box/scripts/ctr.inotify /data/misc/net/rt_tables >/dev/null 2>&1 & " +
                        "/data/adb/box/scripts/net.inotify w manual)", "Starting Box...");
            }
        });

        btnRules.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), RulesActivity.class);
            startActivity(intent);
        });

        View btnRefreshWeb = view.findViewById(R.id.btnRefreshWeb);
        btnRefreshWeb.setOnClickListener(v -> {
            v.animate().rotationBy(360).setDuration(500).start();
            webView.reload();
        });

        btnRefresh.setOnClickListener(v -> {
            // Visual feedback: Rotate icon
            v.animate().rotationBy(360).setDuration(500).start();
            
            refreshClashStats();
            refreshProxies();
            
            // Also trigger fast poll to catch any immediate changes
            fastPollRemaining = 5; 
        });

        btnLatency.setOnClickListener(v -> {
            testAllProxiesLatency();
        });

        backPressedCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                btnClose.performClick();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backPressedCallback);

        btnOpen.setOnClickListener(v -> {
            stopStats();
            initialLayout.setVisibility(View.GONE);
            dashHeader.setVisibility(View.GONE);
            btnLatency.setVisibility(View.GONE);
            webHeader.setVisibility(View.VISIBLE);
            webViewContainer.setVisibility(View.VISIBLE);
            // ALWAYS use local URL for the built-in dashboard button to ensure it works
            webView.loadUrl("http://127.0.0.1:9090/ui");
            if (backPressedCallback != null) backPressedCallback.setEnabled(true);
        });

        btnClose.setOnClickListener(v -> {
            webHeader.setVisibility(View.GONE);
            webViewContainer.setVisibility(View.GONE);
            initialLayout.setVisibility(View.VISIBLE);
            dashHeader.setVisibility(View.VISIBLE);
            btnOpen.setVisibility(View.VISIBLE);
            if (showClashStats) {
                btnLatency.setVisibility(View.VISIBLE);
            }
            webView.loadUrl("about:blank");
            if (backPressedCallback != null) backPressedCallback.setEnabled(false);
            startStats();
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        showClashStats = prefs.getBoolean("enable_clash_api", false);
        refreshServiceStatus();
        startServiceStats();
        
        if (initialLayout.getVisibility() == View.VISIBLE) {
            // Button Open Dashboard should always be visible in the header
            btnOpen.setVisibility(View.VISIBLE);
            
            if (showClashStats) {
                clashStatsCard.setVisibility(View.VISIBLE);
                labelProxyGroups.setVisibility(View.VISIBLE);
                emptyStatsView.setVisibility(View.GONE);
                btnLatency.setVisibility(View.VISIBLE);
                cardRules.setVisibility(View.VISIBLE);
                refreshProxies();
                startStats();
            } else {
                clashStatsCard.setVisibility(View.GONE);
                labelProxyGroups.setVisibility(View.GONE);
                emptyStatsView.setVisibility(View.VISIBLE);
                btnLatency.setVisibility(View.GONE);
                cardRules.setVisibility(View.GONE);
                stopStats();
            }
        }
    }

    private void refreshServiceStatus() {
        ThreadManager.runOnShell(() -> {
            String cmd = "PID=$(cat /data/adb/box/run/box.pid 2>/dev/null || echo \"0\"); " +
                         "CORE=$(grep '^bin_name=' /data/adb/box/settings.ini | cut -d '\"' -f 2); " +
                         "ETIME=$(ps -p $PID -o etime= 2>/dev/null || echo \"00:00\"); " +
                         "echo \"$PID|$CORE|$ETIME\"";
            
            String result = ShellHelper.runRootCommand(cmd);

            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (result != null && result.contains("|")) {
                        String[] parts = result.split("\\|");
                        String pid = parts[0].trim();
                        String core = (parts.length > 1) ? parts[1].trim() : "---";
                        String etime = (parts.length > 2) ? parts[2].trim() : "00:00";

                        isServiceRunning = pid.matches("\\d+") && !pid.equals("0");
                        com.google.android.material.floatingactionbutton.FloatingActionButton fab = (com.google.android.material.floatingactionbutton.FloatingActionButton) btnService;

                        if (isServiceRunning) {
                            statusText.setText(getString(R.string.status_running));
                            statusText.setTextColor(com.google.android.material.color.MaterialColors.getColor(statusText.getContext(), android.R.attr.colorPrimary, android.graphics.Color.BLUE));
                            long seconds = parseETimeToSeconds(etime);
                            startTimer(seconds);
                            coreText.setText(core.toUpperCase() + " (" + pid + ")");
                            
                            fab.setImageResource(R.drawable.ic_stop);
                            fab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(fab, com.google.android.material.R.attr.colorErrorContainer)));
                            fab.setImageTintList(android.content.res.ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(fab, com.google.android.material.R.attr.colorOnErrorContainer)));
                        } else {
                            statusText.setText(getString(R.string.status_stopped));
                            statusText.setTextColor(com.google.android.material.color.MaterialColors.getColor(statusText.getContext(), android.R.attr.colorError, android.graphics.Color.RED));
                            runtimeText.setText("00:00:00");
                            stopTimer();
                            coreText.setText("---");
                            
                            fab.setImageResource(R.drawable.ic_play_arrow);
                            fab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(fab, com.google.android.material.R.attr.colorPrimaryContainer)));
                            fab.setImageTintList(android.content.res.ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(fab, com.google.android.material.R.attr.colorOnPrimaryContainer)));
                        }
                    }
                });
            }
        });
    }

    private void refreshServiceCoreStats() {
        if (!isResumed()) return;
        ThreadManager.runOnShell(() -> {
            String cmd = "PID=$(cat /data/adb/box/run/box.pid 2>/dev/null || echo \"0\"); " +
                         "if [ \"$PID\" != \"0\" ]; then " +
                         "  RSS=$(grep VmRSS /proc/$PID/status | awk '{print $2}'); " +
                         "  CPU=$(ps -p $PID -o %cpu=); " +
                         "  CORE_ID=$(awk '{print $39}' /proc/$PID/stat); " +
                         "  echo \"$RSS|$CPU|$CORE_ID\"; " +
                         "else echo \"0|0|0\"; fi";
            
            String res = ShellHelper.runRootCommand(cmd);

            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (res != null && res.contains("|")) {
                        String[] parts = res.split("\\|");
                        try {
                            long rssKb = Long.parseLong(parts[0].trim());
                            String cpu = parts[1].trim();
                            String coreId = parts[2].trim();

                            if (rssKb > 0) {
                                String ramStr = (rssKb >= 1024) ? (rssKb / 1024) + " MB" : rssKb + " KB";
                                ramText.setText(ramStr);
                                cpuText.setText(cpu.isEmpty() ? "0%" : cpu + "%");
                                idCoreText.setText(coreId.isEmpty() ? "-" : coreId);
                            } else {
                                ramText.setText("0 MB");
                                cpuText.setText("0%");
                                idCoreText.setText("-");
                            }
                        } catch (Exception ignored) {
                            ramText.setText("---");
                            cpuText.setText("---");
                            idCoreText.setText("-");
                        }
                    }
                });
            }
        });
    }

    private void updateRuntimeUI(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        String timeStr = String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s);
        runtimeText.setText(timeStr);
    }

    private long parseETimeToSeconds(String etime) {
        try {
            String[] parts = etime.split(":");
            long seconds = 0;
            if (parts.length == 2) {
                seconds = Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1]);
            } else if (parts.length == 3) {
                if (parts[0].contains("-")) {
                    String[] dayHour = parts[0].split("-");
                    seconds = Long.parseLong(dayHour[0]) * 86400 + Long.parseLong(dayHour[1]) * 3600;
                } else {
                    seconds = Long.parseLong(parts[0]) * 3600;
                }
                seconds += Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2]);
            }
            return seconds;
        } catch (Exception e) {
            return 0;
        }
    }

    private void startTimer(long initialSeconds) {
        this.currentRuntimeSeconds = initialSeconds;
        if (!isTimerRunning) {
            isTimerRunning = true;
            timerHandler.post(timerRunnable);
        }
    }

    private void stopTimer() {
        isTimerRunning = false;
        timerHandler.removeCallbacks(timerRunnable);
    }

    private void startServiceStats() {
        if (!isServiceStatsRunning) {
            isServiceStatsRunning = true;
            serviceStatsHandler.post(serviceStatsRunnable);
        }
    }

    private void stopServiceStats() {
        isServiceStatsRunning = false;
        serviceStatsHandler.removeCallbacks(serviceStatsRunnable);
    }

    private void runServiceAction(String command, String msg) {
        if (isActionRunning) return;
        isActionRunning = true;
        btnService.setEnabled(false);

        if (getView() != null && getActivity() != null) {
            Snackbar.make(getView(), msg, Snackbar.LENGTH_SHORT).show();
        }

        ThreadManager.runOnShell(() -> {
            ShellHelper.runRootCommandOneShot(command);
            try { Thread.sleep(2200); } catch (InterruptedException ignored) {}
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    refreshServiceStatus();
                    if (showClashStats) {
                        refreshProxies();
                        refreshClashStats();
                    }
                    isActionRunning = false;
                    btnService.setEnabled(true);
                });
            }
        });
    }

    private void refreshProxies() {
        if (!showClashStats) return;
        ThreadManager.runOnShell(() -> {
            String apiUrl = getApiUrl();
            String res = ShellHelper.runCommand("curl -s --connect-timeout 1 " + apiUrl + "/proxies");
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    renderProxies(res);
                });
            }
        });
    }

    private void renderProxies(String json) {
        if (json == null || json.startsWith("Error")) return;
        try {
            JSONObject root = new JSONObject(json);
            JSONObject proxies = root.getJSONObject("proxies");
            proxyGroupsContainer.removeAllViews();
            
            Iterator<String> keys = proxies.keys();
            while (keys.hasNext()) {
                String groupName = keys.next();
                JSONObject group = proxies.getJSONObject(groupName);
                String type = group.optString("type", "");
                
                // Porting YACD filter: Only show proxy groups (ones that have 'all' members)
                // This includes Selector, URLTest, Fallback, LoadBalance, Relay
                if (!group.has("all")) continue;
                
                // Hide specific internal groups that aren't useful for selection if needed
                // But generally, if it has "all", it's a group.
                if (type.equals("Pass") || type.equals("Reject") || type.equals("Direct")) continue;

                String selected = group.optString("now", "");
                JSONArray all = group.getJSONArray("all");
                String[] allNames = new String[all.length()];
                for (int i = 0; i < all.length(); i++) allNames[i] = all.getString(i);

                addProxyGroupView(groupName, selected, allNames, proxies);
            }
        } catch (Exception e) {
            Log.e("Dashboard", "Parse error", e);
        }
    }

    private void addProxyGroupView(String groupName, String selected, String[] options, JSONObject allProxies) {
        View groupView = LayoutInflater.from(getContext()).inflate(R.layout.item_proxy_group, proxyGroupsContainer, false);
        TextView title = groupView.findViewById(R.id.proxyGroupName);
        GridLayout itemsContainer = groupView.findViewById(R.id.proxyItemsContainer);
        
        title.setText(groupName);
        for (String proxyName : options) {
            View proxyCard = LayoutInflater.from(getContext()).inflate(R.layout.item_proxy, itemsContainer, false);
            TextView nameTxt = proxyCard.findViewById(R.id.proxyName);
            TextView typeTxt = proxyCard.findViewById(R.id.proxyType);
            TextView latencyTxt = proxyCard.findViewById(R.id.proxyLatency);
            MaterialCardView card = (MaterialCardView) proxyCard;

            nameTxt.setText(proxyName);
            
            // Hide latency UI for DIRECT and REJECT as they have no ping
            if (proxyName.equalsIgnoreCase("DIRECT") || proxyName.equalsIgnoreCase("REJECT")) {
                latencyTxt.setVisibility(View.GONE);
            }

            try {
                JSONObject p = allProxies.getJSONObject(proxyName);
                typeTxt.setText(p.getString("type"));
                
                JSONArray history = p.optJSONArray("history");
                if (history != null && history.length() > 0) {
                    int lastDelay = history.getJSONObject(history.length() - 1).getInt("delay");
                    latencyTxt.setText(lastDelay > 0 ? lastDelay + " ms" : "err");
                } else {
                    latencyTxt.setText("---");
                }
            } catch (Exception e) {
                typeTxt.setText("Proxy");
                latencyTxt.setText("---");
            }

            if (proxyName.equals(selected)) {
                card.setStrokeColor(com.google.android.material.color.MaterialColors.getColor(card, android.R.attr.colorPrimary));
                card.setStrokeWidth(4);
                latencyTxt.setTextColor(com.google.android.material.color.MaterialColors.getColor(card, android.R.attr.colorPrimary));
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

    private void testAllProxiesLatency() {
        if (!showClashStats) return;
        
        // Porting YACD behavior: test ALL unique proxies found in all groups
        fastPollRemaining = 20; // 10 seconds of fast polling (20 * 500ms)

        ThreadManager.runOnShell(() -> {
            String apiUrl = getApiUrl();
            String res = ShellHelper.runCommand("curl -s --connect-timeout 1 " + apiUrl + "/proxies");
            if (res != null && !res.startsWith("Error")) {
                try {
                    JSONObject root = new JSONObject(res);
                    JSONObject proxies = root.getJSONObject("proxies");
                    java.util.HashSet<String> uniqueProxies = new java.util.HashSet<>();
                    
                    // Collect all proxy names from all groups
                    Iterator<String> keys = proxies.keys();
                    while (keys.hasNext()) {
                        String name = keys.next();
                        JSONObject p = proxies.getJSONObject(name);
                        String type = p.optString("type", "");
                        
                        // If it's a group, collect its members
                        if (type.equals("Selector") || type.equals("URLTest") || type.equals("Fallback")) {
                            JSONArray all = p.optJSONArray("all");
                            if (all != null) {
                                for (int i = 0; i < all.length(); i++) {
                                    uniqueProxies.add(all.getString(i));
                                }
                            }
                        } else {
                            // It's a single proxy
                            uniqueProxies.add(name);
                        }
                    }

                    StringBuilder batch = new StringBuilder();
                    int count = 0;
                    for (String proxyName : uniqueProxies) {
                        // Skip DIRECT and REJECT as they don't have delay endpoints
                        if (proxyName.equalsIgnoreCase("DIRECT") || proxyName.equalsIgnoreCase("REJECT")) continue;
                        
                        batch.append("curl -s -X GET --connect-timeout 2 \"")
                             .append(apiUrl).append("/proxies/").append(Uri.encode(proxyName))
                             .append("/delay?timeout=5000&url=http://www.gstatic.com/generate_204\" & ");
                        
                        count++;
                        // Limit batch size to avoid overwhelming the shell
                        if (count % 15 == 0) {
                            batch.append("wait; ");
                        }
                    }
                    if (batch.length() > 0) ShellHelper.runCommand(batch.toString() + " wait");
                } catch (Exception e) {
                    Log.e("Dashboard", "Latency test error", e);
                }
            }
        });
    }

    private void switchProxy(String group, String name) {
        ThreadManager.runOnShell(() -> {
            String apiUrl = getApiUrl();
            ShellHelper.runCommand("curl -s -X PUT --connect-timeout 1 -d '{\"name\":\"" + name + "\"}' " + apiUrl + "/proxies/" + Uri.encode(group));
            getActivity().runOnUiThread(this::refreshProxies);
        });
    }

    private String getApiUrl() {
        String dashUrl = prefs.getString("dash_url", "http://127.0.0.1:9090/ui");
        return dashUrl.replaceAll("/(ui|dashboard)/?$", "").replaceAll("/ui$", "");
    }

    private void refreshClashStats() {
        if (!isResumed() || !showClashStats) return;
        ThreadManager.runOnShell(() -> {
            String apiUrl = getApiUrl();
            String result = ShellHelper.runCommand("curl -s --connect-timeout 1 " + apiUrl + "/connections");
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded() || !showClashStats || result.startsWith("Error")) return;
                    try {
                        JSONObject root = new JSONObject(result);
                        clashDownloadText.setText(formatSize(root.optLong("downloadTotal", 0)));
                        clashUploadText.setText(formatSize(root.optLong("uploadTotal", 0)));
                        JSONArray conns = root.optJSONArray("connections");
                        clashConnectionsText.setText(conns != null ? String.valueOf(conns.length()) : "0");
                    } catch (Exception ignored) {}
                });
            }
        });
    }

    @Override
    public void onPause() { 
        super.onPause(); 
        stopStats(); 
        stopTimer();
        stopServiceStats();
    }
    
    @Override
    public void onDestroyView() {
        stopStats();
        stopTimer();
        stopServiceStats();
        timerHandler.removeCallbacksAndMessages(null);
        serviceStatsHandler.removeCallbacksAndMessages(null);
        
        if (webView != null) {
            webView.stopLoading();
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        // Nullify all view references to prevent leaks
        initialLayout = null;
        webViewContainer = null;
        webHeader = null;
        emptyStatsView = null;
        dashHeader = null;
        btnLatency = null;
        btnOpen = null;
        cardRules = null;
        clashStatsCard = null;
        title = null;
        labelProxyGroups = null;
        clashConnectionsText = null;
        clashDownloadText = null;
        clashUploadText = null;
        proxyGroupsContainer = null;
        btnRefresh = null;
        
        super.onDestroyView();
    }

    private void startStats() {
        if (!isStatsRunning && showClashStats) {
            isStatsRunning = true;
            statsCounter = 0;
            statsHandler.post(statsRunnable);
        }
    }

    private void stopStats() {
        isStatsRunning = false;
        statsHandler.removeCallbacks(statsRunnable);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        return String.format(Locale.getDefault(), "%.1f %cB", bytes / Math.pow(1024, exp), "KMGTPE".charAt(exp - 1));
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
    }
}
