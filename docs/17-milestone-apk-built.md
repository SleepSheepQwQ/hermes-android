# 里程碑：APK 构建成功

日期：2026-10-04 · **首个可安装 APK 产出**

## 产物

| 项 | 值 |
|---|---|
| Release tag | `apk-20261004-12` |
| 文件 | `app-debug.apk` |
| 体积 | 259,346,426 bytes（**247.3 MB**） |
| SHA-256 | `7e80a3b93b0a0211f4d10d437f6ec2852af49751458728f2a126ad454014f90a` |
| 签名 | debug（V2 Signer，cert SHA-256 `be1120...529d`） |
| 依赖 | `bootstrap-20261004-4` + `payload-20261004-5` |
| Gradle | BUILD SUCCESSFUL in 1m 20s |

下载：`https://github.com/SleepSheepQwQ/hermes-android/releases/tag/apk-20261004-12`

## 全部 15 步通过

```
1  Set up job                          success
2  Run actions/checkout@v4             success
3  Free disk space                     success
4  Resolve release tags                success
5  Download bootstrap and payload      success
6  Smoke test downloaded artifacts     success
7  Place payloads into jniLibs         success
8  Set up JDK 17                       success
9  Set up Gradle                       success
10 Set up Android SDK                  success
11 Install SDK components              success
12 Build APK                           success
13 Verify APK signature                success
14 Upload APK (short-lived)            success
15 Publish APK to Release              success
```

## 构建期的关键断言（都已验证通过）

- `bootstrap=212629815 bytes, payload=44456674 bytes`（体积下限断言）
- `OK: bootstrap zip 结构完整`（`zipfile.testzip()`）
- `OK: 伪装 .so 与原始 bootstrap zip 字节一致`（sha256 比对，防 AGP 误处理）
- `PASS venv/bin/python 软链已重写到 APK 路径`
- `PASS pyvenv.cfg home= 已重写到 APK 路径`

## 打通路上修掉的 CI bug

1. **`gh release list` 列序**：输出第一列是**描述**不是 tag。
   `awk '{print $1}'` 取到 `bootstrap` 而非 `bootstrap-20261004-4`，
   导致 tag 解析为空、job 直接失败。改用 `--json tagName --jq`。
2. **bootstrap 冒烟测试找不到脚本**：workflow 只 checkout 了 `termux-packages`，
   `$GITHUB_WORKSPACE/scripts/smoke_test.sh` 不存在（exit 127）。
   补一个本仓库 checkout 到 `self/`。
3. **`android-actions/setup-android@v3` 已失效**：它内部执行 `sdkmanager "tools"`，
   该包已被 Google 从 SDK 仓库移除 → `Failed to find package 'tools'`。
   改为直接用 runner 预装的 SDK（`free-disk-space` 已设 `android: false` 保留它）。
4. **bootstrap 缺 `zstd`**：payload 是 `.tar.zst`，设备侧 `tar --zstd` 解压必败。
   `extra_packages` 补 `zstd`（顺带 `ca-certificates` + `openssl`）。
5. **`set -e` + glob 字面量**：`for f in dist/*.tar.gz` 无匹配时字面量传给
   `[ -f ]` 返回 1 → 脚本退出。冒烟 13 项全过却报失败。改 `shopt -s nullglob`。

## 尚未验证（下一步）

- **真机安装与首启**：解压耗时、`Os.symlink` 在 f2fs 上的行为、
  `hermes --version` 能否跑、网络能否通。
- **体积**：247 MB。换 xz 压缩可省约 108 MB（见 `docs/16`）。
- **release 签名**：当前 debug 签名，仅适合自用/测试。

## 环境异常记录

构建期间本机（Termux）**`github.com` 不通、`api.github.com` 通**：
- `git push` 静默失败（exit=0 但远端不更新）
- `git fetch` 直接超时
- `gh` CLI 正常（走 api.github.com）

**对策**：改用 GitHub Contents API 提交文件（`gh api -X PUT .../contents/...`），
全部成功。后续需在网络恢复后 `git fetch && git reset --hard origin/main` 对齐本地。
