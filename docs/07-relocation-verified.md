# 重定位实测：bootstrap 能否在非 Termux 路径下运行

日期：2026-10-04 · **本机实测，结论已验证**

## 背景问题

调研发现 bootstrap 二进制的 **RUNPATH 硬编码** `/data/data/com.termux/files/usr/lib`，
且 `bin/bash` 的字符串里有 **13 处** `com.termux` 路径。

我们的 APK 包名是 `com.nousresearch.hermesandroid`，bootstrap 解压到
`/data/data/com.nousresearch.hermesandroid/files/usr` —— 路径不匹配。
**这曾是最大的「装上跑不起来」风险。**

## 实测过程与结果

把 CI 产出的定制 bootstrap（203 MB zip）解压到**完全无关的路径**
（`~/.hermes/cache/scratch/reloc-test`），重建 1551 条软链后测试：

| 测试项 | 命令 | 结果 |
| --- | --- | --- |
| 二进制解释器 | `readelf -l bin/bash` | `/system/bin/linker64`（系统路径，与包名无关）✅ |
| RUNPATH 类型 | `readelf -d bin/bash` | `DT_RUNPATH`（**不是** RPATH）✅ |
| bash 执行 | `LD_LIBRARY_PATH=$T/lib $T/bin/bash --version` | **GNU bash 5.3.20 正常输出** ✅ |
| 常用工具 | `ls` / `grep` | ✅ 正常 |
| 软链 | `bin/sh -> dash` 执行 | ✅ 正常 |
| **Python** | `python3.14 -c "..."` | **✅ 3.14.6 正常** |
| 软链重建 | 1551 条按 SYMLINKS.txt | ✅ 全部成功 |

## 关键原理

动态链接器的库搜索顺序：

```
DT_RPATH  >  LD_LIBRARY_PATH  >  DT_RUNPATH  >  ld.so.cache  >  默认路径
```

- bootstrap 用的是 **`DT_RUNPATH`**（`0x1d`），其优先级**低于** `LD_LIBRARY_PATH`
- 因此 **设置 `LD_LIBRARY_PATH=$PREFIX/lib` 就能覆盖**硬编码的错误路径 ✅
- 若它用的是 `DT_RPATH`（`0x0f`），`LD_LIBRARY_PATH` 就无效，只能 patchelf —— 好在不是

## 残留问题（已知、可接受）

`PREFIX` 环境变量在**未显式设置**时，程序会读到二进制内部硬编码的
`/data/data/com.termux/files/usr`：

```
$ $T/bin/bash -c 'echo "$PREFIX"'
/data/data/com.termux/files/usr        ← 未设环境变量时的内置默认值
```

**影响**：少数程序会依赖内置默认路径去找文件（如 `bash` 找 `bashdb-main.inc`）。

**缓解措施**（已实现在 `BootstrapInstaller.hermesEnv()`）：
- APK 启动时**始终显式导出** `PREFIX` / `HOME` / `TERMUX_PREFIX` / `LD_LIBRARY_PATH`
- 对个别仍读内置默认的程序，可后续用 `patchelf` 精确处理（`pkg install patchelf`）

## 结论

✅ **bootstrap 可重定位，独立包名的 APK 方案成立。**
无需修改 bootstrap、无需 patchelf、无需让包名变成 `com.termux`。
只要在启动时正确设置 `PREFIX` + `LD_LIBRARY_PATH` 即可。

这是整个打包方案里最关键的一次验证。

## 复现命令

```bash
T=/tmp/reloc-test
mkdir -p $T && unzip -q bootstrap-aarch64.zip -d $T
cd $T
while IFS= read -r l; do
  [ -z "$l" ] && continue
  ln -sf "${l%%←*}" "${l#*←}"
done < SYMLINKS.txt
export LD_LIBRARY_PATH=$T/lib
$T/bin/bash --version          # → GNU bash 5.3.20
$T/bin/python3.14 -c "import sys;print(sys.version)"   # → 3.14.6
```
