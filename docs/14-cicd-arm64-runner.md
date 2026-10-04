# CI 架构修正：改用原生 arm64 runner（放弃 QEMU）

日期：2026-10-04 · 依据：调研 + 官方公告核实

## 一、决策：`ubuntu-latest` + QEMU → `ubuntu-24.04-arm`（原生）

### 原方案的问题（实测/调研证据）

| 问题 | 证据 |
| --- | --- |
| **QEMU 用户态在 GHA 上非确定性崩溃** | docker/setup-qemu-action#188（2025-01 报告），作者改用上游 `qemu-user-static` 绕过 |
| **慢 5~20 倍** | 社区量级判断；termux-packages 级别构建在纯 QEMU 下要数小时，易撞 6h job 上限 |
| **跨架构 QEMU 无成功公开案例** | 检索 2025–2026 未发现「QEMU aarch64 + termux-docker + ubuntu-latest」的成功案例 |
| **官方自己也不用跨架构 QEMU** | termux-packages CI 用 `ghcr.io/termux/package-builder` 做**交叉编译**，不是 QEMU 模拟 |

### 新方案：原生 arm64 runner

**`ubuntu-24.04-arm` 对 public 仓库免费**（GitHub 2025-01-16 公告，public preview；
2026-01-29 起 private 仓库也可用）。本仓库 `SleepSheepQwQ/hermes-android` 是 **public** ✅

好处：
- **原生 aarch64** → 无需 QEMU，速度接近原生
- 无 seccomp/binfmt 权限坑
- 不再需要 `docker/setup-qemu-action`

## 二、termux-docker 容器的正确用法（关键修正）

### 1. 用**手动 `docker run`**，不是 `jobs.<container>`

调研结论：只有**容器作业**（`jobs.<id>.container`）会被 GHA 强制覆盖 ENTRYPOINT
（固定用 `--entrypoint "tail" <image> -f /dev/null` 保活），导致 `/entrypoint.sh` 从不执行、
命令以 root 跑、而 termux 的 `pkg` 拒绝 root。

**手动 `docker run` 不覆盖 ENTRYPOINT** → `/entrypoint.sh` 正常生效 → 自动降权到
`system`(uid 1000) → `pkg` 可用。✅ 这是本项目采用的方式。

### 2. ARM 容器需要 `--security-opt seccomp=unconfined`

原因：bionic linker 调用 `personality()`，Docker 默认 seccomp 白名单不放行其参数值
→ `Operation not permitted` → 二进制起不来。

三种官方认可做法（README）：
- `--privileged`（最省事，最不安全）
- **`--security-opt seccomp=unconfined`（本项目采用，比 privileged 小）** ✅
- 自定义 seccomp profile 只放开 `personality`（最安全，但需自编 docker daemon，CI 不可行）

### 3. **自定义参数必须走位置参数**（本项目修掉的一个真实 bug）

`entrypoint.sh` 源码（349 字节）：

```sh
if [ "$(id -u)" != "0" ]; then exec "$@"; fi
exec /system/bin/su -s "$PREFIX/bin/env" system -- -i \
  ANDROID_DATA=... ANDROID_ROOT=... HOME=... LANG=... PATH=... \
  PREFIX=... TMPDIR=... TZ=... TERM=... "$@"
```

注意 `su ... -i` —— **清空环境后只重新注入固定变量**。
所以 `docker run -e HERMES_REF=main` 传进去的环境变量**会被丢掉**。

**修正**：改为位置参数传递

```sh
docker run ... termux/termux-docker:aarch64 \
  bash /work/build-payload-in-termux.sh 'main' 'termux'
```

脚本内相应改为 `HERMES_REF="${1:-main}"` / `HERMES_EXTRA="${2:-termux}"`。

### 4. 挂载目录权限

容器内是 uid 1000（system），与 runner 的 uid 不同 → 挂载目录必须 `chmod -R a+rwX`。

## 三、为什么仍用 termux-docker 而不是 proot

调研给的次选是「proot + 官方 bootstrap 当 rootfs」。本项目选择 termux-docker 的理由：

- termux-docker 镜像**本身就是**「官方 bootstrap + aosp-libs + aosp-utils」的封装
  （见其 `generate.sh`）
- bootstrap 的二进制是 bionic，需要 `/system/bin/linker64` 与 `/system` 布局
- 自己用 proot 搭要额外处理 linker 与 `/system` 挂载，风险更高
- 既然已是**原生 arm64**，容器方案不再有 QEMU 的慢与不稳定，没有理由绕开

> 若将来 termux-docker 出问题，proot 路线是已验证可行的备选。

## 四、流水线最终形态

```
build-bootstrap.yml   ubuntu-latest      generate-bootstraps.sh（纯解包重打包，无 Docker）
      ↓ Release: bootstrap-*
build-payload.yml     ubuntu-24.04-arm   termux-docker:aarch64（原生，seccomp=unconfined）
      ↓ Release: payload-*
build-apk.yml         ubuntu-latest      组合两者 → jniLibs → assembleDebug/Release
      ↓ Release: apk-*
```

## 五、来源

| 主题 | URL |
| --- | --- |
| arm64 runner 免费公告 | https://github.blog/changelog/2025-01-16-linux-arm64-hosted-runners-now-available-for-free-in-public-repositories-public-preview |
| arm64 私有仓库可用 | https://github.blog/changelog/2026-01-29-arm64-standard-runners-are-now-available-in-private-repositories |
| QEMU 在 GHA 崩溃 | https://github.com/docker/setup-qemu-action/issues/188 |
| termux-docker README（seccomp/personality） | https://github.com/termux/termux-docker |
| termux-docker entrypoint.sh | https://github.com/termux/termux-docker/blob/master/entrypoint.sh |
| GHA 容器作业覆盖 ENTRYPOINT | https://docs.github.com/en/actions/using-jobs/running-jobs-in-a-container |
| 社区案例（容器作业模式） | https://github.com/guijan/termux-on-gha · https://github.com/ronin-rb/scripts |
| 官方 seccomp profile 参考 | https://github.com/termux/termux-packages/blob/master/scripts/profile.json |
