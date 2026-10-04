# 体积与依赖账（实测）

日期：2026-10-04 · 全部数据来自本机实测

## 一、四大组件

| 组件 | 原始 | 修剪后 | 说明 |
| --- | --- | --- | --- |
| Termux bootstrap（定制） | 203 MB (zip) | — | 解压 616 MB / 15226 文件 |
| Hermes tools（managed） | 186 MB | — | python/node/npm/ffmpeg/ripgrep/uv |
| Hermes 源码 | 631 MB | **95 MB** | `trim_source.sh` 实测 |
| Hermes venv | 233 MB | **88 MB** | `trim_venv.sh` 实测 |

## 二、关键发现：Hermes 自带 managed 工具链

`~/.hermes/tools/` 下是**一套完整的、为 Android/bionic 构建的工具链**：

| 工具 | 版本 | 体积 | 平台标记 |
| --- | --- | --- | --- |
| python | 3.14.7 | 34 MB | `linux-arm64-bionic` |
| node | 26.7.0 | 50 MB | `linux-arm64-bionic` |
| npm | 12.0.2 | 19 MB | `linux-arm64-bionic` |
| ffmpeg | 9.0.1 | 36 MB | `linux-arm64-bionic` |
| ripgrep | 15.2.0 | 5 MB | `linux-arm64-bionic` |
| uv | 0.12.3 | 44 MB | `linux-arm64-bionic` |
| **合计** | | **186 MB** | |

**意义**：不必依赖 bootstrap 里的 python，Hermes 自己会 provision 这些工具（这正是 `install method: unknown` 的 Termux 安装所做的）。但打包时把这 186 MB 一起带上更省事（避免首次启动联网下载）。

## 三、ABI 一致性（已验证）

```
venv 内的原生扩展: _pydantic_core.cpython-314-aarch64-linux-android.so
  ELF: ELF64 / AArch64  ✅ Android ABI
managed python:   python-3.14.7+...-linux-arm64-bionic  ✅ Android/bionic
bootstrap:        aarch64 (Termux)                      ✅
```

三者 ABI 完全一致，可混用。

## 四、路径依赖（关键约束）

managed python 的 ELF 元数据：

```
RUNPATH: /data/data/com.termux/files/usr/lib
NEEDED:  libandroid-support.so
NEEDED:  libpython3.14.so
NEEDED:  libc.so
```

**问题**：RUNPATH **硬编码**了 Termux 的 `$PREFIX` 路径。在独立 APK 里 `$PREFIX` 是 `/data/data/com.nousresearch.hermesandroid/files/usr`，不匹配。

**两个解法**：

### 解法 A（本项目采用）：保持 Termux 目录布局 + LD_LIBRARY_PATH
- APK 内把 bootstrap 解压到 `files/usr`，与 Termux 布局完全一致
- 运行时设 `LD_LIBRARY_PATH=<files/usr/lib>`，覆盖 RUNPATH 找不到的情况
- 已验证 `libpython3.14.so` 和 `libandroid-support.so` **都在 bootstrap 里** ✅
- 无需修改任何二进制

### 解法 B：用 patchelf 改 RUNPATH
- 把 RUNPATH 改成 `$ORIGIN/../lib` 或用 APK 的绝对路径
- 需要对每个 .so 和可执行文件都处理，工作量大
- `patchelf` 在 Termux 上可用（`pkg install patchelf`）

**采用 A**：零修改、风险最低。

## 五、打包后体积预估

```
bootstrap 解压后     616 MB   ┐
Hermes tools         186 MB   │ 应用私有目录（安装后）
源码（修剪）          95 MB   │
venv（修剪）          88 MB   ┘
────────────────────────────
安装后占用           ~985 MB

APK 内（压缩后）：
bootstrap zip        203 MB   （已是压缩包，APK 内不再压缩）
源码 + venv 压缩       需实测（.py 压缩率高，.so 一般）
Hermes tools 压缩     需实测

APK 成品预估          ~500-700 MB
```

**体积偏大**。优化方向：
1. **去掉 bootstrap 里冗余的包**（当前定制含 node/git/ffmpeg，但 Hermes tools 已自带这些）→ 可省 ~100 MB
2. **venv 不打包**，改用 managed python 首次启动时装 → 省 88 MB
3. **Hermes tools 里 uv (44 MB) 可去掉**（运行时不需要）→ 省 44 MB
4. 压缩：源码用 tar.zst，bootstrap 已经是压缩包

**优化后预估 APK ~300-400 MB**。对自用 APK 可接受。

## 六、待办

- [ ] 实测 bootstrap 里哪些包可去掉（对照 Hermes tools 已提供的）
- [ ] 实测源码 + venv 压缩后的确切体积
- [ ] 验证解法 A 在真机上能否加载 managed python（RUNPATH 不匹配时的 LD_LIBRARY_PATH 覆盖效果）
- [ ] 决定 venv 是否打包
