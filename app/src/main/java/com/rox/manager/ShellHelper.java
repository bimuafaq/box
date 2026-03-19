package com.rox.manager;

import android.util.Base64;
import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class ShellHelper {
    private static final String TAG = "ShellHelper";
    private static final String ALLOWED_PATH = "/data/adb/box";
    
    // Persistent shell for light stats only
    private static Process persistentProcess;
    private static BufferedWriter persistentWriter;
    private static BufferedReader persistentReader;

    private synchronized static boolean initPersistentShell() {
        if (persistentProcess == null) {
            try {
                persistentProcess = Runtime.getRuntime().exec("su");
                persistentWriter = new BufferedWriter(new OutputStreamWriter(persistentProcess.getOutputStream()));
                persistentReader = new BufferedReader(new InputStreamReader(persistentProcess.getInputStream()));
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to start persistent root shell", e);
                return false;
            }
        }
        return true;
    }

    /**
     * runRootCommand: Used for frequent stats/checks via persistent shell.
     */
    public synchronized static String runRootCommand(String command) {
        if (!command.equals("id") && !command.contains(ALLOWED_PATH)) {
            return "Error: Unauthorized path.";
        }

        if (!initPersistentShell()) return "Error: Shell failed.";

        final String endMarker = "END_" + UUID.randomUUID().toString();
        try {
            persistentWriter.write(command + "\n");
            persistentWriter.write("echo " + endMarker + "\n");
            persistentWriter.flush();

            StringBuilder output = new StringBuilder();
            String line;
            while ((line = persistentReader.readLine()) != null) {
                if (line.contains(endMarker)) break;
                output.append(line).append("\n");
            }
            return output.toString().trim();
        } catch (Exception e) {
            closePersistentShell();
            return "Error: " + e.getMessage();
        }
    }

    /**
     * runRootCommandOneShot: Used for critical service start/stop/restart.
     * This mimics BFR logic: opens su, runs command, exits immediately.
     * This prevents background daemons from hanging the persistent shell.
     */
    public static String runRootCommandOneShot(String command) {
        if (!command.contains(ALLOWED_PATH)) return "Error: Unauthorized path.";
        
        StringBuilder output = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            BufferedReader is = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader es = new BufferedReader(new InputStreamReader(p.getErrorStream()));

            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            String line;
            // Read output
            while ((line = is.readLine()) != null) {
                output.append(line).append("\n");
            }
            // Read errors
            while ((line = es.readLine()) != null) {
                output.append("[Error] ").append(line).append("\n");
            }

            p.waitFor();
            os.close();
            is.close();
            es.close();
            
            return output.toString().trim();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public static String readRootFileBase64(String path) {
        String res = runRootCommandOneShot("base64 " + path);
        if (res == null || res.startsWith("Error:")) return null;
        try {
            String cleanB64 = res.replaceAll("\\s+", "");
            byte[] data = Base64.decode(cleanB64, Base64.DEFAULT);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean writeRootFileBase64(String path, String content) {
        try {
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
            String cmd = "echo '" + b64 + "' | base64 -d > " + path;
            String res = runRootCommandOneShot(cmd);
            return res != null && !res.startsWith("Error:");
        } catch (Exception e) {
            return false;
        }
    }

    private static void closePersistentShell() {
        try {
            if (persistentWriter != null) persistentWriter.close();
            if (persistentReader != null) persistentReader.close();
            if (persistentProcess != null) persistentProcess.destroy();
        } catch (Exception ignored) {}
        persistentWriter = null; persistentReader = null; persistentProcess = null;
    }

    public static boolean isRootAvailable() {
        String res = runRootCommand("id");
        return res != null && res.contains("uid=0");
    }
}
