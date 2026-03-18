package com.rox.manager;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class FilesFragment extends Fragment {
    private RecyclerView recyclerView;
    private FileAdapter adapter;
    private List<FileData> allFiles = new ArrayList<>();
    private List<FileData> filteredFiles = new ArrayList<>();
    private String currentPath = "/data/adb/box";
    private SwipeRefreshLayout swipeRefresh;
    private EditText searchEditText;

    // Natural Editor Components
    private View fileListLayout, editorContainer;
    private EditText editorEditText;
    private TextView editorFileName, lineNumbers;
    private String editingFilePath = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_files, container, false);

        recyclerView = view.findViewById(R.id.fileRecyclerView);
        swipeRefresh = view.findViewById(R.id.swipeRefreshFiles);
        searchEditText = view.findViewById(R.id.searchEditText);
        fileListLayout = view.findViewById(R.id.fileListLayout);
        
        // Editor UI
        editorContainer = view.findViewById(R.id.editorContainer);
        editorEditText = view.findViewById(R.id.editorEditText);
        editorFileName = view.findViewById(R.id.editorFileName);
        lineNumbers = view.findViewById(R.id.lineNumbers);
        MaterialButton btnBack = view.findViewById(R.id.btnEditorBack);
        MaterialButton btnSave = view.findViewById(R.id.btnEditorSave);
        
        FloatingActionButton btnAddAction = view.findViewById(R.id.btnAddAction);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new FileAdapter();
        recyclerView.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(this::loadFiles);
        
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnBack.setOnClickListener(v -> closeEditor());
        btnSave.setOnClickListener(v -> saveFile());
        
        btnAddAction.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(getContext())
                .setTitle("Create New")
                .setItems(new String[]{"New File", "New Folder"}, (dialog, which) -> {
                    if (which == 0) {
                        showInputDialog("New File", "Enter file name", name -> executeCommand("touch \"" + currentPath + "/" + name + "\"", "File created"));
                    } else {
                        showInputDialog("New Folder", "Enter folder name", name -> executeCommand("mkdir -p \"" + currentPath + "/" + name + "\"", "Folder created"));
                    }
                })
                .show();
        });

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
        new Thread(() -> {
            String fullCmd = "ls -p \"" + currentPath + "\"";
            String result = ShellHelper.runRootCommand(fullCmd);
            
            List<FileData> list = new ArrayList<>();
            if (!currentPath.equals("/") && !currentPath.equals("/data/adb/box")) {
                list.add(new FileData("..", getParentPath(currentPath), true, true, ""));
            }

            if (result != null && !result.isEmpty()) {
                String[] lines = result.split("\n");
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    boolean isDir = line.endsWith("/");
                    String name = isDir ? line.substring(0, line.length()-1) : line;
                    String fullPath = currentPath + "/" + name;
                    
                    String size = "";
                    if (!isDir) {
                        String s = ShellHelper.runRootCommand("du -h \"" + fullPath + "\" | cut -f1");
                        size = s != null ? s.trim() : "";
                    }
                    list.add(new FileData(name, fullPath, isDir, false, size));
                }
            }
            
            Collections.sort(list, (a, b) -> {
                if (a.isBack) return -1;
                if (b.isBack) return 1;
                if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            });

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    allFiles.clear();
                    allFiles.addAll(list);
                    filter(searchEditText.getText().toString());
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
        
        if (getActivity() != null) {
            View nav = getActivity().findViewById(R.id.bottomNavigation);
            if (nav != null) nav.setVisibility(View.GONE);
        }

        new Thread(() -> {
            String content = ShellHelper.readRootFileBase64(path);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (content != null) {
                        // More efficient Anti-Freeze: Only break if lines are ridiculously long
                        if (content.length() > 50000) {
                            editorEditText.setText(content.substring(0, 50000) + "\n[File too large, showing partial content for stability]");
                        } else {
                            editorEditText.setText(content);
                        }
                    } else {
                        editorEditText.setText("");
                    }
                    // Small delay to ensure layout is ready for line numbering
                    editorEditText.postDelayed(this::updateLineNumbers, 100);
                });
            }
        }).start();
    }

    private void closeEditor() {
        editorContainer.setVisibility(View.GONE);
        fileListLayout.setVisibility(View.VISIBLE);
        editingFilePath = "";
        if (getActivity() != null) {
            View nav = getActivity().findViewById(R.id.bottomNavigation);
            if (nav != null) nav.setVisibility(View.VISIBLE);
        }
    }

    private void saveFile() {
        String content = editorEditText.getText().toString();
        new Thread(() -> {
            boolean success = ShellHelper.writeRootFileBase64(editingFilePath, content);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    showSnackbar(success ? "Saved Successfully!" : "Save Failed!");
                });
            }
        }).start();
    }

    private void executeCommand(String cmd, String successMsg) {
        new Thread(() -> {
            ShellHelper.runRootCommand(cmd);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    showSnackbar(successMsg);
                    loadFiles();
                });
            }
        }).start();
    }

    private void showInputDialog(String title, String hint, InputCallback callback) {
        EditText input = new EditText(getContext());
        input.setHint(hint);
        new MaterialAlertDialogBuilder(getContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK", (d, w) -> callback.onInput(input.getText().toString()))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showSnackbar(String msg) {
        if (getView() != null && getActivity() != null) {
            Snackbar.make(getView(), msg, Snackbar.LENGTH_SHORT)
                .setAnchorView(getActivity().findViewById(R.id.bottomNavigation))
                .show();
        }
    }

    private void updateLineNumbers() {
        if (editorEditText == null || lineNumbers == null) return;
        int lineCount = editorEditText.getLineCount();
        if (lineCount <= 0) lineCount = 1;
        
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lineCount; i++) { sb.append(i).append("\n"); }
        lineNumbers.setText(sb.toString());
    }

    interface InputCallback { void onInput(String text); }

    static class FileData {
        String name, fullPath, size = "";
        boolean isDir, isBack;
        FileData(String name, String fullPath, boolean isDir, boolean isBack, String size) {
            this.name = name; this.fullPath = fullPath; this.isDir = isDir; this.isBack = isBack; this.size = size;
        }
    }

    class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {
        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_file, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            FileData data = filteredFiles.get(position);
            holder.name.setText(data.name);
            holder.size.setText(data.size);
            
            if (data.isBack) { 
                holder.icon.setImageResource(R.drawable.ic_back_arrow); 
                holder.size.setVisibility(View.GONE);
            } else if (data.isDir) { 
                holder.icon.setImageResource(R.drawable.ic_folder); 
                holder.size.setVisibility(View.GONE);
            } else { 
                holder.icon.setImageResource(R.drawable.ic_logs); 
                holder.size.setVisibility(View.VISIBLE);
            }

            holder.itemView.setOnClickListener(v -> {
                if (data.isDir) { currentPath = data.fullPath; loadFiles(); }
                else { openEditor(data.fullPath, data.name); }
            });

            holder.itemView.setOnLongClickListener(v -> {
                if (data.isBack) return false;
                new MaterialAlertDialogBuilder(getContext())
                    .setTitle(data.name)
                    .setItems(new String[]{"Rename", "Delete"}, (dialog, which) -> {
                        if (which == 1) { // Delete
                            new MaterialAlertDialogBuilder(getContext())
                                .setTitle("Delete")
                                .setMessage("Are you sure you want to delete " + data.name + "?")
                                .setPositiveButton("Delete", (d, w) -> {
                                    // SAFETY GUARD: Prevent rm -rf / or deleting outside of box
                                    if (data.fullPath == null || data.fullPath.trim().isEmpty() || data.fullPath.equals("/") || !data.fullPath.startsWith("/data/adb/box")) {
                                        showSnackbar("Action blocked: Unsafe path detected!");
                                        return;
                                    }
                                    executeCommand("rm -rf \"" + data.fullPath + "\"", "Deleted");
                                })
                                .setNegativeButton("Cancel", null).show();
                        } else { // Rename
                            showInputDialog("Rename", "Enter new name", newName -> executeCommand("mv \"" + data.fullPath + "\" \"" + currentPath + "/" + newName + "\"", "Renamed"));
                        }
                    })
                    .show();
                return true;
            });
        }
        @Override public int getItemCount() { return filteredFiles.size(); }
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView name, size; ImageView icon;
            ViewHolder(View v) { 
                super(v); 
                name = v.findViewById(R.id.itemName); 
                size = v.findViewById(R.id.itemSize);
                icon = v.findViewById(R.id.itemIcon); 
            }
        }
    }
}
