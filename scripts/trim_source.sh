#!/data/data/com.termux/files/usr/bin/bash
# 修剪 Hermes 源码 checkout，用于打进 APK。
# 用法: trim_source.sh <source_dir>
# 只删运行不需要的目录；删完请跑 smoke 测试。
set -euo pipefail

SRC="${1:?用法: trim_source.sh <source_dir>}"
cd "$SRC"

echo "==> 修剪前体积: $(du -sm --exclude=.git . | cut -f1) MB"

# 1. 版本控制与依赖目录（CI 内 clone 后必须删）
rm -rf .git .github .gitmodules 2>/dev/null || true
rm -rf node_modules 2>/dev/null || true

# 2. 开发 / 测试 / 文档 / 站点
for d in tests evals contributors website; do
  rm -rf "$d" 2>/dev/null || true
done

# 3. 桌面与移动 App 源（Android 打包用不到）
rm -rf apps desktop 2>/dev/null || true

# 4. 构建产物与缓存
find . -type d -name __pycache__ -prune -exec rm -rf {} + 2>/dev/null || true
find . -type f -name '*.pyc' -delete 2>/dev/null || true

# 5. Docker / Nix / CI 配置（运行时无关）
rm -rf docker .dockerignore docker-compose*.yml flake.nix flake.lock .envrc 2>/dev/null || true

# 6. 多语言 README 与文档（保留英文 README 与 LICENSE）
find . -maxdepth 1 -type f \( -name 'README.*.md' -o -name 'CONTRIBUTING*' \
  -o -name 'SECURITY*.md' -o -name '.mailmap' \) -delete 2>/dev/null || true

echo "==> 修剪后体积: $(du -sm . | cut -f1) MB"

# 冒烟测试：核心包必须还能 import
echo "==> 冒烟测试"
python - <<'PY'
import importlib, sys
mods = ["hermes_constants", "agent", "tools", "hermes_cli"]
fail = []
for m in mods:
    try:
        importlib.import_module(m)
    except Exception as e:
        fail.append(f"{m}: {e}")
if fail:
    print("FAIL: " + "; ".join(fail), file=sys.stderr)
    sys.exit(1)
print("OK: 核心模块 import 正常")
PY
