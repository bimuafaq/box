package com.rox.manager;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

public class SettingsFragment extends Fragment {
    private TextView currentThemeText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        
        View themeSelection = view.findViewById(R.id.themeSelection);
        currentThemeText = view.findViewById(R.id.currentThemeText);
        MaterialSwitch notifySwitch = view.findViewById(R.id.notifySwitch);
        
        updateThemeLabel();

        themeSelection.setOnClickListener(v -> showThemeDialog());

        notifySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Toast.makeText(getContext(), isChecked ? "Notifications Enabled" : "Notifications Disabled", Toast.LENGTH_SHORT).show();
        });

        return view;
    }

    private void updateThemeLabel() {
        int mode = AppCompatDelegate.getDefaultNightMode();
        if (mode == AppCompatDelegate.MODE_NIGHT_YES) {
            currentThemeText.setText("Dark Mode (Malam)");
        } else if (mode == AppCompatDelegate.MODE_NIGHT_NO) {
            currentThemeText.setText("Light Mode (Siang)");
        } else {
            currentThemeText.setText("System Default (Auto)");
        }
    }

    private void showThemeDialog() {
        String[] options = {"System Default (Auto)", "Light Mode (Siang)", "Dark Mode (Malam)"};
        int checkedItem = 0;
        int mode = AppCompatDelegate.getDefaultNightMode();
        
        if (mode == AppCompatDelegate.MODE_NIGHT_NO) checkedItem = 1;
        else if (mode == AppCompatDelegate.MODE_NIGHT_YES) checkedItem = 2;

        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Select App Theme")
                .setSingleChoiceItems(options, checkedItem, (dialog, which) -> {
                    if (which == 0) {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                    } else if (which == 1) {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                    } else if (which == 2) {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                    }
                    updateThemeLabel();
                    dialog.dismiss();
                })
                .show();
    }
}
