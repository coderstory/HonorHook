# 逆向分析：成人高考准题库（cn.wangxiao.ckzhuntiku）v5.40

> 范围：判定该 app 的「会员/搜题特权」逻辑是否可被客户端 hook 解锁。
> 方法：apk-reverse 技能 gate 流程（G1–G3）。结论带可信度标签：`observed`=已跑命令取证；`inferred`=由观察推得；`unverified`=假设。

## G1 · 交付物定义（带边界）

**交付句**：一份分析报告，说明该 app 会员态是「客户端可强制」还是「服务端权威」，以及给定 `libdexvmp` DEX 虚拟化下是否存在可行的 hook 路线——**基于结构化校验的运行时 dump，而非磁盘加密 dex**。

**形式**：带边界的分析报告（技能四种形式之四）。原因：静态 dex 加密、无落盘真 dex，且校验类方法极可能被虚拟化，repack 路线在 G1 决策触发器（c）"方法体解码为私有 opcode"已触发即视为阻断。

## G2 · 环境真相（observed，doctor.py --json）

| 能力 | 状态 | 说明 |
|---|---|---|
| `frida_dynamic` / `frida_rpc` | **reduced** | 缺 `frida-cli` + 设备 `frida-server` + `device_root` 未识别 |
| `static_native` / `dart_aot` | reduced | 缺 python `capstone` |
| `repack_signed` | reduced | 缺 java / jarsigner / apksigner / zipalign / keytool |
| `dex_index` (droidasc/rasc) | reduced | 工具未安装 |
| adb / jadx | **可用但未入 skill PATH** | 本项目 `tools/platform-tools/adb.exe`、`tools/jadx/` 均存在 |

结论：本机**无 frida** → 技能的内存 dump 脚本（`dex_mem_scan.py`→`dex_dump_validate.py`）当前不可用。这是**环境缺口，不是 app 阻断**。

## G3 · 代码定位（observed）

| 证据 | 结论 |
|---|---|
| `unzip` 仅 1 个 `classes.dex`（16.6MB）；`strings -n 6 classes.dex \| wc -l` = **0** | dex 数据区**整体加密**，静态分析失效 |
| `libDexHelper.so` + `libdexvmp.so`（另有 `libDexHelper-x86.so`） | **DEX 虚拟化双壳**；方法字节码可能转 native VM 执行 |
| `su` 下 `find /data/data/cn.wangxiao.ckzhuntiku -iname "*.dex\|*.jar\|*.vmp\|*.oat\|*.odex"` **全空** | 壳**纯内存解密**，无落盘真 dex |
| `dex_strings.py` 跑加密 dex，仅吐 `DexHelper`/`DexHelper-x86`/`SOPHIX`/`app_process`/`/proc/self/exe`/`ARM_LIBRARY` | 磁盘 dex 只有**加载桩明文**，业务代码全密 |
| `libobjectbox-jni.so`（ObjectBox）、`libtess`/`liblept`（Tesseract OCR）、`libmsc`（讯飞）、`libliteavsdk`/`agora`/`lecloud` | 拍照搜题/语音搜题/音视频课程功能库齐全 |
| UI 会员入口（`tools/dev.sh ui`）：「搜题特权 / 立即开通 / 购买题库可解锁全部做题权限 / 激活码」 | 与 ujigu 同构的「搜题特权」会员模型 |

**inferred**：会员/激活校验逻辑极可能被 `libdexvmp` 虚拟化（加固厂商默认虚拟化授权代码）→ 虚拟化方法跑在 native VM，**不是 ART 方法，LSPosed/Xposed 无法替换**。

## 边界（诚实结论）

1. 静态 + 落盘两条恢复路线**均已穷尽**（observed 空）。
2. 要定位会员模型并判定 client/server 权限，**必须运行时内存 dump**。当前 frida 缺失，且即便 dump 成功，虚拟化方法在 dex 里仍是桩（`dex_dump_validate.py` 量 stub% 即可证）。
3. 因此本报告**止于「壳已识别、业务代码静态不可达」**这一边界。**未判定**会员是否客户端可强制——那需要下一步。

## 下一步（二选一，均需用户拍板）

- **路线 A（技能原生）**：安装 frida（本机 `frida-cli` + 设备 arm64 `frida-server`，KernelSU root 可用）→ `dex_mem_scan.py` 内存 dump → `dex_dump_validate.py` 量 stub% → 若低则定位会员 bean。
- **路线 B（frida-free，复用现有基建）**：在 HonorHook 模块给该包加临时探针，进程内从 `BaseDexClassLoader` 反射 dump 已解密 dex 字节（非虚拟化方法可恢复；虚拟化方法仍为桩）。

拿到 dex 后，按 `membership-and-limits.md` / `account-gates.md` 判定：会员态是本地 bean（可反射强制，同 exam8）还是服务端权威（只能网络层注入 `isVip`）。

## 已知资产

- `.buildtmp/wangxiao/base.apk`（86MB）、`.buildtmp/wangxiao/classes.dex`（16.6MB，加密）。
- 待补：frida 安装或 LSPosed 探针实现。
