# 打包架构与 CI 设计

## 1. 总体架构

```
┌─────────────────────────────────────────────┐
│  Hermes APK                                  │
│                                              │
│  ┌──────────────┐   ┌────────────────────┐  │
│  │ Android UI   │──▶│ 内置终端 (Terminal) │  │
│  │ (Kotlin)     │   │ + 文件/日志查看     │  │
│  └──────┬───────┘   └─────────┬──────────┘  │
│         │                     │             │
│         │ 首次启动解压         │ 执行 hermes  │
│         ▼                     ▼             │
│  ┌──────────────────────────────────────┐   │
│  │ 应用私有目录 /data/data/<pkg>/files/  │   │
│  │  ├ usr/       ← Termux bootstrap     │   │
│  │  ├ home/      ← HOME，含 .hermes      │   │
│  │  └ opt/hermes-src/  ← 裁剪后源码      │   │
│  │     opt/hermes-venv/ ← 裁剪后 venv    │   │
│  └──────────────────────────────────────┘   │
│                                              │
│  jniLibs/arm64-v8a/libtermux-bootstrap.so   │ ← bootstrap 压缩包伪装成 .so
└─────────────────────────────────────────────┘
```

## 2. 为什么 bootstrap 要伪装成 `lib*.so`

Android 安装时会：
- 解压 `lib/<abi>/*.so` 到 `nativeLibraryDir`（**不压缩、可直接执行/映射**）
- 但 assets/ 会被压缩，且从 assets 解压可执行文件受限（API 29+ 禁 `execve` of writable-app-data 之外的路径）

termux-app 正是用 `libtermux-bootstrap.so` 承载 bootstrap zip。我们沿用同一机制：
1. 把 bootstrap 压缩包命名为 `libtermux-bootstrap.so` 放进 `app/src/main/jniLibs/arm64-v8a/`
2. `android { packaging { jniLibs { useLegacyPackaging = true } } }` 确保不被再次压缩
3. 首次启动从 `applicationInfo.nativeLibraryDir/libtermux-bootstrap.so` 读出 zip，解压到 `files/usr`

Hermes 源码与 venv 同样处理：打成 `libhermes-payload.so`，首次启动解压到 `files/opt/`。

## 3. 构建流水线

```
GitHub Actions (ubuntu-latest)
│
├─ Job A: build-bootstrap        aarch64 Termux 环境
│   1. 拉 termux-packages
│   2. ./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures aarch64
│      （产出 bootstrap-aarch64.zip，含 python/git/rg/ffmpeg/nodejs）
│   3. 上传 artifact: bootstrap-aarch64.zip
│
├─ Job B: build-payload          Termux 内构建
│   1. 用 termux-docker 镜像（或 job A 的 bootstrap）起 aarch64 环境
│   2. git clone --depth 1 hermes-agent @ 指定 commit
│   3. 应用 patches/*.patch
│   4. python -m venv venv && pip install -e '.[termux]'
│   5. scripts/trim_venv.sh venv
│   6. scripts/trim_source.sh src
│   7. tar czf hermes-payload.tar.gz src venv
│   8. 上传 artifact: hermes-payload.tar.gz
│
└─ Job C: build-apk             需要 A + B
    1. ./gradlew assembleRelease
       - 把 bootstrap-aarch64.zip → jniLibs/arm64-v8a/libtermux-bootstrap.so
       - 把 hermes-payload.tar.gz  → jniLibs/arm64-v8a/libhermes-payload.so
    2. 签名（debug key 或 CI secret 里的 keystore）
    3. 上传 artifact: hermes-<version>-arm64.apk
    4. （可选）创建 GitHub Release 并附上 APK
```

## 4. 关键决策与理由

| 决策 | 理由 |
| --- | --- |
| 只出 **arm64-v8a** | 目标机 MT6985 是 arm64；多 ABI 会让 APK 体积翻倍。armeabi-v7a 现代手机已不需要。 |
| Job B 用 **Termux 环境**而非普通 Ubuntu | 原生依赖（`pydantic-core`/`jiter`/`cryptography`）必须链 bionic libc。Ubuntu 的 manylinux wheel 在 Android 上 ABI 不兼容。 |
| 源码**不 fork**，用 `patches/` 叠加 | 便于持续跟进上游；当前只需 2 个补丁（见下） |
| **不预置**任何 API key | 用户首次启动自行配置，凭证不进 APK |
| 用 `useLegacyPackaging = true` | 保证 `lib*.so` 不被二次压缩，可直接读取 |

### 必需的 Termux 补丁（`patches/`）

1. `0001-psutil-optional.patch` — `tools/environments/file_sync.py`：psutil 在 Termux 上编不出，改成可选导入。
2. `0002-pm-install-pin-only.patch` — `pm/install.py`：pin-only 行无 `sha256` 时不再 KeyError。

两个补丁内容已从本机 checkout 的 `git diff` 中提取，见 `patches/`。

## 5. 构建依赖（CI 侧）

- Docker（termux-packages 的 build 脚本依赖）
- Android SDK + Build Tools + NDK（Job C）
- JDK 17（AGP 要求）
- Gradle（用 wrapper，仓库内自带）

## 6. 风险与未决项

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| bootstrap 太大 | APK 可能 >150 MB | 裁剪 bootstrap（去掉不用的 pkg、文档）；或用 split APK |
| Termux bootstrap 与未 root 设备兼容 | Android 10+ 对 `execve` 应用私有目录有限制 | termux-app 已验证可行（它的 bootstrap 就是这么跑的），沿用同样路径 |
| Hermes 的 `terminal` 工具需要真实 shell 环境 | 若 PATH/环境变量不对，工具会失效 | 首次启动时在 UI 侧写好环境变量并生成 `hermes` 包装脚本 |
| 首次启动解压耗时 | 用户等待 | 显示进度条；解压后做一次 `hermes doctor` 自检 |
| Gradle/AGP 版本与构建机 JDK 不匹配 | CI 失败 | 固定 AGP 8.x + JDK 17 |

## 7. 里程碑

1. **M1** — bootstrap zip 产出（Job A 跑通）
2. **M2** — payload tar 产出（Job B 跑通，体积 ≤ 200 MB）
3. **M3** — 空壳 App 能解压 bootstrap 并起 shell
4. **M4** — App 内能跑 `hermes --version`
5. **M5** — App 内能正常对话（配置 API key 后）
