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
    private View initialLayout, webViewContainer, webHeader, emptyStatsView, dashHeader, btnLatency, btnOpen, cardRules, clashStatsCard;
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
        
        btnOpen = view.findViewById(R.id.btnOpenFullWeb);
        btnRefresh = view.findViewById(R.id.btnRefreshDash);
        btnLatency = view.findViewById(R.id.btnLatencyDash);
        View btnRules = view.findViewById(R.id.btnRulesDash);
        MaterialButton btnClose = view.findViewById(R.id.btnCloseWeb);
        
        setupWebView();

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
                String type = group.getString("type");
                
                if (!type.equals("Selector") && !type.equals("URLTest") && !type.equals("Fallback")) continue;

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
    public void onPause() { super.onPause(); stopStats(); }
    
    @Override
    public void onDestroyView() {
        stopStats();
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
