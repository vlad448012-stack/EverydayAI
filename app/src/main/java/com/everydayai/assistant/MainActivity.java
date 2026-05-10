package com.everydayai.assistant;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_REQ = 100;
    private static final int REQ_GALLERY = 102;

    private RecyclerView recycler;
    private MessageAdapter adapter;
    private EditText input;
    private ImageButton sendBtn, attachBtn, clearBtn, settingsBtn;
    private TextView modelText, speedText;
    private FrameLayout loading;

    private final List<ChatMessage> messages = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean busy = false;

    private SharedPreferences prefs;
    private String modelPath;
    private String mmprojPath;
    private String systemPrompt;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("everydayai", MODE_PRIVATE);
        modelPath = prefs.getString("model_path", "/storage/emulated/0/1_ВсеСкрипты/Локальное/ии/Qwen2.5-VL-3B-Instruct-q5_k_m.gguf");
        mmprojPath = prefs.getString("mmproj_path", "/storage/emulated/0/1_ВсеСкрипты/Локальное/ии/mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf");
        systemPrompt = prefs.getString("system_prompt", "You are a helpful AI assistant. Answer in Russian. Be concise.");

        initViews();
        checkPerms();
        addMsg("Hello. I am your AI assistant.", ChatMessage.AI, time());
    }

    private void initViews() {
        recycler = findViewById(R.id.recycler);
        input = findViewById(R.id.input);
        sendBtn = findViewById(R.id.send_btn);
        attachBtn = findViewById(R.id.attach_btn);
        clearBtn = findViewById(R.id.clear_btn);
        settingsBtn = findViewById(R.id.settings_btn);
        modelText = findViewById(R.id.model_text);
        speedText = findViewById(R.id.speed_text);
        loading = findViewById(R.id.loading);

        adapter = new MessageAdapter(messages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        recycler.setLayoutManager(lm);
        recycler.setAdapter(adapter);

        sendBtn.setOnClickListener(v -> sendMsg());
        input.setOnEditorActionListener((v, act, ev) -> {
            if (act == EditorInfo.IME_ACTION_SEND) { sendMsg(); return true; }
            return false;
        });
        attachBtn.setOnClickListener(v -> pickImage());
        clearBtn.setOnClickListener(v -> {
            messages.clear();
            adapter.notifyDataSetChanged();
            addMsg("Chat cleared.", ChatMessage.AI, time());
        });
        settingsBtn.setOnClickListener(v -> showSettings());

        modelText.setText("Qwen2.5-VL-3B Q5_K_M");
        speedText.setText("Ready");
    }

    private void showSettings() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Settings");
        
        final EditText modelInput = new EditText(this);
        modelInput.setText(modelPath);
        modelInput.setHint("Path to .gguf model");
        
        final EditText mmprojInput = new EditText(this);
        mmprojInput.setText(mmprojPath);
        mmprojInput.setHint("Path to mmproj file");
        
        final EditText promptInput = new EditText(this);
        promptInput.setText(systemPrompt);
        promptInput.setHint("System prompt");
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 0);
        layout.addView(modelInput);
        layout.addView(mmprojInput);
        layout.addView(promptInput);
        
        builder.setView(layout);
        builder.setPositiveButton("Save", (dialog, which) -> {
            modelPath = modelInput.getText().toString().trim();
            mmprojPath = mmprojInput.getText().toString().trim();
            systemPrompt = promptInput.getText().toString().trim();
            prefs.edit()
                .putString("model_path", modelPath)
                .putString("mmproj_path", mmprojPath)
                .putString("system_prompt", systemPrompt)
                .apply();
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void checkPerms() {
        String[] p;
        if (Build.VERSION.SDK_INT >= 33) p = new String[]{Manifest.permission.READ_MEDIA_IMAGES};
        else p = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        if (ContextCompat.checkSelfPermission(this, p[0]) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, p, PERM_REQ);
    }

    private void sendMsg() {
        String t = input.getText().toString().trim();
        if (t.isEmpty() || busy) return;
        addMsg(t, ChatMessage.USER, time());
        input.setText("");
        busy = true;
        loading.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            String resp = processText(t);
            handler.post(() -> {
                addMsg(resp, ChatMessage.AI, time());
                loading.setVisibility(View.GONE);
                busy = false;
            });
        });
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(i, REQ_GALLERY);
    }

    @Override
    protected void onActivityResult(int req, int res, @Nullable Intent data) {
        super.onActivityResult(req, res, data);
        if (res == RESULT_OK && data != null && data.getData() != null) {
            String path = data.getData().toString();
            addMsg("[Image]", ChatMessage.USER, time());
            busy = true;
            loading.setVisibility(View.VISIBLE);
            executor.execute(() -> {
                String desc = processImage(path);
                handler.post(() -> {
                    addMsg(desc, ChatMessage.AI, time());
                    loading.setVisibility(View.GONE);
                    busy = false;
                });
            });
        }
    }

    private String processText(String prompt) {
        try {
            String fullPrompt = systemPrompt + "\n\nUser: " + prompt + "\nAssistant:";
            ProcessBuilder pb = new ProcessBuilder(
                "/data/data/com.everydayai.assistant/files/llama-cli",
                "-m", modelPath,
                "-p", fullPrompt,
                "-n", "256",
                "--temp", "0.7",
                "--no-display-prompt"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream())
            );
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append("\n");
            p.waitFor();
            r.close();
            String out = sb.toString().trim();
            return out.isEmpty() ? "(no output)" : out;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String processImage(String imagePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "/data/data/com.everydayai.assistant/files/llama-cli",
                "-m", modelPath,
                "--mmproj", mmprojPath,
                "--image", imagePath,
                "-p", systemPrompt + "\nDescribe this image in detail.",
                "-n", "256",
                "--temp", "0.7",
                "--no-display-prompt"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream())
            );
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append("\n");
            p.waitFor();
            r.close();
            String out = sb.toString().trim();
            return out.isEmpty() ? "(no output)" : out;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private void addMsg(String text, int role, String t) {
        messages.add(new ChatMessage(text, role, t));
        adapter.notifyItemInserted(messages.size() - 1);
        recycler.smoothScrollToPosition(messages.size() - 1);
    }

    private String time() {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
    }
}
