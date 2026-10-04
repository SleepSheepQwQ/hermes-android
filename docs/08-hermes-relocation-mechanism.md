# Hermes 官方重定位机制（实测验证）

日期：2026-10-04 · 基于 hermes-agent 源码阅读 + 本机实跑验证

## 一、三个定位相关的环境变量/机制

| 机制 | 源码位置 | 作用 |
| --- | --- | --- |
| `HERMES_HOME` | `hermes_constants.py::get_hermes_home()` | 整体重定位用户数据目录（sessions/logs/skills…） |
| `HERMES_RUNTIME_DIR` | `pm/environments.py::store_root()` (L112) | **最高优先级**指定 tools store，跳过 PM 下载 |
| `manifest.json` | `pm/environments.py::store_root()` / `payload_venv()` | **相对路径**解析 store 和 venv，免疫搬迁 |

## 二、install_key 与 manifest 的关系（关键）

```python
def install_key(project_root: Path) -> str:
    canonical = str(Path(project_root).resolve())
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]
```

- **install_key = sha256(源码绝对路径)[:16]**
- 本机实测：`install_key('/data/data/com.termux/files/home/.hermes/hermes-agent')`
  = `07e6df5db080b527` —— **正是本机 `~/.hermes/installs/` 下的目录名**，确证无疑。
- **风险**：路径一变，key 就变 → 会新建一个 installs 目录 → 重新做依赖解析。

## 三、manifest.json 如何化解风险（已实测）

`store_root()` 的解析顺序：

```python
1. HERMES_RUNTIME_DIR 环境变量   ← 最高优先级
2. <root.parent>/manifest.json 的 "store" 字段（相对路径）
3. 向上找 .install-stamp.json 的 runtimeDir
4. 兜底 ~/.hermes/tools
```

`payload_venv()` 同理读 `manifest["venv"]`（相对路径）。

### 实测对照

构造 `payload-test/{manifest.json, hermes-src/, venv/, tools/}` 后调用：

| 调用 | 无 manifest（本机真实环境） | 有 manifest（payload） |
| --- | --- | --- |
| `store_root()` | `/data/data/com.termux/files/home/.hermes/tools` | **`<payload>/tools`** ✅ |
| `payload_venv()` | `None` | **`<payload>/venv`** ✅ |

**结论：manifest.json 让 store/venv 走相对路径解析，搬到 APK 任意路径都能正确工作。**

## 四、manifest.json 的硬性要求（踩坑记录）

源码用的是**下标访问**，不是 `.get()`：

```python
store  = (root.parent / manifest["store"]).resolve()   # 缺 "store" → KeyError
venv   = (root.parent / manifest["venv"]).resolve()    # 缺 "venv"  → KeyError
```

且生效前提：
```python
if (root.parent / manifest.get("repo", "")).resolve() == root:
```
→ **源码目录必须是 manifest 所在目录的子目录，且名字与 `repo` 字段一致**。

### 正确的 manifest.json

```json
{
  "version": 1,
  "repo": "hermes-src",
  "venv": "venv",
  "store": "tools",
  "sealed": true
}
```
**三个字段都不可省。**

### 正确的目录布局

```
<payload_root>/
├── manifest.json
├── hermes-src/      ← 名字必须 == manifest["repo"]
├── venv/            ← 名字必须 == manifest["venv"]
└── tools/           ← 名字必须 == manifest["store"]
```

## 五、其它实测结论

- **pyvenv.cfg 的 `home=`**：本机 venv 里是
  `home = ~/.hermes/tools/python-3.14.7+...-bionic/data/data/com.termux/files/usr/bin`
- **Hermes 不读 `home=`**（只读 `version_info` 行），且 uv 建的 venv 带 **`relocatable = true`**
- venv 的 `bin/hermes` shebang 是**相对写法**（`#!/bin/sh` + `exec "$(dirname "$0")/python"`），
  不是硬编码绝对路径 → **可直接搬迁** ✅

## 六、对 APK 打包的结论

1. 载荷必须打成 **sealed payload 布局**（`manifest.json` + 三个子目录），由
   `scripts/make_payload.sh` 生成。
2. 运行时设 `HERMES_HOME`、`HERMES_RUNTIME_DIR`、`PREFIX`、`LD_LIBRARY_PATH`
   （已实现在 `BootstrapInstaller.hermesEnv()`）。
3. 不需要 patchelf（RUNPATH 可被 LD_LIBRARY_PATH 覆盖，docs/07 已实测）。
4. `HERMES_DISABLE_LAZY_INSTALLS=1` 可禁掉按需下载（工具已预置）。

## 七、参考路径

- `hermes_constants.py` — `get_hermes_home()` / `project_venv_dir()`
- `pm/environments.py` — `install_key()` L16、`payload_venv()` L94、`store_root()` L112、`base_venv()` L108
- `scripts/build/launchers.py` — `posix_launcher()` 生成官方 launcher 的方式
- `scripts/termux/stage_runtime_libs.py` — 把 Termux 库闭包合并成单目录（真机上 termux 库可能缺失）
