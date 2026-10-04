#!/data/data/com.termux/files/usr/bin/bash
# 端到端验证：在真实 APK 路径下搭出 usr + opt，跑 hermes --version
#
# 思路（docs/18）：不做完整拷贝（600MB + 发烫），而是用**符号链接**把已解压的
# bootstrap 与 payload 挂到 APK 期望的真实路径上，按 BootstrapInstaller.hermesEnv()
# 的逻辑构造环境变量，然后跑 hermes。
#
# 局限：符号链接会改变 /proc/self/exe 的解析结果，所以本验证**不能**证明
# "从真实 files/ 目录 execve"这一步（那需要 targetSdk=28 的真机安装）。
# 它能证明的是：路径重写是否正确、依赖是否齐全、hermes 能否在组合下启动。
#
# 用法: e2e_verify.sh <bootstrap_dir> <payload_dir>
#   默认取 workspace/_apk-size/{core,payload/x}
set -uo pipefail

SRC=/data/data/com.termux/files/home/workspace/_apk-size
CORE="${1:-$SRC/core}"
PAYX="${2:-$SRC/payload/x}"

PKG=com.nousresearch.hermesandroid
ROOT=/data/data/$PKG
FILES=$ROOT/files
USR=$FILES/usr
OPT=$FILES/opt
HOME_DIR=$FILES/home

[ -d "$CORE" ] || { echo "找不到 bootstrap 目录: $CORE"; exit 1; }
[ -d "$PAYX" ] || { echo "找不到 payload 目录: $PAYX"; exit 1; }

echo "==> 1) 清理旧布局并建骨架"
su -c "rm -rf $ROOT" 2>/dev/null
su -c "mkdir -p $FILES $HOME_DIR/.hermes" || { echo "建目录失败"; exit 1; }

echo "==> 2) 挂 bootstrap / payload"
su -c "ln -s $CORE $USR"
su -c "ln -s $PAYX $OPT"

echo "==> 3) 按 SYMLINKS.txt 重建软链（模拟 BootstrapInstaller.rebuildSymlinks）"
python3 - "$CORE" <<'PYEOF'
import os, sys
SRC = sys.argv[1]
txt = open(os.path.join(SRC, "SYMLINKS.txt"), encoding="utf-8").read()
ok = fail = 0
fails = []
for line in txt.split("\n"):
    line = line.strip()
    if not line or "\u2190" not in line:
        continue
    target, link = line.split("\u2190", 1)
    link_abs = os.path.normpath(os.path.join(SRC, link))
    try:
        if os.path.lexists(link_abs):
            os.remove(link_abs)
        os.symlink(target, link_abs)
        ok += 1
    except Exception as e:
        fail += 1
        fails.append(link)
print(f"    软链重建: 成功 {ok}, 失败 {fail}")
import collections
c = collections.Counter("/".join(l.strip("./").split("/")[:2]) for l in fails)
for k, v in c.most_common(6):
    print(f"      失败于 {k}: {v}")
PYEOF

echo "==> 4) 关键软链白名单检查（与 Java 侧一致）"
MISSING=""
for f in bin/sh bin/bash lib/libandroid-support.so; do
  [ -e "$CORE/$f" ] && echo "    OK   $f" || { echo "    缺   $f"; MISSING="$MISSING $f"; }
done
if ls "$CORE"/bin/python3.* >/dev/null 2>&1; then echo "    OK   bin/python*"; else MISSING="$MISSING bin/python*"; fi
[ -n "$MISSING" ] && { echo "!! 关键软链缺失:$MISSING"; exit 1; }

echo "==> 5) 跑 hermes --version"
P=$USR; O=$OPT; V=$O/venv
PYD=$(ls -d "$V"/lib/python3.* 2>/dev/null | head -1)
LIBPATH="$P/lib:$V/lib"
[ -n "$PYD" ] && LIBPATH="$LIBPATH:$PYD/lib-dynload"

su -c "env -i \
  PREFIX=$P TERMUX_PREFIX=$P HOME=$HOME_DIR HERMES_HOME=$HOME_DIR/.hermes \
  HERMES_RUNTIME_DIR=$O/tools HERMES_DISABLE_LAZY_INSTALLS=1 \
  PATH=$P/bin:$V/bin LD_LIBRARY_PATH=$LIBPATH TMPDIR=$P/tmp \
  TERM=xterm-256color LANG=en_US.UTF-8 SHELL=$P/bin/bash PYTHONNOUSERSITE=1 \
  $V/bin/hermes --version" 2>&1 | tail -12
