package com.rox.manager;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import com.google.android.material.snackbar.Snackbar;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import java.io.File;

public class FileEditorActivity extends AppCompatActivity {
    private String filePath;
    private EditText editText;
    private TextView titleView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_editor);

        filePath = getIntent().getStringExtra("file_path");
        if (filePath == null) {
            finish();
            return;
        }

        editText = findViewById(R.id.fileEditText);
        titleView = findViewById(R.id.fileNameTitle);
        MaterialButton btnBack = findViewById(R.id.btnBack);
        MaterialButton btnSave = findViewById(R.id.btnSave);

        titleView.setText(new File(filePath).getName());

        btnBack.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> saveFile());

        loadFile();
    }

    private void loadFile() {
        new Thread(() -> {
            String content = ShellHelper.runRootCommand("cat " + filePath);
            runOnUiThread(() -> {
                if (content != null && !content.startsWith("Error:")) {
                    editText.setText(content);
                } else {
                    Snackbar.make(findViewById(android.R.id.content), "Failed to read file", Snackbar.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void saveFile() {
        String content = editText.getText().toString();
        new Thread(() -> {
            // Write content to temporary file then move to destination via su
            // Note: For large files this could be complex, but for config it's usually fine
            // We use printf to handle special characters better
            String safeContent = content.replace("'", "'\\''");
            String cmd = "printf '" + safeContent + "' > " + filePath;
            String res = ShellHelper.runRootCommand(cmd);
            
            runOnUiThread(() -> {
                if (res != null && res.isEmpty()) {
                    Snackbar.make(findViewById(android.R.id.content), "File saved successfully", Snackbar.LENGTH_SHORT).show();
                } else if (res != null && res.startsWith("Error:")) {
                    Snackbar.make(findViewById(android.R.id.content), res, Snackbar.LENGTH_SHORT).show();
                } else {
                    Snackbar.make(findViewById(android.R.id.content), "File saved", Snackbar.LENGTH_SHORT).show();
                }
            });
        }).start();
    }
}
