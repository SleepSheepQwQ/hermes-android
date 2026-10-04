package com.nousresearch.hermesandroid;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把打包在 APK 里的 Termux bootstrap（伪装成 libtermux-bootstrap.so）
 * 解压到应用私有目录，并重建符号链接。
 *
 * 设计要点（依据实证调研，见 docs/05-architecture-decisions.md）：
 *  1. 从 nativeLibraryDir 读取 —— Android 安装时会把 lib/<abi>/*.so 原样解压到
 *     只读的 /data/app/<pkg>/lib/arm64/，规避 assets 的压缩与 W^X exec 限制。
 *  2. staging + 原子 renameTo —— 解压到 $PREFIX-staging，完成后整体 rename 成 $PREFIX。
 *     这样"$PREFIX 存在且非空"即等于"解压完整"，崩溃/被杀后重试不会留下半成品。
 *  3. AtomicBoolean 防竞态 —— 首次启动时 Activity 可能被系统重建（MIUI/HyperOS 必现），
 *     不加锁会导致两个线程同时解压同一个 staging 目录并互相踩踏。
 *  4. chmod 白名单 —— 只对可执行目录（bin/ libexec/ lib/）补 x 位，
 *     不对全部上万文件逐个 chmod（那才是性能瓶颈）。
 *  5. 流式解压 + 64KB buffer —— 耗时瓶颈是每文件的元数据 syscall，不是 deflate。
 */
public final class BootstrapInstaller {

    private static final String TAG = "HermesBootstrap";
    private static final String BOOTSTRAP_LIB = "libtermux-bootstrap.so";
    private static final String PAYLOAD_LIB = "libhermes-payload.so";
    private static final String PREFIX_NAME = "usr";
    private static final String STAGING_NAME = "usr-staging";
    private static final String HOME_NAME = "home";
    private static final String OPT_NAME = "opt";
    private static final String STAMP_BOOTSTRAP = ".bootstrap-ok";
    private static final String STAMP_PAYLOAD = ".payload-ok";

    /** 可执行目录白名单（相对 $PREFIX） */
    private static final String[] EXEC_DIRS = {"bin", "libexec", "lib"};

    private static final AtomicBoolean sInstalling = new AtomicBoolean(false);

    private BootstrapInstaller() {}

    /**
     * 安装是否完成。
     *
     * 判据演进（可靠性考虑）：
     *  - 最初用「staging 原子 rename」保证 $PREFIX 一旦出现就是完整的；
     *    但 rename 前若我们已 mkdir 过 $PREFIX（回退分支），就可能留下半成品。
     *  - 因此再加一道**成功标记文件**：解压+建链+chmod 全部成功后写入
     *    $FILES/.bootstrap-ok。只有标记存在才算装好，避免半成品被误判为完成。
     */
    public static boolean isInstalled(Context ctx) {
        File prefix = prefixDir(ctx);
        File stamp = new File(ctx.getFilesDir(), STAMP_BOOTSTRAP);
        return stamp.isFile() && prefix.isDirectory()
                && new File(prefix, "bin").isDirectory();
    }

    private static void writeStamp(Context ctx, String name) throws IOException {
        File stamp = new File(ctx.getFilesDir(), name);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(stamp)) {
            fos.write(("ok " + System.currentTimeMillis() + "\n").getBytes("UTF-8"));
        }
    }

    public static File prefixDir(Context ctx) {
        return new File(ctx.getFilesDir(), PREFIX_NAME);
    }

    public static File homeDir(Context ctx) {
        return new File(ctx.getFilesDir(), HOME_NAME);
    }

    public static File optDir(Context ctx) {
        return new File(ctx.getFilesDir(), OPT_NAME);
    }

    /**
     * 安装 bootstrap。幂等、线程安全。
     * @return true 表示本次调用完成了安装；false 表示已在安装中或已装好。
     */
    public static boolean install(Context ctx) throws IOException {
        if (isInstalled(ctx)) {
            Log.i(TAG, "bootstrap 已安装，跳过");
            return false;
        }
        if (!sInstalling.compareAndSet(false, true)) {
            Log.w(TAG, "已有安装在进行，本次跳过");
            return false;
        }
        try {
            if (isInstalled(ctx)) return false;   // 双检
            doInstall(ctx);
            return true;
        } finally {
            sInstalling.set(false);
        }
    }

    private static void doInstall(Context ctx) throws IOException {
        File nativeDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        File source = new File(nativeDir, BOOTSTRAP_LIB);
        if (!source.exists()) {
            throw new IOException("找不到 bootstrap: " + source.getAbsolutePath());
        }

        File staging = new File(ctx.getFilesDir(), STAGING_NAME);
        File prefix = prefixDir(ctx);
        File home = homeDir(ctx);

        deleteRecursively(staging);           // 清掉上次未完成的 staging
        //noinspection ResultOfMethodCallIgnored
        staging.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        home.mkdirs();

        Log.i(TAG, "解压 bootstrap: " + source + " -> " + staging);

        StringBuilder symlinks = new StringBuilder();
        int total = 0;
        try (ZipFile zip = new ZipFile(source)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();

                // SYMLINKS.txt 记录软链（每行 "目标←链接名"），解压后统一重建
                if (name.equals("SYMLINKS.txt")) {
                    try (InputStream in = zip.getInputStream(e)) {
                        symlinks.append(readAll(in));
                    }
                    continue;
                }
                // second-stage 脚本由我们自己的初始化替代
                if (name.startsWith("termux-bootstrap-second-stage")) {
                    continue;
                }

                File out = new File(staging, name);
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
                try (InputStream in = new BufferedInputStream(zip.getInputStream(e), 65536);
                     OutputStream os = new FileOutputStream(out)) {
                    copy(in, os);
                }
                total++;
            }
        }
        Log.i(TAG, "解出 " + total + " 个文件");

        rebuildSymlinks(staging, symlinks.toString());

        // 只对白名单目录补 x 位
        for (String d : EXEC_DIRS) {
            setExecutableRecursive(new File(staging, d));
        }

        // 原子交付：staging -> prefix
        if (prefix.exists()) deleteRecursively(prefix);
        if (!staging.renameTo(prefix)) {
            Log.w(TAG, "renameTo 失败，退化为逐项移动");
            //noinspection ResultOfMethodCallIgnored
            prefix.mkdirs();
            File[] kids = staging.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (!k.renameTo(new File(prefix, k.getName()))) {
                        throw new IOException("移动失败: " + k);
                    }
                }
            }
            deleteRecursively(staging);
        }

        // Termux 脚本依赖 $PREFIX/tmp 存在
        File tmp = new File(prefix, "tmp");
        if (!tmp.isDirectory()) //noinspection ResultOfMethodCallIgnored
            tmp.mkdirs();

        // 全部成功后才写标记
        writeStamp(ctx, STAMP_BOOTSTRAP);
        Log.i(TAG, "bootstrap 安装完成 -> " + prefix);
    }

    /**
     * SYMLINKS.txt：每行 "目标←链接名"，分隔符是 UTF-8 的 U+2190（←，字节 e2 86 90）。
     * 实测样本：`../../../share/fontconfig/conf.avail/60-generic.conf←./etc/fonts/conf.d/60-generic.conf`
     * 目标可能是**相对路径**（相对链接所在目录解析），链接名常带 `./` 前缀。
     *
     * 实现要点：
     *  - 用 java.nio 的 Files.createSymbolicLink 而不是 fork `ln`：
     *    bootstrap 有约 1551 条软链，逐个 fork 一个进程在手机上极慢；
     *    而且首启动时进程 PATH 里还没有 $PREFIX/bin，裸名 `ln` 会直接失败。
     *  - target 原样写入（不解析），与 `ln -s <target> <link>` 语义一致。
     */
    private static void rebuildSymlinks(File prefix, String content) {
        int ok = 0, fail = 0;
        java.nio.file.Path prefixPath = prefix.toPath().toAbsolutePath().normalize();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int idx = line.indexOf('\u2190');   // ←
            if (idx <= 0) continue;
            String target = line.substring(0, idx);
            String linkPath = line.substring(idx + 1);
            try {
                File link = new File(prefix, linkPath);
                File lp = link.getParentFile();
                if (lp != null && !lp.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    lp.mkdirs();
                }
                // 越界保护：链接必须落在 $PREFIX 内
                java.nio.file.Path linkAbs = link.toPath().toAbsolutePath().normalize();
                if (!linkAbs.startsWith(prefixPath)) {
                    Log.w(TAG, "跳过越界软链: " + linkPath);
                    continue;
                }
                java.nio.file.Files.deleteIfExists(linkAbs);
                java.nio.file.Files.createSymbolicLink(linkAbs,
                        java.nio.file.Paths.get(target));
                ok++;
            } catch (Exception ex) {
                fail++;
                if (fail <= 5) Log.w(TAG, "建软链失败: " + line, ex);
            }
        }
        Log.i(TAG, "软链重建: 成功 " + ok + " 条" + (fail > 0 ? "，失败 " + fail + " 条" : ""));
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

    /**
     * 生成运行环境变量。
     * LD_LIBRARY_PATH 必须指向 $PREFIX/lib —— Hermes 的 managed python 二进制
     * RUNPATH 硬编码了 /data/data/com.termux/files/usr/lib，在独立 APK 里不匹配，
     * 靠这里覆盖（见 docs/06-size-and-deps.md 解法 A）。
     */
    public static String[] hermesEnv(Context ctx) {
        File files = ctx.getFilesDir();
        String p = prefixDir(ctx).getAbsolutePath();
        String h = homeDir(ctx).getAbsolutePath();
        String o = optDir(ctx).getAbsolutePath();
        return new String[]{
                "PREFIX=" + p,
                "HOME=" + h,
                "HERMES_HOME=" + h + "/.hermes",
                "PATH=" + p + "/bin:" + h + "/.local/bin:" + o + "/bin:" + files + "/bin",
                "LD_LIBRARY_PATH=" + p + "/lib",
                "TMPDIR=" + p + "/tmp",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "SHELL=" + p + "/bin/login",
                // Termux 兼容变量，部分脚本会读
                "TERMUX_APP_PACKAGE=" + ctx.getPackageName(),
                "TERMUX_PREFIX=" + p,
        };
    }

    /** 载荷（源码 + venv/tools）是否已就位 */
    public static boolean isPayloadInstalled(Context ctx) {
        File opt = optDir(ctx);
        File stamp = new File(ctx.getFilesDir(), STAMP_PAYLOAD);
        return stamp.isFile() && new File(opt, "hermes-src").isDirectory();
    }

    /** 供 PayloadInstaller 在成功后调用 */
    static void markPayloadInstalled(Context ctx) throws IOException {
        File stamp = new File(ctx.getFilesDir(), STAMP_PAYLOAD);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(stamp)) {
            fos.write(("ok " + System.currentTimeMillis() + "\n").getBytes("UTF-8"));
        }
    }
}
