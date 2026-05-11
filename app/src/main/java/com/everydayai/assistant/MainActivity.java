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
    private String modelPath;
    private String mmprojPath;
    private String systemPrompt;
    private boolean useVulkan;
    private File currentPhotoFile;
    private String cliPath;
    private String icdPath;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        cliPath = getApplicationInfo().nativeLibraryDir + "/libllama.so";
        icdPath = getFilesDir().getAbsolutePath() + "/adreno.json";

        prefs = getSharedPreferences("everydayai", MODE_PRIVATE);
        modelPath = prefs.getString("model_path", "/storage/emulated/0/1_ВсеСкрипты/Локальное/ии/Qwen2.5-VL-3B-Instruct-q5_k_m.gguf");
        mmprojPath = prefs.getString("mmproj_path", "/storage/emulated/0/1_ВсеСкрипты/Локальное/ии/mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf");
        systemPrompt = prefs.getString("system_prompt", "Ты полезный AI-ассистент. Отвечай на русском языке. Будь кратким и по делу.");
        useVulkan = prefs.getBoolean("use_vulkan", false);

        initViews();
        checkPerms();
        createVulkanConfig();
        addMsg("Здравствуйте. Я ваш AI-ассистент.", ChatMessage.AI, time());
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
        attachBtn = findViewById(R.id.attach_btn);
        cameraBtn = findViewById(R.id.camera_btn);
        voiceBtn = findViewById(R.id.voice_btn);
        clearBtn = findViewById(R.id.clear_btn);
        settingsBtn = findViewById(R.id.settings_btn);
        modelText = findViewById(R.id.model_text);
        speedText = findViewById(R.id.speed_text);
        statusText = findViewById(R.id.status_text);
        loading = findViewById(R.id.loading);

        adapter = new MessageAdapter(messages, this::copyToClipboard);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        recycler.setLayoutManager(lm);
        recycler.setAdapter(adapter);

        sendBtn.setOnClickListener(v -> sendMsg());
        attachBtn.setOnClickListener(v -> pickImage());
        cameraBtn.setOnClickListener(v -> openCamera());
        voiceBtn.setOnClickListener(v -> startVoiceInput());
        clearBtn.setOnClickListener(v -> {
            messages.clear();
            adapter.notifyDataSetChanged();
            addMsg("Чат очищен.", ChatMessage.AI, time());
        });
        settingsBtn.setOnClickListener(v -> showSettings());
    }

    private void updateStatus() {
        File modelFile = new File(modelPath);
        File cliFile = new File(cliPath);
        String modelName = modelFile.getName();
        if (modelName.length() > 25) modelName = modelName.substring(0, 22) + "...";
        modelText.setText(modelName);
        
        if (cliFile.exists() && modelFile.exists()) {
            statusText.setText(useVulkan ? "Vulkan активен" : "CPU активен");
            statusText.setTextColor(0xFF34D399);
            speedText.setText("Готов");
        } else {
            statusText.setText("Ошибка: файлы не найдены");
            statusText.setTextColor(0xFFEF4444);
            speedText.setText("Ошибка");
        }
    }

    private void showSettings() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Настройки");
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30);
        
        final EditText modelInput = new EditText(this);
        modelInput.setText(modelPath);
        layout.addView(modelInput);
        
        final CheckBox vulkanCheck = new CheckBox(this);
        vulkanCheck.setText("Использовать Vulkan (GPU)");
        vulkanCheck.setChecked(useVulkan);
        layout.addView(vulkanCheck);
        
        scroll.addView(layout);
        builder.setView(scroll);
        builder.setPositiveButton("Сохранить", (dialog, which) -> {
            modelPath = modelInput.getText().toString().trim();
            useVulkan = vulkanCheck.isChecked();
            prefs.edit().putString("model_path", modelPath).putBoolean("use_vulkan", useVulkan).apply();
            updateStatus();
        });
        builder.show();
    }

    private void checkPerms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERM_REQ);
    }

    private void sendMsg() {
        String t = input.getText().toString().trim();
        if (t.isEmpty() || busy) return;
        addMsg(t, ChatMessage.USER, time());
        input.setText("");
        busy = true;
        loading.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            String resp = runLlama(t, null);
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

    private void openCamera() {
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        startActivityForResult(i, REQ_CAMERA);
    }

    private void startVoiceInput() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        try { startActivityForResult(i, REQ_VOICE); } catch (Exception e) {}
    }

    @Override
    protected void onActivityResult(int req, int res, @Nullable Intent data) {
        super.onActivityResult(req, res, data);
        if (res == RESULT_OK && req == REQ_VOICE && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null) input.setText(results.get(0));
        }
    }

    private String runLlama(String prompt, String imagePath) {
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
        } catch (Exception e) { return "Ошибка: " + e.getMessage(); }
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("res", text));
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
