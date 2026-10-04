package com.nousresearch.hermesandroid;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把打包在 APK 里的 Termux bootstrap（伪装成 libtermux-bootstrap.so）
 * 解压到应用私有目录，并重建符号链接。
 *
 * 依据 termux-app 的做法：Android 安装时会把 lib/<abi>/*.so 原样解压到
 * nativeLibraryDir（不压缩、可直接读取），借此规避 assets 的压缩与 execve 限制。
 */
public final class BootstrapInstaller {

    private static final String TAG = "HermesBootstrap";
    private static final String BOOTSTRAP_LIB = "libtermux-bootstrap.so";
    private static final String PREFIX_NAME = "usr";
    private static final String HOME_NAME = "home";

    private BootstrapInstaller() {}

    /** 已安装标记：$PREFIX/../.bootstrap-installed */
    private static File stampFile(Context ctx) {
        return new File(ctx.getFilesDir(), ".bootstrap-installed");
    }

    public static boolean isInstalled(Context ctx) {
        return stampFile(ctx).exists();
    }

    public static File prefixDir(Context ctx) {
        return new File(ctx.getFilesDir(), PREFIX_NAME);
    }

    public static File homeDir(Context ctx) {
        return new File(ctx.getFilesDir(), HOME_NAME);
    }

    /**
     * 从 nativeLibraryDir 读出 bootstrap zip 并解压。
     * 幂等：已解压则跳过。
     */
    public static void install(Context ctx) throws IOException {
        if (isInstalled(ctx)) {
            Log.i(TAG, "bootstrap 已安装，跳过");
            return;
        }

        File nativeDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        File source = new File(nativeDir, BOOTSTRAP_LIB);
        if (!source.exists()) {
            throw new IOException("找不到 bootstrap: " + source.getAbsolutePath());
        }

        File prefix = prefixDir(ctx);
        File home = homeDir(ctx);
        File tmp = new File(prefix, "tmp");
        //noinspection ResultOfMethodCallIgnored
        prefix.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        home.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();

        Log.i(TAG, "解压 bootstrap: " + source + " -> " + prefix);

        StringBuilder symlinks = new StringBuilder();
        try (ZipFile zip = new ZipFile(source)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();

                // SYMLINKS.txt 记录软链（每行 "target←link"），解压后单独重建
                if (name.equals("SYMLINKS.txt")) {
                    try (InputStream in = zip.getInputStream(e)) {
                        symlinks.append(readAll(in));
                    }
                    continue;
                }
                // 权限脚本由 second-stage 处理，跳过
                if (name.startsWith("termux-bootstrap-second-stage")) {
                    continue;
                }

                File out = new File(prefix, name);
                if (name.endsWith("/")) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                try (InputStream in = new BufferedInputStream(zip.getInputStream(e));
                     OutputStream os = new FileOutputStream(out)) {
                    copy(in, os);
                }
                // 给可执行文件补执行位（zip 不解出 unix mode）
                if (name.startsWith("bin/") || name.startsWith("libexec/")) {
                    //noinspection ResultOfMethodCallIgnored
                    out.setExecutable(true, true);
                }
            }
        }

        rebuildSymlinks(prefix, symlinks.toString());
        setExecutableRecursive(new File(prefix, "bin"));
        setExecutableRecursive(new File(prefix, "libexec"));

        // 写标记
        try (FileOutputStream fos = new FileOutputStream(stampFile(ctx))) {
            fos.write(("installed\n").getBytes());
        }
        Log.i(TAG, "bootstrap 解压完成");
    }

    /** SYMLINKS.txt：每行 "目标←链接名"（相对 $PREFIX） */
    private static void rebuildSymlinks(File prefix, String content) {
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int idx = line.indexOf('←');
            if (idx <= 0) continue;
            String target = line.substring(0, idx);
            String linkPath = line.substring(idx + 1);
            try {
                File link = new File(prefix, linkPath);
                File lp = link.getParentFile();
                if (lp != null) //noinspection ResultOfMethodCallIgnored
                    lp.mkdirs();
                //noinspection ResultOfMethodCallIgnored
                link.delete();
                Runtime.getRuntime().exec(new String[]{
                        "ln", "-sf", target, link.getAbsolutePath()
                }).waitFor();
            } catch (Exception ex) {
                Log.w(TAG, "建软链失败: " + line, ex);
            }
        }
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

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** 生成启动 hermes 的环境（供后续 shell/终端使用） */
    public static String[] hermesEnv(Context ctx) {
        File prefix = prefixDir(ctx);
        File home = homeDir(ctx);
        return new String[]{
                "PREFIX=" + prefix.getAbsolutePath(),
                "HOME=" + home.getAbsolutePath(),
                "PATH=" + new File(prefix, "bin").getAbsolutePath()
                        + ":" + new File(home, ".local/bin").getAbsolutePath(),
                "LD_LIBRARY_PATH=" + new File(prefix, "lib").getAbsolutePath(),
                "TMPDIR=" + new File(prefix, "tmp").getAbsolutePath(),
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
        };
    }

    public static File fileUnused(Context ctx) { return new File(ctx.getFilesDir(), "unused"); }
}
