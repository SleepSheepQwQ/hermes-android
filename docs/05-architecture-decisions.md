# 技术定论：架构修正（基于实证调研）

日期：2026-10-04 · 来源：4 个调研子代理 + 本机实测

本文档汇总**决定性结论**，修正了 `03-packaging.md` 中的若干错误假设。

---

## 一、W^X 与 exec 能力（最关键）

### 硬规则（Google 官方，Won't Fix）
> targetSdk **≥ 29** 的应用**不能**对 `/data/data/<pkg>/`（含 `files/`）内的文件调用 `execve()`。
> 机制是 SELinux `neverallow`（`app_neverallows.te`），不是普通权限位——chmod 0o700 也没用。

```
neverallow {
  all_untrusted_apps
  -untrusted_app_25
  -untrusted_app_27
  -runas_app
} { app_data_file privapp_data_file }:file execute_no_trans;
```
来源：
- https://developer.android.com/about/versions/10/behavior-changes-10 （"Removed execute permission for app home directory"）
- https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te
- https://issuetracker.google.com/issues/128554619 （Won't Fix，官方给出 nativeLibraryDir 替代方案）

### 两条可行路线

| 路线 | 做法 | 代价 |
| --- | --- | --- |
| **A. targetSdk=28（termux-app 路线）** | 保持 `targetSdkVersion 28`，系统把它放进 `untrusted_app_27` 域（被 neverallow 显式豁免），可自由 exec `$PREFIX` 里的文件 | **无法上架 Google Play**，只能侧载/F-Droid/GitHub |
| **B. targetSdk≥35（Play 路线）** | 可执行文件放 `jniLibs/arm64-v8a/lib*.so`，安装后由系统解到**只读**的 `nativeLibraryDir`（`/data/app/<pkg>/lib/arm64/`），从那里 exec；可写数据放 `files/` | 需要「执行入口放 jniLibs + 数据放 files」的混合架构；不能把整个可写 rootfs 放在可执行位置 |

### 本项目的选择：**路线 A（targetSdk=28）**
理由：自用，不需上架 Play；Hermes 需要完整可写的 Termux 环境（`pkg install`、写 `$PREFIX/var`），路线 B 的只读限制会让 Termux 生态基本失效。

**已修正 `app/build.gradle`：`targetSdk 28`**（原为 35，错误）。
- `compileSdk` 可用最新（termux-app 是 `compileSdk=36` + `targetSdk=28`）。
- 需 `minSdk 24` 起（Termux bootstrap 要求 Android 7+）。

---

## 二、bootstrap 的打包机制（termux-app 的真实做法）

**`libtermux-bootstrap.so` 不是「zip 改名的 .so」，而是把 zip 字节用 `.incbin` 内联进一个 ELF 的 `.rodata` 节**：

- 源文件：`app/src/main/cpp/termux-bootstrap-zip.S`（`.incbin "bootstrap-aarch64.zip"`，导出符号 `blob` / `blob_size`）
- JNI：`app/src/main/cpp/termux-bootstrap.c` → `Java_com_termux_app_TermuxInstaller_getZip`
- 打包位置：`app/src/main/cpp/`（编译进 native lib），**不是 assets**
- 好处：系统按 ABI 只解压对应架构；规避 assets 的压缩与 exec 限制

**必开开关**：`packagingOptions.jniLibs.useLegacyPackaging = true`（等价老版 `android:extractNativeLibs="true"`），保证 `.so` 被解压到磁盘而非留在 APK 内 mmap。

### 简化做法（本项目采用）
不写 `.S`/JNI，直接**把 zip 改名为 `libtermux-bootstrap.so` 放进 `jniLibs/arm64-v8a/`**。
- Android 会把 `lib/<abi>/*.so` 原样解压到 `nativeLibraryDir`
- 应用从 `context.getApplicationInfo().nativeLibraryDir + "/libtermux-bootstrap.so"` 读取，用 `ZipFile` 解压
- 与 termux-app 效果等价，实现更简单（已写入 `BootstrapInstaller.java`）

---

## 三、bootstrap 内容（本机实测，2026-10-04）

### 官方默认 bootstrap
- URL：`https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip`
- 大小 **32.8 MB**，3774 文件，解压 ~90 MB
- **不含** python / node / git / ripgrep / ffmpeg / clang —— 只有 apt/pkg + 基础工具

### 本项目定制的 bootstrap（CI 已产出并验证）
- 用 `generate-bootstraps.sh --architectures aarch64 --add python --add git --add ripgrep --add ffmpeg --add nodejs-lts`
- 大小 **203 MB**，**15226 文件**，解压后 **616 MB**
- 实测含：`bin/python3.14`、`lib/libpython3.14.so`、`bin/node`、`bin/git`、`bin/rg`、`bin/ffmpeg` ✅
- 实测不含：`clang`、`rustc`（需另行 `--add clang --add rust`，或首次启动时 pkg install）

CI 产物：`SleepSheepQwQ/hermes-android` release `bootstrap-20261004-2`

---

## 四、CI 中构建 Hermes venv（方案 C）的可行做法

### 已实证的先例
`camillanapoles/termux-wheel-forge` —— 2026-09 在 GitHub Actions 上跑通 **QEMU arm64 + termux-docker**，产出 Python 3.14 的 `android_arm64_v8a` wheel。

确切配方（已写入 `build-payload.yml`）：
```yaml
- uses: docker/setup-qemu-action@v3
  with: { platforms: arm64 }
- run: docker run --platform linux/arm64 termux/termux-docker:latest bash /work/script.sh
```

### 已知坑（必读）
1. **镜像 tag 有 bug**：所有 tag 被标为 `linux/amd64`，必须显式 `--platform linux/arm64`
2. **Actions 覆盖 ENTRYPOINT**：容器内命令可能需 `/entrypoint.sh` 前缀
3. **容器内无 `/etc/os-release`** → `actions/checkout` 不工作，**改用 `git clone`**
4. **`upload-artifact` 在容器场景不工作** → 用 `docker cp` 拷出产物
5. **QEMU 下极慢**：bootstrap 单步 300–390s；warm cache 可降到 ~148s
6. **挂载权限**：容器内非 root、uid≠runner，挂载目录须 `chmod -R a+rwX`
7. **uv 必须用 musl 静态 aarch64 版**（gnu 版在 bionic 上跑不了）；`uv venv --python python3` 必须指向系统解释器

### 原生扩展的现实
- Rust 扩展（`pydantic-core`/`jiter`/`cryptography`）在 QEMU 下编译**很慢**，且 termux-wheel-forge 作者明说 **maturin/Rust 后端尚未打通**
- 备选：`Eutalix/android-pydantic-core` 已发布 Android wheel（但**最高 Py3.13，无 3.14**）
- 备选：`Yizutt/termux-wheels` 用 NDK + rust-android target + maturin **交叉编译**（不走 QEMU，更快）

### 更稳的务实方案（建议）
**不预构建 venv，改为首次启动时在设备上装**：
1. 定制 bootstrap 里加 `--add clang --add rust --add pkg-config --add python-pip`（把编译工具链一起打包）
2. App 首次启动解压 bootstrap 后，在设备上跑 `pkg install` + `pip install -e '.[termux]'`
3. 好处：原生扩展在真机上本地编译，无 QEMU 开销、无交叉编译坑
4. 代价：首次启动需联网 + 若干分钟编译

**或者**：直接把本机这个**已经装好的 venv（88 MB 修剪后）**打包进去——它是 aarch64-linux-android ABI，与目标设备完全一致（同为 Termux aarch64 + Python 3.14）。

---

## 五、给本项目的最优路径

1. **Android 侧**：`targetSdk=28`（已修正），bootstrap zip 改名 `libtermux-bootstrap.so` 放 jniLibs
2. **bootstrap**：CI 定制（已跑通），含 python/git/rg/ffmpeg/node + **clang/rust 工具链**
3. **Hermes 源码**：`trim_source.sh`（249→95 MB）
4. **venv**：**优先复用本机已构建的 venv**（88 MB，ABI 完全匹配），CI 的 QEMU 路线作为备选
5. **首次启动**：解压 → 验证 → 配置 API key → 启动

---

## 六、来源汇总

| 主题 | URL |
| --- | --- |
| Android 10 W^X | https://developer.android.com/about/versions/10/behavior-changes-10 |
| Google Issue（Won't Fix + 替代方案） | https://issuetracker.google.com/issues/128554619 |
| SELinux neverallow | https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te |
| termux-app targetSdk=28 | https://github.com/termux/termux-app/blob/master/gradle.properties |
| termux-packages bootstrap releases | https://github.com/termux/termux-packages/releases/latest |
| generate-bootstraps.sh | https://github.com/termux/termux-packages/blob/master/scripts/generate-bootstraps.sh |
| QEMU+Termux CI 先例 | https://github.com/camillanapoles/termux-wheel-forge |
| NDK 交叉编译 wheel | https://github.com/Yizutt/termux-wheels |
| pydantic-core Android wheel | https://github.com/Eutalix/android-pydantic-core |
| wrap.sh / useLegacyPackaging | https://developer.android.com/ndk/guides/wrap-script |
| Play target API 要求 | https://support.google.com/googleplay/android-developer/answer/11926878 |
