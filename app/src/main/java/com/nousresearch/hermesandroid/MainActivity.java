package com.nousresearch.hermesandroid;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * 主界面：
 *   「安装环境」→ 启动 SetupService（**前台 Service**，解压 bootstrap + 载荷）
 *   「运行 Hermes」→ 在 $PREFIX 环境里跑 hermes
 *
 * 为什么初始化必须走前台 Service 而不是裸线程：
 *   解压 1.5 万+ 文件需 30 秒~3 分钟，Activity 被回收或切后台后
 *   裸线程会被系统直接杀掉，留下半成品。详见 docs/11-cicd-constraints.md
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_NOTIF = 100;

    private TextView output;
    private ScrollView scroller;
    private Button installBtn;
    private Button runBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 接收 SetupService 的完成广播 */
    private final BroadcastReceiver doneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra(SetupService.EXTRA_MESSAGE);
            append("初始化结束: " + msg);
            installBtn.setEnabled(true);
            refreshState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        output = findViewById(R.id.output);
        scroller = findViewById(R.id.scroller);
        installBtn = findViewById(R.id.btn_install);
        runBtn = findViewById(R.id.btn_run);

        installBtn.setOnClickListener(v -> startSetup());
        runBtn.setOnClickListener(v -> runHermes());

        requestNotifPermission();
        refreshState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(SetupService.ACTION_DONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(doneReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            //noinspection UnspecifiedRegisterReceiverFlag
            registerReceiver(doneReceiver, f);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(doneReceiver);
        } catch (IllegalArgumentException ignored) {
            // 未注册时忽略
        }
    }

    private void requestNotifPermission() {
        // Android 13+ 前台 Service 的通知需要运行时权限，否则进度不可见
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                   != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF
                && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            append("未授予通知权限：进度不可见，但初始化仍会继续。");
        }
    }

    private void refreshState() {
        boolean boot = BootstrapInstaller.isInstalled(this);
        boolean payload = PayloadInstaller.isInstalled(this);
        append("环境状态: bootstrap=" + (boot ? "已装" : "未装")
                + ", 载荷=" + (payload ? "已装" : "未装"));
        if (boot && payload) {
            append("一切就绪，可点「运行 Hermes」。");
        } else {
            append("点「安装环境」开始首次初始化。");
        }
    }

    private void startSetup() {
        if (BootstrapInstaller.isInstalled(this) && PayloadInstaller.isInstalled(this)) {
            append("环境已就绪，无需重复安装。");
            return;
        }
        installBtn.setEnabled(false);
        append("启动初始化（前台 Service）——可在通知栏看进度，切后台也不会中断。");
        append("nativeLibraryDir = " + new File(getApplicationInfo().nativeLibraryDir));

        Intent i = new Intent(this, SetupService.class);
        i.setAction(SetupService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
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
