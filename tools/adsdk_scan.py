#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""扫描荣耀系统 app 的 dex，按广告 SDK 包前缀统计构成，找跨 app 公共分母。
纯 Python 解析 dex type_id 表（不依赖 dexdump，快且稳）。
用法：python3 tools/adsdk_scan.py <apk_dir>
"""
import os, re, sys, zipfile, struct

# (标签, 编译正则) —— 已知广告/变现 SDK 的包特征
SDK = [
    ("Pangle/穿山甲",      re.compile(r"Lcom/bytedance/sdk/openadsdk/")),
    ("Pangle-broad",      re.compile(r"Lcom/bytedance/")),
    ("GDT/广点通",         re.compile(r"Lcom/qq/e/(ads|comm|release)/")),
    ("GDT/腾讯统一",       re.compile(r"Lcom/tencent/(ams|gdt|adv|taes)/")),
    ("KSAd/快手",          re.compile(r"Lcom/(kwad|ksad)/sdk/")),
    ("HuaweiAds/华为广告", re.compile(r"Lcom/huawei/(hms/ads|ads)/")),
    ("Huawei-hms-broad",  re.compile(r"Lcom/huawei/hms/")),
    ("HonorAds/荣耀广告",  re.compile(r"Lcom/h(i)?(ih)?onor/[^;]*(ads|ad/|advert)")),
    ("Honor-hms",         re.compile(r"Lcom/h(i)?(ih)?onor/hms/")),
    ("Baidu/百度",         re.compile(r"Lcom/baidu/mobads/")),
    ("Mintegral",         re.compile(r"Lcom/(mintegral|mbridge)/")),
    ("Sigmob",            re.compile(r"Lcom/sigmob/")),
    ("InMobi",            re.compile(r"Lcom/inmobi/")),
    ("AdMob/Google",      re.compile(r"Lcom/google/android/gms/ads/")),
    ("Umeng/友盟",        re.compile(r"Lcom/umeng/")),
    ("Mob",               re.compile(r"Lcom/mob/")),
    ("Kuaishou/快手-lite", re.compile(r"Lcom/kuaishou/")),
    ("JAD/巨量",           re.compile(r"Lcom/ss/android/agp/")),
    ("AppLovin",          re.compile(r"Lcom/applovin/")),
    ("IronSource",        re.compile(r"Lcom/ironsource/")),
    ("UnityAds",          re.compile(r"Lcom/unity3d/")),
    ("Vungle",            re.compile(r"Lcom/vungle/")),
    ("TopOn",             re.compile(r"Lcom/topon/")),
    ("AnyThink",          re.compile(r"Lcom/anythink/")),
    ("Pangle-CN",         re.compile(r"Lcom/pangle/")),
    ("Admobile",          re.compile(r"L(cn|om)/admobile/")),
]

def read_uleb128(b, off):
    result = 0; shift = 0; i = off
    while True:
        x = b[i]; i += 1
        result |= (x & 0x7f) << shift
        if not (x & 0x80): break
        shift += 7
    return result, i

def dex_type_strings(dex_bytes):
    """返回该 dex 中所有类型描述符字符串集合（去重）。"""
    if dex_bytes[:4] not in (b"dex\n", b"dey\n"):
        return set()
    (string_ids_size,) = struct.unpack_from("<I", dex_bytes, 0x38)
    (string_ids_off,) = struct.unpack_from("<I", dex_bytes, 0x3c)
    (type_ids_size,) = struct.unpack_from("<I", dex_bytes, 0x40)
    (type_ids_off,) = struct.unpack_from("<I", dex_bytes, 0x44)
    # 读字符串
    strings = []
    for i in range(string_ids_size):
        (s_off,) = struct.unpack_from("<I", dex_bytes, string_ids_off + i*4)
        _, p = read_uleb128(dex_bytes, s_off)
        end = dex_bytes.index(b"\x00", p)
        strings.append(dex_bytes[p:end].decode("utf-8", "replace"))
    types = set()
    for i in range(type_ids_size):
        (desc_idx,) = struct.unpack_from("<I", dex_bytes, type_ids_off + i*4)
        if 0 <= desc_idx < len(strings):
            types.add(strings[desc_idx])
    return types

def dump_classes(apk):
    out = set()
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if re.match(r"classes\d*\.dex$", n):
                try:
                    out |= dex_type_strings(z.read(n))
                except Exception as e:
                    sys.stderr.write(f"skip {n}: {e}\n")
    return out

def main():
    d = sys.argv[1] if len(sys.argv) > 1 else "."
    apks = sorted(f for f in os.listdir(d) if f.endswith(".apk"))
    results = {}
    for apk in apks:
        classes = dump_classes(os.path.join(d, apk))
        cnt = {label: 0 for label, _ in SDK}
        for c in classes:
            for label, rx in SDK:
                if rx.search(c):
                    cnt[label] += 1
        results[apk] = (len(classes), cnt)
        print(f"\n### {apk}  (class types={len(classes)})")
        for label, _ in SDK:
            if cnt[label]:
                print(f"  {label:22s} {cnt[label]}")
    print("\n=== 跨 app 广告 SDK 矩阵 ===")
    apps = list(results.keys())
    hdr = "SDK".ljust(22) + "".join(a[:9].ljust(11) for a in apps)
    print(hdr)
    for label, _ in SDK:
        if any(results[a][1][label] for a in apps):
            row = label.ljust(22)
            for a in apps:
                n = results[a][1][label]
                row += str(n if n else "-").ljust(11)
            print(row)
    allin = [label for label, _ in SDK if apps and all(results[a][1][label] for a in apps)]
    print("\n>>> 每个 app 都命中的 SDK（通杀候选）:", allin or "无")

if __name__ == "__main__":
    main()
