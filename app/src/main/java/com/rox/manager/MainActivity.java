package com.rox.manager;

import android.os.Bundle;
import android.view.View;
import android.content.res.Configuration;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigationrail.NavigationRailView;
import androidx.activity.OnBackPressedCallback;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class MainActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigation;
    private NavigationRailView navigationRail;
    private boolean isSyncing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        viewPager = findViewById(R.id.viewPager);
        bottomNavigation = findViewById(R.id.bottomNavigation);
        navigationRail = findViewById(R.id.navigationRail);

        setupViewPager();
        setupNavigation();
        updateNavigationVisibility();
        
        // Set Dashboard as default selection
        syncNavSelection(R.id.nav_dashboard);

        ShellHelper.setCacheDir(getCacheDir().getAbsolutePath());
        checkRootAccess();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (viewPager.getCurrentItem() != 0) {
                    viewPager.setCurrentItem(0, false);
                    syncNavSelection(R.id.nav_dashboard);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void checkRootAccess() {
        ThreadManager.runOnShell(() -> {
            boolean hasRoot = ShellHelper.isRootAvailable();
            if (!hasRoot) {
                runOnUiThread(() -> {
                    new MaterialAlertDialogBuilder(this)
                        .setTitle("Root Access Required")
                        .setMessage("This application requires root access to function properly. Please grant root access and restart the app.")
                        .setCancelable(false)
                        .setPositiveButton("Exit", (dialog, which) -> {
                            finishAffinity();
                            System.exit(0);
                        })
                        .show();
                });
            }
        });
    }

    private void setupViewPager() {
        viewPager.setAdapter(new ViewPager2Adapter(this));
        viewPager.setUserInputEnabled(false);
        
        // Add Fade PageTransformer for smooth transitions
        viewPager.setPageTransformer((page, position) -> {
            page.setTranslationX(-position * page.getWidth());
            if (position < -1 || position > 1) {
                page.setAlpha(0f);
            } else {
                page.setAlpha(1f - Math.abs(position));
            }
        });
    }

    private void setupNavigation() {
        NavigationBarView.OnItemSelectedListener listener = item -> {
            if (isSyncing) return true;
            int itemId = item.getItemId();
            navigateToTab(itemId);
            return true;
        };

        bottomNavigation.setOnItemSelectedListener(listener);
        navigationRail.setOnItemSelectedListener(listener);
    }

    public void navigateToTab(int itemId) {
        int index = -1;
        if (itemId == R.id.nav_dashboard) index = 0;
        else if (itemId == R.id.nav_logs) index = 1;
        else if (itemId == R.id.nav_files) index = 2;
        else if (itemId == R.id.nav_settings) index = 3;

        if (index != -1) {
            viewPager.setCurrentItem(index, false);
            syncNavSelection(itemId);
        }
    }

    private void syncNavSelection(int itemId) {
        if (isSyncing) return;
        isSyncing = true;
        try {
            if (bottomNavigation.getSelectedItemId() != itemId) {
                bottomNavigation.setSelectedItemId(itemId);
            }
            if (navigationRail.getSelectedItemId() != itemId) {
                navigationRail.setSelectedItemId(itemId);
            }
        } finally {
            isSyncing = false;
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateNavigationVisibility();
    }

    private void updateNavigationVisibility() {
        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (isLandscape) {
            navigationRail.setVisibility(View.VISIBLE);
            bottomNavigation.setVisibility(View.GONE);
        } else {
            navigationRail.setVisibility(View.GONE);
            bottomNavigation.setVisibility(View.VISIBLE);
        }
    }

    private static class ViewPager2Adapter extends FragmentStateAdapter {
        public ViewPager2Adapter(@NonNull FragmentActivity fa) { super(fa); }
        @NonNull @Override public Fragment createFragment(int pos) {
            switch (pos) {
                case 0: return new DashboardFragment();
                case 1: return new LogsFragment();
                case 2: return new FilesFragment();
                case 3: return new SettingsFragment();
                default: return new DashboardFragment();
            }
        }
        @Override public int getItemCount() { return 4; }
    }
}
