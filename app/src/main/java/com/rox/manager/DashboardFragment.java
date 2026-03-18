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
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

public class DashboardFragment extends Fragment {
    private View initialLayout;
    private MaterialCardView webViewCard;
    private WebView webView;
    private TextView title;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dashboard, container, false);
        
        initialLayout = view.findViewById(R.id.initialLayout);
        webViewCard = view.findViewById(R.id.webViewCard);
        webView = view.findViewById(R.id.dashWebView);
        title = view.findViewById(R.id.dashTitle);
        
        MaterialButton btnOpen = view.findViewById(R.id.btnOpenFullWeb);
        
        setupWebView();

        btnOpen.setOnClickListener(v -> {
            initialLayout.setVisibility(View.GONE);
            webViewCard.setVisibility(View.VISIBLE);
            title.setVisibility(View.GONE); // Sembunyikan title agar WebView lebih luas
            webView.loadUrl("http://127.0.0.1:9090/ui");
        });

        return view;
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
