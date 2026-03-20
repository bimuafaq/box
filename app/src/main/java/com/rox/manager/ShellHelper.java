package com.rox.manager;

import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

public class ShellHelper {
    private static final String TAG = "ShellHelper";
    private static final String ALLOWED_PATH = "/data/adb/box";

    public synchronized static String runRootCommand(String command) {
        // Izinkan perintah 'id' untuk cek root, sisanya harus lewat jalur aman
        if (!command.equals("id") && !command.contains(ALLOWED_PATH)) {
            Log.e(TAG, "Blocked unauthorized command: " + command);
            return "Error: Unauthorized path.";
        }

        StringBuilder output = new StringBuilder();
        Process process = null;
        BufferedWriter writer = null;
        BufferedReader reader = null;

        try {
            process = Runtime.getRuntime().exec("su");
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            writer.write(command + "\n");
            writer.write("exit\n");
            writer.flush();

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            process.waitFor();
            return output.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "Root execution error", e);
            return "Error: " + e.getMessage();
        } finally {
            try {
                if (writer != null) writer.close();
                if (reader != null) reader.close();
                if (process != null) process.destroy();
            } catch (Exception ignored) {}
        }
    }

    public static boolean isRootAvailable() {
        String res = runRootCommand("id");
        return res != null && res.contains("uid=0");
    }
}
