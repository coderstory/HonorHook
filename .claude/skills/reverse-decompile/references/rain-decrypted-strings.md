<!-- 归档：system-files/rain-strings-decrypted.md → skill references（2026-10-10 md 重构） -->
<!-- 口径提示：正文写于 Windows 期/早期调研阶段，Windows 路径（C:\ / .bat / tools/jdk-21）与已删目录（.buildtmp/、system-files/*-apktool 等）可能失效；结论与方法论仍有效。 -->

# Rain APK 加密字符串全量解密清单

> 生成时间：2026-10-09 12:04　工具：`tools/rain-decrypt.py`　材料：`system-files/rain-apktool/smali/`

> 算法：`chr((cipher[start+i] ^ key) & 0xFFFF)`，见 `docs/rain-hook-architecture.md` §4。Family B（np/*）的 start/len/key 为 `const-string.hashCode()` + 掩码算术合成，本工具按运行时语义忠实模拟（确定性求值）。

## 0. 统计

| 项 | 值 |
|---|---|

| smali 类文件 | 3291 |

| 解密器副本（`([SIII)Ljava/lang/String;` 声明） | 158（分布在 158 个类） |

| 解密调用点（invoke-static） | 43 |

| —— Family A（明文 const 参数） | 16 |

| —— Family B（hashCode+掩码，np/* 层） | 27 |

| 解密成功 | **43** |

| 诱饵（Math.random 解密器） | 0 |

| 未解出（原因见 §4） | 0 |


验证锚点：`rain_module` / `enable_debug_logging` / `不要用低质小模块喵`（与 `docs/rain-hook-architecture.md` §4.1 表格一致，下表中标 ⭐）。


## 1. 解密串对照表（按文件分组）


### `system-files/rain-apktool/smali/love/nairain/hook/MainActivity.smali`

| 行号 | 调用点 | start/len/key | 解密串（转义） | 原文 | 判定 |
|---|---|---|---|---|---|

| 161 | `Landroidx/startup/۟ۦۡۨۥ->ۧۦۣ۠` | 0/9/2787 | `\u4e0d\u8981\u7528\u4f4e\u8d28\u5c0f\u6a21\u5757\u55b5` | 不要用低质小模块喵 | 中文文案样 ⭐ |


### `system-files/rain-apktool/smali/love/nairain/hook/ModuleApplication.smali`

| 行号 | 调用点 | start/len/key | 解密串（转义） | 原文 | 判定 |
|---|---|---|---|---|---|

| 52 | `Landroidx/profileinstaller/ۡۥ۠۠->ۣ۟۠ۤۦ` | 0/16/607 | `Hook-Application` | Hook-Application | 键/文件名字样 |

| 398 | `Landroidx/graphics/path/۟ۧۤۡۨ->ۣۨۧ۠` | 16/9/518 | `\u4e0d\u8981\u7528\u4f4e\u8d28\u5c0f\u6a21\u5757\u55b5` | 不要用低质小模块喵 | 中文文案样 ⭐ |

| 708 | `Landroid/support/v4/app/ۦۥۢۦ->ۦۤۢۧ` | 25/16/1722 | `Hook-Application` | Hook-Application | 键/文件名字样 |

| 720 | `L۟ۢۢۢ->۟۟ۤ۟ۧ` | 41/35/2086 | `Unable to read Xposed service state` | Unable to read Xposed service state | 一般字符串 |

| 961 | `Landroidx/lifecycle/ۡۢۤ۠->ۧۢۥ۟` | 76/34/2256 | `Unable to remove module scope for ` | Unable to remove module scope for  | 一般字符串 |

| 981 | `Landroidx/graphics/path/۟ۡۢۦۤ->ۣ۠ۧ۠` | 110/16/612 | `Hook-Application` | Hook-Application | 键/文件名字样 |

| 1140 | `Landroid/view/ۣ۟۟ۤۢ->ۤۢ۠۟` | 126/35/2280 | `Unable to request module scope for ` | Unable to request module scope for  | 一般字符串 |

| 1160 | `Landroid/window/ۤۢۤ۠->۠ۨ۠ۡ` | 161/16/3089 | `Hook-Application` | Hook-Application | 键/文件名字样 |

| 2237 | `Lorg/luckypray/dexkit/util/ۣ۟ۡ۟۠->ۥۣ۠ۧ` | 177/19/2726 | `XposedServiceHelper` | XposedServiceHelper | 键/文件名字样 |

| 2249 | `Landroidx/core/graphics/drawable/ۤ۟۠ۥ->ۥۣۧۤ` | 196/16/2376 | `registerListener` | registerListener | 键/文件名字样 |

| 2969 | `Landroid/view/translation/۟ۡۨۥ->۟ۧۧۢۥ` | 212/16/2916 | `Hook-Application` | Hook-Application | 键/文件名字样 |

| 2981 | `Landroidx/lifecycle/ۧۨ۟ۤ->۟ۡ۠ۤۡ` | 228/27/3206 | `Unable to read module scope` | Unable to read module scope | 一般字符串 |


### `system-files/rain-apktool/smali/love/nairain/hook/xposed/HookEntry.smali`

| 行号 | 调用点 | start/len/key | 解密串（转义） | 原文 | 判定 |
|---|---|---|---|---|---|

| 128 | `Landroidx/profileinstaller/۟ۥ۠ۢ->۟ۧۥ۟ۧ` | 0/9/2173 | `\u4e0d\u8981\u7528\u4f4e\u8d28\u5c0f\u6a21\u5757\u55b5` | 不要用低质小模块喵 | 中文文案样 ⭐ |

| 212 | `Landroidx/versionedparcelable/ۦۢۦ->۟ۧۦ۟ۤ` | 9/11/862 | `rain_module` | rain_module | 键/文件名字样 ⭐ |

| 230 | `Lorg/luckypray/dexkit/۟۠ۢۨۤ->ۨ۟ۡۤ` | 20/20/1860 | `enable_debug_logging` | enable_debug_logging | 键/文件名字样 ⭐ |


### `system-files/rain-apktool/smali/np/̅.smali`

| 行号 | 调用点 | start/len/key | 解密串（转义） | 原文 | 判定 |
|---|---|---|---|---|---|

| 254 | `Lnp/̅->̝` | 0/3/1337 | `lib` | lib | 键/文件名字样 |

| 320 | `Lnp/̅->̝` | 3/11/1705 | `libsandhook` | libsandhook | 键/文件名字样 |

| 382 | `Lnp/̅->̝` | 14/6/2271 | `libarm` | libarm | 键/文件名字样 |

| 444 | `Lnp/̅->̝` | 20/9/1144 | `libmthook` | libmthook | 键/文件名字样 |

| 506 | `Lnp/̅->̝` | 29/6/2231 | `libSub` | libSub | 键/文件名字样 |

| 1192 | `Lnp/̅->̝` | 35/18/1143 | `com.swift.sandhook` | com.swift.sandhook | 包名/路径样 |

| 1252 | `Lnp/̅->̝` | 53/3/1100 | `arm` | arm | 键/文件名字样 |

| 1316 | `Lnp/̅->̝` | 56/8/3087 | `SandHook` | SandHook | 键/文件名字样 |

| 1384 | `Lnp/̅->̝` | 64/21/663 | `nativehook.NativeHook` | nativehook.NativeHook | 键/文件名字样 |

| 1452 | `Lnp/̅->̝` | 85/17/1942 | `com.bug.fuck.fuck` | com.bug.fuck.fuck | 包名/路径样 |

| 1552 | `Lnp/̅->̝` | 102/1/599 | `.` | . | 数值/格式样 |

| 1787 | `Lnp/̅->̝` | 103/10/2729 | `.SignerPro` | .SignerPro | 一般字符串 |


### `system-files/rain-apktool/smali/np/̍.smali`

| 行号 | 调用点 | start/len/key | 解密串（转义） | 原文 | 判定 |
|---|---|---|---|---|---|

| 303 | `Lnp/̅->̝` | 0/32/1775 | `android.content.pm.PackageParser` | android.content.pm.PackageParser | 键/文件名字样 |

| 408 | `Lnp/̅->̝` | 160/16/1500 | `getAPKSignatures` | getAPKSignatures | 键/文件名字样 |

| 610 | `Lnp/̅->̝` | 32/12/1475 | `parsePackage` | parsePackage | 键/文件名字样 |

| 784 | `Lnp/̅->̝` | 44/19/2437 | `collectCertificates` | collectCertificates | 键/文件名字样 |

| 945 | `Lnp/̅->̝` | 63/15/2489 | `mSigningDetails` | mSigningDetails | 键/文件名字样 |

| 1021 | `Lnp/̅->̝` | 78/10/1730 | `signatures` | signatures | 键/文件名字样 |

| 1151 | `Lnp/̅->̝` | 88/19/2836 | `collectCertificates` | collectCertificates | 键/文件名字样 |

| 1282 | `Lnp/̅->̝` | 107/11/1673 | `mSignatures` | mSignatures | 键/文件名字样 |

| 1409 | `Lnp/̅->̝` | 118/12/2779 | `parsePackage` | parsePackage | 键/文件名字样 |

| 1551 | `Lnp/̅->̝` | 130/19/2888 | `collectCertificates` | collectCertificates | 键/文件名字样 |

| 1678 | `Lnp/̅->̝` | 149/11/2043 | `mSignatures` | mSignatures | 键/文件名字样 |


### `system-files/rain-apktool/smali/np/̐.smali`

| 行号 | 调用点 | start/len/key | 解密串（转义） | 原文 | 判定 |
|---|---|---|---|---|---|

| 521 | `Lnp/̅->̝` | 0/12/503 | `YmFzZS5hcGs=` | YmFzZS5hcGs= | Base64 → `base.apk` |

| 797 | `Lnp/̅->̝` | 12/16/2119 | `L2RhdGEvYXBwLw==` | L2RhdGEvYXBwLw== | Base64 → `/data/app/` |

| 895 | `Lnp/̅->̝` | 28/1/2176 | `/` | / | 数值/格式样 |


### `system-files/rain-apktool/smali/np/̒.smali`

| 行号 | 调用点 | start/len/key | 解密串（转义） | 原文 | 判定 |
|---|---|---|---|---|---|

| 395 | `Lnp/̅->̝` | 0/3/3240 | `MD5` | MD5 | 键/文件名字样 |


## 2. 与 hook 目标相关的线索（重点）

| 位置 | 解密串 | 判定 |
|---|---|---|

| `system-files/rain-apktool/smali/love/nairain/hook/ModuleApplication.smali:2249` | `registerListener` | 方法/字段名样 |

| `system-files/rain-apktool/smali/love/nairain/hook/xposed/HookEntry.smali:212` | `rain_module` | 方法/字段名样 |

| `system-files/rain-apktool/smali/love/nairain/hook/xposed/HookEntry.smali:230` | `enable_debug_logging` | 方法/字段名样 |

| `system-files/rain-apktool/smali/np/̅.smali:254` | `lib` | 方法/字段名样 |

| `system-files/rain-apktool/smali/np/̅.smali:320` | `libsandhook` | 方法/字段名样 |

| `system-files/rain-apktool/smali/np/̅.smali:382` | `libarm` | 方法/字段名样 |

| `system-files/rain-apktool/smali/np/̅.smali:444` | `libmthook` | 方法/字段名样 |

| `system-files/rain-apktool/smali/np/̅.smali:506` | `libSub` | 方法/字段名样 |

| `system-files/rain-apktool/smali/np/̅.smali:1252` | `arm` | 方法/字段名样 |

| `system-files/rain-apktool/smali/np/̍.smali:408` | `getAPKSignatures` | 方法/字段名样 |

| `system-files/rain-apktool/smali/np/̍.smali:610` | `parsePackage` | 方法/字段名样 |

| `system-files/rain-apktool/smali/np/̍.smali:784` | `collectCertificates` | 方法/字段名样 |

| `system-files/rain-apktool/smali/np/̍.smali:945` | `mSigningDetails` | 方法/字段名样 |

| `system-files/rain-apktool/smali/np/̍.smali:1021` | `signatures` | 方法/字段名样 |

| `system-files/rain-apktool/smali/np/̍.smali:1151` | `collectCertificates` | 方法/字段名样 |

| `system-files/rain-apktool/smali/np/̍.smali:1282` | `mSignatures` | 方法/字段名样 |

| `system-files/rain-apktool/smali/np/̍.smali:1409` | `parsePackage` | 方法/字段名样 |

| `system-files/rain-apktool/smali/np/̍.smali:1551` | `collectCertificates` | 方法/字段名样 |

| `system-files/rain-apktool/smali/np/̍.smali:1678` | `mSignatures` | 方法/字段名样 |


**结构性结论（重要）**：全部 43 处解密调用点只分布在 7 个类 —— 模块自身框架层
（`love/nairain/hook/*` 3 类 + `np/*` 4 类）。20 个目标 app 的适配器类（`x00`/`tc`/`rz1`/
`me0` 等，见 docs §1.2）**没有任何字符串解密调用** —— hook 目标类名/方法名/字段名全部是
**明文 const-string**（如 docs §2.3 的 `isAnyVip`、`VipInfoModel`、`com.dragon.read`，v00.java
直接可读，DexKit 查询特征无需先解密）。字符串加密只用于保护模块自身的：反篡改文案、
模块偏好文件名、文件系统路径、URI、MD5 算法名等运行时自引用串。


## 3. 复现

```
python tools/rain-decrypt.py
```
