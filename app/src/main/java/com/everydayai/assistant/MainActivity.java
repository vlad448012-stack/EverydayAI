package com.everydayai.assistant;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.ImageButton;
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
    private ChatAdapter adapter; 
    private List<ChatMessage> messages = new ArrayList<>();
    private EditText input;
    private String modelPath = "", mmprojPath = "", cliPath = "";
    private boolean useVulkan = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ID из твоего разметки (activity_main.xml)
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
                // В твоем проекте ChatMessage(String, int, String)
                messages.add(new ChatMessage(text, 1, ""));
                adapter.notifyItemInserted(messages.size() - 1);
                recycler.smoothScrollToPosition(messages.size() - 1);
                input.setText("");
                
                new Thread(() -> {
                    String res = runLlama(text, null);
                    runOnUiThread(() -> {
                        messages.add(new ChatMessage(res, 0, ""));
                        adapter.notifyItemInserted(messages.size() - 1);
                        recycler.smoothScrollToPosition(messages.size() - 1);
                    });
                }).start();
            }
        });
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        modelPath = prefs.getString("model_path", "");
        mmprojPath = prefs.getString("mmproj_path", "");
        useVulkan = prefs.getBoolean("use_vulkan", true);
    }

    private void setupAI() {
        new Thread(() -> {
            try {
                File bin = new File(getFilesDir(), "llama-cli");
                File icd = new File(getFilesDir(), "adreno.json");
                if (!icd.exists()) {
                    String json = "{\"file_format_version\": \"1.0.0\", \"ICD\": {\"library_path\": \"/vendor/lib64/hw/vulkan.adreno.so\", \"api_version\": \"1.3.268\"}}";
                    new FileOutputStream(icd).write(json.getBytes());
                }
                if (!bin.exists()) {
                    URL url = new URL("https://github.com/ggml-org/llama.cpp/releases/download/b3106/llama-b3106-bin-android-arm64.tar.gz");
                    InputStream in = new GZIPInputStream(url.openStream());
                    FileOutputStream out = new FileOutputStream(bin);
                    byte[] buf = new byte[8192];
                    int r; while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                    out.close(); in.close();
                }
                bin.setExecutable(true);
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    private String runLlama(String prompt, String imagePath) {
        try {
            File bin = new File(getFilesDir(), "llama-cli");
            File icd = new File(getFilesDir(), "adreno.json");
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
            pb.environment().put("VK_ICD_FILENAMES", icd.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l; while ((l = r.readLine()) != null) sb.append(l).append("\n");
            return sb.toString().trim();
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }
}
