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
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_REQ = 100;
    private static final int REQ_GALLERY = 101;
    private static final int REQ_CAMERA = 102;
    private static final int REQ_VOICE = 103;

    private RecyclerView recycler;
    private MessageAdapter adapter;
    private EditText input;
    private ImageButton sendBtn, attachBtn, cameraBtn, voiceBtn, clearBtn, settingsBtn;
    private TextView modelText, speedText, statusText;
    private FrameLayout loading;

    private final List<ChatMessage> messages = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean busy = false;

    private SharedPreferences prefs;
    private String modelPath, mmprojPath, systemPrompt, cliPath, icdPath;
    private boolean useVulkan;
    private File currentPhotoFile;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        // --- УЛЬТРА-ФИКС ПУТИ ---
        String baseNativeDir = getApplicationInfo().nativeLibraryDir;
        cliPath = baseNativeDir + "/libllama.so";
        
        // Если по стандартному пути не нашли, пробуем явно указать arm64-v8a
        if (!new File(cliPath).exists()) {
            cliPath = baseNativeDir.replace("/lib/arm64", "/lib/arm64-v8a") + "/libllama.so";
        }
        
        icdPath = getFilesDir().getAbsolutePath() + "/adreno.json";

        prefs = getSharedPreferences("everydayai", MODE_PRIVATE);
        modelPath = prefs.getString("model_path", "/storage/emulated/0/1_ВсеСкрипты/Локальное/ии/Qwen2.5-VL-3B-Instruct-q5_k_m.gguf");
        mmprojPath = prefs.getString("mmproj_path", "/storage/emulated/0/1_ВсеСкрипты/Локальное/ии/mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf");
        systemPrompt = prefs.getString("system_prompt", "Ты полезный AI-ассистент. Отвечай на русском языке.");
        useVulkan = prefs.getBoolean("use_vulkan", false);

        initViews();
        createVulkanConfig();
        addMsg("Здравствуйте. Я ваш AI-ассистент.", ChatMessage.AI, time());
        updateStatus();
    }

    private void createVulkanConfig() {
        try {
            File f = new File(icdPath);
            String json = "{\"file_format_version\": \"1.0.0\", \"ICD\": {\"library_path\": \"/vendor/lib64/hw/vulkan.adreno.so\", \"api_version\": \"1.3.268\"}}";
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(json.getBytes());
            fos.close();
        } catch (Exception e) {}
    }

    private void initViews() {
        recycler = findViewById(R.id.recycler);
        input = findViewById(R.id.input);
        sendBtn = findViewById(R.id.send_btn);
        attachBtn = findViewById(R.id.attach_btn);
        cameraBtn = findViewById(R.id.camera_btn);
        voiceBtn = findViewById(R.id.voice_btn);
        clearBtn = findViewById(R.id.clear_btn);
        settingsBtn = findViewById(R.id.settings_btn);
        modelText = findViewById(R.id.model_text);
        speedText = findViewById(R.id.speed_text);
        statusText = findViewById(R.id.status_text);
        loading = findViewById(R.id.loading);

        adapter = new MessageAdapter(messages, text -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("res", text));
            Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
        });
        
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        recycler.setLayoutManager(lm);
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
        modelText.setText(mFile.getName());
        
        File cFile = new File(cliPath);
        if (cFile.exists()) {
            statusText.setText(useVulkan ? "Vulkan активен" : "CPU активен");
            statusText.setTextColor(0xFF34D399);
        } else {
            statusText.setText("Ошибка: libllama.so не найден\nПуть: " + cliPath);
            statusText.setTextColor(0xFFEF4444);
        }
    }

    private void showSettings() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Настройки");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);
        
        final EditText mIn = new EditText(this); 
        mIn.setText(modelPath); 
        mIn.setHint("Путь к модели (.gguf)");
        layout.addView(mIn);
        
        final CheckBox vCh = new CheckBox(this); 
        vCh.setText("Использовать Vulkan (GPU)"); 
        vCh.setChecked(useVulkan); 
        layout.addView(vCh);
        
        builder.setView(layout);
        builder.setPositiveButton("Сохранить", (d, w) -> {
            modelPath = mIn.getText().toString().trim();
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
            cmd.add("-n"); cmd.add("256");
            cmd.add("--no-display-prompt");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (useVulkan) {
                Map<String, String> env = pb.environment();
                env.put("VK_ICD_FILENAMES", icdPath);
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append("\n");
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) { return "Ошибка исполнения: " + e.getMessage(); }
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
