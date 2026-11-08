#!/usr/bin/env python3
"""宿主版本适配体检：设备实装 versionName vs FeatureCatalog 声明支持版本。

背景：OTA 后宿主版本会变，`FeatureCatalog.extraVersions` 没登记 ⇒ hook 照旧能挂但 UI 标「未适配」，
更糟的是按版本选表的目标会回落默认套（错表 = 挂不上）。以前靠手敲 dumpsys 逐个比对，
2026-11-07 固化成一条命令。

用法（一律走 dev.sh，别直接调）：
    bash tools/dev.sh versions                # 体检：列出未适配的，有一个就 exit 1
    bash tools/dev.sh versions --supported    # 离线：只打印支持版本表
    bash tools/dev.sh versions xhs mms        # 只查包名含这些子串的
"""

from __future__ import annotations

import re
import subprocess
import sys

import feature_catalog

DEV_SH = str(feature_catalog.REPO / 'tools' / 'dev.sh')


def parse_ver(v: str) -> tuple:
    """版本比较用：数字段元组，非数字尾缀（-release 等）按 0 计。"""
    return tuple(int(x) if x.isdigit() else 0 for x in re.split(r'[.\-]', v))


def installed_versions(pkgs: list[str]) -> dict[str, str | None]:
    """一次 dev.sh app-version 拿全部实装版本（内部按包逐个 su dumpsys，已是最省的做法）。"""
    out = subprocess.run(['bash', DEV_SH, 'app-version', *pkgs],
                         capture_output=True, text=True).stdout
    got: dict[str, str | None] = {}
    for line in out.splitlines():
        m = re.match(r'^(\S+)\s+(\S+)\s*$', line)
        if not m:
            continue
        got[m.group(1)] = None if m.group(2).startswith('(') else m.group(2)
    return got


def main() -> int:
    args = [a for a in sys.argv[1:]]
    if '--supported' in args:
        for t in feature_catalog.targets():
            vers = ','.join(t['supported']) or '(通杀/虚拟)'
            print(f"{t['package']:<45} {vers}")
        return 0

    filters = [a for a in args if not a.startswith('-')]
    targets = feature_catalog.targets()
    if filters:
        targets = [t for t in targets if any(f in t['package'] for f in filters)]

    pkgs = [t['package'] for t in targets]
    got = installed_versions(pkgs)

    ok, missing, mismatched = 0, [], []
    for t in targets:
        pkg = t['package']
        if pkg not in got:
            missing.append(pkg)          # app-version 没回行 = su/设备问题，不算未安装
            continue
        if got[pkg] is None:
            continue                     # 未安装：不参与体检
        ver = got[pkg]
        if ver in t['supported'] or not t['supported']:
            ok += 1
        else:
            top = max(t['supported'], key=parse_ver)
            hint = '更新（比最新支持版本还新）' if parse_ver(ver) > parse_ver(top) else '落后于基线'
            mismatched.append((pkg, ver, ','.join(t['supported']), hint))

    if mismatched:
        print('未适配（宿主版本不在 FeatureCatalog 支持列表里）:')
        for pkg, ver, sup, hint in mismatched:
            print(f'  {pkg:<45} 实装 {ver:<22} 支持 {sup}  ← {hint}')
    if missing:
        print('拿不到版本（检查设备连接/su）: ' + ', '.join(missing), file=sys.stderr)

    print(f'--- 已适配 {ok} / 未适配 {len(mismatched)} / 未安装或未知 {len(pkgs) - ok - len(mismatched)}')
    return 1 if mismatched else 0


if __name__ == '__main__':
    sys.exit(main())
