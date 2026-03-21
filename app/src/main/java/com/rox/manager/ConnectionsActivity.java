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

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ConnAdapter();
        recyclerView.setAdapter(adapter);

        btnRefresh.setOnClickListener(v -> {
            v.animate().rotationBy(360).setDuration(500).start();
            refresh();
        });
        
        btnBack.setOnClickListener(v -> finish());
    }

    private void refresh() {
        ThreadManager.runOnShell(() -> {
            String apiUrl = getApiUrl();
            String res = ShellHelper.runCommand("curl -s --connect-timeout 1 " + apiUrl + "/connections");
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
                
                String host = metadata.optString("host", "");
                if (host.isEmpty()) host = metadata.optString("destinationIP", "Unknown");
                
                holder.host.setText(host);
                String meta = metadata.optString("type", "") + " • " + metadata.optString("sourceIP", "");
                holder.meta.setText(meta);
                
                JSONArray chain = item.getJSONArray("chains");
                holder.proxy.setText(chain.length() > 0 ? chain.getString(0) : "DIRECT");
                
                holder.up.setText(formatSize(item.optLong("upload", 0)));
                holder.down.setText(formatSize(item.optLong("download", 0)));
            } catch (Exception ignored) {}
        }

        @Override
        public int getItemCount() { return data.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView host, meta, proxy, up, down;
            ViewHolder(View v) {
                super(v);
                host = v.findViewById(R.id.connHost);
                meta = v.findViewById(R.id.connMeta);
                proxy = v.findViewById(R.id.connProxy);
                up = v.findViewById(R.id.connUp);
                down = v.findViewById(R.id.connDown);
            }
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        return String.format(Locale.getDefault(), "%.1f %cB", bytes / Math.pow(1024, exp), "KMGTPE".charAt(exp - 1));
    }
}
