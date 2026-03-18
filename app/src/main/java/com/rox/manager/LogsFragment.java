package com.rox.manager;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;

public class LogsFragment extends Fragment {
    private ChipGroup logFileGroup;
    private TextView logTextView;
    private NestedScrollView logScrollView;
    private SwipeRefreshLayout swipeRefresh;
    private MaterialSwitch switchLive;
    private String selectedLogPath = "";
    
    private final Handler liveHandler = new Handler(Looper.getMainLooper());
    private boolean isLiveEnabled = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_logs, container, false);

        logFileGroup = view.findViewById(R.id.logFileGroup);
        logTextView = view.findViewById(R.id.logTextView);
        logScrollView = view.findViewById(R.id.logScrollView);
        swipeRefresh = view.findViewById(R.id.swipeRefreshLogs);
        switchLive = view.findViewById(R.id.switchLiveLogs);
        MaterialButton btnRefresh = view.findViewById(R.id.btnRefreshLogs);

        btnRefresh.setOnClickListener(v -> loadLogFileList());
        
        swipeRefresh.setOnRefreshListener(this::refreshLogContent);
        
        switchLive.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isLiveEnabled = isChecked;
            if (isChecked) startLiveUpdate();
            else stopLiveUpdate();
        });

        loadLogFileList();

        return view;
    }

    private final Runnable liveRunnable = new Runnable() {
        @Override
        public void run() {
            if (isLiveEnabled) {
                refreshLogContentQuietly();
                liveHandler.postDelayed(this, 2000); // Polling every 2s
            }
        }
    };

    private void startLiveUpdate() {
        liveHandler.removeCallbacks(liveRunnable);
        liveHandler.post(liveRunnable);
    }

    private void stopLiveUpdate() {
        liveHandler.removeCallbacks(liveRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        stopLiveUpdate();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isLiveEnabled) startLiveUpdate();
    }

    private void loadLogFileList() {
        new Thread(() -> {
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

        if (logFileGroup.getChildCount() == 1) {
            chip.setChecked(true);
            selectedLogPath = path;
            refreshLogContent();
        }
    }

    private void refreshLogContent() {
        if (selectedLogPath.isEmpty()) {
            swipeRefresh.setRefreshing(false);
            return;
        }

        swipeRefresh.setRefreshing(true);
        new Thread(() -> {
            String content = ShellHelper.runRootCommand("tail -n 500 " + selectedLogPath);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    swipeRefresh.setRefreshing(false);
                    if (content != null && !content.isEmpty()) {
                        logTextView.setText(content);
                        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
                    } else {
                        logTextView.setText("Log file is empty.");
                    }
                });
            }
        }).start();
    }

    private void refreshLogContentQuietly() {
        if (selectedLogPath.isEmpty()) return;

        new Thread(() -> {
            String content = ShellHelper.runRootCommand("tail -n 500 " + selectedLogPath);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (content != null && !content.isEmpty()) {
                        // Cek jika konten berbeda untuk menghindari flickering
                        if (!content.equals(logTextView.getText().toString())) {
                            logTextView.setText(content);
                            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
                        }
                    }
                });
            }
        }).start();
    }
}
