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

public class DashboardFragment extends Fragment {
    private View initialLayout, webViewContainer, webHeader;
    private WebView webView;
    private TextView title;
    private SwipeRefreshLayout swipeRefresh;
    private OnBackPressedCallback backPressedCallback;
    private SharedPreferences prefs;

    private TextView clashConnectionsText, clashDownloadText, clashUploadText;
    private MaterialCardView clashStatsCard;
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
        webView = view.findViewById(R.id.dashWebView);
        title = view.findViewById(R.id.dashTitle);
        swipeRefresh = view.findViewById(R.id.swipeRefreshDash);

        clashStatsCard = view.findViewById(R.id.clashStatsCard);
        clashConnectionsText = view.findViewById(R.id.clashConnectionsText);
        clashDownloadText = view.findViewById(R.id.clashDownloadText);
        clashUploadText = view.findViewById(R.id.clashUploadText);
        
        MaterialButton btnOpen = view.findViewById(R.id.btnOpenFullWeb);
        MaterialButton btnClose = view.findViewById(R.id.btnCloseWeb);
        
        setupWebView();

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
            title.setVisibility(View.GONE);
            
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
            title.setVisibility(View.VISIBLE);
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
            startStats();
        } else {
            clashStatsCard.setVisibility(View.GONE);
            stopStats();
        }
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
