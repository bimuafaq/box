package com.rox.manager;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.io.File;

public class LogsFragment extends Fragment {
    private ChipGroup logFileGroup;
    private TextView logTextView;
    private NestedScrollView logScrollView;
    private String selectedLogPath = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_logs, container, false);

        logFileGroup = view.findViewById(R.id.logFileGroup);
        logTextView = view.findViewById(R.id.logTextView);
        logScrollView = view.findViewById(R.id.logScrollView);
        MaterialButton btnRefresh = view.findViewById(R.id.btnRefreshLogs);

        btnRefresh.setOnClickListener(v -> refreshLogContent());

        loadLogFileList();

        return view;
    }

    private void loadLogFileList() {
        new Thread(() -> {
            // Ambil semua file .log di folder run
            String res = ShellHelper.runRootCommand("ls /data/adb/box/run/*.log");
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    logFileGroup.removeAllViews();
                    if (res != null && !res.isEmpty() && !res.contains("Error") && !res.contains("No such file")) {
                        String[] files = res.split("\n");
                        for (String fullPath : files) {
                            String fileName = new File(fullPath).getName();
                            addLogChip(fileName, fullPath);
                        }
                    } else {
                        logTextView.setText("No log files found in /data/adb/box/run/");
                    }
                });
            }
        }).start();
    }

    private void addLogChip(String name, String path) {
        Chip chip = new Chip(getContext());
        chip.setText(name);
        chip.setCheckable(true);
        chip.setClickable(true);
        chip.setTag(path);
        
        chip.setOnClickListener(v -> {
            selectedLogPath = path;
            refreshLogContent();
        });

        logFileGroup.addView(chip);

        // Auto select first chip
        if (logFileGroup.getChildCount() == 1) {
            chip.setChecked(true);
            selectedLogPath = path;
            refreshLogContent();
        }
    }

    private void refreshLogContent() {
        if (selectedLogPath.isEmpty()) return;

        logTextView.setText("Reading " + new File(selectedLogPath).getName() + "...");
        
        new Thread(() -> {
            // Baca 500 baris terakhir agar tidak lag
            String content = ShellHelper.runRootCommand("tail -n 500 " + selectedLogPath);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (content != null && !content.isEmpty()) {
                        logTextView.setText(content);
                        // Auto scroll to bottom
                        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
                    } else {
                        logTextView.setText("Log file is empty.");
                    }
                });
            }
        }).start();
    }
}
