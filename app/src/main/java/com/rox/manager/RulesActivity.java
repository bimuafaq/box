package com.rox.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
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

import android.os.Handler;
import android.os.Looper;

public class RulesActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private RuleAdapter adapter;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rules);
        
        prefs = getSharedPreferences("rox_prefs", Context.MODE_PRIVATE);

        recyclerView = findViewById(R.id.recyclerRules);
        MaterialButton btnRefresh = findViewById(R.id.btnRefreshRules);
        MaterialButton btnBack = findViewById(R.id.btnBackRules);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RuleAdapter();
        recyclerView.setAdapter(adapter);

        btnRefresh.setOnClickListener(v -> {
            v.animate().rotationBy(360).setDuration(500).start();
            refresh();
        });
        
        btnBack.setOnClickListener(v -> finish());
        
        refresh();
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
                handler.postDelayed(this, 10000);
            }
        }
    };

    private void refresh() {
        ThreadManager.runOnShell(() -> {
            String apiUrl = getApiUrl();
            String res = ShellHelper.runCommand("curl -s --connect-timeout 1 " + apiUrl + "/rules");
            runOnUiThread(() -> {
                parseAndSet(res);
            });
        });
    }

    private void parseAndSet(String json) {
        if (json == null || json.startsWith("Error")) return;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray rules = root.getJSONArray("rules");
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < rules.length(); i++) {
                list.add(rules.getJSONObject(i));
            }
            adapter.setData(list);
        } catch (Exception ignored) {}
    }

    private String getApiUrl() {
        String dashUrl = prefs.getString("dash_url", "http://127.0.0.1:9090/ui");
        return dashUrl.replaceAll("/(ui|dashboard)/?$", "").replaceAll("/ui$", "");
    }

    private class RuleAdapter extends RecyclerView.Adapter<RuleAdapter.ViewHolder> {
        private final List<JSONObject> data = new ArrayList<>();

        public void setData(List<JSONObject> newData) {
            data.clear();
            data.addAll(newData);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_rule, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            try {
                JSONObject item = data.get(position);
                holder.type.setText(item.optString("type", "UNKNOWN"));
                holder.payload.setText(item.optString("payload", "---"));
                holder.proxy.setText(item.optString("proxy", "DIRECT"));
            } catch (Exception ignored) {}
        }

        @Override
        public int getItemCount() { return data.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView type, payload, proxy;
            ViewHolder(View v) {
                super(v);
                type = v.findViewById(R.id.ruleType);
                payload = v.findViewById(R.id.rulePayload);
                proxy = v.findViewById(R.id.ruleProxy);
            }
        }
    }
}
