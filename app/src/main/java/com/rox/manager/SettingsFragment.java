package com.rox.manager;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.EditText;

public class SettingsFragment extends Fragment {
    private TextView currentThemeText, currentDashUrlText;
    private SharedPreferences prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        
        prefs = getActivity().getSharedPreferences("rox_prefs", Context.MODE_PRIVATE);

        View themeSelection = view.findViewById(R.id.themeSelection);
        currentThemeText = view.findViewById(R.id.currentThemeText);
        
        View dashUrlSelection = view.findViewById(R.id.dashUrlSelection);
        currentDashUrlText = view.findViewById(R.id.currentDashUrlText);
        
        updateThemeLabel();
        updateDashUrlLabel();

        themeSelection.setOnClickListener(v -> showThemeDialog());
        dashUrlSelection.setOnClickListener(v -> showDashUrlDialog());

        return view;
    }

    private void updateDashUrlLabel() {
        String url = prefs.getString("dash_url", "http://127.0.0.1:9090/ui");
        currentDashUrlText.setText(url);
    }

    private void showDashUrlDialog() {
        EditText input = new EditText(getContext());
        String currentUrl = prefs.getString("dash_url", "http://127.0.0.1:9090/ui");
        input.setText(currentUrl);
        input.setSelection(currentUrl.length());
        input.setHint("http://127.0.0.1:9090/ui");

        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Dashboard URL")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newUrl = input.getText().toString().trim();
                    if (newUrl.isEmpty()) newUrl = "http://127.0.0.1:9090/ui";
                    prefs.edit().putString("dash_url", newUrl).apply();
                    updateDashUrlLabel();
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Default", (dialog, which) -> {
                    prefs.edit().putString("dash_url", "http://127.0.0.1:9090/ui").apply();
                    updateDashUrlLabel();
                })
                .show();
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
