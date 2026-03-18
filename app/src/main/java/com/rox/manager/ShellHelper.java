package com.rox.manager;

import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.UUID;

public class ShellHelper {
    private static final String TAG = "ShellHelper";
    private static final String ALLOWED_PATH = "/data/adb/box";
    private static final String END_MARKER = UUID.randomUUID().toString();

    private static Process rootProcess;
    private static BufferedWriter writer;
    private static BufferedReader reader;

    private synchronized static void initShell() {
        if (rootProcess == null) {
            try {
                rootProcess = Runtime.getRuntime().exec("su");
                writer = new BufferedWriter(new OutputStreamWriter(rootProcess.getOutputStream()));
                reader = new BufferedReader(new InputStreamReader(rootProcess.getInputStream()));
                Log.d(TAG, "Persistent root shell initialized.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start root shell", e);
            }
        }
    }

    public synchronized static String runRootCommand(String command) {
        if (!command.equals("id") && !command.contains(ALLOWED_PATH)) {
            Log.e(TAG, "Blocked unauthorized command: " + command);
            return "Error: Unauthorized path.";
        }

        initShell();
        if (writer == null || reader == null) return "Error: Shell not available.";

        StringBuilder output = new StringBuilder();
        try {
            // Kita gunakan echo marker untuk tahu kapan perintah selesai tanpa menutup su
            writer.write(command + "\n");
            writer.write("echo " + END_MARKER + "\n");
            writer.flush();

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(END_MARKER)) break;
                output.append(line).append("\n");
            }
            return output.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "Error executing command in persistent shell", e);
            // Jika error, reset shell agar di-init ulang perintah berikutnya
            closeShell();
            return "Error: " + e.getMessage();
        }
    }

    private static void closeShell() {
        try {
            if (writer != null) writer.close();
            if (reader != null) reader.close();
            if (rootProcess != null) rootProcess.destroy();
        } catch (Exception ignored) {}
        writer = null;
        reader = null;
        rootProcess = null;
    }

    public static boolean isRootAvailable() {
        String res = runRootCommand("id");
        return res != null && res.contains("uid=0");
    }
}
