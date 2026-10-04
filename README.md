# hermes-android

把 [Hermes Agent](https://github.com/NousResearch/hermes-agent) 打包成一个独立的 Android APK。

**当前状态：已产出可安装 APK**（`apk-20261004-12`，247 MB，debug 签名）。

## 目标

用户装一个 APK，点开就能用 Hermes —— 不需要先装 Termux、不需要手工跑安装脚本、不需要联网拉依赖。APK 内部自带：

- Termux bootstrap（aarch64，含 Python 3.14 / git / ripgrep / ffmpeg / nodejs / zstd）
- Hermes 源码（裁剪版）
- 预编译好的 venv（`.[termux]` extras，aarch64-linux-android ABI）

## 打包路线

采用 **Termux-bootstrap 内嵌**方案，而不是 Chaquopy / Kivy / 纯 Python-for-Android：

| 方案 | 为什么不用 |
| --- | --- |
| Chaquopy | 面向单进程 Python 调用，Hermes 需要完整 shell、子进程、`pkg` 生态 |
| Kivy/python-for-android | 沙箱与 Termux 生态不兼容，Hermes 的 terminal/code_execution 工具会失效 |
| 纯 Termux 外链 | 用户仍需自己装 Termux 和包，不算「一个 APP」 |
| **内嵌 Termux bootstrap** | ✅ 复用官方 `termux-packages` 的 aarch64 bootstrap，ABI 一致，Hermes 的 shell 工具体系原样可用 |

架构：APK 的 `jniLibs/arm64-v8a/` 放两个**伪装成 `.so` 的压缩包**
（`libtermux-bootstrap.so` = bootstrap zip、`libhermes-payload.so` = payload tar.zst），
首次启动解压到应用私有目录，再用 `bash --noprofile --norc` 运行 `venv/bin/hermes`。

### 关键设计决策

- **`targetSdk=28`**：Android 10+ 禁止 `untrusted_app` 域从应用私有目录 `execve`
  （W^X neverallow），只有 `targetSdk ≤ 28` 走 `untrusted_app_27` 域才有豁免。
  代价是无法上架 Google Play。
- **载荷伪装成 `.so`**：`assets/` 有压缩/权限限制，`jniLibs` 在安装时被系统解压到
  只读 `nativeLibraryDir`，可直接 `ZipFile` 读取。
- **`useLegacyPackaging=true`**：让系统在安装期解压载荷，规避 W^X。
- **单个大文件而非 1.5 万小文件**：APK 的 zip 不支持 zip64，
  条目数超过 65535 会 `Zip64 required but forbidden`。

## 目录结构

```
hermes-android/
├── app/                  Android 工程（Java）
├── docs/                 调查、决策与实测文档（01–16）
├── scripts/              构建、修剪、重定位、冒烟测试脚本
└── .github/workflows/    三条 CI 流水线
```

## 构建流程

三条流水线，产物各存一个 Release：

1. **`build-bootstrap.yml`** — 用 `termux-packages` 生成定制 aarch64 bootstrap
   → Release `bootstrap-YYYYMMDD-N`（约 212 MB）
2. **`build-payload.yml`** — 在原生 arm64 runner 的 Termux 容器里拉取上游 Hermes、
   修剪源码、建 venv、装 `.[termux]`、重写路径、打包 payload
   → Release `payload-YYYYMMDD-N`（约 44 MB）
3. **`build-apk.yml`** — 取上面两个 Release 的产物塞进 `jniLibs`，Gradle 编译
   → Release `apk-YYYYMMDD-N`（约 247 MB）

前两条可并行（payload 不依赖 bootstrap 产物）；第三条由前两条成功后自动触发。

## 状态

- [x] 仓库初始化
- [x] 依赖调查（`docs/01-dependencies.md`）
- [x] 运行环境修剪与重定位（`docs/02`、`docs/08`、`docs/09`、`docs/13`）
- [x] 三条 CI 流水线打通
- [x] **产出可安装 APK**（`apk-20261004-12`）
- [ ] 真机验证（安装、首启解压、`hermes --version`）
- [ ] 体积优化（xz 可省 ~108 MB，见 `docs/16`）
- [ ] release 签名（当前是 debug 签名）

## 与上游的关系

本仓库**不 fork** Hermes 源码，而是通过 CI 拉取指定 commit 的上游代码，
外加 `patches/` 下的 Termux 必需补丁。这样能持续跟进上游。
