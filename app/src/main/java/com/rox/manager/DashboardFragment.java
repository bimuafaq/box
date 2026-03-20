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
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

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
    private MaterialButton btnOpen, btnRefresh;
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
        MaterialButton btnClose = view.findViewById(R.id.btnCloseWeb);
        
        setupWebView();

        btnRefresh.setOnClickListener(v -> {
            refreshClashStats();
            refreshProxies();
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
        showClashStats = prefs.getBoolean("show_clash_stats", false);
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
            String res = ShellHelper.runRootCommandOneShot("curl -s " + apiUrl + "/proxies");
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    renderProxies(res);
                });
            }
        });
    }

    private void renderProxies(String json) {
        if (json == null || !json.contains("\"proxies\"")) return;
        proxyGroupsContainer.removeAllViews();
        try {
            // Simplified manual parsing for proxy groups
            // In a real app, use a JSON library like Org.Json or Gson
            String proxiesContent = json.split("\"proxies\":\\{")[1];
            String[] groups = proxiesContent.split("\\},\"");
            
            for (String groupStr : groups) {
                if (!groupStr.contains("\"all\":[")) continue;
                
                String name = groupStr.split("\":\\{")[0].replace("\"", "");
                String type = groupStr.split("\"type\":\"")[1].split("\"")[0];
                
                if (!type.equals("Selector") && !type.equals("URLTest") && !type.equals("Fallback")) continue;

                String now = groupStr.split("\"now\":\"")[1].split("\"")[0];
                String allStr = groupStr.split("\"all\":\\[")[1].split("\\]")[0];
                String[] allProxies = allStr.replace("\"", "").split(",");

                addProxyGroupView(name, now, allProxies);
            }
        } catch (Exception ignored) {}
    }

    private void addProxyGroupView(String groupName, String selected, String[] options) {
        View card = LayoutInflater.from(getContext()).inflate(R.layout.item_proxy_group, proxyGroupsContainer, false);
        TextView title = card.findViewById(R.id.proxyGroupName);
        ChipGroup chipGroup = card.findViewById(R.id.proxyChipGroup);
        
        title.setText(groupName);
        for (String opt : options) {
            Chip chip = new Chip(getContext());
            chip.setText(opt);
            chip.setCheckable(true);
            chip.setChecked(opt.equals(selected));
            chip.setClickable(true);
            chip.setOnClickListener(v -> switchProxy(groupName, opt));
            chipGroup.addView(chip);
        }
        proxyGroupsContainer.addView(card);
    }

    private void switchProxy(String group, String name) {
        ThreadManager.runOnShell(() -> {
            String apiUrl = getApiUrl();
            String payload = "{\"name\":\"" + name + "\"}";
            ShellHelper.runRootCommandOneShot("curl -s -X PUT -d '" + payload + "' " + apiUrl + "/proxies/" + group);
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
            String dashUrl = prefs.getString("dash_url", "http://127.0.0.1:9090/ui");
            String apiUrl = dashUrl.replaceAll("/(ui|dashboard)/?$", "");
            if (apiUrl.endsWith("/ui")) apiUrl = apiUrl.substring(0, apiUrl.length() - 3);
            
            String clashCmd = "curl -s " + apiUrl + "/connections";
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
