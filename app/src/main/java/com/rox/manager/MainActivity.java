package com.rox.manager;

import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.card.MaterialCardView;

public class MainActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private MaterialCardView cardHome, cardLogs, cardFiles, cardSettings;
    private ImageView imgHome, imgLogs, imgFiles, imgSettings;
    private TextView txtHome, txtLogs, txtFiles, txtSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        viewPager = findViewById(R.id.viewPager);
        cardHome = findViewById(R.id.cardHome);
        cardLogs = findViewById(R.id.cardLogs);
        cardFiles = findViewById(R.id.cardFiles);
        cardSettings = findViewById(R.id.cardSettings);

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
        cardHome.setOnClickListener(v -> selectTab(0));
        cardLogs.setOnClickListener(v -> selectTab(1));
        cardFiles.setOnClickListener(v -> selectTab(2));
        cardSettings.setOnClickListener(v -> selectTab(3));
        selectTab(0);
    }

    private void selectTab(int index) {
        viewPager.setCurrentItem(index, false);
        
        int activeColor = ContextCompat.getColor(this, R.color.white);
        int inactiveColor = ContextCompat.getColor(this, R.color.text_secondary);
        int primaryColor = ContextCompat.getColor(this, R.color.primary_indigo);
        int transColor = android.R.color.transparent;

        // Reset all
        cardHome.setCardBackgroundColor(ContextCompat.getColor(this, transColor));
        cardLogs.setCardBackgroundColor(ContextCompat.getColor(this, transColor));
        cardFiles.setCardBackgroundColor(ContextCompat.getColor(this, transColor));
        cardSettings.setCardBackgroundColor(ContextCompat.getColor(this, transColor));
        
        cardHome.setCardElevation(0);
        cardLogs.setCardElevation(0);
        cardFiles.setCardElevation(0);
        cardSettings.setCardElevation(0);

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
                cardHome.setCardBackgroundColor(primaryColor);
                cardHome.setCardElevation(8);
                imgHome.setColorFilter(activeColor);
                txtHome.setTextColor(activeColor);
                break;
            case 1:
                cardLogs.setCardBackgroundColor(primaryColor);
                cardLogs.setCardElevation(8);
                imgLogs.setColorFilter(activeColor);
                txtLogs.setTextColor(activeColor);
                break;
            case 2:
                cardFiles.setCardBackgroundColor(primaryColor);
                cardFiles.setCardElevation(8);
                imgFiles.setColorFilter(activeColor);
                txtFiles.setTextColor(activeColor);
                break;
            case 3:
                cardSettings.setCardBackgroundColor(primaryColor);
                cardSettings.setCardElevation(8);
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
