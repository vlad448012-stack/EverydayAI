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
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        // Путь к нативной библиотеке — здесь Android разрешает выполнение
        cliPath = getApplicationInfo().nativeLibraryDir + "/libllama.so";

        prefs = getSharedPreferences("everydayai", MODE_PRIVATE);
        modelPath = prefs.getString("model_path", "/storage/emulated/0/1_ВсеСкрипты/Локальное/ии/Qwen2.5-VL-3B-Instruct-q5_k_m.gguf");
        mmprojPath = prefs.getString("mmproj_path", "/storage/emulated/0/1_ВсеСкрипты/Локальное/ии/mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf");
        systemPrompt = prefs.getString("system_prompt", "Ты полезный AI-ассистент. Отвечай на русском языке. Будь кратким и по делу.");
        useVulkan = prefs.getBoolean("use_vulkan", false);

        initViews();
        checkPerms();
        addMsg("Здравствуйте. Я ваш AI-ассистент.", ChatMessage.AI, time());
        updateStatus();
        setupAI();
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
        input.setOnEditorActionListener((v, act, ev) -> {
            if (act == EditorInfo.IME_ACTION_SEND) { sendMsg(); return true; }
            return false;
        });
        attachBtn.setOnClickListener(v -> pickImage());
        cameraBtn.setOnClickListener(v -> openCamera());
        voiceBtn.setOnClickListener(v -> startVoiceInput());
        clearBtn.setOnClickListener(v -> {
            messages.clear();
            adapter.notifyDataSetChanged();
            addMsg("Чат очищен.", ChatMessage.AI, time());
        });
        settingsBtn.setOnClickListener(v -> showSettings());

        updateStatus();
        setupAI();
    }

    private void updateStatus() {
        File modelFile = new File(modelPath);
        File mmprojFile = new File(mmprojPath);
        File cliFile = new File(cliPath);
        
        String modelName = modelFile.getName();
        if (modelName.length() > 25) modelName = modelName.substring(0, 22) + "...";
        modelText.setText(modelName);
        
        StringBuilder sb = new StringBuilder();
        if (!cliFile.exists()) sb.append("libllama.so не найден. ");
        if (!modelFile.exists()) sb.append("Модель не найдена. ");
        else if (!mmprojFile.exists()) sb.append("mmproj не найден. ");
        
        if (sb.length() == 0) {
            statusText.setText(useVulkan ? "Vulkan активен" : "CPU активен");
            statusText.setTextColor(0xFF34D399);
            speedText.setText("Готов");
        } else {
            statusText.setText(sb.toString().trim());
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
        
        TextView modelLabel = new TextView(this);
        modelLabel.setText("Путь к .gguf модели:");
        modelLabel.setTextSize(13);
        modelLabel.setTextColor(0xFF9090A6);
        layout.addView(modelLabel);
        
        final EditText modelInput = new EditText(this);
        modelInput.setText(modelPath);
        modelInput.setTextSize(13);
        modelInput.setSingleLine(true);
        modelInput.setBackgroundColor(0xFF1A1A23);
        modelInput.setTextColor(0xFFE4E4ED);
        modelInput.setPadding(20, 14, 20, 14);
        layout.addView(modelInput);
        
        addSpace(layout);
        
        TextView mmprojLabel = new TextView(this);
        mmprojLabel.setText("Путь к mmproj файлу:");
        mmprojLabel.setTextSize(13);
        mmprojLabel.setTextColor(0xFF9090A6);
        layout.addView(mmprojLabel);
        
        final EditText mmprojInput = new EditText(this);
        mmprojInput.setText(mmprojPath);
        mmprojInput.setTextSize(13);
        mmprojInput.setSingleLine(true);
        mmprojInput.setBackgroundColor(0xFF1A1A23);
        mmprojInput.setTextColor(0xFFE4E4ED);
        mmprojInput.setPadding(20, 14, 20, 14);
        layout.addView(mmprojInput);
        
        addSpace(layout);
        
        TextView promptLabel = new TextView(this);
        promptLabel.setText("Системный промпт:");
        promptLabel.setTextSize(13);
        promptLabel.setTextColor(0xFF9090A6);
        layout.addView(promptLabel);
        
        final EditText promptInput = new EditText(this);
        promptInput.setText(systemPrompt);
        promptInput.setTextSize(13);
        promptInput.setMinLines(3);
        promptInput.setBackgroundColor(0xFF1A1A23);
        promptInput.setTextColor(0xFFE4E4ED);
        promptInput.setPadding(20, 14, 20, 14);
        promptInput.setGravity(Gravity.TOP);
        layout.addView(promptInput);
        
        addSpace(layout);
        
        final CheckBox vulkanCheck = new CheckBox(this);
        vulkanCheck.setText("Использовать Vulkan (GPU)");
        vulkanCheck.setTextColor(0xFFE4E4ED);
        vulkanCheck.setTextSize(14);
        vulkanCheck.setChecked(useVulkan);
        layout.addView(vulkanCheck);
        
        scroll.addView(layout);
        builder.setView(scroll);
        
        builder.setPositiveButton("Сохранить", (dialog, which) -> {
            modelPath = modelInput.getText().toString().trim();
            mmprojPath = mmprojInput.getText().toString().trim();
            systemPrompt = promptInput.getText().toString().trim();
            useVulkan = vulkanCheck.isChecked();
            prefs.edit()
                .putString("model_path", modelPath)
                .putString("mmproj_path", mmprojPath)
                .putString("system_prompt", systemPrompt)
                .putBoolean("use_vulkan", useVulkan)
                .apply();
            updateStatus();
        setupAI();
            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    private void addSpace(LinearLayout layout) {
        View space = new View(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(1, 16));
        layout.addView(space);
    }

    private void checkPerms() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.READ_MEDIA_IMAGES);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.CAMERA);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.RECORD_AUDIO);
        
        if (!perms.isEmpty())
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), PERM_REQ);
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
        if (i.resolveActivity(getPackageManager()) != null) {
            try {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                currentPhotoFile = File.createTempFile("IMG_" + stamp, ".jpg", getExternalFilesDir(null));
                Uri uri = FileProvider.getUriForFile(this, "com.everydayai.assistant.fileprovider", currentPhotoFile);
                i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
                startActivityForResult(i, REQ_CAMERA);
            } catch (Exception e) {
                Toast.makeText(this, "Ошибка камеры", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startVoiceInput() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите...");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (Exception e) {
            Toast.makeText(this, "Распознавание речи недоступно", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, @Nullable Intent data) {
        super.onActivityResult(req, res, data);
        if (res == RESULT_OK) {
            if (req == REQ_VOICE && data != null) {
                ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (results != null && !results.isEmpty()) {
                    input.setText(results.get(0));
                    input.setSelection(input.getText().length());
                }
            } else if (req == REQ_CAMERA && currentPhotoFile != null && currentPhotoFile.exists()) {
                processImage(Uri.fromFile(currentPhotoFile));
            } else if (req == REQ_GALLERY && data != null && data.getData() != null) {
                processImage(data.getData());
            }
        }
    }

    private void processImage(Uri imageUri) {
        addMsg("[Image]", ChatMessage.USER, time());
        busy = true;
        loading.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            String desc = runLlama(systemPrompt + "\nDescribe this image in detail.", imageUri.toString());
            handler.post(() -> {
                addMsg(desc, ChatMessage.AI, time());
                loading.setVisibility(View.GONE);
                busy = false;
            });
        });
    }

    
    private void setupAI() {
        File bin = new File(getFilesDir(), "llama-cli");
        File icd = new File(getFilesDir(), "adreno.json");

        new Thread(() -> {
            try {
                // 1. Создаем ICD JSON (Шаг 4 из инструкции)
                if (!icd.exists()) {
                    String json = "{\"file_format_version\": \"1.0.0\", \"ICD\": {\"library_path\": \"/vendor/lib64/hw/vulkan.adreno.so\", \"api_version\": \"1.3.268\"}}";
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(icd);
                    fos.write(json.getBytes());
                    fos.close();
                }

                // 2. Скачиваем бинарник (мы берем версию с поддержкой Vulkan)
                if (!bin.exists()) {
                    java.net.URL url = new java.net.URL("https://github.com/ggml-org/llama.cpp/releases/download/b3106/llama-b3106-bin-android-arm64.tar.gz"); 
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    java.io.InputStream in = new java.util.zip.GZIPInputStream(conn.getInputStream());
                    java.io.FileOutputStream out = new java.io.FileOutputStream(bin);
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    out.close(); in.close();
                }
                
                bin.setExecutable(true, false);
                runOnUiThread(() -> Toast.makeText(this, "Vulkan Engine Ready", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> android.util.Log.e("AI_ERROR", "Setup failed", e));
            }
        }).start();
    }

    private String runLlama(String prompt, String imagePath) {
        try {
            File bin = new File(getFilesDir(), "llama-cli");
            File icd = new File(getFilesDir(), "adreno.json");
            
            List<String> cmd = new ArrayList<>();
            cmd.add(bin.getAbsolutePath());
            cmd.add("-m"); cmd.add(modelPath);
            
            if (useVulkan) {
                cmd.add("-ngl"); cmd.add("99"); // Пробрасываем слои на GPU
            }

            cmd.add("-p"); cmd.add(prompt);
            cmd.add("-n"); cmd.add("128");
            cmd.add("--no-display-prompt");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            
            // ПЕРЕМЕННЫЕ ОКРУЖЕНИЯ (Шаг 5 из инструкции)
            Map<String, String> env = pb.environment();
            env.put("VK_ICD_FILENAMES", icd.getAbsolutePath());
            // Добавляем пути к системным библиотекам и драйверам
            env.put("LD_LIBRARY_PATH", "/vendor/lib64/hw:/system/lib64:" + getApplicationInfo().nativeLibraryDir);

            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder(); String l;
            while ((l = r.readLine()) != null) sb.append(l).append("
");
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) { return "Ошибка: " + e.getMessage(); }
    }
        if (!new File(cliPath).exists()) {
            return "Ошибка: libllama.so не найден\nПуть: " + cliPath;
        }

        try {
            String nativeDir = getApplicationInfo().nativeLibraryDir;
            List<String> cmd = new ArrayList<>();
            cmd.add(nativeDir + "/libllama.so");
            cmd.add(modelPath);

            if (imagePath != null && !imagePath.isEmpty() && new File(mmprojPath).exists()) {
                cmd.add("--mmproj");
                cmd.add(mmprojPath);
                cmd.add("--image");
                cmd.add(imagePath);
            }

            if (useVulkan) {
                cmd.add("-ngl");
                cmd.add("99");
            }

            cmd.add("-p");
            cmd.add(prompt);
            cmd.add("-n");
            cmd.add("256");
            cmd.add("--temp");
            cmd.add("0.7");
            cmd.add("--no-display-prompt");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append("\n");
            p.waitFor();
            r.close();

            String out = sb.toString().trim();
            if (out.isEmpty()) return "Нет ответа (код: " + p.exitValue() + ")";
            return out;
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return "Ошибка запуска:\n" + sw.toString();
        }
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("response", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
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
