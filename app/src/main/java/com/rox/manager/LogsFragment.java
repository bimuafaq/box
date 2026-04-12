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
import com.google.android.material.materialswitch.MaterialSwitch;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import android.content.Context;
import android.content.SharedPreferences;

public class LogsFragment extends Fragment {
    private TextView logTextView, textSelectedLog;
    private SwipeRefreshLayout swipeRefresh;
    private MaterialSwitch switchLiveLogs;
    private NestedScrollView logScrollView;
    private View cardLogSource, btnRefreshLogs;
    private String selectedLogFile = "runs.log";
    private SharedPreferences prefs;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isLive = true;
    private int lastLogLength = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_logs, container, false);

        prefs = getActivity().getSharedPreferences("rox_prefs", Context.MODE_PRIVATE);
        selectedLogFile = prefs.getString("selected_log", "runs.log");
        isLive = prefs.getBoolean("is_live_logs", true);

        logTextView = view.findViewById(R.id.logTextView);
        textSelectedLog = view.findViewById(R.id.textSelectedLog);
        textSelectedLog.setText(selectedLogFile);
        swipeRefresh = view.findViewById(R.id.swipeRefreshLogs);
        switchLiveLogs = view.findViewById(R.id.switchLiveLogs);
        logScrollView = view.findViewById(R.id.logScrollView);
        cardLogSource = view.findViewById(R.id.cardLogSource);
        btnRefreshLogs = view.findViewById(R.id.btnRefreshLogs);

        // Load cached logs from previous session
        String cachedLogs = prefs.getString("last_logs_cache", "");
        if (!cachedLogs.isEmpty()) {
            logTextView.setText(formatLogText(cachedLogs));
            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        }

        switchLiveLogs.setChecked(isLive);
        switchLiveLogs.setOnCheckedChangeListener((v, checked) -> {
            isLive = checked;
            lastLogLength = 0;
            prefs.edit().putBoolean("is_live_logs", isLive).apply();
            if (isLive) startLiveLogs();
            else stopLiveLogs();
        });

        cardLogSource.setOnClickListener(v -> showLogSelectionDialog());

        btnRefreshLogs.setOnClickListener(v -> loadLogsFull());

        swipeRefresh.setOnRefreshListener(() -> {
            loadLogsFull();
            swipeRefresh.setRefreshing(false);
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isLive) {
            startLiveLogs();
        } else {
            // Auto refresh once when app/fragment is relogged/resumed if live logs is off
            loadLogs();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopLiveLogs();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopLiveLogs();
        handler.removeCallbacksAndMessages(null);
    }

    private void showLogSelectionDialog() {
        ThreadManager.runBackgroundTask(() -> {
            // Using a shell command once to list files since we need root permission for the directory
            // but we process the results natively in Java.
            String res = ShellHelper.runRootCommand("ls /data/adb/box/run/*.log");
            List<String> logFiles = new ArrayList<>();
            if (res != null && !res.isEmpty() && !res.startsWith("Error")) {
                for (String line : res.split("\n")) {
                    if (line.contains("/")) {
                        logFiles.add(line.substring(line.lastIndexOf("/") + 1));
                    } else if (!line.trim().isEmpty()) {
                        logFiles.add(line.trim());
                    }
                }
            }
            
            if (logFiles.isEmpty()) logFiles.add("runs.log");

            String[] items = logFiles.toArray(new String[0]);
            runOnUI(() -> {
                new MaterialAlertDialogBuilder(getContext())
                    .setTitle("Select Log Source")
                    .setItems(items, (dialog, which) -> {
                        selectedLogFile = items[which];
                        prefs.edit().putString("selected_log", selectedLogFile).apply();
                        textSelectedLog.setText(selectedLogFile);
                        lastLogLength = 0;
                        loadLogs();
                    })
                    .show();
            });
        });
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
        lastLogLength = logTextView.getText().length();
        handler.post(liveRunnable);
    }

    private void stopLiveLogs() {
        handler.removeCallbacks(liveRunnable);
    }

    private void loadLogs() {
        if (!isResumed()) return;
        ThreadManager.runBackgroundTask(() -> {
            String path = "/data/adb/box/run/" + selectedLogFile;
            String rawContent = ShellHelper.readRootFileDirect(path);

            runOnUI(() -> {
                if (rawContent == null) {
                    logTextView.setText(R.string.log_file_not_found);
                    return;
                }

                String fullContent = rawContent.trim();
                if (fullContent.isEmpty()) {
                    logTextView.setText("Log file is empty.");
                    lastLogLength = 0;
                    return;
                }

                // Append-only: only show new content since last read
                if (lastLogLength > 0 && fullContent.length() > lastLogLength) {
                    String newContent = fullContent.substring(lastLogLength);
                    logTextView.append(formatLogText(newContent));
                } else if (lastLogLength == 0) {
                    // First load or reset: show last 100 lines
                    String[] lines = fullContent.split("\n");
                    StringBuilder tailContent = new StringBuilder();
                    int start = Math.max(0, lines.length - 100);
                    for (int i = start; i < lines.length; i++) {
                        tailContent.append(lines[i]).append("\n");
                    }
                    String result = tailContent.toString().trim();
                    prefs.edit().putString("last_logs_cache", result).apply();
                    logTextView.setText(formatLogText(result));
                }
                // If content shrunk (log rotated), reload full
                else if (fullContent.length() < lastLogLength) {
                    String[] lines = fullContent.split("\n");
                    StringBuilder tailContent = new StringBuilder();
                    int start = Math.max(0, lines.length - 100);
                    for (int i = start; i < lines.length; i++) {
                        tailContent.append(lines[i]).append("\n");
                    }
                    String result = tailContent.toString().trim();
                    prefs.edit().putString("last_logs_cache", result).apply();
                    logTextView.setText(formatLogText(result));
                }

                lastLogLength = fullContent.length();
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
            });
        });
    }

    private void loadLogsFull() {
        lastLogLength = 0;
        loadLogs();
    }

    private void runOnUI(Runnable r) {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(r);
        }
    }

    private CharSequence formatLogText(String text) {
        SpannableString spannable = new SpannableString(text);
        String[] lines = text.split("\n");
        int currentPos = 0;

        for (String line : lines) {
            int lineEnd = currentPos + line.length();
            if (lineEnd > spannable.length()) break;

            String lower = line.toLowerCase(Locale.ROOT);
            int color = 0xFF9E9E9E; // Default grey

            if (lower.contains("info") || lower.contains("success")) color = 0xFF4CAF50;
            else if (lower.contains("warn")) color = 0xFFFFC107;
            else if (lower.contains("error") || lower.contains("fatal") || lower.contains("fail")) color = 0xFFF44336;
            else if (lower.contains("debug") || lower.contains("conn")) color = 0xFF2196F3;
            
            spannable.setSpan(new ForegroundColorSpan(color), currentPos, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
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
