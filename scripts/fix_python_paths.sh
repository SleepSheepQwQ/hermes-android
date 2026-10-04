#!/data/data/com.termux/files/usr/bin/bash
# 把 payload 里 venv 的 Python 路径重写到 APK 的最终绝对位置。
#
# 为什么必须做：
#   uv 建的 venv 有两处**绝对路径**指向 base python（managed python）：
#     1. venv/bin/python -> <绝对路径>/tools/python-*/data/data/com.termux/files/usr/bin/python3.14
#     2. venv/pyvenv.cfg 的 home = <绝对路径>/tools/python-*/data/data/com.termux/files/usr/bin
#   构建机上的路径与 APK 里的路径不同，不重写则：
#     - 软链断掉 → 找不到解释器
#     - CPython 由 home= 推导 base_prefix → 找不到标准库 → 直接崩
#
#   注意：docs/10 的端到端测试未暴露此问题，因为当时 base python 仍在原始位置。
#
# 做法：既然 APK 的安装路径是确定的，就直接写死为 APK 的最终路径。
#   应用私有目录 = /data/data/<applicationId>/files
#   载荷目录     = /data/data/<applicationId>/files/opt
#   （applicationId 见 app/build.gradle；保持一致即可）
#
# 用法: fix_python_paths.sh <payload_root> [target_prefix]
#   默认 target_prefix = /data/data/com.nousresearch.hermesandroid/files/opt
set -euo pipefail

ROOT="${1:?用法: fix_python_paths.sh <payload_root> [target_prefix]}"
TARGET="${2:-/data/data/com.nousresearch.hermesandroid/files/opt}"

[ -d "$ROOT/venv" ] || { echo "找不到 $ROOT/venv" >&2; exit 1; }

echo "==> payload root : $ROOT"
echo "==> APK 目标前缀 : $TARGET"

# 1) 定位 payload 内的 managed python（版本号带哈希，必须 glob）
PYREL=""
for d in "$ROOT"/tools/python-*/data/data/com.termux/files/usr/bin; do
  [ -d "$d" ] || continue
  PYREL="${d#"$ROOT"/}"      # 相对 payload 根的路径
  break
done

if [ -z "$PYREL" ]; then
  echo "!! payload 内找不到 tools/python-*/.../bin" >&2
  echo "   若 payload 不含 managed python，venv 无法工作" >&2
  exit 1
fi
echo "==> managed python 相对路径: $PYREL"

PYBIN="$TARGET/$PYREL"
PYEXE=""
for cand in python3.14 python3 python; do
  [ -x "$ROOT/$PYREL/$cand" ] && { PYEXE="$cand"; break; }
done
[ -n "$PYEXE" ] || { echo "!! 在 $PYREL 下找不到 python 可执行文件" >&2; exit 1; }
echo "==> 解释器: $PYBIN/$PYEXE"

# 2) 重写 venv/bin 下的 python* 软链 → 指向 APK 内路径
echo "==> 重写 venv/bin/python* 软链"
for f in "$ROOT"/venv/bin/python "$ROOT"/venv/bin/python3 "$ROOT"/venv/bin/python3.*; do
  [ -e "$f" ] || [ -L "$f" ] || continue
  base=$(basename "$f")
  case "$base" in
    *-config) continue ;;   # python3.14-config 是脚本，另有处理
  esac
  rm -f "$f"
  ln -s "$PYBIN/$PYEXE" "$f"
  echo "  $base -> $PYBIN/$PYEXE"
done

# 3) 重写 pyvenv.cfg 的 home=
CFG="$ROOT/venv/pyvenv.cfg"
if [ -f "$CFG" ]; then
  echo "==> 重写 pyvenv.cfg home="
  python3 - "$CFG" "$PYBIN" <<'PYEOF'
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
  # relocatable=true 保留（uv 写的），但我们现在用的是绝对路径，两者不冲突
  grep -q "^relocatable" "$CFG" || echo "relocatable = false" >> "$CFG"
fi

# 4) 修 venv/bin 下脚本的 shebang（uv 风格是相对写法，但保险起见统一处理）
echo "==> 检查 venv/bin 脚本的 shebang"
fixed=0
for f in "$ROOT"/venv/bin/*; do
  [ -f "$f" ] || continue
  head1=$(head -c 200 "$f" 2>/dev/null | head -1)
  case "$head1" in
    '#!'*python*|'#!'*"/data/data/"*)
      # 含绝对 python 路径 → 重写为 APK 内路径
      python3 - "$f" "$TARGET/venv/bin/$PYEXE" <<'PYEOF'
import sys, pathlib
p, new = pathlib.Path(sys.argv[1]), sys.argv[2]
data = p.read_bytes()
if data.startswith(b"#!"):
    nl = data.find(b"\n")
    if nl > 0:
        p.write_bytes(b"#!" + new.encode() + data[nl:])
        print("  shebang 修正:", p.name)
PYEOF
      fixed=$((fixed + 1))
      ;;
  esac
done
echo "  修正 $fixed 个"

# 5) 产出 paths.env：记录 managed python 的库路径，供 Java 侧设置 LD_LIBRARY_PATH
#
# 为什么需要：
#   managed python 的 ELF RUNPATH 硬编码 /data/data/com.termux/files/usr/lib，
#   但它实际运行在 APK 的 files/opt/tools/python-*/... 下。
#   RUNPATH 优先级低于 LD_LIBRARY_PATH，所以由 Java 侧注入正确的库路径即可覆盖。
#   这里把**相对 payload 根**的路径写下来，Java 侧拼上 files/opt/ 使用，
#   这样即使 applicationId 变了也仍然有效。
echo "==> 生成 paths.env"
ENVF="$ROOT/paths.env"
{
  echo "# 由 fix_python_paths.sh 生成；路径相对 payload 根（安装后为 files/opt/）"
  echo "PYTHON_REL=$PYREL/$PYEXE"
  echo "PYTHON_BIN_REL=venv/bin"
  # managed python 自带的 lib（若存在）
  for cand in "${PYREL%/bin}/lib" "tools/lib" "runtime-libs/lib"; do
    [ -d "$ROOT/$cand" ] && echo "LIB_REL=$cand"
  done
} > "$ENVF"
cat "$ENVF"

# 6) 残留绝对路径检查
echo "==> 残留检查"
LEAK=0
for f in "$ROOT"/venv/bin/python*; do
  [ -L "$f" ] || continue
  tgt=$(readlink "$f")
  case "$tgt" in
    "$TARGET"/*) : ;;
    *) echo "  !! $f -> $tgt（未指向 APK 路径）"; LEAK=$((LEAK+1)) ;;
  esac
done
if [ -f "$CFG" ]; then
  h=$(grep '^home' "$CFG" | head -1)
  case "$h" in
    "home = $TARGET"/*) : ;;
    *) echo "  !! pyvenv.cfg $h（未指向 APK 路径）"; LEAK=$((LEAK+1)) ;;
  esac
fi
[ "$LEAK" -eq 0 ] && echo "  OK 无残留" || { echo "  发现 $LEAK 处问题"; exit 1; }

echo "==> 完成"
