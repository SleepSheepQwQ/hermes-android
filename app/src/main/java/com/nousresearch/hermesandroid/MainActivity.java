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
 * 最小可用版 UI：
 *   「安装环境」→ 解压 bootstrap + Hermes 载荷
 *   「运行 Hermes」→ 在 $PREFIX 环境里跑 hermes
 *
 * 注意：解压耗时较长（数千~上万小文件），必须放在后台线程。
 * 正式版应改为前台 Service + 通知（见 docs/05-architecture-decisions.md），
 * 否则系统可能在解压中途回收进程。
 */
public class MainActivity extends AppCompatActivity {

    private TextView output;
    private ScrollView scroller;
    private Button installBtn;
    private Button runBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        output = findViewById(R.id.output);
        scroller = findViewById(R.id.scroller);
        installBtn = findViewById(R.id.btn_install);
        runBtn = findViewById(R.id.btn_run);

        installBtn.setOnClickListener(v -> installAll());
        runBtn.setOnClickListener(v -> runHermes());

        refreshState();
    }

    private void refreshState() {
        boolean boot = BootstrapInstaller.isInstalled(this);
        boolean payload = PayloadInstaller.isInstalled(this);
        append("环境状态: bootstrap=" + (boot ? "已装" : "未装")
                + ", 载荷=" + (payload ? "已装" : "未装"));
        if (!boot) {
            append("点「安装环境」开始首次初始化。");
        } else if (payload) {
            append("一切就绪，可点「运行 Hermes」。");
        }
    }

    private void installAll() {
        installBtn.setEnabled(false);
        append("开始安装（bootstrap + 载荷）…");
        new Thread(() -> {
            try {
                File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
                append("nativeLibraryDir=" + nativeDir);

                if (!BootstrapInstaller.isInstalled(this)) {
                    append("解压 bootstrap（文件较多，请稍候）…");
                    BootstrapInstaller.install(this);
                    append("bootstrap 完成。");
                } else {
                    append("bootstrap 已存在，跳过。");
                }

                if (!PayloadInstaller.isInstalled(this)) {
                    append("解压 Hermes 载荷…");
                    PayloadInstaller.install(this);
                    append("载荷完成。");
                } else {
                    append("载荷已存在，跳过。");
                }

                ui.post(() -> {
                    append("安装全部完成。");
                    installBtn.setEnabled(true);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    append("安装失败: " + e);
                    installBtn.setEnabled(true);
                });
            }
        }, "hermes-installer").start();
    }

    private void runHermes() {
        if (!BootstrapInstaller.isInstalled(this)) {
            Toast.makeText(this, "请先安装环境", Toast.LENGTH_SHORT).show();
            return;
        }
        runBtn.setEnabled(false);
        new Thread(() -> {
            try {
                File prefix = BootstrapInstaller.prefixDir(this);
                File opt = BootstrapInstaller.optDir(this);
                File bash = new File(prefix, "bin/bash");
                File login = new File(prefix, "bin/login");
                File exe = bash.exists() ? bash : login;

                // 优先用载荷里的 venv/bin/hermes
                File hermes = new File(opt, "venv/bin/hermes");

                ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath(), "-l");
                pb.environment().clear();
                for (String kv : BootstrapInstaller.hermesEnv(this)) {
                    int i = kv.indexOf('=');
                    pb.environment().put(kv.substring(0, i), kv.substring(i + 1));
                }
                pb.redirectErrorStream(true);
                pb.directory(BootstrapInstaller.homeDir(this));

                Process p = pb.start();
                String cmd = hermes.exists()
                        ? "\"" + hermes.getAbsolutePath() + "\" --version\nexit\n"
                        : "hermes --version\nexit\n";
                p.getOutputStream().write(cmd.getBytes());
                p.getOutputStream().flush();

                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) {
                    final String l = line;
                    ui.post(() -> append(l));
                }
                int code = p.waitFor();
                ui.post(() -> {
                    append("退出码: " + code);
                    runBtn.setEnabled(true);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    append("运行失败: " + e);
                    runBtn.setEnabled(true);
                });
            }
        }, "hermes-runner").start();
    }

    private void append(String s) {
        output.append(s + "\n");
        scroller.post(() -> scroller.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
