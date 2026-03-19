package com.rox.manager;

import android.util.Base64;
import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ShellHelper {
    private static final String TAG = "ShellHelper";
    private static final String ALLOWED_PATH = "/data/adb/box";
    
    private static Process rootProcess;
    private static BufferedWriter writer;
    private static BufferedReader reader;
    private static BufferedReader errorReader;

    private synchronized static boolean initShell() {
        if (rootProcess == null) {
            try {
                rootProcess = Runtime.getRuntime().exec("su");
                writer = new BufferedWriter(new OutputStreamWriter(rootProcess.getOutputStream()));
                reader = new BufferedReader(new InputStreamReader(rootProcess.getInputStream()));
                errorReader = new BufferedReader(new InputStreamReader(rootProcess.getErrorStream()));
                Log.d(TAG, "Persistent root shell initialized.");
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to start root shell", e);
                return false;
            }
        }
        return true;
    }

    public synchronized static String runRootCommand(String command) {
        if (!command.equals("id") && !command.contains(ALLOWED_PATH)) {
            Log.e(TAG, "Blocked unauthorized command: " + command);
            return "Error: Unauthorized path.";
        }

        if (!initShell()) return "Error: Shell initialization failed.";

        final String endMarker = "END_" + UUID.randomUUID().toString();
        final StringBuilder output = new StringBuilder();
        
        try {
            // Isolasi command agar tidak menyandera stdin/stdout
            writer.write("(" + command + ") </dev/null 2>&1\n");
            writer.write("echo ''\n");
            writer.write("echo " + endMarker + "\n");
            writer.flush();

            // Thread pembaca output agar tidak memblokir jalur utama
            Thread readerThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains(endMarker)) break;
                        if (!line.trim().isEmpty()) {
                            output.append(line).append("\n");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Reader thread error", e);
                }
            });

            readerThread.start();
            // Kasih waktu maksimal 10 detik agar aplikasi tidak kena ANR jika script ngehang
            readerThread.join(10000); 
            
            if (readerThread.isAlive()) {
                Log.w(TAG, "Command timed out: " + command);
                // Jangan interupsi thread, biarkan dia mati sendiri nanti
                return output.append("[Timeout reached]").toString().trim();
            }

            return output.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "Error executing command", e);
            closeShell();
            return "Error: " + e.getMessage();
        }
    }

    public static String readRootFileBase64(String path) {
        String res = runRootCommand("base64 " + path);
        if (res == null || res.startsWith("Error:")) return null;
        try {
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
            if (errorReader != null) errorReader.close();
            if (rootProcess != null) rootProcess.destroy();
        } catch (Exception ignored) {}
        writer = null;
        reader = null;
        errorReader = null;
        rootProcess = null;
    }

    public static boolean isRootAvailable() {
        String res = runRootCommand("id");
        return res != null && res.contains("uid=0");
    }
}
