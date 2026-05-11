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
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class MainActivity extends AppCompatActivity {
    private RecyclerView recycler;
    private ChatAdapter adapter; // Твой адаптер теперь на месте
    private List<ChatMessage> messages = new ArrayList<>();
    private EditText input;
    private String modelPath = "", mmprojPath = "", cliPath = "";
    private boolean useVulkan = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Используем ПРАВИЛЬНЫЙ ID из твоего XML (в твоем zip он был chat_recycler)
        recycler = findViewById(R.id.chat_recycler);
        input = findViewById(R.id.input);
        ImageButton sendBtn = findViewById(R.id.send_btn);

        adapter = new ChatAdapter(messages);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        loadSettings();
        setupAI();

        sendBtn.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (!text.isEmpty()) {
                addMsg(text, 1, "");
                input.setText("");
                new Thread(() -> {
                    String res = runLlama(text, null);
                    runOnUiThread(() -> addMsg(res, 0, ""));
                }).start();
            }
        });
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        modelPath = prefs.getString("model_path", "");
        mmprojPath = prefs.getString("mmproj_path", "");
        useVulkan = prefs.getBoolean("use_vulkan", true);
        cliPath = new File(getFilesDir(), "llama-cli").getAbsolutePath();
    }

    private void setupAI() {
        new Thread(() -> {
            try {
                File bin = new File(getFilesDir(), "llama-cli");
                File icd = new File(getFilesDir(), "adreno.json");

                if (!icd.exists()) {
                    String json = "{\"file_format_version\": \"1.0.0\", \"ICD\": {\"library_path\": \"/vendor/lib64/hw/vulkan.adreno.so\", \"api_version\": \"1.3.268\"}}";
                    FileOutputStream fos = new FileOutputStream(icd);
                    fos.write(json.getBytes());
                    fos.close();
                }

                if (!bin.exists()) {
                    URL url = new URL("https://github.com/ggml-org/llama.cpp/releases/download/b3106/llama-b3106-bin-android-arm64.tar.gz");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    InputStream in = new GZIPInputStream(conn.getInputStream());
                    FileOutputStream out = new FileOutputStream(bin);
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    out.close(); in.close();
                }
                bin.setExecutable(true, false);
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    private String runLlama(String prompt, String imagePath) {
        try {
            File bin = new File(getFilesDir(), "llama-cli");
            File icd = new File(getFilesDir(), "adreno.json");
            
            if (!bin.exists()) return "Загрузка ядра ИИ... Подождите 10 сек.";

            List<String> cmd = new ArrayList<>();
            cmd.add(bin.getAbsolutePath());
            cmd.add("-m"); cmd.add(modelPath);
            if (useVulkan) { cmd.add("-ngl"); cmd.add("99"); }
            if (imagePath != null && !mmprojPath.isEmpty()) {
                cmd.add("--mmproj"); cmd.add(mmprojPath);
                cmd.add("--image"); cmd.add(imagePath);
            }
            cmd.add("-p"); cmd.add(prompt);
            cmd.add("-n"); cmd.add("256");
            cmd.add("--no-display-prompt");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            Map<String, String> env = pb.environment();
            env.put("VK_ICD_FILENAMES", icd.getAbsolutePath());
            env.put("LD_LIBRARY_PATH", "/vendor/lib64/hw:/system/lib64");

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

    private void addMsg(String text, int role, String time) {
        messages.add(new ChatMessage(text, role, time));
        adapter.notifyItemInserted(messages.size() - 1);
        recycler.smoothScrollToPosition(messages.size() - 1);
    }
}
