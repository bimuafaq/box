package com.rox.manager;

import android.util.Base64;
import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.UUID;

public class ShellHelper {
    private static final String TAG = "ShellHelper";
    private static final String ALLOWED_PATH = "/data/adb/box";

    // Persistent shell for stats to avoid "su loop" spam
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
     * SILENT: No su loop spam.
     */
    public synchronized static String runRootCommand(String command) {
        if (!command.equals("id") && !command.contains(ALLOWED_PATH)) {
            return "Error: Unauthorized path.";
        }

        if (!initPersistentShell()) return "Error: Shell failed.";

        final String endMarker = "END_" + UUID.randomUUID().toString();
        try {
            persistentWriter.write(command + " 2>&1\n");
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
     * RELIABLE: Prevents hanging from complex background scripts.
     */
    public static String runRootCommandOneShot(String command) {
        if (!command.contains(ALLOWED_PATH)) return "Error: Unauthorized path.";
        
        StringBuilder output = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            InputStream is = process.getInputStream();
            InputStream es = process.getErrorStream();

            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(es));

            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            Thread t = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });
            t.start();

            StringBuilder errorOutput = new StringBuilder();
            Thread et = new Thread(() -> {
                try {
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        errorOutput.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });
            et.start();

            // Wait for completion with a 15-second safety timeout
            int exitCode = -1;
            boolean finished = false;
            long startTime = System.currentTimeMillis();
            
            while (System.currentTimeMillis() - startTime < 15000) {
                try {
                    exitCode = process.exitValue();
                    finished = true;
                    break;
                } catch (IllegalThreadStateException e) {
                    try { Thread.sleep(200); } catch (Exception ignored) {}
                }
            }

            if (!finished) {
                process.destroy();
                return "Error: Command timed out.";
            }

            t.join(2000);
            et.join(1000);

            os.close();
            reader.close();
            errorReader.close();
            
            String result = output.toString().trim();
            if (result.isEmpty() && errorOutput.length() > 0) {
                return "[Error]: " + errorOutput.toString().trim();
            }
            return result;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public static String readRootFileDirect(String path) {
        String res = runRootCommandOneShot("cat \"" + path + "\"");
        if (res == null || res.startsWith("Error:") || res.startsWith("[Error]")) return null;
        return res;
    }

    public static boolean writeRootFileDirect(String path, String content) {
        try {
            // Run cat and pipe our raw content directly into it via stdin
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat > \"" + path + "\""});
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.write(content.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close(); // Send EOF to cat
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to write file directly", e);
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
