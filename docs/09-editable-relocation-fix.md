# editable 重定位修复（实测验证）

日期：2026-10-04 · **端到端实测通过**

## 问题

Hermes 是 **editable 安装**（`pip install -e .`）。uv/pip 会生成：

```
<venv>/lib/python3.X/site-packages/__editable___hermes_agent_0_0_0_finder.py
```

其内容形如：

```python
MAPPING: dict[str, str] = {'hermes_constants': '/data/.../installs/<key>/workspace/hermes_constants',
                           'agent': '/data/.../installs/<key>/workspace/agent', ...}
NAMESPACES: dict[str, list[str]] = {'plugins.browser': ['/data/.../workspace/plugins/browser'], ...}
```

**几十个模块的绝对路径被写死**。搬到 APK 的 `files/opt/venv/` 后全部失效 →
`import hermes_constants` 直接报 `ModuleNotFoundError`。

## 解法（`scripts/fix_editable.sh`）

把静态字典**重写为运行时解析**：

```python
# 注入（放在 import 之后）
_SRC_ROOT = str(Path(__file__).resolve().parents[4] / "hermes-src")

# MAPPING 由静态字典改为推导式
MAPPING: dict[str, str] = {_k: _SRC_ROOT + _v
                           for _k, _v in {'hermes_constants': '/hermes_constants', ...}.items()}
```

路径推算（finder 在 `<payload>/venv/lib/pythonX.Y/site-packages/`）：

```
parents[0] = site-packages
parents[1] = python3.14
parents[2] = lib
parents[3] = venv
parents[4] = <payload>        ← 源码根 = parents[4] + "/hermes-src"
```

## 踩过的三个坑（都已在脚本里修正）

1. **不能用 `str.replace()` 注入拼接**
   初版把 `'/data/.../workspace` 替换成 `'" + _SRC_ROOT + "`，
   结果字典的值退化成**字面字符串** `'" + _SRC_ROOT + "/hermes_constants'`（语法能过，逻辑全错）。
   → 改为**重建整行**，生成真正的 dict 推导式。

2. **`parents[3]` 少一层**
   初版算成 `<payload>/venv/hermes-src`，正确是 `<payload>/hermes-src` → 应为 `parents[4]`。

3. **正则 `[^\]]*` 被 `list[str]` 的 `]` 截断**
   `NAMESPACES: dict[str, list[str]]` 里的 `]` 让正则提前结束，导致 NAMESPACES 未被替换。
   → 改用 `dict\[.*?\]`（非贪婪）。

## 端到端验证结果

构造 payload 后跑 `fix_editable.sh`，再实测：

| 验证项 | 结果 |
| --- | --- |
| `_SRC_ROOT` 运行时推算 | `<payload>/hermes-src` ✅ |
| `MAPPING['hermes_constants']` | `<payload>/hermes-src/hermes_constants` ✅ |
| 该路径下 `__init__.py` 存在 | True ✅ |
| `NAMESPACES` 替换 | ✅ 同前缀正确 |
| **`import hermes_constants`** | ✅ **成功** |
| 模块属性读取 | `VALUE = imported-from-src` ✅ |
| `py_compile` 语法检查 | OK ✅ |

## 用法

```bash
# 1) 摆好 payload 布局并生成 manifest.json
bash scripts/make_payload.sh <payload_root> <src_dir> <venv_dir> [tools_dir]
# 2) 修复 editable 的绝对路径
bash scripts/fix_editable.sh <payload_root>
```

> 注意：跑完 fix 后如曾 import 过，记得清理 `__pycache__`。

## 另一个选择（更彻底）

若不想依赖 fix 脚本，可在 CI 里**用非 editable 方式安装**：
`pip install '.[termux]'`（不带 `-e`）会把源码**复制**进 site-packages，
不存在 `MAPPING` 绝对路径问题。代价是源码与 site-packages 各存一份（体积翻倍）
且失去「改源码立即生效」。

**本项目选择**：保留 editable + `fix_editable.sh`（省体积、保持可改）。
