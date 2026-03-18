package com.rox.manager;

import android.os.Bundle;
import android.view.View;
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
    private LinearLayout navHome, navLogs, navFiles, navSettings;
    private ImageView imgHome, imgLogs, imgFiles, imgSettings;
    private TextView txtHome, txtLogs, txtFiles, txtSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        viewPager = findViewById(R.id.viewPager);
        
        navHome = findViewById(R.id.navHome);
        navLogs = findViewById(R.id.navLogs);
        navFiles = findViewById(R.id.navFiles);
        navSettings = findViewById(R.id.navSettings);

        imgHome = findViewById(R.id.imgHome);
        imgLogs = findViewById(R.id.imgLogs);
        imgFiles = findViewById(R.id.imgFiles);
        imgSettings = findViewById(R.id.imgSettings);

        txtHome = findViewById(R.id.txtHome);
        txtLogs = findViewById(R.id.txtLogs);
        txtFiles = findViewById(R.id.txtFiles);
        txtSettings = findViewById(R.id.txtSettings);

        setupViewPager();
        setupBottomNav();
    }

    private void setupViewPager() {
        viewPager.setAdapter(new ViewPager2Adapter(this));
        viewPager.setUserInputEnabled(false);
        viewPager.setOffscreenPageLimit(3);
    }

    private void setupBottomNav() {
        navHome.setOnClickListener(v -> selectTab(0));
        navLogs.setOnClickListener(v -> selectTab(1));
        navFiles.setOnClickListener(v -> selectTab(2));
        navSettings.setOnClickListener(v -> selectTab(3));
        selectTab(0);
    }

    private void selectTab(int index) {
        viewPager.setCurrentItem(index, false);
        
        int activeColor = ContextCompat.getColor(this, R.color.primary_indigo);
        int inactiveColor = 0xFF9E9E9E; // Abu-abu

        // Reset all to Inactive
        imgHome.setColorFilter(inactiveColor);
        imgLogs.setColorFilter(inactiveColor);
        imgFiles.setColorFilter(inactiveColor);
        imgSettings.setColorFilter(inactiveColor);

        txtHome.setTextColor(inactiveColor);
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
                imgLogs.setColorFilter(activeColor);
                txtLogs.setTextColor(activeColor);
                break;
            case 2:
                imgFiles.setColorFilter(activeColor);
                txtFiles.setTextColor(activeColor);
                break;
            case 3:
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
                case 1: return new LogsFragment();
                case 2: return new FilesFragment();
                case 3: return new SettingsFragment();
                default: return new HomeFragment();
            }
        }
        @Override public int getItemCount() { return 4; }
    }
}
