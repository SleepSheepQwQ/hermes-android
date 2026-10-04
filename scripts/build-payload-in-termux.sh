#!/data/data/com.termux/files/usr/bin/bash
# 在 Termux 容器内运行（由 build-payload.yml 通过 QEMU 启动）。
#
# 关键约束（来源：termux-docker issue #62/#64，见 docs/12）：
#  1. GitHub Actions 会覆盖 ENTRYPOINT 并以 root 运行 → 每条命令需 /entrypoint.sh 前缀。
#     本脚本由 workflow 以 `bash /work/xxx.sh` 调起，故内部自带 entrypoint 转发。
#  2. 容器内没有 /etc/os-release → actions/checkout 不可用，必须 git clone。
#  3. upload-artifact 在容器场景不可用 → 产物写到挂载的 /out，由宿主 docker cp 取出。
#
# 产出：sealed payload（符合 docs/08 的 manifest.json 规范）：
#   <out>/manifest.json
#   <out>/hermes-src/
#   <out>/venv/
#   <out>/tools/
set -uo pipefail

HERMES_REF="${HERMES_REF:-main}"
HERMES_EXTRA="${HERMES_EXTRA:-termux}"
PREFIX_DIR=/data/data/com.termux/files
HOME_DIR="$PREFIX_DIR/home"
WORK=/work
OUT=/data/data/com.termux/files/home/out          # 与 workflow 挂载点对应
BUILD="$HOME_DIR/build"
mkdir -p "$OUT" "$BUILD"

# Actions 覆盖 ENTRYPOINT 后必须以 /entrypoint.sh 转发命令
termux_run() {
  if [ -x /entrypoint.sh ]; then
    /entrypoint.sh "$@"
  else
    "$@"
  fi
}

echo "==> 环境自检"
uname -m
echo "HOME=$HOME_DIR"
cat /etc/os-release 2>/dev/null | head -2 || echo "(无 /etc/os-release，符合容器预期)"

echo "==> 安装构建工具链"
termux_run pkg update -y || true
termux_run pkg install -y git python clang rust make pkg-config \
  libffi openssl ripgrep ffmpeg nodejs-lts zstd || {
    echo "!! pkg install 失败"; exit 1; }

echo "==> 克隆 Hermes 源码 @ $HERMES_REF"
export GIT_TERMINAL_PROMPT=0
rm -rf "$BUILD/hermes-src"
if ! git clone --depth 1 --branch "$HERMES_REF" \
      https://github.com/NousResearch/hermes-agent.git "$BUILD/hermes-src"; then
  echo "  ref=$HERMES_REF 拉取失败，回退默认分支"
  git clone https://github.com/NousResearch/hermes-agent.git "$BUILD/hermes-src"
fi
cd "$BUILD/hermes-src"

echo "==> 应用 Termux 必需补丁"
for p in "$WORK"/*.patch; do
  [ -f "$p" ] || continue
  echo "  applying $(basename "$p")"
  git apply "$p" 2>/dev/null || patch -p1 < "$p" 2>/dev/null \
    || echo "  WARN: 补丁未应用（可能已包含）"
done

echo "==> 建 venv 并安装 .[$HERMES_EXTRA]"
export ANDROID_API_LEVEL="$(getprop ro.build.version.sdk 2>/dev/null || echo 34)"
python -m venv venv
# shellcheck disable=SC1091
source venv/bin/activate
python -m pip install --upgrade pip setuptools wheel
python -m pip install -e ".[$HERMES_EXTRA]" 2>&1 | tail -20

echo "==> 冒烟测试（安装后必须能导入）"
venv/bin/python -c "import hermes_constants; print('hermes import OK')" || exit 1
venv/bin/hermes --version || true

echo "==> 修剪源码"
termux_run bash "$WORK/trim_source.sh" .

echo "==> 修剪 venv"
termux_run bash "$WORK/trim_venv.sh" venv

# ---------------- 组装 sealed payload（docs/08 规范） ----------------
echo "==> 组装 sealed payload 布局"
PAY="$BUILD/payload"
rm -rf "$PAY"; mkdir -p "$PAY"

cp -a "$BUILD/hermes-src" "$PAY/hermes-src"
cp -a "$BUILD/hermes-src/venv" "$PAY/venv"

# tools 在 Termux 容器里由 PM provision；若存在则一并打包
if [ -d "$HOME_DIR/.hermes/tools" ]; then
  cp -a "$HOME_DIR/.hermes/tools" "$PAY/tools"
else
  echo "  (无 ~/.hermes/tools，建空目录占位——manifest['store'] 必须有值)"
  mkdir -p "$PAY/tools"
fi

# manifest.json：repo/venv/store 三个字段缺一不可（源码用下标访问 → KeyError）
cat > "$PAY/manifest.json" <<JSON
{
  "version": 1,
  "repo": "hermes-src",
  "venv": "venv",
  "store": "tools",
  "sealed": true,
  "note": "sealed payload built in Termux container for hermes-android APK"
}
JSON

echo "==> 修复 editable 绝对路径（docs/09）"
termux_run bash "$WORK/fix_editable.sh" "$PAY" || {
  echo "!! fix_editable 失败，payload 搬迁后会崩"; exit 1; }

echo "==> 校验 payload 自包含"
( cd "$PAY" && LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib \
    ./venv/bin/python -c "import hermes_constants; print('payload 自包含 OK')" ) || {
  echo "!! payload 无法自我导入"; exit 1; }

echo "==> 打包（输出到挂载目录，供宿主 docker cp 取出）"
( cd "$PAY" && tar --zstd -cf "$OUT/hermes-payload.tar.zst" . ) \
  || ( cd "$PAY" && tar -czf "$OUT/hermes-payload.tar.gz" . )

echo "==> 产物"
ls -lh "$OUT"
sha256sum "$OUT"/*.tar.* > "$OUT/SHA256SUMS" 2>/dev/null && cat "$OUT/SHA256SUMS"
echo "==> 完成"
