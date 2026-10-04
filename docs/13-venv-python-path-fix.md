# 关键缺陷：venv 的 Python 路径未重写（会直接导致 APK 跑不起来）

日期：2026-10-04 · 代码审查中发现 · **尚未在真机验证，但机制确定**

## 问题

`uv` 建的 venv 有**两处绝对路径**指向构建机上的 base python：

### 1. `venv/bin/python` 是绝对符号链接

```
venv/bin/python -> /data/data/com.termux/files/home/.hermes/tools/python-3.14.7+.../data/data/com.termux/files/usr/bin/python3.14
venv/bin/python3 -> python          （相对，OK）
venv/bin/python3.14 -> python       （相对，OK）
```

搬到 APK 后，`/data/data/com.termux/files/home/.hermes/tools/...` **不存在** → 软链断掉。

### 2. `pyvenv.cfg` 的 `home=` 是绝对路径

```
home = /data/data/com.termux/files/home/.hermes/tools/python-3.14.7+.../data/data/com.termux/files/usr/bin
implementation = CPython
uv = 0.12.15
version_info = 3.14.6
include-system-site-packages = false
relocatable = true
```

**CPython 启动时靠 `home=` 推导 `base_prefix`，再据此定位标准库**（`lib/python3.14/`）。
路径不存在 → **找不到 stdlib → 解释器直接崩**。

> 注：`relocatable = true` 是 uv 写的标记，但它**只影响 uv 自身的重新解析行为**，
> 不改变 CPython 解释器启动时读取 `home=` 的事实。

## 为什么之前的端到端验证没发现

`docs/10` 的验证是在**本机**跑的——那时 base python 仍在原始路径
（`~/.hermes/tools/python-3.14.7+...`），软链和 `home=` 恰好都能解析，所以「看起来正常」。

**这是验证方法的盲区**：只测了「payload 内部相对路径是否正确」，
没测「脱离构建机绝对路径后是否仍能启动」。

## 修复（`scripts/fix_python_paths.sh`）

APK 的安装路径是**确定的**：

```
应用私有目录 = /data/data/<applicationId>/files
载荷目录     = /data/data/<applicationId>/files/opt
（applicationId = com.nousresearch.hermesandroid，见 app/build.gradle）
```

既然路径确定，就直接**写死为 APK 的最终路径**（Hermes 官方文档也是这个建议：
"最稳做法是在最终绝对路径上直接建 venv"）：

1. **重写 `venv/bin/python*` 软链** → 指向 `<target>/tools/python-*/.../bin/python3.14`
2. **重写 `pyvenv.cfg` 的 `home=`** → `<target>/tools/python-*/.../usr/bin`
3. **修正 `venv/bin/*` 脚本的 shebang**（若有绝对 python 路径）
4. **产出 `paths.env`**：记录相对 payload 根的库路径，供 Java 侧拼装 `LD_LIBRARY_PATH`
5. **残留检查**：确认无遗留的构建机绝对路径

## 与 RUNPATH 问题的关系

managed python 的 ELF `RUNPATH` 硬编码 `/data/data/com.termux/files/usr/lib`，
需要 `libpython3.14.so`。该库在两个位置各有一份：

- managed python 自己的树：`tools/python-*/data/data/com.termux/files/usr/lib/`
- bootstrap：`$PREFIX/lib/`

由于 `DT_RUNPATH` 优先级**低于** `LD_LIBRARY_PATH`（docs/07 已实测），
由 Java 侧注入正确路径即可覆盖。`paths.env` 就是为了让 Java 知道该注入哪些路径。

## 教训（写入项目规范）

**验证 payload 可搬迁性时，必须真正脱离构建机路径**，例如：
- 把 payload 复制到一个全新的、不存在的路径下再测试，或
- 检查所有软链与配置文件中的绝对路径是否指向构建机

只测「相对路径解析正确」是不够的。

## 待办

- [ ] 在 CI 的 smoke_test 里加「绝对路径泄漏」检查（软链 + pyvenv.cfg）
- [ ] 真机安装后验证 `hermes --version` 能否输出
