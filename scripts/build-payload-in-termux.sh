#!/data/data/com.termux/files/usr/bin/bash
# 在 Termux 容器内运行（由 build-payload.yml 用 `docker run` 调起）。
#
# 调用链（重要）：
#   docker run termux/termux-docker:aarch64 bash /work/xxx.sh <ref> <extra>
#     → 镜像 ENTRYPOINT=/entrypoint.sh 自动生效
#     → 检测到 uid=0（root）→ 用 su 降权到 system(uid 1000) 并清空环境后执行本脚本
#   因此：
#     1. 本脚本**已经**以非 root 运行，pkg 不会报错，无需再调 /entrypoint.sh。
#     2. 自定义参数必须走**位置参数**——entrypoint 的 `su -i` 会清空环境变量，
#        docker 的 -e 传不进来（这是本次修正的一个真实 bug）。
#
# 产出：sealed payload（符合 docs/08 的 manifest.json 规范）：
#   <out>/manifest.json
#   <out>/hermes-src/
#   <out>/venv/
#   <out>/tools/
# 注意：必须用 set -e。
# 之前只用了 set -uo pipefail（缺 -e），导致 pip 安装失败后脚本继续跑，
# 产出**空的 venv** 还"成功"走到最后（CI 实际踩到过：pillow-heif 编译失败）。
set -euo pipefail

HERMES_REF="${1:-main}"
HERMES_EXTRA="${2:-termux}"
PREFIX_DIR=/data/data/com.termux/files
HOME_DIR="$PREFIX_DIR/home"
WORK=/work
OUT="$HOME_DIR/out"                 # 与 workflow 的挂载点对应
BUILD="$HOME_DIR/build"
mkdir -p "$OUT" "$BUILD"

echo "==> 环境自检"
echo "uid=$(id -u) user=$(id -un)  (应为非 root，由 entrypoint.sh 降权)"
uname -m
echo "HOME=$HOME_DIR  PREFIX=$PREFIX_DIR/usr"
echo "HERMES_REF=$HERMES_REF  HERMES_EXTRA=$HERMES_EXTRA"

echo "==> 安装构建工具链"
pkg update -y || true
# libheif 是 pillow-heif 的编译/运行依赖（Hermes core 依赖 pillow-heif，
# 缺它 pip 会报 "Failed building wheel for pillow-heif"）。
# libjpeg-turbo/libpng 供 Pillow 使用；cmake/ninja 供部分包源码构建。
pkg install -y git python clang rust make pkg-config \
  libffi openssl ripgrep ffmpeg nodejs-lts zstd \
  libheif libjpeg-turbo libpng cmake ninja || {
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
PATCH_FAIL=0
for p in "$WORK"/*.patch; do
  [ -f "$p" ] || continue
  echo "  applying $(basename "$p")"
  if git apply "$p" 2>/dev/null; then
    echo "    ok (git apply)"
  elif patch -p1 < "$p" >/dev/null 2>&1; then
    echo "    ok (patch -p1)"
  elif git apply --reverse --check "$p" 2>/dev/null; then
    echo "    已在源码中（跳过）"
  else
    echo "    !! 补丁应用失败：$(basename "$p")"
    PATCH_FAIL=$((PATCH_FAIL+1))
  fi
done
if [ "$PATCH_FAIL" -gt 0 ]; then
  # 补丁失败意味着 psutil 可选导入 / PM pin-only 容错缺失，
  # 会在运行期崩溃。宁可失败，不要带着病打包。
  echo "!! 有 $PATCH_FAIL 个补丁未能应用，终止构建" >&2
  exit 1
fi

echo "==> 建 venv 并安装 .[$HERMES_EXTRA]"
export ANDROID_API_LEVEL="$(getprop ro.build.version.sdk 2>/dev/null || echo 34)"
python -m venv venv
# shellcheck disable=SC1091
source venv/bin/activate
python -m pip install --upgrade pip setuptools wheel

# 注意：不要把 pip 的输出接 `| tail` —— 管道会让退出码变成 tail 的，
# 掩盖 pip 的真实失败（CI 实际踩到过：pillow-heif 编译失败被吞掉）。
# 改为写日志文件，失败时打印尾部。
PIPLOG="$BUILD/pip-install.log"
if ! python -m pip install -e ".[$HERMES_EXTRA]" > "$PIPLOG" 2>&1; then
  echo "!! pip install 失败，日志尾部：" >&2
  tail -40 "$PIPLOG" >&2
  exit 1
fi
tail -5 "$PIPLOG"

echo "==> 冒烟测试（安装后必须能导入）"
venv/bin/python -c "import hermes_constants; print('hermes_constants OK')" || {
  echo "!! 无法导入 hermes_constants" >&2; exit 1; }
# 关键原生扩展必须真的在（防止「装了但缺 .so」的静默失败）
venv/bin/python - <<'PYEOF' || { echo "!! 原生扩展缺失" >&2; exit 1; }
import importlib, sys
mods = ["pydantic_core", "cryptography", "jiter", "PIL", "pillow_heif"]
bad = []
for m in mods:
    try:
        importlib.import_module(m)
    except Exception as e:
        bad.append(f"{m}: {type(e).__name__}: {e}")
if bad:
    print("FAILED: " + "; ".join(bad), file=sys.stderr)
    sys.exit(1)
print("原生扩展全部可导入:", ", ".join(mods))
PYEOF
venv/bin/hermes --version || true

echo "==> 修剪源码"
bash "$WORK/trim_source.sh" .

echo "==> 修剪 venv"
bash "$WORK/trim_venv.sh" venv

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

echo "==> 校验 payload 自包含（必须在路径重写**之前**做）"
# 重写后 venv/bin/python 会指向 APK 内的绝对路径，在容器里不存在，
# 那时再执行必然失败。所以在「仍指向构建机真实路径」时先验证一次。
( cd "$PAY" && ./venv/bin/python -c "import hermes_constants; print('payload 自包含 OK')" ) || {
  echo "!! payload 无法自我导入"; exit 1; }

echo "==> 修复 editable 绝对路径（docs/09）"
bash "$WORK/fix_editable.sh" "$PAY" || {
  echo "!! fix_editable 失败，payload 搬迁后会崩"; exit 1; }

echo "==> 修复 venv 的 Python 路径（软链 + pyvenv.cfg home=）"
# 关键：venv/bin/python 是指向构建机绝对路径的软链，pyvenv.cfg 的 home= 同理。
# 不重写则搬进 APK 后解释器找不到、标准库找不到（docs/13）。
# 脚本会自动探测 base python 来源（bootstrap 的 usr/bin 或 managed tools/python-*）
# 并映射到 APK 内的对应位置。
bash "$WORK/fix_python_paths.sh" "$PAY" \
  "/data/data/com.nousresearch.hermesandroid/files" || {
  echo "!! fix_python_paths 失败"; exit 1; }

echo "==> 重写后的静态校验（不执行，只查路径正确性）"
# 此时不能执行 venv/bin/python（它已指向 APK 路径，容器里不存在），
# 只能做静态检查：软链与 pyvenv.cfg 是否都指向 APK 前缀。
STATIC_OK=1
for c in python python3 python3.14; do
  p="$PAY/venv/bin/$c"
  [ -L "$p" ] || continue
  t=$(readlink "$p")
  case "$t" in
    /data/data/com.nousresearch.hermesandroid/files/*) : ;;
    *) echo "  !! $c -> $t（未指向 APK 路径）"; STATIC_OK=0 ;;
  esac
done
h=$(grep '^home' "$PAY/venv/pyvenv.cfg" | head -1 | sed 's/^home *= *//')
case "$h" in
  /data/data/com.nousresearch.hermesandroid/files/*) : ;;
  *) echo "  !! pyvenv.cfg home=$h（未指向 APK 路径）"; STATIC_OK=0 ;;
esac
# editable finder 不应再有构建机绝对路径
if grep -q "/installs/\|/home/.hermes/tools/" "$PAY"/venv/lib/python3.*/site-packages/__editable___*_finder.py 2>/dev/null; then
  echo "  !! editable finder 仍有构建机绝对路径"; STATIC_OK=0
fi
[ "$STATIC_OK" -eq 1 ] && echo "  静态校验通过" || { echo "!! 静态校验失败"; exit 1; }

echo "==> 打包（输出到挂载目录，供宿主 docker cp 取出）"
( cd "$PAY" && tar --zstd -cf "$OUT/hermes-payload.tar.zst" . ) \
  || ( cd "$PAY" && tar -czf "$OUT/hermes-payload.tar.gz" . )

echo "==> 产物"
ls -lh "$OUT"
sha256sum "$OUT"/*.tar.* > "$OUT/SHA256SUMS" 2>/dev/null && cat "$OUT/SHA256SUMS"
echo "==> 完成"
