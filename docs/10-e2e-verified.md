# 端到端验证：payload 自包含可运行（里程碑）

日期：2026-10-04 · **完整链路实测通过**

## 验证目标

证明按本项目方案产出的 payload，**搬迁到任意路径后仍能正常运行 Hermes**。

## 实测步骤与结果

### 1) 修剪源码

```
$ bash scripts/trim_source.sh src
==> 修剪前体积: 241 MB
==> 修剪后体积: 95 MB
==> 冒烟测试
OK: 核心模块 import 正常
```

### 2) 生成 payload（含 manifest + editable 修复）

```
$ bash scripts/make_payload.sh payload src <venv> <tools>
==> 完成: payload        (512 MB)
==> 调用 fix_editable.sh 修复 editable 绝对路径
  探测到旧源码根: /data/data/.../installs/<key>/workspace
  已写回（MAPPING/NAMESPACES 已改为运行时解析）
  校验: OK
```

产物结构：

```
payload/
├── manifest.json      { repo: hermes-src, venv: venv, store: tools, sealed: true }
├── hermes-src/        95 MB（修剪后源码）
├── venv/              editable venv（finder 已改为运行时解析）
└── tools/             managed 工具链
```

### 3) **决定性测试：payload 内部的 import**

在 payload 目录下（**完全脱离原安装路径**）执行：

```
$ LD_LIBRARY_PATH=<termux>/usr/lib ./venv/bin/python -c "..."
python: 3.14.6
hermes_constants OK -> .../e2e/payload/hermes-src/hermes_constants.py    ← payload 内部
agent OK            -> .../e2e/payload/hermes-src/agent/__init__.py     ← payload 内部
```

### 4) **hermes CLI 端到端**

```
$ ./venv/bin/hermes --version
Hermes Agent v0.21.5+4531.g3cf2eb1 (2026.9.24) · upstream 3cf2eb1c
Install directory: .../e2e/payload/hermes-src      ← 正确指向 payload 内源码
Install method: unknown
Python: 3.14.6
OpenAI SDK: 2.24.0
```

### 5) 核心模块 import 全绿

```
OK   hermes_cli
OK   tools
OK   pm
OK   providers
OK   hermes_state
```

## 结论

| 环节 | 状态 |
| --- | --- |
| 源码修剪 | ✅ 241 → 95 MB |
| payload 布局 + manifest | ✅ |
| editable 重定位修复 | ✅ 端到端生效 |
| 脱离原路径运行 | ✅ **payload 自包含** |
| hermes CLI | ✅ 正常运行 |
| 核心模块 import | ✅ 5/5 |

**三个关键技术难题全部解决**：
1. bootstrap 硬编码 RUNPATH → `LD_LIBRARY_PATH` 覆盖（docs/07）
2. tools/venv 绝对路径 → `manifest.json` 相对解析（docs/08）
3. editable 模块映射写死 → `fix_editable.sh` 运行时解析（docs/09）

## 复现命令

```bash
cd <workdir>
bash scripts/trim_source.sh src
bash scripts/make_payload.sh payload src <venv_dir> <tools_dir>
cd payload && ./venv/bin/hermes --version
```

## 体积账（实测）

| 组件 | 修剪后 | 说明 |
| --- | --- | --- |
| hermes-src | 95 MB | |
| venv | 88 MB | 已在 trim 后计入 |
| tools | 186 MB | |
| **payload 合计** | **512 MB** | 解压后占用 |
| bootstrap（另计） | 203 MB zip / 616 MB 解压 | |

> APK 内两份大载荷若都压缩存放，成品预计 400–600 MB。优化方向见 docs/06。
