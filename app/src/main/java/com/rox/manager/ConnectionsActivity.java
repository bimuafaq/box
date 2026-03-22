package com.rox.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ConnectionsActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private ConnAdapter adapter;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connections);
        
        prefs = getSharedPreferences("rox_prefs", Context.MODE_PRIVATE);

        recyclerView = findViewById(R.id.recyclerConns);
        MaterialButton btnRefresh = findViewById(R.id.btnRefreshConns);
        MaterialButton btnBack = findViewById(R.id.btnBackConns);
        MaterialButton btnCloseAll = findViewById(R.id.btnCloseAllConns);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ConnAdapter();
        recyclerView.setAdapter(adapter);

        btnRefresh.setOnClickListener(v -> {
            v.animate().rotationBy(360).setDuration(500).start();
            refresh();
        });
        
        btnCloseAll.setOnClickListener(v -> closeAllConnections());
        
        btnBack.setOnClickListener(v -> finish());
    }

    private void closeAllConnections() {
        ThreadManager.runBackgroundTask(() -> {
            String apiUrl = getApiUrl();
            ClashApiHelper.delete(apiUrl + "/connections");
            runOnUiThread(this::refresh);
        });
    }

    private void closeConnection(String id) {
        ThreadManager.runBackgroundTask(() -> {
            String apiUrl = getApiUrl();
            ClashApiHelper.delete(apiUrl + "/connections/" + id);
            runOnUiThread(this::refresh);
        });
    }

    private void refresh() {
        ThreadManager.runBackgroundTask(() -> {
            String apiUrl = getApiUrl();
            String res = ClashApiHelper.get(apiUrl + "/connections");
            runOnUiThread(() -> {
                parseAndSet(res);
            });
        });
    }

    private void parseAndSet(String json) {
        if (json == null || json.startsWith("Error")) return;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray conns = root.getJSONArray("connections");
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < conns.length(); i++) {
                list.add(conns.getJSONObject(i));
            }
            adapter.setData(list);
        } catch (Exception ignored) {}
    }

    private String getApiUrl() {
        String dashUrl = prefs.getString("dash_url", "http://127.0.0.1:9090/ui");
        return dashUrl.replaceAll("/(ui|dashboard)/?$", "").replaceAll("/ui$", "");
    }

    @Override
    protected void onResume() {
        super.onResume();
        isRunning = true;
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        isRunning = false;
        handler.removeCallbacks(refreshRunnable);
    }

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning) {
                refresh();
                handler.postDelayed(this, 3000);
            }
        }
    };

    private class ConnAdapter extends RecyclerView.Adapter<ConnAdapter.ViewHolder> {
        private final List<JSONObject> data = new ArrayList<>();

        public void setData(List<JSONObject> newData) {
            data.clear();
            data.addAll(newData);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_connection, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            try {
                JSONObject item = data.get(position);
                JSONObject metadata = item.getJSONObject("metadata");
                String id = item.optString("id", "");
                
                String network = metadata.optString("network", "TCP").toUpperCase();
                holder.network.setText(network);
                
                String host = metadata.optString("host", "");
                String destIp = metadata.optString("destinationIP", "");
                String destPort = metadata.optString("destinationPort", "");
                
                if (host.isEmpty()) {
                    host = destIp;
                    if (!destPort.isEmpty()) host += ":" + destPort;
                } else if (!destPort.isEmpty()) {
                    host += ":" + destPort;
                }
                holder.host.setText(host);
                
                String sourceIp = metadata.optString("sourceIP", "");
                String sourcePort = metadata.optString("sourcePort", "");
                String src = sourceIp;
                if (!sourcePort.isEmpty()) src += ":" + sourcePort;
                
                String dest = destIp;
                if (!destPort.isEmpty()) dest += ":" + destPort;
                
                String type = metadata.optString("type", "HTTP");
                
                String meta = type + " • " + src + " ➔ " + dest;
                holder.meta.setText(meta);
                
                JSONArray chain = item.getJSONArray("chains");
                holder.proxy.setText(chain.length() > 0 ? chain.getString(0) : "DIRECT");
                
                holder.up.setText(formatSize(item.optLong("upload", 0)));
                holder.down.setText(formatSize(item.optLong("download", 0)));

                holder.closeBtn.setOnClickListener(v -> {
                    if (!id.isEmpty()) closeConnection(id);
                });
            } catch (Exception ignored) {}
        }

        @Override
        public int getItemCount() { return data.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView network, host, meta, proxy, up, down;
            View closeBtn;
            ViewHolder(View v) {
                super(v);
                network = v.findViewById(R.id.connNetwork);
                host = v.findViewById(R.id.connHost);
                meta = v.findViewById(R.id.connMeta);
                proxy = v.findViewById(R.id.connProxy);
                up = v.findViewById(R.id.connUp);
                down = v.findViewById(R.id.connDown);
                closeBtn = v.findViewById(R.id.btnCloseConn);
            }
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        return String.format(Locale.getDefault(), "%.1f %cB", bytes / Math.pow(1024, exp), "KMGTPE".charAt(exp - 1));
    }
}
