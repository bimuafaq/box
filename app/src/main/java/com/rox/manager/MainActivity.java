package com.rox.manager;

import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

public class MainActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private LinearLayout navHome, navDashboard, navLogs, navFiles, navSettings;
    private ImageView imgHome, imgDashboard, imgLogs, imgFiles, imgSettings;
    private TextView txtHome, txtDashboard, txtLogs, txtFiles, txtSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        viewPager = findViewById(R.id.viewPager);
        
        navHome = findViewById(R.id.navHome);
        navDashboard = findViewById(R.id.navDashboard);
        navLogs = findViewById(R.id.navLogs);
        navFiles = findViewById(R.id.navFiles);
        navSettings = findViewById(R.id.navSettings);

        imgHome = findViewById(R.id.imgHome);
        imgDashboard = findViewById(R.id.imgDashboard);
        imgLogs = findViewById(R.id.imgLogs);
        imgFiles = findViewById(R.id.imgFiles);
        imgSettings = findViewById(R.id.imgSettings);

        txtHome = findViewById(R.id.txtHome);
        txtDashboard = findViewById(R.id.txtDashboard);
        txtLogs = findViewById(R.id.txtLogs);
        txtFiles = findViewById(R.id.txtFiles);
        txtSettings = findViewById(R.id.txtSettings);

        setupViewPager();
        setupBottomNav();
    }

    private void setupViewPager() {
        viewPager.setAdapter(new ViewPager2Adapter(this));
        viewPager.setUserInputEnabled(false);
        viewPager.setOffscreenPageLimit(4);
    }

    private void setupBottomNav() {
        navHome.setOnClickListener(v -> selectTab(0));
        navDashboard.setOnClickListener(v -> selectTab(1));
        navLogs.setOnClickListener(v -> selectTab(2));
        navFiles.setOnClickListener(v -> selectTab(3));
        navSettings.setOnClickListener(v -> selectTab(4));
        selectTab(0);
    }

    private void selectTab(int index) {
        viewPager.setCurrentItem(index, false);
        
        int activeColor = ContextCompat.getColor(this, R.color.primary_indigo);
        int inactiveColor = 0xFF9E9E9E;

        // Reset all
        imgHome.setColorFilter(inactiveColor);
        imgDashboard.setColorFilter(inactiveColor);
        imgLogs.setColorFilter(inactiveColor);
        imgFiles.setColorFilter(inactiveColor);
        imgSettings.setColorFilter(inactiveColor);

        txtHome.setTextColor(inactiveColor);
        txtDashboard.setTextColor(inactiveColor);
        txtLogs.setTextColor(inactiveColor);
        txtFiles.setTextColor(inactiveColor);
        txtSettings.setTextColor(inactiveColor);

        // Set Active
        switch (index) {
            case 0:
                imgHome.setColorFilter(activeColor);
                txtHome.setTextColor(activeColor);
                break;
            case 1:
                imgDashboard.setColorFilter(activeColor);
                txtDashboard.setTextColor(activeColor);
                break;
            case 2:
                imgLogs.setColorFilter(activeColor);
                txtLogs.setTextColor(activeColor);
                break;
            case 3:
                imgFiles.setColorFilter(activeColor);
                txtFiles.setTextColor(activeColor);
                break;
            case 4:
                imgSettings.setColorFilter(activeColor);
                txtSettings.setTextColor(activeColor);
                break;
        }
    }

    private static class ViewPager2Adapter extends FragmentStateAdapter {
        public ViewPager2Adapter(@NonNull FragmentActivity fa) { super(fa); }
        @NonNull @Override public Fragment createFragment(int pos) {
            switch (pos) {
                case 0: return new HomeFragment();
                case 1: return new DashboardFragment();
                case 2: return new LogsFragment();
                case 3: return new FilesFragment();
                case 4: return new SettingsFragment();
                default: return new HomeFragment();
            }
        }
        @Override public int getItemCount() { return 5; }
    }
}
