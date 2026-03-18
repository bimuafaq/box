package com.rox.manager;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;

import java.util.Locale;

public class HomeFragment extends Fragment {
    private TextView statusText, coreText, runtimeText, cpuText, ramText;
    private MaterialButton startBtn, stopBtn;
    private boolean isActionRunning = false;
    
    private Handler timerHandler = new Handler();
    private long currentRuntimeSeconds = 0;
    private boolean isTimerRunning = false;
    
    private Handler statsHandler = new Handler();
    private boolean isStatsRunning = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        
        statusText = view.findViewById(R.id.statusText);
        coreText = view.findViewById(R.id.coreText);
        runtimeText = view.findViewById(R.id.runtimeText);
        cpuText = view.findViewById(R.id.cpuText);
        ramText = view.findViewById(R.id.ramText);
        
        startBtn = view.findViewById(R.id.startBtn);
        stopBtn = view.findViewById(R.id.stopBtn);
        MaterialButton webBtn = view.findViewById(R.id.webBtn);

        refreshAllInfo();

        startBtn.setOnClickListener(v -> runRootAction("/data/adb/box/scripts/box.service start", "Starting ROX..."));
        stopBtn.setOnClickListener(v -> runRootAction("/data/adb/box/scripts/box.service stop", "Stopping ROX..."));

        webBtn.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), WebViewActivity.class);
            startActivity(intent);
        });

        return view;
    }

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTimerRunning) {
                currentRuntimeSeconds++;
                updateRuntimeUI(currentRuntimeSeconds);
                timerHandler.postDelayed(this, 1000);
            }
        }
    };

    private final Runnable statsRunnable = new Runnable() {
        @Override
        public void run() {
            if (isStatsRunning) {
                refreshSystemStats();
                statsHandler.postDelayed(this, 3000); // Refresh every 3s
            }
        }
    };

    private void updateRuntimeUI(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        String timeStr = String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s);
        runtimeText.setText(timeStr);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshAllInfo();
        startStats();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopTimer();
        stopStats();
    }

    private void startTimer(long initialSeconds) {
        this.currentRuntimeSeconds = initialSeconds;
        if (!isTimerRunning) {
            isTimerRunning = true;
            timerHandler.post(timerRunnable);
        }
    }

    private void stopTimer() {
        isTimerRunning = false;
        timerHandler.removeCallbacks(timerRunnable);
    }

    private void startStats() {
        if (!isStatsRunning) {
            isStatsRunning = true;
            statsHandler.post(statsRunnable);
        }
    }

    private void stopStats() {
        isStatsRunning = false;
        statsHandler.removeCallbacks(statsRunnable);
    }

    private void refreshAllInfo() {
        new Thread(() -> {
            String cmd = "PID=$(cat /data/adb/box/run/box.pid 2>/dev/null || echo \"0\"); " +
                         "CORE=$(grep '^bin_name=' /data/adb/box/settings.ini | cut -d '\"' -f 2); " +
                         "ETIME=$(ps -p $PID -o etime= 2>/dev/null || echo \"00:00\"); " +
                         "echo \"$PID|$CORE|$ETIME\"";
            
            String result = ShellHelper.runRootCommand(cmd);
            
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (result != null && result.contains("|")) {
                        String[] parts = result.split("\\|");
                        String pid = parts[0].trim();
                        String core = (parts.length > 1) ? parts[1].trim() : "---";
                        String etime = (parts.length > 2) ? parts[2].trim() : "00:00";

                        if (pid.matches("\\d+") && !pid.equals("0")) {
                            statusText.setText(getString(R.string.status_running));
                            statusText.setTextColor(ContextCompat.getColor(getContext(), R.color.primary_indigo));
                            long seconds = parseETimeToSeconds(etime);
                            startTimer(seconds);
                        } else {
                            statusText.setText(getString(R.string.status_stopped));
                            statusText.setTextColor(0xFFE53935);
                            runtimeText.setText("00:00:00");
                            stopTimer();
                        }
                        coreText.setText(core.isEmpty() ? "---" : core.toUpperCase());
                    }
                });
            }
        }).start();
    }

    private void refreshSystemStats() {
        new Thread(() -> {
            // Get RAM: MemTotal and MemAvailable in kB
            String ramCmd = "cat /proc/meminfo | grep -E 'MemTotal|MemAvailable' | awk '{print $2}'";
            String cpuCmd = "top -n 1 -d 1 | grep 'CPU' | head -n 1"; // Varying output format
            
            String ramRes = ShellHelper.runRootCommand(ramCmd);
            // Fallback for CPU: more robust approach
            String cpuRes = ShellHelper.runRootCommand("top -n 1 | grep -i \"idle\" | head -n 1");

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    // Process RAM
                    if (ramRes != null && !ramRes.contains("Error")) {
                        String[] lines = ramRes.split("\n");
                        if (lines.length >= 2) {
                            try {
                                long totalKb = Long.parseLong(lines[0].trim());
                                long availKb = Long.parseLong(lines[1].trim());
                                long usedMb = (totalKb - availKb) / 1024;
                                long totalMb = totalKb / 1024;
                                ramText.setText(usedMb + " / " + totalMb + " MB");
                            } catch (Exception ignored) {}
                        }
                    }

                    // Process CPU (Simple heuristic)
                    if (cpuRes != null && !cpuRes.contains("Error")) {
                        try {
                            // Example: "CPU: 10% usr 5% sys 0% nic 85% idle"
                            // or "800%cpu  27%user   0%nice  25%sys 745%idle   0%iow   3%irq   0%sirq   0%host"
                            String lower = cpuRes.toLowerCase();
                            if (lower.contains("idle")) {
                                String[] parts = lower.split("\\s+");
                                for (int i = 0; i < parts.length; i++) {
                                    if (parts[i].contains("idle")) {
                                        // Usually the value is before "idle" or at parts[i-1]
                                        String valStr = parts[i].replace("idle", "").replace("%", "");
                                        if (valStr.isEmpty() && i > 0) {
                                            valStr = parts[i-1].replace("%", "");
                                        }
                                        if (!valStr.isEmpty()) {
                                            int idle = Integer.parseInt(valStr);
                                            // Heuristic: if idle > 100 (multi-core), normalize it or just show 100-idle
                                            // Most modern 'top' shows percentage per core or total.
                                            // We'll just show a simplified version.
                                            if (idle > 100) idle = idle / 8; // assuming 8 cores fallback
                                            int usage = Math.max(0, Math.min(100, 100 - idle));
                                            cpuText.setText(usage + "%");
                                            break;
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                            cpuText.setText("---");
                        }
                    }
                });
            }
        }).start();
    }

    private long parseETimeToSeconds(String etime) {
        try {
            String[] parts = etime.split(":");
            long seconds = 0;
            if (parts.length == 2) {
                seconds = Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1]);
            } else if (parts.length == 3) {
                if (parts[0].contains("-")) {
                    String[] dayHour = parts[0].split("-");
                    seconds = Long.parseLong(dayHour[0]) * 86400 + Long.parseLong(dayHour[1]) * 3600;
                } else {
                    seconds = Long.parseLong(parts[0]) * 3600;
                }
                seconds += Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2]);
            }
            return seconds;
        } catch (Exception e) {
            return 0;
        }
    }

    private void runRootAction(String command, String msg) {
        if (isActionRunning) return;
        isActionRunning = true;
        toggleButtons(false);
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            ShellHelper.runRootCommand(command);
            try { Thread.sleep(2200); } catch (InterruptedException ignored) {}
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    refreshAllInfo();
                    isActionRunning = false;
                    toggleButtons(true);
                });
            }
        }).start();
    }

    private void toggleButtons(boolean enabled) {
        startBtn.setEnabled(enabled);
        stopBtn.setEnabled(enabled);
    }
}
