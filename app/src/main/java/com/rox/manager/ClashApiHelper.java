package com.rox.manager;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * ClashApiHelper: Native Java HTTP client for Clash API interactions.
 * Replaces curl shell commands for better performance and reliability.
 */
public class ClashApiHelper {
    private static final String TAG = "ClashApiHelper";
    private static final int TIMEOUT = 3000;

    public static String fetch(String urlStr, String method, String body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int code = conn.getResponseCode();
            if (code >= 400) {
                return "Error: HTTP " + code;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "API Error: " + urlStr, e);
            return "Error: " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static String get(String url) {
        return fetch(url, "GET", null);
    }

    public static String delete(String url) {
        return fetch(url, "DELETE", null);
    }

    public static String post(String url, String body) {
        return fetch(url, "POST", body);
    }

    public static String patch(String url, String body) {
        return fetch(url, "PATCH", body);
    }
    
    public static String put(String url, String body) {
        return fetch(url, "PUT", body);
    }
}
