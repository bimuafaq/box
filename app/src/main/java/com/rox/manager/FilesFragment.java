package com.rox.manager;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.android.material.snackbar.Snackbar;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

    // Editor Components
    private View fileListLayout, editorContainer;
    private EditText editorEditText;
    private TextView lineNumbersView, editorFileName;
    private String editingFilePath = "";
    private float currentTextSize = 13f;
    private ScaleGestureDetector scaleGestureDetector;

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

        // List UI
        recyclerView = view.findViewById(R.id.fileRecyclerView);
        pathIndicator = view.findViewById(R.id.pathIndicator);
        swipeRefresh = view.findViewById(R.id.swipeRefreshFiles);
        EditText searchEdit = view.findViewById(R.id.searchEditText);
        fileListLayout = view.findViewById(R.id.fileListLayout);

        // Editor UI
        editorContainer = view.findViewById(R.id.editorContainer);
        editorEditText = view.findViewById(R.id.editorEditText);
        lineNumbersView = view.findViewById(R.id.lineNumbers);
        editorFileName = view.findViewById(R.id.editorFileName);
        MaterialButton btnBack = view.findViewById(R.id.btnEditorBack);
        MaterialButton btnSave = view.findViewById(R.id.btnEditorSave);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new FileAdapter();
        recyclerView.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(this::loadFiles);

        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Pinch to Zoom Implementation (More Sensitive & Robust)
        scaleGestureDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float scaleFactor = detector.getScaleFactor();
                // Increase sensitivity by scaling the factor slightly
                float sensitivity = 1.1f; 
                if (scaleFactor > 1.0f) scaleFactor *= sensitivity;
                else scaleFactor /= sensitivity;

                currentTextSize *= scaleFactor;
                currentTextSize = Math.max(8f, Math.min(50f, currentTextSize));
                updateEditorTextSize();
                return true;
            }
        });

        editorEditText.setOnTouchListener((v, event) -> {
            // If more than one finger, disable scroll interception to focus on zoom
            if (event.getPointerCount() > 1) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                // Also reach up to the vertical ScrollView
                if (v.getParent().getParent() != null && v.getParent().getParent().getParent() != null) {
                    v.getParent().getParent().getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            
            scaleGestureDetector.onTouchEvent(event);
            return false; 
        });

        // Editor Listeners
        btnBack.setOnClickListener(v -> closeEditor());
        btnSave.setOnClickListener(v -> saveFile());

        editorEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateLineNumbers(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadFiles();
        return view;
    }

    private void loadFiles() {
        swipeRefresh.setRefreshing(true);
        pathIndicator.setText(currentPath);
        
        new Thread(() -> {
            List<FileData> folders = new ArrayList<>();
            List<FileData> files = new ArrayList<>();
            
            if (!currentPath.equals("/data/adb/box")) {
                folders.add(new FileData("..", getParentPath(currentPath), true, true, "Parent Directory"));
            }

            String res = ShellHelper.runRootCommand("ls -F " + currentPath);
            if (res != null && !res.isEmpty() && !res.contains("Error")) {
                String[] lines = res.split("\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    boolean isDir = line.endsWith("/");
                    String name = isDir ? line.substring(0, line.length() - 1) : line;
                    String fullPath = currentPath + "/" + name;
                    FileData data = new FileData(name, fullPath, isDir, false, isDir ? "Folder" : "File");
                    if (isDir) folders.add(data); else files.add(data);
                }
            }

            Comparator<FileData> comp = (a, b) -> a.name.toLowerCase().compareTo(b.name.toLowerCase());
            Collections.sort(folders, comp);
            Collections.sort(files, comp);

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    allFiles.clear();
                    allFiles.addAll(folders);
                    allFiles.addAll(files);
                    filter("");
                    swipeRefresh.setRefreshing(false);
                });
            }
        }).start();
    }

    private String getParentPath(String path) {
        int lastSlash = path.lastIndexOf("/");
        return (lastSlash <= 0) ? "/data/adb/box" : path.substring(0, lastSlash);
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

    private void openEditor(String path, String name) {
        editingFilePath = path;
        editorFileName.setText(name);
        fileListLayout.setVisibility(View.GONE);
        editorContainer.setVisibility(View.VISIBLE);
        
        new Thread(() -> {
            String content = ShellHelper.readRootFileBase64(path);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    editorEditText.setText(content != null ? content : "");
                    updateLineNumbers();
                });
            }
        }).start();
    }

    private void closeEditor() {
        editorContainer.setVisibility(View.GONE);
        fileListLayout.setVisibility(View.VISIBLE);
        editingFilePath = "";
    }

    private void saveFile() {
        String content = editorEditText.getText().toString();
        new Thread(() -> {
            boolean success = ShellHelper.writeRootFileBase64(editingFilePath, content);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (getView() != null && getActivity() != null) {
                        Snackbar.make(getView(), success ? "Saved Successfully!" : "Save Failed!", Snackbar.LENGTH_SHORT)
                            .setAnchorView(getActivity().findViewById(R.id.bottomNavigation))
                            .show();
                    }
                });
            }
        }).start();
    }

    private void updateLineNumbers() {
        String text = editorEditText.getText().toString();
        String[] lines = text.split("\n", -1);
        int lineCount = lines.length;
        
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lineCount; i++) {
            sb.append(i).append("\n");
        }
        lineNumbersView.setText(sb.toString());
    }

    private void updateEditorTextSize() {
        editorEditText.setTextSize(currentTextSize);
        lineNumbersView.setTextSize(currentTextSize);
    }

    class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {
        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_file, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            FileData data = filteredFiles.get(position);
            holder.name.setText(data.name);
            holder.size.setText(data.size);
            if (data.isBack) { holder.icon.setImageResource(R.drawable.ic_back_arrow); holder.icon.setRotation(0); }
            else if (data.isDir) { holder.icon.setImageResource(R.drawable.ic_folder); holder.icon.setRotation(0); }
            else { holder.icon.setImageResource(R.drawable.ic_logs); holder.icon.setRotation(0); }

            holder.itemView.setOnClickListener(v -> {
                if (data.isDir) { currentPath = data.fullPath; loadFiles(); }
                else { openEditor(data.fullPath, data.name); }
            });
        }
        @Override public int getItemCount() { return filteredFiles.size(); }
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView name, size; ImageView icon;
            ViewHolder(View v) { super(v); name = v.findViewById(R.id.itemName); size = v.findViewById(R.id.itemSize); icon = v.findViewById(R.id.itemIcon); }
        }
    }
}
