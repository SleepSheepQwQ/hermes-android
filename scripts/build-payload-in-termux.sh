#!/data/data/com.termux/files/usr/bin/bash
# 在 Termux 容器内运行（由 build-payload.yml 通过 QEMU 启动）。
# 职责：克隆 Hermes 源码 → 应用补丁 → 建 venv 装 [termux] 依赖 → 修剪 → 打包。
set -euo pipefail

HERMES_REF="${HERMES_REF:-main}"
HERMES_EXTRA="${HERMES_EXTRA:-termux}"
DIST=/data/data/com.termux/files/home/dist
mkdir -p "$DIST"

echo "==> 环境自检"
uname -m
python --version || python3 --version || true
command -v git || pkg install -y git

echo "==> 安装构建工具链（termux 容器内）"
pkg update -y >/dev/null 2>&1 || true
pkg install -y git python clang rust make pkg-config libffi openssl ripgrep ffmpeg nodejs-lts >/dev/null

echo "==> 克隆 Hermes 源码 @ $HERMES_REF"
rm -rf /data/data/com.termux/files/home/hermes-src
git clone --depth 1 --branch "$HERMES_REF" \
  https://github.com/NousResearch/hermes-agent.git \
  /data/data/com.termux/files/home/hermes-src 2>/dev/null \
  || git clone https://github.com/NousResearch/hermes-agent.git \
       /data/data/com.termux/files/home/hermes-src

cd /data/data/com.termux/files/home/hermes-src

echo "==> 应用 Termux 必需补丁"
for p in /work/*.patch; do
  [ -f "$p" ] || continue
  echo "  applying $(basename "$p")"
  git apply "$p" || patch -p1 < "$p" || echo "  WARN: 补丁未应用（可能已包含）"
done

echo "==> 建 venv 并安装 .[$HERMES_EXTRA]"
export ANDROID_API_LEVEL="$(getprop ro.build.version.sdk 2>/dev/null || echo 34)"
python -m venv venv
# shellcheck disable=SC1091
source venv/bin/activate
python -m pip install --upgrade pip setuptools wheel
python -m pip install -e ".[$HERMES_EXTRA]" 2>&1 | tail -20

echo "==> 冒烟测试"
venv/bin/python -c "import hermes_constants; print('hermes import OK')"
venv/bin/hermes --version || true

echo "==> 修剪源码"
bash /work/trim_source.sh .

echo "==> 修剪 venv"
bash /work/trim_venv.sh venv

echo "==> 打包"
cd /data/data/com.termux/files/home
tar --zstd -cf "$DIST/hermes-payload.tar.zst" hermes-src venv 2>/dev/null \
  || tar -czf "$DIST/hermes-payload.tar.gz" hermes-src venv
ls -lh "$DIST"
echo "==> 完成"
