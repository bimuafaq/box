package com.rox.manager;

import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.UUID;

public class ShellHelper {
    private static final String TAG = "ShellHelper";
    private static final String ALLOWED_PATH = "/data/adb/box";
    private static String appCacheDir;

    // Persistent shell for stats
    private static Process persistentProcess;
    private static BufferedWriter persistentWriter;
    private static BufferedReader persistentReader;

    public static void setCacheDir(String path) {
        appCacheDir = path;
    }

    private synchronized static boolean initPersistentShell() {
        if (persistentProcess == null) {
            try {
                persistentProcess = Runtime.getRuntime().exec("su");
                persistentWriter = new BufferedWriter(new OutputStreamWriter(persistentProcess.getOutputStream()));
                persistentReader = new BufferedReader(new InputStreamReader(persistentProcess.getInputStream()));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    public synchronized static String runRootCommand(String command) {
        if (!command.equals("id") && !command.contains(ALLOWED_PATH) && !command.contains(appCacheDir)) {
            return "Error: Unauthorized path.";
        }
        if (!initPersistentShell()) return "Error: Shell failed.";
        final String endMarker = "END_" + UUID.randomUUID().toString();
        try {
            persistentWriter.write(command + " 2>&1\n");
            persistentWriter.write("echo " + endMarker + "\n");
            persistentWriter.flush();
            
            StringBuilder output = new StringBuilder();
            long startTime = System.currentTimeMillis();
            while (true) {
                if (persistentReader.ready()) {
                    String line = persistentReader.readLine();
                    if (line == null) break;
                    if (line.contains(endMarker)) break;
                    output.append(line).append("\n");
                } else {
                    Thread.sleep(10);
                }
                
                // 5 second timeout to prevent infinite looping
                if (System.currentTimeMillis() - startTime > 5000) {
                    Log.e(TAG, "Command timeout: " + command);
                    closePersistentShell();
                    return "Error: Command timeout.";
                }
            }
            return output.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "Shell execution error", e);
            closePersistentShell();
            return "Error: " + e.getMessage();
        }
    }

    public static String runRootCommandOneShot(String command) {
        StringBuilder output = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
            os.close();
            reader.close();
            return output.toString().trim();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * technique: Bridge File. Very safe for large files.
     */
    public static String readRootFileDirect(String path) {
        if (appCacheDir == null) return null;
        File bridge = new File(appCacheDir, "bridge_read.txt");
        // Use root to copy file to app's cache and make it readable
        runRootCommandOneShot("cp \"" + path + "\" \"" + bridge.getAbsolutePath() + "\" && chmod 666 \"" + bridge.getAbsolutePath() + "\"");
        
        if (!bridge.exists()) return null;
        
        try (FileInputStream fis = new FileInputStream(bridge)) {
            byte[] data = new byte[(int) bridge.length()];
            fis.read(data);
            bridge.delete(); // cleanup
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Bridge read failed", e);
            return null;
        }
    }

    public static boolean writeRootFileDirect(String path, String content) {
        if (appCacheDir == null) return false;
        File bridge = new File(appCacheDir, "bridge_write.txt");
        
        try {
            // Write to bridge file in app territory
            try (FileOutputStream fos = new FileOutputStream(bridge)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
            // Use root to move it to destination
            String res = runRootCommandOneShot("cp \"" + bridge.getAbsolutePath() + "\" \"" + path + "\" && rm \"" + bridge.getAbsolutePath() + "\"");
            return res != null && !res.startsWith("Error:");
        } catch (Exception e) {
            Log.e(TAG, "Bridge write failed", e);
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
        String res = runRootCommandOneShot("id");
        return res != null && res.contains("uid=0");
    }
}
