"""FeatureCatalog.kt 的最小解析器——目标包名 ↔ 支持版本集合。

为什么不解析 Kotlin 语义：这张表是纯字面量数据（`Target("包名", … baselineVersion=…, extraVersions=setOf(…))`），
正则沿 `Target(` 切段足够，且不引入依赖。**唯一事实源仍是 FeatureCatalog.kt**，本模块只读不写。

被谁用：
- `tools/version_check.py`（设备实装版本 vs 支持版本，判断哪些宿主没适配）
- `tools/adapt_mt.py`（`all` = 有基线版本的目标，即“版本敏感”的全部宿主，不再手工维护漂移清单）
"""

from __future__ import annotations

import pathlib
import re

REPO = pathlib.Path(__file__).resolve().parent.parent
CATALOG = REPO / 'app/app/src/main/kotlin/top/honorhook/FeatureCatalog.kt'

# 目标声明以 8 空格缩进的 `Target(` 开头（Feature(...) 也是 8 空格起，但名字不同，不会误切）
_TARGET = re.compile(r'\n        Target\(')
_PKG = re.compile(r'\s*"([\w.]+)"')
_BASELINE = re.compile(r'baselineVersion\s*=\s*"([^"]+)"')
_EXTRA = re.compile(r'extraVersions\s*=\s*setOf\(([^)]*)\)')
_STR = re.compile(r'"([^"]+)"')


def targets() -> list[dict]:
    """返回 [{'package': str, 'baseline': str|None, 'extra': [str], 'supported': [str]}]（声明序）。"""
    text = CATALOG.read_text(encoding='utf-8')
    out: list[dict] = []
    for seg in _TARGET.split(text)[1:]:
        m = _PKG.match(seg)
        if not m:
            continue
        pkg = m.group(1)
        b = _BASELINE.search(seg)
        e = _EXTRA.search(seg)
        baseline = b.group(1) if b else None
        extra = _STR.findall(e.group(1)) if e else []
        supported = ([baseline] if baseline else []) + extra
        out.append({'package': pkg, 'baseline': baseline,
                    'extra': extra, 'supported': supported})
    return out


def version_sensitive() -> list[str]:
    """有基线版本（= 按宿主版本选表/自检）的包名，即需要跟版本适配的那批。"""
    return [t['package'] for t in targets() if t['baseline']]


if __name__ == '__main__':
    rows = targets()
    for t in rows:
        print(f"{t['package']:<45} {','.join(t['supported']) or '(通杀/虚拟)'}")
    print(f'--- {len(rows)} targets / {len(version_sensitive())} 版本敏感')
