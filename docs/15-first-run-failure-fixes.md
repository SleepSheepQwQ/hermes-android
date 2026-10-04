# 首次启动失败模式修复清单

日期：2026-10-04 · 基于子代理审查（deleg_42c1ba2b）+ 实证修复

## 背景

payload 构建已能成功产出（61MB，17173 条目，13 项冒烟全过），但**设备侧首次启动**
仍存在多个"必崩点"。本文记录已修复项，供回归时对照。

## 头号必崩点

### 1. bootstrap 缺 zstd → payload 解压必败

- **现象**：payload 用 `tar --zstd` 打包成 `.tar.zst`；`PayloadInstaller` 调
  bootstrap 的 `bin/tar --zstd -xf`。bootstrap 的 `extra_packages` 里没有 `zstd`，
  tar 会报 `zstd: not found`，解压直接失败。
- **修复**：`build-bootstrap.yml` 的 `extra_packages` 补 `zstd`，
  顺带补 `ca-certificates` + `openssl`（保证 HTTPS API 可用）。
- **代价**：bootstrap 需重新构建（已触发）。

### 2. `bash -l` 被 bootstrap profile 覆盖 env

- **现象**：`MainActivity` 原用 `bash -l` 启动。bootstrap 的 `/etc/profile`
  有大量 `com.termux` 硬编码分支，会覆盖我们精心设好的 `PATH` / `LD_LIBRARY_PATH`
  → `libpython` 找不到 → 进程起不来。
- **修复**：改 `bash --noprofile --norc -c 'exec <hermes> --version'`。
  我们已显式提供全部必要变量，不需要 profile。

### 3. 软链静默失败

- **现象**：`rebuildSymlinks` 用 `catch (Exception)` 吞掉每条失败，`fail<=5` 才打日志，
  且**不失败整个安装**。1551 条软链若全失败，`.bootstrap-ok` 照样写入 →
  用户点"运行"时 `bin/sh` 断链 → 起不来。
- **附带**：`Files.createSymbolicLink` 是 **API 26+**，minSdk=24 在 Android 7.x
  上抛 `NoSuchMethodError`。
- **修复**：
  - 改用 `android.system.Os.symlink`（API 21+）。
  - 关键软链白名单（`bin/sh`、`bin/bash`、`lib/libandroid-support.so`）缺失即抛异常。
  - `fail > 0` 整体失败，不写安装标记。

## 严重（运行期崩溃 / 路径失效）

### 4. LD_LIBRARY_PATH 缺 lib-dynload

- venv 的 C 扩展（`_struct`/`_ctypes`/`select`/`_hashlib`…）在
  `venv/lib/python3.X/lib-dynload`，CPython 启动时 dlopen。
- **修复**：`buildLibraryPath()` 动态探测 `python3.X` 并追加 `lib-dynload`；
  managed python 的同类目录也一并扫描。

### 5. HERMES_RUNTIME_DIR 硬编码

- `pm/environments.py::store_root()` 第一优先级读 `HERMES_RUNTIME_DIR`，
  **直接 return、绕过 manifest**。Java 侧硬编码 `opt/tools`，一旦 `manifest["store"]`
  与实际目录名不一致就指向空目录。
- **修复**：新增 `manifestStore()`，从 `opt/manifest.json` 读 `store` 字段，
  失败才回退 `"tools"`。

### 6. rename 前先删旧目录 → 崩溃两头空

- `if (prefix.exists()) deleteRecursively(prefix);` 在 rename 之前执行。
  若此刻进程被杀，旧环境已毁、新的未就位。
- **修复**：旧 prefix 先改名 `.bak` → rename staging → 成功后才删 `.bak`。

### 7. 跳过 second-stage → 无 resolv.conf

- `BootstrapInstaller` 跳过了 `termux-bootstrap-second-stage`（它原本建
  `/etc/resolv.conf`）。没有它 DNS 全废。
- **修复**：解压后显式写 `$PREFIX/etc/resolv.conf`（8.8.8.8 / 1.1.1.1 兜底）
  与 `/etc/hosts`。

### 8. flattenSingleDir 单目录误判

- 原逻辑"顶层只有一个目录就下移"。若 payload 顶层恰好只剩 `tools/`，
  会把 `tools/` 内容错移到顶层 → 结构损坏。
- **修复**：仅当那个唯一子目录**自身含 `manifest.json`** 时才下移。

## 中等

### 9. payload 里 venv 双份

- `cp -a hermes-src` 后又 `cp -a hermes-src/venv venv`，导致 `hermes-src/venv`
  也进 payload：体积翻倍，且旧 `pyvenv.cfg`（构建机绝对路径）可能被优先命中。
- **修复**：`rm -rf "$PAY/hermes-src/venv"`。

### 10. CI 只查体积不查字节完整性

- AGP 的 strip/对齐若误处理伪 `.so`，体积断言查不出来，但设备侧解压必崩。
- **修复**：`build-apk.yml` 加 `zipfile.testzip()` + sha256 逐字节比对。

### 11. smoke test glob 导致 set -e 退出

- `for f in dist/*.tar.zst dist/*.tar.gz` 在无 `.tar.gz` 时，字面量传给 `[ -f ]`
  返回 1 → `set -e` 直接退出。冒烟测试 13 项全过却报失败。
- **修复**：`shopt -s nullglob` 收集到数组再遍历。

## 未修复 / 待验证

- `fix_editable.sh` 的 `parents[4]` 硬编码深度（数据形态变化才爆）。
- `fix_python_paths.sh` 只查前缀字符串、不查软链可解析性。
- 通知权限（Android 13+ `POST_NOTIFICATIONS` 未授权时前台通知不可见，
  用户可能以为"卡住"）。
- 真机实测：解压耗时（估 20–60 秒）、`Os.symlink` 在 f2fs 上的实际行为。
