#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""宿主新版本挂载点漂移校验 —— MT MCP 精准路（2026-11-07 起）。

为什么不用 adapt_new_version.py 的 jadx 全量反编译（背景 2026-11-06 实战）：
- 本机 = 目标机，/storage noexec + 整机内存吃紧，jadx 全量 3.5 万类要数小时且易被 LMK 挤死；
- MT MCP 按类直读 dex 大纲（方法名+签名），每目标秒级~分钟级，精度够（方法名+参数个数比对，
  与 MountEngine 运行期判定同口径）。
- MT MCP 0.2.0 新文件策略：APK 目标必须 mt://installed-app/<包名>（任意 /storage 路径被拒）。

用法：
  python3 tools/adapt_mt.py <包名> [<包名>...]   # 包名 = mt://installed-app 目标
  python3 tools/adapt_mt.py all                  # 2026-11-06 漂移的 8 个目标

报告落 tools/adapt-apks-20261106/<包名>.mt-adapt.txt
"""
import re
import sys
import time
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / 'tools'))

import mtmcp                    # noqa: E402
import adapt_new_version as av  # noqa: E402
import feature_catalog as fcat  # noqa: E402

# `all` 的包清单不再手写：取 FeatureCatalog 里有基线版本的目标（= 版本敏感的全部宿主）。
# 手写清单会过期（2026-11-06 那版 8 个包在 11-07 适配后已全部落表，仍留着会误导）。

PLATFORM_PREFIXES = ('java.', 'javax.', 'android.', 'androidx.', 'kotlin.', 'kotlinx.')


TARGET_DIR = REPO / 'app' / 'app' / 'src' / 'main' / 'kotlin' / 'top' / 'honorhook' / 'xposed' / 'target'

# 挂载点 → 宿主包名的归属（extract_mounts 的 pkg 字段只认 PKG/PKG_HN/PKG_HNOUC 三种常量，
# 其余文件全部落到全局 PKG = thememanager —— 2026-11-07 实测：thememanager 一份报告里混进
# systemui/installer/settings 的 127 个假 DRIFT，原因即此）。这里按源码结构重建归属：
#   ① 文件内 `override val packageName = "<pkg>"`（TargetHook 实现类，mms/xhs/sysmgr 等）
#   ② 同目录兄弟文件（themesore/、xhs/、mms/ 等把挂载表拆到同包多文件）
#   ③ TargetRegistry 的 `register(XxxHook("<pkg>"))` 参数化注册（ads/tracking 通杀 SDK 按包各注册）
def files_for_pkg(pkg: str) -> set:
    return {f for f, pkgs in file_owners().items() if pkg in pkgs}


def file_owners() -> dict:
    """文件 → 宿主包名集合（一次扫描，供 files_for_pkg 与 mounts_for 共用）。"""
    owners = defaultdict(set)
    reg = (TARGET_DIR / 'TargetRegistry.kt').read_text(encoding='utf-8', errors='replace')
    for cls, p in re.findall(r'register\((\w+)\("([^"]+)"\)\)', reg):
        owners[cls + '.kt'].add(p)          # 同文件可注册多包（ads/tracking 通杀 SDK）
    for f in TARGET_DIR.rglob('*.kt'):
        src = f.read_text(encoding='utf-8', errors='replace')
        # 优先 TargetHook 的 packageName，其次文件自己的 PKG / PKG_HN / PKG_HNOUC 常量
        # （InstallerHook/SystemUiHook/LauncherHook/OucHook 走常量，不写 packageName）
        m = (re.search(r'override\s+val\s+packageName\s*=\s*"([^"]+)"', src)
             or re.search(r'(?:private|internal)\s+const\s+val\s+PKG(?:_HN|_HNOUC)?\s*=\s*"([^"]+)"', src))
        if m:
            owners[f.name].add(m.group(1))
            if f.parent != TARGET_DIR:      # 顶层目录是 target/ 根，不能整棵收
                for g in f.parent.glob('*.kt'):
                    owners[g.name].add(m.group(1))
    # ④ 引用归属：数据表文件（InstallerMap_*/LauncherMap_* 等）自己不声明包名，靠 hook 文件引用
    #   其顶层 val 来归属。做法 = 取表文件的 public val 名，在已归属文件里找引用者，继承其包名。
    unowned = [f for f in TARGET_DIR.rglob('*.kt') if not owners.get(f.name)]
    texts = {f.name: f.read_text(encoding='utf-8', errors='replace')
             for f in TARGET_DIR.rglob('*.kt') if owners.get(f.name)}
    for f in unowned:
        names = re.findall(r'^(?:val|internal val)\s+([A-Z][A-Z0-9_]+)\s*[:=]',
                           f.read_text(encoding='utf-8', errors='replace'), re.M)
        for owner_name, src in texts.items():
            if any(n in src for n in names):
                owners[f.name] |= owners[owner_name]
    return owners


def mounts_for(pkg: str) -> list:
    """该宿主包的挂载点：文件归属优先（extract_mounts 的 pkg 只对 PKG 三常量可信），
    无归属的文件才回退到 pkg 字段。

    两个必须在此纠正的抽取层缺陷（2026-11-07 实测）：
    ① `$PKG` 模板：adapt_new_version 用全局 PKG（= 最后一个定义者 thememanager）替换所有文件的
       `$PKG`，AppMarketHook 的 `$PKG.operation.*` 因此被写成 thememanager 的类名 → 用文件自己的
       `const val PKG` 重拼；
    ② `${'$'}` 转义：tracking hook 用 `HiAnalyticsInstance${'$'}Builder` 绕 Kotlin 模板，抽取层原样
       保留，照抄查类必然 "Class not found" → 归一成 `$`。
    """
    owners = file_owners()
    consts = pkg_constants()
    default_pkg = av_pkg_default()
    out = []
    for mp in av.extract_mounts():
        pk = owners.get(mp.file)
        if (pkg in pk) if pk else (mp.pkg == pkg):
            cls = mp.cls.replace("${'$'}", '$')
            own = consts.get(mp.file)
            if own and default_pkg and own != default_pkg and cls.startswith(default_pkg + '.'):
                cls = own + cls[len(default_pkg):]
            mp.cls = cls
            out.append(mp)
    return out


_PKG_RE = re.compile(r'(?:private|internal)\s+const\s+val\s+PKG\s*=\s*"([^"]+)"')


def pkg_constants() -> dict:
    """文件 → 该文件自己的 `const val PKG`（没有则不在表里）。"""
    out = {}
    for f in av.TARGET_DIR.rglob('*.kt'):
        m = _PKG_RE.search(f.read_text(encoding='utf-8', errors='replace'))
        if m:
            out[f.name] = m.group(1)
    return out


def av_pkg_default() -> str:
    """adapt_new_version 抽取时用的全局 PKG（按它自己的遍历顺序取最后一个定义者）。"""
    v = ''
    for f in sorted(av.TARGET_DIR.rglob('*.kt')):
        m = _PKG_RE.search(f.read_text(encoding='utf-8', errors='replace'))
        if m:
            v = m.group(1)
    return v


def smali_methods(apk, cls, cache):
    """类 smali 的方法签名清单（outline 的兜底：outline 对合成/超大方法会漏列，
    2026-11-07 实测 SingleCutProgressView#setLeftCropPosition 漏；smali 是字节码直译，
    `.method` 行即全量真名，唯一风险是 mtmcp 的 4 万字符截断）。"""
    if cls in cache:
        return cache[cls]
    try:
        text = apk.smali(cls)
    except Exception:
        text = ''
    sigs = re.findall(r'^\.method .*?(\S+?)\((.*)\)(\S+)\s*$', text, re.M)
    cache[cls] = [(n, '(' + p + ')' + r) for n, p, r in sigs if n]
    return cache[cls]


def verify(apk, mp, outline_cache, smali_cache=None):
    """单点校验（方法名+参数个数，与 MountEngine findMethod 同口径）→ (状态, 备注)"""
    if mp.cls.startswith(PLATFORM_PREFIXES):
        return 'PLATFORM', '平台类（bootclasspath 恒存在）'
    if mp.cls not in outline_cache:
        try:
            outline_cache[mp.cls] = apk.outline(mp.cls)
        except Exception as e:
            outline_cache[mp.cls] = {'__err__': str(e)[:160]}
    data = outline_cache[mp.cls]
    if '__err__' in data:
        return 'CLASS_MISSING', f'类不存在或读取失败: {data["__err__"]}'
    got = [m for m in data.get('methods', []) if m['name'] == mp.method]
    counts = sorted({mtmcp.param_count(m['sig']) for m in got})
    if mp.param_count in counts:
        return 'ALIVE', 'ok'
    if counts:
        return 'PARAM_CHANGED', f'期望参数 {mp.param_count}，实际 {counts}'
    # outline 没列到 ≠ 方法不存在：用 smali 直译再确认一次（高频短名/合成方法 outline 会漏）
    sm_cache = smali_cache if smali_cache is not None else {}
    sgot = [sig for name, sig in smali_methods(apk, mp.cls, sm_cache) if name == mp.method]
    scounts = sorted({mtmcp.param_count(s) for s in sgot})
    if mp.param_count in scounts:
        return 'ALIVE', 'ok（smali 兜底命中，outline 漏列）'
    if scounts:
        return 'PARAM_CHANGED', f'期望参数 {mp.param_count}，实际 {scounts}（smali 兜底）'
    return 'METHOD_MISSING', f'类存在但无方法 {mp.method}（任何参数个数）'


def run(pkg: str) -> Path:
    mounts = mounts_for(pkg)
    report = REPO / 'tools' / f'mt-adapt-{pkg}.txt'
    if not mounts:
        report.write_text(f'{pkg}: hook 源码里没有该包的挂载点\n')
        return report
    apk = mtmcp.Apk('mt://installed-app/%s' % pkg)
    outline_cache, smali_cache = {}, {}
    by_cls = defaultdict(list)
    for mp in mounts:
        by_cls[mp.cls].append(mp)
    lines = [f'# {pkg} 挂载点漂移校验（MT MCP 精准路，{time.strftime("%Y-%m-%d %H:%M")}）',
             f'# 挂载点 {len(mounts)} 个 / 类 {len(by_cls)} 个']
    stat = defaultdict(int)
    for cls, mps in sorted(by_cls.items()):
        # 以 outline 为主（全量方法名+签名），miss 时用 smali `.method` 行兜底：
        # 曾经用 dex_names 搜方法名，高频短名（b/a/build/<init>）在默认 50 条里搜不到目标类
        # ⇒ 2026-11-07 报出 okhttp3.OkHttpClient$Builder#build 这种必然存在的假阴性。
        for mp in mps:
            st, note = verify(apk, mp, outline_cache, smali_cache)
            stat[st] += 1
            if st != 'ALIVE':
                lines.append(f'[{st}] {mp.mount_id}  {cls}# {mp.method}({mp.param_count}) —— {note}')
    alive = stat.pop('ALIVE', 0)
    lines.insert(2, f'# 结果: ALIVE {alive}/{len(mounts)}  ' +
                 '  '.join(f'{k} {v}' for k, v in stat.items()))
    report.write_text('\n'.join(lines) + '\n')
    return report


def main():
    if len(sys.argv) < 2 or sys.argv[1] == 'all':
        pkgs = fcat.version_sensitive()
    else:
        pkgs = sys.argv[1:]
    for pkg in pkgs:
        t0 = time.time()
        r = run(pkg)
        head = r.read_text().splitlines()[2]
        print(f'{pkg}: {head}  ({int(time.time()-t0)}s) -> {r.name}')


if __name__ == '__main__':
    main()
