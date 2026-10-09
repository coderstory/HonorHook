#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
HonorHook 版本适配检查脚本 —— 「一键检测新版本 APK 的 hook 点是否失效」

移植自 reference/fanqie-purify（AI开发插件实践）/tools/adapt_new_version.sh 的四步法思路：
  fanqie 版：① 拉包（adb）→ ② 资源 id 比对（aapt）→ ③ 代码目标比对（dex 二进制 grep 类描述符）→ ④ 结论
  本版（HonorHook 目标）：① 输入新版目标 APK → ② jadx 反编译（--no-res）→ ③ hook 源码挂载点清单提取
  （类名 + 方法名 + 参数个数）→ ④ 在新版反编译产物里逐点验证「类存在 + 方法存在（名 + 参数数）」
  → ⑤ 输出比对报告（存活 N/M + 丢失点清单 + 结论）。

为什么不用 fanqie 的 dex 二进制 grep：fanqie 只验证类描述符在不在 dex 里，方法级验证做不到
（dex 字符串表无法可靠关联「方法名 → 所属类」）；HonorHook 的挂载点两处都精确到方法 + 参数个数
（findMethod 按 名 + 参数数 匹配），用 jadx 反编译产物做方法声明级比对更准确。

挂载点清单提取方式（重要同步规则）：
  清单**不是硬编码**的，每次运行自动从 Hook 源码解析，解析器只认两种既有书写格式：
    1) 位置式：addConst(pending, CLS_X, "方法名", 参数数, "返回类型", ...)
              addForceArg(pending, CLS_X, "方法名", 参数数, "返回类型", ...)
    2) 具名式：pending += MountTask(kind = MountKind.X, className = CLS_Y, methodName = "方法名",
                                    paramCount = 参数数, ...)
  类名来源：CLS_* 常量定义（private const val CLS_X = "...") 或 className 位置的字符串字面量
  （如 "java.net.Socket"）。$PKG 模板与 \\$ 转义会自动解析。
  → 之后新增/删除 hook 点只需照既有格式改 Hook.kt，本脚本自动纳入比对，无需同步任何清单。
  类名常量值里的 `$PKG` 是 Kotlin 字符串模板（展开为 PKG 包名）；`\\$` 是转义（内部类分隔符）。

用法：
  python tools/adapt_new_version.py <新版本目标 APK 路径> [选项]

选项：
  --report <md 路径>   报告输出路径（默认 system-files/adapt-report.md；基线验证时
                       用 --report system-files/adapt-report-baseline.md 留档）
  --expect-pkg <包名>   APK 的目标包名（主题商店 com.hihonor.android.thememanager /
                       安装器 com.android.packageinstaller）。声明后目标包不同的挂载点标
                       「目标包不在本 APK」而非类丢失——一个 APK 只含一个目标包
  --reuse <反编译目录>  复用已有的 jadx 反编译目录，跳过反编译步骤（改报告/换比对逻辑时省时）
  --clean             跑完后删除本脚本生成的反编译目录（默认保留，可再 --reuse）
  -j <线程数>          jadx 线程数（默认让 jadx 自定）

示例：
  python tools/adapt_new_version.py "system-files/主题_20.1.63.305.apk" \
      --expect-pkg com.hihonor.android.thememanager --report system-files/adapt-report-baseline.md
  python tools/adapt_new_version.py new.apk --reuse .buildtmp/adapt_jadx_new.apk

依赖：tools/jadx/bin/jadx.bat（本项目自带）+ JDK 21（优先 HH_JAVA_HOME 环境变量 →
  C:\\Program Files\\Eclipse Adoptium\\jdk-21* → JAVA_HOME → 系统 C:\\Program Files\\Java\\jdk-21*）。
"""

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

REPO = Path(__file__).resolve().parent.parent          # lsposed-plugin/
TARGET_DIR = (REPO / 'app' / 'app' / 'src' / 'main' / 'kotlin'
              / 'top' / 'honorhook' / 'xposed' / 'target')
JADX_BAT = REPO / 'tools' / 'jadx' / 'bin' / 'jadx.bat'
BUILDTMP = REPO / '.buildtmp'

# ---------------- 挂载点提取（Hook 源码 → 挂载表） ----------------

@dataclass
class MountPoint:
    file: str            # Hook 源码文件名（报告分组用）
    pkg: str             # 目标包名（com.hihonor.android.thememanager / com.android.packageinstaller）
    cls: str             # 全限定类名（含 $ 内部类）
    method: str          # 方法名
    param_count: int     # hook 期望的参数个数（findMethod 按 名 + 参数数 匹配）
    return_type: str     # hook 期望的返回类型（ThemeStoreHook 另按它匹配；空串表无）
    mount_id: str        # 幂等 ID（如 theme:ItemInfo.isNeedPayItem），报告追溯用

CLS_DEF_RE = re.compile(r'private\s+const\s+val\s+(CLS_\w+)\s*=\s*"((?:[^"\\]|\\.)*)"')
PKG_DEF_RE = re.compile(r'private\s+const\s+val\s+PKG\s*=\s*"((?:[^"\\]|\\.)*)"')
POS_MOUNT_RE = re.compile(
    r'\b(?:addConst|addForceArg)\(\s*pending\s*,\s*'
    r'(CLS_[A-Z0-9_]+|"(?:[^"\\]|\\.)*")\s*,\s*'
    r'"((?:[^"\\]|\\.)*)"\s*,\s*(\d+)\s*,\s*'
    r'"((?:[^"\\]|\\.)*)"')
NAMED_MOUNT_RE = re.compile(
    r'MountTask\(\s*kind\s*=\s*MountKind\.\w+\s*,\s*'
    r'className\s*=\s*(CLS_[A-Z0-9_]+|"(?:[^"\\]|\\.)*")\s*,\s*'
    r'methodName\s*=\s*"((?:[^"\\]|\\.)*)"\s*,\s*'
    r'paramCount\s*=\s*(\d+)',
    re.DOTALL)

def unescape_kotlin_str(raw: str) -> str:
    """Kotlin 字符串字面量 → 实际值（本项目只出现 \\$ 转义；通用处理 \\x）。"""
    return re.sub(r'\\(.)', r'\1', raw)

def resolve_class_name(cls_ref: str, clss: dict, pkg: str) -> str:
    """className 引用 → 全限定类名：CLS_* 查常量表；字符串字面量直接解析。"""
    if cls_ref.startswith('CLS_'):
        return clss[cls_ref]
    return unescape_kotlin_str(cls_ref).strip('"')   # 字符串字面量形式（如 "java.net.Socket"）剥引号

def extract_call_block(text: str, start: int) -> str:
    """从匹配起点（已含开括号）向后做括号深度扫描，截取整个调用块文本。失败返回 ''。"""
    depth = 0
    for i in range(start, len(text)):
        if text[i] == '(':
            depth += 1
        elif text[i] == ')':
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return ''

def extract_mounts() -> list:
    """从 target/ 目录全部 Hook 源码提取挂载点清单（按文件内出现顺序）。"""
    mounts: list = []
    hook_files = sorted(TARGET_DIR.glob('*Hook.kt'))
    hook_files = [f for f in hook_files if f.name != 'TargetHook.kt']
    if not hook_files:
        raise SystemExit(f'未在 {TARGET_DIR} 找到 Hook 源码文件')
    for hf in hook_files:
        src = hf.read_text(encoding='utf-8', errors='replace')
        pkg_m = PKG_DEF_RE.search(src)
        pkg = unescape_kotlin_str(pkg_m.group(1)) if pkg_m else ''
        clss = {}
        for name, raw in CLS_DEF_RE.findall(src):
            val = unescape_kotlin_str(raw)
            if '$PKG' in val and pkg:
                val = val.replace('$PKG', pkg)      # Kotlin 字符串模板展开
            clss[name] = val
        seen = set()
        for m in NAMED_MOUNT_RE.finditer(src):
            cls_ref, method, pcount = m.group(1), m.group(2), int(m.group(3))
            block = extract_call_block(src, m.start())
            rt_m = re.search(r'returnType\s*=\s*("(?:[^"\\]|\\.)*"|CLS_[A-Z0-9_]+)', block)
            ret = resolve_class_name(rt_m.group(1).strip('"'), clss, pkg) if rt_m else ''
            id_m = re.search(r'\bid\s*=\s*"([^"]+)"', block)
            _add(mounts, seen, hf.name, pkg, resolve_class_name(cls_ref, clss, pkg),
                 method, pcount, ret, id_m.group(1) if id_m else '')
        for m in POS_MOUNT_RE.finditer(src):
            cls_ref, method, pcount, ret = (m.group(1), m.group(2), int(m.group(3)), m.group(4))
            block = extract_call_block(src, m.start())
            id_m = re.search(r'"(?:theme|honorhook):[^"]+"', block)
            _add(mounts, seen, hf.name, pkg, resolve_class_name(cls_ref, clss, pkg),
                 method, pcount, unescape_kotlin_str(ret), id_m.group(0).strip('"') if id_m else '')
    if not mounts:
        raise SystemExit('挂载点提取结果为 0——Hook 源码里没有可识别的挂载调用，请检查书写格式')
    return mounts

def _add(mounts, seen, fname, pkg, cls, method, pcount, ret, mid):
    key = (fname, cls, method, pcount)
    if key in seen:
        return
    seen.add(key)
    mounts.append(MountPoint(fname, pkg, cls, method, pcount, ret, mid))

# ---------------- 反编译产物验证 ----------------

PLATFORM_PREFIXES = ('java.', 'javax.')               # bootclasspath 平台类，恒存在（断开 SKIP）
# 只保留「调用句」里会紧邻方法名的关键字（return/if(...)/new .../case 等）。
# 注意：void/synchronized/static/final 等是合法声明修饰与返回类型，绝不能进此表。
KEYWORDS = {'return', 'if', 'else', 'while', 'for', 'do', 'switch', 'case', 'default',
            'new', 'catch', 'finally', 'throw', 'try', 'this', 'super', 'instanceof', 'assert'}

def find_class_file(sources: Path, cls: str):
    """定位类文件：优先 <类名>.java；默认包类在 jadx 1.5 输出到 defpackage/ 目录；内部类（$）
    先试独立文件，再回退外类文件 + class 声明搜索。"""
    rel = cls.replace('.', '/') + '.java'
    for base in ('', 'defpackage/'):                    # '' = 常规包路径；defpackage/ = jadx 默认包类
        p = sources / (base + rel)
        if p.exists():
            return p
    if '$' in cls:
        outer, _, inner = cls.rpartition('$')
        for base in ('', 'defpackage/'):
            outer_p = sources / (base + outer.replace('.', '/') + '.java')
            if outer_p.exists():
                content = outer_p.read_text(encoding='utf-8', errors='replace')
                # jadx 合并内类进外类文件时声明形如 `public static final class Inner {`
                if re.search(r'\bclass\s+' + re.escape(inner) + r'\b', content):
                    return outer_p
    return None

def strip_comments(content: str) -> str:
    content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)
    lines = []
    for ln in content.splitlines():
        # 行注释：切掉 // （字符串字面量含 // 的行整行丢弃——声明不会出现在字符串行内，安全）
        idx = ln.find('//')
        lines.append(ln if idx < 0 else ln[:idx])
    return '\n'.join(lines)

def scan_param_count(text: str, paren_idx: int) -> int:
    """从 '(' 之后做括号/尖括号/花括号平衡扫描，返回参数个数；括号未闭合返回 -1。"""
    depth = ang = brace = 0
    commas = 0
    started = False
    i = paren_idx + 1
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == '(':
            depth += 1
        elif ch == ')':
            if depth == 0:
                return commas + 1 if started else 0
            depth -= 1
        elif ch == '<':
            ang += 1
        elif ch == '>':
            ang = max(0, ang - 1)
        elif ch == '{':
            brace += 1
        elif ch == '}':
            brace = max(0, brace - 1)
        elif depth == 0 and ang == 0 and brace == 0:
            if ch == ',':
                commas += 1
                started = True
            elif not ch.isspace():
                started = True
        i += 1
    return -1

def find_method_decls(content: str, name: str):
    """找全部「方法声明」候选，产出 (参数个数, 返回类型 token)。"""
    # 候选 = 名字后紧跟 '(' 且前面是返回类型（非调用/关键字）
    pat = re.compile(r'(?<![\w.$])\b' + re.escape(name) + r'\s*\(')
    for m in pat.finditer(content):
        j = m.start() - 1
        while j >= 0 and content[j] in ' \t\r\n':
            j -= 1
        if j < 0 or not (content[j].isalnum() or content[j] in '_$>]'):
            continue                                    # 前一字符不是返回类型末尾（. 调用/括号/算子）
        tok_j = j
        while tok_j >= 0 and (content[tok_j].isalnum() or content[tok_j] in '_$'):
            tok_j -= 1
        if content[tok_j + 1:j + 1] in KEYWORDS:
            continue                                    # `return foo()` / `new Foo()` 之类
        pcount = scan_param_count(content, m.end() - 1)  # m.end()-1 指向 '('
        if pcount < 0:
            continue
        # 返回类型 token：名字前最后一个空白分段（advisory 用，容忍泛型/数组；[] 不是终止符）
        k = tok_j
        while k >= 0 and content[k] not in '\n{};:,=()':
            k -= 1
        seg = content[k + 1:m.start()].strip()
        ret_tok = seg.split()[-1] if seg.split() else ''
        yield pcount, ret_tok

def norm_ret_type(t: str) -> str:
    t = re.sub(r'\s+', '', t)
    t = t.replace('java.lang.', '')                    # 源码写 java.lang.String/java.lang.Long
    return t.replace('$', '.').replace('<', '.').replace('>', '')

def ret_type_note(want: str, got: str) -> str:
    """返回类型软比对（advisory，不进存活判定；jadx 渲染形式多样，只提示明显漂移）。"""
    want, got = want.strip(), (got or '').strip()
    if not want or not got:
        return ''
    if norm_ret_type(want) == norm_ret_type(got):
        return ''
    want_simple = norm_ret_type(want).rsplit('.', 1)[-1]
    got_simple = norm_ret_type(got).rsplit('.', 1)[-1]
    if want_simple == got_simple:
        return ''                                      # 仅渲染差异（导入/全限定形式）
    return f'返回类型漂移: 源码 {want} ≠ jadx {got}（ThemeStoreHook 按返回类型匹配，需人工确认）'

def dex_class_desc(cls: str) -> str:
    return 'L' + cls.replace('.', '/') + ';'

class DexProbe:
    """APK dex 描述符探测（区分「类真丢失」与「存在但 jadx 反编译失败」）。
    仅在类文件缺失时惰性读取 classes*.dex 并缓存字节。"""
    def __init__(self, apk: Path):
        self.apk = apk
        self._cache = {}

    def has_class(self, cls: str) -> bool:
        desc = dex_class_desc(cls).encode('utf-8')
        with zipfile.ZipFile(self.apk) as z:
            for n in z.namelist():
                if not re.fullmatch(r'classes\d*\.dex', n):
                    continue
                data = self._cache.get(n)
                if data is None:
                    data = z.read(n)
                    self._cache[n] = data
                if desc in data:
                    return True
        return False

def verify_mount(sources: Path, mp: MountPoint, apk: Path, expect_pkg: str = '', dex: 'DexProbe | None' = None):
    """单点验证 → (状态, 备注/说明)。状态: ALIVE / PARAM_CHANGED / CLASS_MISSING / METHOD_MISSING /
    PLATFORM / NOT_IN_APK（挂载点目标包与 APK 包名不符，需用对应 APK 验证）"""
    if expect_pkg and mp.pkg and expect_pkg != mp.pkg:
        return 'NOT_IN_APK', f'挂载点目标包 {mp.pkg} ≠ 本 APK 包名 {expect_pkg}——本 APK 不含该目标，需用对应 APK 单独验证'
    if mp.cls.startswith(PLATFORM_PREFIXES):
        return 'PLATFORM', '平台类（bootclasspath 恒存在，无需比对）'
    clz = find_class_file(sources, mp.cls)
    if clz is None:
        if dex is not None and dex.has_class(mp.cls):
            return 'JADX_FAILED', f'类 {mp.cls} 在 APK dex 中存在但 jadx 反编译失败（见反编译目录的 jadx 错误日志）——需人工确认'
        return 'CLASS_MISSING', f'类 {mp.cls} 不在反编译产物中（建议用 --expect-pkg 确认目标包是否在本 APK）'
    content = strip_comments(clz.read_text(encoding='utf-8', errors='replace'))
    got = [(pc, rt) for pc, rt in find_method_decls(content, mp.method)]
    actual_counts = sorted({pc for pc, _ in got})
    if mp.param_count in actual_counts:
        note = ret_type_note(mp.return_type, next(rt for pc, rt in got if pc == mp.param_count))
        return 'ALIVE', note
    if actual_counts:
        return 'PARAM_CHANGED', (f'方法 {mp.method} 存在但参数个数变化: 期望 {mp.param_count}，'
                                 f'实际 {actual_counts}（findMethod 按名+参数数匹配会挂载失败）'
                                 + (f'；{ret_type_note(mp.return_type, got[0][1])}' if ret_type_note(mp.return_type, got[0][1]) else ''))
    return 'METHOD_MISSING', f'类 {mp.cls} 存在但找不到方法 {mp.method}（任何参数个数都没有）'

# ---------------- jadx 反编译 ----------------

def find_java_home() -> str:
    """JDK 定位（三段式：env 覆盖 → 用户级/常见位置 → 系统位 → throw）。"""
    cands = []
    if os.environ.get('HH_JAVA_HOME'):
        cands.append(os.environ['HH_JAVA_HOME'])
    cands += glob.glob(r'C:\Program Files\Eclipse Adoptium\jdk-21*')
    if os.environ.get('JAVA_HOME'):
        cands.append(os.environ['JAVA_HOME'])
    cands += glob.glob(r'C:\Program Files\Java\jdk-21*')
    for c in cands:
        if Path(c, 'bin', 'java.exe').exists():
            return c
    raise SystemExit('未找到 JDK 21：请设置 HH_JAVA_HOME 指向含 bin/java.exe 的 JDK21 目录'
                     '（或安装 Eclipse Adoptium jdk-21）')

def run_jadx(apk: Path, out_dir: Path, threads: int):
    if not JADX_BAT.exists():
        raise SystemExit(f'未找到 jadx: {JADX_BAT}')
    java_home = find_java_home()
    if out_dir.exists():
        shutil.rmtree(out_dir, ignore_errors=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    cmd = ['cmd', '/c', str(JADX_BAT), '--no-res']
    if threads:
        cmd += ['-j', str(threads)]
    cmd += ['-d', str(out_dir), str(apk)]
    env = dict(os.environ, JAVA_HOME=java_home)
    print(f'⟳ jadx 反编译中（{apk.name}，JAVA_HOME={java_home}）…… 可能数分钟')
    t0 = time.time()
    proc = subprocess.run(cmd, env=env, cwd=str(REPO))
    if proc.returncode != 0:
        raise SystemExit(f'jadx 反编译失败（rc={proc.returncode}）：{out_dir}')
    sources = out_dir / 'sources'
    if not sources.is_dir():
        raise SystemExit(f'jadx 反编译完成但无 sources/ 输出，请人工检查 {out_dir}')
    print(f'✓ jadx 反编译完成（{int(time.time() - t0)}s）→ {out_dir}')

# ---------------- 报告 ----------------

STATUS_ICON = {'ALIVE': '✅', 'PARAM_CHANGED': '⚠️', 'CLASS_MISSING': '❌',
               'METHOD_MISSING': '❌', 'PLATFORM': '🔵', 'NOT_IN_APK': '🎯',
               'JADX_FAILED': '❓'}
STATUS_CN = {'ALIVE': '存活', 'PARAM_CHANGED': '参数变化', 'CLASS_MISSING': '类丢失',
             'METHOD_MISSING': '方法丢失', 'PLATFORM': '平台类', 'NOT_IN_APK': '目标包不在本 APK',
             'JADX_FAILED': '反编译失败'}

def build_report(apk: Path, mounts: list, verdicts: list, sources_dir: Path, reused: bool, expect_pkg: str = '') -> str:
    by_file = {}
    for mp, (st, note) in zip(mounts, verdicts):
        by_file.setdefault(mp.file, []).append((mp, st, note))
    total = len(mounts)
    broken = [(mp, st, note) for mp, (st, note) in zip(mounts, verdicts)
              if st in ('CLASS_MISSING', 'METHOD_MISSING', 'PARAM_CHANGED', 'JADX_FAILED')]
    not_in_apk = sum(1 for _, (st, _) in zip(mounts, verdicts) if st == 'NOT_IN_APK')
    alive = total - len(broken) - not_in_apk
    lines = []
    lines.append('# HonorHook 版本适配检查报告')
    lines.append('')
    lines.append(f'- 目标 APK：`{apk}`（{apk.stat().st_size / 1024 / 1024:.1f} MB）'
                 + (f'，--expect-pkg 声明包名 `{expect_pkg}`' if expect_pkg else ''))
    lines.append(f'- 生成时间：{time.strftime("%Y-%m-%d %H:%M:%S")}')
    lines.append(f'- 挂载点提取：`app/.../xposed/target/*Hook.kt`（自动解析 CLS_* 常量 + addConst/'
                 f'addForceArg/MountTask 调用，非硬编码清单）')
    lines.append(f'- 反编译产物：`{sources_dir}`（jadx `--no-res`，{"复用既有目录" if reused else "本次生成"}）')
    lines.append('')
    lines.append('## 结论')
    lines.append('')
    if not broken:
        if not_in_apk:
            lines.append(f'**✅ 本 APK 范围内全存活 {alive}/{total - not_in_apk}（另有 {not_in_apk} 个挂载点目标包不在本 APK）'
                         f'——无需代码适配**。不在本 APK 的挂载点需用对应目标 APK 单独验证。')
        else:
            lines.append(f'**✅ 全存活 {alive}/{total}——无需代码适配**，本版 APK 可直接使用。')
    else:
        lines.append(f'**❌ 存活 {alive}/{total}，{len(broken)} 处丢失/漂移——需人工确认，'
                     f'对应 hook 挂载会失败（源码 findMethod 返回 null → WARN 放弃）**')
    lines.append('')
    lines.append('## 挂载点统计')
    lines.append('')
    lines.append('| Hook 文件 | 目标包 | 挂载点 | 存活 | 丢失/漂移 | 目标包外 |')
    lines.append('|----------|--------|-------:|-----:|----------:|---------:|')
    for fname in sorted(by_file):
        f_entries = by_file[fname]
        pkg = f_entries[0][0].pkg
        f_broken = sum(1 for _, st, _ in f_entries if st in ('CLASS_MISSING', 'METHOD_MISSING', 'PARAM_CHANGED', 'JADX_FAILED'))
        f_na = sum(1 for _, st, _ in f_entries if st == 'NOT_IN_APK')
        lines.append(f'| `{fname}` | `{pkg}` | {len(f_entries)} | {len(f_entries) - f_broken - f_na} | {f_broken} | {f_na} |')
    lines.append(f'| **合计** | | **{total}** | **{alive}** | **{len(broken)}** | **{not_in_apk}** |')
    lines.append('')
    for fname in sorted(by_file):
        f_entries = by_file[fname]
        lines.append(f'## {fname}')
        lines.append('')
        lines.append('| 状态 | 挂载点 ID | 类 | 方法 | 期望参数 | 实际参数 | 说明 |')
        lines.append('|------|----------|----|----|:-------:|:-------:|------|')
        for mp, st, note in f_entries:
            actual = '—'
            if st == 'ALIVE':
                actual = str(mp.param_count)
            elif st == 'PARAM_CHANGED':
                actual = note.split('实际 ')[1].split('（')[0] if '实际 ' in note else '?'
            lines.append(f'| {STATUS_ICON[st]} {STATUS_CN[st]} | {mp.mount_id or "—"} | '
                         f'`{mp.cls}` | `{mp.method}` | {mp.param_count} | {actual} | {note} |')
        lines.append('')
    if broken:
        lines.append('## 丢失/漂移点清单（需人工确认）')
        lines.append('')
        for mp, st, note in broken:
            lines.append(f'- {STATUS_ICON[st]} **{STATUS_CN[st]}** `{mp.cls}.{mp.method}'
                         f'`（参数数 {mp.param_count}，来源 `{mp.file}`，挂载点 `{mp.mount_id or "—"}`）：{note}')
        lines.append('')
    if not_in_apk:
        lines.append(f'## 目标包不在本 APK 的挂载点（{not_in_apk} 个，非失败）')
        lines.append('')
        lines.append('这些挂载点的目标包与 `--expect-pkg` 声明的包名不一致——本 APK 中不存在它们的类，'
                     '属预期（如用主题商店 APK 验证安装器 hook 点）。用对应目标 APK（如安装器需要 '
                     '`软件包安装程序_*.apk`）执行本脚本即可验证。')
        lines.append('')
    lines.append('## 说明')
    lines.append('')
    lines.append('- 校验口径与 hook 运行时一致：方法按 **名 + 参数个数** 匹配（对应 HonorInstallerHook.findMethod）；'
                 'ThemeStoreHook 另按返回类型匹配，返回类型漂移列在说明里提示。')
    lines.append('- 内部类（如 `OverallRetentionDialogManager`）先查独立 `$` 文件，jadx 合并进外类文件时'
                 '回退在外类文件内搜 `class 内类名` 声明；默认包类（如 `o52`）在 jadx 输出的 `defpackage/` 目录。')
    lines.append('- `java.*`/`javax.*` 平台类（如 `java.net.Socket`）走 bootclasspath，恒存在，跳过比对。')
    lines.append('- 类文件缺失时对 APK 的 classes*.dex 做描述符交叉验证：dex 中存在 → 状态「反编译失败」'
                 '（jadx 未解出，非版本漂移）；dex 中不存在 → 「类丢失」（版本漂移或包不匹配）。')
    lines.append('- 一个 APK 只含一个目标包：跑主题商店 APK 时建议 `--expect-pkg com.hihonor.android.thememanager`，'
                 '跑安装器 APK 用 `--expect-pkg com.android.packageinstaller`，避免把「包不在本 APK」误当类丢失。')
    lines.append('- 同步规则：挂载点清单自动从 Hook 源码提取，新增/删除 hook 点只需照既有书写格式'
                 '（addConst/addForceArg 位置式或 MountTask 具名式 kind→className→methodName→paramCount）'
                 '改源码即可，无需同步本脚本。')
    return '\n'.join(lines)

def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument('apk', help='新版目标 APK 路径')
    ap.add_argument('--report', default=str(REPO / 'system-files' / 'adapt-report.md'))
    ap.add_argument('--expect-pkg', default='',
                    help='APK 的目标包名（如 com.hihonor.android.thememanager）。声明后，目标包不同的'
                         '挂载点标「目标包不在本 APK」而非类丢失')
    ap.add_argument('--reuse', default='', help='复用已有 jadx 反编译目录，跳过反编译')
    ap.add_argument('--clean', action='store_true', help='跑完删除反编译目录')
    ap.add_argument('-j', '--threads', type=int, default=0, help='jadx 线程数（默认自定）')
    args = ap.parse_args()
    apk = Path(args.apk)
    if not apk.is_file():
        raise SystemExit(f'APK 不存在: {apk}')

    sources_dir = Path(args.reuse) if args.reuse else None
    reused = False
    if sources_dir is not None:
        if not (sources_dir / 'sources').is_dir():
            raise SystemExit(f'--reuse 目录无效（缺 sources/）: {sources_dir}')
        reused = True
    else:
        sources_dir = BUILDTMP / f'adapt_jadx_{apk.stem}'
        run_jadx(apk, sources_dir, args.threads)
    sources = sources_dir / 'sources'

    mounts = extract_mounts()
    print(f'\n提取挂载点 {len(mounts)} 项：')
    from collections import Counter
    for fname, cnt in Counter(mp.file for mp in mounts).items():
        print(f'  {fname}: {cnt} 项')

    dex = DexProbe(apk)                                   # 类文件缺失时的 dex 交叉验证（惰性读）
    verdicts = [verify_mount(sources, mp, apk, args.expect_pkg, dex) for mp in mounts]
    report = build_report(apk, mounts, verdicts, sources_dir, reused, args.expect_pkg)
    report_path = Path(args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report, encoding='utf-8')
    print(f'\n报告已写入: {report_path}')
    print('=' * 72)
    print(report)
    if args.clean and not reused:
        shutil.rmtree(sources_dir, ignore_errors=True)
        print(f'（已按 --clean 删除 {sources_dir}）')

if __name__ == '__main__':
    main()