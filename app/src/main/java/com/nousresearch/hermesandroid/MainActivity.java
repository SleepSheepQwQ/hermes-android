package com.nousresearch.hermesandroid;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * 最小可用版：首次启动解压 bootstrap，然后可运行 hermes 并把输出显示出来。
 * 后续可替换为真正的终端模拟器（termux terminal-emulator 或 xterm.js+WebView）。
 */
public class MainActivity extends AppCompatActivity {

    private TextView output;
    private ScrollView scroller;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        output = findViewById(R.id.output);
        scroller = findViewById(R.id.scroller);
        Button installBtn = findViewById(R.id.btn_install);
        Button runBtn = findViewById(R.id.btn_run);

        installBtn.setOnClickListener(v -> installBootstrap());
        runBtn.setOnClickListener(v -> runHermes());

        if (BootstrapInstaller.isInstalled(this)) {
            append("bootstrap 已就绪：" + BootstrapInstaller.prefixDir(this));
        } else {
            append("尚未安装 bootstrap，点「安装环境」。");
        }
    }

    private void installBootstrap() {
        append("开始解压 bootstrap…");
        new Thread(() -> {
            try {
                BootstrapInstaller.install(this);
                ui.post(() -> append("bootstrap 安装完成。"));
            } catch (Exception e) {
                ui.post(() -> append("安装失败: " + e));
            }
        }).start();
    }

    private void runHermes() {
        if (!BootstrapInstaller.isInstalled(this)) {
            Toast.makeText(this, "请先安装环境", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                File prefix = BootstrapInstaller.prefixDir(this);
                File shell = new File(prefix, "bin/login");
                File bash = new File(prefix, "bin/bash");
                File exe = bash.exists() ? bash : shell;

                ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath(), "-l");
                pb.environment().clear();
                for (String kv : BootstrapInstaller.hermesEnv(this)) {
                    int i = kv.indexOf('=');
                    pb.environment().put(kv.substring(0, i), kv.substring(i + 1));
                }
                pb.redirectErrorStream(true);
                File home = BootstrapInstaller.homeDir(this);
                pb.directory(home);

                Process p = pb.start();
                // 写一条命令：进 hermes
                p.getOutputStream().write("hermes --version\nexit\n".getBytes());
                p.getOutputStream().flush();

                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) {
                    final String l = line;
                    ui.post(() -> append(l));
                }
                int code = p.waitFor();
                ui.post(() -> append("退出码: " + code));
            } catch (Exception e) {
                ui.post(() -> append("运行失败: " + e));
            }
        }).start();
    }

    private void append(String s) {
        output.append(s + "\n");
        scroller.post(() -> scroller.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
