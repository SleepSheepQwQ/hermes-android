package com.nousresearch.hermesandroid;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 解压 Hermes 载荷（源码 + venv + managed tools）到 $FILES/opt/。
 *
 * 载荷格式：打包在 APK 的 jniLibs 里、名为 libhermes-payload.so 的 **tar.zst（或 tar.gz）**。
 * 与 bootstrap 同理，放 jniLibs 才能被系统原样解压到 nativeLibraryDir。
 *
 * 目录布局（解压后）：
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

        Log.i(TAG, "解压 payload: " + source + " -> " + staging);

        // 用系统 tar（bootstrap 提供的）解 zst/gz：
        //   tar --zstd -xf <source> -C <staging>
        // 优先用 $PREFIX/bin/tar，回退到 /system/bin/tar。
        File tar = new File(BootstrapInstaller.prefixDir(ctx), "bin/tar");
        String tarBin = tar.exists() ? tar.getAbsolutePath() : "/system/bin/tar";

        String[] cmd;
        if (source.getName().endsWith(".so")) {
            // 内容是 tar.zst：先 zstd 解再 tar
            cmd = new String[]{"sh", "-c",
                    "\"" + tarBin + "\" --zstd -xf \"" + source.getAbsolutePath() + "\" -C \""
                            + staging.getAbsolutePath() + "\" || "
                            + "\"" + tarBin + "\" -xzf \"" + source.getAbsolutePath() + "\" -C \""
                            + staging.getAbsolutePath() + "\""};
        } else {
            cmd = new String[]{tarBin, "-xf", source.getAbsolutePath(),
                    "-C", staging.getAbsolutePath()};
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        for (String kv : BootstrapInstaller.hermesEnv(ctx)) {
            int i = kv.indexOf('=');
            pb.environment().put(kv.substring(0, i), kv.substring(i + 1));
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // 读干输出，避免缓冲区阻塞
        byte[] buf = new byte[8192];
        while (p.getInputStream().read(buf) > 0) { /* drain */ }
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("解压 payload 失败，tar 退出码 " + code);
        }

        // 补执行位（venv/bin、tools/*/bin）
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
        Log.i(TAG, "payload 安装完成 -> " + opt);
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

    @SuppressWarnings("unused")
    private static InputStream open(File f) throws IOException {
        return new BufferedInputStream(new java.io.FileInputStream(f), 65536);
    }

    @SuppressWarnings("unused")
    private static OutputStream create(File f) throws IOException {
        return new FileOutputStream(f);
    }
}
