package com.nousresearch.hermesandroid;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 解压 Hermes 载荷（源码 + venv + managed tools）到 $FILES/opt/。
 *
 * 载荷格式：打包在 APK 的 jniLibs 里、名为 libhermes-payload.so 的压缩包。
 * 与 bootstrap 同理，放 jniLibs 才能被系统原样解压到 nativeLibraryDir。
 *
 * 支持的容器（用**魔数嗅探**判断，不看扩展名——因为文件被强制命名为 .so）：
 *   gzip      : 1f 8b
 *   zstd      : 28 b5 2f fd
 *   xz        : fd 37 7a 58 5a 00
 *   裸 tar    : "ustar" @ offset 257
 *
 * 解压后目录布局：
 *   $FILES/opt/hermes-src/     Hermes 源码（已 trim）
 *   $FILES/opt/venv/           Python venv（已 trim，editable 指向 hermes-src）
 *   $FILES/opt/tools/          Hermes managed 工具链（python/node/...）
 */
public final class PayloadInstaller {

    private static final String TAG = "HermesPayload";
    private static final String PAYLOAD_LIB = "libhermes-payload.so";
    private static final String STAGING_NAME = "opt-staging";

    private static final AtomicBoolean sInstalling = new AtomicBoolean(false);

    private PayloadInstaller() {}

    public static boolean isInstalled(Context ctx) {
        return BootstrapInstaller.isPayloadInstalled(ctx);
    }

    public static boolean install(Context ctx) throws IOException {
        if (isInstalled(ctx)) {
            Log.i(TAG, "payload 已安装，跳过");
            return false;
        }
        if (!sInstalling.compareAndSet(false, true)) {
            Log.w(TAG, "已有 payload 安装在进行，本次跳过");
            return false;
        }
        try {
            if (isInstalled(ctx)) return false;
            doInstall(ctx);
            return true;
        } finally {
            sInstalling.set(false);
        }
    }

    private static void doInstall(Context ctx) throws IOException {
        File nativeDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        File source = new File(nativeDir, PAYLOAD_LIB);
        if (!source.exists()) {
            throw new IOException("找不到 payload: " + source.getAbsolutePath());
        }

        File files = ctx.getFilesDir();
        File staging = new File(files, STAGING_NAME);
        File opt = BootstrapInstaller.optDir(ctx);

        deleteRecursively(staging);
        //noinspection ResultOfMethodCallIgnored
        staging.mkdirs();

        String kind = detectContainer(source);
        Log.i(TAG, "解压 payload: " + source + " (" + kind + ") -> " + staging);

        // 必须用 bootstrap 的 tar/sh 绝对路径 —— 不能依赖进程 PATH，
        // 首启动时 PATH 里还没有 $PREFIX/bin。
        File prefix = BootstrapInstaller.prefixDir(ctx);
        File tarF = new File(prefix, "bin/tar");
        File shF = new File(prefix, "bin/sh");
        File busyboxF = new File(prefix, "bin/busybox");
        String sh = shF.exists() ? shF.getAbsolutePath() : "/system/bin/sh";
        String tar = tarF.exists() ? tarF.getAbsolutePath()
                : (busyboxF.exists() ? busyboxF.getAbsolutePath() + " tar" : "/system/bin/tar");

        String cmd;
        switch (kind) {
            case "zstd":
                cmd = q(tar) + " --zstd -xf " + q(source.getAbsolutePath())
                        + " -C " + q(staging.getAbsolutePath());
                break;
            case "gzip":
                cmd = q(tar) + " -xzf " + q(source.getAbsolutePath())
                        + " -C " + q(staging.getAbsolutePath());
                break;
            case "xz":
                cmd = q(tar) + " -xJf " + q(source.getAbsolutePath())
                        + " -C " + q(staging.getAbsolutePath());
                break;
            case "tar":
                cmd = q(tar) + " -xf " + q(source.getAbsolutePath())
                        + " -C " + q(staging.getAbsolutePath());
                break;
            default:
                throw new IOException("无法识别的载荷容器格式: " + kind);
        }

        int code = runShell(ctx, sh, cmd);
        if (code != 0) {
            throw new IOException("解压 payload 失败，退出码 " + code + "（格式 " + kind + "）");
        }

        // 载荷顶层应直接是 hermes-src/ venv/ tools/；若多包了一层目录则下移
        flattenSingleDir(staging);

        setExecutableRecursive(new File(staging, "venv/bin"));
        setExecutableRecursive(new File(staging, "tools"));

        if (opt.exists()) deleteRecursively(opt);
        if (!staging.renameTo(opt)) {
            //noinspection ResultOfMethodCallIgnored
            opt.mkdirs();
            File[] kids = staging.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (!k.renameTo(new File(opt, k.getName()))) {
                        throw new IOException("移动失败: " + k);
                    }
                }
            }
            deleteRecursively(staging);
        }

        // 全部成功后写标记（只有标记存在才算装好）
        BootstrapInstaller.markPayloadInstalled(ctx);
        Log.i(TAG, "payload 安装完成 -> " + opt);
    }

    /** 用 bootstrap 的 sh 执行一条命令；边读边等，避免管道缓冲区死锁。 */
    private static int runShell(Context ctx, String sh, String script) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(sh, "-c", script);
        pb.environment().clear();
        for (String kv : BootstrapInstaller.hermesEnv(ctx)) {
            int i = kv.indexOf('=');
            pb.environment().put(kv.substring(0, i), kv.substring(i + 1));
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();

        // 关键：必须在 waitFor 前把 stdout 读干，否则子进程写满管道会阻塞，
        // 而 waitFor 永远等不到退出 → 死锁。
        StringBuilder log = new StringBuilder();
        byte[] buf = new byte[8192];
        try (InputStream in = p.getInputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                log.append(new String(buf, 0, n));
                if (log.length() > 16384) log.setLength(0);  // 只留尾部，别撑爆内存
            }
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("解压被中断", e);
        }
        if (code != 0) {
            Log.e(TAG, "tar 输出: " + log);
        }
        return code;
    }

    /** 读前 300 字节判断容器格式（不依赖扩展名）。 */
    private static String detectContainer(File f) throws IOException {
        byte[] head = new byte[300];
        int n;
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 4096)) {
            n = in.read(head);
        }
        if (n < 4) return "unknown";
        // zstd: 28 B5 2F FD
        if ((head[0] & 0xFF) == 0x28 && (head[1] & 0xFF) == 0xB5
                && (head[2] & 0xFF) == 0x2F && (head[3] & 0xFF) == 0xFD) return "zstd";
        // gzip: 1F 8B
        if ((head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) return "gzip";
        // xz: FD 37 7A 58 5A 00
        if ((head[0] & 0xFF) == 0xFD && (head[1] & 0xFF) == 0x37
                && (head[2] & 0xFF) == 0x7A && (head[3] & 0xFF) == 0x58) return "xz";
        // 裸 tar: "ustar" @ 257
        if (n >= 262 && head[257] == 'u' && head[258] == 's'
                && head[259] == 't' && head[260] == 'a' && head[261] == 'r') return "tar";
        return "unknown";
    }

    /** 若 staging 下只有一个目录，把它下移一层（处理打包时多包了一层）。 */
    private static void flattenSingleDir(File staging) throws IOException {
        File[] kids = staging.listFiles();
        if (kids == null || kids.length != 1 || !kids[0].isDirectory()) return;
        File inner = kids[0];
        File[] innerKids = inner.listFiles();
        if (innerKids == null) return;
        for (File k : innerKids) {
            if (!k.renameTo(new File(staging, k.getName()))) {
                throw new IOException("下移失败: " + k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        inner.delete();
        Log.i(TAG, "载荷多包了一层目录，已下移: " + inner.getName());
    }

    /** shell 单引号转义（路径可能含空格，虽然 APK 目录一般不含）。 */
    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void setExecutableRecursive(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) setExecutableRecursive(f);
            else //noinspection ResultOfMethodCallIgnored
                f.setExecutable(true, true);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    @SuppressWarnings("unused")
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
