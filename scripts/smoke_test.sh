#!/data/data/com.termux/files/usr/bin/bash
# CI 冒烟测试：不需要真机，验证 bootstrap 与 payload 产物的完整性。
#
# 用法:
#   smoke_test.sh bootstrap <bootstrap.zip> [<bootstrap.zip.sha256>]
#   smoke_test.sh payload   <payload.tar.zst>
#
# 退出码 0 = 全部通过；非 0 = 有检查失败（CI 中会中断）。
set -uo pipefail

MODE="${1:?用法: smoke_test.sh <bootstrap|payload> <文件> [<sha256文件>]}"
FILE="${2:?缺少产物路径}"
SUM="${3:-}"

PASS=0; FAIL=0
ok()   { echo "  PASS  $*"; PASS=$((PASS+1)); }
bad()  { echo "  FAIL  $*"; FAIL=$((FAIL+1)); }
info() { echo "  ----  $*"; }

SCRATCH=$(mktemp -d)
trap 'rm -rf "$SCRATCH"' EXIT

echo "==> 冒烟测试: $MODE ($FILE)"
[ -f "$FILE" ] && ok "产物存在" || { bad "产物不存在"; exit 1; }
info "大小: $(du -h "$FILE" | cut -f1)"

# ---------- 通用：hash 校验 ----------
if [ -n "$SUM" ] && [ -f "$SUM" ]; then
  if sha256sum -c "$SUM" >/dev/null 2>&1; then ok "sha256 校验通过"; else bad "sha256 校验失败"; fi
fi

case "$MODE" in
bootstrap)
  LIST="$SCRATCH/list.txt"
  unzip -l "$FILE" > "$LIST" 2>/dev/null || { bad "无法读取 zip"; exit 1; }

  # 1) 条目数区间（官方默认 3774；定制含 python 后实测 15226）
  N=$(grep -cE "^\s+[0-9]+" "$LIST" || echo 0)
  if [ "$N" -gt 3000 ]; then ok "条目数 $N"; else bad "条目数异常: $N"; fi

  # 2) 关键二进制存在性
  #    注意：python3 / sh / ls 等在 zip 里可能是**软链**（记录在 SYMLINKS.txt），
  #    不出现为独立文件条目，故需同时查文件列表与软链清单。
  SYML="$SCRATCH/symlinks.txt"
  unzip -p "$FILE" SYMLINKS.txt > "$SYML" 2>/dev/null || : > "$SYML"
  has_entry() {
    local name="$1"
    grep -qE "(^|\s)${name}\$" "$LIST" && return 0
    # 软链格式：目标←链接名
    grep -qE "←\.?/?${name}\$" "$SYML" && return 0
    return 1
  }
  for t in python3.14 bash tar; do
    if has_entry "bin/$t"; then ok "bin/$t 存在"; else bad "bin/$t 缺失"; fi
  done
  # 这些通常是软链
  for t in python3 sh ls; do
    if has_entry "bin/$t"; then ok "bin/$t 存在（文件或软链）"
    else info "bin/$t 未找到（可能由 second-stage 生成）"; fi
  done

  # 3) Hermes 需要的额外工具（定制包才有，官方默认没有）
  for t in node git rg ffmpeg; do
    if grep -qE "(^|\s)bin/${t}\$" "$LIST"; then ok "bin/$t 存在（定制）"
    else info "bin/$t 缺失（若 extras 未包含则正常）"; fi
  done

  # 4) 关键库
  for l in libpython3.14.so libandroid-support.so; do
    if grep -qE "lib/${l}\$" "$LIST"; then ok "lib/$l 存在"; else bad "lib/$l 缺失"; fi
  done

  # 5) SYMLINKS.txt（软链清单，解压后必须重建）
  grep -q "SYMLINKS.txt" "$LIST" && ok "SYMLINKS.txt 存在" || bad "SYMLINKS.txt 缺失"

  # 6) 深度验证（可选）：解压 + LD_LIBRARY_PATH 覆盖后 Python 能否导入
  #    这直接固化 docs/07 的「可重定位」结论。
  #    默认跳过（解压 616MB/1.5万文件，手机端会 OOM）；CI 或内存充足时设 SMOKE_DEEP=1。
  if [ "${SMOKE_DEEP:-0}" = "1" ]; then
    if unzip -q -o "$FILE" -d "$SCRATCH/rootfs" 2>/dev/null; then
      cd "$SCRATCH/rootfs" || exit 1
      if [ -f SYMLINKS.txt ]; then
        while IFS= read -r line; do
          [ -z "$line" ] && continue
          tgt="${line%%←*}"; lnk="${line#*←}"
          [ -z "$lnk" ] && continue
          mkdir -p "$(dirname "$lnk")" 2>/dev/null
          rm -f "$lnk" 2>/dev/null
          ln -sf "$tgt" "$lnk" 2>/dev/null
        done < SYMLINKS.txt
      fi
      chmod -R +x bin libexec 2>/dev/null
      if LD_LIBRARY_PATH="$SCRATCH/rootfs/lib" \
         "$SCRATCH/rootfs/bin/python3.14" -c "import sys; assert sys.version_info[:2]==(3,14)" 2>/dev/null; then
        ok "Python 3.14 可执行（重定位验证通过）"
      else
        bad "Python 无法执行（重定位失败）"
      fi
    else
      bad "解压失败"
    fi
  else
    info "跳过深度解压测试（设 SMOKE_DEEP=1 启用）"
  fi
  ;;

payload)
  # 1) 列出内容
  tar --zstd -tf "$FILE" > "$SCRATCH/list.txt" 2>/dev/null \
    || tar -tzf "$FILE" > "$SCRATCH/list.txt" 2>/dev/null \
    || { bad "无法读取 tar"; exit 1; }
  N=$(wc -l < "$SCRATCH/list.txt")
  [ "$N" -gt 100 ] && ok "条目数 $N" || bad "条目数异常: $N"

  # 2) sealed payload 结构
  for p in manifest.json hermes-src/ venv/ tools/; do
    if grep -qE "^\.?/?${p%\/}(\$|/)" "$SCRATCH/list.txt"; then ok "包含 $p"
    else bad "缺少 $p"; fi
  done

  # 3) 解压后验证 manifest 字段（store/venv 是下标访问，缺则 KeyError）
  mkdir -p "$SCRATCH/p"
  if tar --zstd -xf "$FILE" -C "$SCRATCH/p" 2>/dev/null \
     || tar -xzf "$FILE" -C "$SCRATCH/p" 2>/dev/null; then
    # 支持多包一层目录的情况
    ROOT="$SCRATCH/p"
    [ -d "$ROOT/manifest.json" ] && :
    if [ ! -f "$ROOT/manifest.json" ]; then
      inner=$(find "$ROOT" -maxdepth 2 -name manifest.json | head -1)
      [ -n "$inner" ] && ROOT=$(dirname "$inner")
    fi
    if [ -f "$ROOT/manifest.json" ]; then
      ok "manifest.json 存在"
      for k in repo venv store; do
        if grep -q "\"$k\"" "$ROOT/manifest.json"; then ok "manifest.$k 存在"
        else bad "manifest.$k 缺失（会导致 KeyError）"; fi
      done
    else
      bad "解压后找不到 manifest.json"
    fi

    # 4) editable 绝对路径泄漏检查（搬走后必失效）
    if [ -d "$ROOT/venv" ]; then
      leaks=$(grep -rl "/installs/" "$ROOT/venv/lib"/*/site-packages/__editable___*_finder.py 2>/dev/null | wc -l)
      if [ "$leaks" -eq 0 ]; then ok "editable finder 无绝对路径泄漏"
      else bad "editable finder 仍有 $leaks 处绝对路径（需 fix_editable.sh）"; fi

      # 5) Python 能否从 payload 内部导入源码
      if [ -x "$ROOT/venv/bin/python" ]; then
        if (cd "$ROOT" && LD_LIBRARY_PATH="${PREFIX:-/data/data/com.termux/files/usr}/lib" \
            ./venv/bin/python -c "import hermes_constants" 2>/dev/null); then
          ok "payload 自包含：可导入 hermes_constants"
        else
          bad "无法从 payload 导入 hermes_constants"
        fi
      else
        info "venv/bin/python 不在（CI 环境可能无法执行，跳过运行测试）"
      fi
    fi
  else
    bad "解压失败"
  fi
  ;;
*)
  bad "未知模式: $MODE"
  ;;
esac

echo
echo "==> 结果: $PASS 通过, $FAIL 失败"
[ "$FAIL" -eq 0 ] || exit 1
echo "==> 冒烟测试全部通过"
