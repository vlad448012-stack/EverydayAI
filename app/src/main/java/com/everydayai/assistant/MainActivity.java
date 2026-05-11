package com.everydayai.assistant;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_REQ = 100;
    private static final int REQ_VOICE = 103;

    private RecyclerView recycler;
    private MessageAdapter adapter;
    private EditText input;
    private ImageButton sendBtn, settingsBtn, clearBtn;
    private TextView modelText, statusText;
    private FrameLayout loading;

    private final List<ChatMessage> messages = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean busy = false;

    private SharedPreferences prefs;
    private String modelPath, mmprojPath, systemPrompt, cliPath, icdPath;
    private boolean useVulkan;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        // ДИНАМИЧЕСКИЙ ПОИСК ПУТИ (Фикс для arm64-v8a)
        cliPath = getApplicationInfo().nativeLibraryDir + "/libllama.so";
        icdPath = getFilesDir().getAbsolutePath() + "/adreno.json";

        prefs = getSharedPreferences("everydayai", MODE_PRIVATE);
        modelPath = prefs.getString("model_path", "/storage/emulated/0/1_ВсеСкрипты/Локальное/ии/Qwen2.5-VL-3B-Instruct-q5_k_m.gguf");
        mmprojPath = prefs.getString("mmproj_path", "/storage/emulated/0/1_ВсеСкрипты/Локальное/ии/mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf");
        systemPrompt = prefs.getString("system_prompt", "Ты полезный AI-ассистент.");
        useVulkan = prefs.getBoolean("use_vulkan", false);

        initViews();
        createVulkanConfig();
        updateStatus();
    }

    private void createVulkanConfig() {
        try {
            File f = new File(icdPath);
            if (!f.exists()) {
                String json = "{\"file_format_version\": \"1.0.0\", \"ICD\": {\"library_path\": \"/vendor/lib64/hw/vulkan.adreno.so\", \"api_version\": \"1.3.268\"}}";
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(json.getBytes());
                fos.close();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void initViews() {
        recycler = findViewById(R.id.recycler);
        input = findViewById(R.id.input);
        sendBtn = findViewById(R.id.send_btn);
        clearBtn = findViewById(R.id.clear_btn);
        settingsBtn = findViewById(R.id.settings_btn);
        modelText = findViewById(R.id.model_text);
        statusText = findViewById(R.id.status_text);
        loading = findViewById(R.id.loading);

        adapter = new MessageAdapter(messages, text -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("res", text));
        });
        
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        sendBtn.setOnClickListener(v -> sendMsg());
        clearBtn.setOnClickListener(v -> {
            messages.clear();
            adapter.notifyDataSetChanged();
        });
        settingsBtn.setOnClickListener(v -> showSettings());
    }

    private void updateStatus() {
        File mFile = new File(modelPath);
        File cFile = new File(cliPath);
        modelText.setText(mFile.getName());
        if (cFile.exists() && mFile.exists()) {
            statusText.setText(useVulkan ? "Vulkan ACTIVE" : "CPU ACTIVE");
            statusText.setTextColor(0xFF34D399);
        } else {
            statusText.setText("MISSING FILES");
            statusText.setTextColor(0xFFEF4444);
        }
    }

    private void showSettings() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Settings");
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(50, 20, 50, 20);
        
        final EditText mIn = new EditText(this); mIn.setText(modelPath); l.addView(mIn);
        final CheckBox vCh = new CheckBox(this); vCh.setText("Use Vulkan"); vCh.setChecked(useVulkan); l.addView(vCh);
        
        builder.setView(l);
        builder.setPositiveButton("Save", (d, w) -> {
            modelPath = mIn.getText().toString();
            useVulkan = vCh.isChecked();
            prefs.edit().putString("model_path", modelPath).putBoolean("use_vulkan", useVulkan).apply();
            updateStatus();
        });
        builder.show();
    }

    private void sendMsg() {
        String t = input.getText().toString().trim();
        if (t.isEmpty() || busy) return;
        addMsg(t, ChatMessage.USER, time());
        input.setText("");
        busy = true;
        loading.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            String resp = runLlama(t);
            handler.post(() -> {
                addMsg(resp, ChatMessage.AI, time());
                loading.setVisibility(View.GONE);
                busy = false;
            });
        });
    }

    private String runLlama(String prompt) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(cliPath);
            cmd.add("-m"); cmd.add(modelPath);
            if (useVulkan) { cmd.add("-ngl"); cmd.add("99"); }
            cmd.add("-p"); cmd.add(prompt);
            cmd.add("-n"); cmd.add("128");
            cmd.add("--no-display-prompt");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (useVulkan) {
                Map<String, String> env = pb.environment();
                env.put("VK_ICD_FILENAMES", icdPath);
                env.put("LD_LIBRARY_PATH", "/vendor/lib64/hw:/system/lib64");
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append("\n");
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) { return "Error: " + e.getMessage(); }
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
