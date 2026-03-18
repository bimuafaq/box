package com.rox.manager;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FilesFragment extends Fragment {
    private RecyclerView recyclerView;
    private FileAdapter adapter;
    private TextView pathIndicator;
    private SwipeRefreshLayout swipeRefresh;
    private String currentPath = "/data/adb/box";
    private List<FileData> allFiles = new ArrayList<>();
    private List<FileData> filteredFiles = new ArrayList<>();

    static class FileData {
        String name;
        String fullPath;
        boolean isDir;
        boolean isBack;
        String size;

        FileData(String name, String fullPath, boolean isDir, boolean isBack, String size) {
            this.name = name;
            this.fullPath = fullPath;
            this.isDir = isDir;
            this.isBack = isBack;
            this.size = size;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_files, container, false);

        recyclerView = view.findViewById(R.id.fileRecyclerView);
        pathIndicator = view.findViewById(R.id.pathIndicator);
        swipeRefresh = view.findViewById(R.id.swipeRefreshFiles);
        EditText searchEdit = view.findViewById(R.id.searchEditText);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new FileAdapter();
        recyclerView.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(this::loadFiles);

        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter(s.toString());
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        loadFiles();

        return view;
    }

    private void loadFiles() {
        swipeRefresh.setRefreshing(true);
        pathIndicator.setText(currentPath);
        
        new Thread(() -> {
            List<FileData> list = new ArrayList<>();
            
            // Add back button if not in root box dir
            if (!currentPath.equals("/data/adb/box")) {
                list.add(new FileData("..", getParentPath(currentPath), true, true, "Parent Directory"));
            }

            // Command: ls -F (adds / to dirs) and ls -lh for sizes
            String res = ShellHelper.runRootCommand("ls -F " + currentPath);
            
            if (res != null && !res.isEmpty() && !res.contains("Error")) {
                String[] lines = res.split("\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    
                    boolean isDir = line.endsWith("/");
                    String name = isDir ? line.substring(0, line.length() - 1) : line;
                    String fullPath = currentPath + "/" + name;
                    
                    // Simple size fetch for files
                    String size = isDir ? "Folder" : "File";
                    list.add(new FileData(name, fullPath, isDir, false, size));
                }
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    allFiles.clear();
                    allFiles.addAll(list);
                    filter("");
                    swipeRefresh.setRefreshing(false);
                });
            }
        }).start();
    }

    private String getParentPath(String path) {
        if (path.equals("/data/adb/box")) return path;
        int lastSlash = path.lastIndexOf("/");
        if (lastSlash <= 0) return "/";
        return path.substring(0, lastSlash);
    }

    private void filter(String query) {
        filteredFiles.clear();
        for (FileData f : allFiles) {
            if (f.name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)) || f.isBack) {
                filteredFiles.add(f);
            }
        }
        adapter.notifyDataSetChanged();
    }

    class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_file, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            FileData data = filteredFiles.get(position);
            holder.name.setText(data.name);
            holder.size.setText(data.size);
            
            if (data.isBack) {
                holder.icon.setImageResource(R.drawable.ic_home); // Temporary back icon
                holder.icon.setRotation(-90);
            } else if (data.isDir) {
                holder.icon.setImageResource(R.drawable.ic_folder);
                holder.icon.setRotation(0);
            } else {
                holder.icon.setImageResource(R.drawable.ic_logs); // Temporary file icon
                holder.icon.setRotation(0);
            }

            holder.itemView.setOnClickListener(v -> {
                if (data.isDir) {
                    currentPath = data.fullPath;
                    loadFiles();
                } else {
                    Intent intent = new Intent(getContext(), FileEditorActivity.class);
                    intent.putExtra("file_path", data.fullPath);
                    startActivity(intent);
                }
            });
        }

        @Override
        public int getItemCount() {
            return filteredFiles.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView name, size;
            ImageView icon;
            ViewHolder(View v) {
                super(v);
                name = v.findViewById(R.id.itemName);
                size = v.findViewById(R.id.itemSize);
                icon = v.findViewById(R.id.itemIcon);
            }
        }
    }
}
