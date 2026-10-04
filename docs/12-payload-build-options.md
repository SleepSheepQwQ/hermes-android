# payload 产出方案对比（含被中断代理的抢救发现）

日期：2026-10-04
状态：**调研不完整**——原代理被主动停止（api_calls=18/未完），以下为可确认部分。

---

## 一、已确认的事实（有来源）

### 1. 本机已有可用 venv，可直接打包
本机（Termux aarch64）的 venv 已验证可随 payload 搬迁运行（见 docs/10）。
**这是当前最可靠的 payload 来源**，无需任何 CI 构建。

### 2. GitHub Actions 计费现状（2026）
- **public 仓库**：GitHub-hosted runner **免费**（含 ubuntu-latest 的 x86_64）
- **self-hosted runner**：2026 年起**私有仓库**按 **$0.002/分钟** 收取平台费
  （来源：https://northflank.com/blog/github-pricing-change-self-hosted-alternatives-github-actions）
- 本仓库 `hermes-android` 是 **public**，所以 GitHub-hosted runner 免费 ✅

### 3. arm64 hosted runner
- GitHub 提供 `ubuntu-24.04-arm` 等 arm64 hosted runner（public 仓库免费）
- **但**：它是 Ubuntu arm64（glibc），**不是 Termux/bionic**，因此
  **仍不能直接用于构建 Termux 的 Python wheel**（ABI 不兼容）
- 唯一价值：可以在其中跑 **Docker（无 QEMU）**，比 x86 + QEMU 快很多

### 4. 已知的现成 Android wheel 资源
| 项目 | 内容 | 局限 |
| --- | --- | --- |
| [Eutalix/android-pydantic-core](https://github.com/Eutalix/android-pydantic-core) | 预编译 pydantic-core wheel（ARM64/ARMv7/x86/x86_64） | **最高 Python 3.13，无 3.14** |
| [Yizutt/termux-wheels](https://github.com/Yizutt/termux-wheels) | NDK + rust + maturin 交叉编译 | 需自建 |
| [skoll43/aider-chat-termux-wheels](https://github.com/skoll43/aider-chat-termux-wheels) | aider-chat 的 Termux wheel（aarch64, Py3.12） | 版本旧 |
| [camillanapoles/termux-wheel-forge](https://github.com/camillanapoles/termux-wheel-forge) | QEMU + termux-docker 自动构建 | 作者明说 Rust 后端未打通 |

### 5. Termux 进程被杀问题（self-hosted runner 风险）
Android 12+ 的 **phantom process killer** 会杀后台进程
（`Process completed (signal 9)`），需在开发者选项关闭子进程限制。
来源：https://cosyra.com/guides/termux-signal-9-fix.html

---

## 二、方案对比（按本项目可行性排序）

| 方案 | 可行性 | 耗时 | 可靠性 | 结论 |
| --- | --- | --- | --- | --- |
| **A. 本机构建 payload → 上传 Release** | ✅ 高 | 已构建完成 | 高（已实测可跑） | **✅ 推荐，当前首选** |
| B. QEMU + termux-docker（CI） | ⚠️ 中 | 很慢（300-390s/步） | 低（社区反馈脆弱） | 备选 |
| C. NDK 交叉编译 | ⚠️ 中 | 快 | 需自研 | 仅补缺失 wheel 时用 |
| D. arm64 hosted runner | ❌ 不适用 | — | — | Ubuntu arm64 ≠ Termux ABI |
| E. self-hosted（手机） | ⚠️ 中 | — | 低（后台被杀） | 不推荐 |

---

## 三、本项目的务实结论

**payload 直接复用本机已构建的产物**（方案 A）：

1. 本机 venv 已验证自包含、可搬迁（docs/10 端到端通过）
2. 无需 QEMU、无需交叉编译、无需 NDK
3. 只需上传到 Release，`build-apk.yml` 会自动拉取

```bash
# 本机打包（需用户同意后执行）
bash scripts/make_payload.sh payload <src> <venv> <tools>
tar --zstd -cf hermes-payload.tar.zst -C payload .
gh release create payload-$(date -u +%Y%m%d) \
  --repo SleepSheepQwQ/hermes-android \
  --title "payload $(date -u +%Y%m%d)" \
  hermes-payload.tar.zst
```

**CI 侧只做组装**（bootstrap + payload → APK），不做 Python 构建——
这样避开了 QEMU 慢、termux-docker 脆弱、NDK 交叉编译复杂这三个坑。

---

## 四、待补充（原代理未完成的部分）

- [ ] GitHub Release 上传 500MB+ 文件的具体注意事项（超时/分块/hash）
- [ ] `gh release upload` 在大文件上的实际表现
- [ ] arm64 hosted runner 上跑 termux-docker 的可行性（无 QEMU 加速）
