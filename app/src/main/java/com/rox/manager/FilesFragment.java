package com.rox.manager;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FilesFragment extends Fragment {
    private List<FileData> fullList = new ArrayList<>();
    private List<FileData> filteredList = new ArrayList<>();
    private FileAdapter adapter;
    private TextView pathIndicator;
    private boolean isRoot = true;

    static class FileData {
        String name;
        boolean isFolder;
        boolean isBack;

        FileData(String name, boolean isFolder, boolean isBack) {
            this.name = name;
            this.isFolder = isFolder;
            this.isBack = isBack;
        }
    }

    class FileAdapter extends ArrayAdapter<FileData> {
        FileAdapter(Context context, List<FileData> objects) {
            super(context, 0, objects);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.list_item_file, parent, false);
            }
            FileData data = getItem(position);
            ImageView icon = convertView.findViewById(R.id.itemIcon);
            TextView name = convertView.findViewById(R.id.itemName);

            name.setText(data.name);
            if (data.isBack) icon.setImageResource(R.drawable.ic_back_modern);
            else if (data.isFolder) icon.setImageResource(R.drawable.ic_folder_modern);
            else icon.setImageResource(R.drawable.ic_file_modern);

            return convertView;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_files, container, false);
        ListView listView = view.findViewById(R.id.fileListView);
        EditText searchEdit = view.findViewById(R.id.searchEditText);
        pathIndicator = view.findViewById(R.id.pathIndicator);

        loadRootFiles();
        adapter = new FileAdapter(getContext(), filteredList);
        listView.setAdapter(adapter);

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

        listView.setOnItemClickListener((parent, v, position, id) -> {
            FileData item = filteredList.get(position);
            if (item.isBack) {
                loadRootFiles();
                searchEdit.setText("");
            } else if (item.isFolder) {
                enterFolder(item.name);
                searchEdit.setText("");
            } else {
                Toast.makeText(getContext(), "Opening: " + item.name, Toast.LENGTH_SHORT).show();
            }
        });

        return view;
    }

    private void loadRootFiles() {
        isRoot = true;
        fullList.clear();
        fullList.add(new FileData("Downloads", true, false));
        fullList.add(new FileData("Documents", true, false));
        fullList.add(new FileData("Pictures", true, false));
        fullList.add(new FileData("Movies", true, false));
        fullList.add(new FileData("config.json", false, false));
        fullList.add(new FileData("system.log", false, false));
        fullList.add(new FileData("user_data.db", false, false));
        pathIndicator.setText("Path: /storage/emulated/0");
        filter("");
    }

    private void enterFolder(String folderName) {
        isRoot = false;
        fullList.clear();
        fullList.add(new FileData("Back", false, true));
        fullList.add(new FileData("notes_in_" + folderName.toLowerCase() + ".txt", false, false));
        fullList.add(new FileData("photo_demo.jpg", false, false));
        pathIndicator.setText("Path: /storage/emulated/0/" + folderName);
        filter("");
    }

    private void filter(String query) {
        filteredList.clear();
        if (query.isEmpty()) {
            filteredList.addAll(fullList);
        } else {
            for (FileData item : fullList) {
                if (item.name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                    filteredList.add(item);
                }
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }
}
