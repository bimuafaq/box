package com.rox.manager;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        viewPager = findViewById(R.id.viewPager);
        bottomNavigation = findViewById(R.id.bottomNavigation);

        setupViewPager();
        setupBottomNav();
    }

    private void setupViewPager() {
        viewPager.setAdapter(new ViewPager2Adapter(this));
        viewPager.setUserInputEnabled(false);
        viewPager.setOffscreenPageLimit(4);
        
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                bottomNavigation.getMenu().getItem(position).setChecked(true);
            }
        });
    }

    private void setupBottomNav() {
        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_home) {
                viewPager.setCurrentItem(0, false);
                return true;
            } else if (itemId == R.id.nav_dashboard) {
                viewPager.setCurrentItem(1, false);
                return true;
            } else if (itemId == R.id.nav_logs) {
                viewPager.setCurrentItem(2, false);
                return true;
            } else if (itemId == R.id.nav_files) {
                viewPager.setCurrentItem(3, false);
                return true;
            } else if (itemId == R.id.nav_settings) {
                viewPager.setCurrentItem(4, false);
                return true;
            }
            return false;
        });
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
