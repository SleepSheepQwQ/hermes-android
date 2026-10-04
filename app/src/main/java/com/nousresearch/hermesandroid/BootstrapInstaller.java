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
     * 并发语义：若另一个线程正在安装，本调用**等待其完成**而不是立即返回
     * （立即返回会让调用方误以为已完成，在解压未结束时继续往下走）。
     *
     * @return true 表示本次调用完成了安装；false 表示已装好（含等待到别人装完）。
     */
    public static boolean install(Context ctx) throws IOException {
        if (isInstalled(ctx)) {
            Log.i(TAG, "bootstrap 已安装，跳过");
            return false;
        }
        if (!sInstalling.compareAndSet(false, true)) {
            Log.w(TAG, "已有安装在进行，等待其完成…");
            long deadline = System.currentTimeMillis() + 10 * 60 * 1000L;
            while (sInstalling.get() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("等待安装完成时被中断", e);
                }
            }
            if (isInstalled(ctx)) return false;
            throw new IOException("等待其他安装线程超时，bootstrap 仍未就绪");
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
        // 关键：**不要**在 rename 前先删旧的 prefix —— 若此刻进程被杀，
        // 旧环境已毁、新的还没就位，两头空（审查 D）。
        // 正确顺序：旧 prefix 先改名备份 → rename staging → 成功后才删备份。
        if (prefix.exists()) {
            File bak = new File(prefix.getParentFile(), prefix.getName() + ".bak");
            deleteRecursively(bak);
            if (!prefix.renameTo(bak)) {
                // 备份改名都失败，只能退回删除（此时 staging 仍完整）
                Log.w(TAG, "旧 prefix 备份失败，改为直接删除");
                deleteRecursively(prefix);
            }
        }
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
        // 新的已就位，清理备份
        deleteRecursively(new File(prefix.getParentFile(), prefix.getName() + ".bak"));

        // Termux 脚本依赖 $PREFIX/tmp 存在
        File tmp = new File(prefix, "tmp");
        if (!tmp.isDirectory()) //noinspection ResultOfMethodCallIgnored
            tmp.mkdirs();

        // second-stage 被我们跳过，它原本会建 /etc/resolv.conf。
        // 没有它，DNS 解析全废 → hermes 调 API 直接失败（审查 F9）。
        try {
            File etc = new File(prefix, "etc");
            if (!etc.isDirectory()) //noinspection ResultOfMethodCallIgnored
                etc.mkdirs();
            File resolv = new File(etc, "resolv.conf");
            if (!resolv.exists()) {
                // Android 的 DNS 走 netd，应用无法直接读 /etc/resolv.conf；
                // 用公共 DNS 兜底，保证首启能联网。
                writeText(resolv, "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
            }
            // hosts 同理（某些工具依赖 localhost 解析）
            File hosts = new File(etc, "hosts");
            if (!hosts.exists()) {
                writeText(hosts, "127.0.0.1 localhost\n::1 localhost\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "写 resolv.conf/hosts 失败（不影响本地运行）", e);
        }

        // HERMES_HOME（$HOME/.hermes）必须预先存在：
        // 见 docs/08 —— 该目录是 Hermes 的用户数据根（sessions/logs/skills…），
        // 首启时若父目录缺失，部分初始化路径会失败。
        File hermesHome = new File(homeDir(ctx), ".hermes");
        if (!hermesHome.isDirectory()) //noinspection ResultOfMethodCallIgnored
            hermesHome.mkdirs();

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
     *  - 用 android.system.Os.symlink（API 21+）而不是 java.nio.Files.createSymbolicLink：
     *    后者是 **API 26+**，minSdk=24 在 Android 7.0/7.1 上会抛 NoSuchMethodError。
     *  - 不用 fork `ln`：bootstrap 有约 1551 条软链，逐个 fork 在手机上极慢；
     *    而且首启动时进程 PATH 里还没有 $PREFIX/bin，裸名 `ln` 会直接失败。
     *  - target 原样写入（不解析），与 `ln -s <target> <link>` 语义一致。
     *  - **失败必须致命**：软链断了（bin/sh、lib*.so）整个 shell 起不来，
     *    绝不能写出 "安装成功" 标记让用户以为装好了。
     */
    private static void rebuildSymlinks(File prefix, String content) throws IOException {
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
                // 重建前先删（可能是上一轮遗留）
                //noinspection ResultOfMethodCallIgnored
                linkAbs.toFile().delete();
                android.system.Os.symlink(target, linkAbs.toString());
                ok++;
            } catch (Exception ex) {
                fail++;
                if (fail <= 5) Log.w(TAG, "建软链失败: " + line, ex);
            }
        }
        Log.i(TAG, "软链重建: 成功 " + ok + " 条" + (fail > 0 ? "，失败 " + fail + " 条" : ""));

        // 关键软链白名单：缺一不可，否则 shell 起不来
        String[] critical = {"bin/sh", "bin/bash", "lib/libandroid-support.so"};
        for (String c : critical) {
            File f = new File(prefix, c);
            if (!f.exists()) {
                throw new IOException("关键软链缺失: " + c + "（软链失败 " + fail + " 条）");
            }
        }
        // 有任何失败就整体失败：1551 条里断几条往往意味着系统性错误
        // （例如文件系统不支持 symlink、路径非法），继续下去只会得到半死环境。
        if (fail > 0) {
            throw new IOException("软链重建失败 " + fail + " 条，安装中止");
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

    private static void writeText(File f, String text) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(text.getBytes("UTF-8"));
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /**
     * 生成运行环境变量。
     *
     * 关键变量（依据 hermes-agent 源码 pm/environments.py + scripts/build/launchers.py）：
     *
     *  - PREFIX           Termux 前缀。bootstrap 二进制内置默认值是 /data/data/com.termux/...
     *                     必须显式覆盖（docs/07 实测：不设就会读到错误默认值）。
     *  - LD_LIBRARY_PATH  指向 $PREFIX/lib。bootstrap 的 DT_RUNPATH 硬编码了
     *                     /data/data/com.termux/files/usr/lib，而 RUNPATH 优先级低于
     *                     LD_LIBRARY_PATH，故可覆盖（docs/07 已实测通过）。
     *  - HERMES_HOME      整体重定位用户数据目录（hermes_constants.get_hermes_home）。
     *  - HERMES_RUNTIME_DIR  最高优先级指向预置 tools store（pm/environments.store_root），
     *                     避免 PM 去联网下载工具。
     *  - HERMES_DISABLE_LAZY_INSTALLS=1  禁掉按需下载（等效 security.allow_lazy_installs:false），
     *                     因为工具已随 APK 预置。
     *  - TERMUX_VERSION   若干 Termux 优化分支靠它命中；不设会走桌面路径。
     */
    public static String[] hermesEnv(Context ctx) {
        File files = ctx.getFilesDir();
        String p = prefixDir(ctx).getAbsolutePath();
        String h = homeDir(ctx).getAbsolutePath();
        String o = optDir(ctx).getAbsolutePath();
        String venv = o + "/venv";
        return new String[]{
                "PREFIX=" + p,
                "TERMUX_PREFIX=" + p,
                "HOME=" + h,
                "HERMES_HOME=" + h + "/.hermes",
                // 预置载荷布局：$opt/{manifest.json,hermes-src/,venv/,tools/}
                // 注意：优先读 manifest.json 的 store 字段，与 pm/environments.py 的
                // store_root() 语义保持一致；读不到才回退到 "tools"。
                "HERMES_RUNTIME_DIR=" + o + "/" + manifestStore(o, "tools"),
                "HERMES_DISABLE_LAZY_INSTALLS=1",
                "PATH=" + p + "/bin:" + h + "/.local/bin:" + venv + "/bin:"
                        + o + "/tools/bin:" + files + "/bin",
                "LD_LIBRARY_PATH=" + buildLibraryPath(ctx),
                "TMPDIR=" + p + "/tmp",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "SHELL=" + p + "/bin/bash",
                "ANDROID_DATA_ROOT=" + files.getAbsolutePath(),
                // Termux 兼容变量
                "TERMUX_APP_PACKAGE=" + ctx.getPackageName(),
                "TERMUX_VERSION=0.118.3",
                "TERMUX_MAIN_PACKAGE_FORMAT=debian",
                // venv 的 pyvenv.cfg home= 指向 bootstrap 的 python；
                // 显式给 PYTHONHOME 会在某些场景干扰 venv 的 base_prefix 推导，
                // 因此**不设** PYTHONHOME，只保证 lib-dynload 在 LD_LIBRARY_PATH 里
                // （见 buildLibraryPath 的 lib-dynload 追加逻辑）。
                "PYTHONNOUSERSITE=1",
                "SSL_CERT_FILE=" + p + "/etc/tls/cert.pem",
                "CURL_CA_BUNDLE=" + p + "/etc/tls/cert.pem",
                "GIT_SSL_CAINFO=" + p + "/etc/tls/cert.pem",
        };
    }

    /** 从 <opt>/manifest.json 读 store 字段；失败回退 fallback。 */
    private static String manifestStore(String opt, String fallback) {
        try {
            File mf = new File(opt, "manifest.json");
            if (!mf.isFile()) return fallback;
            String txt = readAll(new java.io.FileInputStream(mf));
            // 极简解析：找 "store" : "xxx"（不引入 JSON 库，避免额外依赖）
            int i = txt.indexOf("\"store\"");
            if (i < 0) return fallback;
            int c = txt.indexOf(':', i);
            if (c < 0) return fallback;
            int q1 = txt.indexOf('"', c);
            if (q1 < 0) return fallback;
            int q2 = txt.indexOf('"', q1 + 1);
            if (q2 < 0) return fallback;
            String v = txt.substring(q1 + 1, q2);
            return v.isEmpty() ? fallback : v;
        } catch (Exception e) {
            Log.w(TAG, "读 manifest.store 失败，回退 " + fallback, e);
            return fallback;
        }
    }

    /** 探测 <venv>/lib/python3.X，返回目录名（如 "python3.14"）；找不到返回 null。 */
    private static String detectVenvPythonDir(String venv) {
        File lib = new File(venv, "lib");
        File[] kids = lib.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isDirectory() && k.getName().startsWith("python3.")) return k.getName();
        }
        return null;
    }

    /**
     * 组装 LD_LIBRARY_PATH。
     *
     * 为什么不能写死：payload 里的库分布在多个位置，且随打包形态变化：
     *   - bootstrap 的库            <files>/usr/lib
     *   - managed python 自带的库   <files>/opt/tools/python-<ver>/data/data/com.termux/files/usr/lib
     *   - Hermes runtime-libs       <files>/opt/runtime-libs/lib
     * managed python 的 ELF RUNPATH 硬编码 /data/data/com.termux/files/usr/lib，
     * 若不把它的真实 lib 目录加进来，libpython3.14.so 会找不到（docs/13）。
     * 因此这里**动态扫描** payload 下所有存在的 lib 目录，全部纳入。
     */
    private static String buildLibraryPath(Context ctx) {
        StringBuilder sb = new StringBuilder();
        String p = prefixDir(ctx).getAbsolutePath();
        String o = optDir(ctx).getAbsolutePath();

        sb.append(p).append("/lib");

        // 固定候选
        String[] fixed = {o + "/runtime-libs/lib", o + "/tools/lib", o + "/venv/lib"};
        for (String d : fixed) {
            if (new File(d).isDirectory()) sb.append(':').append(d);
        }

        // **关键**：venv 的 C 扩展（_struct/_ctypes/select/_hashlib…）都在
        // venv/lib/python3.X/lib-dynload 下，CPython 启动时会 dlopen 它们。
        // 该目录不在 LD_LIBRARY_PATH 里就会 ModuleNotFoundError（审查 S1）。
        String venvPy = detectVenvPythonDir(o + "/venv");
        if (venvPy != null) {
            File dyn = new File(o + "/venv/lib/" + venvPy + "/lib-dynload");
            if (dyn.isDirectory()) sb.append(':').append(dyn.getAbsolutePath());
        }

        // 扫描 tools/ 下 managed 工具的 usr/lib（版本号带哈希，必须扫）
        File tools = new File(o, "tools");
        File[] toolDirs = tools.listFiles();
        if (toolDirs != null) {
            for (File t : toolDirs) {
                if (!t.isDirectory()) continue;
                File usrLib = new File(t,
                        "data/data/com.termux/files/usr/lib");
                if (usrLib.isDirectory()) sb.append(':').append(usrLib.getAbsolutePath());
                File plainLib = new File(t, "lib");
                if (plainLib.isDirectory()) sb.append(':').append(plainLib.getAbsolutePath());
                // managed python 的 lib-dynload 同样要纳入
                File pyLib = new File(t, "lib");
                File[] pyKids = pyLib.listFiles();
                if (pyKids != null) {
                    for (File pk : pyKids) {
                        if (pk.isDirectory() && pk.getName().startsWith("python3.")) {
                            File dyn2 = new File(pk, "lib-dynload");
                            if (dyn2.isDirectory()) sb.append(':').append(dyn2.getAbsolutePath());
                        }
                    }
                }
            }
        }

        // 扫描 payload 内任意层级的 python lib（兜底）
        File[] optKids = new File(o).listFiles();
        if (optKids != null) {
            for (File k : optKids) {
                File lib = new File(k, "lib");
                if (lib.isDirectory()) sb.append(':').append(lib.getAbsolutePath());
            }
        }

        return sb.toString();
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
