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
import android.widget.Button;
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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("everydayai", MODE_PRIVATE);
        modelPath = prefs.getString("model_path", "/storage/emulated/0/1_ВсеСкрипты/Локальное/ии/Qwen2.5-VL-3B-Instruct-q5_k_m.gguf");
        mmprojPath = prefs.getString("mmproj_path", "/storage/emulated/0/1_ВсеСкрипты/Локальное/ии/mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf");
        systemPrompt = prefs.getString("system_prompt", "Ты — полезный AI-ассистент. Отвечай на русском языке. Будь кратким и по делу.");
        useVulkan = prefs.getBoolean("use_vulkan", false);

        initViews();
        ensureCliBinary();
        checkPerms();
        addMsg("\u0417\u0434\u0440\u0430\u0432\u0441\u0442\u0432\u0443\u0439\u0442\u0435. \u042f \u0432\u0430\u0448 AI-\u0430\u0441\u0441\u0438\u0441\u0442\u0435\u043d\u0442.", ChatMessage.AI, time());
        updateStatus();
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
            addMsg("\u0427\u0430\u0442 \u043e\u0447\u0438\u0449\u0435\u043d.", ChatMessage.AI, time());
        });
        settingsBtn.setOnClickListener(v -> showSettings());

        updateStatus();
    }

    private void updateStatus() {
        File modelFile = new File(modelPath);
        File mmprojFile = new File(mmprojPath);
        
        String modelName = modelFile.getName();
        if (modelName.length() > 25) modelName = modelName.substring(0, 22) + "...";
        modelText.setText(modelName);
        
        String status;
        if (!modelFile.exists()) {
            status = "\u041c\u043e\u0434\u0435\u043b\u044c \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d\u0430";
            speedText.setText("\u041e\u0448\u0438\u0431\u043a\u0430");
        } else if (!mmprojFile.exists()) {
            status = "mmproj \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d";
            speedText.setText("\u041e\u0448\u0438\u0431\u043a\u0430");
        } else {
            status = useVulkan ? "Vulkan \u0430\u043a\u0442\u0438\u0432\u0435\u043d" : "CPU \u0430\u043a\u0442\u0438\u0432\u0435\u043d";
            speedText.setText("\u0413\u043e\u0442\u043e\u0432");
        }
        statusText.setText(status);
    }

    
    private void ensureCliBinary() {
        File cliFile = new File(getFilesDir(), "llama-cli");
        if (!cliFile.exists()) {
            try {
                File src = new File("/data/data/com.termux/files/home/llama-cli");
                if (src.exists()) {
                    InputStream in = new FileInputStream(src);
                    OutputStream out = new FileOutputStream(cliFile);
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                    in.close();
                    out.close();
                    cliFile.setExecutable(true);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "llama-cli скопирован", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception ignored) {}
        }
    }

    private void showSettings() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438");

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30);
        
        // Model path
        TextView modelLabel = new TextView(this);
        modelLabel.setText("\u041f\u0443\u0442\u044c \u043a .gguf \u043c\u043e\u0434\u0435\u043b\u0438:");
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
        
        View space1 = new View(this);
        space1.setLayoutParams(new LinearLayout.LayoutParams(1, 16));
        layout.addView(space1);
        
        // MMPROJ path
        TextView mmprojLabel = new TextView(this);
        mmprojLabel.setText("\u041f\u0443\u0442\u044c \u043a mmproj \u0444\u0430\u0439\u043b\u0443:");
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
        
        View space2 = new View(this);
        space2.setLayoutParams(new LinearLayout.LayoutParams(1, 16));
        layout.addView(space2);
        
        // System prompt
        TextView promptLabel = new TextView(this);
        promptLabel.setText("\u0421\u0438\u0441\u0442\u0435\u043c\u043d\u044b\u0439 \u043f\u0440\u043e\u043c\u043f\u0442:");
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
        
        View space3 = new View(this);
        space3.setLayoutParams(new LinearLayout.LayoutParams(1, 16));
        layout.addView(space3);
        
        // Vulkan checkbox
        final CheckBox vulkanCheck = new CheckBox(this);
        vulkanCheck.setText("\u0418\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u044c Vulkan (GPU)");
        vulkanCheck.setTextColor(0xFFE4E4ED);
        vulkanCheck.setTextSize(14);
        vulkanCheck.setChecked(useVulkan);
        layout.addView(vulkanCheck);
        
        View space4 = new View(this);
        space4.setLayoutParams(new LinearLayout.LayoutParams(1, 20));
        layout.addView(space4);
        
        // Status info
        File modelFile = new File(modelPath);
        TextView statusInfo = new TextView(this);
        statusInfo.setText("\u0421\u0442\u0430\u0442\u0443\u0441: " + (modelFile.exists() ? "\u041c\u043e\u0434\u0435\u043b\u044c \u043d\u0430\u0439\u0434\u0435\u043d\u0430" : "\u041c\u043e\u0434\u0435\u043b\u044c \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d\u0430"));
        statusInfo.setTextColor(modelFile.exists() ? 0xFF34D399 : 0xFFEF4444);
        statusInfo.setTextSize(12);
        layout.addView(statusInfo);
        
        scroll.addView(layout);
        builder.setView(scroll);
        
        builder.setPositiveButton("\u0421\u043e\u0445\u0440\u0430\u043d\u0438\u0442\u044c", (dialog, which) -> {
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
            Toast.makeText(this, "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438 \u0441\u043e\u0445\u0440\u0430\u043d\u0435\u043d\u044b", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("\u041e\u0442\u043c\u0435\u043d\u0430", null);
        builder.show();
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
                Toast.makeText(this, "\u041e\u0448\u0438\u0431\u043a\u0430 \u043a\u0430\u043c\u0435\u0440\u044b", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startVoiceInput() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "\u0413\u043e\u0432\u043e\u0440\u0438\u0442\u0435...");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (Exception e) {
            Toast.makeText(this, "\u0420\u0430\u0441\u043f\u043e\u0437\u043d\u0430\u0432\u0430\u043d\u0438\u0435 \u0440\u0435\u0447\u0438 \u043d\u0435\u0434\u043e\u0441\u0442\u0443\u043f\u043d\u043e", Toast.LENGTH_LONG).show();
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

    private String runLlama(String prompt, String imagePath) {
        if (!new File(modelPath).exists()) {
            return "\u041e\u0448\u0438\u0431\u043a\u0430: \u043c\u043e\u0434\u0435\u043b\u044c \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d\u0430\n\u041f\u0443\u0442\u044c: " + modelPath;
        }
        
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("getFilesDir() + "/llama-cli"");
            cmd.add("-m");
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
            if (out.isEmpty()) return "\u041d\u0435\u0442 \u043e\u0442\u0432\u0435\u0442\u0430 (\u043a\u043e\u0434: " + p.exitValue() + ")";
            return out;
        } catch (Exception e) {
            return "\u041e\u0448\u0438\u0431\u043a\u0430 \u0437\u0430\u043f\u0443\u0441\u043a\u0430: " + e.getMessage() +
                   "\n\n\u041f\u0440\u043e\u0432\u0435\u0440\u044c\u0442\u0435, \u0447\u0442\u043e llama-cli \u0443\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d \u0432 getFilesDir() + "/llama-cli"";
        }
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("response", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "\u0421\u043a\u043e\u043f\u0438\u0440\u043e\u0432\u0430\u043d\u043e", Toast.LENGTH_SHORT).show();
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
