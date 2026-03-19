package com.rox.manager;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import androidx.core.widget.NestedScrollView;

public class LogsFragment extends Fragment {
    private TextView logTextView;
    private SwipeRefreshLayout swipeRefresh;
    private ChipGroup logFileGroup;
    private MaterialSwitch switchLiveLogs;
    private NestedScrollView logScrollView;
    private String selectedLogFile = "box.log";
    
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isLive = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_logs, container, false);

        logTextView = view.findViewById(R.id.logTextView);
        swipeRefresh = view.findViewById(R.id.swipeRefreshLogs);
        logFileGroup = view.findViewById(R.id.logFileGroup);
        switchLiveLogs = view.findViewById(R.id.switchLiveLogs);
        logScrollView = view.findViewById(R.id.logScrollView);

        setupLogChips();
        
        switchLiveLogs.setChecked(true);
        switchLiveLogs.setOnCheckedChangeListener((v, checked) -> {
            isLive = checked;
            if (isLive) startLiveLogs();
            else stopLiveLogs();
        });

        swipeRefresh.setOnRefreshListener(() -> {
            loadLogs();
            swipeRefresh.setRefreshing(false);
        });

        loadLogs();
        startLiveLogs();

        return view;
    }

    private void setupLogChips() {
        String[] logs = {"box.log", "clash.log", "sing-box.log", "xray.log", "v2fly.log", "hysteria.log"};
        for (String log : logs) {
            Chip chip = new Chip(getContext());
            chip.setText(log);
            chip.setCheckable(true);
            if (log.equals(selectedLogFile)) chip.setChecked(true);
            chip.setOnClickListener(v -> {
                selectedLogFile = log;
                loadLogs();
            });
            logFileGroup.addView(chip);
        }
    }

    private final Runnable liveRunnable = new Runnable() {
        @Override
        public void run() {
            if (isLive) {
                loadLogs();
                handler.postDelayed(this, 2000);
            }
        }
    };

    private void startLiveLogs() {
        handler.removeCallbacks(liveRunnable);
        handler.post(liveRunnable);
    }

    private void stopLiveLogs() {
        handler.removeCallbacks(liveRunnable);
    }

    private void loadLogs() {
        new Thread(() -> {
            String path = "/data/adb/box/run/" + selectedLogFile;
            // Get last 100 lines for performance
            String cmd = "tail -n 100 " + path + " 2>/dev/null || echo 'Log file not found or empty.'";
            String result = ShellHelper.runRootCommand(cmd);
            
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (result != null) {
                        logTextView.setText(formatLogText(result));
                        // Auto scroll to bottom
                        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
                    }
                });
            }
        }).start();
    }

    private CharSequence formatLogText(String text) {
        SpannableString spannable = new SpannableString(text);
        String[] lines = text.split("\n");
        int currentPos = 0;

        for (String line : lines) {
            int lineEnd = currentPos + line.length();
            if (lineEnd > spannable.length()) break;

            if (line.toLowerCase().contains("info")) {
                spannable.setSpan(new ForegroundColorSpan(0xFF4CAF50), currentPos, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.toLowerCase().contains("warn")) {
                spannable.setSpan(new ForegroundColorSpan(0xFFFFC107), currentPos, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.toLowerCase().contains("error") || line.toLowerCase().contains("fatal")) {
                spannable.setSpan(new ForegroundColorSpan(0xFFF44336), currentPos, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.toLowerCase().contains("debug")) {
                spannable.setSpan(new ForegroundColorSpan(0xFF2196F3), currentPos, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            
            currentPos = lineEnd + 1;
        }
        return spannable;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopLiveLogs();
    }
}
