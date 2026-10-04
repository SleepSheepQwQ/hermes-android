#!/data/data/com.termux/files/usr/bin/bash
# 把 payload 里 venv 的 Python 路径重写到 APK 的最终绝对位置。
#
# 为什么必须做（docs/13）：
#   venv 有两处**绝对路径**指向构建机上的 base python：
#     1. venv/bin/python -> <base>/bin/python3.14
#     2. venv/pyvenv.cfg 的 home = <base>/bin
#   CPython 启动时靠 home= 推导 base_prefix 再定位标准库；路径不存在 → 直接崩。
#
# 关键：base python 有两种来源，映射目标不同 —— 必须自动探测：
#   A) bootstrap 的 python（CI 里 `pkg install python` 后建 venv）
#        /data/data/com.termux/files/usr/...
#      → APK: <files>/usr/...                （bootstrap 解压到 files/usr）
#   B) Hermes managed python（本机安装形态，uv 建的 venv）
#        .../home/.hermes/tools/python-<ver>/data/data/com.termux/files/usr/...
#      → APK: <files>/opt/tools/python-<ver>/data/data/com.termux/files/usr/...
#
# 用法: fix_python_paths.sh <payload_root> [files_dir]
#   files_dir 默认 /data/data/com.nousresearch.hermesandroid/files
set -euo pipefail

ROOT="${1:?用法: fix_python_paths.sh <payload_root> [files_dir]}"
FILES="${2:-/data/data/com.nousresearch.hermesandroid/files}"
VENV="$ROOT/venv"
CFG="$VENV/pyvenv.cfg"

[ -d "$VENV" ] || { echo "找不到 $VENV" >&2; exit 1; }
[ -f "$CFG" ] || { echo "找不到 $CFG" >&2; exit 1; }

echo "==> payload root : $ROOT"
echo "==> APK files 目录: $FILES"

# ---------- 1) 探测当前 base python 路径 ----------
OLD_HOME=$(grep '^home' "$CFG" | head -1 | sed 's/^home *= *//')
echo "==> 当前 pyvenv.cfg home = $OLD_HOME"

OLD_LINK_TGT=""
PYLINK=""
for cand in python python3 python3.14; do
  p="$VENV/bin/$cand"
  if [ -L "$p" ]; then
    t=$(readlink "$p")
    case "$t" in
      /*) PYLINK="$p"; OLD_LINK_TGT="$t"; break ;;
    esac
  fi
done
[ -n "$OLD_LINK_TGT" ] && echo "==> 当前 venv/bin/$(basename "$PYLINK") -> $OLD_LINK_TGT"

BASE_SRC="${OLD_LINK_TGT:-$OLD_HOME}"
[ -n "$BASE_SRC" ] || { echo "!! 无法探测 base python 路径" >&2; exit 1; }

# ---------- 2) 决定映射目标 ----------
NEW_HOME=""
MAP_KIND=""

case "$BASE_SRC" in
  */home/.hermes/tools/python-*)
    # 情形 B：managed python
    SUB="${BASE_SRC#*/home/.hermes/}"       # tools/python-xxx/data/.../usr/bin
    REST="${SUB#tools/}"                    # python-xxx/data/.../usr/bin
    VERDIR="${REST%%/*}"                    # python-xxx
    TAIL="${REST#*/}"                       # data/data/com.termux/files/usr/bin
    if [ -d "$ROOT/tools/$VERDIR" ]; then
      NEW_HOME="$FILES/opt/tools/$VERDIR/$TAIL"
      MAP_KIND="B (managed python，payload 内自带)"
    else
      echo "!! payload/tools/$VERDIR 不存在——managed python 未随 payload 打包" >&2
      echo "   venv 在 APK 中将无法启动。请确认 make_payload 传入了 tools 目录。" >&2
      exit 1
    fi
    ;;
  /data/data/com.termux/files/usr/*)
    # 情形 A：bootstrap python（由 APK 内的 bootstrap 提供）
    TAIL="${BASE_SRC#/data/data/com.termux/files/usr/}"   # bin 或 bin/python3.14
    case "$TAIL" in
      bin/*) NEW_HOME="$FILES/usr/bin" ;;
      *)     NEW_HOME="$FILES/usr/$TAIL" ;;
    esac
    MAP_KIND="A (bootstrap python，由 APK 的 \$PREFIX 提供)"
    ;;
  *)
    echo "!! 无法识别的 base python 路径: $BASE_SRC" >&2
    echo "   期望以 /data/data/com.termux/files/usr/ 或 .../home/.hermes/tools/python-* 开头" >&2
    exit 1
    ;;
esac

echo "==> 映射情形: $MAP_KIND"
echo "==> 新 home = $NEW_HOME"

# 目标解释器文件名：优先沿用原软链名，否则 python3.14
EXE=$(basename "${OLD_LINK_TGT:-python3.14}")
NEW_TGT="$NEW_HOME/$EXE"
echo "==> 新解释器 = $NEW_TGT"

# ---------- 3) 重写 venv/bin 下的 python* 软链 ----------
echo "==> 重写 venv/bin/python* 软链"
for cand in python python3 python3.14 python3.13; do
  p="$VENV/bin/$cand"
  [ -e "$p" ] || [ -L "$p" ] || continue
  rm -f "$p"
  ln -s "$NEW_TGT" "$p"
  echo "  $cand -> $NEW_TGT"
done

# ---------- 4) 重写 pyvenv.cfg home= ----------
echo "==> 重写 pyvenv.cfg home="
python3 - "$CFG" "$NEW_HOME" <<'PYEOF'
import sys, pathlib
cfg, newhome = pathlib.Path(sys.argv[1]), sys.argv[2]
lines, seen = [], False
for ln in cfg.read_text(encoding="utf-8").splitlines():
    if ln.strip().startswith("home"):
        lines.append(f"home = {newhome}")
        seen = True
    else:
        lines.append(ln)
if not seen:
    lines.append(f"home = {newhome}")
cfg.write_text("\n".join(lines) + "\n", encoding="utf-8")
print("  已写入:", newhome)
PYEOF

# ---------- 5) 修 venv/bin 脚本的 shebang（仅绝对路径写法）----------
echo "==> 检查 venv/bin 脚本的 shebang"
fixed=0
for f in "$VENV"/bin/*; do
  [ -f "$f" ] || continue
  head1=$(head -c 300 "$f" 2>/dev/null | head -1)
  case "$head1" in
    '#!'*"/data/data/"*)
      python3 - "$f" "$NEW_TGT" <<'PYEOF'
import sys, pathlib
p, new = pathlib.Path(sys.argv[1]), sys.argv[2]
data = p.read_bytes()
if data.startswith(b"#!"):
    nl = data.find(b"\n")
    if nl > 0 and b"/data/data/" in data[:nl]:
        p.write_bytes(b"#!" + new.encode() + data[nl:])
        print("  shebang 修正:", p.name)
PYEOF
      fixed=$((fixed + 1))
      ;;
  esac
done
echo "  处理 $fixed 个"

# ---------- 6) 产出 paths.env ----------
# 供 Java 侧拼 LD_LIBRARY_PATH：bootstrap 的 lib 与 managed python 的 lib 都要带上。
echo "==> 生成 paths.env"
ENVF="$ROOT/paths.env"
{
  echo "# 由 fix_python_paths.sh 生成"
  echo "# APK 内的绝对路径（Java 侧直接可用）"
  echo "PYTHON_EXE_APK=$NEW_TGT"
  echo "PYTHON_HOME_APK=$NEW_HOME"
  echo "PREFIX_LIB_APK=$FILES/usr/lib"
  echo "MAP_KIND=$MAP_KIND"
} > "$ENVF"
cat "$ENVF"

# ---------- 7) 残留检查 ----------
echo "==> 残留检查"
LEAK=0
for cand in python python3 python3.14; do
  p="$VENV/bin/$cand"
  [ -L "$p" ] || continue
  tgt=$(readlink "$p")
  case "$tgt" in
    "$FILES"/*) : ;;
    *) echo "  !! $cand -> $tgt（未指向 APK 路径）"; LEAK=$((LEAK+1)) ;;
  esac
done
h=$(grep '^home' "$CFG" | head -1)
case "$h" in
  "home = $FILES"/*) : ;;
  *) echo "  !! pyvenv.cfg $h（未指向 APK 路径）"; LEAK=$((LEAK+1)) ;;
esac
if [ "$LEAK" -eq 0 ]; then echo "  OK 无残留"; else echo "  发现 $LEAK 处问题"; exit 1; fi

echo "==> 完成"
