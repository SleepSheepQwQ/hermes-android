# CI/CD 约束与设计（实测依据）

日期：2026-10-04 · 来源：调研 + 实测

## 一、GitHub 资源约束（决定流水线结构）

| 资源 | 限制 | 对本项目的影响 |
| --- | --- | --- |
| **Actions Artifacts 配额** | Free 计划 **500 MB 总量** | ⚠️ bootstrap(203MB)+payload(~300MB) 会瞬间打爆 → **产物必须走 Release** |
| Artifact 单文件 | 5 GB | 无压力 |
| **Release asset** | 单文件 **< 2 GiB**，无总量/带宽限制 | ✅ 700MB 产物走这里 |
| Actions Cache | 默认 **10 GB/仓库**，7 天未访问即删 | 用于 uv/工具链缓存 |
| **ubuntu-latest 磁盘** | 约 **14 GB** 可用 | ⚠️ 解压 bootstrap 616MB + payload 512MB + Docker 镜像 → **必须 free-disk-space** |
| ubuntu-latest 内存 | 16 GB（private 仓库 8 GB） | Gradle 可给 6 GB |

### 已落地的应对

1. **artifact retention-days: 1**（三个 workflow 全部改为 1 天）——仅作 job 间传递
2. **产物一律发布到 Release**（`gh release create/upload --clobber`）
3. **`jlumbroso/free-disk-space@main`** 加在 build-payload（最耗盘的 job）最前面
4. **Gradle 内存**：`-Xmx6144m -XX:MaxMetaspaceSize=1024m`（原 4g 偏小）

## 二、Gradle/AGP 调优

```properties
org.gradle.jvmargs=-Xmx6144m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configureondemand=false
```

- **< 2.5 GB 必 OOM**（nowinandroid 有实测数据），16GB runner 上 6GB 是甜点区
- `useLegacyPackaging = true` 除规避 W^X 外，还能**减少约 50% APK 体积**
- 大量小文件（1.5 万+）进 zip 时注意 zip64 / `Too many entries` 报错

## 三、冒烟测试（`scripts/smoke_test.sh`）

无真机验证产物完整性。两种模式：

```bash
bash scripts/smoke_test.sh bootstrap bootstrap-aarch64.zip bootstrap-aarch64.zip.sha256
bash scripts/smoke_test.sh payload   hermes-payload.tar.zst
```

检查项：

| 模式 | 检查 |
| --- | --- |
| bootstrap | sha256、条目数区间、关键二进制（python3.14/bash/tar + 软链 python3/sh/ls）、定制工具（node/git/rg/ffmpeg）、关键库（libpython3.14.so / libandroid-support.so）、SYMLINKS.txt |
| payload | tar 可读、sealed 结构（manifest.json + hermes-src/ + venv/ + tools/）、**manifest 三个必需字段**（repo/venv/store，缺则 KeyError）、**editable 绝对路径泄漏检查**、payload 内 import 测试 |

**本机实测**（对真实 CI 产物）：

```
==> 冒烟测试: bootstrap (bootstrap-aarch64.zip)
  PASS  条目数 15225
  PASS  bin/python3.14 存在
  PASS  bin/python3 / sh / ls 存在（文件或软链）
  PASS  bin/node / git / rg / ffmpeg 存在（定制）
  PASS  lib/libpython3.14.so / libandroid-support.so 存在
==> 结果: 15 通过, 0 失败
```

> **踩坑**：`python3`/`sh`/`ls` 在 zip 里是**软链**（记录在 SYMLINKS.txt），
> 不出现为文件条目。初版脚本只查文件列表导致误报 FAIL。
> 深度解压测试默认关闭（手机端 616MB 解压会 OOM），CI 里可设 `SMOKE_DEEP=1`。

## 四、签名与侧载

### 自签流程
1. `keytool -genkeypair -v -keystore hermes.keystore -alias hermes -keyalg RSA -keysize 4096 -validity 10000`
2. base64 存 GitHub Secret（`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`）
3. CI 里解码到 `$RUNNER_TEMP`，Gradle `signingConfigs` 从**环境变量**读
4. `.gitignore` 排除 `*.keystore` `*.jks`，并加 CI 步骤扫描误提交

### targetSdk=28 的侧载事实
- **Android 14/15 的 low-SDK 拦截门槛是 API 23/24**，28 **不在拦截范围**，可正常侧载 ✅
- 真正障碍是 **Play Protect**：侧载时可能弹「为旧版 Android 构建」提示 → 可「仍要安装」/临时关扫描/ADB 安装
- **无法上架 Google Play**（Play 要求 targetSdk ≥ 35/36）——本项目自用，接受
- 开启 **v1 + v2 + v3 签名**（老设备可能只认 v1）

## 五、首次启动体验

- 解压 1.5–3 万文件预计 **30 秒–3 分钟**，是主要瓶颈
- **必须用前台 Service**（`dataSync` 类型；Android 15 上限 6h，足够），10 秒内 `startForeground`
- Android 13+ 需运行时申请 `POST_NOTIFICATIONS`
- 增量解压 + sentinel 幂等（已实现在 `BootstrapInstaller`：staging + 原子 rename + 标记文件）
- ⚠️ **未验证项**：把数据块伪装成多个 `lib*.so` 在**安装期**由系统预解压——合法性未经实测，需 POC；保留 assets 解压 fallback

## 六、流水线结构（当前）

```
build-bootstrap.yml   产出定制 bootstrap（generate-bootstraps.sh，无 Docker）
       ↓ Release: bootstrap-YYYYMMDD-N
build-payload.yml     QEMU + termux-docker 构建 venv/payload
       ↓ Release: payload-YYYYMMDD-N
build-apk.yml         组合 bootstrap + payload → assembleDebug/Release
       ↓ Release: APK
```

**注意**：build-payload 依赖 QEMU 模拟 aarch64，**很慢**（bootstrap 单步 300–390s）。
若嫌慢，替代路径是**直接复用本机已构建好的 payload**（已验证自包含，docs/10）。

## 七、来源

- Artifacts/Cache 配额：GitHub Actions 官方文档
- runner 规格：https://docs.github.com/en/actions/using-github-hosted-runners/about-github-hosted-runners
- Gradle 内存实测：nowinandroid / gradle-profiler 社区数据
- Play target API 要求：https://support.google.com/googleplay/android-developer/answer/11926878
- Play Protect 侧载提示：https://developers.google.com/android/play-protect/warning-dev-guidance
