# Hermes Agent 依赖分析（Android 打包视角）

调查时间：2026-10-04
调查对象：本机在跑的 Hermes 安装 + 官方源码 checkout

## 1. 当前本机安装形态

| 项 | 值 |
| --- | --- |
| 版本 | `Hermes Agent v0.21.5+4531.g3cf2eb1.dirty (2026.9.24)` |
| 安装方式 | 官方 `install.sh` 的 **Termux 分支**（`install method: unknown`） |
| 源码 checkout | `~/.hermes/hermes-agent`（git 仓库，HEAD = `3cf2eb1c94`，remote = NousResearch/hermes-agent） |
| 代码安装形态 | **editable 安装**（`__editable__.hermes_agent-0.0.0.pth` → 指向 checkout） |
| Python | 3.14.6（Termux 的 `python`；注意 checkout 里 `.python-version` 写 3.14，系统另装了 python3.13 给 TUR/上代用） |
| venv | `~/.hermes/installs/07e6df5db080b527/environments/5d51e41ebd3a413f8d01579a7d4f63cc/venv` |
| venv 体积 | **233 MB**（site-packages 216 个条目）；workspace 另 96 MB |
| pip | venv 内**无 pip**（官方 installer 用自身 PM 管理，不落 pip） |
| 本地改动 | 仅 2 个文件有 patch：`pm/install.py`（pin-only 行无 sha256 时不崩）、`tools/environments/file_sync.py`（psutil 在 Termux 上不可用时的降级）。这是**为跑通 Termux 必需的补丁**，打包必须带上。 |

**打包含义**：editable 安装不能直接冻结成可分发 wheel。要么 (a) 把 checkout 源码 + venv 一起塞进 APK，要么 (b) 在 CI 里把源码装成非 editable 再打包。方案采用 (a)，因为 termux 上源码是主要资产，且插件/技能是目录形式。

## 2. 依赖分层：哪些能在 Android 上装

官方 pyproject 通过 extras 分层。已确认 extras 共 60 个，与 Android 相关的关键分组：

- **`termux`**（官方「可靠新装」基线）= `python-telegram-bot[webhooks]` + `hermes-agent[cron]` + `[mcp]` + `[honcho]` + `[acp]`。这才是手机端能装干净的集合。
- **`termux-all`**（best-effort）= `termux` + `[google]` + `[homeassistant]` + `[sms]` + `[web]` + `[pty]`。
- **`all`**（桌面/服务器）**不能**在 Android 上装：包含 `uvloop`（libuv 的 ./configure 在 bionic 上跑不起来）、`voice`/`faster-whisper → ctranslate2`（无 Android wheel）。

安装命令（官方给的 Termux 路径）：
```bash
pkg install -y git python clang rust make pkg-config libffi openssl nodejs ripgrep ffmpeg
export ANDROID_API_LEVEL="$(getprop ro.build.version.sdk)"
python -m venv venv
python -m pip install -e '.[termux]' -c constraints-termux.txt
```

> 注：本机 checkout 里**没有** `constraints-termux.txt`（`wc -l` 返回空/文件不存在）。安装脚本在新版本里已改为 `.[termux-all]` 或 `.[termux]`，constraints 文件不再随仓库发。CI 里不应假设该文件存在。

## 3. 原生扩展 / 需要编译的依赖（打包风险点）

这些是纯内置在 venv 里的 `.so`，**必须**与目标 ABI 匹配（aarch64-linux-android）：

- `_cffi_backend`、`_pillow_heif`（PIL 的 HEIF 支持）——本机已是 `cpython-314-aarch64-linux-android.so`
- `cffi`、`cryptography`、`pillow`
- `psutil` —— **本机装不上**（源码已 patch 成可选导入），打包时要确保 Hermes 不硬依赖它
- `jiter`（Rust/maturin 构建，需要 `ANDROID_API_LEVEL`）——本机已装好，说明 Termux 的 rust+clang 能编
- `pydantic-core`、`regex`、`httptools`、`uvicorn`（无 uvloop，退回 stdlib asyncio）

**结论**：所有原生扩展都能在 Termux aarch64 上用 `clang 21 + rust 1.98` 本地编译成功——本机 venv 就是活证据。这决定了 CI 里也必须用 Termux 环境编译，**不能**用普通 Ubuntu runner（glibc 的 manylinux wheel 在 bionic/Android 上 ABI 不兼容）。

## 4. 外部工具依赖（非 Python）

| 工具 | 用途 | Android 侧状态 |
| --- | --- | --- |
| git | 更新/插件/技能 | Termux `git` 2.55 |
| ripgrep | `search_files` 工具 | Termux `ripgrep` 15.2 |
| ffmpeg | 音频/语音转码 | Termux `ffmpeg` 8.1.3 |
| nodejs + npm | 浏览器工具、部分 MCP | Termux `nodejs` 26.4 |
| clang / rust / make / pkg-config / libffi / openssl | 编译原生依赖 | 全部已装 |
| agent-browser + Chromium | 本地浏览器自动化 | **不打包**（体积大，Android 实验性）；云端 browser 后端只需 nodejs |

## 5. 与打包相关的其他事实

- **磁盘**：`/data` 225 G，已用 139 G，剩 86 G。APK 里塞 bootstrap（~80–150 MB 解压后）+ venv（233 MB）+ 源码（1.7 G 含 .git，去 .git 约 600 MB）——**必须裁剪**，见 `02-runtime-trim.md`。
- **.git 占 1.1 G**，绝不该进 APK。源码瘦身第一步就是剔除 vcs 目录与测试/评测目录。
- 会话/状态数据（`state.db` 29 MB、`sessions/`、`logs/`）属于**用户数据**，不打包，首次启动时在 `$PREFIX/../home/.hermes` 里新建。
- `HERMES_HOME` 环境变量可指定用户数据目录，打包后默认指向 Termux home 下的 `.hermes`。

## 6. 待办（依赖维度）

- [ ] 在 CI 里跑通 `pip install -e '.[termux]'`（或 `termux-all`）并生成可复制的 venv
- [ ] 确认 `psutil` 缺失时 Hermes 能正常启动（本机已能跑，源码 patch 已覆盖）
- [ ] 统计裁剪后源码 + venv + bootstrap 的总体积，确定 APK 尺寸上限
- [ ] 决定 extras 档位：`termux`（最小可靠）还是 `termux-all`（+google/homeassistant/sms/web/pty）
