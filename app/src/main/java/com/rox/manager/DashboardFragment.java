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

public class DashboardFragment extends Fragment {
    private View initialLayout, webViewContainer, webHeader, emptyStatsView, dashHeader;
    private WebView webView;
    private TextView title, labelProxyGroups;
    private SwipeRefreshLayout swipeRefresh;
    private OnBackPressedCallback backPressedCallback;
    private SharedPreferences prefs;
    private LinearLayout proxyGroupsContainer;

    private TextView clashConnectionsText, clashDownloadText, clashUploadText;
    private View clashStatsCard;
    private MaterialButton btnOpen, btnRefresh, btnLatency;
    private boolean showClashStats = false;
    
    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private boolean isStatsRunning = false;

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
        swipeRefresh = view.findViewById(R.id.swipeRefreshDash);
        emptyStatsView = view.findViewById(R.id.emptyStatsView);
        
        proxyGroupsContainer = view.findViewById(R.id.proxyGroupsContainer);
        labelProxyGroups = view.findViewById(R.id.labelProxyGroups);

        clashStatsCard = view.findViewById(R.id.clashStatsCard);
        clashConnectionsText = view.findViewById(R.id.clashConnectionsText);
        clashDownloadText = view.findViewById(R.id.clashDownloadText);
        clashUploadText = view.findViewById(R.id.clashUploadText);
        
        btnOpen = view.findViewById(R.id.btnOpenFullWeb);
        btnRefresh = view.findViewById(R.id.btnRefreshDash);
        btnLatency = view.findViewById(R.id.btnLatencyDash);
        MaterialButton btnClose = view.findViewById(R.id.btnCloseWeb);
        
        setupWebView();

        btnRefresh.setOnClickListener(v -> {
            refreshClashStats();
            refreshProxies();
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

        swipeRefresh.setOnRefreshListener(() -> {
            webView.reload();
            swipeRefresh.setRefreshing(false);
        });

        btnOpen.setOnClickListener(v -> {
            stopStats(); // Stop background updates when webview is open
            initialLayout.setVisibility(View.GONE);
            dashHeader.setVisibility(View.GONE);
            btnOpen.setVisibility(View.GONE);
            
            webHeader.setVisibility(View.VISIBLE);
            webViewContainer.setVisibility(View.VISIBLE);
            String url = prefs.getString("dash_url", "http://127.0.0.1:9090/ui");
            webView.loadUrl(url);
            if (backPressedCallback != null) backPressedCallback.setEnabled(true);
        });

        btnClose.setOnClickListener(v -> {
            webHeader.setVisibility(View.GONE);
            webViewContainer.setVisibility(View.GONE);
            
            initialLayout.setVisibility(View.VISIBLE);
            dashHeader.setVisibility(View.VISIBLE);
            btnOpen.setVisibility(View.VISIBLE);
            webView.loadUrl("about:blank");
            if (backPressedCallback != null) backPressedCallback.setEnabled(false);
            startStats(); // Resume background updates
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        showClashStats = prefs.getBoolean("enable_clash_api", false);
        // Only show card if feature enabled AND webview is not open
        if (showClashStats && initialLayout.getVisibility() == View.VISIBLE) {
            clashStatsCard.setVisibility(View.VISIBLE);
            labelProxyGroups.setVisibility(View.VISIBLE);
            emptyStatsView.setVisibility(View.GONE);
            refreshProxies();
            startStats();
        } else {
            clashStatsCard.setVisibility(View.GONE);
            labelProxyGroups.setVisibility(View.GONE);
            emptyStatsView.setVisibility(initialLayout.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
            stopStats();
        }
    }

    private void refreshProxies() {
        if (!showClashStats) return;
        ThreadManager.runOnShell(() -> {
            String apiUrl = getApiUrl();
            // Use standard curl with 1s timeout, no root needed for localhost API
            String res = ShellHelper.runRootCommandOneShot("curl -s --connect-timeout 1 " + apiUrl + "/proxies");
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    renderProxies(res);
                });
            }
        });
    }

    private void renderProxies(String json) {
        if (json == null || !json.contains("\"proxies\"")) {
            proxyGroupsContainer.removeAllViews();
            return;
        }
        proxyGroupsContainer.removeAllViews();
        try {
            // Split into proxies object and the rest
            String proxiesPart = json.split("\"proxies\":\\{")[1];
            
            // First, find all Selector/URLTest/Fallback groups
            String[] parts = proxiesPart.split("\\},\"");
            for (String part : parts) {
                if (!part.contains("\"all\":[")) continue;
                
                String groupName = part.split("\":\\{")[0].replace("\"", "").replace("{", "");
                String type = part.split("\"type\":\"")[1].split("\"")[0];
                
                if (!type.equals("Selector") && !type.equals("URLTest") && !type.equals("Fallback")) continue;

                String selected = part.split("\"now\":\"")[1].split("\"")[0];
                String allStr = part.split("\"all\":\\[")[1].split("\\]")[0];
                String[] allProxyNames = allStr.replace("\"", "").split(",");

                addProxyGroupView(groupName, selected, allProxyNames, proxiesPart);
            }
        } catch (Exception ignored) {}
    }

    private void addProxyGroupView(String groupName, String selected, String[] options, String fullProxiesJson) {
        View groupView = LayoutInflater.from(getContext()).inflate(R.layout.item_proxy_group, proxyGroupsContainer, false);
        TextView title = groupView.findViewById(R.id.proxyGroupName);
        GridLayout itemsContainer = groupView.findViewById(R.id.proxyItemsContainer);
        
        title.setText(groupName);
        for (String opt : options) {
            String proxyName = opt.trim();
            View proxyCard = LayoutInflater.from(getContext()).inflate(R.layout.item_proxy, itemsContainer, false);
            
            TextView nameTxt = proxyCard.findViewById(R.id.proxyName);
            TextView typeTxt = proxyCard.findViewById(R.id.proxyType);
            TextView latencyTxt = proxyCard.findViewById(R.id.proxyLatency);
            MaterialCardView card = (MaterialCardView) proxyCard;

            nameTxt.setText(proxyName);
            
            // Extract type and latency from full JSON
            String proxyInfo = "";
            try {
                if (fullProxiesJson.contains("\"" + proxyName + "\":{")) {
                    proxyInfo = fullProxiesJson.split("\"" + proxyName + "\":\\{")[1].split("\\},\"")[0];
                    String type = proxyInfo.split("\"type\":\"")[1].split("\"")[0];
                    typeTxt.setText(type);

                    if (proxyInfo.contains("\"delay\":")) {
                        String delay = proxyInfo.split("\"delay\":")[1].split("[,}]")[0];
                        latencyTxt.setText(delay + " ms");
                    } else if (proxyInfo.contains("\"history\":[")) {
                        String history = proxyInfo.split("\"history\":\\[")[1].split("\\]")[0];
                        if (history.contains("\"delay\":")) {
                            String delay = history.split("\"delay\":")[1].split("[,}]")[0];
                            latencyTxt.setText(delay + " ms");
                        } else {
                            latencyTxt.setText("---");
                        }
                    } else {
                        latencyTxt.setText("---");
                    }
                }
            } catch (Exception e) {
                typeTxt.setText("Proxy");
                latencyTxt.setText("---");
            }

            // Highlight selected
            if (proxyName.equals(selected)) {
                card.setStrokeColor(com.google.android.material.color.MaterialColors.getColor(card, android.R.attr.colorPrimary));
                card.setStrokeWidth(4);
                latencyTxt.setTextColor(com.google.android.material.color.MaterialColors.getColor(card, android.R.attr.colorPrimary));
            }

            card.setOnClickListener(v -> switchProxy(groupName, proxyName));
            
            // GridLayout params for 2 columns
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED);
            int margin = (int) (4 * getResources().getDisplayMetrics().density);
            params.setMargins(margin, margin, margin, margin);
            proxyCard.setLayoutParams(params);

            itemsContainer.addView(proxyCard);
        }
        proxyGroupsContainer.addView(groupView);
    }

    private void testAllProxiesLatency() {
        if (!showClashStats) return;
        btnLatency.setEnabled(false);
        ThreadManager.runOnShell(() -> {
            String apiUrl = getApiUrl();
            // Get all groups first
            String res = ShellHelper.runRootCommandOneShot("curl -s --connect-timeout 1 " + apiUrl + "/proxies");
            if (res != null && res.contains("\"proxies\"")) {
                try {
                    String proxiesPart = res.split("\"proxies\":\\{")[1];
                    String[] parts = proxiesPart.split("\\},\"");
                    StringBuilder combinedCmd = new StringBuilder();
                    for (String part : parts) {
                        if (part.contains("\"type\":\"Selector\"") || part.contains("\"type\":\"URLTest\"") || part.contains("\"type\":\"Fallback\"")) {
                            String groupName = part.split("\":\\{")[0].replace("\"", "").replace("{", "");
                            combinedCmd.append("curl -s -X GET --connect-timeout 1 \"")
                                       .append(apiUrl).append("/proxies/")
                                       .append(Uri.encode(groupName))
                                       .append("/delay?timeout=5000&url=http://www.gstatic.com/generate_204\" & ");
                        }
                    }
                    if (combinedCmd.length() > 0) {
                        combinedCmd.append("wait");
                        ShellHelper.runRootCommandOneShot(combinedCmd.toString());
                    }
                } catch (Exception ignored) {}
            }
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    btnLatency.setEnabled(true);
                    refreshProxies();
                });
            }
        });
    }

    private void switchProxy(String group, String name) {
        ThreadManager.runOnShell(() -> {
            String apiUrl = getApiUrl();
            String payload = "{\"name\":\"" + name + "\"}";
            ShellHelper.runRootCommandOneShot("curl -s -X PUT --connect-timeout 1 -d '" + payload + "' " + apiUrl + "/proxies/" + group);
            getActivity().runOnUiThread(this::refreshProxies);
        });
    }

    private String getApiUrl() {
        String dashUrl = prefs.getString("dash_url", "http://127.0.0.1:9090/ui");
        String apiUrl = dashUrl.replaceAll("/(ui|dashboard)/?$", "");
        if (apiUrl.endsWith("/ui")) apiUrl = apiUrl.substring(0, apiUrl.length() - 3);
        return apiUrl;
    }

    @Override
    public void onPause() {
        super.onPause();
        stopStats();
    }

    @Override
    public void onDestroyView() {
        stopStats();
        if (webView != null) {
            webView.stopLoading();
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        }
        super.onDestroyView();
    }

    private final Runnable statsRunnable = new Runnable() {
        @Override
        public void run() {
            if (isStatsRunning) {
                refreshClashStats();
                statsHandler.postDelayed(this, 2000);
            }
        }
    };

    private void startStats() {
        if (!isStatsRunning && showClashStats) {
            isStatsRunning = true;
            statsHandler.post(statsRunnable);
        }
    }

    private void stopStats() {
        isStatsRunning = false;
        statsHandler.removeCallbacks(statsRunnable);
    }

    private void refreshClashStats() {
        if (!isResumed() || !showClashStats) return;
        ThreadManager.runOnShell(() -> {
            String apiUrl = getApiUrl();
            String clashCmd = "curl -s --connect-timeout 1 " + apiUrl + "/connections";
            String result = ShellHelper.runRootCommandOneShot(clashCmd);

            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded() || !showClashStats) return;
                    if (result != null && result.contains("\"connections\"")) {
                        try {
                            String dlTotal = "0";
                            String ulTotal = "0";
                            
                            if (result.contains("\"downloadTotal\":")) {
                                dlTotal = result.split("\"downloadTotal\":")[1].split("[,}]")[0];
                            }
                            if (result.contains("\"uploadTotal\":")) {
                                ulTotal = result.split("\"uploadTotal\":")[1].split("[,}]")[0];
                            }
                            
                            int count = 0;
                            int lastIndex = 0;
                            while ((lastIndex = result.indexOf("\"id\":", lastIndex)) != -1) {
                                count++;
                                lastIndex += 5;
                            }
                            
                            clashConnectionsText.setText(String.valueOf(count));
                            clashUploadText.setText(formatSize(Long.parseLong(ulTotal)));
                            clashDownloadText.setText(formatSize(Long.parseLong(dlTotal)));
                        } catch (Exception ignored) {}
                    }
                });
            }
        });
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.getDefault(), "%.1f %cB", bytes / Math.pow(1024, exp), pre);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        webView.setWebViewClient(new WebViewClient());
    }
}
