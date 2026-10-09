# -*- coding: utf-8 -*-
"""
rain-decrypt.py — Rain APK 字符串解密批量还原（short[] XOR 体系）

算法（docs/rain-hook-architecture.md §4）：明文 = chr((cipher[start+i] ^ key) & 0xFFFF)。
cipher 为 smali `.array-data 2` short 数组，解密器 `([SIII)Ljava/lang/String;` 复制进 158 个类。

两家族：
  * Family A（16 处）：数组 sget-object / 静态 getter + start/len/key 三个明文 const。
  * Family B（27 处, np/* 混淆配置层）：start/len/key 由 const-string + String.hashCode()
    + and/or/xor 掩码算术合成 —— 按运行时语义忠实模拟（确定性求值，无猜测）。
  * 诱饵：解密器正文含 `Ljava/lang/Math;->random()D` 为非确定性诱饵（全库仅
    org/luckypray/dexkit/۟۠۠ۥۢ->ۣ۟ۡۧ۠ 一个且从未被调用），过滤不输出。

用法：python rain-decrypt.py [smali_dir] [out_md]
"""

import base64
import os
import re
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SMALI_DIR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(BASE, "system-files", "rain-apktool", "smali")
OUT_MD = sys.argv[2] if len(sys.argv) > 2 else os.path.join(BASE, "system-files", "rain-strings-decrypted.md")

DECRYPT_DESC = "([SIII)Ljava/lang/String;"

# ---------------------------------------------------------------- 数值与字面量

def lit32(tok):
    """smali 整型字面量 -> int32（内部一律用无符号 32 位表示，位运算与加法等价）。"""
    t = tok.strip()
    neg = t.startswith("-")
    if t[0] in "+-":
        t = t[1:]
    v = int(t, 16) if t.lower().startswith("0x") else int(t)
    if neg:
        v = -v
    return v & 0xFFFFFFFF

def u16(v):
    return v & 0xFFFF

def dec_str(s):
    body = s[1:-1]
    out, i = [], 0
    while i < len(body):
        c = body[i]
        if c == "\\" and i + 1 < len(body):
            n = body[i + 1]
            if n == "u" and i + 5 < len(body):
                out.append(chr(int(body[i + 2:i + 6], 16)))
                i += 6
                continue
            m = {"t": "\t", "n": "\n", "r": "\r", "b": "\b", "f": "\f",
                 '"': '"', "'": "'", "\\": "\\", "0": "\0"}.get(n)
            if m is not None:
                out.append(m)
                i += 2
                continue
        out.append(c)
        i += 1
    return "".join(out)

def java_hash(s):
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h

# ---------------------------------------------------------------- 正则

RE_CLASS = re.compile(r"\.class\s+(?:\w+\s+)*L([^;]+);")
RE_METHOD = re.compile(r"\.method\s+(?:\w+\s+)*([^\s(]+)\(([^)]*)\)(\S*)\s*$")
RE_CONST = re.compile(r"^(const|const/4|const/16|const/high16)\s+(v\d+),\s*([^\s#]+)\s*(?:#.*)?$")
RE_CONST_STR = re.compile(r'^(const-string|const-string/jumbo)\s+(v\d+),\s*(".*")\s*$')
RE_MOVE = re.compile(r"^move(?:-object)?\s+(v\d+),\s*(v\d+|p\d+)\s*$")
RE_MVR = re.compile(r"^move-result(?:-object)?\s+(v\d+|p\d+)\s*$")
RE_SGET_OBJ = re.compile(r"^sget-object\s+(v\d+),\s*(L[^;]+);->([^:\s]+):\[S\s*$")
RE_SGET = re.compile(r"^sget\s+(v\d+),\s*(L[^;]+);->([^:\s]+):I\s*$")
RE_FILL = re.compile(r"^fill-array-data\s+(v\d+),\s*:(\S+)\s*$")
RE_SPUT = re.compile(r"^sput-object\s+(v\d+),\s*(L[^;]+);->([^:\s]+):\[S\s*$")
RE_ARRAY_DATA = re.compile(r"^\.array-data 2\s*$")
RE_ARRAY_VAL = re.compile(r"^\s*(-?0x[\da-fA-F]+|-?\d+)s\s*$")
RE_LABEL = re.compile(r"^:(\S+)\s*$")
RE_INVOKE = re.compile(
    r"^invoke-(static|virtual|direct|interface)\s*\{([^}]*)\},\s*(L[^;]+);->([^\s(]+)\(([^)]*)\)([^\s;]*);?\s*$")
RE_CALL = re.compile(
    r"^invoke-static\s*\{([^}]*)\},\s*(L[^;]+);->([^\s(]+)\(\[SIII\)Ljava/lang/String;\s*$")
RE_STATIC_LIT = re.compile(r"^\.field\s+(?:\w+\s+)*([^:\s]+):I\s*=\s*(.+?)\s*$")

LIT_OPS = {
    "and-int/lit8": lambda a, b: a & b, "and-int/lit16": lambda a, b: a & b,
    "or-int/lit8": lambda a, b: a | b, "or-int/lit16": lambda a, b: a | b,
    "xor-int/lit8": lambda a, b: a ^ b, "xor-int/lit16": lambda a, b: a ^ b,
    "add-int/lit8": lambda a, b: (a + b) & 0xFFFFFFFF, "add-int/lit16": lambda a, b: (a + b) & 0xFFFFFFFF,
    "rsub-int/lit8": lambda a, b: (b - a) & 0xFFFFFFFF, "rsub-int/lit16": lambda a, b: (b - a) & 0xFFFFFFFF,
    "mul-int/lit8": lambda a, b: (a * b) & 0xFFFFFFFF,
    "shl-int/lit8": lambda a, b: (a << (b & 31)) & 0xFFFFFFFF,
    "shr-int/lit8": lambda a, b: (a >> (b & 31)) & 0xFFFFFFFF,
    "ushr-int/lit8": lambda a, b: (a >> (b & 31)) & 0xFFFFFFFF,
}
RE_LIT = re.compile(r"^([a-z-]+?/lit(?:8|16))\s+(v\d+),\s*(v\d+),\s*(.+?)\s*$")
ARITH_2 = {
    "and-int/2addr": lambda a, b: a & b, "or-int/2addr": lambda a, b: a | b,
    "xor-int/2addr": lambda a, b: a ^ b, "add-int/2addr": lambda a, b: (a + b) & 0xFFFFFFFF,
    "sub-int/2addr": lambda a, b: (a - b) & 0xFFFFFFFF, "mul-int/2addr": lambda a, b: (a * b) & 0xFFFFFFFF,
    "div-int/2addr": lambda a, b: (a // b) & 0xFFFFFFFF if b else 0,
    "rem-int/2addr": lambda a, b: a % b if b else 0,
    "shl-int/2addr": lambda a, b: (a << (b & 31)) & 0xFFFFFFFF,
    "shr-int/2addr": lambda a, b: (a >> (b & 31)) & 0xFFFFFFFF,
    "ushr-int/2addr": lambda a, b: (a >> (b & 31)) & 0xFFFFFFFF,
}
RE_ARITH2 = re.compile(
    r"^(and-int/2addr|or-int/2addr|xor-int/2addr|add-int/2addr|sub-int/2addr|mul-int/2addr|div-int/2addr|rem-int/2addr|shl-int/2addr|shr-int/2addr|ushr-int/2addr)\s+(v\d+),\s*(v\d+)\s*$")
ARITH_3 = {
    "and-int": lambda a, b: a & b, "or-int": lambda a, b: a | b,
    "xor-int": lambda a, b: a ^ b, "add-int": lambda a, b: (a + b) & 0xFFFFFFFF,
    "sub-int": lambda a, b: (a - b) & 0xFFFFFFFF, "mul-int": lambda a, b: (a * b) & 0xFFFFFFFF,
}
RE_ARITH3 = re.compile(r"^(and-int|or-int|xor-int|add-int|sub-int|mul-int)\s+(v\d+),\s*(v\d+),\s*(v\d+)\s*$")
RE_NEG = re.compile(r"^neg-int\s+(v\d+),\s*(v\d+)\s*$")
RE_NOT = re.compile(r"^not-int\s+(v\d+),\s*(v\d+)\s*$")
RE_NEWARR = re.compile(r"^new-array\s+(v\d+),\s*(v\d+),\s*\[S\s*$")
RE_AGET = re.compile(r"^aget-short\s+(v\d+),\s*(v\d+),\s*(v\d+)\s*$")
RE_ITOC = re.compile(r"^int-to-char\s+(v\d+),\s*(v\d+)\s*$")

# 已知函数语义（类, 方法, 描述符）-> ('hash'|'parseInt'|'strlen')
KNOWN_FUNCS = {
    ("np/̅", "̝", "(Ljava/lang/Object;)I"): "hash",      # np/̅->̝(O)I  = String.hashCode
    ("np/̎", "̬", "(Ljava/lang/Object;)I"): "hash",      # np/̎->̬(O)I
    ("np/̔", "̐", "(Ljava/lang/Object;)I"): "hash",      # np/̔->̐(O)I
    ("np/̔", "̅", "(Ljava/lang/Object;)I"): "parseInt",  # np/̔->̅(O)I = Integer.parseInt
    ("java/lang/String", "length", "()I"): "strlen",
    ("java/lang/String", "hashCode", "()I"): "hash",
}

# 首操作数为 reg 但只读（不回写 reg）的指令族 —— 回溯时跳过
READONLY_FIRST = ("sput", "iput", "aput", "check-cast", "monitor-", "throw", "fill-array-data")

# ---------------------------------------------------------------- 类文件解析

class SmaliFile:
    def __init__(self, path):
        self.path = path
        with open(path, encoding="utf-8", errors="replace") as f:
            self.lines = f.read().splitlines()
        m = RE_CLASS.match(self.lines[0])
        self.name = m.group(1) if m else "?"
        self.methods = []       # [name, desc, start(1-based), end(1-based)]
        self.arrays = {}        # (mname, mdesc, label) -> [u16,...]
        self.field_fills = {}   # field -> [u16,...]
        self.static_ints = {}   # field -> int32
        self.random_methods = set()  # 含 Math.random 的方法 (name, desc) —— 诱饵
        self._parse()

    def _parse(self):
        cur = None
        for i, ln in enumerate(self.lines):
            st = ln.strip()
            if st.startswith(".method "):
                mm = RE_METHOD.match(st)
                cur = [mm.group(1), "(%s)%s" % (mm.group(2), mm.group(3)), i + 1, i + 1] if mm else None
                if cur:
                    self.methods.append(cur)
                continue
            if st.startswith(".end method"):
                if cur is not None:
                    cur[3] = i + 1
                    cur = None
                continue
            if "Math;->random()D" in st and cur is not None:
                self.random_methods.add((cur[0], cur[1]))
            mm = RE_STATIC_LIT.match(st)
            if mm:
                self.static_ints[mm.group(1)] = lit32(mm.group(2))
        for name, desc, s, e in self.methods:
            seg = self.lines[s - 1:e]
            j, n = 0, len(seg)
            while j < n:
                if RE_ARRAY_DATA.match(seg[j].strip()):
                    lbl = None
                    for k in range(j - 1, -1, -1):
                        lm = RE_LABEL.match(seg[k].strip())
                        if lm:
                            lbl = lm.group(1)
                            break
                    vals = []
                    j += 1
                    while j < n and ".end array-data" not in seg[j]:
                        vm = RE_ARRAY_VAL.match(seg[j])
                        if vm:
                            vals.append(u16(lit32(vm.group(1))))
                        j += 1
                    if lbl:
                        self.arrays[(name, desc, lbl)] = vals
                j += 1
            for j in range(len(seg)):
                sm = RE_SPUT.match(seg[j].strip())
                own = sm.group(2)[1:] if sm and sm.group(2).startswith("L") else (sm.group(2) if sm else "")
                if not sm or own != self.name:
                    continue
                for k in range(j - 1, -1, -1):
                    fm = RE_FILL.match(seg[k].strip())
                    if fm and fm.group(1) == sm.group(1):
                        arr = self.arrays.get((name, desc, fm.group(2)))
                        if arr is not None:
                            self.field_fills[sm.group(3)] = arr
                        break

# ---------------------------------------------------------------- 语料索引

class Corpus:
    def __init__(self, smali_dir):
        self.by_name = {}
        self.files = []
        for root, _, fs in os.walk(smali_dir):
            for fn in fs:
                if fn.endswith(".smali"):
                    p = os.path.join(root, fn)
                    sf = SmaliFile(p)
                    self.files.append(sf)
                    self.by_name[sf.name] = sf
        self.decl_count = 0
        self.decl_files = []
        for sf in self.files:
            n = sum(1 for _, d, _, _ in sf.methods if d == DECRYPT_DESC)
            if n:
                self.decl_count += n
                self.decl_files.append(sf.name)
        self.invoke_count = sum(1 for sf in self.files for ln in sf.lines if RE_CALL.match(ln.strip()))

    @staticmethod
    def _nc(cls):
        return cls[1:] if cls.startswith("L") else cls

    def array_of(self, cls, field):
        sf = self.by_name.get(self._nc(cls))
        return sf.field_fills.get(field) if sf else None

    def getter_array(self, cls, mname):
        """静态 getter `()[S` -> (数组, 失败原因)。"""
        sf = self.by_name.get(self._nc(cls))
        if sf is None:
            return None, "类 %s 不存在" % cls
        for n, d, s, e in sf.methods:
            if n == mname and d == "()[S":
                for j in range(s - 1, e):
                    st = sf.lines[j].strip()
                    m = RE_SGET_OBJ.match(st)
                    if m and (m.group(2)[1:] if m.group(2).startswith("L") else m.group(2)) == cls:
                        arr = sf.field_fills.get(m.group(3))
                        return (arr, None) if arr is not None else (None, "字段 %s 未定位" % m.group(3))
                return None, "getter %s->%s 正文无 sget-object" % (cls, mname)
        return None, "getter %s->%s 未定义" % (cls, mname)

    def static_int(self, cls, field):
        sf = self.by_name.get(self._nc(cls))
        return sf.static_ints.get(field) if sf else None

# ---------------------------------------------------------------- 回溯求值

class Resolver:
    def __init__(self, corpus):
        self.corpus = corpus

    def solve(self, reg, pos, sf, mname, mdesc, mstart, depth=0):
        """从 pos-1 向上回溯，求 reg 的常量值。返回 ('i',int)|('s',str)|('a',list)|('u',cause)。"""
        if depth > 120:
            return ("u", "递归过深")
        if reg.startswith("p"):
            return ("u", "参数寄存器")
        for i in range(pos - 1, mstart - 1, -1):
            st = sf.lines[i].strip()
            if not st or st.startswith((".", "goto", "nop", "if-", "return", "throw",
                                        "move-exception", "packed-switch", "sparse-switch")):
                continue
            m = RE_CONST.match(st)
            if m and m.group(2) == reg:
                return ("i", lit32(m.group(3)))
            m = RE_CONST_STR.match(st)
            if m and m.group(2) == reg:
                return ("s", dec_str(m.group(3)))
            m = RE_MOVE.match(st)
            if m and m.group(1) == reg:
                return self.solve(m.group(2), i, sf, mname, mdesc, mstart, depth + 1)
            m = RE_MVR.match(st)
            if m and m.group(1) == reg:
                return self._invoke_result(i, sf, mname, mdesc, mstart, depth)
            m = RE_SGET_OBJ.match(st)
            if m and m.group(1) == reg:
                arr = self.corpus.array_of(m.group(2), m.group(3))
                return ("a", arr) if arr is not None else ("u", "sget-object %s->%s:[S 数组未定位" % (m.group(2), m.group(3)))
            m = RE_SGET.match(st)
            if m and m.group(1) == reg:
                v = self.corpus.static_int(m.group(2), m.group(3))
                return ("i", v) if v is not None else ("u", "sget %s->%s:I 无数值" % (m.group(2), m.group(3)))
            m = RE_FILL.match(st)
            if m and m.group(1) == reg:
                arr = sf.arrays.get((mname, mdesc, m.group(2)))
                return ("a", arr) if arr is not None else ("u", "fill-array-data 无对应 array-data")
            m = RE_NEWARR.match(st)
            if m and m.group(1) == reg:
                sz = self.solve(m.group(2), i, sf, mname, mdesc, mstart, depth + 1)
                return ("a", [0] * sz[1]) if sz[0] == "i" else ("u", "new-array 大小不可求值")
            m = RE_AGET.match(st)
            if m and m.group(1) == reg:
                av = self.solve(m.group(2), i, sf, mname, mdesc, mstart, depth + 1)
                iv = self.solve(m.group(3), i, sf, mname, mdesc, mstart, depth + 1)
                if av[0] == "a" and iv[0] == "i" and 0 <= iv[1] < len(av[1]):
                    return ("i", av[1][iv[1]])
                return ("u", "aget-short 无法求值")
            m = RE_ITOC.match(st)
            if m and m.group(1) == reg:
                v = self.solve(m.group(2), i, sf, mname, mdesc, mstart, depth + 1)
                return ("i", u16(v[1])) if v[0] == "i" else ("u", "int-to-char 源不可求值")
            m = RE_LIT.match(st)
            if m and m.group(2) == reg:
                fn = LIT_OPS.get(m.group(1))
                if not fn:
                    return ("u", "%s 未支持" % m.group(1))
                sv = self.solve(m.group(3), i, sf, mname, mdesc, mstart, depth + 1)
                if sv[0] != "i":
                    return ("u", "%s 源不可求值" % m.group(1))
                return ("i", fn(sv[1], lit32(m.group(4))))
            m = RE_ARITH2.match(st)
            if m and m.group(2) == reg:
                fn = ARITH_2.get(m.group(1))
                if not fn:
                    return ("u", "%s 未支持" % m.group(1))
                av = self.solve(reg, i, sf, mname, mdesc, mstart, depth + 1)
                bv = self.solve(m.group(3), i, sf, mname, mdesc, mstart, depth + 1)
                if av[0] != "i" or bv[0] != "i":
                    return ("u", "%s 源不可求值(%s/%s)" % (m.group(1), av[0], bv[0]))
                return ("i", fn(av[1], bv[1]))
            m = RE_ARITH3.match(st)
            if m and m.group(2) == reg:
                fn = ARITH_3.get(m.group(1))
                if not fn:
                    return ("u", "%s 未支持" % m.group(1))
                xv = self.solve(m.group(3), i, sf, mname, mdesc, mstart, depth + 1)
                yv = self.solve(m.group(4), i, sf, mname, mdesc, mstart, depth + 1)
                if xv[0] != "i" or yv[0] != "i":
                    return ("u", "%s 源不可求值" % m.group(1))
                return ("i", fn(xv[1], yv[1]))
            m = RE_NEG.match(st)
            if m and m.group(1) == reg:
                v = self.solve(m.group(2), i, sf, mname, mdesc, mstart, depth + 1)
                return ("i", (-v[1]) & 0xFFFFFFFF) if v[0] == "i" else ("u", "neg-int 源不可求值")
            m = RE_NOT.match(st)
            if m and m.group(1) == reg:
                v = self.solve(m.group(2), i, sf, mname, mdesc, mstart, depth + 1)
                return ("i", (~v[1]) & 0xFFFFFFFF) if v[0] == "i" else ("u", "not-int 源不可求值")
            # 其它指令：首操作数是 reg → 只读族跳过，写族如实标记不可求值
            toks = st.split()
            if len(toks) >= 2 and toks[1].startswith(reg + ","):
                if toks[0].startswith(READONLY_FIRST):
                    continue
                return ("u", "指令 %s 未支持" % toks[0])
        return ("u", "无赋值")

    def _invoke_result(self, mvline, sf, mname, mdesc, mstart, depth):
        """move-result 行之前的 invoke 语义。mvline = move-result 行号（1-based）。"""
        for i in range(mvline - 1, mstart - 1, -1):
            st = sf.lines[i].strip()
            if not st or st.startswith((".", "goto", "nop")):
                continue
            m = RE_INVOKE.match(st)
            if not m:
                return ("u", "move-result 前无 invoke")
            kind, regs_s, cls, name, params, ret = m.groups()
            cls = cls[1:] if cls.startswith("L") else cls
            desc = "(%s)%s" % (params, ret)
            regs = [r.strip() for r in regs_s.split(",") if r.strip()]
            if kind == "static" and len(regs) == 0 and desc == "()[S":
                arr, err = self.corpus.getter_array(cls, name)
                return ("a", arr) if arr is not None else ("u", "getter 解析失败: %s" % err)
            sem = KNOWN_FUNCS.get((cls, name, desc))
            if sem is None:
                return ("u", "未知 invoke %s->%s%s" % (cls, name, desc))
            if len(regs) != 1:
                return ("u", "%s 实参个数异常" % sem)
            sv = self.solve(regs[0], i, sf, mname, mdesc, mstart, depth + 1)
            if sem == "hash":
                if sv[0] != "s":
                    return ("u", "hash 实参不是 const-string")
                return ("i", java_hash(sv[1]))
            if sem == "strlen":
                if sv[0] != "s":
                    return ("u", "strlen 实参不是 const-string")
                return ("i", len(sv[1]))
            if sem == "parseInt":
                if sv[0] != "s" or not sv[1].isdigit():
                    return ("u", "parseInt 实参非数字串")
                return ("i", lit32(sv[1]))
        return ("u", "move-result 前无指令")

# ---------------------------------------------------------------- 主流程

def expand_regs(regs_s):
    """{v1, v2, v3, v4} 或 range 形态 {v1 .. v4} -> 寄存器名列表。"""
    regs = []
    for r in [x.strip() for x in regs_s.split(",") if x.strip()]:
        if ".." in r:
            a, b = r.split("..")
            regs.append(a.strip())
        else:
            regs.append(r)
    return regs[:4]


def decrypt_site(corpus, sf, line_no, m):
    regs = expand_regs(m.group(1))
    recv = "%s->%s" % (m.group(2), m.group(3))
    mstart = None
    for n, d, s, e in sf.methods:
        if s <= line_no <= e:
            mstart, mname, mdesc = s, n, d
            break
    if mstart is None:
        return {"recv": recv, "line": line_no, "error": "调用点不在任何方法内"}
    rslv = Resolver(corpus)
    vals, causes = [], {}
    for r in regs:
        v = rslv.solve(r, line_no, sf, mname, mdesc, mstart)
        vals.append(v)
        if v[0] == "u":
            causes[r] = v[1]
    if causes:
        return {"recv": recv, "line": line_no, "regs": regs, "vals": vals,
                "error": "未解出: " + "; ".join("%s=%s" % (k, v) for k, v in causes.items())}
    arr_val, start, llen, key = vals[0], vals[1][1], vals[2][1], vals[3][1]
    if arr_val[0] != "a" or arr_val[1] is None:
        return {"recv": recv, "line": line_no, "regs": regs, "vals": vals, "error": "数组未解出"}
    arr = arr_val[1]
    dcls, dmeth = recv.split("->", 1)
    if dcls.startswith("L"):
        dcls = dcls[1:]
    dsf = corpus.by_name.get(dcls)
    if dsf is not None and (dmeth, DECRYPT_DESC) in dsf.random_methods:
        return {"recv": recv, "line": line_no, "decoy": True, "error": "诱饵解密器(Math.random)"}
    cs, oob = [], False
    for k in range(llen):
        idx = start + k
        if idx >= len(arr):
            oob = True
            cs.append("�")
        else:
            cs.append(chr(u16(arr[idx]) ^ (key & 0xFFFFFFFF)))
    return {"recv": recv, "line": line_no, "regs": regs, "start": start, "len": llen,
            "key": key, "arr_len": len(arr), "dec": "".join(cs), "oob": oob}


def classify(s):
    if not s:
        return "空串"
    if "�" in s:
        return "含越界(?)"
    if re.fullmatch(r"[A-Za-z0-9+/=]{8,}", s) and len(s) % 4 == 0 and re.search(r"[A-Z]", s) and re.search(r"[a-z]", s):
        try:
            dec = base64.b64decode(s).decode("utf-8")
        except Exception:
            dec = None
        if dec and not any(ord(c) < 32 or ord(c) == 127 for c in dec):
            return "Base64 → `%s`" % dec
    if "://" in s or s.startswith(("http", "www")):
        return "URL 样"
    if re.fullmatch(r"[a-z0-9_]+(?:[/.][a-z0-9_]+)+", s):
        return "包名/路径样"
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.-]*", s):
        return "键/文件名字样"
    if re.search(r"[一-鿿]", s):
        return "中文文案样"
    if re.fullmatch(r"[a-z][A-Za-z0-9]*", s):
        return "方法/字段名样"
    if re.fullmatch(r"[0-9 .:%+/-]+", s):
        return "数值/格式样"
    return "一般字符串"
def esc(s):
    return s.encode("unicode_escape").decode()


def build_report(results, corpus, out_path):
    L = []
    ap = L.append
    ap("# Rain APK 加密字符串全量解密清单\n")
    ap("> 生成时间：%s　工具：`tools/rain-decrypt.py`　材料：`system-files/rain-apktool/smali/`\n" % time.strftime("%Y-%m-%d %H:%M"))
    ap("> 算法：`chr((cipher[start+i] ^ key) & 0xFFFF)`，见 `docs/rain-hook-architecture.md` §4。"
       "Family B（np/*）的 start/len/key 为 `const-string.hashCode()` + 掩码算术合成，本工具按运行时语义忠实模拟（确定性求值）。\n")
    ok = [r for r in results if r.get("dec") is not None]
    bad = [r for r in results if r.get("dec") is None]
    decoys = [r for r in bad if r.get("decoy")]
    unres = [r for r in bad if not r.get("decoy")]
    def _fam(r):
        rv = str(r.get("recv", ""))
        return rv[1:] if rv.startswith("L") else rv
    fam_a = [r for r in results if not _fam(r).startswith("np/") and r.get("error") is None and not r.get("decoy")]
    fam_b = [r for r in results if _fam(r).startswith("np/") and r.get("error") is None and not r.get("decoy")]
    ap("## 0. 统计\n")
    ap("| 项 | 值 |\n|---|---|\n")
    ap("| smali 类文件 | %d |\n" % len(corpus.files))
    ap("| 解密器副本（`%s` 声明） | %d（分布在 %d 个类） |\n" % (DECRYPT_DESC, corpus.decl_count, len(corpus.decl_files)))
    ap("| 解密调用点（invoke-static） | %d |\n" % corpus.invoke_count)
    ap("| —— Family A（明文 const 参数） | %d |\n" % len(fam_a))
    ap("| —— Family B（hashCode+掩码，np/* 层） | %d |\n" % len(fam_b))
    ap("| 解密成功 | **%d** |\n" % len(ok))
    ap("| 诱饵（Math.random 解密器） | %d |\n" % len(decoys))
    ap("| 未解出（原因见 §4） | %d |\n" % len(unres))
    ap("\n验证锚点：`rain_module` / `enable_debug_logging` / `不要用低质小模块喵`（与 `docs/rain-hook-architecture.md` §4.1 表格一致，下表中标 ⭐）。\n")
    groups = {}
    for r in results:
        groups.setdefault(r["file_path"], []).append(r)
    titles = {
        "love/nairain/hook/ModuleApplication.smali": "模块 Application 入口 —— 模块路径/偏好/URI 等（Family A）",
        "love/nairain/hook/xposed/HookEntry.smali": "入口分发类 —— 模块偏好文件名 / 反篡改（Family A）",
        "love/nairain/hook/MainActivity.smali": "模块 UI 入口（Family A）",
        "np/̅.smali": "np/* 混淆工具层：文件系统/路径操作（Family B）",
        "np/̍.smali": "np/* 混淆工具层（Family B）",
        "np/̐.smali": "np/* 混淆工具层（Family B）",
        "np/̒.smali": "np/* 混淆工具层：MD5/摘要（Family B）",
    }
    ap("\n## 1. 解密串对照表（按文件分组）\n")
    for k in sorted(groups):
        ap("\n### `%s`\n" % k)
        if k in titles:
            ap("> %s\n" % titles[k])
        ap("| 行号 | 调用点 | start/len/key | 解密串（转义） | 原文 | 判定 |\n|---|---|---|---|---|---|\n")
        for r in sorted(groups[k], key=lambda x: x["line"]):
            star = " ⭐" if r.get("dec") in ("rain_module", "enable_debug_logging", "不要用低质小模块喵") else ""
            if r.get("dec") is not None:
                ap("| %d | `%s` | %d/%d/%d | `%s` | %s | %s%s%s |\n" % (
                    r["line"], r["recv"], r["start"], r["len"], r["key"], esc(r["dec"]),
                    r["dec"].replace("|", "\\|"), classify(r["dec"]), star,
                    " ⚠️len越界" if r.get("oob") else ""))
            else:
                ap("| %d | `%s` | — | — | — | ❌ %s |\n" % (r["line"], r["recv"], r.get("error") or "?"))
    ap("\n## 2. 与 hook 目标相关的线索（重点）\n")
    TARGET_PKGS = ["com.dragon.read", "com.baidu.tieba", "com.phoenix.read", "com.kylin.read",
                   "com.MobileTicket", "app.jjyy.chuckle", "com.coolapk.market", "com.baidu.netdisk",
                   "com.kmxs.reader", "com.cainiao.wireless", "com.attempt.afusekt", "com.huawei.health",
                   "com.yunyi.smartcamera", "com.chan.cwallpaper", "com.autonavi.minimap",
                   "com.clover.daysmatter", "com.duolingo", "com.handsgo.jiakao.android",
                   "com.tencent.androidqqmail", "com.mipay.wallet"]
    hook_related = []
    for r in ok:
        s = r["dec"]
        if any(p in s for p in TARGET_PKGS):
            hook_related.append((r, "目标 app 包名"))
        elif re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", s) and s[0].islower():
            hook_related.append((r, "方法/字段名样"))
    if hook_related:
        ap("| 位置 | 解密串 | 判定 |\n|---|---|---|\n")
        for r, why in hook_related:
            ap("| `%s:%d` | `%s` | %s |\n" % (r["file_path"], r["line"], esc(r["dec"]), why))
    else:
        ap("全部解密串中未出现 20 个目标 app 的包名片段。\n")
    ap("""
**结构性结论（重要）**：全部 %d 处解密调用点只分布在 7 个类 —— 模块自身框架层
（`love/nairain/hook/*` 3 类 + `np/*` 4 类）。20 个目标 app 的适配器类（`x00`/`tc`/`rz1`/
`me0` 等，见 docs §1.2）**没有任何字符串解密调用** —— hook 目标类名/方法名/字段名全部是
**明文 const-string**（如 docs §2.3 的 `isAnyVip`、`VipInfoModel`、`com.dragon.read`，v00.java
直接可读，DexKit 查询特征无需先解密）。字符串加密只用于保护模块自身的：反篡改文案、
模块偏好文件名、文件系统路径、URI、MD5 算法名等运行时自引用串。
""" % corpus.invoke_count)
    if unres:
        ap("\n## 3. 未解出调用点明细（如实）\n")
        for r in sorted(unres, key=lambda x: (x["file_path"], x["line"])):
            ap("- `%s:%d` `%s`：%s\n" % (r["file_path"], r["line"], r["recv"], r.get("error")))
    ap("\n## 3. 复现\n")
    ap("```\npython tools/rain-decrypt.py\n```\n")
    tmp = out_path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write("\n".join(L))
    os.replace(tmp, out_path)
    print("报告已写入:", out_path)


def main():
    t0 = time.time()
    corpus = Corpus(SMALI_DIR)
    print("类文件 %d，解密器声明 %d，调用点 %d（解析耗时 %.1fs）" % (
        len(corpus.files), corpus.decl_count, corpus.invoke_count, time.time() - t0))
    results = []
    for sf in corpus.files:
        for i, ln in enumerate(sf.lines):
            m = RE_CALL.match(ln.strip())
            if not m:
                continue
            r = decrypt_site(corpus, sf, i + 1, m)
            r["file_path"] = os.path.relpath(sf.path, BASE).replace("\\", "/")
            results.append(r)
    ok = [r for r in results if r.get("dec") is not None]
    bad = [r for r in results if r.get("dec") is None]
    print("解密成功 %d / %d；未解出 %d（含诱饵 %d）" % (
        len(ok), len(results), len(bad), sum(1 for r in bad if r.get("decoy"))))
    for r in sorted(bad, key=lambda x: (x["file_path"], x["line"])):
        print("  [x] %s:%d %s -- %s" % (r["file_path"], r["line"], r["recv"], r.get("error")))
    for r in sorted(ok, key=lambda x: (x["file_path"], x["line"])):
        print("  [ok] %s:%d %s (%d,%d,%d) = %r" % (
            r["file_path"], r["line"], r["recv"], r["start"], r["len"], r["key"], r["dec"]))
    decs = [r["dec"] for r in ok]
    for anchor in ("rain_module", "enable_debug_logging", "不要用低质小模块喵"):
        print("  %s 锚点: %s" % ("✓" if anchor in decs else "✗ 缺失", anchor))
    build_report(results, corpus, OUT_MD)
    print("完成，总耗时 %.1fs" % (time.time() - t0))


if __name__ == "__main__":
    main()