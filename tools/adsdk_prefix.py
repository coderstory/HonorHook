#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""定位荣耀广告 SDK 的精确包根，并找跨 app 公共包。
对每个 apk：列出 Lcom/h(i)?(ih)?onor/ 下各 2~3 段包前缀的类数，筛出广告相关。
"""
import os, re, sys, zipfile, struct

def read_uleb128(b, off):
    result = 0; shift = 0; i = off
    while True:
        x = b[i]; i += 1
        result |= (x & 0x7f) << shift
        if not (x & 0x80): break
        shift += 7
    return result, i

def dex_type_strings(dex_bytes):
    if dex_bytes[:4] not in (b"dex\n", b"dey\n"):
        return set()
    (ss,) = struct.unpack_from("<I", dex_bytes, 0x38)
    (so,) = struct.unpack_from("<I", dex_bytes, 0x3c)
    (ts,) = struct.unpack_from("<I", dex_bytes, 0x40)
    (to,) = struct.unpack_from("<I", dex_bytes, 0x44)
    strings = []
    for i in range(ss):
        (o,) = struct.unpack_from("<I", dex_bytes, so + i*4)
        _, p = read_uleb128(dex_bytes, o)
        end = dex_bytes.index(b"\x00", p)
        strings.append(dex_bytes[p:end].decode("utf-8", "replace"))
    types = set()
    for i in range(ts):
        (d,) = struct.unpack_from("<I", dex_bytes, to + i*4)
        if 0 <= d < len(strings):
            types.add(strings[d])
    return types

def dump_classes(apk):
    out = set()
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if re.match(r"classes\d*\.dex$", n):
                try: out |= dex_type_strings(z.read(n))
                except Exception as e: sys.stderr.write(f"skip {n}: {e}\n")
    return out

HONOR_RE = re.compile(r"^Lcom/(hihonor|honor)/([^/]+/)([^;]*)$")
AD_KW = re.compile(r"ad|advert|promo|recommend|feed|splash|banner|native|reward|interstiti", re.I)

def main():
    d = sys.argv[1] if len(sys.argv) > 1 else "."
    apks = sorted(f for f in os.listdir(d) if f.endswith(".apk"))
    per_app = {}
    for apk in apks:
        classes = dump_classes(os.path.join(d, apk))
        # 2 段包前缀计数（含 ad 关键词）
        seg2 = {}
        for c in classes:
            m = HONOR_RE.match(c)
            if m:
                if AD_KW.search(m.group(2)) or AD_KW.search(m.group(3)):
                    seg2.setdefault(m.group(2), 0)
                    seg2[m.group(2)] += 1
        per_app[apk] = seg2
        print(f"\n### {apk}  hihonor/honor 2段广告相关包前缀:")
        for k, v in sorted(seg2.items(), key=lambda x: -x[1]):
            print(f"  Lcom/(hihonor|honor)/{k}  {v}")

    # 跨 app 公共 2段包（在每个 app 都出现）
    print("\n=== 跨 app 公共 2段包（每个 app 都出现的广告相关荣耀包）===")
    common = None
    for apk, seg2 in per_app.items():
        keys = set(seg2.keys())
        common = keys if common is None else (common & keys)
    if common:
        for k in sorted(common):
            present = {a: per_app[a].get(k, 0) for a in per_app}
            print(f"  Lcom/(hihonor|honor)/{k}  -> " + ", ".join(f"{a.split('.')[0]}:{v}" for a, v in present.items()))
    else:
        print("  无（各 app 广告相关荣耀包不完全一致）")

if __name__ == "__main__":
    main()
