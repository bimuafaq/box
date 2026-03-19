package com.rox.manager;

import android.util.Base64;
import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
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
            writer.write("(" + command + ") </dev/null 2>&1\n");
            writer.write("echo ''\n"); // Ensure newline before END_MARKER
            writer.write("echo " + END_MARKER + "\n");
            writer.flush();

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals(END_MARKER)) break;
                if (!line.trim().isEmpty()) {
                    output.append(line).append("\n");
                }
            }
            return output.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "Error executing command in persistent shell", e);
            closeShell();
            return "Error: " + e.getMessage();
        }
    }

    public static String readRootFileBase64(String path) {
        String res = runRootCommand("base64 " + path);
        if (res == null || res.startsWith("Error:")) return null;
        try {
            // Remove any whitespace/newlines from base64 output
            String cleanB64 = res.replaceAll("\\s+", "");
            byte[] data = Base64.decode(cleanB64, Base64.DEFAULT);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode base64", e);
            return null;
        }
    }

    public static boolean writeRootFileBase64(String path, String content) {
        try {
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
            // Use shell to decode and write
            String cmd = "echo '" + b64 + "' | base64 -d > " + path;
            String res = runRootCommand(cmd);
            return res != null && !res.startsWith("Error:");
        } catch (Exception e) {
            Log.e(TAG, "Failed to encode/write base64", e);
            return false;
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
