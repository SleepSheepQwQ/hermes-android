# 端到端运行验证（真机路径，本机实测）

日期：2026-10-04 · **首次证明 payload 能在 APK 路径下真正跑起来**

## 方法

本机（Termux，aarch64，有 KernelSU root）就是目标架构。用 **符号链接**把已解压的
bootstrap 与 payload 挂到 APK 的真实路径上，按 `BootstrapInstaller.hermesEnv()`
的逻辑构造环境变量，跑 `hermes`。不做 600MB 拷贝（省磁盘、避免发烫）。

```bash
PKG=com.nousresearch.hermesandroid
mkdir -p /data/data/$PKG/files/home/.hermes
ln -s .../_apk-size/core      /data/data/$PKG/files/usr
ln -s .../_apk-size/payload/x /data/data/$PKG/files/opt
```

## 发现并修复的两个真实缺陷

### 缺陷 1：console script 的 shebang 指向 bootstrap python（阻断级）

**现象**：
```
$ venv/bin/hermes --version
ModuleNotFoundError: No module named 'hermes_cli'
```
但同一环境下 `venv/bin/python3.14 -c "import hermes_cli"` **正常**。

**根因**：pip 生成的 `venv/bin/hermes` shebang 是
`#!/data/data/com.nousresearch.hermesandroid/files/usr/bin/python`——
**bootstrap 的基础 python**，它不激活 venv 的 site-packages，
所以 editable 的 `.pth` finder 没被加载 → `hermes_cli` 找不到。

**修复**（`fix_python_paths.sh` 第 5 节）：把 console script 改成 POSIX shell 包装，
显式用 venv 自己的解释器：
```sh
#!/bin/sh
'''exec' "$(dirname "$(readlink -f "$0")")/python3.14" "$0" "$@"
'''
```
- 用 venv python → site-packages 正确
- `$(dirname $0)` 运行时解析 → **完全免疫路径搬迁**
- 与 Hermes 官方 `scripts/build/launchers.py` 的形态一致

**实测结果**：
```
Hermes Agent vunknown (2026.9.24)
Install directory: .../payload/x/hermes-src
Python: 3.14.6
OpenAI SDK: 2.24.0
```

### 缺陷 2：软链失败一刀切太激进（会导致裁剪版 bootstrap 装不上）

**现象**：裁剪版 bootstrap（core，2223 条目）的 1551 条软链里
**1032 条失败**。原实现 `fail > 0` 就抛异常中止安装。

**根因**：失败的**全部**是文档与头文件：
```
share/man: 914
share/doc: 89
include/ncursesw: 11
include/ncurses: 11
libexec/installed-tests: 3
include/*.h: 4
```
这些对运行毫无影响（裁剪版没有对应的目标文件，软链自然建不起来）。

**修复**（`BootstrapInstaller.rebuildSymlinks`）：不再一刀切，
只强制**关键白名单**：
- `bin/sh`、`bin/bash`
- `lib/libandroid-support.so`
- `bin/python*`（通配探测，版本号会变）

其余失败容忍并记录日志。

## 验证通过的项

| 验证 | 结果 |
|---|---|
| bootstrap 的 python 直接运行 | ✅ `Python 3.14.6` |
| venv 的 python 在 APK 路径下运行 | ✅ `Python 3.14.6`（证明 `pyvenv.cfg home=` 重写正确） |
| `import hermes_constants` | ✅ `get_hermes_home()` → APK 路径（证明 editable 重定位生效） |
| `import hermes_cli` | ✅（补齐 LD_LIBRARY_PATH 后） |
| `hermes --version` | ✅ 完整输出 |
| 关键软链重建 | ✅ `sh→dash`、`python→python3.14` |
| 软链失败分类 | ✅ 全部为文档/头文件，无害 |

## 局限

- 用**符号链接**模拟布局，**不能**证明「从真实 `files/` 目录 execve」这一步
  （那需要 targetSdk=28 的真机安装，符号链接会改变 `/proc/self/exe` 解析）。
- 未验证：网络连通性、Hermes 首次初始化的下载行为、`~/.hermes` 目录创建。

## 复现

```bash
bash scripts/e2e_verify.sh
```
（脚本会搭布局、按 SYMLINKS.txt 重建软链、构造 env、跑 hermes）
