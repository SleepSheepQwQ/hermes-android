# hermes-android

把 [Hermes Agent](https://github.com/NousResearch/hermes-agent) 打包成一个独立的 Android APK。

## 目标

用户装一个 APK，点开就能用 Hermes —— 不需要先装 Termux、不需要手工跑安装脚本、不需要联网拉依赖。APK 内部自带：

- Termux bootstrap（aarch64，含 Python 3.14 / git / ripgrep / ffmpeg / nodejs）
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

架构：APK 内放终端模拟 UI + bootstrap 压缩包（作为 `libtermux-bootstrap.so` 放进 `jniLibs`，规避 Android 对 assets 的压缩/权限限制），首次启动解压到应用私有目录，然后启动 Termux 风格的 shell 运行 `hermes`。

## 目录结构

```
hermes-android/
├── docs/                 调查与设计文档
│   ├── 01-dependencies.md    依赖分析（已完成）
│   ├── 02-runtime-trim.md    运行环境修剪方案
│   └── 03-packaging.md       打包与 CI 设计
├── packaging/            bootstrap 与 APK 打包脚本/配置
├── scripts/              CI 与本地辅助脚本
└── .github/workflows/    GitHub Actions
```

## 状态

- [x] 仓库初始化
- [x] 依赖调查（见 `docs/01-dependencies.md`）
- [ ] 运行环境修剪与配置
- [ ] GitHub Actions 构建与编译
- [ ] 产出可安装 APK

## 与上游的关系

本仓库**不 fork** Hermes 源码，而是通过 CI 拉取指定 commit 的上游代码，外加 `patches/` 下的 Termux 必需补丁（当前 2 个：psutil 可选、PM pin-only 行容错）。这样能持续跟进上游。
