#!/bin/bash
# 一键：编译 debug → 覆盖安装 → 重新启用 LSPosed 模块（install 会先卸载 ⇒ 模块必被停用，必须跟 enable）。
# 等价于手跑 dev.sh build + install + enable-module。

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

bash "$ROOT/tools/dev.sh" build
bash "$ROOT/tools/dev.sh" install
bash "$ROOT/tools/dev.sh" enable-module
