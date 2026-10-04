#!/data/data/com.termux/files/usr/bin/bash
# 修复 editable 安装的 .pth finder，使其在搬迁后仍能定位源码。
#
# 问题：uv/pip 的 editable 安装会生成
#   <venv>/lib/python3.X/site-packages/__editable___<pkg>_<ver>_finder.py
# 其中 MAPPING / NAMESPACES 两个字典里的值**全是源码的绝对路径**，
# 例如 /data/data/com.termux/files/home/.hermes/installs/<key>/.../workspace/agent
# 一旦 payload 搬到 APK 的 files/opt/，这些路径全部失效 → import 直接失败。
#
# 做法：把 MAPPING/NAMESPACES 里写死的「源码根」前缀替换成运行时动态计算的值。
#   payload 布局（见 make_payload.sh）：
#       <payload_root>/manifest.json
#       <payload_root>/hermes-src/
#       <payload_root>/venv/
#   finder 位于 <payload_root>/venv/lib/pythonX.Y/site-packages/，
#   故源码根 = 向上 4 层（site-packages → pythonX.Y → lib → venv）再 + /hermes-src。
#
# 用法: fix_editable.sh <payload_root>
set -euo pipefail

ROOT="${1:?用法: fix_editable.sh <payload_root>}"
SRC_DIR="$ROOT/hermes-src"
[ -d "$SRC_DIR" ] || { echo "找不到 $SRC_DIR" >&2; exit 1; }

PY_SITE=$(ls -d "$ROOT"/venv/lib/python*/site-packages 2>/dev/null | head -1)
[ -n "$PY_SITE" ] || { echo "找不到 venv 的 site-packages" >&2; exit 1; }

echo "==> payload root : $ROOT"
echo "==> site-packages: $PY_SITE"

fixed=0
for f in "$PY_SITE"/__editable___*_finder.py; do
  [ -f "$f" ] || continue
  echo "==> 处理 $(basename "$f")"

  python3 - "$f" <<'PYEOF'
import ast, re, sys, pathlib

path = pathlib.Path(sys.argv[1])
src = path.read_text(encoding="utf-8")

# 1) 解析现有的 MAPPING / NAMESPACES（都是字面量 dict）
def _grab(name):
    # 注意：类型注解里有 list[str]，含 ']'，所以不能用 [^\]]* 限定
    m = re.search(rf"{name}\s*:\s*dict\[.*?\]\s*=\s*(\{{.*?\}})\s*\n", src, re.S)
    if not m:
        return None, None
    try:
        return m.group(1), ast.literal_eval(m.group(1))
    except Exception as e:
        print(f"  {name} 解析失败: {e}", file=sys.stderr)
        return m.group(1), None

ma_raw, mapping = _grab("MAPPING")
na_raw, namespaces = _grab("NAMESPACES")

if not mapping:
    print("  MAPPING 为空或缺失，跳过")
    sys.exit(0)

# 2) 反推旧源码根：所有值的最长公共前缀的父目录
def old_root_of(vals):
    sample = vals[0]
    return sample.rsplit("/", 1)[0]

old_root = old_root_of(list(mapping.values()))
print(f"  探测到旧源码根: {old_root}")

# 3) 重建为**运行时按相对位置解析**的字典。
#    不用字符串替换（会退化成字面量），而是生成真正的表达式：
#      MAPPING = {k: _SRC_ROOT + v[len(_OLD_ROOT):] for k, v in {...}.items()}
#    这样 k→相对后缀 是静态的，前缀在运行时算。
def rebuild(name, d, indent=""):
    if not d:
        return None
    rel = {k: (v[len(old_root):] if v.startswith(old_root) else v) for k, v in d.items()}
    return (
        f"{name}: dict[str, str] = "
        f"{{_k: _SRC_ROOT + _v for _k, _v in {rel!r}.items()}}"
    )

new_mapping = rebuild("MAPPING", mapping)
new_ns = None
if namespaces:
    rel_ns = {k: [(v[len(old_root):] if v.startswith(old_root) else v) for v in vs]
              for k, vs in namespaces.items()}
    new_ns = (
        "NAMESPACES: dict[str, list[str]] = "
        f"{{_k: [_SRC_ROOT + _v for _v in _vs] for _k, _vs in {rel_ns!r}.items()}}"
    )

# 4) 替换（用 DOTALL 精确替换原行，含多行的字面量）
src = re.sub(r"MAPPING\s*:\s*dict\[.*?\]\s*=\s*\{.*?\}\s*\n",
             new_mapping + "\n", src, count=1, flags=re.S)
if new_ns:
    src = re.sub(r"NAMESPACES\s*:\s*dict\[.*?\]\s*=\s*\{.*?\}\s*\n",
                 new_ns + "\n", src, count=1, flags=re.S)

# 5) 注入 _SRC_ROOT 定义（放在第一个 import 之后）
#    路径推算：本文件在 <payload>/venv/lib/pythonX.Y/site-packages/
#      parents[0]=site-packages [1]=pythonX.Y [2]=lib [3]=venv [4]=<payload>
#    源码根 = <payload>/hermes-src  →  用 parents[4]
inject = (
    "# --- patched by hermes-android fix_editable.sh ---\n"
    "# 运行时解析源码根，免疫绝对路径搬迁\n"
    "_SRC_ROOT = str(Path(__file__).resolve().parents[4] / \"hermes-src\")\n"
    "# --- end patch ---\n"
)
lines = src.split("\n")
insert_at = 0
for i, ln in enumerate(lines):
    if ln.startswith("import ") or ln.startswith("from "):
        insert_at = i + 1
lines.insert(insert_at, inject)
path.write_text("\n".join(lines), encoding="utf-8")
print("  已写回（MAPPING/NAMESPACES 已改为运行时解析）")
PYEOF
  fixed=$((fixed + 1))
done

echo "==> 共处理 $fixed 个 finder 文件"

echo "==> 校验：编译 finder 看是否有语法错误"
for f in "$PY_SITE"/__editable___*_finder.py; do
  [ -f "$f" ] || continue
  if python3 -c "import py_compile,sys; py_compile.compile(sys.argv[1], doraise=True)" "$f" 2>/dev/null; then
    echo "  OK   $(basename "$f")"
  else
    echo "  FAIL $(basename "$f")"
  fi
done

echo "==> 完成"
