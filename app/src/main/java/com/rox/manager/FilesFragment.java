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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.widget.PopupMenu;

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
    private TextView pathIndicator;
    private SwipeRefreshLayout swipeRefresh;
    private EditText searchEditText;

    // Editor Components
    private View fileListLayout, editorContainer;
    private EditText editorEditText;
    private TextView editorFileName, lineNumbers;
    private String editingFilePath = "";
    private float currentTextSize = 13f;
    private ScaleGestureDetector scaleGestureDetector;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_files, container, false);

        recyclerView = view.findViewById(R.id.fileRecyclerView);
        pathIndicator = view.findViewById(R.id.pathIndicator);
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
        
        // Toolbar Buttons
        MaterialButton btnNewFile = view.findViewById(R.id.btnNewFile);
        MaterialButton btnNewFolder = view.findViewById(R.id.btnNewFolder);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new FileAdapter();
        recyclerView.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(this::loadFiles);
        
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Pinch to Zoom
        scaleGestureDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float scaleFactor = detector.getScaleFactor();
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
            if (event.getPointerCount() > 1) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                if (v.getParent().getParent() != null && v.getParent().getParent().getParent() != null) {
                    v.getParent().getParent().getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            scaleGestureDetector.onTouchEvent(event);
            return false; 
        });

        btnBack.setOnClickListener(v -> closeEditor());
        btnSave.setOnClickListener(v -> saveFile());
        
        btnNewFile.setOnClickListener(v -> showInputDialog("New File", "Enter file name", name -> executeCommand("touch \"" + currentPath + "/" + name + "\"", "File created")));
        btnNewFolder.setOnClickListener(v -> showInputDialog("New Folder", "Enter folder name", name -> executeCommand("mkdir -p \"" + currentPath + "/" + name + "\"", "Folder created")));

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
            String cmd = "ls -F \"" + currentPath + "\" | grep -v '/$' || true; ls -d */ \"" + currentPath + "\" 2>/dev/null || true";
            // Correct way to list with info:
            String fullCmd = "ls -p \"" + currentPath + "\"";
            String result = ShellHelper.runRootCommand(fullCmd);
            
            List<FileData> list = new ArrayList<>();
            if (!currentPath.equals("/") && !currentPath.equals("/data/adb/box")) {
                list.add(new FileData("..", getParentPath(currentPath), true, true));
            }

            if (result != null && !result.isEmpty()) {
                String[] lines = result.split("\n");
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    boolean isDir = line.endsWith("/");
                    String name = isDir ? line.substring(0, line.length()-1) : line;
                    String fullPath = currentPath + "/" + name;
                    list.add(new FileData(name, fullPath, isDir, false));
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
                    pathIndicator.setText(currentPath);
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
        new Thread(() -> {
            // Anti-Freeze 2.0: Check if file is binary using grep
            // -q: quiet, -I: process binary as if it didn't match
            String isBinary = ShellHelper.runRootCommand("grep -qI . \"" + path + "\"; echo $?");
            String sizeRes = ShellHelper.runRootCommand("stat -c%s \"" + path + "\"");
            long size = 0;
            try { size = Long.parseLong(sizeRes.trim()); } catch (Exception ignored) {}

            long finalSize = size;
            boolean binary = isBinary.trim().equals("1");

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (finalSize > 500 * 1024) {
                        showSnackbar("File too large (>500KB)"); return;
                    }
                    if (binary) {
                        new MaterialAlertDialogBuilder(getContext())
                            .setTitle("Binary File Detected")
                            .setMessage("This file contains binary data (alien language). Opening it might be slow. Proceed?")
                            .setPositiveButton("Open", (d, w) -> startReading(path, name, true))
                            .setNegativeButton("Cancel", null)
                            .show();
                    } else {
                        startReading(path, name, false);
                    }
                });
            }
        }).start();
    }

    private void startReading(String path, String name, boolean isBinary) {
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
                    if (isBinary && content != null) {
                        // Prevent EditText freeze by injecting newlines if missing
                        editorEditText.setText(content.replaceAll("(.{100})", "$1\n"));
                    } else {
                        editorEditText.setText(content != null ? content : "");
                    }
                    updateLineNumbers();
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
        int lineCount = editorEditText.getLineCount();
        StringBuilder sb = new TextView(getContext()).getContext().getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? new StringBuilder() : new StringBuilder();
        for (int i = 1; i <= lineCount; i++) { sb.append(i).append("\n"); }
        lineNumbers.setText(sb.toString());
    }

    private void updateEditorTextSize() {
        editorEditText.setTextSize(currentTextSize);
        lineNumbers.setTextSize(currentTextSize);
    }

    interface InputCallback { void onInput(String text); }

    static class FileData {
        String name, fullPath, size = "";
        boolean isDir, isBack;
        FileData(String name, String fullPath, boolean isDir, boolean isBack) {
            this.name = name; this.fullPath = fullPath; this.isDir = isDir; this.isBack = isBack;
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
                holder.btnMenu.setVisibility(View.GONE);
                holder.size.setVisibility(View.GONE);
            } else if (data.isDir) { 
                holder.icon.setImageResource(R.drawable.ic_folder); 
                holder.btnMenu.setVisibility(View.VISIBLE);
                holder.size.setVisibility(View.GONE);
            } else { 
                holder.icon.setImageResource(R.drawable.ic_logs); 
                holder.btnMenu.setVisibility(View.VISIBLE);
                holder.size.setVisibility(View.VISIBLE);
            }

            holder.itemView.setOnClickListener(v -> {
                if (data.isDir) { currentPath = data.fullPath; loadFiles(); }
                else { openEditor(data.fullPath, data.name); }
            });

            holder.btnMenu.setOnClickListener(v -> {
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
            });
        }
        @Override public int getItemCount() { return filteredFiles.size(); }
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView name, size; ImageView icon, btnMenu;
            ViewHolder(View v) { 
                super(v); 
                name = v.findViewById(R.id.itemName); 
                size = v.findViewById(R.id.itemSize);
                icon = v.findViewById(R.id.itemIcon); 
                btnMenu = v.findViewById(R.id.btnMenu); 
            }
        }
    }
}
