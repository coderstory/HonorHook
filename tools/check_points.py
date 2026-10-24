#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""挂载点存活核对 —— 对新版本 APK 逐点验证「类在 + 方法在 + 参数数同」（MT MCP 版）。

与 tools/adapt_new_version.py 的分工：
- adapt_new_version.py：jadx 全量反编译路线（桌面机用，产出逐点报告 + 漂移明细）。
- 本脚本：MT MCP 直读 dex 路线（on-device 用，不反编译、不吃内存）。
  2026-10-23 主题商店 49 点 / 小红书坐标核对实战验证。

点清单 SPEC（JSON 文件或 stdin）：
[
  {"class": "com.hihonor.android.thememanager.common.ItemInfo",
   "points": [["isNeedPayItem", 0, "A1"], ["setPrice", 1, "A5-setter"]]},
  {"class": "u15", "points": [["getBoolean", 2, "cloud.2"], ["getBoolean", 1, "cloud.1"]]}
]
  points 每项 = [方法名, 参数个数, 备注id]；class 允许 java 名或 smali 描述符。

用法：
  python3 tools/check_points.py <apk路径> <spec.json>
  cat spec.json | python3 tools/check_points.py <apk路径> -
退出码：0 = 无丢失点；1 = 有丢失/漂移（CI 可作门禁）。
"""
import json
import sys

from mtmcp import Apk, param_count  # 同目录，sys.path[0] 即 tools/


def check(apk: Apk, spec: list, verbose=True):
    alive = drift = missing = 0
    lines = []
    for entry in spec:
        cls = entry["class"]
        try:
            d = apk.outline(cls)
        except RuntimeError as e:
            lines.append(f"❌ {cls} — 类不存在/读失败：{e}")
            missing += len(entry["points"])
            continue
        methods = d.get("methods", [])
        short = cls.replace("L", "").replace(";", "").replace("/", ".")
        for name, pc, pid in entry["points"]:
            cands = [m for m in methods if m["name"] == name]
            hit = [m for m in cands if param_count(m.get("sig", "")) == pc]
            if hit:
                alive += 1
                if verbose:
                    lines.append(f"✅ {pid} {short}#{name}({pc}) — {hit[0].get('sig')}")
            elif cands:
                drift += 1
                sigs = "; ".join(m.get("sig", "") for m in cands[:3])
                lines.append(f"⚠️ {pid} {short}#{name} — 参数数漂移（要 {pc}）：{sigs}")
            else:
                missing += 1
                lines.append(f"❌ {pid} {short}#{name}({pc}) — 方法不存在（类在，{len(methods)} 方法）")
    print(f"\n===== 汇总：存活 {alive} / 漂移 {drift} / 丢失 {missing} =====")
    for line in lines:
        if verbose or not line.startswith("✅"):
            print(line)
    return missing == 0 and drift == 0


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        raise SystemExit(2)
    apk_path, spec_path = sys.argv[1], sys.argv[2]
    spec = json.load(open(spec_path, encoding="utf-8") if spec_path != "-"
                     else sys.stdin)
    apk = Apk(apk_path)
    ok = check(apk, spec)
    raise SystemExit(0 if ok else 1)


if __name__ == "__main__":
    main()
