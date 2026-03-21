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
import java.util.UUID;

/**
 * ShellHelper: Optimized for Material 3 and root-level system management.
 * Features a persistent shell to minimize 'su' process overhead.
 */
public class ShellHelper {
    private static final String TAG = "ShellHelper";
    private static final String ALLOWED_PATH = "/data/adb/box";
    private static String appCacheDir;

    // Persistent shell components
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
                Log.e(TAG, "Failed to initialize persistent shell", e);
                return false;
            }
        }
        return true;
    }

    /**
     * Executes a root command using a persistent shell.
     * Synchronization ensures that multiple stats calls don't overlap in the same stream.
     */
    public synchronized static String runRootCommand(String command) {
        // Safety check for path authorization
        if (!command.equals("id") && !command.contains(ALLOWED_PATH) && (appCacheDir == null || !command.contains(appCacheDir))) {
            return "Error: Unauthorized path.";
        }
        
        if (!initPersistentShell()) return "Error: Shell initialization failed.";
        
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
                    if (line == null || line.contains(endMarker)) break;
                    output.append(line).append("\n");
                } else {
                    // Small sleep to reduce CPU usage during polling
                    Thread.sleep(15);
                }
                
                // 3 second timeout for persistent stats commands
                if (System.currentTimeMillis() - startTime > 3000) {
                    Log.e(TAG, "Command timed out: " + command);
                    closePersistentShell();
                    return "Error: Timeout";
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
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            try (DataOutputStream os = new DataOutputStream(process.getOutputStream());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                
                os.writeBytes(command + "\n");
                os.writeBytes("exit\n");
                os.flush();
                
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                process.waitFor();
            }
            return output.toString().trim();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        } finally {
            if (process != null) process.destroy();
        }
    }

    /**
     * Bridge File Pattern: Safely reads protected files by copying to cache.
     */
    public static String readRootFileDirect(String path) {
        if (appCacheDir == null) return null;
        File bridge = new File(appCacheDir, "bridge_read.txt");
        
        runRootCommandOneShot("cp \"" + path + "\" \"" + bridge.getAbsolutePath() + "\" && chmod 666 \"" + bridge.getAbsolutePath() + "\"");
        
        if (!bridge.exists()) return null;
        
        try (FileInputStream fis = new FileInputStream(bridge)) {
            byte[] data = new byte[(int) bridge.length()];
            int bytesRead = fis.read(data);
            if (!bridge.delete()) Log.w(TAG, "Could not delete bridge file");
            return bytesRead > 0 ? new String(data, 0, bytesRead, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            Log.e(TAG, "Bridge read failed", e);
            return null;
        }
    }

    public static boolean writeRootFileDirect(String path, String content) {
        if (appCacheDir == null) return false;
        File bridge = new File(appCacheDir, "bridge_write.txt");
        
        try {
            try (FileOutputStream fos = new FileOutputStream(bridge)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
            String res = runRootCommandOneShot("cp \"" + bridge.getAbsolutePath() + "\" \"" + path + "\" && rm \"" + bridge.getAbsolutePath() + "\"");
            return res != null && !res.startsWith("Error:");
        } catch (Exception e) {
            Log.e(TAG, "Bridge write failed", e);
            return false;
        }
    }

    public synchronized static void closePersistentShell() {
        try {
            if (persistentWriter != null) persistentWriter.close();
            if (persistentReader != null) persistentReader.close();
            if (persistentProcess != null) persistentProcess.destroy();
        } catch (Exception ignored) {}
        persistentWriter = null;
        persistentReader = null;
        persistentProcess = null;
    }

    public static boolean isRootAvailable() {
        String res = runRootCommandOneShot("id");
        return res != null && res.contains("uid=0");
    }

    public static String runCommand(String command) {
        StringBuilder output = new StringBuilder();
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            process.waitFor();
            return output.toString().trim();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        } finally {
            if (process != null) process.destroy();
        }
    }
}
