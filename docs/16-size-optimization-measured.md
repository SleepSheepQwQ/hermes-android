# APK 体积优化实测数据

日期：2026-10-04 · 本机 Termux 实测（aarch64）

## 结论先行

**换用 xz 压缩可省约 108MB**：
- bootstrap：212MB（zip/deflate）→ **134MB**（tar.xz）
- payload：44MB（tar.zst）→ **13.7MB**（tar.xz）

两者合计 **256MB → 148MB**，直接让最终 APK 从 ~260MB 降到 ~150MB。

## 压缩格式实测对比

### bootstrap（15226 条目，解压后 616MB）

| 格式 | 体积 | 相对 zip |
|---|---|---|
| zip (deflate) | 212,629,548 | 基准 |
| tar.gz | 207,388,912 | -2.5% |
| tar.zst | 199,856,068 | -6% |
| tar.zst -19 | 150,202,950 | -29% |
| **tar.xz** | **134,553,004** | **-37%** |

### payload（11383 条目，解压后 148MB）

| 格式 | 体积 | 相对 zstd |
|---|---|---|
| tar.zst | 44,456,674 | 基准 |
| tar.gz | 44,425,248 | ≈0 |
| **tar.xz** | **13,713,408** | **-69%** |

**payload 的 xz 收益极大**（3.2 倍），因为 payload 里大量是 Python 源码（`.py`）
与纯文本，xz 的长距离匹配优势明显；而 bootstrap 里是已编译的二进制，收益较小。

### 解压成本（设备侧）

- **zstd**：解压极快（~500MB/s），内存占用低
- **xz**：解压慢（~50-100MB/s），单线程，峰值内存较高

bootstrap 616MB 用 xz 解压估计 **6–12 秒**（zstd 约 2 秒）。
payload 148MB 用 xz 约 **1.5–3 秒**。合计增加约 5–10 秒首启时间——
**对一次性安装可接受**，换 108MB 体积很划算。

## 依赖前提

bootstrap 里**已含** `bin/xz`、`bin/tar`、`bin/zstd`（已核实），
所以设备侧 `tar -xJf` 可用。**但**：当前 payload 用 `tar --zstd`，
若改用 xz 需同步改 `PayloadInstaller` 的调用参数。

## 核心包裁剪（core）

后台实验还做了包裁剪：从 15226 条目裁到 **2223 条目**，
解压体积 616MB → **103MB**，xz 后仅 **24.8MB**。

被移除的 96 个包主要是：`ffmpeg`、`libllvm`、`libicu`、`fontconfig`、
`freetype`、`harfbuzz`、`cairo`、`libjpeg-turbo`、`inetutils`、`ed`、
`dos2unix`、`command-not-found`、`debianutils` 等。

**保留**：`python3.14`（+libpython3.14.so、libcrypto/libssl）、`bash`、`git`、
`rg`、`tar`、`xz`、`zstd`。

**注意**：
- `core` 里**没有 `bin/node`**——若 Hermes 的某些工具（如 teams_pipeline 用 ffmpeg、
  某些 JS 工具用 node）需要，会降级或报错。
- `bin/sh` 缺失是**正常**的（它是软链，在 SYMLINKS.txt 里重建）。
- `libicu` 被移除可能影响某些 Python 库的 Unicode 处理（需实测）。

## 未决问题

1. **`libicu` 移除是否影响 Python**：CPython 在 Android 上通常不依赖 ICU，
   但某些第三方库（如 `regex` 的某些构建）可能需要。需真机验证。
2. **`ffmpeg` 移除的影响**：Hermes 的 `teams_pipeline/pipeline.py` 用
   `shutil.which("ffmpeg")`，移除后会走降级分支（如果有）。
3. **xz 解压的峰值内存**：低端机上解压 616MB 的 xz 可能吃 100–200MB 内存，
   需确认应用进程限制（通常 256–512MB）够用。
4. **是否值得为 108MB 增加首启 5–10 秒**：取决于目标用户。

## 建议路线

**分两步走**：
1. **先让 APK 构建跑通**（当前任务），用现有 zip + zstd 载荷。
2. **再做体积优化**：换 xz（省 108MB）+ 裁包（再省 ~80MB），
   逐步验证功能不缺失。

裁包是**风险较高**的优化（可能砍掉运行时需要的东西），
建议先只做 xz 换压缩格式——这是**零功能损失**的纯收益。
