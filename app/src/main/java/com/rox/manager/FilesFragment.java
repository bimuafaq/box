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
import android.widget.PopupMenu;
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

    // Custom RecyclerView Editor (Anti-Freeze)
    private View fileListLayout, editorContainer;
    private RecyclerView editorRecyclerView;
    private EditorAdapter editorAdapter;
    private List<String> editorLines = new ArrayList<>();
    private TextView editorFileName;
    private String editingFilePath = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_files, container, false);

        recyclerView = view.findViewById(R.id.fileRecyclerView);
        swipeRefresh = view.findViewById(R.id.swipeRefreshFiles);
        searchEditText = view.findViewById(R.id.searchEditText);
        fileListLayout = view.findViewById(R.id.fileListLayout);
        
        // Custom Editor UI
        editorContainer = view.findViewById(R.id.editorContainer);
        editorRecyclerView = view.findViewById(R.id.editorRecyclerView);
        editorFileName = view.findViewById(R.id.editorFileName);
        MaterialButton btnBack = view.findViewById(R.id.btnEditorBack);
        MaterialButton btnSave = view.findViewById(R.id.btnEditorSave);
        
        FloatingActionButton btnAddAction = view.findViewById(R.id.btnAddAction);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new FileAdapter();
        recyclerView.setAdapter(adapter);

        editorRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        editorAdapter = new EditorAdapter();
        editorRecyclerView.setAdapter(editorAdapter);

        swipeRefresh.setOnRefreshListener(this::loadFiles);
        
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnBack.setOnClickListener(v -> closeEditor());
        btnSave.setOnClickListener(v -> saveFile());
        
        btnAddAction.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(getContext(), v);
            popup.getMenu().add("New File");
            popup.getMenu().add("New Folder");
            popup.setOnMenuItemClickListener(item -> {
                if (item.getTitle().equals("New File")) {
                    showInputDialog("New File", "Enter file name", name -> executeCommand("touch \"" + currentPath + "/" + name + "\"", "File created"));
                } else {
                    showInputDialog("New Folder", "Enter folder name", name -> executeCommand("mkdir -p \"" + currentPath + "/" + name + "\"", "Folder created"));
                }
                return true;
            });
            popup.show();
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
                    editorLines.clear();
                    if (content != null) {
                        // Split content by newline to feed RecyclerView
                        String[] lines = content.split("\n");
                        for (String line : lines) {
                            // Break extremely long lines (binaries) to prevent freeze
                            if (line.length() > 200) {
                                for (int i = 0; i < line.length(); i += 200) {
                                    editorLines.add(line.substring(i, Math.min(i + 200, line.length())));
                                }
                            } else {
                                editorLines.add(line);
                            }
                        }
                    }
                    if (editorLines.isEmpty()) editorLines.add("");
                    editorAdapter.notifyDataSetChanged();
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
        StringBuilder sb = new StringBuilder();
        for (String line : editorLines) { sb.append(line).append("\n"); }
        new Thread(() -> {
            boolean success = ShellHelper.writeRootFileBase64(editingFilePath, sb.toString());
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

    interface InputCallback { void onInput(String text); }

    static class FileData {
        String name, fullPath, size = "";
        boolean isDir, isBack;
        FileData(String name, String fullPath, boolean isDir, boolean isBack, String size) {
            this.name = name; this.fullPath = fullPath; this.isDir = isDir; this.isBack = isBack; this.size = size;
        }
    }

    class EditorAdapter extends RecyclerView.Adapter<EditorAdapter.ViewHolder> {
        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
            TextView tv = v.findViewById(android.R.id.text1);
            tv.setTextSize(12);
            tv.setPadding(8, 4, 8, 4);
            tv.setBackgroundColor(0x0A000000);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            return new ViewHolder(v);
        }
        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            TextView tv = holder.itemView.findViewById(android.R.id.text1);
            String lineNum = String.format(Locale.ROOT, "%3d | ", position + 1);
            tv.setText(lineNum + editorLines.get(position));
            
            holder.itemView.setOnClickListener(v -> {
                EditText input = new EditText(getContext());
                input.setText(editorLines.get(position));
                input.setTypeface(android.graphics.Typeface.MONOSPACE);
                new MaterialAlertDialogBuilder(getContext())
                    .setTitle("Edit Line " + (position + 1))
                    .setView(input)
                    .setPositiveButton("Update", (d, w) -> {
                        editorLines.set(position, input.getText().toString());
                        notifyItemChanged(position);
                    })
                    .setNegativeButton("Cancel", null).show();
            });
        }
        @Override public int getItemCount() { return editorLines.size(); }
        class ViewHolder extends RecyclerView.ViewHolder { ViewHolder(View v) { super(v); } }
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
                PopupMenu popup = new PopupMenu(getContext(), v);
                popup.getMenu().add("Rename");
                popup.getMenu().add("Delete");
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getTitle().equals("Delete")) {
                        new MaterialAlertDialogBuilder(getContext())
                            .setTitle("Delete")
                            .setMessage("Are you sure you want to delete " + data.name + "?")
                            .setPositiveButton("Delete", (d, w) -> executeCommand("rm -rf \"" + data.fullPath + "\"", "Deleted"))
                            .setNegativeButton("Cancel", null).show();
                    } else {
                        showInputDialog("Rename", "Enter new name", newName -> executeCommand("mv \"" + data.fullPath + "\" \"" + currentPath + "/" + newName + "\"", "Renamed"));
                    }
                    return true;
                });
                popup.show();
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
