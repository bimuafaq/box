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

import com.rox.manager.service.ClashApiService;
import com.rox.manager.model.ApiResult;

public class SettingsFragment extends Fragment {
    private TextView currentThemeText;
    private TextView currentBinNameText, currentNetworkModeText, currentClashOptionText;
    private MaterialSwitch switchIpv6, switchQuic;
    private SharedPreferences prefs;
    private boolean isUpdatingUI = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        prefs = getActivity().getSharedPreferences("rox_prefs", Context.MODE_PRIVATE);

        View themeSelection = view.findViewById(R.id.themeSelection);
        currentThemeText = view.findViewById(R.id.currentThemeText);

        // Module Config Views
        switchIpv6 = view.findViewById(R.id.switchIpv6);
        switchQuic = view.findViewById(R.id.switchQuic);
        currentBinNameText = view.findViewById(R.id.currentBinNameText);
        currentNetworkModeText = view.findViewById(R.id.currentNetworkModeText);
        currentClashOptionText = view.findViewById(R.id.currentClashOptionText);

        updateThemeLabel();
        loadModuleSettings();

        View binNameSelection = view.findViewById(R.id.binNameSelection);
        View networkModeSelection = view.findViewById(R.id.networkModeSelection);
        View clashOptionSelection = view.findViewById(R.id.clashOptionSelection);

        themeSelection.setOnClickListener(v -> showThemeDialog());

        switchIpv6.setOnCheckedChangeListener((v, checked) -> {
            if (!isUpdatingUI) updateSettingsIni("ipv6", String.valueOf(checked));
        });

        switchQuic.setOnCheckedChangeListener((v, checked) -> {
            if (!isUpdatingUI) updateQuicSetting(checked);
        });

        binNameSelection.setOnClickListener(v -> showBinNameDialog());
        networkModeSelection.setOnClickListener(v -> showNetworkModeDialog());
        clashOptionSelection.setOnClickListener(v -> showClashOptionDialog());

        View btnClearFakeIp = view.findViewById(R.id.btnClearFakeIp);
        btnClearFakeIp.setOnClickListener(v -> clearFakeIpCache(v));

        View btnReloadConfig = view.findViewById(R.id.btnReloadConfig);
        btnReloadConfig.setOnClickListener(v -> reloadClashConfig(v));

        return view;
    }

    private void clearFakeIpCache(View btn) {
        View icon = ((ViewGroup) btn).getChildAt(1);
        if (icon != null) {
            icon.animate().rotationBy(360).setDuration(500).start();
        }

        ThreadManager.runBackgroundTask(() -> {
            String apiUrl = ClashApiService.normalizeBaseUrl(
                    prefs.getString("dash_url", "http://127.0.0.1:9090/ui"));
            ClashApiService service = new ClashApiService(apiUrl);
            ApiResult<Void> result = service.flushFakeIpCache();
            if (!result.isSuccess()) {
                android.util.Log.w("SettingsFragment", "FakeIP cache flush failed: " + result.getErrorMessage());
            }
        });
    }

    private void reloadClashConfig(View btn) {
        View icon = ((ViewGroup) btn).getChildAt(1);
        if (icon != null) {
            icon.animate().rotationBy(360).setDuration(500).start();
        }

        ThreadManager.runBackgroundTask(() -> {
            String apiUrl = ClashApiService.normalizeBaseUrl(
                    prefs.getString("dash_url", "http://127.0.0.1:9090/ui"));
            ClashApiService service = new ClashApiService(apiUrl);
            ApiResult<Boolean> result = service.reloadConfig();
            if (result.isSuccess()) {
                // Signal DashboardFragment to refresh proxies
                prefs.edit().putBoolean("reload_config_triggered", true).apply();
            } else {
                android.util.Log.w("SettingsFragment", "Config reload failed: " + result.getErrorMessage());
            }
        });
    }

    private void loadModuleSettings() {
        ThreadManager.runBackgroundTask(() -> {
            String settingsContent = ShellHelper.readRootFileDirect("/data/adb/box/settings.ini");
            String iptablesContent = ShellHelper.readRootFileDirect("/data/adb/box/scripts/box.iptables");

            runOnUI(() -> {
                if (settingsContent == null) return;
                isUpdatingUI = true;
                
                String ipv6 = getValue(settingsContent, "ipv6");
                String binName = getValue(settingsContent, "bin_name");
                String netMode = getValue(settingsContent, "network_mode");
                String clashOpt = getValue(settingsContent, "xclash_option");
                String quicValue = getValue(iptablesContent != null ? iptablesContent : "", "quic");

                switchIpv6.setChecked("true".equalsIgnoreCase(ipv6));
                switchQuic.setChecked("disable".equalsIgnoreCase(quicValue));
                
                currentBinNameText.setText(binName.isEmpty() ? "clash" : binName);
                currentNetworkModeText.setText(netMode.isEmpty() ? "tproxy" : netMode);
                currentClashOptionText.setText(clashOpt.isEmpty() ? "mihomo" : clashOpt);
                isUpdatingUI = false;
            });
        });
    }

    private String getValue(String content, String key) {
        for (String line : content.split("\n")) {
            if (line.trim().startsWith(key + "=")) {
                return line.split("=", 2)[1].replace("\"", "").trim();
            }
        }
        return "";
    }

    private void updateSettingsIni(String key, String value) {
        ThreadManager.runBackgroundTask(() -> {
            String path = "/data/adb/box/settings.ini";
            String content = ShellHelper.readRootFileDirect(path);
            if (content == null) return;

            StringBuilder newContent = new StringBuilder();
            boolean found = false;
            for (String line : content.split("\n")) {
                if (line.trim().startsWith(key + "=")) {
                    newContent.append(key).append("=\"").append(value).append("\"\n");
                    found = true;
                } else {
                    newContent.append(line).append("\n");
                }
            }
            if (!found) newContent.append(key).append("=\"").append(value).append("\"\n");

            if (ShellHelper.writeRootFileDirect(path, newContent.toString())) {
                runOnUI(this::loadModuleSettings);
            }
        });
    }

    private void updateQuicSetting(boolean disable) {
        String val = disable ? "disable" : "enable";
        ThreadManager.runBackgroundTask(() -> {
            String path = "/data/adb/box/scripts/box.iptables";
            String content = ShellHelper.readRootFileDirect(path);
            if (content == null) return;

            StringBuilder newContent = new StringBuilder();
            for (String line : content.split("\n")) {
                if (line.trim().startsWith("quic=")) {
                    newContent.append("quic=\"").append(val).append("\"\n");
                } else {
                    newContent.append(line).append("\n");
                }
            }

            if (ShellHelper.writeRootFileDirect(path, newContent.toString())) {
                runOnUI(this::loadModuleSettings);
            }
        });
    }

    private void runOnUI(Runnable r) {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(r);
        }
    }

    private void showBinNameDialog() {
        String[] options = {"clash", "sing-box", "xray", "v2fly", "hysteria"};
        String current = currentBinNameText.getText().toString();
        int checkedItem = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(current)) {
                checkedItem = i;
                break;
            }
        }

        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Select Binary Name")
                .setSingleChoiceItems(options, checkedItem, (dialog, which) -> {
                    updateSettingsIni("bin_name", options[which]);
                    prefs.edit().putBoolean("core_changed", true).apply();
                    dialog.dismiss();
                })
                .show();
    }

    private void showNetworkModeDialog() {
        String[] options = {"redirect", "tproxy", "mixed", "enhance", "tun"};
        String current = currentNetworkModeText.getText().toString();
        int checkedItem = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(current)) {
                checkedItem = i;
                break;
            }
        }

        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Select Network Mode")
                .setSingleChoiceItems(options, checkedItem, (dialog, which) -> {
                    updateSettingsIni("network_mode", options[which]);
                    dialog.dismiss();
                })
                .show();
    }

    private void showClashOptionDialog() {
        String[] options = {"mihomo", "premium"};
        String current = currentClashOptionText.getText().toString();
        int checkedItem = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(current)) {
                checkedItem = i;
                break;
            }
        }

        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Select Clash Option")
                .setSingleChoiceItems(options, checkedItem, (dialog, which) -> {
                    updateSettingsIni("xclash_option", options[which]);
                    dialog.dismiss();
                })
                .show();
    }

    private void updateThemeLabel() {
        int mode = AppCompatDelegate.getDefaultNightMode();
        if (mode == AppCompatDelegate.MODE_NIGHT_YES) {
            currentThemeText.setText(R.string.theme_dark_mode);
        } else if (mode == AppCompatDelegate.MODE_NIGHT_NO) {
            currentThemeText.setText(R.string.theme_light_mode);
        } else {
            currentThemeText.setText(R.string.theme_system_default);
        }
    }

    private void showThemeDialog() {
        String[] options = getResources().getStringArray(R.array.theme_options);
        int checkedItem = 0;
        int mode = AppCompatDelegate.getDefaultNightMode();
        
        if (mode == AppCompatDelegate.MODE_NIGHT_NO) checkedItem = 1;
        else if (mode == AppCompatDelegate.MODE_NIGHT_YES) checkedItem = 2;

        new MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.dialog_select_theme)
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
