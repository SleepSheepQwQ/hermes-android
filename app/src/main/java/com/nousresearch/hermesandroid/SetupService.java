package com.nousresearch.hermesandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * 首次初始化前台 Service。
 *
 * 为什么必须是前台 Service：
 *  - bootstrap 有 1.5 万+ 文件、解压 + 建 1.5 千条软链需 30 秒~3 分钟。
 *    普通后台线程在 Activity 被回收或切后台后会被系统杀掉，留下半成品。
 *  - Android 8+ 后台 Service 有时限；Android 13+ 需 POST_NOTIFICATIONS 运行时权限。
 *  - Android 14+ 前台服务必须声明具体类型（此处 dataSync，上限 6 小时，足够）。
 *
 * 幂等性由 BootstrapInstaller / PayloadInstaller 的标记文件保证：
 * 中断后重跑会重新解压 staging，不会出现半成品。
 */
public class SetupService extends Service {

    public static final String TAG = "HermesSetup";
    public static final String ACTION_START = "com.nousresearch.hermesandroid.SETUP_START";
    public static final String ACTION_DONE = "com.nousresearch.hermesandroid.SETUP_DONE";
    public static final String EXTRA_STAGE = "stage";
    public static final String EXTRA_MESSAGE = "message";

    private static final String CHANNEL_ID = "hermes_setup";
    private static final int NOTIF_ID = 1001;

    /** 供 UI 观察进度（简易静态广播；正式版可用 LiveData/Flow） */
    public static volatile String lastMessage = "";
    public static volatile boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 10 秒内必须进入前台，否则系统会抛 ANR/杀掉
        startForeground(NOTIF_ID, buildNotification("准备中…", 0));

        if (running) {
            // 已有初始化在跑：不能只 return —— 上面已经 startForeground 了，
            // 直接返回会留下一个永不消失的通知（且本实例不会被 stopSelf）。
            Log.w(TAG, "已有初始化在跑，本实例退出前台");
            stopForegroundCompat();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        running = true;

        new Thread(() -> {
            try {
                // 阶段 1：bootstrap
                notify("解压 Termux 环境…", 10);
                if (!BootstrapInstaller.isInstalled(this)) {
                    BootstrapInstaller.install(this);
                }
                notify("Termux 环境就绪", 50);

                // 阶段 2：payload
                notify("解压 Hermes 载荷…", 60);
                if (!PayloadInstaller.isInstalled(this)) {
                    PayloadInstaller.install(this);
                }
                notify("Hermes 载荷就绪", 95);

                notify("初始化完成", 100);
                broadcast(ACTION_DONE, "完成");
            } catch (Exception e) {
                Log.e(TAG, "初始化失败", e);
                notify("初始化失败: " + e.getMessage(), 0);
                broadcast(ACTION_DONE, "失败: " + e);
            } finally {
                running = false;
                stopForegroundCompat();
                stopSelf();
            }
        }, "hermes-setup").start();

        // 不被系统自动重启（我们靠标记文件幂等，由 UI 决定何时重试）
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Hermes 初始化",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("首次启动解压运行环境");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text, int progress) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Hermes 正在初始化")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (progress > 0) {
            b.setProgress(100, progress, false);
        } else {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    private void notify(String text, int progress) {
        lastMessage = text;
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text, progress));
    }

    private void broadcast(String action, String message) {
        Intent i = new Intent(action);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(i);
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
