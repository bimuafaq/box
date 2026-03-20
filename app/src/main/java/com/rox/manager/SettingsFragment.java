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
import com.google.android.material.materialswitch.MaterialSwitch;

public class SettingsFragment extends Fragment {
    private TextView currentThemeText, currentDashUrlText;
    private TextView currentBinNameText, currentNetworkModeText, currentClashOptionText;
    private MaterialSwitch switchIpv6, switchQuic, switchClashStats;
    private SharedPreferences prefs;
    private boolean isUpdatingUI = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        
        prefs = getActivity().getSharedPreferences("rox_prefs", Context.MODE_PRIVATE);

        View themeSelection = view.findViewById(R.id.themeSelection);
        currentThemeText = view.findViewById(R.id.currentThemeText);
        
        View dashUrlSelection = view.findViewById(R.id.dashUrlSelection);
        currentDashUrlText = view.findViewById(R.id.currentDashUrlText);

        // Module Config Views
        switchIpv6 = view.findViewById(R.id.switchIpv6);
        switchQuic = view.findViewById(R.id.switchQuic);
        switchClashStats = view.findViewById(R.id.switchClashStats);
        currentBinNameText = view.findViewById(R.id.currentBinNameText);
        currentNetworkModeText = view.findViewById(R.id.currentNetworkModeText);
        currentClashOptionText = view.findViewById(R.id.currentClashOptionText);
        
        // Load Clash Stats preference
        switchClashStats.setChecked(prefs.getBoolean("show_clash_stats", false));
        switchClashStats.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("show_clash_stats", checked).apply();
        });

        View binNameSelection = view.findViewById(R.id.binNameSelection);
        View networkModeSelection = view.findViewById(R.id.networkModeSelection);
        View clashOptionSelection = view.findViewById(R.id.clashOptionSelection);
        
        updateThemeLabel();
        updateDashUrlLabel();
        loadModuleSettings();

        themeSelection.setOnClickListener(v -> showThemeDialog());
        dashUrlSelection.setOnClickListener(v -> showDashUrlDialog());

        switchIpv6.setOnCheckedChangeListener((v, checked) -> {
            if (!isUpdatingUI) updateSettingsIni("ipv6", String.valueOf(checked));
        });

        switchQuic.setOnCheckedChangeListener((v, checked) -> {
            if (!isUpdatingUI) updateQuicSetting(checked);
        });

        binNameSelection.setOnClickListener(v -> showBinNameDialog());
        networkModeSelection.setOnClickListener(v -> showNetworkModeDialog());
        clashOptionSelection.setOnClickListener(v -> showClashOptionDialog());

        return view;
    }

    private void loadModuleSettings() {
        ThreadManager.runOnShell(() -> {
            String ipv6 = ShellHelper.runRootCommand("grep '^ipv6=' /data/adb/box/settings.ini | cut -d '\"' -f 2");
            String binName = ShellHelper.runRootCommand("grep '^bin_name=' /data/adb/box/settings.ini | cut -d '\"' -f 2");
            String netMode = ShellHelper.runRootCommand("grep '^network_mode=' /data/adb/box/settings.ini | cut -d '\"' -f 2");
            String clashOpt = ShellHelper.runRootCommand("grep '^xclash_option=' /data/adb/box/settings.ini | cut -d '\"' -f 2");
            
            // Read QUIC from box.iptables
            String quicValue = ShellHelper.runRootCommand("grep '^quic=' /data/adb/box/scripts/box.iptables | cut -d '\"' -f 2");

            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    isUpdatingUI = true;
                    switchIpv6.setChecked("true".equalsIgnoreCase(ipv6));
                    switchQuic.setChecked("disable".equalsIgnoreCase(quicValue));
                    
                    currentBinNameText.setText(binName.isEmpty() ? "clash" : binName);
                    currentNetworkModeText.setText(netMode.isEmpty() ? "tproxy" : netMode);
                    currentClashOptionText.setText(clashOpt.isEmpty() ? "mihomo" : clashOpt);
                    isUpdatingUI = false;
                });
            }
        });
    }

    private void updateSettingsIni(String key, String value) {
        ThreadManager.runOnShell(() -> {
            ShellHelper.runRootCommand("sed -i 's/^" + key + "=.*/" + key + "=\"" + value + "\"/' /data/adb/box/settings.ini");
            loadModuleSettings();
        });
    }

    private void updateQuicSetting(boolean disable) {
        String val = disable ? "disable" : "enable";
        ThreadManager.runOnShell(() -> {
            ShellHelper.runRootCommand("sed -i 's/^quic=.*/quic=\"" + val + "\"/' /data/adb/box/scripts/box.iptables");
            loadModuleSettings();
        });
    }

    private void showBinNameDialog() {
        String[] options = {"clash", "sing-box", "xray", "v2fly", "hysteria"};
        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Select Binary Name")
                .setItems(options, (dialog, which) -> updateSettingsIni("bin_name", options[which]))
                .show();
    }

    private void showNetworkModeDialog() {
        String[] options = {"redirect", "tproxy", "mixed", "enhance", "tun"};
        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Select Network Mode")
                .setItems(options, (dialog, which) -> updateSettingsIni("network_mode", options[which]))
                .show();
    }

    private void showClashOptionDialog() {
        String[] options = {"mihomo", "premium"};
        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Select Clash Option")
                .setItems(options, (dialog, which) -> updateSettingsIni("xclash_option", options[which]))
                .show();
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
