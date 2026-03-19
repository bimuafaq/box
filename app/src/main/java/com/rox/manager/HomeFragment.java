package com.rox.manager;

import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.google.android.material.snackbar.Snackbar;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;

import java.util.Locale;

public class HomeFragment extends Fragment {
    private TextView statusText, coreText, runtimeText, cpuText, ramText, idCoreText;
    private MaterialButton startBtn, stopBtn, restartBtn;
    private boolean isActionRunning = false;
    
    private Handler timerHandler = new Handler(android.os.Looper.getMainLooper());
    private long currentRuntimeSeconds = 0;
    private boolean isTimerRunning = false;
    
    private Handler statsHandler = new Handler(android.os.Looper.getMainLooper());
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
        idCoreText = view.findViewById(R.id.idCoreText);
        
        startBtn = view.findViewById(R.id.startBtn);
        restartBtn = view.findViewById(R.id.restartBtn);
        stopBtn = view.findViewById(R.id.stopBtn);

        refreshAllInfo();

        startBtn.setOnClickListener(v -> runRootAction("/data/adb/box/scripts/box.service start && /data/adb/box/scripts/box.iptables enable && (pkill -f inotifyd; inotifyd /data/adb/box/scripts/box.inotify /data/adb/modules/box_for_root >/dev/null 2>&1 &)", "Starting ROX..."));
        restartBtn.setOnClickListener(v -> runRootAction("/data/adb/box/scripts/box.service restart", "Restarting ROX..."));
        stopBtn.setOnClickListener(v -> runRootAction("/data/adb/box/scripts/box.iptables disable && /data/adb/box/scripts/box.service stop && pkill -f inotifyd", "Stopping ROX..."));

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
                refreshCoreStats();
                statsHandler.postDelayed(this, 2000);
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
                            statusText.setTextColor(com.google.android.material.color.MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorPrimary));
                            long seconds = parseETimeToSeconds(etime);
                            startTimer(seconds);
                            coreText.setText(core.toUpperCase() + " (" + pid + ")");
                        } else {
                            statusText.setText(getString(R.string.status_stopped));
                            statusText.setTextColor(com.google.android.material.color.MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorError));
                            runtimeText.setText("00:00:00");
                            stopTimer();
                            coreText.setText("---");
                        }
                    }
                });
            }
        }).start();
    }

    private void refreshCoreStats() {
        new Thread(() -> {
            String cmd = "PID=$(cat /data/adb/box/run/box.pid 2>/dev/null || echo \"0\"); " +
                         "if [ \"$PID\" != \"0\" ]; then " +
                         "  RSS=$(grep VmRSS /proc/$PID/status | awk '{print $2}'); " +
                         "  CPU=$(ps -p $PID -o %cpu=); " +
                         "  CORE_ID=$(awk '{print $39}' /proc/$PID/stat); " +
                         "  echo \"$RSS|$CPU|$CORE_ID\"; " +
                         "else echo \"0|0|0\"; fi";
            
            String res = ShellHelper.runRootCommand(cmd);

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (res != null && res.contains("|")) {
                        String[] parts = res.split("\\|");
                        try {
                            long rssKb = Long.parseLong(parts[0].trim());
                            String cpu = parts[1].trim();
                            String coreId = parts[2].trim();

                            if (rssKb > 0) {
                                String ramStr = (rssKb >= 1024) ? (rssKb / 1024) + " MB" : rssKb + " KB";
                                ramText.setText(ramStr);
                                cpuText.setText(cpu.isEmpty() ? "0%" : cpu + "%");
                                idCoreText.setText(coreId.isEmpty() ? "-" : coreId);
                            } else {
                                ramText.setText("0 MB");
                                cpuText.setText("0%");
                                idCoreText.setText("-");
                            }
                        } catch (Exception ignored) {
                            ramText.setText("---");
                            cpuText.setText("---");
                            idCoreText.setText("-");
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
        
        statusText.setText(msg);
        statusText.setTextColor(com.google.android.material.color.MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOutline));

        if (getView() != null && getActivity() != null) {
            Snackbar snackbar = Snackbar.make(getView(), msg, Snackbar.LENGTH_SHORT);
            View nav = getActivity().findViewById(R.id.bottomNavigation);
            if (nav != null && nav.getVisibility() == View.VISIBLE) {
                snackbar.setAnchorView(nav);
            }
            snackbar.show();
        }

        new Thread(() -> {
            ShellHelper.runRootCommandOneShot(command);
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
        restartBtn.setEnabled(enabled);
        stopBtn.setEnabled(enabled);
    }
}
