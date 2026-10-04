#!/data/data/com.termux/files/usr/bin/bash
# 修剪 Hermes venv，用于打进 APK。
# 用法: trim_venv.sh <venv_dir> [--keep-google]
#   --keep-google  保留 googleapiclient/google（选了 termux-all 时用）
set -euo pipefail

VENV="${1:?用法: trim_venv.sh <venv_dir> [--keep-google]}"
KEEP_GOOGLE="${2:-}"
SP=""
for d in "$VENV"/lib/python*/site-packages; do
  [ -d "$d" ] && SP="$d" && break
done
[ -n "$SP" ] || { echo "找不到 site-packages（在 $VENV/lib/python*/site-packages）" >&2; exit 1; }

echo "==> 修剪前: $(du -sm "$SP" | cut -f1) MB"

# 1. 字节码缓存（零风险，省 ~44 MB）
find "$SP" -type d -name __pycache__ -prune -exec rm -rf {} + 2>/dev/null || true
find "$SP" -type f -name '*.pyc' -delete 2>/dev/null || true

# 2. C 源码与头文件（运行时不需要，保留 .so）
find "$SP" -type f \( -name '*.c' -o -name '*.h' -o -name '*.pxd' \) -delete 2>/dev/null || true

# 3. 包内测试与文档
find "$SP" -type d \( -name tests -o -name 'test_*' \) -prune -exec rm -rf {} + 2>/dev/null || true
# 注：保留 *.dist-info（运行时 importlib.metadata 需要读取版本信息），
# 不要删 *.egg-info 之外的元数据，否则部分包会报 version 缺失。

# 4. google 生态（不在 [termux] 基线内，省 ~104 MB）
if [ "$KEEP_GOOGLE" != "--keep-google" ]; then
  for p in googleapiclient google google_auth google_auth_httplib2 \
           googleapis_common_protos google_api_core googleapis_common; do
    rm -rf "$SP/$p" 2>/dev/null || true
  done
  rm -rf "$SP"/*google*"dist-info" 2>/dev/null || true
fi

# 5. 可选：pip / setuptools / wheel（正常安装不需要）
for p in pip setuptools wheel pkg_resources; do
  rm -rf "$SP/$p" 2>/dev/null || true
done

echo "==> 修剪后: $(du -sm "$SP" | cut -f1) MB"
echo "==> 剩余 .so: $(find "$SP" -name '*.so' | wc -l) 个"
