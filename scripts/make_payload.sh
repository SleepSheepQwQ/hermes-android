#!/data/data/com.termux/files/usr/bin/bash
# 生成 Hermes「sealed payload」布局与 manifest.json。
#
# 依据 hermes-agent 源码 pm/environments.py：
#   store_root():  读 <payload_root>/manifest.json 的 "store" 字段（相对路径）
#   payload_venv():读 <payload_root>/manifest.json 的 "venv" 字段（相对路径）
#   install_key(): sha256(源码绝对路径)[:16] —— 绝对路径一变 key 就变，
#                  而 manifest 机制用**相对路径**解析，从而免疫路径变化。
#
# 用法: make_payload.sh <payload_root> <src_dir> <venv_dir> [tools_dir]
#
# 产物布局：
#   <payload_root>/
#   ├── manifest.json
#   ├── hermes-src/     ← 源码（已 trim）
#   ├── venv/           ← Python venv（已 trim）
#   └── tools/          ← managed 工具链（可选）
set -euo pipefail

ROOT="${1:?用法: make_payload.sh <payload_root> <src_dir> <venv_dir> [tools_dir]}"
SRC="${2:?缺少 src_dir}"
VENV="${3:?缺少 venv_dir}"
TOOLS="${4:-}"

mkdir -p "$ROOT"

echo "==> 摆放源码"
rm -rf "$ROOT/hermes-src"
cp -a "$SRC" "$ROOT/hermes-src"

echo "==> 摆放 venv"
rm -rf "$ROOT/venv"
cp -a "$VENV" "$ROOT/venv"

MANIFEST_TOOLS="tools"
if [ -n "$TOOLS" ] && [ -d "$TOOLS" ]; then
  echo "==> 摆放 tools"
  rm -rf "$ROOT/tools"
  cp -a "$TOOLS" "$ROOT/tools"
else
  echo "==> 未提供 tools，store 字段仍必须存在（store_root() 用下标访问 manifest['store']，缺了会 KeyError）"
  mkdir -p "$ROOT/tools"
fi

# 注意：manifest.json 的 store/venv 字段**必须**存在：
#   store_root()   → manifest["store"]   （下标，缺则 KeyError）
#   payload_venv() → manifest["venv"]    （下标，缺则 KeyError）
cat > "$ROOT/manifest.json" <<JSON
{
  "version": 1,
  "repo": "hermes-src",
  "venv": "venv",
  "store": "${MANIFEST_TOOLS}",
  "sealed": true,
  "note": "sealed payload for hermes-android APK; relative paths only"
}
JSON

echo "==> manifest.json:"
cat "$ROOT/manifest.json"
echo

echo "==> 校验：源码里 editable 的指向"
if [ -f "$ROOT/venv/lib/python3.14/site-packages/__editable__.hermes_agent-0.0.0.pth" ]; then
  echo "  发现 editable 安装的 .pth —— 其 finder 里写死了源码绝对路径，需要重写："
  grep -o '/[^"]*hermes[^"]*' \
    "$ROOT/venv/lib/python3.14/site-packages/__editable___hermes_agent_0_0_0_finder.py" 2>/dev/null | head -3 || true
  echo "  → 由 fix_editable.sh 处理"
fi

echo "==> 完成: $ROOT"
du -sh "$ROOT" 2>/dev/null | tail -1

# 自动修复 editable 的绝对路径（详见 docs/09-editable-relocation-fix.md）
FIX="$(dirname "$0")/fix_editable.sh"
if [ -x "$FIX" ] || [ -f "$FIX" ]; then
  echo
  echo "==> 调用 fix_editable.sh 修复 editable 绝对路径"
  bash "$FIX" "$ROOT"
else
  echo "WARN: 未找到 fix_editable.sh，editable 安装可能无法搬迁"
fi

echo
echo "==> payload 就绪，可打包："
echo "    tar --zstd -cf hermes-payload.tar.zst -C $ROOT ."

