#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""通用版本适配自动化：锚点搜索 + outline 核签名 → 坐标行草稿。

为什么存在（2026-11-11 拍板，先 xhs 后推广）：混淆坐标每次版本全漂移，
定位可机械化为「串锚唯一宿主 → outline 核签名」，只有结论落表要人审——
锚命中 ≠ 点正确（x2b 反例：同锚异方法，选错会整图变 null）。

注册表 = 各目标锚点登记文档的机器可读投影：
  com.xingin.xhs            → docs/reverse/xhs-anchors.md
  com.hihonor.appmarket     → AppMarketHook.kt HookPoint 表（dexStrings 子集）
  com.hihonor.packageinstaller → InstallerMap_14_0_38_301.kt（最新表）
  com.hihonor.systemmanager    → SysmgrHook.kt HookPoint 表
  com.hihonor.android.thememanager → ThemeStoreMap_20_1_63_305.kt（包名见 ThemeStoreParts）
别名：xhs / appmarket / installer / sysmgr / theme。

spec 两种形态：
  expect 正则（xhs 风格，匹配 outline sig 全文）
  method + paramCount + returnType（HookPoint 表风格：方法名精确 + 顶层参数个数 + 返回类型）
可选：reject（outline 全文否决副本）/ static / strip_inner。

用法：
  python3 tools/anchor_adapt.py <pkg或别名> [mt://路径或apk路径]
输出：坐标草稿 + 冲突清单；exit 1 = 有未消解冲突。只出草稿不落表。
"""
import re
import subprocess
import sys

import os

MTMCPP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mtmcp.py")

# ---------------------------------------------------------------- 注册表

REGISTRIES = {
    "com.xingin.xhs": {
        "alias": "xhs",
        "default_apk": "mt://installed-app/com.xingin.xhs",
        "emit": "xhs",
        "points": {
            "saveClick": {
                "anchor": "onDownloadClick, noteId=",
                "expect": r"^invoke\(Ljava/lang/Object;\)Ljava/lang/Object;$",
            },
            "x2b": {
                "anchor": "disableAiWaterMark is ",
                # 真身 b(Context,Z)Bitmap；同类 a(Bitmap×4,Z,Z) 是白底合成（AI 水标是参数），勿挂
                "expect": r"^b\(Landroid/content/Context;Z\)Landroid/graphics/Bitmap;$",
            },
            "noteSaveEntry": {
                # 锚串落在内部类 $a，真身是外层 #b —— outline 时剥掉 $a 后缀再查
                "anchor": "save video failed:",
                "expect": r"^b\(ZLandroid/app/Activity;\S+?;Lcom/xingin/entities/notedetail/NoteFeed;IZLandroid/view/ViewGroup;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function0;\)V$",
                "strip_inner": True,
            },
            "aigcEditLaunch": {
                "anchor": "tag_video_edit_download_loading",
                "expect": r"^invoke\(Ljava/lang/Object;\)Ljava/lang/Object;$",
                "reject": r"global_edit",  # capa.video_edit 系为真身，global_edit 副本不取
            },
            "downloaderEntry": {
                "anchor": "addDownloadTask",
                "expect": r"^e\(\S+?;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;JLjava/lang/String;JZZL\S+?/Function3;ILjava/lang/Object;\)V$",
                "static": True,  # 9.51 坑：同名 SuspendLambda 复用，真身必 static（首参 receiver）
            },
            "albumCopy.videoStore": {
                "anchor": "saveVideo start copy file.",
                "expect": r"^a\(\S+?;Ljava/lang/String;Ljava/lang/String;Landroid/net/Uri;Z\)V$",
                "static": True,
            },
        },
    },
    "com.hihonor.appmarket": {
        "alias": "appmarket",
        "default_apk": "mt://installed-app/com.hihonor.appmarket",
        "emit": "points",
        "points": {
            "market:notif.notifyMultiIcon": {
                "anchor": "notifyAppUpdateMultiIcon: throwable",
                "method": "B", "paramCount": 4, "returnType": "boolean",
            },
            "market:notif.notifyQueueStyle": {
                "anchor": "notifyAppUpdateAndSilentIconQueueButtonStyle: throwable",
                "method": "C", "paramCount": 2, "returnType": "boolean",
            },
            "market:notif.createNotify": {
                "anchor": "createNotify notifyType = ",
                "method": "D", "paramCount": 3, "returnType": "boolean",
            },
        },
    },
    "com.hihonor.packageinstaller": {
        "alias": "installer",
        # 本机实证只有 AOSP 包（InstallerHook 判据挂 com.android.packageinstaller）
        "default_apk": "mt://installed-app/com.android.packageinstaller",
        "emit": "points",
        "points": {
            # 坐标种子 = InstallerMap_14_0_38_301.kt（真机判据表）；22_326 表无真机不投影
            "installer:CommonConfig.isNewTaskStartInstallBySourcePkg": {
                "anchor": "isNewTaskStartInstallBySourcePkg",
                "method": "e", "paramCount": 1, "returnType": "boolean",
            },
            "installer:CommonConfig.getSafeModeConfigJsonFromRsp": {
                "anchor": "initGetSafeModeConfig",
                "method": "c", "paramCount": 1, "returnType": "java.lang.String",
            },
            "installer:SafeModeDialogGate.isShowGuideOpenSafeModeDialog": {
                # 注意 38_301 表真身是 A1；is0 是 22_326 旧表的坐标（勿混）
                "anchor": "input params not available. triggerType = ",
                "method": "A1", "paramCount": 4, "returnType": "boolean",
            },
        },
    },
    "com.hihonor.systemmanager": {
        "alias": "sysmgr",
        "default_apk": "mt://installed-app/com.hihonor.systemmanager",
        "emit": "points",
        "points": {
            "sysmgr:AntivirusTools.hideVirusPrefer": {
                "anchor": "msc.config.hide_virus",
                "method": "hideVirusPrefer", "paramCount": 0, "returnType": "boolean",
            },
            "sysmgr:CheckUninstallApkCaller.scanClean": {
                # 2026-11-12 实证真串：'checkUninstallApk called by sub user, skip...'（原种子 UninstallApk 多个 d 是笔误）
                "anchor": "called by sub user",
                "method": "call", "paramCount": 1, "returnType": "android.os.Bundle",
            },
            "sysmgr:VirusNotifyService.noDialog": {
                "anchor": "onStartCommand virus notify pkg: ",
                "method": "onStartCommand", "paramCount": 3, "returnType": "int",
            },
        },
    },
    "com.android.settings": {
        "alias": "settings",
        "default_apk": "mt://installed-app/com.android.settings",
        "emit": "points",
        "points": {
            # 坐标种子 = SettingsHook.kt（宿主 14.0.0.210；2026-11-12 S1 签名 Q3()V→Q3(Object)V 已修表）
            "settings:AppDetails.enableDisableButton": {
                "anchor": "com.sohu.inputmethod.sogou.honor",
                "method": "Q3", "paramCount": 1, "returnType": "void",
            },
            "settings:hwidTile.badge": {
                "anchor": "data.badge is false",
                # 2026-11-12 实证：消费点从 u#g 漂成 dashboard.v#h（g 是 synthetic bridge，真身 h(v$d)V）
                "method": "h", "paramCount": 1, "returnType": "void",
            },
        },
    },
    "theme": {
        "alias": "theme",
        "default_apk": "mt://installed-app/com.hihonor.android.thememanager",
        "emit": "points",
        "points": {
            # className=None 的点表里走 DexKit 运行期自定位，本工具只验锚活性与签名形状
            "theme:TryOut.isValidTheme": {
                "anchor": "not register theme ware",
                "method": "isValidTheme", "paramCount": 0, "returnType": "boolean",
            },
            "theme:TryOut.checkCurrentTheme": {
                "anchor": "isFileExists",
                "method": "checkCurrentTheme", "paramCount": 0, "returnType": "boolean",
                # isFileExists 是通用工具方法名，锚弱：命中多处会走冲突清单，人工裁决
            },
            "theme:WallpaperPreview.fixPackagePath": {
                "anchor": "applyLiveWallpaper start",
                "method": "applyLiveWallpaperPrepare", "paramCount": 0, "returnType": "void",
            },
            "theme:WallpaperPreview.decryptPaidWp": {
                "anchor": "applyWallpaper path = ",
                "method": "createApplyWallPaper", "paramCount": 1, "returnType": "void",
            },
            "cloud:ShareprefUtils.getBoolean1": {
                "anchor": "themename",
                "method": "getBoolean", "paramCount": 1, "returnType": "boolean",
                # themename 串过短且多处出现，预期进冲突清单
            },
            "cloud:ShareprefUtils.getBoolean2": {
                "anchor": "themename",
                "method": "getBoolean", "paramCount": 2, "returnType": "boolean",
            },
        },
    },
}

ALIAS = {v["alias"]: k for k, v in REGISTRIES.items()}

# ---------------------------------------------------------------- 引擎


def mt(*args):
    """调 mtmcp.py 子命令，返回 stdout；失败抛异常。"""
    r = subprocess.run([sys.executable, MTMCPP, *args],
                       capture_output=True, text=True, timeout=300)
    if r.returncode != 0:
        raise RuntimeError(f"mtmcp {' '.join(args[:2])} 失败: {r.stderr.strip()[:200]}")
    return r.stdout


def outline_class(apk, cls):
    """outline 一类 → (methods=[签名...], access={签名: access 串})。
    mtmcp outline 文本形态：`  name | access | sig`（翻页已由 mtmcp.py 兜底）。"""
    out = mt("outline", apk, cls)
    methods, access = [], {}
    for line in out.splitlines():
        m = re.match(r"\s+\S+ \| (.+?) \| (\S+)\s*$", line)
        if m:
            methods.append(m.group(2))
            access[m.group(2)] = m.group(1)
    return methods, access


TYPE_TOKEN = re.compile(r"\[*L[^;]+;|\[*[BCDFIJSZ]")


def arg_count(sig):
    """smali 签名的顶层参数个数：'(参数类型…)返回类型'，类型可带数组前缀。"""
    i = sig.find("(")
    j = sig.rfind(")")
    if i < 0 or j < i:
        return -1
    return len(TYPE_TOKEN.findall(sig[i + 1:j]))


def rt_smali(rt):
    """java 返回类型名 → smali 描述符（HookPoint 表语义 vs outline sig 形态）。"""
    return {"void": "V", "boolean": "Z", "int": "I", "long": "J",
            "short": "S", "byte": "B", "char": "C", "float": "F", "double": "D",
            }.get(rt, "L%s;" % rt.replace(".", "/"))


def expect_from(spec):
    """HookPoint 风格 spec → (名字, 顶层参数个数, 返回类型后缀)。"""
    return spec.get("method"), spec.get("paramCount"), spec.get("returnType")


def resolve_point(apk, pid, spec):
    """返回 (坐标 or None, 冲突说明 or None)。坐标形如 gl9.q1（dex 描述符转 java 名）。"""
    out = mt("strings", apk, spec["anchor"])
    # mtmcp strings 文本形态：每行一个 locator（dex_class:Lxx/yy;）
    classes = re.findall(r"dex_class:(L\S+?;)", out)
    if not classes:
        return None, f"{pid}: 锚串「{spec['anchor']}」全 dex 未见——锚失效，需人工从伪代码重找"
    names = [c[1:-1].replace("/", ".") for c in classes]
    if spec.get("strip_inner"):
        names = sorted({re.sub(r"\$[a-z0-9]+$", "", n) for n in names})
    else:
        names = sorted(set(names))

    method, pc, rt = expect_from(spec)
    matches = []
    for n in names:
        try:
            methods, access = outline_class(apk, n)
        except RuntimeError as e:
            return None, f"{pid}: outline {n} 失败: {e}"
        # reject 按 outline 全文（含构造器参数的完整包路径）否决副本——R8 短类名不带包，类名层看不到
        if spec.get("reject") and re.search(spec["reject"], "\n".join(methods)):
            continue
        for sig in methods:
            if spec.get("expect"):
                if not re.match(spec["expect"], sig):
                    continue
            else:
                mname = sig[:sig.find("(")] if "(" in sig else sig
                if mname != method:
                    continue
                if pc is not None and arg_count(sig) != pc:
                    continue
                if rt is not None and not sig.endswith(")" + rt_smali(rt)):
                    continue
            if spec.get("static") and "static" not in access.get(sig, ""):
                continue
            matches.append((n, sig))
    if not matches:
        want = spec.get("expect") or f"{method}(pc={pc}){rt or ''}"
        return None, (f"{pid}: 锚命中 {names} 但无一方法匹配期望签名 {want}"
                      f"——签名漂移，需人工核对 outline")
    if len(matches) > 1:
        return None, f"{pid}: 多方法匹配 {matches}——需人工裁决"
    n, sig = matches[0]
    return f"{n}  # {sig[:70]}", None


# ---------------------------------------------------------------- 输出

XHS_ORDER = ["saveClick", "videoClick", "x2b", "noteSaveEntry",
             "aigcEditLaunch", "downloaderEntry", "albumCopy.h0", "albumCopy.e0",
             "albumCopy.videoStore"]


def emit_xhs(results):
    version = "?.?.?"  # 版本号由人填（mtmcp 无 version 子命令；草稿行占位）
    print(f'\n        "{version}" to mapOf(')
    for pid in XHS_ORDER:
        v = results.get(pid)
        if pid in ("videoClick", "albumCopy.h0", "albumCopy.e0"):
            print(f'            "{pid}" to null,   # 仍无家（勿回落基线）' if v is None
                  else f'            "{pid}" to "{v}",')
        elif v:
            print(f'            "{pid}" to "{v}",')
        else:
            print(f'            "{pid}" to null,   // ⚠ 未定位，见冲突清单')
    print("        ),")


def emit_points(key, results):
    print(f"\n// {key} 覆盖行草稿（className 换新名；method 未漂移则只核坐标）")
    for pid, v in results.items():
        if v:
            print(f"  {pid}  →  {v}")
        else:
            print(f"  {pid}  →  ⚠ 未定位，见冲突清单")


def main():
    key = sys.argv[1] if len(sys.argv) > 1 else ""
    key = ALIAS.get(key, key)
    reg = REGISTRIES.get(key)
    if reg is None:
        sys.stderr.write(f"未知目标 {key!r}；可用：{', '.join(sorted(ALIAS))}\n")
        sys.exit(2)
    apk = sys.argv[2] if len(sys.argv) > 2 else reg["default_apk"]
    results, conflicts = {}, []
    for pid, spec in reg["points"].items():
        sys.stderr.write(f"[anchor-adapt] {pid}: 搜「{spec['anchor']}」…\n")
        coord, conflict = resolve_point(apk, pid, spec)
        if conflict:
            conflicts.append(conflict)
        results[pid] = coord
    if reg["emit"] == "xhs":
        emit_xhs(results)
    else:
        emit_points(key, results)
    if conflicts:
        print("\n== 冲突清单（人工处理）==", file=sys.stderr)
        for c in conflicts:
            print(c, file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
