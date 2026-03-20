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
import android.content.res.Configuration;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula;
import io.github.rosemoe.sora.widget.schemes.SchemeNotepadXX;
import androidx.activity.OnBackPressedCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Date;

public class FilesFragment extends Fragment {
    private RecyclerView recyclerView;
    private FileAdapter adapter;
    private List<FileData> allFiles = new ArrayList<>();
    private List<FileData> filteredFiles = new ArrayList<>();
    private String currentPath = "/data/adb/box";
    private SwipeRefreshLayout swipeRefresh;
    private EditText searchEditText;

    // Sora Editor Components
    private View fileListLayout, editorContainer, btnBackParent;
    private CodeEditor codeEditor;
    private TextView editorFileName;
    private String editingFilePath = "";
    private OnBackPressedCallback backPressedCallback;
    private FloatingActionButton btnAddAction;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_files, container, false);

        recyclerView = view.findViewById(R.id.fileRecyclerView);
        swipeRefresh = view.findViewById(R.id.swipeRefreshFiles);
        searchEditText = view.findViewById(R.id.searchEditText);
        fileListLayout = view.findViewById(R.id.fileListLayout);
        btnBackParent = view.findViewById(R.id.btnBackParent);
        
        // Editor UI
        editorContainer = view.findViewById(R.id.editorContainer);
        codeEditor = view.findViewById(R.id.codeEditor);
        editorFileName = view.findViewById(R.id.editorFileName);
        MaterialButton btnBack = view.findViewById(R.id.btnEditorBack);
        MaterialButton btnSave = view.findViewById(R.id.btnEditorSave);
        
        btnAddAction = view.findViewById(R.id.btnAddAction);

        backPressedCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                closeEditor();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backPressedCallback);

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
        
        btnBackParent.setOnClickListener(v -> {
            currentPath = getParentPath(currentPath);
            loadFiles();
        });

        btnAddAction.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(getContext())
                .setTitle("Create New")
                .setItems(new String[]{"New File", "New Folder"}, (dialog, which) -> {
                    if (which == 0) {
                        showInputDialog("New File", "Enter file name", "", name -> executeCommand("touch \"" + currentPath + "/" + name + "\"", "File created"));
                    } else {
                        showInputDialog("New Folder", "Enter folder name", "", name -> executeCommand("mkdir -p \"" + currentPath + "/" + name + "\"", "Folder created"));
                    }
                })
                .show();
        });

        // Initialize Sora Editor Settings
        codeEditor.setWordwrap(true);
        codeEditor.setLineNumberEnabled(true);
        
        applyThemeToEditor();
        loadFiles();
        return view;
    }

    private void applyThemeToEditor() {
        int currentNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (currentNightMode == Configuration.UI_MODE_NIGHT_YES) {
            try { codeEditor.setColorScheme(new SchemeDarcula()); } catch (Exception ignored) {}
        } else {
            try { codeEditor.setColorScheme(new SchemeNotepadXX()); } catch (Exception ignored) {}
        }
    }

    private void loadFiles() {
        swipeRefresh.setRefreshing(true);
        new Thread(() -> {
            String cmd = "for f in \"" + currentPath + "\"/* \"" + currentPath + "\"/.*; do " +
                         "  name=$(basename \"$f\"); " +
                         "  if [ \"$name\" != \".\" ] && [ \"$name\" != \"..\" ] && [ -e \"$f\" ]; then " +
                         "    is_dir=0; [ -d \"$f\" ] && is_dir=1; " +
                         "    size=$(stat -c '%s' \"$f\"); " +
                         "    mtime=$(stat -c '%Y' \"$f\"); " +
                         "    echo \"$is_dir|$size|$mtime|$name\"; " +
                         "  fi; " +
                         "done";
            String result = ShellHelper.runRootCommand(cmd);
            
            List<FileData> list = new ArrayList<>();
            // FIXED BACK BUTTON logic handles visibility now, no need for list item

            if (result != null && !result.isEmpty()) {
                String[] lines = result.split("\n");
                for (String line : lines) {
                    if (line.trim().isEmpty() || !line.contains("|")) continue;
                    String[] parts = line.split("\\|", 4);
                    if (parts.length == 4) {
                        boolean isDir = parts[0].equals("1");
                        long size = 0;
                        long mtime = 0;
                        try {
                            size = Long.parseLong(parts[1]);
                            mtime = Long.parseLong(parts[2]);
                        } catch (NumberFormatException ignored) {}
                        String name = parts[3];
                        String fullPath = currentPath + "/" + name;
                        list.add(new FileData(name, fullPath, isDir, false, size, mtime));
                    }
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
                    // Update fixed back button visibility
                    boolean isRoot = currentPath.equals("/data/adb/box") || currentPath.equals("/");
                    btnBackParent.setVisibility(isRoot ? View.GONE : View.VISIBLE);

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
        if (btnAddAction != null) btnAddAction.setVisibility(View.GONE);
        if (backPressedCallback != null) backPressedCallback.setEnabled(true);

        new Thread(() -> {
            String content = ShellHelper.readRootFileDirect(path);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (content != null) {
                        codeEditor.setText(content);
                        codeEditor.requestFocus();
                    } else {
                        codeEditor.setText("");
                    }
                });
            }
        }).start();
    }

    private void closeEditor() {
        editorContainer.setVisibility(View.GONE);
        fileListLayout.setVisibility(View.VISIBLE);
        if (btnAddAction != null) btnAddAction.setVisibility(View.VISIBLE);
        editingFilePath = "";
        if (backPressedCallback != null) backPressedCallback.setEnabled(false);
    }

    private void saveFile() {
        String content = codeEditor.getText().toString();
        new Thread(() -> {
            boolean success = ShellHelper.writeRootFileDirect(editingFilePath, content);
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

    private void showInputDialog(String title, String hint, String initialText, InputCallback callback) {
        EditText input = new EditText(getContext());
        input.setHint(hint);
        if (initialText != null && !initialText.isEmpty()) {
            input.setText(initialText);
            input.setSelection(initialText.length());
        }
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
        String name, fullPath, displayMeta = "";
        boolean isDir, isBack;
        long size, mtime;
        
        FileData(String name, String fullPath, boolean isDir, boolean isBack, long size, long mtime) {
            this.name = name; this.fullPath = fullPath; this.isDir = isDir; this.isBack = isBack;
            this.size = size; this.mtime = mtime;
            
            if (!isBack) {
                SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yy HH.mm", Locale.getDefault());
                String dateStr = sdf.format(new Date(mtime * 1000L));
                if (isDir) {
                    this.displayMeta = dateStr;
                } else {
                    this.displayMeta = dateStr + "   " + formatSize(size);
                }
            }
        }
        
        private String formatSize(long v) {
            if (v < 1024) return v + " B";
            int z = (63 - Long.numberOfLeadingZeros(v)) / 10;
            return String.format(Locale.getDefault(), "%.2f%s", (double)v / (1L << (z*10)), " KMGTPE".charAt(z));
        }
    }

    class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {
        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_file, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            FileData data = filteredFiles.get(position);
            holder.name.setText(data.name);
            holder.size.setText(data.displayMeta);
            
            if (data.isBack) { 
                holder.icon.setImageResource(R.drawable.ic_back_arrow); 
                holder.size.setVisibility(View.GONE);
            } else if (data.isDir) { 
                holder.icon.setImageResource(R.drawable.ic_folder); 
                holder.size.setVisibility(View.VISIBLE);
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
                                    if (data.fullPath == null || !data.fullPath.startsWith("/data/adb/box")) {
                                        showSnackbar("Blocked: Unsafe path."); return;
                                    }
                                    executeCommand("rm -rf \"" + data.fullPath + "\"", "Deleted");
                                })
                                .setNegativeButton("Cancel", null).show();
                        } else { // Rename
                            showInputDialog("Rename", "Enter new name", data.name, newName -> executeCommand("mv \"" + data.fullPath + "\" \"" + currentPath + "/" + newName + "\"", "Renamed"));
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
