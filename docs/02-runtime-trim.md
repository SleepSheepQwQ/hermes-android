# 运行环境修剪方案

目标：把「源码 + venv + bootstrap」从原始的 ~870 MB 压到能装进 APK 的规模（目标 ≤ 200 MB 压缩前，APK 成品 ≤ 120 MB）。

数据来源：本机实际测量（2026-10-04），详见 `01-dependencies.md`。

## 1. 体积现状（实测）

| 部件 | 原始 | 说明 |
| --- | --- | --- |
| 源码 checkout | **631 MB** | 已排除 `.git`（`.git` 另占 1098 MB） |
| ├ node_modules | 371 MB | 与内嵌 bootstrap 的 nodejs 重复，删 |
| ├ tests | 62 MB | 运行不需要 |
| ├ apps | 48 MB | 桌面/移动 App 源，Android 打包不需要 |
| ├ website | 28 MB | 文档站 |
| └ evals / contributors / assets | 10 MB | 开发用 |
| venv | **233 MB** | site-packages 216 项 |
| ├ `__pycache__` | 44 MB | 可全部删除（运行时重新生成） |
| ├ googleapiclient + google | 104 MB | 属 `[google]` extra，**不在 `[termux]` 基线内** |
| └ 其余 | ~85 MB | 真实运行依赖 |
| Termux bootstrap | ~80–150 MB | 压缩包，解压后更大；用官方 aarch64 bootstrap |

## 2. 修剪规则

### 2.1 源码侧（`scripts/trim_source.sh`）

删除（保留目录结构，避免 `import` 时包缺失）：

```
.git/            node_modules/     # 见下方注意事项
tests/           evals/
apps/            website/
contributors/    assets/           (若无运行时引用)
*.md 文档（保留 LICENSE）
__pycache__/  *.pyc
```

注意事项：
- **`.git` 必须在 CI 里删**（clone 后立刻删），否则传输/打包时间暴增。源码版本号靠构建时注入 `HERMES_BUILD_COMMIT` 环境变量保留。
- **`node_modules` 删除安全**：内嵌 bootstrap 自带 nodejs，浏览器工具走 `npx` 懒加载；如果之后要离线支持本地浏览器，再把 `agent-browser` 单独打进 bootstrap。
- 删目录前跑一次 `python -c "import <pkg>"` 冒烟测试，确认没有 `__init__.py` 引用这些路径。

### 2.2 venv 侧（`scripts/trim_venv.sh`）

```
1. find -name __pycache__ -type d -exec rm -rf   # 省 44 MB，零风险
2. find -name '*.pyc' -delete
3. 删 googleapiclient/ google/ google_auth* 等 [google] 专属包   # 省 ~104 MB
   前提：本次打包选 termux（不含 google extra）。若选 termux-all，保留。
4. 删测试与文档目录：
   site-packages/**/tests/  **/test/  **/*.dist-info/RECORD 之外的多余元数据
   **/*.h  **/*.c  (源码头文件，运行时不需要)
5. 删 pip/setuptools/wheel 相关（本机 venv 本来就没有 pip）
```

预期结果：**233 MB → ~74 MB**。

### 2.3 三项合一后的目标

```
源码（trim 后）   ~ 110 MB   （631 - 371 - 62 - 48 - 28 - 10 ≈ 112）
venv（trim 后）   ~  74 MB
bootstrap（压缩） ~ 100 MB   （aarch64 bootstrap 压缩包，含 python/node/git/rg/ffmpeg）
────────────────────────────
未压缩合计        ~ 284 MB
APK 成品（压缩后）  目标 ≤ 150 MB
```

> APK 本质是 zip，venv 的 `.py` 压缩率好（约 3:1），`.so` 压缩率一般（约 2.4:1）。这决定了最终 APK 大概落在 100–150 MB。

## 3. 运行环境配置（首次启动）

APK 首次启动时，UI 侧执行等价于以下的初始化（写入 `$PREFIX/../home/.hermes`）：

```bash
# 1. 解压 bootstrap 到应用私有目录，得到 $PREFIX
# 2. 配置 PATH / LD_LIBRARY_PATH / HOME / TMPDIR
export PREFIX=/data/data/<pkg>/files/usr
export HOME=/data/data/<pkg>/files/home
export PATH=$PREFIX/bin:$HOME/.local/bin
export LD_LIBRARY_PATH=$PREFIX/lib
export TMPDIR=$PREFIX/tmp

# 3. hermes 启动器（bootstrap 内 venv 已就位）
ln -sf <bundled-venv>/bin/hermes $PREFIX/bin/hermes

# 4. 首次运行
hermes doctor
```

要点：
- **不要用 Termux 的 `/data/data/com.termux`**，用本 APP 自己的包名目录，与已装 Termux 完全隔离。
- `HERMES_HOME` 默认 `$HOME/.hermes`，首次启动建空目录即可；会话/状态数据不预置。
- 必须预置的内容只有：Hermes 源码、venv、`.env` 模板（API key 由用户首次配置，**不预置任何密钥**）。

## 4. 不预置 / 不打包清单

| 项 | 原因 |
| --- | --- |
| `.env` / API key / `auth.json` | 安全：绝不把凭证打进 APK |
| `state.db` / `sessions/` / `logs/` | 用户数据，运行时新建 |
| `cron/` 任务与 `kanban.db` | 用户数据 |
| agent-browser + Chromium | 体积大，Android 实验性 |
| `voice` / `faster-whisper` / `uvloop` | Android 无 wheel / 编不过 |

## 5. 待办

- [ ] 写 `scripts/trim_source.sh`（纯 shell，本地可跑）
- [ ] 写 `scripts/trim_venv.sh`
- [ ] 实测本机跑一次修剪，核对是否降到 ~74 MB 且 `hermes --version` 仍正常
- [ ] 确认哪些源码目录删除后 `import` 仍通过（冒烟测试）
