# 关键实测结论：官方 bootstrap 的内容与局限

日期：2026-10-04 · 实测方式：下载官方 release 并解包核对

## 实测数据

```
URL : https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip
实际: .../releases/download/bootstrap-2026.09.27-r1+apt.android-7/bootstrap-aarch64.zip
大小: 32,845,839 字节 (32.8 MB)
条目: 3774 个文件，解压后约 90 MB
顶层: bin/ etc/ include/ lib/ libexec/ share/ tmp/ var/
bin/ 下: 273 个可执行文件
```

## 包含什么

`bootstrap-aarch64.zip` 是 **Termux 的最小根文件系统**，只含包管理器和基础工具：

| 类别 | 内容 |
| --- | --- |
| 包管理 | ✅ `apt`、`apt-get`、`pkg`、`dpkg`、`apt-cache`、`apt-mark` |
| shell | ✅ `bash`、`dash`、`coreutils`（`ls`/`cp`/`cat`…）、`sed`、`grep`、`gawk`、`find`、`tar`、`xz`、`gzip`、`bzip2`、`diff` |
| 网络 | ✅ `curl`、`wget`（wcurl）、`ftp`、`telnet`、`netstat`、`ifconfig` |
| Termux 专有 | ✅ `termux-exec`、`termux-tools`、`termux-am`、`termux-setup-storage`、`termux-open`、`termux-wake-lock`、`termux-change-repo` |
| 其他 | `nano`、`less`、`patch`、`unzip`、`lsof`、`ps`、`top`、`psmisc`、`proot`(android<10) |
| 加密库 | ✅ `libssl.so.3`、`libcrypto.so.3`（353 KB + 5.2 MB） |

## **不**包含什么（关键！）

| 期望 | 实际 |
| --- | --- |
| `python` / `python3.14` | ❌ **没有** |
| `node` / `npm` | ❌ **没有** |
| `git` | ❌ **没有** |
| `ripgrep` (rg) | ❌ **没有** |
| `ffmpeg` | ❌ **没有** |
| `clang` / `rust` | ❌ 没有（编译工具链不在内） |

**含义**：官方 bootstrap 只是「空 Termux」，Hermes 需要的 Python 3.14、git、ripgrep、ffmpeg 都得额外获取。

## 三种补齐方案

### 方案 A：首次启动时用 `pkg install`（推荐给「小 APK」）
- APK 体积最小（bootstrap 仅 33 MB 压缩，APK 可能 ~60–80 MB）
- 首次启动需联网，执行 `pkg install python git ripgrep ffmpeg nodejs`（约 300–500 MB 下载 + 编译原生依赖要 clang/rust，更慢）
- **风险**：Hermes 的原生依赖（`pydantic-core` 等）需要 `clang`/`rust` 才能从源码构建；纯 pkg 不提供预编译 wheel，pip 会现场编译，在手机上耗时 10–40 分钟且可能失败。

### 方案 B：CI 预构建 bootstrap（推荐给「可用 APK」）✅
- 在 CI 里跑 `generate-bootstraps.sh --architectures aarch64 --add python --add git --add ripgrep --add ffmpeg --add nodejs`
- **不需要 Docker、不需要编译**（`generate-bootstraps.sh` 从 apt 镜像拉 deb 解包重打包，1–3 分钟）
- 产出定制的 bootstrap zip（含 Python 等），塞进 APK
- 首次启动只解压，**无需联网、无需编译**

### 方案 C：CI 里连 venv 一起预构建（最终形态）✅✅
- 在方案 B 基础上，额外在 CI 里用 Termux 环境跑一遍 `pip install -e '.[termux]'`，把**已编译好的 venv**一起打进 APK
- 用户首次启动只需解压，直接 `hermes` 可用
- 这是 `docs/03-packaging.md` 描述的完整形态

## 对打包方案的修正

原 `03-packaging.md` 假设「bootstrap 自带 python/git/rg/ffmpeg」——**该假设错误，需按方案 C 修正**：
1. CI 先用 `generate-bootstraps.sh` 产出含 python/nodejs/git/ripgrep/ffmpeg 的定制 bootstrap
2. CI 再在 Termux 环境里构建 Hermes venv（`.[termux]`），跑 `trim_venv.sh`
3. 源码跑 `trim_source.sh`
4. 三者一起打进 APK 的 jniLibs

## generate-bootstraps.sh 支持的选项（已核对源码）

```sh
./scripts/generate-bootstraps.sh \
  --architectures aarch64 \
  --add python \        # 可重复，附加包
  --add git \
  --add ripgrep \
  --add ffmpeg \
  --add nodejs-lts \
  --pm apt              # 包管理器：apt（默认）或 pacman
```
依赖命令：`ar awk curl grep gzip find sed tar xargs xz zip jq`（ubuntu runner 需补装 `ar jq xz-utils zip` 等）。
脚本头部含自检，缺命令会明确报错。

## 待验证

- [ ] `--add` 是否会自动带上依赖（python 的依赖如 libandroid-support、openssl 等）
- [ ] 定制 bootstrap 的体积（含 python/node/ffmpeg 后预计 150–250 MB 压缩）
- [ ] `python` 在 Termux apt 里的实际包名与版本（官方 installer 说 Termux 的 python 是 3.14.x，其中还有 3.13 供旧依赖）
