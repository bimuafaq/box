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
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import com.rox.manager.model.ApiResult;
import com.rox.manager.model.Connection;
import com.rox.manager.service.ClashApiService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Live connection viewer.
 *
 * <p>All Clash API interactions go through {@link ClashApiService}, which returns
 * typed {@link Connection} models. The UI layer never parses raw JSON.
 */
public class ConnectionsActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private ConnAdapter adapter;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;
    private boolean isPausedByUser = false;
    private ClashApiService clashApiService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connections);

        prefs = getSharedPreferences("rox_prefs", Context.MODE_PRIVATE);
        clashApiService = new ClashApiService(getApiUrl());

        recyclerView = findViewById(R.id.recyclerConns);
        MaterialButton btnRefresh = findViewById(R.id.btnRefreshConns);
        MaterialButton btnBack = findViewById(R.id.btnBackConns);
        FloatingActionButton fabClearAll = findViewById(R.id.fabClearAll);
        FloatingActionButton fabPause = findViewById(R.id.fabPause);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ConnAdapter();
        recyclerView.setAdapter(adapter);

        btnRefresh.setOnClickListener(v -> {
            v.animate().rotationBy(360).setDuration(500).start();
            refresh();
        });

        fabPause.setOnClickListener(v -> {
            isPausedByUser = !isPausedByUser;
            if (isPausedByUser) {
                isRunning = false;
                handler.removeCallbacks(refreshRunnable);
                fabPause.setImageResource(R.drawable.ic_play_arrow);
                fabClearAll.setVisibility(View.VISIBLE);
            } else {
                isRunning = true;
                refresh();
                handler.postDelayed(refreshRunnable, 3000);
                fabPause.setImageResource(R.drawable.ic_stop);
                fabClearAll.setVisibility(View.GONE);
            }
        });

        fabClearAll.setOnClickListener(v -> closeAllConnections());

        btnBack.setOnClickListener(v -> finish());
    }

    private void closeAllConnections() {
        ThreadManager.runBackgroundTask(() -> {
            ApiResult<Void> result = clashApiService.closeAllConnections();
            if (result.isSuccess()) {
                runOnUiThread(this::refresh);
            }
        });
    }

    private void refresh() {
        if (!isRunning && !isPausedByUser) return;
        ThreadManager.runBackgroundTask(() -> {
            ApiResult<List<Connection>> result = clashApiService.getConnections();
            if (result.isSuccess() && result.getData() != null) {
                runOnUiThread(() -> adapter.setData(result.getData()));
            }
        });
    }

    private String getApiUrl() {
        String dashUrl = prefs.getString("dash_url", "http://127.0.0.1:9090/ui");
        return dashUrl.replaceAll("/(ui|dashboard)/?$", "").replaceAll("/ui$", "");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isPausedByUser) {
            isRunning = true;
            handler.post(refreshRunnable);
        }
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
        private final List<Connection> data = new ArrayList<>();

        public void setData(List<Connection> newData) {
            data.clear();
            data.addAll(newData);
            notifyItemRangeInserted(0, newData.size());
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_connection, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Connection conn = data.get(position);
            holder.network.setText(conn.getNetwork());
            holder.host.setText(conn.getHost());
            String meta = String.format(java.util.Locale.getDefault(),
                    holder.itemView.getContext().getString(R.string.connection_meta_format),
                    conn.getType(), conn.getSource(), conn.getDestination());
            holder.meta.setText(meta);
            holder.proxy.setText(conn.getProxy());
            holder.up.setText(formatSize(conn.getUpload()));
            holder.down.setText(formatSize(conn.getDownload()));
        }

        @Override
        public int getItemCount() { return data.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView network, host, meta, proxy, up, down;
            ViewHolder(View v) {
                super(v);
                network = v.findViewById(R.id.connNetwork);
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
